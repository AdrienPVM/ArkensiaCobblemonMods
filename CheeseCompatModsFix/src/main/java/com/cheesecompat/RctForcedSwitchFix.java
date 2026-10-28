package com.cheesecompat;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.ShowdownActionRequest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watchdog "force-choice" pour les combats Cobblemon NPC figés.
 *
 * <h2>Bug ciblé (déterministe)</h2>
 * En combat, quand plusieurs Pokémon faint <b>au même tick</b> (Perish Song /
 * Requiem côté Rad Gyms, double-KO 2v2 coop côté Battle Tower), le moteur
 * Cobblemon/Showdown émet une requête de choix ({@code |request|}, en pratique
 * un <i>forceSwitch</i>) pour un acteur, mais le routage de cette requête vers
 * l'acteur NON-joueur (IA RCT / {@link AIBattleActor}) ne déclenche jamais
 * {@code AIBattleActor.onChoiceRequested()}. Résultat : l'acteur NPC garde un
 * {@code request != null} avec {@code responses} vide et {@code mustChoose}
 * éventuellement faux — {@code PokemonBattle.checkForInputDispatch()} attend une
 * réponse qui n'arrive jamais → combat FIGÉ.
 *
 * <h2>API Cobblemon 1.6.1 utilisée (décompilée depuis le jar)</h2>
 * <ul>
 *   <li>{@code BattleRegistry.battleMap} (private static ConcurrentHashMap&lt;UUID,PokemonBattle&gt;) — itéré par réflexion (pas de getter public).</li>
 *   <li>{@code PokemonBattle.getStarted()/getEnded()/getActors()} — état + acteurs.</li>
 *   <li>{@code BattleActor.getType()} → {@link ActorType} (WILD/PLAYER/NPC) — distingue le NPC du joueur.</li>
 *   <li>{@code BattleActor.getRequest()} → {@link ShowdownActionRequest} (request en attente, {@code getWait()}, {@code getActive()}, {@code getForceSwitch()}).</li>
 *   <li>{@code BattleActor.getResponses()} (vide ⇒ pas encore répondu) et {@code getMustChoose()}.</li>
 *   <li><b>Déblocage</b> : {@code AIBattleActor.onChoiceRequested()} — méthode PUBLIQUE qui rejoue
 *       la logique de choix officielle de Cobblemon (IA RCT puis fallback
 *       {@code PassActionResponse} sur {@code IllegalActionChoiceException}) et appelle
 *       {@code setActionResponses(...)}. C'est exactement le point d'entrée que le moteur
 *       aurait dû déclencher. On le rejoue donc nous-mêmes, sur le thread serveur.</li>
 * </ul>
 *
 * <h2>Stratégie</h2>
 * <ol>
 *   <li><b>Thread serveur uniquement</b> : {@link ServerTickEvents#END_SERVER_TICK}. Le moteur
 *       de combat est tické sur ce thread, donc aucun accès concurrent.</li>
 *   <li><b>Détection</b> : pour chaque combat démarré et non terminé, on cherche un acteur NON-joueur
 *       (type != PLAYER) qui est un {@link AIBattleActor}, possède un {@code request != null}
 *       (et non {@code wait}), avec {@code responses} vide.</li>
 *   <li><b>Debounce</b> : un tel acteur n'est forcé qu'après être resté figé {@value #STALL_TICKS}
 *       ticks d'affilée (~3 s). Cela évite de court-circuiter le flux normal (l'IA répond
 *       habituellement dans le même tick ou le suivant).</li>
 *   <li><b>Idempotence</b> : on force une requête au plus UNE fois (clé =
 *       battleId + identityHash de l'objet request). Si le moteur ré-émet un NOUVEAU request,
 *       la clé change et on pourra re-forcer ; on ne boucle jamais sur le même request.</li>
 *   <li><b>Fail-open</b> : toute exception/incertitude ⇒ log warn + no-op. Le watchdog ne peut
 *       jamais crasher un combat ni le serveur. Pire cas = on ne fait rien.</li>
 * </ol>
 *
 * <h2>Niveau de certitude</h2>
 * La <i>compilation</i> et la sûreté (fail-open, thread serveur, idempotence) sont certaines.
 * L'<i>efficacité réelle</i> du déblocage dépend de l'hypothèse — fortement étayée par la
 * décompilation — que rejouer {@code onChoiceRequested()} sur l'acteur figé suffit à peupler
 * {@code responses} puis à laisser {@code checkForInputDispatch()} continuer. À valider en jeu
 * (voir rapport). Si {@code onChoiceRequested()} ne suffit pas dans certains cas de double-KO,
 * le pire scénario reste un no-op (combat déjà figé) — on n'aggrave rien.
 */
public final class RctForcedSwitchFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompat");

    /** Nombre de ticks consécutifs où un acteur NPC doit rester figé avant de forcer (~3 s à 20 tps). */
    private static final int STALL_TICKS = 60;

    /** Scrute les combats toutes les N ticks (le tracking de stall reste précis car on garde un compteur). */
    private static final int SCAN_INTERVAL = 10;

    /** Réflexion (lazy) vers BattleRegistry.battleMap. */
    private static Field battleMapField;
    private static Object battleRegistryInstance;
    private static boolean reflectionFailed = false;

    /**
     * Compteur de stall par acteur figé. Clé = battleId#actorUuid#identityHash(request).
     * Remis à zéro / purgé dès que la condition figée disparaît.
     */
    private static final Map<String, Integer> stallTicks = new ConcurrentHashMap<>();

    /**
     * Requests déjà forcées (idempotence). Même clé que {@link #stallTicks}. Un request donné
     * (instance) n'est forcé qu'une fois ; un NOUVEAU request (autre identityHash) repart à neuf.
     */
    private static final Map<String, Boolean> forcedRequests = new ConcurrentHashMap<>();

    private RctForcedSwitchFix() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                if (server.getTicks() % SCAN_INTERVAL != 0) return;
                scan();
            } catch (Throwable t) {
                // Fail-open absolu : jamais rien laisser remonter dans la boucle de tick serveur.
                LOGGER.warn("[CheeseCompat] RctForcedSwitchFix scan failed (ignored): {}", t.toString());
            }
        });
        LOGGER.info("[CheeseCompat] RCT forced-switch watchdog REGISTERED " +
                "(unsticks NPC battles after {} stalled ticks; e.g. Perish Song / double-KO)", STALL_TICKS);
    }

    // ── Scan ────────────────────────────────────────────────────────────────────

    private static void scan() {
        Map<UUID, PokemonBattle> battles = getBattleMap();
        if (battles == null || battles.isEmpty()) {
            if (!stallTicks.isEmpty()) stallTicks.clear();
            if (!forcedRequests.isEmpty()) forcedRequests.clear();
            return;
        }

        // Clés vues ce scan, pour purger les compteurs/idempotence des combats/acteurs résolus.
        Map<String, Boolean> seen = new HashMap<>();

        for (PokemonBattle battle : battles.values()) {
            try {
                if (battle == null) continue;
                // Ignore les combats non démarrés ou terminés : pas de request à honorer.
                if (!battle.getStarted() || battle.getEnded()) continue;

                for (BattleActor actor : battle.getActors()) {
                    try {
                        if (!isStuckNpcActor(actor)) continue;

                        ShowdownActionRequest request = actor.getRequest();
                        String key = stallKey(battle, actor, request);
                        seen.put(key, Boolean.TRUE);

                        // Déjà forcé pour CE request précis → ne pas reboucler.
                        if (forcedRequests.containsKey(key)) continue;

                        int ticks = stallTicks.getOrDefault(key, 0) + SCAN_INTERVAL;
                        if (ticks < STALL_TICKS) {
                            stallTicks.put(key, ticks);
                            continue;
                        }

                        // Seuil atteint → on force, une seule fois.
                        forceChoice(battle, actor, key);
                    } catch (Throwable perActor) {
                        LOGGER.warn("[CheeseCompat] RctForcedSwitchFix actor check failed (ignored): {}",
                                perActor.toString());
                    }
                }
            } catch (Throwable perBattle) {
                LOGGER.warn("[CheeseCompat] RctForcedSwitchFix battle check failed (ignored): {}",
                        perBattle.toString());
            }
        }

        // Purge : tout compteur/flag d'une situation qui n'est plus figée est supprimé,
        // pour libérer la mémoire ET permettre un futur re-déclenchement propre.
        stallTicks.keySet().removeIf(k -> !seen.containsKey(k));
        forcedRequests.keySet().removeIf(k -> !seen.containsKey(k));
    }

    /**
     * Vrai si l'acteur est un NPC/IA figé : type != PLAYER, c'est un {@link AIBattleActor},
     * il a un request actif (non {@code wait}) et n'a pas encore produit de réponse.
     */
    private static boolean isStuckNpcActor(BattleActor actor) {
        if (actor == null) return false;

        // 1. Doit être non-joueur. ActorType : WILD / PLAYER / NPC.
        ActorType type = actor.getType();
        if (type == ActorType.PLAYER) return false;

        // 2. Doit être une IA capable de (re)choisir via onChoiceRequested().
        if (!(actor instanceof AIBattleActor)) return false;

        // 3. Doit avoir une requête en attente, non "wait" (un acteur en wait n'a rien à choisir).
        ShowdownActionRequest request = actor.getRequest();
        if (request == null) return false;
        if (request.getWait()) return false;

        // 4. N'a pas encore répondu : responses vide ⇒ le moteur attend toujours.
        java.util.List<?> responses = actor.getResponses();
        if (responses != null && !responses.isEmpty()) return false;

        return true;
    }

    /**
     * Rejoue la logique de choix officielle de Cobblemon sur l'acteur figé.
     * {@code AIBattleActor.onChoiceRequested()} calcule des réponses via l'IA RCT et, en cas
     * d'{@code IllegalActionChoiceException}, retombe sur des {@code PassActionResponse}, puis
     * appelle {@code setActionResponses(...)}. C'est précisément l'action que le moteur aurait dû
     * déclencher. On marque le request comme forcé (idempotence) AVANT l'appel pour ne jamais
     * reboucler même si l'appel lève.
     */
    private static void forceChoice(PokemonBattle battle, BattleActor actor, String key) {
        forcedRequests.put(key, Boolean.TRUE);
        stallTicks.remove(key);
        try {
            // NB : on n'appelle PAS actor.getName() — il renvoie un type Minecraft (class_5250)
            // absent du classpath compileOnly Cobblemon (jar non remappé). On loggue l'UUID/showdownId.
            String actorId = safe(() -> actor.getUuid().toString());

            LOGGER.warn("[CheeseCompat] STUCK NPC battle detected (battleId={}, actorUuid={}, type={}) — " +
                    "replaying onChoiceRequested() to unstick (forceSwitch={}, active={})",
                    safe(() -> battle.getBattleId().toString()),
                    actorId,
                    safe(() -> actor.getType().toString()),
                    safe(() -> String.valueOf(actor.getRequest().getForceSwitch())),
                    safe(() -> String.valueOf(actor.getRequest().getActive())));

            ((AIBattleActor) actor).onChoiceRequested();

            LOGGER.info("[CheeseCompat] onChoiceRequested() replayed for actorUuid {} — battle {} should resume",
                    actorId, safe(() -> battle.getBattleId().toString()));
        } catch (Throwable t) {
            // Fail-open : si le forçage échoue, on log et on ne retente pas ce request précis
            // (forcedRequests est déjà posé). Pire cas = no-op, on n'aggrave pas l'état.
            LOGGER.warn("[CheeseCompat] forceChoice/onChoiceRequested failed (battle left as-is): {}",
                    t.toString());
        }
    }

    // ── Réflexion vers BattleRegistry.battleMap ───────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<UUID, PokemonBattle> getBattleMap() {
        if (reflectionFailed) return null;
        try {
            if (battleMapField == null) {
                Class<?> regCls = Class.forName("com.cobblemon.mod.common.battles.BattleRegistry");
                // INSTANCE (objet Kotlin) — battleMap est static, mais on garde l'instance au cas où.
                try {
                    battleRegistryInstance = regCls.getField("INSTANCE").get(null);
                } catch (NoSuchFieldException nsf) {
                    battleRegistryInstance = null;
                }
                Field f = regCls.getDeclaredField("battleMap");
                f.setAccessible(true);
                battleMapField = f;
            }
            Object map = battleMapField.get(battleRegistryInstance); // static field → instance ignoré
            if (map instanceof Map) {
                return (Map<UUID, PokemonBattle>) map;
            }
            return null;
        } catch (Throwable t) {
            reflectionFailed = true;
            LOGGER.warn("[CheeseCompat] RctForcedSwitchFix could not access BattleRegistry.battleMap — " +
                    "watchdog DISABLED (fail-open): {}", t.toString());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static String stallKey(PokemonBattle battle, BattleActor actor, ShowdownActionRequest request) {
        // identityHash du request : si le moteur ré-émet un NOUVEAU request (nouvel objet),
        // la clé change → on pourra re-forcer. Sur le MÊME request, la clé reste stable → idempotent.
        return battle.getBattleId() + "#" + actor.getUuid() + "#" + System.identityHashCode(request);
    }

    private interface ThrowingSupplier { String get() throws Throwable; }

    /** Évalue un supplier en avalant toute exception (pour les logs uniquement). */
    private static String safe(ThrowingSupplier s) {
        try { return s.get(); } catch (Throwable t) { return "?"; }
    }
}

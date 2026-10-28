package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.ChampionEntry;
import com.cobblecap.data.PlayerCapData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Soft-dependency hook for Cobblemon XP events and BATTLE_STARTED_PRE safety net.
 * Uses reflection and a Kotlin Function1 proxy to subscribe without a compile-time dependency.
 * <p>
 * On first startup, this class logs ALL CobblemonEvents field names so the correct XP event
 * field name can be confirmed from the server log before hardcoding it for release.
 */
public class CobblemonHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    private static boolean initialized = false;
    public static boolean available = false;

    /** Server reference captured in tryInit for use in BATTLE_STARTED_PRE handler. */
    private static MinecraftServer serverRef;

    /**
     * Players currently being swapped to a challenge battle.
     * Prevents infinite loop: swap → startBattleWith → BATTLE_STARTED_PRE → swap again.
     */
    private static final Set<UUID> challengeSwapInProgress = ConcurrentHashMap.newKeySet();

    /**
     * Called from CapListener on SERVER_STARTED. Idempotent — only runs once.
     * Scans CobblemonEvents fields for diagnostic purposes, then hooks the XP event
     * and the BATTLE_STARTED_PRE safety net.
     */
    public static void tryInit(MinecraftServer server) {
        if (initialized) return;
        initialized = true;
        serverRef = server;

        try {
            Class<?> cobblemonEvents = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            available = true;

            // ── Hook XP event (battle XP) ────────────────────────────────────────────────────────────
            String hookedField = null;
            for (String candidate : new String[]{"EXPERIENCE_GAINED_EVENT_PRE", "EXP_GAINED", "EXPERIENCE_GAINED"}) {
                try {
                    hookEvent(cobblemonEvents, candidate, event -> CapManager.handleXpEvent(event));
                    hookedField = candidate;
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            if (hookedField != null) {
                LOGGER.info("[CobbleCap] Cobblemon XP hook registered on field: {}", hookedField);
            } else {
                LOGGER.warn("[CobbleCap] Could not find XP event field in CobblemonEvents — XP enforcement disabled.");
            }

            // ── Hook XP candy event (rare candy / exp candy) ─────────────────────────────────────────
            String candyField = null;
            for (String candidate : new String[]{"EXPERIENCE_CANDY_USE_PRE", "EXPERIENCE_CANDY_PRE"}) {
                try {
                    hookEvent(cobblemonEvents, candidate, event -> CapManager.handleXpEvent(event));
                    candyField = candidate;
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            if (candyField != null) {
                LOGGER.info("[CobbleCap] Cobblemon candy XP hook registered on field: {}", candyField);
            } else {
                LOGGER.warn("[CobbleCap] Could not find EXPERIENCE_CANDY_USE_PRE field — candy XP bypass possible.");
            }

            // ── Hook BATTLE_STARTED_PRE safety net (CHAMP-03) ─────────────────────
            String battlePreField = null;
            for (String candidate : new String[]{"BATTLE_STARTED_PRE", "PRE_BATTLE_START", "BATTLE_START_PRE"}) {
                try {
                    hookEvent(cobblemonEvents, candidate, event -> {
                        try {
                            if (!CobbleCapMod.INSTANCE.getCapConfig().isEnabled()) return;

                            // Find player actors in this battle
                            // Try multiple methods to get actors/participants
                            Object actors = CapManager.invokeGetter(event, "getActors", "getPlayers", "getParticipants");
                            if (actors == null) {
                                // Fallback: try getBattle() -> getActors()
                                Object battle = CapManager.invokeGetter(event, "getBattle");
                                if (battle != null) {
                                    actors = CapManager.invokeGetter(battle, "getActors", "getPlayers");
                                }
                            }

                            if (!(actors instanceof Iterable<?> iter)) return;

                            // ── Detect NPC trainer and player ──────────────────────────
                            ChampionEntry championInBattle = null;
                            boolean isChallengeBattle = false;
                            net.minecraft.entity.Entity npcEntity = null;
                            for (Object actor : iter) {
                                Object ent = CapManager.invokeGetter(actor, "getEntity", "getNPC", "entity");
                                if (ent instanceof net.minecraft.entity.Entity entity) {
                                    String rawTid = ChampionManager.extractRctmodTrainerId(entity);
                                    // Strip the per-dim suffix (Conseil4TrainerCloner) — config lookups
                                    // compare against canonical IDs only.
                                    String tid = ChampionManager.stripPerDimSuffix(rawTid);
                                    LOGGER.info("[CobbleCap] BATTLE_PRE: entity={} trainerId={} (raw={})",
                                            entity.getClass().getSimpleName(), tid, rawTid);
                                    if (tid != null) {
                                        // Check challenge variant first
                                        if (CobbleCapMod.INSTANCE.getChampionConfig().findByChallengeId(tid).isPresent()) {
                                            LOGGER.info("[CobbleCap] BATTLE_PRE: detected as CHALLENGE battle (tid={})", tid);
                                            isChallengeBattle = true;
                                            break;
                                        }
                                        Optional<ChampionEntry> co = CobbleCapMod.INSTANCE.getChampionConfig().findById(tid);
                                        if (co.isPresent()) {
                                            championInBattle = co.get();
                                            npcEntity = entity;
                                            LOGGER.info("[CobbleCap] BATTLE_PRE: detected as NORMAL champion (tid={}, name={})", tid, championInBattle.displayName());
                                            break;
                                        }
                                    }
                                }
                            }

                            // Challenge battles bypass cap checks entirely
                            if (isChallengeBattle) return;

                            // Find the player in this battle
                            UUID playerUuid = null;
                            ServerPlayerEntity battlePlayer = null;
                            for (Object actor : iter) {
                                UUID uuid = getActorPlayerUuid(actor);
                                if (uuid == null) continue;
                                ServerPlayerEntity p = serverRef.getPlayerManager().getPlayer(uuid);
                                if (p != null) { playerUuid = uuid; battlePlayer = p; break; }
                            }

                            if (championInBattle != null && battlePlayer != null) {
                                Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(playerUuid);
                                if (dataOpt.isPresent()) {
                                    PlayerCapData data = dataOpt.get();
                                    LOGGER.info("[CobbleCap] BATTLE_PRE: player={} championsBeaten={} checking champion={}", battlePlayer.getName().getString(), data.getChampionsBeaten(), championInBattle.id());

                                    if (!data.getChampionsBeaten().contains(championInBattle.id())) {
                                        // Not beaten yet — must be the next in order
                                        Optional<ChampionEntry> nextOpt = CobbleCapMod.INSTANCE.getChampionConfig()
                                                .nextUnbeaten(data.getChampionsBeaten());
                                        if (nextOpt.isPresent() && !nextOpt.get().id().equals(championInBattle.id())) {
                                            // Diagnostic: flag likely championsBeaten/cap desync so admins notice in logs.
                                            // Triggered by e.g. /cobblecap reset followed by /cobblecap set without rebuild.
                                            if (data.getCapLevel() >= championInBattle.capGranted()) {
                                                LOGGER.warn("[CobbleCap] Order-check rejection may be a desync: player={} cap={} tried={} expectedNext={} championsBeaten={}. If the player legitimately beat prior champions, repair with: /cobblecap addbeaten {} {}",
                                                    battlePlayer.getName().getString(), data.getCapLevel(),
                                                    championInBattle.id(), nextOpt.get().id(), data.getChampionsBeaten(),
                                                    battlePlayer.getName().getString(), nextOpt.get().id());
                                            }
                                            cancelBattle(event);
                                            battlePlayer.sendMessage(
                                                Text.translatable("cobblecap.msg.champion_not_next",
                                                        championInBattle.displayName(), nextOpt.get().displayName())
                                                    .formatted(Formatting.RED),
                                                false
                                            );
                                            return;
                                        }

                                        // Check pre-battle conditions (pokedex, quests)
                                        ActionResult preResult = ChampionManager.handlePreBattle(battlePlayer, championInBattle);
                                        if (preResult == ActionResult.FAIL) {
                                            cancelBattle(event);
                                            return;
                                        }
                                    } else {
                                        // ── Challenge swap — all normal beaten + has challenge variant ───
                                        String challId = championInBattle.challengeId();
                                        boolean challengeGateOpen = championInBattle.challengeRequiresAllBeaten()
                                                ? data.hasAllNormalChampionsBeaten(CobbleCapMod.INSTANCE.getChampionConfig().getChampions())
                                                : true;
                                        if (challId != null && !challId.isEmpty()
                                                && challengeGateOpen
                                                && !challengeSwapInProgress.contains(playerUuid)) {

                                            // Check challenge pre-conditions (cooldown)
                                            ActionResult challPre = ChampionManager.handleChallengePreBattle(battlePlayer, championInBattle);
                                            if (challPre == ActionResult.FAIL) {
                                                cancelBattle(event);
                                                return;
                                            }

                                            // Cancel the normal battle and start the challenge version
                                            cancelBattle(event);
                                            swapToChallengeBattle(battlePlayer, npcEntity, championInBattle);
                                            return;
                                        }
                                    }
                                }
                            }

                            // ── Banned species + Party cap violation check ──────────────
                            // Skip entirely if level forcing is active (e.g. Battle Tower)
                            boolean levelForceActive = CobbleCapMod.LEVEL_FORCE_ACTIVE.get();
                            for (Object actor : iter) {
                                UUID uuid = getActorPlayerUuid(actor);
                                if (uuid == null) continue;

                                ServerPlayerEntity player = serverRef.getPlayerManager().getPlayer(uuid);
                                if (player == null) continue;

                                // Check banned species
                                java.util.List<String> banned = ChampionManager.findBannedSpeciesInParty(player);
                                if (!banned.isEmpty()) {
                                    cancelBattle(event);
                                    String names = String.join(", ", banned);
                                    player.sendMessage(
                                        Text.translatable("cobblecap.msg.battle_cancelled_banned", names)
                                            .formatted(Formatting.RED),
                                        false
                                    );
                                    return;
                                }

                                // Check party cap (skip during level forcing — e.g. Battle Tower)
                                if (!levelForceActive && ChampionManager.playerPartyViolatesCap(player)) {
                                    cancelBattle(event);
                                    player.sendMessage(
                                        Text.translatable("cobblecap.msg.battle_cancelled_cap")
                                            .formatted(Formatting.RED),
                                        false
                                    );
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.debug("[CobbleCap] BATTLE_STARTED_PRE handler error: {}", e.getMessage());
                        }
                    });
                    battlePreField = candidate;
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            if (battlePreField != null) {
                LOGGER.info("[CobbleCap] Battle pre-check hook registered on field: {}", battlePreField);
            } else {
                LOGGER.warn("[CobbleCap] Could not find BATTLE_STARTED_PRE field — battle safety net disabled");
            }

            // ── Hook BATTLE_VICTORY — champion detection + cap raise (CHAMP-04) ────
            String victoryField = null;
            for (String candidate : new String[]{"BATTLE_VICTORY", "BATTLE_WON"}) {
                try {
                    hookEvent(cobblemonEvents, candidate, event -> {
                        try {
                            if (!CobbleCapMod.INSTANCE.getCapConfig().isEnabled()) return;
                            ChampionManager.handleBattleVictoryEvent(event, serverRef);
                        } catch (Exception e) {
                            LOGGER.debug("[CobbleCap] BATTLE_VICTORY handler error: {}", e.getMessage());
                        }
                    });
                    victoryField = candidate;
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            if (victoryField != null) {
                LOGGER.info("[CobbleCap] Battle victory hook registered on field: {}", victoryField);
            } else {
                LOGGER.warn("[CobbleCap] Could not find BATTLE_VICTORY field — champion victory detection disabled");
            }

        } catch (ClassNotFoundException e) {
            LOGGER.warn("[CobbleCap] Cobblemon not found — XP cap enforcement disabled");
        } catch (Exception e) {
            LOGGER.error("[CobbleCap] Failed to hook Cobblemon XP event: {}", e.getMessage(), e);
        }
    }

    /**
     * Extract a player UUID from a Cobblemon battle actor via reflection.
     * Returns null if not a player actor or UUID cannot be resolved.
     */
    private static UUID getActorPlayerUuid(Object actor) {
        try {
            // Try getPlayerUUIDs() which returns Iterable<UUID> in Cobblemon
            Object uuids = CapManager.invokeGetter(actor, "getPlayerUUIDs", "getUUIDs");
            if (uuids instanceof Iterable<?> iter) {
                for (Object o : iter) {
                    if (o instanceof UUID u) return u;
                }
            }
            // Fallback: try getUuid() directly
            Object uuid = CapManager.invokeGetter(actor, "getUuid", "uuid", "getPlayerId");
            if (uuid instanceof UUID u) return u;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Subscribe to a Cobblemon event field using a Proxy that wraps Java Consumer as Kotlin Function1.
     * Copied and adapted from CheeseGuard's production CobblemonHook.
     */
    private static void hookEvent(Class<?> eventsClass, String fieldName, Consumer<Object> handler)
            throws Exception {
        Field field = eventsClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object observable = field.get(null); // static field

        // Kotlin Function1 proxy
        Class<?> function1Class = Class.forName("kotlin.jvm.functions.Function1");
        Class<?> unitClass = Class.forName("kotlin.Unit");
        Object unitInstance = unitClass.getField("INSTANCE").get(null);

        Object kotlinHandler = Proxy.newProxyInstance(
            observable.getClass().getClassLoader(),
            new Class[]{function1Class},
            (proxy, method, args) -> {
                if (method.getName().equals("invoke") && args != null && args.length == 1) {
                    handler.accept(args[0]);
                    return unitInstance;
                }
                if (method.getName().equals("toString")) return "CobbleCapHook[" + fieldName + "]";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return null;
            }
        );

        // Try subscribe(Priority, Function1) first
        try {
            Class<?> priorityClass = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object normalPriority = priorityClass.getField("NORMAL").get(null);
            Method sub = observable.getClass().getMethod("subscribe", priorityClass, function1Class);
            sub.invoke(observable, normalPriority, kotlinHandler);
        } catch (Exception e1) {
            // Fallback: subscribe(Function1) without priority
            Method sub = observable.getClass().getMethod("subscribe", function1Class);
            sub.invoke(observable, kotlinHandler);
        }
    }

    public static boolean isAvailable() { return available; }

    // ── Cancel a Cobblemon battle event via reflection ──────────────────────────

    private static void cancelBattle(Object event) {
        try {
            Method cancel = CapManager.findMethod(event.getClass(), "cancel", "setCanceled", "setCancelled");
            if (cancel != null) {
                if (cancel.getParameterCount() == 0) cancel.invoke(event);
                else if (cancel.getParameterCount() == 1) cancel.invoke(event, true);
            }
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] cancelBattle error: {}", e.getMessage());
        }
    }

    // ── Swap a normal champion NPC to challenge mode and re-start the battle ────

    /**
     * Temporarily swaps an RCT TrainerMob's trainer ID to the challenge variant,
     * starts the challenge battle, and restores the original ID afterward.
     * Uses challengeSwapInProgress to prevent the re-triggered BATTLE_STARTED_PRE from looping.
     */
    private static void swapToChallengeBattle(ServerPlayerEntity player, net.minecraft.entity.Entity npcEntity, ChampionEntry champ) {
        try {
            // Verify npcEntity is an RCT TrainerMob
            Class<?> trainerMobClass = Class.forName("com.gitlab.srcmc.rctmod.world.entities.TrainerMob");
            if (!trainerMobClass.isInstance(npcEntity)) {
                LOGGER.debug("[CobbleCap] NPC entity is not a TrainerMob — cannot swap");
                return;
            }

            // Get original trainer ID
            Method getTrainerId = trainerMobClass.getMethod("getTrainerId");
            String originalId = (String) getTrainerId.invoke(npcEntity);

            // Set challenge trainer ID
            Method setTrainerId = trainerMobClass.getMethod("setTrainerId", String.class);
            setTrainerId.invoke(npcEntity, champ.challengeId());

            // Mark swap in progress to prevent BATTLE_STARTED_PRE from re-swapping
            challengeSwapInProgress.add(player.getUuid());

            // Start the challenge battle
            Method startBattle = trainerMobClass.getMethod("startBattleWith", net.minecraft.server.network.ServerPlayerEntity.class);
            startBattle.invoke(npcEntity, player);

            player.sendMessage(
                Text.translatable("cobblecap.msg.challenge_start", champ.displayName())
                    .formatted(Formatting.LIGHT_PURPLE),
                false
            );

            // Schedule restoration of original ID after a short delay
            // The battle has started — restore the ID once the mob is "locked" in battle
            final String restoreId = originalId;
            final net.minecraft.entity.Entity restoreEntity = npcEntity;
            serverRef.execute(() -> {
                try {
                    // Small delay: run next tick to ensure battle is fully initialized
                    serverRef.execute(() -> {
                        try {
                            setTrainerId.invoke(restoreEntity, restoreId);
                            LOGGER.debug("[CobbleCap] Restored TrainerMob ID to {}", restoreId);
                        } catch (Exception e) {
                            LOGGER.warn("[CobbleCap] Failed to restore TrainerMob ID: {}", e.getMessage());
                        }
                    });
                } finally {
                    challengeSwapInProgress.remove(player.getUuid());
                }
            });

        } catch (ClassNotFoundException e) {
            LOGGER.debug("[CobbleCap] RCT mod not found — challenge swap unavailable");
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] swapToChallengeBattle error: {}", e.getMessage());
            challengeSwapInProgress.remove(player.getUuid());
        }
    }
}

package com.cheesecompat.mixin;

import com.vecoo.extraquests.storage.quests.TimerStorage;
import com.vecoo.extraquests.util.Utils;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Protège contre la race condition d'initialisation entre ExtraQuests et
 * FTB Quests qui supprime silencieusement des timers dailies sans reset.
 *
 * Cause du bug "dailies 8h qui ne reset pas pour certains joueurs" :
 *   - ExtraQuests.loadStorage et ServerQuestFile.INSTANCE sont initialisés
 *     tous deux sur ServerLifecycleEvents.SERVER_STARTED.
 *   - L'ordre d'exécution des listeners Fabric n'est PAS garanti.
 *   - Si ExtraQuests tourne en premier, ServerQuestFile.INSTANCE est null.
 *   - Utils.questReset déréférence INSTANCE → NPE silencieux OU obtient null
 *     pour la quête → log error + retire l'entrée du TimerStorage.json sans
 *     reset la quête.
 *   - Résultat : la quête reste claim-locked pour ce joueur jusqu'à
 *     intervention admin manuelle.
 *
 * Fix : @Inject HEAD cancellable. Si ServerQuestFile.INSTANCE est null,
 * retourner false SANS supprimer le timer du storage. Au prochain tick
 * ExtraQuests / au prochain reboot, FTB sera prêt et le reset réussira.
 *
 * Mixin chirurgical : 1 méthode static externe, return false si pas prêt.
 *
 * Logging (v1.2.39+) :
 *   - WARN : chaque blocage (rate-limité — premières 5 puis tous les 50)
 *   - INFO : 1ère fois où on bloque ET 1ère fois où FTB est de nouveau prêt
 *   - Permet de diagnostiquer si la race condition se produit en prod et
 *     combien de fois.
 */
@Mixin(value = Utils.class, remap = false)
public class ExtraQuestsTimerResetGuardMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix/EQTimerGuard");
    private static final AtomicLong BLOCKED_COUNT = new AtomicLong(0);
    private static volatile boolean firstRecoveryLogged = false;

    @Inject(
        method = "questReset",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void cheesecompat$guardAgainstUninitializedFTB(
        TimerStorage timer,
        CallbackInfoReturnable<Boolean> cir
    ) {
        try {
            if (ServerQuestFile.INSTANCE == null) {
                // FTB pas encore prêt — abandonner sans toucher au storage.
                // La méthode originale ferait NPE ou retirerait l'entrée silencieusement.
                long n = BLOCKED_COUNT.incrementAndGet();
                if (n <= 5 || n % 50 == 0) {
                    LOGGER.warn("[CCMF] ExtraQuests timer reset BLOCKED — ServerQuestFile.INSTANCE is null (race condition, FTB not ready yet). Total blocks since startup: {}. Timer entry preserved, will retry next tick.",
                            n);
                }
                cir.setReturnValue(false);
                return;
            }
            // First successful pass-through after at least one block → recovery
            if (!firstRecoveryLogged && BLOCKED_COUNT.get() > 0) {
                firstRecoveryLogged = true;
                LOGGER.info("[CCMF] ExtraQuests timer reset — FTB is now ready, normal operation resumed (had blocked {} attempts during startup race)",
                        BLOCKED_COUNT.get());
            }
        } catch (Throwable t) {
            // Si l'introspection INSTANCE échoue, laisser tomber et passer
            // au code original (qui throw probablement aussi mais c'est le
            // comportement attendu sans notre guard).
            LOGGER.error("[CCMF] ExtraQuests guard — threw checking INSTANCE: {}", t.toString());
        }
    }
}

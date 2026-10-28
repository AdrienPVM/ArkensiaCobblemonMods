package com.cheesecompat.mixin;

import maxigregrze.cobblesafari.CobbleSafari;
import maxigregrze.cobblesafari.command.CobbleSafariCommand;
import maxigregrze.cobblesafari.command.SafariExitCommand;
import maxigregrze.cobblesafari.config.DimensionalBanConfig;
import maxigregrze.cobblesafari.config.IncubatorConfig;
import maxigregrze.cobblesafari.config.MiscConfig;
import maxigregrze.cobblesafari.config.SafariConfig;
import maxigregrze.cobblesafari.config.SafariTimerConfig;
import maxigregrze.cobblesafari.config.SecretBasePCConfig;
import maxigregrze.cobblesafari.config.SpawnBoostConfig;
import maxigregrze.cobblesafari.dungeon.DungeonDimensions;
import maxigregrze.cobblesafari.dungeon.PortalSpawnConfig;
import maxigregrze.cobblesafari.influence.ShinyBoostEvent;
import maxigregrze.cobblesafari.safari.SafariCatchEventHandler;
import maxigregrze.cobblesafari.underground.UndergroundMinigame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Patches CobbleSafari.initLogic() to skip SpawningInfluence registration.
 *
 * CobbleSafari 0.1.5 was built for Cobblemon 1.7.1+ which has breaking API changes vs 1.6.1:
 * - SpawnablePosition class doesn't exist in 1.6.1 (used by RepelInfluence, BucketBoostInfluence)
 * - BattleStartedEvent.Pre renamed (was BattleStartedPreEvent in 1.6.1), used by DimensionalBanEventHandler
 *
 * This mixin reproduces initLogic() skipping influence registration and dimensional ban events,
 * allowing the rest of CobbleSafari (Safari zones, catch mechanics, incubator,
 * dungeons, underground minigame) to function normally.
 */
@Mixin(value = CobbleSafari.class, remap = false)
public class CobbleSafariInitMixin {

    /**
     * @author CheeseCompatModsFix
     * @reason Skip incompatible 1.7.x APIs for Cobblemon 1.6.x compatibility
     */
    @Overwrite
    public static void initLogic() {
        SafariTimerConfig.load();
        IncubatorConfig.load();
        SafariConfig.load();
        SpawnBoostConfig.load();
        PortalSpawnConfig.load();
        DimensionalBanConfig.load();
        MiscConfig.load();
        SecretBasePCConfig.load();
        DungeonDimensions.register();
        CobbleSafariCommand.register();
        SafariExitCommand.register();
        UndergroundMinigame.registerCommon();
        ShinyBoostEvent.register();
        // SKIP: DimensionalBanEventHandler.registerCobblemonEvents()
        // References BattleStartedEvent$Pre which is BattleStartedPreEvent in 1.6.1
        // SKIP: PlayerSpawnerFactory influence registration
        // RepelInfluence and BucketBoostInfluence import SpawnablePosition (1.7.x only)
        SafariCatchEventHandler.register();
        CobbleSafari.LOGGER.info("CobbleSafari initialized (patched by CheeseCompatModsFix - dim bans + spawn influences disabled for Cobblemon 1.6.x)");
    }
}

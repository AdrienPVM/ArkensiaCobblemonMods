package com.cheesecompat.mixin;

import maxigregrze.cobblesafari.dungeon.DungeonConfig;
import maxigregrze.cobblesafari.dungeon.DungeonDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds dungeon_jump to the registered dungeons list.
 * CobbleSafari only registers dungeon_underground by default.
 */
@Mixin(value = DungeonDimensions.class)
public class DungeonDimensionsMixin {

    // dungeon_jump is NOT registered — CobbleSafari 0.1.5 has no worldgen structure for it.
    // The dimension files exist but the feature is unfinished (no template pools, no start piece).
    // Registering it would create an empty void dimension.
}

package com.cheesecompat.mixin;

import battle.tower.battle.BattleArenaService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Fixes Battle Tower arena teleportation offsets.
 *
 * The vanilla BattleArenaService.buildPositions() computes:
 *   soloPlayerPos = anchor.offset(facing, -5)   (5 blocks backward)
 *   npcPos        = anchor.offset(facing,  5)   (5 blocks forward)
 *
 * This means the config anchor is NOT the player spawn point — it's a center point.
 * Our arenas have platforms 18 blocks apart, so:
 *   - Change player offset from -5 to 0  → player spawns AT the configured anchor
 *   - Change NPC offset from 5 to 18     → NPC spawns on the far platform
 *
 * Coop positions (±2 blocks sideways from player pos) are unchanged.
 */
@Mixin(value = BattleArenaService.class, remap = false)
public class BattleArenaPositionMixin {

    @ModifyConstant(method = "buildPositions", constant = @Constant(intValue = -5))
    private static int cheesecompat$fixPlayerOffset(int original) {
        return 0;
    }

    @ModifyConstant(method = "buildPositions", constant = @Constant(intValue = 5))
    private static int cheesecompat$fixNpcOffset(int original) {
        return 18;
    }
}

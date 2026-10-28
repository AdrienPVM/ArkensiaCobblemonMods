package com.cheesecompat.mixin;

import battle.tower.data.TeamSnapshot;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.Pokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fixes Cobblemon 1.6.1 Pokemon.copyFrom() bug that corrupts IVs/EVs.
 * copyFrom's lambdas write IVs/EVs in the wrong direction:
 * instead of copying source->target, they overwrite source with target's random IVs and zero EVs.
 *
 * Two fix sites:
 * 1) captureTeam -- protects the player's party pokemon
 * 2) getTeam -- protects the snapshot's internal pokemon (called every time client UI updates)
 */
@Mixin(value = TeamSnapshot.class, remap = false)
public class TeamSnapshotMixin {

    @Shadow private List<Pokemon> snapshotTeam;

    @Unique private static final Stat[] ALL_STATS = {
        Stats.HP, Stats.ATTACK, Stats.DEFENCE,
        Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED
    };

    // -- captureTeam fix: protects player's party --

    @Unique private static final ThreadLocal<List<int[][]>> cheesecompat$captureStats =
        ThreadLocal.withInitial(ArrayList::new);

    @Inject(method = "captureTeam", at = @At("HEAD"))
    private void cheesecompat$saveBeforeCapture(List<Pokemon> team, UUID uuid, CallbackInfo ci) {
        List<int[][]> saved = cheesecompat$captureStats.get();
        saved.clear();
        for (Pokemon p : team) {
            if (p != null && !p.isFainted()) {
                saved.add(cheesecompat$readStats(p));
            }
        }
    }

    @Inject(method = "captureTeam", at = @At("RETURN"))
    private void cheesecompat$restoreAfterCapture(List<Pokemon> team, UUID uuid, CallbackInfo ci) {
        List<int[][]> saved = cheesecompat$captureStats.get();
        if (saved.isEmpty()) return;

        // Restore player's original pokemon IVs/EVs (corrupted by copyFrom)
        int idx = 0;
        for (Pokemon p : team) {
            if (p != null && !p.isFainted() && idx < saved.size()) {
                cheesecompat$writeStats(p, saved.get(idx));
                idx++;
            }
        }

        // Fix snapshot copies too (they got the random IVs instead of the originals)
        for (int j = 0; j < snapshotTeam.size() && j < saved.size(); j++) {
            cheesecompat$writeStats(snapshotTeam.get(j), saved.get(j));
        }

        saved.clear();
    }

    // -- getTeam fix: protects snapshot's internal pokemon --

    @Unique private static final ThreadLocal<List<int[][]>> cheesecompat$getTeamStats =
        ThreadLocal.withInitial(ArrayList::new);

    @Inject(method = "getTeam", at = @At("HEAD"))
    private void cheesecompat$saveBeforeGetTeam(UUID uuid, CallbackInfoReturnable<List<Pokemon>> cir) {
        List<int[][]> saved = cheesecompat$getTeamStats.get();
        saved.clear();
        for (Pokemon p : snapshotTeam) {
            saved.add(cheesecompat$readStats(p));
        }
    }

    @Inject(method = "getTeam", at = @At("RETURN"))
    private void cheesecompat$restoreAfterGetTeam(UUID uuid, CallbackInfoReturnable<List<Pokemon>> cir) {
        List<int[][]> saved = cheesecompat$getTeamStats.get();
        if (saved.isEmpty()) return;

        // Restore snapshot's internal pokemon IVs/EVs (corrupted by copyFrom)
        for (int j = 0; j < snapshotTeam.size() && j < saved.size(); j++) {
            cheesecompat$writeStats(snapshotTeam.get(j), saved.get(j));
        }

        // Also fix the returned clones' IVs/EVs (they got random values from fresh Pokemon)
        List<Pokemon> returned = cir.getReturnValue();
        if (returned != null) {
            for (int j = 0; j < returned.size() && j < saved.size(); j++) {
                cheesecompat$writeStats(returned.get(j), saved.get(j));
            }
        }

        saved.clear();
    }

    // -- Shared helpers --

    @Unique
    private static int[][] cheesecompat$readStats(Pokemon p) {
        int[][] stats = new int[2][6];
        for (int i = 0; i < ALL_STATS.length; i++) {
            Integer iv = p.getIvs().get(ALL_STATS[i]);
            Integer ev = p.getEvs().get(ALL_STATS[i]);
            stats[0][i] = iv != null ? iv : 0;
            stats[1][i] = ev != null ? ev : 0;
        }
        return stats;
    }

    @Unique
    private static void cheesecompat$writeStats(Pokemon p, int[][] stats) {
        for (int i = 0; i < ALL_STATS.length; i++) {
            p.getIvs().set(ALL_STATS[i], stats[0][i]);
            p.getEvs().set(ALL_STATS[i], stats[1][i]);
        }
    }
}

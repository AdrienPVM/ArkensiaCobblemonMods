package com.cheesecompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fixes ArrayIndexOutOfBoundsException in RaidCrystalBlockEntity.spawnRaidBoss().
 *
 * The original code calls:
 *   getDimension().iterateEntities().forEach(entity -> entity.discard())
 *
 * This discards entities from the dimension's Int2ObjectLinkedOpenHashMap DURING
 * forEach iteration, corrupting the linked-list pointers and causing index -1 OOB.
 * Structures with many interactive blocks (redstone, copper bulbs, hoppers) create
 * enough entities to reliably trigger this race.
 *
 * Fix: redirect the forEach to snapshot the iterable into an ArrayList first,
 * then apply the discard consumer on the safe copy.
 */
@Mixin(targets = "com.necro.raid.dens.common.blocks.entity.RaidCrystalBlockEntity", remap = false)
public class RaidEntityClearMixin {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(
        method = "spawnRaidBoss",
        at = @At(value = "INVOKE", target = "Ljava/lang/Iterable;forEach(Ljava/util/function/Consumer;)V")
    )
    private void cheesecompat$safeEntityClear(Iterable iterable, Consumer action) {
        List<Object> snapshot = new ArrayList<>();
        for (Object obj : iterable) {
            snapshot.add(obj);
        }
        snapshot.forEach(action);
    }
}

package com.roicheese.eggandcheese.block;

import com.roicheese.eggandcheese.EggAndCheese;
import com.roicheese.eggandcheese.blockentity.AdminCollectibleBlockEntity;
import com.roicheese.eggandcheese.data.CollectibleEntry;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.data.LiveAdminEggIndex;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps the registry and the physical world in sync for admin egg blocks, and
 * enforces ONE physical block per registryId (duplicate-ID reconciliation).
 *
 * <p>The {@link LiveAdminEggIndex} tracks every currently-loaded admin egg block.
 * On load we add to the index and {@link #reconcile} the group; on unload we remove.
 * Reconciliation keeps the block at the registry entry's canonical position (or adopts
 * the loading block when the entry is unclaimed) and UNBINDS every other block sharing
 * that id — i.e. clears its {@code registryId} so it becomes a decorative, unlinked egg.
 */
public class AdminEggPlacedEvent {

    /** Deferred reconcile tasks, drained at the END of a tick (never inside a chunk-load callback). */
    private static final ConcurrentLinkedQueue<Runnable> PENDING = new ConcurrentLinkedQueue<>();

    public static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (!(blockEntity instanceof AdminCollectibleBlockEntity be)) return;
            String id = be.getRegistryId();
            if (id.isEmpty()) return;

            String dim = world.getRegistryKey().getValue().toString();
            BlockPos pos = be.getPos().toImmutable();
            LiveAdminEggIndex.add(id, dim, pos);
            // Defer reconciliation to the END of a FUTURE tick. BLOCK_ENTITY_LOAD fires INSIDE c2me's
            // chunk-upgrade pipeline (ServerAccessible.upgradeToThis). Running reconcile now re-enters
            // the chunk system (getChunkBlocking) and deadlocks the server thread → watchdog crash loop.
            // NOTE: server.execute() does NOT help here — ThreadExecutor.execute runs the runnable
            // INLINE when already on the server thread (which we are), so it would still run synchronously
            // inside the load callback. A tick-end queue is the only reliable deferral.
            MinecraftServer server = world.getServer();
            if (server != null) {
                LiveAdminEggIndex.EggLoc loaded = new LiveAdminEggIndex.EggLoc(dim, pos);
                PENDING.add(() -> reconcile(server, id, loaded));
            }
        });

        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> {
            if (!(blockEntity instanceof AdminCollectibleBlockEntity be)) return;
            String id = be.getRegistryId();
            if (id.isEmpty()) return;
            LiveAdminEggIndex.remove(id, world.getRegistryKey().getValue().toString(), be.getPos());
        });

        // Drain deferred reconciles at tick end — outside any chunk-upgrade callback.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Runnable r;
            int guard = 0;
            while ((r = PENDING.poll()) != null && guard++ < 8192) {
                try { r.run(); }
                catch (Exception e) {
                    EggAndCheese.LOGGER.warn("[EggAndCheese] deferred reconcile failed: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Ensure at most ONE loaded physical block claims {@code id}. Keeps the block at the
     * registry entry's canonical pos (adopting the loading block if the entry is unclaimed),
     * unbinds the rest. Safe to call repeatedly. {@code justLoaded} may be null (bulk runs).
     */
    public static void reconcile(MinecraftServer server, String id, LiveAdminEggIndex.EggLoc justLoaded) {
        if (server == null) return;
        Set<LiveAdminEggIndex.EggLoc> set = LiveAdminEggIndex.get(id); // snapshot copy
        if (set.isEmpty()) return;

        EggRegistryData reg = EggRegistryData.get(server);
        CollectibleEntry entry = reg.getEntry(id).orElse(null);
        if (entry == null) return; // orphan (no registry entry) — left for the dashboard integrity tool

        boolean claimed = !entry.isPositionLost() && !entry.getPos().equals(BlockPos.ORIGIN);
        LiveAdminEggIndex.EggLoc canonical = claimed
            ? new LiveAdminEggIndex.EggLoc(entry.getDimension(), entry.getPos().toImmutable()) : null;

        LiveAdminEggIndex.EggLoc keep;
        if (canonical != null && set.contains(canonical)) {
            keep = canonical;
        } else if (!claimed) {
            // entry unclaimed (broken/ORIGIN) — adopt the loading block (or any), heal the registry
            keep = (justLoaded != null && set.contains(justLoaded)) ? justLoaded : pickDeterministic(set);
            entry.setPositionLost(false);
            entry.setPos(keep.pos());
            entry.setDimension(keep.dim());
            reg.markDirty();
        } else {
            // entry claims a pos that isn't currently loaded — keep one deterministically, don't move entry
            keep = pickDeterministic(set);
        }

        for (LiveAdminEggIndex.EggLoc gp : set) {
            if (gp.equals(keep)) continue;
            unbindAt(server, id, gp);
        }
    }

    /** Clear the registryId of the admin egg block at {@code gp} (if it still claims {@code id}). */
    public static boolean unbindAt(MinecraftServer server, String id, LiveAdminEggIndex.EggLoc gp) {
        for (ServerWorld w : server.getWorlds()) {
            if (!w.getRegistryKey().getValue().toString().equals(gp.dim())) continue;
            // NEVER force-load a chunk here. ServerChunkManager.isChunkLoaded(int,int) is a
            // non-blocking state query (no getChunkBlocking — c2me-safe). Only if the chunk is
            // already loaded do we read the block entity (which then returns from cache without
            // blocking). If the duplicate's chunk isn't loaded, it's reconciled when its own
            // BLOCK_ENTITY_LOAD fires.
            int cx = gp.pos().getX() >> 4, cz = gp.pos().getZ() >> 4;
            if (!w.getChunkManager().isChunkLoaded(cx, cz)) return false; // not loaded — skip
            if (w.getBlockEntity(gp.pos()) instanceof AdminCollectibleBlockEntity be && id.equals(be.getRegistryId())) {
                be.setRegistryId(""); // becomes a decorative, unlinked egg; also removes it from the index
                EggAndCheese.LOGGER.info("[EggAndCheese] Unbound duplicate egg '{}' at {}", id, gp.pos());
                return true;
            }
            return false;
        }
        return false;
    }

    private static LiveAdminEggIndex.EggLoc pickDeterministic(Set<LiveAdminEggIndex.EggLoc> set) {
        LiveAdminEggIndex.EggLoc best = null;
        for (LiveAdminEggIndex.EggLoc gp : set) {
            if (best == null
                || gp.dim().compareTo(best.dim()) < 0
                || (gp.dim().equals(best.dim()) && gp.pos().asLong() < best.pos().asLong())) {
                best = gp;
            }
        }
        return best;
    }
}

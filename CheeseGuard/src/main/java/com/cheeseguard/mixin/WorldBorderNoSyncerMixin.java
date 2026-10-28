package com.cheeseguard.mixin;

import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.border.WorldBorderListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Permanently prevents any vanilla {@link WorldBorderListener.WorldBorderSyncer}
 * from being attached to a {@link WorldBorder}.
 *
 * <p>Vanilla wires one syncer per non-overworld dimension into the overworld's
 * border so any change to the overworld cascades to nether/end/custom dims.
 * That chain breaks the "each dimension has its own independent border" feature
 * we expose through CheeseGuard's dashboard.</p>
 *
 * <p>An earlier fix detached existing syncers at SERVER_STARTED, but new
 * ServerWorlds created at runtime (cobblemonraiddens UUID dims, cheeseguard
 * per-player dims, etc.) re-attach a fresh syncer when their world is
 * constructed — and those slip past the boot-time detach. This mixin blocks
 * the attachment at the source so the chain can never reform.</p>
 */
@Mixin(WorldBorder.class)
public class WorldBorderNoSyncerMixin {

    @Inject(method = "addListener", at = @At("HEAD"), cancellable = true)
    private void cheeseguard$rejectSyncer(WorldBorderListener listener, CallbackInfo ci) {
        if (listener instanceof WorldBorderListener.WorldBorderSyncer) {
            // Silently drop — we don't need any cross-dimension border replication.
            ci.cancel();
        }
    }
}

package com.cheesepatrol.mixin;

import com.cheesepatrol.CheesePatrolMod;
import com.cheesepatrol.PatrolData;
import com.cheesepatrol.PatrolManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents patrol NPCs from being removed (killed, discarded, despawned).
 * Protection is active for ANY entity that has a PatrolData entry OR a backup file,
 * regardless of whether the patrol route is active or not.
 * Only chunk unload/save and dimension changes are allowed.
 */
@Mixin(Entity.class)
public abstract class EntityRemoveMixin {

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void cheesepatrol$preventPatrolRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        // Only block destructive removals — allow chunk unload/save and dimension changes
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                || reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            return;
        }

        Entity self = (Entity) (Object) this;

        // Layer 1: Check loaded patrol data — protect ALL registered NPCs (active or not)
        PatrolData patrol = PatrolManager.get(self.getUuid());
        if (patrol != null) {
            CheesePatrolMod.LOGGER.info("[CheesePatrol] Blocked removal ({}) of registered NPC {} (patrol {})",
                    reason, self.getUuid().toString().substring(0, 8),
                    patrol.isActive() ? "active" : "inactive");
            ci.cancel();
            return;
        }

        // Layer 2: Pre-load protection — if patrols not loaded yet but a backup file exists,
        // protect the entity to prevent race condition during world load.
        if (PatrolManager.hasBackupFile(self.getUuid())) {
            CheesePatrolMod.LOGGER.warn("[CheesePatrol] Blocked removal ({}) of NPC {} via backup-file protection (patrol data not yet loaded)",
                    reason, self.getUuid().toString().substring(0, 8));
            ci.cancel();
        }
    }
}

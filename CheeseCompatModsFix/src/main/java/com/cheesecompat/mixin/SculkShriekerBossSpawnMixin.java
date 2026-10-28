package com.cheesecompat.mixin;

import com.ace.cobbleboss.BossSpawner;
import com.cheesecompat.cobbleboss.ShriekerBossState;
import net.minecraft.block.SculkShriekerBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SculkShriekerBlockEntity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Map;

@Mixin(SculkShriekerBlockEntity.class)
public abstract class SculkShriekerBossSpawnMixin extends BlockEntity {

    private SculkShriekerBossSpawnMixin() { super(null, null, null); }

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    private static volatile boolean reflectInit = false;
    private static Method portalStart;

    private static synchronized void initReflect() {
        if (reflectInit) return;
        try {
            Class<?> animator = Class.forName("com.ace.cobbleboss.PortalAnimator");
            portalStart = animator.getMethod("startPortalCreation", ServerWorld.class, BlockPos.class, String.class);
        } catch (Throwable t) {
            LOGGER.info("[CheeseCompat] PortalAnimator unavailable, using direct spawn fallback");
        }
        reflectInit = true;
    }

    @Inject(method = "shriek", at = @At("HEAD"), cancellable = true)
    private void cheesecompat$interceptShriek(ServerWorld world, ServerPlayerEntity player, CallbackInfo ci) {
        if (player == null) return;
        if (player.isCreative() || player.isSpectator()) return;

        try {
            if (!this.getCachedState().get(SculkShriekerBlock.CAN_SUMMON)) return;
        } catch (Throwable t) {
            return;
        }

        initReflect();
        ShriekerBossState.load();

        BlockPos pos = this.getPos();
        long now = System.currentTimeMillis();

        long activeExpiry = 0L;
        for (Map.Entry<Long, Long> e : ShriekerBossState.COOLDOWN_UNTIL.entrySet()) {
            if (e.getValue() <= now) continue;
            int cx = ShriekerBossState.unpackX(e.getKey());
            int cz = ShriekerBossState.unpackZ(e.getKey());
            long dx = cx - pos.getX();
            long dz = cz - pos.getZ();
            if (dx * dx + dz * dz <= ShriekerBossState.COOLDOWN_RADIUS_SQ) {
                if (e.getValue() > activeExpiry) activeExpiry = e.getValue();
            }
        }
        if (activeExpiry > now) {
            long remainingMin = (activeExpiry - now) / 60000L + 1;
            player.sendMessage(
                Text.literal("Cette zone se repose encore (" + remainingMin + " min)...")
                    .formatted(Formatting.DARK_GRAY),
                true
            );
            ci.cancel();
            return;
        }

        String species = determineSpecies(world, pos);

        try {
            world.playSound(null, pos, SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 2.0f, 0.6f);
            Text title = Text.literal("⚠ Un être ancien s'éveille...")
                .formatted(Formatting.DARK_PURPLE, Formatting.BOLD);
            Text subtitle = Text.literal("Le hurleur a attiré une entité puissante.")
                .formatted(Formatting.GRAY);
            double r2 = 64.0 * 64.0;
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (p.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= r2) {
                    p.networkHandler.sendPacket(new TitleS2CPacket(title));
                    p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[CheeseCompat] Telegraph failed", t);
        }

        try {
            boolean handled = false;
            if (portalStart != null) {
                try {
                    portalStart.invoke(null, world, pos, species);
                    handled = true;
                    LOGGER.info("[CheeseCompat] Portal animation started for {} at {} by {}",
                        species, pos, player.getName().getString());
                } catch (Throwable t) {
                    LOGGER.warn("[CheeseCompat] PortalAnimator failed, falling back to direct spawn", t);
                }
            }
            if (!handled) {
                LOGGER.info("[CheeseCompat] Direct spawn {} at {} by {}",
                    species, pos, player.getName().getString());
                BossSpawner.spawnBoss(world, pos, species);
            }

            ShriekerBossState.COOLDOWN_UNTIL.put(
                ShriekerBossState.packXZ(pos.getX(), pos.getZ()),
                now + ShriekerBossState.COOLDOWN_MILLIS
            );
            ShriekerBossState.save();
            ci.cancel();
        } catch (Throwable t) {
            LOGGER.warn("[CheeseCompat] Shrieker boss spawn failed", t);
        }
    }

    private static String determineSpecies(ServerWorld level, BlockPos pos) {
        try {
            boolean deepDark = level.getBiome(pos).getKey()
                .map(k -> k.getValue().getPath().equals("deep_dark"))
                .orElse(false);
            if (deepDark && pos.getY() < 0) {
                return "giratina";
            }
        } catch (Throwable ignored) {}
        return "exploud";
    }
}

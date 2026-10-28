package com.cobbleshift.client;

import com.cobbleshift.network.AbilitySyncPayload;
import com.cobbleshift.network.AnimationStateSyncPayload;
import com.cobbleshift.network.AttackAbilityPayload;
import com.cobbleshift.network.TransformSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point for CobbleShift.
 * Handles:
 * - Transform sync packets (model swap, camera, VFX)
 * - Ability sync packets (HUD overlay)
 * - Attack projectile rendering
 * - Keybinds (sleep pose, cry)
 */
public class CobbleShiftClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");

    private KeyBinding sleepKey;
    private KeyBinding cryKey;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[CobbleShift] Client initializing...");

        // Register keybinds
        sleepKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cobbleshift.sleep",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "category.cobbleshift"
        ));
        cryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cobbleshift.cry",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "category.cobbleshift"
        ));

        // Register packet receivers
        ClientPlayNetworking.registerGlobalReceiver(TransformSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                MinecraftClient client = context.client();
                boolean isLocalPlayer = client.player != null
                    && client.player.getUuid().equals(payload.playerUuid());

                // NPC transforms must never produce cry or transform particles, regardless
                // of distance or dimension. Player transforms use the silent flag (set by the
                // server based on dimension + radius) to gate cry/particles.
                boolean playFx = !payload.silent() && !payload.fromNpc() && client.world != null;

                if (payload.species().isEmpty()) {
                    // Detransform — spawn particles before removing state
                    if (playFx) {
                        net.minecraft.entity.Entity targetEntity = findEntityByUuid(client.world, payload.playerUuid());
                        if (targetEntity != null) {
                            TransformRenderer.spawnTransformParticles(targetEntity);
                        }
                    }
                    TransformRenderer.removeTransform(payload.playerUuid());
                    if (isLocalPlayer) {
                        client.options.setPerspective(Perspective.FIRST_PERSON);
                        TransformRenderer.resetZoom();
                    }
                } else {
                    TransformRenderer.setTransform(payload.playerUuid(), payload.species(),
                        payload.type1(), payload.type2());
                    TransformRenderer.setScale(payload.playerUuid(), payload.scale());
                    if (isLocalPlayer) {
                        client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                    }
                    // Transform VFX: particles + Pokemon cry (skipped for NPCs and silent syncs)
                    if (playFx) {
                        net.minecraft.entity.Entity targetEntity = findEntityByUuid(client.world, payload.playerUuid());
                        if (targetEntity != null) {
                            TransformRenderer.spawnTransformParticles(targetEntity);
                        }
                        TransformRenderer.triggerCry(payload.playerUuid(), client.world);
                    }
                }
            });
        });

        // Animation state sync from server (for remote players)
        ClientPlayNetworking.registerGlobalReceiver(AnimationStateSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                TransformRenderer.setRemoteAnimState(payload.playerUuid(), payload.envState(), payload.isMoving());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(AbilitySyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.species().isEmpty()) {
                    AbilityHudRenderer.clearAbility();
                } else {
                    AbilityHudRenderer.setAbility(payload.species(), payload.type1(), payload.type2());
                }
            });
        });

        // Register HUD renderer
        AbilityHudRenderer.register();

        // Register crosshair renderer for transformed players (in 3rd person, crosshair is normally hidden)
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
            if (!TransformRenderer.isTransformed(mc.player.getUuid())) return;
            if (mc.options.getPerspective().isFirstPerson()) return; // already rendered by MC

            int cx = mc.getWindow().getScaledWidth() / 2;
            int cy = mc.getWindow().getScaledHeight() / 2;
            int color = 0xBBFFFFFF; // semi-transparent white
            context.fill(cx - 5, cy, cx + 6, cy + 1, color); // horizontal bar
            context.fill(cx, cy - 5, cx + 1, cy + 6, color); // vertical bar
        });

        // Register keybind tick handler
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Register TypeProjectileEntity renderer
        try {
            Class<?> entityTypeClass = Class.forName("com.cobbleshift.TypeProjectileEntity");
            java.lang.reflect.Field typeField = entityTypeClass.getField("TYPE");
            Object rawType = typeField.get(null);
            @SuppressWarnings({"unchecked", "rawtypes"})
            net.minecraft.entity.EntityType entityType = (net.minecraft.entity.EntityType) rawType;
            EntityRendererRegistry.register(entityType, FlyingItemEntityRenderer::new);
            LOGGER.info("[CobbleShift] Client: TypeProjectile renderer registered");
        } catch (Exception e) {
            LOGGER.debug("[CobbleShift] Client: TypeProjectile renderer registration skipped: {}", e.getMessage());
        }

        LOGGER.info("[CobbleShift] Client initialized");
    }

    /**
     * Find an entity by UUID in the client world.
     * Checks players first (fast), then iterates all entities.
     */
    private static net.minecraft.entity.Entity findEntityByUuid(net.minecraft.client.world.ClientWorld world, java.util.UUID uuid) {
        if (world == null || uuid == null) return null;
        // Fast path: check players
        PlayerEntity player = world.getPlayerByUuid(uuid);
        if (player != null) return player;
        // Slow path: iterate entities (for NPCs)
        for (net.minecraft.entity.Entity e : world.getEntities()) {
            if (e.getUuid().equals(uuid)) return e;
        }
        return null;
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        java.util.UUID uuid = client.player.getUuid();
        if (!TransformRenderer.isTransformed(uuid)) return;

        // Lock camera to third person back when transformed — prevent F5 from changing it
        if (client.options.getPerspective() != Perspective.THIRD_PERSON_BACK) {
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        }

        // Sleep keybind (toggle)
        while (sleepKey.wasPressed()) {
            TransformRenderer.toggleSleep(uuid);
            if (TransformRenderer.isSleeping(uuid)) {
                client.player.sendMessage(net.minecraft.text.Text.translatable("cobbleshift.client.sleeping").formatted(net.minecraft.util.Formatting.GRAY), true);
            }
        }

        // Cry keybind
        while (cryKey.wasPressed()) {
            TransformRenderer.triggerCry(uuid, client.world);
        }
    }
}

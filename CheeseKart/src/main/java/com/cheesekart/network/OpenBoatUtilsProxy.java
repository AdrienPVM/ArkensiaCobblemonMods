package com.cheesekart.network;

import com.cheesekart.KartPreset;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;

/**
 * Sends OpenBoatUtils setting packets to the client.
 * OBU listens on channel "openboatutils:settings" with binary format:
 *   [short packetId][payload]
 *
 * When OBU is loaded server-side, we use its own BytePayload class via reflection
 * to avoid payload type conflicts. When OBU is NOT loaded, we register our own payload.
 */
public class OpenBoatUtilsProxy {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseKart");

    // OBU packet IDs
    private static final short OBU_RESET           = 0;
    private static final short OBU_STEP_HEIGHT     = 1;
    private static final short OBU_SLIPPERINESS    = 2;
    private static final short OBU_FALL_DAMAGE     = 4;
    private static final short OBU_AIR_CONTROL     = 6;
    private static final short OBU_JUMP_FORCE      = 7;
    private static final short OBU_GRAVITY         = 9;
    private static final short OBU_YAW_ACCEL       = 10;
    private static final short OBU_FORWARD_ACCEL   = 11;
    private static final short OBU_BACKWARD_ACCEL  = 12;
    private static final short OBU_TURN_FWD_ACCEL  = 13;
    private static final short OBU_ACCEL_STACKING  = 14;
    private static final short OBU_AIR_STEPPING    = 28;

    public static final Identifier OBU_CHANNEL = Identifier.of("openboatutils", "settings");

    // Reflection cache for OBU's BytePayload
    private static boolean obuLoaded = false;
    private static Constructor<?> obuBytePayloadCtor = null;
    private static CustomPayload.Id<?> obuPayloadId = null;

    // ── Fallback payload (only used when OBU is NOT loaded) ─────────────────
    public record ObuPayload(byte[] data) implements CustomPayload {
        public static final Id<ObuPayload> ID = new CustomPayload.Id<>(OBU_CHANNEL);
        public static final PacketCodec<PacketByteBuf, ObuPayload> CODEC =
                PacketCodec.of(ObuPayload::write, ObuPayload::read);

        private void write(PacketByteBuf buf) {
            buf.writeBytes(data);
        }

        private static ObuPayload read(PacketByteBuf buf) {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new ObuPayload(bytes);
        }

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    private static boolean registered = false;

    /**
     * Register the OBU payload type. Call once during mod init.
     * If OBU is loaded, cache its BytePayload constructor via reflection.
     * If OBU is NOT loaded, register our own fallback payload.
     */
    public static void register() {
        if (registered) return;
        registered = true;

        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("openboatutils")) {
            obuLoaded = true;
            try {
                Class<?> bytePayloadClass = Class.forName("dev.o7moon.openboatutils.OpenBoatUtils$BytePayload");
                // BytePayload(ByteBuf data) — record canonical constructor
                obuBytePayloadCtor = bytePayloadClass.getConstructor(ByteBuf.class);
                // Get the static ID field
                var idField = bytePayloadClass.getField("ID");
                obuPayloadId = (CustomPayload.Id<?>) idField.get(null);
                LOGGER.info("[CheeseKart] OBU detected — using OBU BytePayload via reflection");
            } catch (Exception e) {
                LOGGER.error("[CheeseKart] Failed to init OBU reflection, OBU packets will not work", e);
                obuLoaded = false;
            }
        } else {
            net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(ObuPayload.ID, ObuPayload.CODEC);
            LOGGER.info("[CheeseKart] OBU not loaded — registered fallback payload channel");
        }
    }

    /**
     * Apply all preset settings to a player's boat.
     */
    public static void applyPreset(ServerPlayerEntity player, KartPreset preset) {
        if (!canSend(player)) {
            LOGGER.debug("[CheeseKart] Cannot send OBU packets to {} (client missing OBU)", player.getName().getString());
            return;
        }
        sendFloat(player, OBU_SLIPPERINESS, preset.getSlipperiness());
        sendFloat(player, OBU_STEP_HEIGHT, preset.getStepHeight());
        sendDouble(player, OBU_GRAVITY, preset.getGravity());
        sendFloat(player, OBU_FORWARD_ACCEL, preset.getForwardAccel());
        sendFloat(player, OBU_BACKWARD_ACCEL, preset.getBackwardAccel());
        sendFloat(player, OBU_YAW_ACCEL, preset.getYawAccel());
        sendFloat(player, OBU_TURN_FWD_ACCEL, preset.getTurnForwardAccel());
        sendBool(player, OBU_ACCEL_STACKING, preset.isAccelStacking());
        sendBool(player, OBU_AIR_CONTROL, preset.isAirControl());
        sendBool(player, OBU_AIR_STEPPING, preset.isAirStepping());
        sendFloat(player, OBU_JUMP_FORCE, preset.getJumpForce());
        sendBool(player, OBU_FALL_DAMAGE, preset.isFallDamage());
    }

    /**
     * Reset all OBU settings to vanilla defaults.
     */
    public static void sendReset(ServerPlayerEntity player) {
        if (!canSend(player)) return;
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeShort(OBU_RESET);
        sendRaw(player, buf);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static boolean canSend(ServerPlayerEntity player) {
        if (obuLoaded && obuPayloadId != null) {
            return ServerPlayNetworking.canSend(player, (CustomPayload.Id<? extends CustomPayload>) obuPayloadId);
        }
        return ServerPlayNetworking.canSend(player, ObuPayload.ID);
    }

    private static void sendFloat(ServerPlayerEntity player, short id, float value) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeShort(id);
        buf.writeFloat(value);
        sendRaw(player, buf);
    }

    private static void sendDouble(ServerPlayerEntity player, short id, double value) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeShort(id);
        buf.writeDouble(value);
        sendRaw(player, buf);
    }

    private static void sendBool(ServerPlayerEntity player, short id, boolean value) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeShort(id);
        buf.writeBoolean(value);
        sendRaw(player, buf);
    }

    private static void sendRaw(ServerPlayerEntity player, PacketByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();

        if (obuLoaded && obuBytePayloadCtor != null) {
            // Use OBU's own BytePayload class via reflection
            try {
                ByteBuf byteBuf = Unpooled.wrappedBuffer(data);
                CustomPayload payload = (CustomPayload) obuBytePayloadCtor.newInstance(byteBuf);
                ServerPlayNetworking.send(player, payload);
            } catch (Exception e) {
                LOGGER.error("[CheeseKart] Failed to send OBU packet via reflection", e);
            }
        } else {
            // Fallback — our own payload type
            ServerPlayNetworking.send(player, new ObuPayload(data));
        }
    }
}

package com.cobbleshift.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S packet: player wants to attack while transformed.
 * Contains a boolean indicating whether the client has an attack animation ready.
 * If true, server delays projectile spawn by 1.3s so the animation plays first.
 * If false, projectile spawns immediately.
 */
public record AttackAbilityPayload(boolean hasAnimation) implements CustomPayload {

    public static final CustomPayload.Id<AttackAbilityPayload> ID =
        new CustomPayload.Id<>(Identifier.of("cobbleshift", "attack_ability"));

    public static final PacketCodec<PacketByteBuf, AttackAbilityPayload> CODEC =
        PacketCodec.of(AttackAbilityPayload::write, AttackAbilityPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeBoolean(hasAnimation);
    }

    private static AttackAbilityPayload read(PacketByteBuf buf) {
        return new AttackAbilityPayload(buf.readBoolean());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * Register for C2S networking.
     */
    public static void registerC2S() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
    }
}

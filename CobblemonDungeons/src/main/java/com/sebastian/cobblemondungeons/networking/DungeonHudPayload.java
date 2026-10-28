package com.sebastian.cobblemondungeons.networking;

import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record DungeonHudPayload(
    String dungeonName,
    int alphasDefeated, int requiredAlphas,
    int gemsFound, int requiredGems,
    int guardiansDefeated, int requiredGuardians,
    int stakesFound, int requiredStakes,
    int timeLeftSeconds,
    int lives,
    boolean isVisible
) implements CustomPayload {
    public static final Id<DungeonHudPayload> ID = new Id<>(Identifier.of(CobblemonDungeons.MOD_ID, "dungeon_hud"));

    public static final PacketCodec<PacketByteBuf, DungeonHudPayload> CODEC = PacketCodec.of(
        (value, buf) -> {
            buf.writeString(value.dungeonName);
            buf.writeInt(value.alphasDefeated);
            buf.writeInt(value.requiredAlphas);
            buf.writeInt(value.gemsFound);
            buf.writeInt(value.requiredGems);
            buf.writeInt(value.guardiansDefeated);
            buf.writeInt(value.requiredGuardians);
            buf.writeInt(value.stakesFound);
            buf.writeInt(value.requiredStakes);
            buf.writeInt(value.timeLeftSeconds);
            buf.writeInt(value.lives);
            buf.writeBoolean(value.isVisible);
        },
        buf -> new DungeonHudPayload(
            buf.readString(),
            buf.readInt(), buf.readInt(),
            buf.readInt(), buf.readInt(),
            buf.readInt(), buf.readInt(),
            buf.readInt(), buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readBoolean()
        )
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

package com.cheeseguard.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server → Client: payload describing the zone the owner is editing.
 *
 * JSON shape:
 * {
 *   "zoneId": "...",
 *   "name": "...",
 *   "world": "minecraft:overworld",
 *   "size": "32x32x16",
 *   "owner": { "uuid": "...", "name": "..." },
 *   "members": [{ "uuid": "...", "name": "..." }, ...],
 *   "flags": { "PVP": true, "MOB_SPAWN": false }   // optional, owner-toggleable subset
 * }
 *
 * The empty payload (json == "") is a CLOSE signal — client should close the menu.
 */
public record ZoneMenuDataS2C(String json) implements CustomPayload {

    public static final Id<ZoneMenuDataS2C> ID =
        new Id<>(Identifier.of("cheeseguard", "zone_menu_data"));

    public static final PacketCodec<RegistryByteBuf, ZoneMenuDataS2C> CODEC =
        PacketCodec.tuple(
            PacketCodecs.STRING, ZoneMenuDataS2C::json,
            ZoneMenuDataS2C::new
        );

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }

    /** Parse the JSON string. Returns an empty object on parse failure. */
    public JsonObject data() {
        if (json == null || json.isEmpty()) return new JsonObject();
        try { return JsonParser.parseString(json).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    public boolean isClose() { return json == null || json.isEmpty(); }
}

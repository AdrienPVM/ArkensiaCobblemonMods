package com.cheesemarket.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

// ══════════════════════════════════════════════════════════════════════════════
// Client → Server: player/admin action
// ══════════════════════════════════════════════════════════════════════════════
public class ShopPayloads {

    // ── ShopActionPayload ──────────────────────────────────────────────────────

    /**
     * Sent client → server when the player performs an action in the GUI.
     *
     * Action strings (admin):
     *   open_shop_list
     *   open_shop:{shopId}
     *   shop_create:{shopId}
     *   shop_delete:{shopId}
     *   shop_save:{shopId}|{json of edited fields}
     *   open_set_list:{shopId}
     *   open_set:{shopId}:{setId}
     *   set_create:{shopId}
     *   set_delete:{shopId}:{setId}
     *   set_save:{shopId}:{setId}|{json}
     *   open_item_list:{shopId}:{setId}
     *   open_item:{shopId}:{setId}:{itemId}
     *   item_create:{shopId}:{setId}
     *   item_delete:{shopId}:{setId}:{itemId}
     *   item_save:{shopId}:{setId}:{itemId}|{json}
     *   item_toggle:{shopId}:{setId}:{itemId}
     *   item_output_add:{shopId}:{setId}:{itemId}:{mcItemId}:{count}
     *   item_output_remove:{shopId}:{setId}:{itemId}:{index}
     *   item_cost_add:{shopId}:{setId}:{itemId}:{mcItemId}:{count}
     *   item_cost_remove:{shopId}:{setId}:{itemId}:{index}
     *   picker_search:{query}|{context}    ← server returns filtered item list
     *   set_manual_toggle:{shopId}:{setId}
     *
     * Action strings (player):
     *   shop_open:{shopId}               ← player opens a shop
     *   shop_buy:{shopId}:{setId}:{itemId}
     *   shop_tab:{tabIndex}              ← client-side only but echoed for sync
     *
     * Special client-only prefixes (handled locally, NOT sent to server):
     *   _tab:{tabId}
     *   _scroll:{delta}
     *   _setmode:{modeString}
     */
    public record ShopActionPayload(String action) implements CustomPayload {
        public static final Id<ShopActionPayload> ID =
            new Id<>(Identifier.of("cheesemarket", "shop_action"));
        public static final PacketCodec<RegistryByteBuf, ShopActionPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, ShopActionPayload::action, ShopActionPayload::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    // ── ShopSyncPayload ────────────────────────────────────────────────────────

    /**
     * Sent server → client to update the GUI.
     *
     * viewMode values:
     *   SHOP_LIST, SHOP_EDIT, SET_LIST, SET_EDIT,
     *   ITEM_LIST, ITEM_EDIT, ITEM_PICKER,
     *   SHOP_VIEW (player-facing), CLOSE
     */
    // Large-capacity string codec (no 32767 char limit)
    private static final PacketCodec<RegistryByteBuf, String> LARGE_STRING =
        PacketCodec.of(
            (value, buf) -> ((PacketByteBuf) buf).writeString(value, Integer.MAX_VALUE),
            buf          -> ((PacketByteBuf) buf).readString(Integer.MAX_VALUE)
        );

    public record ShopSyncPayload(String viewMode, String json) implements CustomPayload {
        public static final Id<ShopSyncPayload> ID =
            new Id<>(Identifier.of("cheesemarket", "shop_sync"));
        public static final PacketCodec<RegistryByteBuf, ShopSyncPayload> CODEC =
            PacketCodec.tuple(
                PacketCodecs.STRING, ShopSyncPayload::viewMode,
                LARGE_STRING,        ShopSyncPayload::json,
                ShopSyncPayload::new
            );
        @Override public Id<? extends CustomPayload> getId() { return ID; }

        public com.google.gson.JsonObject data() {
            try { return com.google.gson.JsonParser.parseString(json).getAsJsonObject(); }
            catch (Exception e) { return new com.google.gson.JsonObject(); }
        }
    }

    // ── ShopOpenPayload ────────────────────────────────────────────────────────

    /**
     * Sent server → client to instruct the client to open the GUI.
     * adminMode=true → open CheeseMarketScreen (full-screen admin)
     * adminMode=false → open NewShopViewScreen (player shop view)
     */
    public record ShopOpenPayload(boolean adminMode) implements CustomPayload {
        public static final Id<ShopOpenPayload> ID =
            new Id<>(Identifier.of("cheesemarket", "shop_open"));
        public static final PacketCodec<RegistryByteBuf, ShopOpenPayload> CODEC =
            PacketCodec.tuple(
                PacketCodecs.BOOL, ShopOpenPayload::adminMode,
                ShopOpenPayload::new
            );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}

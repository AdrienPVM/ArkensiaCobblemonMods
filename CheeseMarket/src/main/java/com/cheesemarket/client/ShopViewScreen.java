package com.cheesemarket.client;

import com.cheesemarket.network.ShopPayloads.*;
import com.google.gson.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.*;

/**
 * Player-facing shop screen — v6 (texture-based, Gengar theme).
 *
 * Uses custom PNG assets:
 *  - shop.png  (350x250) : background with Gengar frame, red banner + skulls, dark content zone
 *  - item.png  (41x36)   : item card block
 *  - close.png (20x20)   : X close button
 *  - button.png(75x20)   : confirm modal button
 *
 * Item cards are rendered as a 6x3 grid in the dark content zone only.
 * Click on a card → opens confirmation modal. No buy button on cards.
 */
public class ShopViewScreen extends Screen {

    // ── Textures ─────────────────────────────────────────────────────────────
    private static final Identifier TEX_SHOP   = Identifier.of("cheesemarket", "textures/gui/shop/custom/shop.png");
    private static final Identifier TEX_ITEM   = Identifier.of("cheesemarket", "textures/gui/shop/custom/item.png");
    private static final Identifier TEX_CLOSE  = Identifier.of("cheesemarket", "textures/gui/shop/custom/close.png");
    private static final Identifier TEX_BUTTON  = Identifier.of("cheesemarket", "textures/gui/shop/custom/button.png");
    private static final Identifier TEX_FROMAGE = Identifier.of("cheesemarket", "textures/gui/shop/custom/fromage.png");
    private static final Identifier TEX_CONFIRM = Identifier.of("cheesemarket", "textures/gui/shop/custom/confirm.png");
    private static final Identifier TEX_SLIDER  = Identifier.of("cheesemarket", "textures/gui/shop/custom/slider.png");

    // ── confirm.png (204×146) — zone constants (pixel coords: top-left → bottom-right) ──
    private static final int CONFIRM_TEX_W = 204, CONFIRM_TEX_H = 146;
    private static final int SLIDER_TEX_W = 10, SLIDER_TEX_H = 17;
    // Input items: (17,19)→(77,59)
    private static final int CF_COST_X = 17, CF_COST_Y = 19, CF_COST_W = 60, CF_COST_H = 40;
    // Output items: (127,19)→(187,59)
    private static final int CF_OUT_X = 127, CF_OUT_Y = 19, CF_OUT_W = 60, CF_OUT_H = 40;
    // Quantity text above slider
    private static final int CF_QTY_Y = 76;
    // Slider white bar max fill: (24,87)→(179,93)
    private static final int CF_SL_X = 24, CF_SL_Y = 87, CF_SL_W = 155, CF_SL_H = 7;
    // Left button (Fermer): (22,115)→(82,129)
    private static final int CF_BTN_L_X = 22, CF_BTN_L_Y = 115, CF_BTN_L_W = 60, CF_BTN_L_H = 14;
    // Right button (Confirmer): (123,115)→(183,129)
    private static final int CF_BTN_R_X = 123, CF_BTN_R_Y = 115, CF_BTN_R_W = 60, CF_BTN_R_H = 14;

    // ── shop.png zones (pixel coordinates within the 350x250 texture) ────────
    private static final int SHOP_TEX_W = 350, SHOP_TEX_H = 250;
    // Gengar eyes: y15→y62. Red banner/skulls: y63→y92. Content: y95→y216.
    // Purple frame: irregular, ~x6→x30 left, ~x320→x342 right.
    private static final int RED_Y      = 63,  RED_H    = 30;   // red banner zone (y63 to y92)
    private static final int CONTENT_X  = 32;                    // content zone left edge
    private static final int CONTENT_X2 = 318;                   // content zone right edge
    private static final int CONTENT_Y  = 95;                    // content zone top
    private static final int CONTENT_Y2 = 216;                   // content zone bottom
    private static final int CONTENT_W  = CONTENT_X2 - CONTENT_X; // 286px
    private static final int CONTENT_H  = CONTENT_Y2 - CONTENT_Y; // 121px

    // ── item.png ─────────────────────────────────────────────────────────────
    private static final int ITEM_TEX_W = 41, ITEM_TEX_H = 36;

    // ── close.png ────────────────────────────────────────────────────────────
    private static final int CLOSE_TEX  = 20;

    // ── button.png ───────────────────────────────────────────────────────────
    private static final int BTN_TEX_W = 75, BTN_TEX_H = 20;

    // ── Grid layout (6 cols x 3 visible rows) ────────────────────────────────
    private static final int GRID_COLS  = 6;
    private static final int GRID_ROWS_VISIBLE = 3;
    private static final int GRID_PAD_X = 4;  // horizontal gap between cards
    private static final int GRID_PAD_Y = 3;  // vertical gap between cards

    // ── Colors ───────────────────────────────────────────────────────────────
    private static final int C_TEXT       = 0xFFE8EAF0;
    private static final int C_TEXT_DIM   = 0xFF7A8A9A;
    private static final int C_TEXT_SMALL = 0xFFAABBCC;
    private static final int C_ACCENT     = 0xFF4AC8EA;
    private static final int C_COST_TEXT  = 0xFFFFCC44;
    private static final int C_SEP        = 0xFF1E3050;
    private static final int C_BG         = 0xFF1A1A2E;
    private static final int C_BG_DARK    = 0xFF0D2137;
    private static final int C_BORDER     = 0xFF2A5A8A;
    private static final int C_BTN_BUY    = 0xFF1A5C2A;
    private static final int C_BTN_CLOSE  = 0xFF1E3A5F;

    // ── State ────────────────────────────────────────────────────────────────
    private JsonObject shopData    = new JsonObject();
    private String     shopName    = "Shop";
    private String     shopDesc    = "";
    private String     displayMode = "TABS";
    private String     titleColor  = "gold";
    private int        tabIdx      = 0;
    private int        scrollY     = 0;
    private int        maxScrollY  = 0;
    // Scrollbar drag state
    private boolean    sbDragging  = false;
    private int        sbX, sbY, sbH, sbW; // scrollbar track bounds (set during render)

    // Layout-driven rendering (parsed from JSON, null = use hardcoded defaults)
    private JsonObject layoutData  = null;
    private JsonObject itemCardData = null;
    // Parsed item card values (defaults match hardcoded constants)
    private int icCardW = 41, icCardH = 36;
    private int icIconOX = 13, icIconOY = 7, icIconSz = 15;
    private int icCostOX = 0, icCostOY = 26, icCostIconSz = 9, icCostGap = 2, icCostTextIconGap = -1, icCostIconOY = 0;
    private float icCostScale = 0.85f;
    private int icCostColor = C_COST_TEXT;

    // Dynamic background texture (from server-uploaded image)
    private Identifier customBgTexId = null;
    private int customBgW = 0, customBgH = 0;

    // Confirm overlay colors (parsed from confirmLayout, defaults match hardcoded constants)
    private int cfW = 340;
    private int cfBg = C_BG, cfBorder = C_BORDER, cfAccent = C_ACCENT, cfHeaderBg = C_BG_DARK;
    private int cfTitle = C_ACCENT, cfLabel = C_TEXT_DIM, cfCost = C_COST_TEXT, cfArrow = C_ACCENT;
    private int cfSliderBg = 0xFF1A2A4A, cfSliderFill = 0xFF357EA3, cfSliderThumb = C_ACCENT;
    private int cfSep = C_SEP, cfBtnConfirm = 0xFF55FF55, cfBtnCancel = 0xFFFF6666;
    private int cfColumnBg = 0x18FFFFFF;

    // Confirm overlay
    private boolean    showConfirm    = false;
    private String     cShopId, cSetId, cItemId;
    private String     cName          = "";
    private JsonArray  cOutputs       = new JsonArray();
    private JsonArray  cCosts         = new JsonArray();
    private int        cQty           = 1;
    private boolean    sliderDragging = false;
    private int        sliderLeft, sliderTop, sliderW = 1, sliderH = 10;
    // Confirm column scroll state
    private int cfCostScrollY = 0, cfOutScrollY = 0;
    private int cfCostMaxScroll = 0, cfOutMaxScroll = 0;
    // Confirm column screen positions (set during render)
    private int cfCostAreaX, cfCostAreaY, cfCostAreaW, cfCostAreaH;
    private int cfOutAreaX, cfOutAreaY, cfOutAreaW, cfOutAreaH;

    // Panel bounds (set during render, screen coords)
    private int px, py; // top-left of shop.png on screen

    private final List<Widget> widgets = new ArrayList<>();
    private List<Text> tooltipTexts;

    // Resolved CheeseItems data: ciId → dynamic texture + display name
    private final Map<String, Identifier> ciTextures = new HashMap<>();
    private final Map<String, String> ciDisplayNames = new HashMap<>();
    private final Map<String, int[]> ciTexSizes = new HashMap<>(); // ciId → [w, h]

    // Server-resolved tooltips: itemId → JsonArray of serialized Text JSON strings
    private JsonObject resolvedTooltipData;

    public ShopViewScreen() { super(Text.translatable("cheesemarket.screen.shop")); }

    @Override public boolean shouldPause()      { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }

    // ── Data ingestion ───────────────────────────────────────────────────────
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("CheeseMarket");

    public void receiveData(JsonObject data) {
        shopData    = data;
        shopName    = gs("shopName", "Shop");
        shopDesc    = gs("description", "");
        displayMode = gs("displayMode", "TABS");
        titleColor  = gs("titleColor", "gold");
        scrollY = 0; tabIdx = 0;

        // Parse layout if present
        if (data.has("layout") && data.get("layout").isJsonObject()) {
            layoutData = data.getAsJsonObject("layout");
            int elCount = layoutData.has("elements") ? layoutData.getAsJsonArray("elements").size() : 0;
            LOGGER.info("[Market/Client] Layout received: {} elements, has itemCard: {}", elCount, layoutData.has("itemCard"));
            if (layoutData.has("itemCard") && layoutData.get("itemCard").isJsonObject()) {
                itemCardData = layoutData.getAsJsonObject("itemCard");
                icCardW = jInt(itemCardData, "cardWidth", 41);
                icCardH = jInt(itemCardData, "cardHeight", 36);
                icIconOX = jInt(itemCardData, "iconOffsetX", 13);
                icIconOY = jInt(itemCardData, "iconOffsetY", 7);
                icIconSz = jInt(itemCardData, "iconSize", 15);
                icCostOX = jInt(itemCardData, "costOffsetX", 0);
                icCostOY = jInt(itemCardData, "costOffsetY", 26);
                icCostScale = itemCardData.has("costTextScale") ? itemCardData.get("costTextScale").getAsFloat() : 0.85f;
                icCostIconSz = jInt(itemCardData, "costIconSize", 9);
                icCostGap = jInt(itemCardData, "costGap", 2);
                icCostTextIconGap = jInt(itemCardData, "costTextIconGap", -1);
                icCostIconOY = jInt(itemCardData, "costIconOffsetY", 0);
                String ctc = itemCardData.has("costTextColor") ? itemCardData.get("costTextColor").getAsString() : null;
                icCostColor = ctc != null ? parseMCColor(ctc) : C_COST_TEXT;
            }
            // Parse confirm layout colors
            if (layoutData.has("confirmLayout") && layoutData.get("confirmLayout").isJsonObject()) {
                JsonObject cl = layoutData.getAsJsonObject("confirmLayout");
                cfW = jInt(cl, "width", 340);
                if (cl.has("bgColor")) cfBg = parseMCColor(cl.get("bgColor").getAsString());
                if (cl.has("borderColor")) cfBorder = parseMCColor(cl.get("borderColor").getAsString());
                if (cl.has("accentColor")) cfAccent = parseMCColor(cl.get("accentColor").getAsString());
                if (cl.has("headerBgColor")) cfHeaderBg = parseMCColor(cl.get("headerBgColor").getAsString());
                if (cl.has("titleColor")) cfTitle = parseMCColor(cl.get("titleColor").getAsString());
                if (cl.has("labelColor")) cfLabel = parseMCColor(cl.get("labelColor").getAsString());
                if (cl.has("costColor")) cfCost = parseMCColor(cl.get("costColor").getAsString());
                if (cl.has("arrowColor")) cfArrow = parseMCColor(cl.get("arrowColor").getAsString());
                if (cl.has("sliderBgColor")) cfSliderBg = parseMCColor(cl.get("sliderBgColor").getAsString());
                if (cl.has("sliderFillColor")) cfSliderFill = parseMCColor(cl.get("sliderFillColor").getAsString());
                if (cl.has("sliderThumbColor")) cfSliderThumb = parseMCColor(cl.get("sliderThumbColor").getAsString());
                if (cl.has("sepColor")) cfSep = parseMCColor(cl.get("sepColor").getAsString());
                if (cl.has("btnConfirmTextColor")) cfBtnConfirm = parseMCColor(cl.get("btnConfirmTextColor").getAsString());
                if (cl.has("btnCancelTextColor")) cfBtnCancel = parseMCColor(cl.get("btnCancelTextColor").getAsString());
                if (cl.has("columnBgColor")) {
                    try { cfColumnBg = (int) Long.parseLong(cl.get("columnBgColor").getAsString(), 16); } catch (Exception ignored) {}
                }
            }
            // Log layout element positions for debugging
            if (layoutData.has("elements")) {
                for (com.google.gson.JsonElement el : layoutData.getAsJsonArray("elements")) {
                    com.google.gson.JsonObject o = el.getAsJsonObject();
                    LOGGER.info("[Market/Client]   Element type={} x={} y={} w={} h={}",
                        o.has("type") ? o.get("type").getAsString() : "?",
                        jInt(o, "x", 0), jInt(o, "y", 0), jInt(o, "width", 0), jInt(o, "height", 0));
                }
            }
        } else {
            LOGGER.info("[Market/Client] No layout in shop data (using defaults)");
            layoutData = null;
            itemCardData = null;
        }

        // Load custom background image if provided as base64
        if (data.has("backgroundImageBase64")) {
            try {
                byte[] imgBytes = java.util.Base64.getDecoder().decode(data.get("backgroundImageBase64").getAsString());
                NativeImage nativeImg = NativeImage.read(new ByteArrayInputStream(imgBytes));
                NativeImageBackedTexture dynTex = new NativeImageBackedTexture(nativeImg);
                String shopId = gs("shopId", "unknown");
                customBgTexId = Identifier.of("cheesemarket", "dynamic/shop_bg_" + shopId);
                MinecraftClient.getInstance().getTextureManager().registerTexture(customBgTexId, dynTex);
                customBgW = nativeImg.getWidth();
                customBgH = nativeImg.getHeight();
                LOGGER.info("[Market/Client] Custom background loaded: {}x{}", customBgW, customBgH);
            } catch (Exception e) {
                LOGGER.error("[Market/Client] Failed to load custom background: {}", e.getMessage());
                customBgTexId = null;
            }
        }
        // When no backgroundImageBase64 (skipImages=true after purchase), keep existing texture

        // Parse resolved CheeseItems data — register icon images as dynamic textures
        if (data.has("resolvedCheeseItems") && data.get("resolvedCheeseItems").isJsonObject()) {
            JsonObject rci = data.getAsJsonObject("resolvedCheeseItems");
            LOGGER.info("[Market/Client] Received resolvedCheeseItems with {} entries", rci.size());
            for (var entry : rci.entrySet()) {
                String ciId = entry.getKey();
                JsonObject val = entry.getValue().getAsJsonObject();
                if (val.has("displayName")) ciDisplayNames.put(ciId, val.get("displayName").getAsString());
                if (val.has("image")) {
                    try {
                        byte[] imgBytes = java.util.Base64.getDecoder().decode(val.get("image").getAsString());
                        NativeImage nImg = NativeImage.read(new ByteArrayInputStream(imgBytes));
                        NativeImageBackedTexture dynTex = new NativeImageBackedTexture(nImg);
                        Identifier texId = Identifier.of("cheesemarket", "dynamic/ci_" + ciId);
                        // Clean up previous texture if any
                        if (ciTextures.containsKey(ciId)) {
                            MinecraftClient.getInstance().getTextureManager().destroyTexture(ciTextures.get(ciId));
                        }
                        MinecraftClient.getInstance().getTextureManager().registerTexture(texId, dynTex);
                        ciTextures.put(ciId, texId);
                        ciTexSizes.put(ciId, new int[]{nImg.getWidth(), nImg.getHeight()});
                        LOGGER.info("[Market/Client]   Loaded icon for {} ({}x{})", ciId, nImg.getWidth(), nImg.getHeight());
                    } catch (Exception e) {
                        LOGGER.warn("[Market/Client] Failed to load CheeseItems icon for {}: {}", ciId, e.getMessage());
                    }
                } else {
                    LOGGER.warn("[Market/Client]   No image data for {}", ciId);
                }
            }
        } else {
            LOGGER.info("[Market/Client] No resolvedCheeseItems in shop data");
        }

        // Parse server-resolved tooltips (serialized Text JSON for exact vanilla tooltip rendering)
        if (data.has("resolvedTooltips") && data.get("resolvedTooltips").isJsonObject()) {
            resolvedTooltipData = data.getAsJsonObject("resolvedTooltips");
            LOGGER.info("[Market/Client] Received resolvedTooltips for {} items", resolvedTooltipData.size());
        }
    }

    public void receivePurchaseResult(String result) {
        showConfirm = false;
        cQty = 1;
    }

    // ── Render ───────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        widgets.clear(); tooltipTexts = null;

        // Center shop.png on screen
        px = this.width / 2 - SHOP_TEX_W / 2;
        py = this.height / 2 - SHOP_TEX_H / 2;

        // Dim background
        ctx.fill(0, 0, this.width, this.height, 0x88000000);

        // Draw background — custom uploaded image or default texture
        if (customBgTexId != null) {
            ctx.drawTexture(customBgTexId, px, py, 0, 0, SHOP_TEX_W, SHOP_TEX_H, customBgW, customBgH);
        } else {
            ctx.drawTexture(TEX_SHOP, px, py, 0, 0, SHOP_TEX_W, SHOP_TEX_H, SHOP_TEX_W, SHOP_TEX_H);
        }

        // ── Layout-driven rendering ──
        JsonObject titleEl = findLayoutElement("TEXT", "{shopName}");
        JsonObject descEl  = findLayoutElement("TEXT", "{shopDesc}");
        JsonObject closeEl = findLayoutElement("BUTTON", "close");
        JsonObject gridEl  = findLayoutElement("ITEM_GRID", null);

        // Shop name — x is LEFT EDGE of box, text positioned by align within box width
        {
            int titleBoxX = titleEl != null ? jInt(titleEl, "x", 50) : 50;
            int titleBoxW = titleEl != null ? jInt(titleEl, "width", 250) : 250;
            int titleY = titleEl != null ? jInt(titleEl, "y", 38) : RED_Y + RED_H / 2 - 4 - 40;
            String titleAlign = "center";
            boolean titleBold = false;
            int tc = parseMCColor(titleColor);
            float titleScale = 1f;
            if (titleEl != null && titleEl.has("props")) {
                JsonObject tp = titleEl.getAsJsonObject("props");
                if (tp.has("align")) titleAlign = tp.get("align").getAsString();
                if (tp.has("bold")) titleBold = tp.get("bold").getAsBoolean();
                if (tp.has("color")) {
                    String c = tp.get("color").getAsString();
                    if (!c.equals("{titleColor}")) tc = parseMCColor(c);
                }
                titleScale = textEffectiveScale(tp);
            }
            renderAlignedText(ctx, shopName, px + titleBoxX, py + titleY, titleBoxW, titleScale, titleAlign, tc, titleBold);

            // Description below name
            if (!shopDesc.isEmpty()) {
                int descBoxX = descEl != null ? jInt(descEl, "x", 50) : titleBoxX;
                int descBoxW = descEl != null ? jInt(descEl, "width", 250) : titleBoxW;
                float descScale = 7f / 9f * 0.75f; // default: fontSize=7, scale=0.75
                String descAlign = "center";
                int descColor = C_TEXT_DIM;
                boolean descBold = false;
                if (descEl != null && descEl.has("props")) {
                    JsonObject dp = descEl.getAsJsonObject("props");
                    descScale = textEffectiveScale(dp);
                    if (dp.has("align")) descAlign = dp.get("align").getAsString();
                    if (dp.has("bold")) descBold = dp.get("bold").getAsBoolean();
                    if (dp.has("color")) {
                        String dc = dp.get("color").getAsString();
                        if (!dc.equals("{titleColor}")) descColor = parseMCColor(dc);
                    }
                }
                int descY = descEl != null ? jInt(descEl, "y", 50) : titleY + 12;
                renderAlignedText(ctx, shopDesc, px + descBoxX, py + descY, descBoxW, descScale, descAlign, descColor, descBold);
            }
        }

        // Close button (close.png)
        {
            int closeX, closeY;
            if (closeEl != null) {
                closeX = px + jInt(closeEl, "x", 312);
                closeY = py + jInt(closeEl, "y", 38);
            } else {
                closeX = px + CONTENT_X2 - CLOSE_TEX + 14;
                closeY = py + RED_Y + RED_H / 2 - CLOSE_TEX / 2 - 40;
            }
            ctx.drawTexture(TEX_CLOSE, closeX, closeY, 0, 0, CLOSE_TEX, CLOSE_TEX, CLOSE_TEX, CLOSE_TEX);
            if (hov(mx, my, closeX, closeY, CLOSE_TEX, CLOSE_TEX))
                ctx.fill(closeX, closeY, closeX + CLOSE_TEX, closeY + CLOSE_TEX, 0x33FFFFFF);
            widgets.add(new Widget(closeX, closeY, CLOSE_TEX, CLOSE_TEX, "_close"));
        }

        // Item grid in content zone (scissored)
        {
            int gx, gy, gw, gh;
            if (gridEl != null) {
                gx = px + jInt(gridEl, "x", CONTENT_X);
                gy = py + jInt(gridEl, "y", CONTENT_Y);
                gw = jInt(gridEl, "width", CONTENT_W);
                gh = jInt(gridEl, "height", CONTENT_H);
            } else {
                gx = px + CONTENT_X; gy = py + CONTENT_Y; gw = CONTENT_W; gh = CONTENT_H;
            }
            if (!showConfirm) {
                ctx.enableScissor(gx, gy, gx + gw, gy + gh);
                renderItemGrid(ctx, mx, my, gx, gy, gw, gh);
                ctx.disableScissor();
                // Scrollbar drawn OUTSIDE scissor so it's not clipped
                renderScrollbar(ctx);
            }
        }

        // ── Custom elements (TEXT/BUTTON not already handled above) ──
        // Track IDs of elements already rendered to avoid duplicates
        Set<String> handledIds = new HashSet<>();
        if (titleEl != null && titleEl.has("id")) handledIds.add(titleEl.get("id").getAsString());
        if (descEl != null && descEl.has("id")) handledIds.add(descEl.get("id").getAsString());
        if (closeEl != null && closeEl.has("id")) handledIds.add(closeEl.get("id").getAsString());
        if (gridEl != null && gridEl.has("id")) handledIds.add(gridEl.get("id").getAsString());

        if (layoutData != null && layoutData.has("elements")) {
            for (JsonElement rawEl : layoutData.getAsJsonArray("elements")) {
                JsonObject el = rawEl.getAsJsonObject();
                String elType = el.has("type") ? el.get("type").getAsString() : "";
                String elId = el.has("id") ? el.get("id").getAsString() : "";
                JsonObject props = el.has("props") ? el.getAsJsonObject("props") : new JsonObject();

                // Skip already-handled elements (by ID) and non-renderable types
                if (!elId.isEmpty() && handledIds.contains(elId)) continue;
                if ("BACKGROUND".equals(elType) || "ITEM_GRID".equals(elType) || "IMAGE".equals(elType)) continue;

                int ex = jInt(el, "x", 0), ey = jInt(el, "y", 0);
                int ew = jInt(el, "width", 50), eh = jInt(el, "height", 20);

                if ("TEXT".equals(elType)) {
                    String text = (props.has("text") ? props.get("text").getAsString() : "")
                        .replace("{shopName}", shopName).replace("{shopDesc}", shopDesc);
                    float scale = textEffectiveScale(props);
                    String align = props.has("align") ? props.get("align").getAsString() : "center";
                    String colorStr = props.has("color") ? props.get("color").getAsString() : "FFFFFF";
                    int color = "{titleColor}".equals(colorStr) ? parseMCColor(titleColor) : parseMCColor(colorStr);
                    boolean bold = props.has("bold") && props.get("bold").getAsBoolean();
                    renderAlignedText(ctx, text, px + ex, py + ey, ew, scale, align, color, bold);
                } else if ("BUTTON".equals(elType)) {
                    String btnText = props.has("text") ? props.get("text").getAsString() : "";
                    int bgColor = props.has("color") ? parseMCColor(props.get("color").getAsString()) : C_BTN_CLOSE;
                    int txtColor = props.has("textColor") ? parseMCColor(props.get("textColor").getAsString()) : C_TEXT;
                    ctx.fill(px + ex, py + ey, px + ex + ew, py + ey + eh, bgColor);
                    int btnTw = tw(btnText);
                    dt(ctx, btnText, px + ex + ew / 2 - btnTw / 2, py + ey + eh / 2 - 4, txtColor);
                    String action = props.has("action") ? props.get("action").getAsString() : "";
                    if (!action.isEmpty()) widgets.add(new Widget(px + ex, py + ey, ew, eh, "_custom:" + action));
                }
            }
        }

        // Confirm overlay
        if (showConfirm) renderConfirm(ctx, mx, my);

        // Tooltip — use vanilla Screen.setTooltip() so renderWithTooltip() draws it identically to inventory
        if (tooltipTexts != null && !tooltipTexts.isEmpty()) {
            List<OrderedText> ordered = new ArrayList<>();
            for (Text t : tooltipTexts) ordered.add(t.asOrderedText());
            this.setTooltip(ordered);
        }
    }

    /** Find a layout element by type and optional text/action match. */
    private JsonObject findLayoutElement(String type, String match) {
        if (layoutData == null || !layoutData.has("elements")) return null;
        for (JsonElement el : layoutData.getAsJsonArray("elements")) {
            JsonObject o = el.getAsJsonObject();
            if (!type.equals(o.has("type") ? o.get("type").getAsString() : "")) continue;
            if (match == null) return o;
            JsonObject props = o.has("props") ? o.getAsJsonObject("props") : null;
            if (props == null) continue;
            if (type.equals("TEXT") && match.equals(props.has("text") ? props.get("text").getAsString() : "")) return o;
            if (type.equals("BUTTON") && match.equals(props.has("action") ? props.get("action").getAsString() : "")) return o;
        }
        return null;
    }

    // ── Item Grid ────────────────────────────────────────────────────────────
    private void renderItemGrid(DrawContext ctx, int mx, int my, int areaX, int areaY, int areaW, int areaH) {
        JsonArray sets = activeSets();
        if (sets.isEmpty()) { dt(ctx, "Aucun article.", areaX + 8, areaY + 10, C_TEXT_DIM); return; }

        // Get current set items
        JsonObject set = sets.get(Math.min(tabIdx, sets.size() - 1)).getAsJsonObject();
        String shopId = gs("shopId", ""), setId = sp(set, "id", "");
        JsonArray items = set.has("items") ? set.getAsJsonArray("items") : new JsonArray();
        if (items.isEmpty()) { dt(ctx, "Aucun article.", areaX + 8, areaY + 10, C_TEXT_DIM); return; }

        // Grid params from layout or hardcoded defaults
        int gridCols = GRID_COLS, gridPadX = GRID_PAD_X, gridPadY = GRID_PAD_Y;
        if (layoutData != null) {
            JsonObject ge = findLayoutElement("ITEM_GRID", null);
            if (ge != null && ge.has("props")) {
                JsonObject gp = ge.getAsJsonObject("props");
                gridCols = jInt(gp, "cols", GRID_COLS);
                gridPadX = jInt(gp, "padX", GRID_PAD_X);
                gridPadY = jInt(gp, "padY", GRID_PAD_Y);
            }
        }

        // Use layout item card dimensions
        int cardW = icCardW, cardH = icCardH;

        // Calculate grid positioning — center the grid horizontally
        int totalGridW = gridCols * cardW + (gridCols - 1) * gridPadX;
        int gridStartX = areaX + (areaW - totalGridW) / 2;
        int gridStartY = areaY + 4;

        // Calculate max scroll
        int totalRows = (items.size() + gridCols - 1) / gridCols;
        int totalGridH = totalRows * cardH + (totalRows - 1) * gridPadY;
        maxScrollY = Math.max(0, totalGridH - areaH + 8);
        scrollY = Math.min(scrollY, maxScrollY);

        // When all items fit on one page, center the grid vertically
        boolean fitsOnePage = maxScrollY == 0;
        if (fitsOnePage) {
            gridStartY = areaY + (areaH - totalGridH) / 2;
        }

        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            String itemId = sp(item, "id", "");
            boolean canBuy = !item.has("canBuy") || item.get("canBuy").getAsBoolean();

            String iconId = effectiveIcon(item);
            String rawName = sp(item, "displayName", "");
            String name = rawName.isBlank() ? xlat(iconId) : rawName;
            JsonArray costs = item.has("costs") ? item.getAsJsonArray("costs") : new JsonArray();

            int col = i % gridCols;
            int row = i / gridCols;

            // Center incomplete rows horizontally when items fit on one page
            int cardRowX = gridStartX;
            if (fitsOnePage) {
                int itemsInRow = Math.min(gridCols, items.size() - row * gridCols);
                if (itemsInRow < gridCols) {
                    int rowW = itemsInRow * cardW + (itemsInRow - 1) * gridPadX;
                    cardRowX = areaX + (areaW - rowW) / 2;
                }
            }

            int cx = cardRowX + col * (cardW + gridPadX);
            int cy = gridStartY + row * (cardH + gridPadY) - scrollY;

            // Skip if off-screen
            if (cy + cardH < areaY || cy > areaY + areaH) continue;

            // Draw item.png as card background (scaled to card size)
            ctx.drawTexture(TEX_ITEM, cx, cy, 0, 0, cardW, cardH, ITEM_TEX_W, ITEM_TEX_H);

            boolean isHov = hov(mx, my, cx, cy, cardW, cardH) && canBuy && !showConfirm
                    && my >= areaY && my < areaY + areaH;

            if (isHov) ctx.fill(cx, cy, cx + cardW, cy + cardH, 0x22FFFFFF);
            if (!canBuy) ctx.fill(cx, cy, cx + cardW, cy + cardH, 0x88000000);

            // Item icon — layout-driven
            int iconX = cx + icIconOX;
            int iconY = cy + icIconOY;
            renderItem3D(ctx, iconId, iconX, iconY, icIconSz);

            // Cost row — layout-driven
            int costY = cy + icCostOY;
            float costScale = icCostScale;
            int costIconSz = icCostIconSz;
            if (!costs.isEmpty()) {
                int maxC = Math.min(costs.size(), 3);
                int totalRowW = 0;
                for (int ci = 0; ci < maxC; ci++) {
                    int cnt = costs.get(ci).getAsJsonObject().has("count") ? costs.get(ci).getAsJsonObject().get("count").getAsInt() : 1;
                    String cntStr = cnt > 99 ? "99+" : String.valueOf(cnt);
                    totalRowW += (int)(tw(cntStr) * costScale) + icCostTextIconGap + costIconSz + (ci < maxC - 1 ? icCostGap : 0);
                }
                int rowX = cx + cardW / 2 - totalRowW / 2 + icCostOX;
                for (int ci = 0; ci < maxC; ci++) {
                    JsonObject ce = costs.get(ci).getAsJsonObject();
                    int cnt = ce.has("count") ? ce.get("count").getAsInt() : 1;
                    String cntStr = cnt > 99 ? "99+" : String.valueOf(cnt);
                    ctx.getMatrices().push();
                    ctx.getMatrices().translate(rowX, costY + 2, 0);
                    ctx.getMatrices().scale(costScale, costScale, 1f);
                    dt(ctx, cntStr, 0, 0, icCostColor);
                    ctx.getMatrices().pop();
                    rowX += (int)(tw(cntStr) * costScale) + icCostTextIconGap;
                    renderItem3D(ctx, sp(ce, "itemId", ""), rowX, costY - 1 + icCostIconOY, costIconSz);
                    rowX += costIconSz + icCostGap;
                }
                if (costs.size() > 3) dt(ctx, "\u2026", rowX, costY + 2, C_TEXT_DIM);
            } else {
                dt(ctx, "Gratuit", cx + cardW / 2 - tw("Gratuit") / 2, costY + 2, 0xFF66BB44);
            }

            // Tooltip on hover — full item info (title, lore, id) + costs
            if (isHov) {
                tooltipTexts = buildItemTooltip(iconId, rawName.isBlank() ? null : rawName);
                tooltipTexts.add(Text.empty());
                if (!costs.isEmpty()) {
                    tooltipTexts.add(Text.literal("Co\u00fbt:").formatted(Formatting.GRAY));
                    for (int ci = 0; ci < costs.size(); ci++) {
                        JsonObject ce = costs.get(ci).getAsJsonObject();
                        int cnt = ce.has("count") ? ce.get("count").getAsInt() : 1;
                        tooltipTexts.add(Text.literal("  " + cnt + "\u00d7 " + xlat(sp(ce, "itemId", ""))).formatted(Formatting.GOLD));
                    }
                } else {
                    tooltipTexts.add(Text.literal("Gratuit").formatted(Formatting.GREEN));
                }
            }

            // Click widget
            if (canBuy && !showConfirm)
                widgets.add(new Widget(cx, cy, ITEM_TEX_W, ITEM_TEX_H, "_buy:" + shopId + ":" + setId + ":" + itemId));
        }

        // Scrollbar coordinates saved for renderScrollbar() called after scissor
        if (maxScrollY > 0) {
            this.sbW = 2;
            this.sbX = areaX + areaW - 4;
            this.sbY = areaY + 4;
            this.sbH = areaH - 8;
        }
    }

    /** Renders the shop grid scrollbar — called AFTER disableScissor(). */
    private void renderScrollbar(DrawContext ctx) {
        if (maxScrollY <= 0) return;

        // Track background
        ctx.fill(sbX, sbY + 1, sbX + sbW, sbY + sbH - 1, 0x33BABABB);

        // Thumb
        float visibleRatio = (float) (sbH + 8) / (sbH + 8 + maxScrollY); // areaH approx
        int thumbH = Math.max(10, (int) (sbH * visibleRatio));
        float scrollRatio = maxScrollY > 0 ? (float) scrollY / maxScrollY : 0;
        int thumbY = sbY + (int) ((sbH - thumbH) * scrollRatio);

        int thumbColor = sbDragging ? 0xFFDDDDDD : 0xFFBABABB;
        ctx.fill(sbX, thumbY + 1, sbX + sbW, thumbY + thumbH - 1, thumbColor);
        // Rounded caps
        ctx.fill(sbX, thumbY, sbX + sbW, thumbY + 1, (thumbColor & 0x00FFFFFF) | 0x99000000);
        ctx.fill(sbX, thumbY + thumbH - 1, sbX + sbW, thumbY + thumbH, (thumbColor & 0x00FFFFFF) | 0x99000000);
    }

    // ── Confirm overlay (uses confirm.png texture) ─────────────────────────
    private void renderConfirm(DrawContext ctx, int mx, int my) {
        // Dim everything
        ctx.fill(0, 0, this.width, this.height, 0x77000000);

        // Draw confirm.png centered on screen
        int bx = this.width / 2 - CONFIRM_TEX_W / 2;
        int by = this.height / 2 - CONFIRM_TEX_H / 2;
        ctx.drawTexture(TEX_CONFIRM, bx, by, 0, 0, CONFIRM_TEX_W, CONFIRM_TEX_H, CONFIRM_TEX_W, CONFIRM_TEX_H);

        // ── Costs column (left rectangle, scrollable) ──
        int rowH = 24;
        cfCostAreaX = bx + CF_COST_X; cfCostAreaY = by + CF_COST_Y;
        cfCostAreaW = CF_COST_W; cfCostAreaH = CF_COST_H;
        int costTotalH = Math.max(1, cCosts.size()) * rowH;
        cfCostMaxScroll = Math.max(0, costTotalH - cfCostAreaH);
        cfCostScrollY = clamp(cfCostScrollY, 0, cfCostMaxScroll);

        ctx.enableScissor(cfCostAreaX, cfCostAreaY, cfCostAreaX + cfCostAreaW, cfCostAreaY + cfCostAreaH);
        renderConfirmColumn(ctx, cCosts, cQty, cfCostAreaX + 1, cfCostAreaY + 1 - cfCostScrollY, cfCostAreaW - 2, cfCostAreaH - 2, mx, my);
        ctx.disableScissor();
        if (cfCostMaxScroll > 0) {
            if (cfCostScrollY < cfCostMaxScroll) ctx.fill(cfCostAreaX, cfCostAreaY + cfCostAreaH - 3, cfCostAreaX + cfCostAreaW, cfCostAreaY + cfCostAreaH, 0x88000000);
            if (cfCostScrollY > 0) ctx.fill(cfCostAreaX, cfCostAreaY, cfCostAreaX + cfCostAreaW, cfCostAreaY + 3, 0x88000000);
        }

        // ── Outputs column (right rectangle, scrollable) ──
        cfOutAreaX = bx + CF_OUT_X; cfOutAreaY = by + CF_OUT_Y;
        cfOutAreaW = CF_OUT_W; cfOutAreaH = CF_OUT_H;
        int outTotalH = Math.max(1, cOutputs.size()) * rowH;
        cfOutMaxScroll = Math.max(0, outTotalH - cfOutAreaH);
        cfOutScrollY = clamp(cfOutScrollY, 0, cfOutMaxScroll);

        ctx.enableScissor(cfOutAreaX, cfOutAreaY, cfOutAreaX + cfOutAreaW, cfOutAreaY + cfOutAreaH);
        renderConfirmColumn(ctx, cOutputs, cQty, cfOutAreaX + 1, cfOutAreaY + 1 - cfOutScrollY, cfOutAreaW - 2, cfOutAreaH - 2, mx, my);
        ctx.disableScissor();
        if (cfOutMaxScroll > 0) {
            if (cfOutScrollY < cfOutMaxScroll) ctx.fill(cfOutAreaX, cfOutAreaY + cfOutAreaH - 3, cfOutAreaX + cfOutAreaW, cfOutAreaY + cfOutAreaH, 0x88000000);
            if (cfOutScrollY > 0) ctx.fill(cfOutAreaX, cfOutAreaY, cfOutAreaX + cfOutAreaW, cfOutAreaY + 3, 0x88000000);
        }

        // ── Quantity display above slider ──
        String qtyText = "Quantit\u00e9: " + cQty;
        dt(ctx, qtyText, bx + CONFIRM_TEX_W / 2 - tw(qtyText) / 2, by + CF_QTY_Y, 0xFFBABABB);

        // ── Slider ──
        sliderLeft = bx + CF_SL_X; sliderTop = by + CF_SL_Y; sliderW = CF_SL_W; sliderH = CF_SL_H;
        int filled = (int) ((cQty - 1) / 63.0f * sliderW);
        if (filled > 0) ctx.fill(sliderLeft, sliderTop, sliderLeft + filled, sliderTop + sliderH, 0xFFBABABB);
        // Slider handle (slider.png)
        int handleX = sliderLeft + filled - SLIDER_TEX_W / 2;
        int handleY = sliderTop + sliderH / 2 - SLIDER_TEX_H / 2;
        ctx.drawTexture(TEX_SLIDER, handleX, handleY, 0, 0, SLIDER_TEX_W, SLIDER_TEX_H, SLIDER_TEX_W, SLIDER_TEX_H);
        widgets.add(new Widget(sliderLeft - 6, sliderTop - 4, sliderW + 12, sliderH + 8, "_slider"));

        // ── Buttons ──
        // Left: Fermer
        int blX = bx + CF_BTN_L_X, blY = by + CF_BTN_L_Y;
        if (hov(mx, my, blX, blY, CF_BTN_L_W, CF_BTN_L_H))
            ctx.fill(blX, blY, blX + CF_BTN_L_W, blY + CF_BTN_L_H, 0x22FFFFFF);
        String fermerLabel = "Fermer";
        dt(ctx, fermerLabel, blX + CF_BTN_L_W / 2 - tw(fermerLabel) / 2, blY + CF_BTN_L_H / 2 - 4, cfBtnCancel);
        widgets.add(new Widget(blX, blY, CF_BTN_L_W, CF_BTN_L_H, "_cancel"));
        // Right: Confirmer
        int brX = bx + CF_BTN_R_X, brY = by + CF_BTN_R_Y;
        if (hov(mx, my, brX, brY, CF_BTN_R_W, CF_BTN_R_H))
            ctx.fill(brX, brY, brX + CF_BTN_R_W, brY + CF_BTN_R_H, 0x22FFFFFF);
        String confirmLabel = "Confirmer";
        dt(ctx, confirmLabel, brX + CF_BTN_R_W / 2 - tw(confirmLabel) / 2, brY + CF_BTN_R_H / 2 - 4, cfBtnConfirm);
        widgets.add(new Widget(brX, brY, CF_BTN_R_W, CF_BTN_R_H, "_do_buy"));
    }

    /** Renders item column for confirm modal — large icons (20px) + quantity, centered in rectangle. */
    private void renderConfirmColumn(DrawContext ctx, JsonArray arr, int multiplier, int colX, int colY, int colW, int colH, int mx, int my) {
        if (arr == null || arr.isEmpty()) { dt(ctx, "\u2014", colX + colW / 2 - tw("\u2014") / 2, colY + colH / 2 - 4, cfLabel); return; }

        // Convert numismatic coins to proper denominations
        JsonArray displayArr = convertNumismaticCoins(arr, multiplier);

        int iconSz = 16, rowH = 15;
        float textScale = 1.0f;
        int totalH = displayArr.size() * rowH;
        int offsetY = totalH <= colH ? (colH - totalH) / 2 : 0;
        for (int i = 0; i < displayArr.size(); i++) {
            JsonObject e = displayArr.get(i).getAsJsonObject();
            String itemId = sp(e, "itemId", "");
            int cnt = e.has("displayCount") ? e.get("displayCount").getAsInt() : (e.has("count") ? e.get("count").getAsInt() * multiplier : multiplier);
            String cs = cnt > 9999 ? "9999+" : String.valueOf(cnt);
            String label = "x" + cs;
            int textW = (int)(tw(label) * textScale);
            int itemW = iconSz + 1 + textW;
            int ix = colX + (colW - itemW) / 2;
            int ry = colY + offsetY + i * rowH;

            renderItem3D(ctx, itemId, ix, ry + 1, iconSz);
            ctx.getMatrices().push();
            ctx.getMatrices().translate(ix + iconSz + 1, ry + (rowH - 8) / 2.0 + 2, 0);
            ctx.getMatrices().scale(textScale, textScale, 1f);
            dt(ctx, label, 0, 0, cfCost);
            ctx.getMatrices().pop();

            if (hov(mx, my, colX, ry, colW, rowH)) {
                tooltipTexts = buildItemTooltip(itemId);
                tooltipTexts.add(Text.literal("Quantit\u00e9: " + cnt).formatted(Formatting.GOLD));
            }
        }
    }

    /**
     * Converts numismatic coin entries to proper denominations after multiplying.
     * 100 bronze = 1 silver, 100 silver = 1 gold.
     * Non-coin items are passed through with count * multiplier.
     */
    private JsonArray convertNumismaticCoins(JsonArray arr, int multiplier) {
        long totalBronze = 0;
        boolean hasCoins = false;
        JsonArray result = new JsonArray();

        // First pass: aggregate all coins into bronze value, pass through other items
        for (int i = 0; i < arr.size(); i++) {
            JsonObject e = arr.get(i).getAsJsonObject();
            String itemId = sp(e, "itemId", "");
            int baseCnt = e.has("count") ? e.get("count").getAsInt() : 1;
            int cnt = baseCnt * multiplier;

            if (itemId.equals("numismatic-overhaul:gold_coin")) {
                totalBronze += (long) cnt * 10000L;
                hasCoins = true;
            } else if (itemId.equals("numismatic-overhaul:silver_coin")) {
                totalBronze += (long) cnt * 100L;
                hasCoins = true;
            } else if (itemId.equals("numismatic-overhaul:bronze_coin")) {
                totalBronze += cnt;
                hasCoins = true;
            } else {
                // Non-coin item — pass through
                JsonObject copy = new JsonObject();
                copy.addProperty("itemId", itemId);
                copy.addProperty("displayCount", cnt);
                result.add(copy);
            }
        }

        if (!hasCoins) return result;

        // Convert total bronze to gold/silver/bronze
        int gold = (int) (totalBronze / 10000L);
        totalBronze %= 10000L;
        int silver = (int) (totalBronze / 100L);
        int bronze = (int) (totalBronze % 100L);

        if (gold > 0) {
            JsonObject g = new JsonObject();
            g.addProperty("itemId", "numismatic-overhaul:gold_coin");
            g.addProperty("displayCount", gold);
            result.add(g);
        }
        if (silver > 0) {
            JsonObject s = new JsonObject();
            s.addProperty("itemId", "numismatic-overhaul:silver_coin");
            s.addProperty("displayCount", silver);
            result.add(s);
        }
        if (bronze > 0) {
            JsonObject b = new JsonObject();
            b.addProperty("itemId", "numismatic-overhaul:bronze_coin");
            b.addProperty("displayCount", bronze);
            result.add(b);
        }

        return result;
    }

    // ── Mouse ────────────────────────────────────────────────────────────────
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        // Slider drag (confirm modal)
        if (showConfirm && sliderW > 1 && mx >= sliderLeft - 6 && mx <= sliderLeft + sliderW + 6 && my >= sliderTop - 4 && my <= sliderTop + sliderH + 4) {
            sliderDragging = true; updateSlider((int) mx); return true;
        }

        // Scrollbar drag (shop grid)
        if (!showConfirm && maxScrollY > 0 && mx >= sbX - 3 && mx <= sbX + sbW + 3 && my >= sbY && my <= sbY + sbH) {
            sbDragging = true; updateScrollbar((int) my); return true;
        }

        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            if (w.contains((int) mx, (int) my) && !w.action().isEmpty()) { act(w.action()); return true; }
        }

        // Click outside confirm = close
        if (showConfirm) {
            int cbx = this.width / 2 - CONFIRM_TEX_W / 2, cby = this.height / 2 - CONFIRM_TEX_H / 2;
            if (mx < cbx || mx > cbx + CONFIRM_TEX_W || my < cby || my > cby + CONFIRM_TEX_H) { showConfirm = false; return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (sliderDragging && button == 0) { updateSlider((int) mx); return true; }
        if (sbDragging && button == 0) { updateScrollbar((int) my); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (sliderDragging) { sliderDragging = false; return true; }
        if (sbDragging) { sbDragging = false; return true; }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (showConfirm) {
            int imx = (int) mx, imy = (int) my;
            if (hov(imx, imy, cfCostAreaX, cfCostAreaY, cfCostAreaW, cfCostAreaH))
                cfCostScrollY = clamp(cfCostScrollY - (int) (v * 10), 0, cfCostMaxScroll);
            else if (hov(imx, imy, cfOutAreaX, cfOutAreaY, cfOutAreaW, cfOutAreaH))
                cfOutScrollY = clamp(cfOutScrollY - (int) (v * 10), 0, cfOutMaxScroll);
        } else {
            scrollY = clamp(scrollY - (int) (v * 14), 0, maxScrollY);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (showConfirm) {
            if (key == 256) { showConfirm = false; cQty = 1; return true; }
            return true;
        }
        return super.keyPressed(key, scan, mod);
    }

    private void updateScrollbar(int my) {
        float t = (float) (my - sbY) / sbH;
        scrollY = clamp((int) (t * maxScrollY), 0, maxScrollY);
    }

    private void updateSlider(int mx) {
        float t = (float) (mx - sliderLeft) / sliderW;
        cQty = clamp(1 + (int) Math.round(t * 63), 1, 64);
    }

    private void act(String a) {
        if (a.equals("_close")) { close(); return; }
        if (a.startsWith("_custom:")) {
            String action = a.substring(8);
            if ("close".equals(action)) { close(); }
            return;
        }
        if (a.startsWith("_tab:")) { tabIdx = Integer.parseInt(a.substring(5)); scrollY = 0; return; }
        if (a.equals("_slider")) { return; }
        if (a.equals("_cancel")) { showConfirm = false; cQty = 1; return; }
        if (a.startsWith("_buy:")) {
            String[] p = a.substring(5).split(":", 3);
            if (p.length < 3) return;
            cShopId = p[0]; cSetId = p[1]; cItemId = p[2];
            cQty = 1;
            outer: for (JsonElement se : activeSets()) {
                JsonArray items = se.getAsJsonObject().has("items") ? se.getAsJsonObject().getAsJsonArray("items") : new JsonArray();
                for (JsonElement ie : items) {
                    JsonObject item = ie.getAsJsonObject();
                    if (p[2].equals(sp(item, "id", ""))) {
                        String icon = effectiveIcon(item);
                        String raw = sp(item, "displayName", "");
                        cName = raw.isBlank() ? xlat(icon) : raw;
                        cOutputs = item.has("outputs") ? item.getAsJsonArray("outputs") : new JsonArray();
                        cCosts = item.has("costs") ? item.getAsJsonArray("costs") : new JsonArray();
                        break outer;
                    }
                }
            }
            cfCostScrollY = 0; cfOutScrollY = 0;
            showConfirm = true; return;
        }
        if (a.equals("_do_buy")) {
            if (cShopId != null)
                ClientPlayNetworking.send(new ShopActionPayload("shop_buy:" + cShopId + ":" + cSetId + ":" + cItemId + ":" + cQty));
            showConfirm = false; cQty = 1; return;
        }
    }

    // ── 3D render ────────────────────────────────────────────────────────────
    // Cache for parsed ItemStacks with components (avoid re-parsing every frame)
    private static final java.util.Map<String, ItemStack> componentStackCache = new java.util.HashMap<>();

    private void renderItem3D(DrawContext ctx, String itemId, int x, int y, int sz) {
        if (itemId == null || itemId.isBlank()) return;
        // Fromage virtual currency → render cheese texture
        if ("cheesestore:fromage".equals(itemId)) {
            ctx.drawTexture(TEX_FROMAGE, x, y, sz, sz, 0, 0, 178, 178, 178, 178);
            return;
        }
        // CheeseItems custom items → render dynamic texture from server-sent PNG
        if (itemId.startsWith("cheeseitems:")) {
            String ciId = itemId.replace("cheeseitems:", "");
            Identifier texId = ciTextures.get(ciId);
            if (texId != null) {
                int[] dims = ciTexSizes.getOrDefault(ciId, new int[]{16, 16});
                ctx.drawTexture(texId, x, y, sz, sz, 0, 0, dims[0], dims[1], dims[0], dims[1]);
            } else {
                ctx.fill(x, y, x + sz, y + sz, 0x33FFFFFF);
            }
            return;
        }
        try {
            ItemStack stack;
            if (itemId.contains("[")) {
                // Item with components — parse with MC's item reader for correct model/color
                stack = componentStackCache.computeIfAbsent(itemId, s -> {
                    try {
                        var client = net.minecraft.client.MinecraftClient.getInstance();
                        if (client.getNetworkHandler() == null) return ItemStack.EMPTY;
                        var lookup = client.getNetworkHandler().getRegistryManager();
                        var parser = new net.minecraft.command.argument.ItemStringReader(lookup);
                        var result = parser.consume(new com.mojang.brigadier.StringReader(s));
                        return new ItemStack(result.item(), 1, result.components());
                    } catch (Exception e) {
                        // Fallback: base item without components
                        try {
                            String baseId = s.substring(0, s.indexOf('['));
                            Identifier bid = Identifier.tryParse(baseId);
                            if (bid != null) return new ItemStack(Registries.ITEM.get(bid), 1);
                        } catch (Exception ignored) {}
                        return ItemStack.EMPTY;
                    }
                });
                if (stack.isEmpty()) return;
            } else {
                Identifier id = Identifier.tryParse(itemId); if (id == null) return;
                Item item = Registries.ITEM.get(id); if (item == null) return;
                stack = new ItemStack(item, 1);
            }
            float s = sz / 16.0f;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x, y, 100);
            ctx.getMatrices().scale(s, s, 1f);
            ctx.drawItem(stack, 0, 0);
            ctx.getMatrices().pop();
        } catch (Exception e) { ctx.fill(x, y, x + sz, y + sz, 0x33FFFFFF); }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────
    @Override
    public void removed() {
        super.removed();
        if (customBgTexId != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(customBgTexId);
            customBgTexId = null;
        }
        // Clean up CheeseItems icon textures
        for (Identifier texId : ciTextures.values()) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(texId);
        }
        ciTextures.clear();
        ciDisplayNames.clear();
        ciTexSizes.clear();
    }

    // ── Text rendering ──────────────────────────────────────────────────────
    /** Render text aligned within a box. x/y = top-left of box. */
    private void renderAlignedText(DrawContext ctx, String text, int boxX, int boxY, int boxW,
                                    float scale, String align, int color, boolean bold) {
        if (text == null || text.isEmpty()) return;
        String[] lines = text.split("\n");
        int lineH = (int)(10 * scale);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li].trim();
            if (line.isEmpty()) continue;
            Text mcText = bold ? Text.literal(line).formatted(Formatting.BOLD) : Text.literal(line);
            int textW = textRenderer != null ? textRenderer.getWidth(mcText) : line.length() * 6;
            int scaledW = (int)(textW * scale);
            int textX;
            if ("center".equals(align)) textX = boxX + boxW / 2 - scaledW / 2;
            else if ("right".equals(align)) textX = boxX + boxW - scaledW;
            else textX = boxX;
            if (Math.abs(scale - 1f) < 0.01f) {
                if (textRenderer != null) ctx.drawText(textRenderer, mcText, textX, boxY + li * lineH, color, false);
            } else {
                ctx.getMatrices().push();
                ctx.getMatrices().translate(textX, boxY + li * lineH, 0);
                ctx.getMatrices().scale(scale, scale, 1f);
                if (textRenderer != null) ctx.drawText(textRenderer, mcText, 0, 0, color, false);
                ctx.getMatrices().pop();
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String xlat(String itemId) {
        if (itemId == null || itemId.isBlank()) return "?";
        if ("cheesestore:fromage".equals(itemId)) return "Cr\u00e9dit";
        // CheeseItems → use server-sent display name
        if (itemId.startsWith("cheeseitems:")) {
            String ciId = itemId.replace("cheeseitems:", "");
            String name = ciDisplayNames.get(ciId);
            if (name != null) return name;
            return raw(itemId);
        }
        try { Identifier id = Identifier.tryParse(itemId); if (id == null) return raw(itemId); Item it = Registries.ITEM.get(id); if (it == null) return raw(itemId); return new ItemStack(it).getName().getString(); } catch (Exception e) { return raw(itemId); }
    }
    private static String raw(String id) { if (id == null || id.isBlank()) return "?"; String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id; return s.replace('_', ' '); }
    private static String effectiveIcon(JsonObject item) {
        // Check for displayStack (full item string with components) first
        String ds = sp(item, "displayStack", "");
        if (!ds.isBlank()) return ds;
        // Check first output for components → build full item string for correct rendering
        if (item.has("outputs")) {
            JsonArray o = item.getAsJsonArray("outputs");
            if (!o.isEmpty()) {
                JsonObject first = o.get(0).getAsJsonObject();
                String comp = sp(first, "components", "");
                if (!comp.isBlank()) return sp(first, "itemId", "") + comp;
            }
        }
        String di = sp(item, "displayItem", "");
        if (!di.isBlank()) return di;
        if (item.has("outputs")) { JsonArray o = item.getAsJsonArray("outputs"); if (!o.isEmpty()) return sp(o.get(0).getAsJsonObject(), "itemId", ""); }
        return "";
    }

    private void border(DrawContext ctx, int x, int y, int w, int h, int c) {
        ctx.fill(x, y, x + w, y + 1, c); ctx.fill(x, y + h - 1, x + w, y + h, c);
        ctx.fill(x, y, x + 1, y + h, c); ctx.fill(x + w - 1, y, x + w, y + h, c);
    }
    private JsonArray activeSets() { return shopData.has("sets") ? shopData.getAsJsonArray("sets") : new JsonArray(); }
    private String gs(String k, String d) { return shopData.has(k) ? shopData.get(k).getAsString() : d; }
    private boolean gb(String k, boolean d) { return shopData.has(k) ? shopData.get(k).getAsBoolean() : d; }
    private static String sp(JsonObject o, String k, String d) { return o.has(k) ? o.get(k).getAsString() : d; }
    private static String trunc(String s, int m) { return s == null ? "?" : s.length() > m ? s.substring(0, m) + "\u2026" : s; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static boolean hov(int mx, int my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private void dt(DrawContext ctx, String t, int x, int y, int c) { if (textRenderer != null) ctx.drawText(textRenderer, t, x, y, c, false); }
    private int tw(String t) { return textRenderer != null ? textRenderer.getWidth(t) : t.length() * 6; }
    /** Build item tooltip from server-resolved data (exact vanilla tooltip with full item components). */
    private List<Text> buildItemTooltip(String itemId) {
        return buildItemTooltip(itemId, null);
    }
    private List<Text> buildItemTooltip(String itemId, String customName) {
        List<Text> lines = new ArrayList<>();
        // Try server-resolved tooltip first (has full item data including NBT/components)
        if (resolvedTooltipData != null && resolvedTooltipData.has(itemId)) {
            try {
                JsonArray arr = resolvedTooltipData.getAsJsonArray(itemId);
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.world != null) {
                    var registries = mc.world.getRegistryManager();
                    for (int i = 0; i < arr.size(); i++) {
                        Text t = Text.Serialization.fromJson(arr.get(i).getAsString(), registries);
                        if (t != null) lines.add(t);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[Market/Client] Failed to deserialize tooltip for {}: {}", itemId, e.getMessage());
            }
        }
        // Fallback: local resolution (strip component brackets for Identifier parsing)
        if (lines.isEmpty()) {
            try {
                String baseId = itemId.contains("[") ? itemId.substring(0, itemId.indexOf('[')) : itemId;
                Identifier id = Identifier.tryParse(baseId);
                if (id != null) {
                    Item item = Registries.ITEM.get(id);
                    if (item != Items.AIR) {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        lines.addAll(Screen.getTooltipFromItem(mc, new ItemStack(item)));
                    }
                }
            } catch (Exception e) { /* fallback */ }
        }
        // Override first line with custom display name if provided
        if (customName != null && !customName.isBlank()) {
            if (!lines.isEmpty()) lines.set(0, Text.literal(customName).formatted(Formatting.WHITE));
            else lines.add(Text.literal(customName));
        }
        if (lines.isEmpty()) lines.add(Text.literal(xlat(itemId)));
        return lines;
    }

    private static int parseMCColor(String n) {
        if (n == null || n.isEmpty()) return 0xFFFFFFFF;
        // Try MC named colors first
        int named = switch (n.toLowerCase()) { case "black" -> 0xFF000000; case "dark_blue" -> 0xFF0000AA; case "dark_green" -> 0xFF00AA00; case "dark_aqua" -> 0xFF00AAAA; case "dark_red" -> 0xFFAA0000; case "dark_purple" -> 0xFFAA00AA; case "gold" -> 0xFFFFAA00; case "gray" -> 0xFFAAAAAA; case "dark_gray" -> 0xFF555555; case "blue" -> 0xFF5555FF; case "green" -> 0xFF55FF55; case "aqua" -> 0xFF55FFFF; case "red" -> 0xFFFF5555; case "light_purple" -> 0xFFFF55FF; case "yellow" -> 0xFFFFFF55; case "white" -> 0xFFFFFFFF; default -> -1; };
        if (named != -1) return named;
        // Try hex (RRGGBB or #RRGGBB)
        try { String hex = n.startsWith("#") ? n.substring(1) : n; if (hex.length() == 6) return 0xFF000000 | Integer.parseInt(hex, 16); } catch (Exception ignored) {}
        return 0xFFFFFFFF;
    }
    /** Compute in-game text scale from fontSize + scale props. fontSize=9 is baseline (1.0). */
    private static float textEffectiveScale(JsonObject props) {
        float fontSize = props.has("fontSize") ? props.get("fontSize").getAsFloat() : 9f;
        float scale = props.has("scale") ? props.get("scale").getAsFloat() : 1f;
        return (fontSize / 9f) * scale;
    }
    private static int darken(int c, float f) { int r = (int) (((c >> 16) & 0xFF) * f), g = (int) (((c >> 8) & 0xFF) * f), b = (int) ((c & 0xFF) * f); return 0xFF000000 | (Math.min(r, 255) << 16) | (Math.min(g, 255) << 8) | Math.min(b, 255); }
    private static int jInt(JsonObject o, String k, int d) { return o != null && o.has(k) ? o.get(k).getAsInt() : d; }

    private record Widget(int x, int y, int w, int h, String action) { boolean contains(int mx, int my) { return mx >= x && mx < x + w && my >= y && my < y + h; } }
}

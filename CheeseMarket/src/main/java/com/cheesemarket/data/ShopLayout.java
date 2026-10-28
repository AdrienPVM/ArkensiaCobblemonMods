package com.cheesemarket.data;

import com.google.gson.*;
import java.util.*;

/**
 * Visual layout for a shop GUI. Stores element positions and item card template.
 * null layout on ShopData = use default hardcoded layout.
 */
public class ShopLayout {

    private List<LayoutElement> elements;
    private ItemCardLayout itemCard;
    private ConfirmLayout confirmLayout;

    public ShopLayout() {
        this.elements = new ArrayList<>();
        this.itemCard = new ItemCardLayout();
    }

    // ── Element types ────────────────────────────────────────────────────────

    public enum ElementType {
        BACKGROUND, TEXT, BUTTON, ITEM_GRID, IMAGE
    }

    // ── LayoutElement ────────────────────────────────────────────────────────

    public static class LayoutElement {
        private String id;
        private ElementType type;
        private int x, y, width, height;
        private int zOrder;
        private JsonObject props; // type-specific properties

        public LayoutElement() {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.props = new JsonObject();
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("type", type.name());
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("width", width);
            o.addProperty("height", height);
            o.addProperty("zOrder", zOrder);
            if (props != null) o.add("props", props);
            return o;
        }

        public static LayoutElement fromJson(JsonObject o) {
            LayoutElement el = new LayoutElement();
            if (o.has("id")) el.id = o.get("id").getAsString();
            if (o.has("type")) {
                try { el.type = ElementType.valueOf(o.get("type").getAsString()); }
                catch (Exception ignored) { el.type = ElementType.TEXT; }
            }
            if (o.has("x")) el.x = o.get("x").getAsInt();
            if (o.has("y")) el.y = o.get("y").getAsInt();
            if (o.has("width")) el.width = o.get("width").getAsInt();
            if (o.has("height")) el.height = o.get("height").getAsInt();
            if (o.has("zOrder")) el.zOrder = o.get("zOrder").getAsInt();
            if (o.has("props")) el.props = o.getAsJsonObject("props");
            return el;
        }

        // Getters/setters
        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public ElementType getType() { return type; }
        public void setType(ElementType v) { this.type = v; }
        public int getX() { return x; }
        public void setX(int v) { this.x = v; }
        public int getY() { return y; }
        public void setY(int v) { this.y = v; }
        public int getWidth() { return width; }
        public void setWidth(int v) { this.width = v; }
        public int getHeight() { return height; }
        public void setHeight(int v) { this.height = v; }
        public int getZOrder() { return zOrder; }
        public void setZOrder(int v) { this.zOrder = v; }
        public JsonObject getProps() { return props != null ? props : new JsonObject(); }
        public void setProps(JsonObject v) { this.props = v; }
    }

    // ── ItemCardLayout ───────────────────────────────────────────────────────

    public static class ItemCardLayout {
        private int cardWidth = 41;
        private int cardHeight = 36;
        private int iconOffsetX = 13;   // (41 - 15) / 2 = 13, centered
        private int iconOffsetY = 7;
        private int iconSize = 15;
        private int costOffsetX = 0;    // horizontal offset from center (0 = centered)
        private int costOffsetY = 26;   // iconOffsetY + iconSize + 4
        private float costTextScale = 0.85f;
        private int costIconSize = 9;
        private int costGap = 2;
        private int costTextIconGap = -1;  // gap between cost number text and its icon within a pair
        private int costIconOffsetY = 0;   // Y offset for cost icon relative to text (positive = down)
        private String costTextColor = "FFCC44";

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("cardWidth", cardWidth);
            o.addProperty("cardHeight", cardHeight);
            o.addProperty("iconOffsetX", iconOffsetX);
            o.addProperty("iconOffsetY", iconOffsetY);
            o.addProperty("iconSize", iconSize);
            o.addProperty("costOffsetX", costOffsetX);
            o.addProperty("costOffsetY", costOffsetY);
            o.addProperty("costTextScale", costTextScale);
            o.addProperty("costIconSize", costIconSize);
            o.addProperty("costGap", costGap);
            o.addProperty("costTextIconGap", costTextIconGap);
            o.addProperty("costIconOffsetY", costIconOffsetY);
            o.addProperty("costTextColor", costTextColor);
            return o;
        }

        public static ItemCardLayout fromJson(JsonObject o) {
            ItemCardLayout ic = new ItemCardLayout();
            if (o.has("cardWidth")) ic.cardWidth = o.get("cardWidth").getAsInt();
            if (o.has("cardHeight")) ic.cardHeight = o.get("cardHeight").getAsInt();
            if (o.has("iconOffsetX")) ic.iconOffsetX = o.get("iconOffsetX").getAsInt();
            if (o.has("iconOffsetY")) ic.iconOffsetY = o.get("iconOffsetY").getAsInt();
            if (o.has("iconSize")) ic.iconSize = o.get("iconSize").getAsInt();
            if (o.has("costOffsetX")) ic.costOffsetX = o.get("costOffsetX").getAsInt();
            if (o.has("costOffsetY")) ic.costOffsetY = o.get("costOffsetY").getAsInt();
            if (o.has("costTextScale")) ic.costTextScale = o.get("costTextScale").getAsFloat();
            if (o.has("costIconSize")) ic.costIconSize = o.get("costIconSize").getAsInt();
            if (o.has("costGap")) ic.costGap = o.get("costGap").getAsInt();
            if (o.has("costTextIconGap")) ic.costTextIconGap = o.get("costTextIconGap").getAsInt();
            if (o.has("costIconOffsetY")) ic.costIconOffsetY = o.get("costIconOffsetY").getAsInt();
            if (o.has("costTextColor")) ic.costTextColor = o.get("costTextColor").getAsString();
            return ic;
        }

        // Getters/setters
        public int getCardWidth() { return cardWidth; }
        public void setCardWidth(int v) { this.cardWidth = v; }
        public int getCardHeight() { return cardHeight; }
        public void setCardHeight(int v) { this.cardHeight = v; }
        public int getIconOffsetX() { return iconOffsetX; }
        public void setIconOffsetX(int v) { this.iconOffsetX = v; }
        public int getIconOffsetY() { return iconOffsetY; }
        public void setIconOffsetY(int v) { this.iconOffsetY = v; }
        public int getIconSize() { return iconSize; }
        public void setIconSize(int v) { this.iconSize = v; }
        public int getCostOffsetX() { return costOffsetX; }
        public void setCostOffsetX(int v) { this.costOffsetX = v; }
        public int getCostOffsetY() { return costOffsetY; }
        public void setCostOffsetY(int v) { this.costOffsetY = v; }
        public float getCostTextScale() { return costTextScale; }
        public void setCostTextScale(float v) { this.costTextScale = v; }
        public int getCostIconSize() { return costIconSize; }
        public void setCostIconSize(int v) { this.costIconSize = v; }
        public int getCostGap() { return costGap; }
        public void setCostGap(int v) { this.costGap = v; }
        public int getCostTextIconGap() { return costTextIconGap; }
        public void setCostTextIconGap(int v) { this.costTextIconGap = v; }
        public int getCostIconOffsetY() { return costIconOffsetY; }
        public void setCostIconOffsetY(int v) { this.costIconOffsetY = v; }
        public String getCostTextColor() { return costTextColor; }
        public void setCostTextColor(String v) { this.costTextColor = v; }
    }

    // ── ConfirmLayout ────────────────────────────────────────────────────────

    public static class ConfirmLayout {
        private int width = 340;
        private String bgImage = "";  // asset name for background image (empty = no image)
        private String bgColor = "1A1A2E";
        private String borderColor = "2A5A8A";
        private String accentColor = "4AC8EA";
        private String headerBgColor = "0D2137";
        private String titleColor = "4AC8EA";
        private String labelColor = "7A8A9A";
        private String costColor = "FFCC44";
        private String arrowColor = "4AC8EA";
        private String sliderBgColor = "1A2A4A";
        private String sliderFillColor = "357EA3";
        private String sliderThumbColor = "4AC8EA";
        private String sepColor = "1E3050";
        private String btnConfirmTextColor = "55FF55";
        private String btnCancelTextColor = "FF6666";
        private String columnBgColor = "18FFFFFF";

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("width", width);
            if (bgImage != null && !bgImage.isEmpty()) o.addProperty("bgImage", bgImage);
            o.addProperty("bgColor", bgColor);
            o.addProperty("borderColor", borderColor);
            o.addProperty("accentColor", accentColor);
            o.addProperty("headerBgColor", headerBgColor);
            o.addProperty("titleColor", titleColor);
            o.addProperty("labelColor", labelColor);
            o.addProperty("costColor", costColor);
            o.addProperty("arrowColor", arrowColor);
            o.addProperty("sliderBgColor", sliderBgColor);
            o.addProperty("sliderFillColor", sliderFillColor);
            o.addProperty("sliderThumbColor", sliderThumbColor);
            o.addProperty("sepColor", sepColor);
            o.addProperty("btnConfirmTextColor", btnConfirmTextColor);
            o.addProperty("btnCancelTextColor", btnCancelTextColor);
            o.addProperty("columnBgColor", columnBgColor);
            return o;
        }

        public static ConfirmLayout fromJson(JsonObject o) {
            ConfirmLayout cl = new ConfirmLayout();
            if (o.has("width")) cl.width = o.get("width").getAsInt();
            if (o.has("bgImage")) cl.bgImage = o.get("bgImage").getAsString();
            if (o.has("bgColor")) cl.bgColor = o.get("bgColor").getAsString();
            if (o.has("borderColor")) cl.borderColor = o.get("borderColor").getAsString();
            if (o.has("accentColor")) cl.accentColor = o.get("accentColor").getAsString();
            if (o.has("headerBgColor")) cl.headerBgColor = o.get("headerBgColor").getAsString();
            if (o.has("titleColor")) cl.titleColor = o.get("titleColor").getAsString();
            if (o.has("labelColor")) cl.labelColor = o.get("labelColor").getAsString();
            if (o.has("costColor")) cl.costColor = o.get("costColor").getAsString();
            if (o.has("arrowColor")) cl.arrowColor = o.get("arrowColor").getAsString();
            if (o.has("sliderBgColor")) cl.sliderBgColor = o.get("sliderBgColor").getAsString();
            if (o.has("sliderFillColor")) cl.sliderFillColor = o.get("sliderFillColor").getAsString();
            if (o.has("sliderThumbColor")) cl.sliderThumbColor = o.get("sliderThumbColor").getAsString();
            if (o.has("sepColor")) cl.sepColor = o.get("sepColor").getAsString();
            if (o.has("btnConfirmTextColor")) cl.btnConfirmTextColor = o.get("btnConfirmTextColor").getAsString();
            if (o.has("btnCancelTextColor")) cl.btnCancelTextColor = o.get("btnCancelTextColor").getAsString();
            if (o.has("columnBgColor")) cl.columnBgColor = o.get("columnBgColor").getAsString();
            return cl;
        }

        // Getters/setters
        public int getWidth() { return width; }
        public void setWidth(int v) { this.width = v; }
        public String getBgImage() { return bgImage; }
        public void setBgImage(String v) { this.bgImage = v; }
        public String getBgColor() { return bgColor; }
        public String getBorderColor() { return borderColor; }
        public String getAccentColor() { return accentColor; }
        public String getHeaderBgColor() { return headerBgColor; }
        public String getTitleColor() { return titleColor; }
        public String getLabelColor() { return labelColor; }
        public String getCostColor() { return costColor; }
        public String getArrowColor() { return arrowColor; }
        public String getSliderBgColor() { return sliderBgColor; }
        public String getSliderFillColor() { return sliderFillColor; }
        public String getSliderThumbColor() { return sliderThumbColor; }
        public String getSepColor() { return sepColor; }
        public String getBtnConfirmTextColor() { return btnConfirmTextColor; }
        public String getBtnCancelTextColor() { return btnCancelTextColor; }
        public String getColumnBgColor() { return columnBgColor; }
    }

    // ── Serialization ────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (LayoutElement el : elements) arr.add(el.toJson());
        o.add("elements", arr);
        o.add("itemCard", itemCard.toJson());
        if (confirmLayout != null) o.add("confirmLayout", confirmLayout.toJson());
        return o;
    }

    public static ShopLayout fromJson(JsonObject o) {
        ShopLayout layout = new ShopLayout();
        if (o.has("elements")) {
            for (JsonElement e : o.getAsJsonArray("elements"))
                layout.elements.add(LayoutElement.fromJson(e.getAsJsonObject()));
        }
        if (o.has("itemCard")) {
            layout.itemCard = ItemCardLayout.fromJson(o.getAsJsonObject("itemCard"));
        }
        if (o.has("confirmLayout")) {
            layout.confirmLayout = ConfirmLayout.fromJson(o.getAsJsonObject("confirmLayout"));
        }
        return layout;
    }

    /** Build the default layout matching current hardcoded ShopViewScreen constants. */
    public static ShopLayout buildDefault() {
        ShopLayout layout = new ShopLayout();

        // Background (z=0)
        LayoutElement bg = new LayoutElement();
        bg.setType(ElementType.BACKGROUND);
        bg.setX(0); bg.setY(0);
        bg.setWidth(350); bg.setHeight(250);
        bg.setZOrder(0);
        JsonObject bgProps = new JsonObject();
        bgProps.addProperty("src", "default");
        bg.setProps(bgProps);
        layout.elements.add(bg);

        // Title text (z=10) — x is left edge of box, text centered via align
        LayoutElement title = new LayoutElement();
        title.setType(ElementType.TEXT);
        title.setX(50); title.setY(38);
        title.setWidth(250); title.setHeight(20);
        title.setZOrder(10);
        JsonObject titleProps = new JsonObject();
        titleProps.addProperty("text", "{shopName}");
        titleProps.addProperty("fontSize", 9);
        titleProps.addProperty("color", "{titleColor}");
        titleProps.addProperty("align", "center");
        titleProps.addProperty("bold", false);
        title.setProps(titleProps);
        layout.elements.add(title);

        // Description text (z=10) — x is left edge of box
        LayoutElement desc = new LayoutElement();
        desc.setType(ElementType.TEXT);
        desc.setX(50); desc.setY(50);
        desc.setWidth(250); desc.setHeight(14);
        desc.setZOrder(10);
        JsonObject descProps = new JsonObject();
        descProps.addProperty("text", "{shopDesc}");
        descProps.addProperty("fontSize", 7);
        descProps.addProperty("color", "AABBCC");
        descProps.addProperty("align", "center");
        descProps.addProperty("bold", false);
        descProps.addProperty("scale", 0.75);
        desc.setProps(descProps);
        layout.elements.add(desc);

        // Close button (z=20)
        LayoutElement close = new LayoutElement();
        close.setType(ElementType.BUTTON);
        close.setX(312); close.setY(38);
        close.setWidth(20); close.setHeight(20);
        close.setZOrder(20);
        JsonObject closeProps = new JsonObject();
        closeProps.addProperty("action", "close");
        closeProps.addProperty("src", "default");
        close.setProps(closeProps);
        layout.elements.add(close);

        // Item grid (z=5)
        LayoutElement grid = new LayoutElement();
        grid.setType(ElementType.ITEM_GRID);
        grid.setX(32); grid.setY(95);
        grid.setWidth(286); grid.setHeight(121);
        grid.setZOrder(5);
        JsonObject gridProps = new JsonObject();
        gridProps.addProperty("cols", 6);
        gridProps.addProperty("rows", 3);
        gridProps.addProperty("padX", 4);
        gridProps.addProperty("padY", 3);
        grid.setProps(gridProps);
        layout.elements.add(grid);

        return layout;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Get elements sorted by zOrder for rendering. */
    public List<LayoutElement> getSortedElements() {
        List<LayoutElement> sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingInt(LayoutElement::getZOrder));
        return sorted;
    }

    public LayoutElement findElement(String id) {
        return elements.stream().filter(e -> e.getId().equals(id)).findFirst().orElse(null);
    }

    // ── Getters/Setters ─────────────────────────────────────────────────────

    public List<LayoutElement> getElements() { return elements; }
    public void setElements(List<LayoutElement> v) { this.elements = v; }
    public ItemCardLayout getItemCard() { return itemCard; }
    public void setItemCard(ItemCardLayout v) { this.itemCard = v; }
    public ConfirmLayout getConfirmLayout() { return confirmLayout; }
    public void setConfirmLayout(ConfirmLayout v) { this.confirmLayout = v; }
}

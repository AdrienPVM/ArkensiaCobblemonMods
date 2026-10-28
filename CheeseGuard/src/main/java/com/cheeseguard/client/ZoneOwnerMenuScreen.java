package com.cheeseguard.client;

import com.cheeseguard.network.ZoneMenuActionC2S;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side zone-owner edit menu, opened by the K keybind.
 *
 * Layout (panel ~360×280, centered):
 *   ┌───────────────────────────────────────────────────────┐
 *   │ Edition de zone : <name>                              │
 *   │ Monde: <world>     Taille: 32x32x16                   │
 *   ├───────────────────────────────────────────────────────┤
 *   │ Membres (N)                       ▲ ▼  (if scrollable)│
 *   │   alice                                            [×]│
 *   │   bob                                              [×]│
 *   │   …                                                   │
 *   ├───────────────────────────────────────────────────────┤
 *   │ Ajouter:    [_____________________________]   [Ajouter]│
 *   │ Transférer: [_____________________________]   [Transf.]│
 *   │ Renommer:   [_____________________________]   [Renom.] │
 *   ├───────────────────────────────────────────────────────┤
 *   │ PVP: allow [Toggle]   Mobs: deny [Toggle]             │
 *   ├───────────────────────────────────────────────────────┤
 *   │ [Fermer]                            [Abandonner zone] │
 *   └───────────────────────────────────────────────────────┘
 */
@Environment(EnvType.CLIENT)
public class ZoneOwnerMenuScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 320;
    private static final int VISIBLE_MEMBER_ROWS = 5;
    private static final int CONFIRM_WINDOW_MS = 5000;

    private JsonObject data;
    private int scrollOffset;

    private TextFieldWidget addField;
    private TextFieldWidget transferField;
    private TextFieldWidget renameField;

    private long transferArmedAt;
    private long leaveArmedAt;

    public ZoneOwnerMenuScreen(JsonObject data) {
        super(Text.translatable("cheeseguard.zone_menu.title"));
        this.data = data == null ? new JsonObject() : data;
    }

    /** Called by the network receiver when the server pushes an updated snapshot. */
    public void updateData(JsonObject newData) {
        this.data = newData == null ? new JsonObject() : newData;
        if (this.client != null) {
            this.clearAndInit();
        }
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        String zoneId = strField("zoneId");

        // ── Members list with [×] buttons + scroll arrows ────────────────────
        var membersArr = data.has("members") && data.get("members").isJsonArray()
            ? data.getAsJsonArray("members") : null;
        int totalMembers = membersArr == null ? 0 : membersArr.size();
        int maxScroll = Math.max(0, totalMembers - VISIBLE_MEMBER_ROWS);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        int membersTop = panelY + 50;
        int rowHeight = 22;
        int listX = panelX + 12;
        int listW = PANEL_W - 24;

        for (int i = 0; i < VISIBLE_MEMBER_ROWS && (i + scrollOffset) < totalMembers; i++) {
            int idx = i + scrollOffset;
            JsonObject m = membersArr.get(idx).getAsJsonObject();
            String memberUuid = m.has("uuid") ? m.get("uuid").getAsString() : "";
            String memberName = m.has("name") ? m.get("name").getAsString() : memberUuid;
            int rowY = membersTop + i * rowHeight;

            // Remove button on the right
            ButtonWidget removeBtn = ButtonWidget.builder(
                    Text.literal("×").formatted(Formatting.RED),
                    btn -> sendAction(zoneId, "remove", memberUuid))
                .dimensions(listX + listW - 22, rowY, 20, 20)
                .build();
            removeBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(
                Text.translatable("cheeseguard.zone_menu.member_remove_tooltip", memberName)));
            this.addDrawableChild(removeBtn);
        }

        // Scroll arrows
        if (totalMembers > VISIBLE_MEMBER_ROWS) {
            ButtonWidget up = ButtonWidget.builder(Text.literal("▲"), b -> {
                scrollOffset = Math.max(0, scrollOffset - 1);
                this.clearAndInit();
            }).dimensions(panelX + PANEL_W - 36, membersTop - 14, 20, 12).build();
            ButtonWidget dn = ButtonWidget.builder(Text.literal("▼"), b -> {
                scrollOffset = Math.min(maxScroll, scrollOffset + 1);
                this.clearAndInit();
            }).dimensions(panelX + PANEL_W - 14, membersTop - 14, 20, 12).build();
            this.addDrawableChild(up);
            this.addDrawableChild(dn);
        }

        // ── Add / Transfer / Rename rows ─────────────────────────────────────
        int formY = membersTop + VISIBLE_MEMBER_ROWS * rowHeight + 10;

        addField = new TextFieldWidget(this.textRenderer, listX + 80, formY, 180, 18,
            Text.translatable("cheeseguard.zone_menu.add_placeholder"));
        addField.setMaxLength(32);
        this.addDrawableChild(addField);

        ButtonWidget addBtn = ButtonWidget.builder(
                Text.translatable("cheeseguard.zone_menu.add_button"),
                b -> {
                    String n = addField.getText();
                    if (!n.isBlank()) sendAction(zoneId, "add", n.trim());
                })
            .dimensions(listX + 264, formY - 1, 80, 20)
            .build();
        this.addDrawableChild(addBtn);

        transferField = new TextFieldWidget(this.textRenderer, listX + 80, formY + 24, 180, 18,
            Text.translatable("cheeseguard.zone_menu.transfer_placeholder"));
        transferField.setMaxLength(32);
        this.addDrawableChild(transferField);

        long now = System.currentTimeMillis();
        boolean transferArmed = (now - transferArmedAt) < CONFIRM_WINDOW_MS;
        ButtonWidget transferBtn = ButtonWidget.builder(
                transferArmed
                    ? Text.translatable("cheeseguard.zone_menu.transfer_confirm").formatted(Formatting.RED)
                    : Text.translatable("cheeseguard.zone_menu.transfer_button"),
                b -> {
                    String n = transferField.getText();
                    if (n.isBlank()) return;
                    if (!transferArmed) {
                        transferArmedAt = System.currentTimeMillis();
                        this.clearAndInit();
                    } else {
                        transferArmedAt = 0;
                        sendAction(zoneId, "transfer", n.trim());
                    }
                })
            .dimensions(listX + 264, formY + 23, 80, 20)
            .build();
        this.addDrawableChild(transferBtn);

        renameField = new TextFieldWidget(this.textRenderer, listX + 80, formY + 48, 180, 18,
            Text.translatable("cheeseguard.zone_menu.rename_placeholder"));
        renameField.setMaxLength(64);
        renameField.setText(strField("name"));
        this.addDrawableChild(renameField);

        ButtonWidget renameBtn = ButtonWidget.builder(
                Text.translatable("cheeseguard.zone_menu.rename_button"),
                b -> {
                    String n = renameField.getText();
                    if (!n.isBlank()) sendAction(zoneId, "rename", n.trim());
                })
            .dimensions(listX + 264, formY + 47, 80, 20)
            .build();
        this.addDrawableChild(renameBtn);

        // ── Flag toggles ─────────────────────────────────────────────────────
        int flagsY = formY + 80;
        addFlagToggle(panelX + 12, flagsY, "PVP", "toggle_pvp", zoneId);
        addFlagToggle(panelX + 12 + 170, flagsY, "MOB_SPAWNING", "toggle_mob", zoneId);

        // ── Close + Leave ────────────────────────────────────────────────────
        int btnRowY = panelY + PANEL_H - 30;
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("cheeseguard.zone_menu.close"),
                b -> this.close())
            .dimensions(panelX + 12, btnRowY, 80, 20)
            .build());

        boolean leaveArmed = (now - leaveArmedAt) < CONFIRM_WINDOW_MS;
        this.addDrawableChild(ButtonWidget.builder(
                leaveArmed
                    ? Text.translatable("cheeseguard.zone_menu.leave_confirm").formatted(Formatting.RED)
                    : Text.translatable("cheeseguard.zone_menu.leave_button").formatted(Formatting.YELLOW),
                b -> {
                    if (!leaveArmed) {
                        leaveArmedAt = System.currentTimeMillis();
                        this.clearAndInit();
                    } else {
                        leaveArmedAt = 0;
                        sendAction(zoneId, "leave", "");
                    }
                })
            .dimensions(panelX + PANEL_W - 132, btnRowY, 120, 20)
            .build());
    }

    private void addFlagToggle(int x, int y, String label, String action, String zoneId) {
        String state = "inherit";
        if (data.has("flags") && data.get("flags").isJsonObject()) {
            var f = data.getAsJsonObject("flags");
            if (f.has(label)) state = f.get(label).getAsString();
        }
        Formatting color = switch (state) {
            case "allow" -> Formatting.GREEN;
            case "deny"  -> Formatting.RED;
            default      -> Formatting.GRAY;
        };
        Text btnText = Text.literal(label + ": ").append(Text.literal(state).formatted(color));
        this.addDrawableChild(ButtonWidget.builder(btnText, b -> sendAction(zoneId, action, ""))
            .dimensions(x, y, 160, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        // Panel background
        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xC0101418);
        ctx.drawBorder(panelX, panelY, PANEL_W, PANEL_H, 0xFF252836);

        // Title row
        Text title = Text.translatable("cheeseguard.zone_menu.title", strField("name"))
            .copy().formatted(Formatting.GOLD);
        ctx.drawTextWithShadow(this.textRenderer, title, panelX + 12, panelY + 10, 0xFFFFFFFF);

        // Subtitle: world + size
        String world = strField("world");
        String size = strField("size");
        Text sub = Text.translatable("cheeseguard.zone_menu.world_size", world, size)
            .copy().formatted(Formatting.GRAY);
        ctx.drawTextWithShadow(this.textRenderer, sub, panelX + 12, panelY + 24, 0xFFAAAAAA);

        // Members section header
        var membersArr = data.has("members") && data.get("members").isJsonArray()
            ? data.getAsJsonArray("members") : null;
        int total = membersArr == null ? 0 : membersArr.size();
        Text mh = Text.translatable("cheeseguard.zone_menu.members_header", total)
            .copy().formatted(Formatting.AQUA);
        ctx.drawTextWithShadow(this.textRenderer, mh, panelX + 12, panelY + 40, 0xFF22D3EE);

        // Render member name rows
        int membersTop = panelY + 50;
        int rowHeight = 22;
        for (int i = 0; i < VISIBLE_MEMBER_ROWS && membersArr != null && (i + scrollOffset) < total; i++) {
            JsonObject m = membersArr.get(i + scrollOffset).getAsJsonObject();
            String name = m.has("name") ? m.get("name").getAsString() : "?";
            int rowY = membersTop + i * rowHeight + 6;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("  " + name), panelX + 16, rowY, 0xFFFFFFFF);
        }
        if (total == 0) {
            Text none = Text.translatable("cheeseguard.zone_menu.no_members").formatted(Formatting.DARK_GRAY);
            ctx.drawTextWithShadow(this.textRenderer, none, panelX + 18, membersTop + 6, 0xFF707070);
        }

        // Field labels
        int formY = membersTop + VISIBLE_MEMBER_ROWS * rowHeight + 10;
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("cheeseguard.zone_menu.add_label"),
            panelX + 12, formY + 5, 0xFFCCCCCC);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("cheeseguard.zone_menu.transfer_label"),
            panelX + 12, formY + 29, 0xFFCCCCCC);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.translatable("cheeseguard.zone_menu.rename_label"),
            panelX + 12, formY + 53, 0xFFCCCCCC);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Scroll the members list when hovering over the visible rows.
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int membersTop = panelY + 50;
        int membersBottom = membersTop + VISIBLE_MEMBER_ROWS * 22;
        if (mouseX >= panelX && mouseX <= panelX + PANEL_W
                && mouseY >= membersTop - 4 && mouseY <= membersBottom) {
            int totalMembers = (data.has("members") && data.get("members").isJsonArray())
                ? data.getAsJsonArray("members").size() : 0;
            int maxScroll = Math.max(0, totalMembers - VISIBLE_MEMBER_ROWS);
            scrollOffset -= (int) Math.signum(verticalAmount);
            if (scrollOffset < 0) scrollOffset = 0;
            if (scrollOffset > maxScroll) scrollOffset = maxScroll;
            this.clearAndInit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void sendAction(String zoneId, String action, String target) {
        if (zoneId == null || zoneId.isEmpty()) return;
        ClientPlayNetworking.send(new ZoneMenuActionC2S(zoneId, action, target == null ? "" : target));
    }

    private String strField(String key) {
        return data.has(key) && !data.get(key).isJsonNull() ? data.get(key).getAsString() : "";
    }
}

package com.cheesedash.client;

import com.cheesedash.network.QuickMenuPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * In-game Quick Menu screen for CheeseDash.
 * Shows a grid of custom shortcut buttons (name + command).
 * Opened via a configurable keybind.
 */
@Environment(EnvType.CLIENT)
public class QuickMenuScreen extends Screen {

    private static final int PANEL_WIDTH = 300;
    private static final int BTN_HEIGHT = 24;
    private static final int BTN_GAP = 4;
    private static final int COLS = 2;
    private static final int PADDING = 12;
    private static final int TITLE_HEIGHT = 24;

    private final QuickMenuConfig config;
    private boolean addMode = false;
    private TextFieldWidget labelField;
    private TextFieldWidget commandField;

    public QuickMenuScreen(QuickMenuConfig config) {
        super(Text.translatable("cheesedash.quickmenu.title"));
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    private void rebuildWidgets() {
        clearChildren();
        addMode = false;
        labelField = null;
        commandField = null;

        List<QuickMenuConfig.Shortcut> shortcuts = config.getShortcuts();
        int panelX = (width - PANEL_WIDTH) / 2;
        int colWidth = (PANEL_WIDTH - PADDING * 2 - BTN_GAP) / COLS;

        // Calculate panel height
        int rows = (shortcuts.size() + COLS - 1) / COLS;
        int contentHeight = TITLE_HEIGHT + PADDING * 2 + rows * (BTN_HEIGHT + BTN_GAP) + BTN_HEIGHT + BTN_GAP;
        int panelY = (height - contentHeight) / 2;
        int startY = panelY + TITLE_HEIGHT + PADDING;

        // Shortcut buttons — 2 columns grid
        for (int i = 0; i < shortcuts.size(); i++) {
            QuickMenuConfig.Shortcut s = shortcuts.get(i);
            int col = i % COLS;
            int row = i / COLS;
            int bx = panelX + PADDING + col * (colWidth + BTN_GAP);
            int by = startY + row * (BTN_HEIGHT + BTN_GAP);

            final int index = i;

            // Main shortcut button — label is user-defined, stays literal
            addDrawableChild(ButtonWidget.builder(Text.literal(s.label()), btn -> {
                executeCommand(s.command());
            }).dimensions(bx, by, colWidth - 18, BTN_HEIGHT).build());

            // Delete button (small "x")
            addDrawableChild(ButtonWidget.builder(Text.literal("\u2715"), btn -> {
                config.removeShortcut(index);
                rebuildWidgets();
            }).dimensions(bx + colWidth - 16, by, 16, BTN_HEIGHT).build());
        }

        // "+" button to add new shortcut
        int addY = startY + rows * (BTN_HEIGHT + BTN_GAP);
        addDrawableChild(ButtonWidget.builder(Text.translatable("cheesedash.quickmenu.add_shortcut"), btn -> {
            showAddForm();
        }).dimensions(panelX + PADDING, addY, PANEL_WIDTH - PADDING * 2, BTN_HEIGHT).build());
    }

    private void showAddForm() {
        clearChildren();
        addMode = true;

        int panelX = (width - PANEL_WIDTH) / 2;
        int formY = (height - 120) / 2 + TITLE_HEIGHT;

        // Label field
        labelField = new TextFieldWidget(textRenderer, panelX + PADDING, formY, PANEL_WIDTH - PADDING * 2, 20, Text.translatable("cheesedash.quickmenu.name_placeholder"));
        labelField.setPlaceholder(Text.translatable("cheesedash.quickmenu.name_placeholder"));
        labelField.setMaxLength(64);
        addDrawableChild(labelField);

        // Command field
        commandField = new TextFieldWidget(textRenderer, panelX + PADDING, formY + 26, PANEL_WIDTH - PADDING * 2, 20, Text.translatable("cheesedash.quickmenu.command_placeholder"));
        commandField.setPlaceholder(Text.translatable("cheesedash.quickmenu.command_placeholder"));
        commandField.setMaxLength(256);
        addDrawableChild(commandField);

        // Buttons row
        int btnY = formY + 56;
        int halfW = (PANEL_WIDTH - PADDING * 2 - BTN_GAP) / 2;

        addDrawableChild(ButtonWidget.builder(Text.translatable("cheesedash.quickmenu.add"), btn -> {
            String label = labelField.getText().trim();
            String command = commandField.getText().trim();
            if (!label.isEmpty() && !command.isEmpty()) {
                config.addShortcut(label, command);
                rebuildWidgets();
            }
        }).dimensions(panelX + PADDING, btnY, halfW, BTN_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("cheesedash.quickmenu.cancel"), btn -> {
            rebuildWidgets();
        }).dimensions(panelX + PADDING + halfW + BTN_GAP, btnY, halfW, BTN_HEIGHT).build());

        setFocused(labelField);
    }

    private void executeCommand(String command) {
        if (client != null && client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new QuickMenuPayload(command));
            close();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Semi-transparent dark background
        context.fill(0, 0, width, height, 0x88000000);

        List<QuickMenuConfig.Shortcut> shortcuts = config.getShortcuts();
        int rows = (shortcuts.size() + COLS - 1) / COLS;
        int contentHeight = TITLE_HEIGHT + PADDING * 2 + rows * (BTN_HEIGHT + BTN_GAP) + BTN_HEIGHT + BTN_GAP;
        if (addMode) contentHeight = 120 + TITLE_HEIGHT;

        int panelX = (width - PANEL_WIDTH) / 2;
        int panelY = (height - contentHeight) / 2;

        // Panel background
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + contentHeight, 0xE0131620);
        // Panel border
        context.drawBorder(panelX, panelY, PANEL_WIDTH, contentHeight, 0xFF252836);

        // Title
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, panelY + 6, 0xFFF5A623);

        // Empty state
        if (!addMode && shortcuts.isEmpty()) {
            Text emptyMsg = Text.translatable("cheesedash.quickmenu.empty")
                    .append(Text.literal(" "))
                    .append(Text.translatable("cheesedash.quickmenu.empty_hint"));
            context.drawCenteredTextWithShadow(textRenderer, emptyMsg, width / 2, panelY + TITLE_HEIGHT + PADDING + 4, 0xFF6B7280);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Close on pressing the keybind again
        if (CheeseDashClient.QUICK_MENU_KEY != null && CheeseDashClient.QUICK_MENU_KEY.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}

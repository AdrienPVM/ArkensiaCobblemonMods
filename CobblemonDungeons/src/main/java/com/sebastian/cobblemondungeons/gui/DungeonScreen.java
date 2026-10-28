package com.sebastian.cobblemondungeons.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import java.util.List;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class DungeonScreen extends HandledScreen<DungeonScreenHandler> {
    private static final Identifier DUNGEON_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/dungeon_selection.png");
    private static final Identifier PARTY_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/party_management.png");
    private static final Identifier SEARCHING_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/searching_group.png");
    private static final Identifier INVITES_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/party_invites.png");
    private static final Identifier INVITE_PLAYERS_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/invite_players.png");
    private static final Identifier BOSS_PROMPT_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/boss_prompt.png");
    private static final Identifier DUNGEON_GO_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/dungeongo.png");
    private static final Identifier DUNGEON_INFO_TEXTURE = Identifier.of(CobblemonDungeons.MOD_ID, "textures/gui/dungeon_info.png");

    // Change these if your custom texture files are not 256x256
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;

    private ButtonWidget createDungeonsPartyButton;
    private ButtonWidget searchGroupButton;
    private ButtonWidget leaveSearchButton;
    private ButtonWidget invitesButton;
    private ButtonWidget leaveDungeonsPartyButton;
    private ButtonWidget inviteOthersButton;
    private ButtonWidget startDungeonButton;
    private ButtonWidget invitePlayersBackButton;
    private ButtonWidget invitesBackButton;
    private ButtonWidget bossYesButton;
    private ButtonWidget bossNoButton;
    private ButtonWidget dungeonGoButton;
    private ButtonWidget tier1Button;
    private ButtonWidget tier2Button;
    private ButtonWidget tier3Button;
    private ButtonWidget tier4Button;
    private ButtonWidget tier5Button;
    private ButtonWidget ticketExchangeButton;
    private ButtonWidget exchangeT1toT2Button;
    private ButtonWidget exchangeT2toT3Button;
    private ButtonWidget exchangeT3toT4Button;
    private ButtonWidget exchangeT4toT5Button;

    private static double capturedMouseX = -1;
    private static double capturedMouseY = -1;
    private static long captureTime = 0;

    public static void captureMouse() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.mouse != null) {
            capturedMouseX = client.mouse.getX();
            capturedMouseY = client.mouse.getY();
            captureTime = System.currentTimeMillis();
        }
    }

    public DungeonScreen(DungeonScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        // Show the whole 256x256 texture
        this.backgroundWidth = 256;
        this.backgroundHeight = 256;
    }

    private Identifier getBackgroundTexture() {
        if (this.handler == null || this.handler.getMode() == null) return DUNGEON_TEXTURE;
        try {
            return switch (this.handler.getMode()) {
                case DUNGEONS -> DUNGEON_TEXTURE;
                case DUNGEONS_PARTY -> PARTY_TEXTURE;
                case SEARCHING -> SEARCHING_TEXTURE;
                case INVITES -> INVITES_TEXTURE;
                case INVITE_PLAYERS -> INVITE_PLAYERS_TEXTURE;
                case BOSS_PROMPT -> BOSS_PROMPT_TEXTURE;
                case DUNGEON_GO -> DUNGEON_GO_TEXTURE;
                case DUNGEON_INFO -> DUNGEON_INFO_TEXTURE;
                case TICKET_EXCHANGE -> DUNGEON_INFO_TEXTURE;
                default -> DUNGEON_TEXTURE;
            };
        } catch (Exception e) {
            return DUNGEON_TEXTURE;
        }
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        if (this.handler == null || this.textRenderer == null) return;
        
        // Remove the "Select a Dungeon" and "Inventory" text by not calling super
        if (this.handler.getMode() == DungeonScreenHandler.ViewMode.BOSS_PROMPT || this.handler.getMode() == DungeonScreenHandler.ViewMode.DUNGEON_INFO || this.handler.getMode() == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE) {
            String title = "Dungeon";
            if (this.handler.getMode() == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE) {
                title = "Ticket Exchange";
            } else {
                // Check slot 0 first (synced from server)
                if (handler.getSlot(0) != null && !handler.getSlot(0).getStack().isEmpty()) {
                    title = handler.getSlot(0).getStack().getName().getString();
                    // Strip color codes if they exist and we want to apply our own Bold
                    if (title.startsWith("\u00A7a")) title = title.substring(2);
                } else if (handler.getSelectedDungeonId() != null) {
                    var config = com.sebastian.cobblemondungeons.dungeon.DungeonManager.getDungeonConfig(handler.getSelectedDungeonId());
                    if (config != null) title = config.general.name;
                }
            }

            Text titleText = Text.literal(title).formatted(Formatting.BOLD);
            int titleWidth = this.textRenderer.getWidth(titleText);
            context.drawText(this.textRenderer, titleText, (backgroundWidth - titleWidth) / 2, 5, 0xFFFFFF, true);

            Text descText;
            if (this.handler.getMode() == DungeonScreenHandler.ViewMode.BOSS_PROMPT) {
                descText = Text.translatable("cobblemondungeons.screen.desc_boss_prompt");
            } else if (this.handler.getMode() == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE) {
                descText = Text.translatable("cobblemondungeons.screen.desc_exchange");
            } else {
                descText = Text.translatable("cobblemondungeons.screen.desc_enter");
            }
            int descWidth = this.textRenderer.getWidth(descText);
            context.drawText(this.textRenderer, descText, (backgroundWidth - descWidth) / 2, 24, 0x000000, false);
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        
        boolean isSmallGui = this.handler != null && (this.handler.getMode() == DungeonScreenHandler.ViewMode.DUNGEON_INFO || this.handler.getMode() == DungeonScreenHandler.ViewMode.BOSS_PROMPT || this.handler.getMode() == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE);
        int texWidth = isSmallGui ? 215 : 256;
        int texHeight = isSmallGui ? 97 : 256;

        context.drawTexture(getBackgroundTexture(), x, y, 0, 0, backgroundWidth, backgroundHeight, texWidth, texHeight);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (this.handler == null || this.textRenderer == null) return;
        DungeonScreenHandler.ViewMode mode = this.handler.getMode();
        if (mode == null) mode = DungeonScreenHandler.ViewMode.DUNGEONS;

        boolean isDungeonMode = mode == DungeonScreenHandler.ViewMode.DUNGEONS;
        if (createDungeonsPartyButton != null) {
            createDungeonsPartyButton.visible = isDungeonMode;
            createDungeonsPartyButton.active = isDungeonMode;
        }
        if (searchGroupButton != null) {
            searchGroupButton.visible = isDungeonMode;
            searchGroupButton.active = isDungeonMode;
        }

        boolean isSearchingMode = mode == DungeonScreenHandler.ViewMode.SEARCHING;
        boolean isInvitesMode = mode == DungeonScreenHandler.ViewMode.INVITES;
        boolean isTicketExchangeMode = mode == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE;
        if (leaveSearchButton != null) {
            leaveSearchButton.visible = isSearchingMode;
            leaveSearchButton.active = isSearchingMode;
        }
        if (invitesButton != null) {
            invitesButton.visible = isSearchingMode;
            invitesButton.active = isSearchingMode;
        }

        boolean isBossPromptMode = mode == DungeonScreenHandler.ViewMode.BOSS_PROMPT;
        boolean isDungeonGoMode = mode == DungeonScreenHandler.ViewMode.DUNGEON_GO;
        boolean isDungeonInfoMode = mode == DungeonScreenHandler.ViewMode.DUNGEON_INFO;
        boolean isPartyMode = mode == DungeonScreenHandler.ViewMode.DUNGEONS_PARTY;
        boolean isInvitePlayersMode = mode == DungeonScreenHandler.ViewMode.INVITE_PLAYERS;

        if (leaveDungeonsPartyButton != null) {
            leaveDungeonsPartyButton.visible = isPartyMode;
            leaveDungeonsPartyButton.active = isPartyMode;
        }
        if (inviteOthersButton != null) {
            inviteOthersButton.visible = isPartyMode;
            inviteOthersButton.active = isPartyMode;
        }
        if (startDungeonButton != null) {
            startDungeonButton.visible = isPartyMode && handler.isLeader();
            startDungeonButton.active = isPartyMode && handler.isLeader();
        }
        if (invitePlayersBackButton != null) {
            invitePlayersBackButton.visible = isInvitePlayersMode || isDungeonGoMode || isDungeonInfoMode || isTicketExchangeMode;
            invitePlayersBackButton.active = isInvitePlayersMode || isDungeonGoMode || isDungeonInfoMode || isTicketExchangeMode;
        }
        if (invitesBackButton != null) {
            invitesBackButton.visible = isInvitesMode;
            invitesBackButton.active = isInvitesMode;
        }

        if (bossYesButton != null) {
            bossYesButton.visible = isBossPromptMode;
            bossYesButton.active = isBossPromptMode;
        }
        if (bossNoButton != null) {
            bossNoButton.visible = isBossPromptMode;
            bossNoButton.active = isBossPromptMode;
        }

        if (dungeonGoButton != null) {
            dungeonGoButton.visible = isDungeonInfoMode;
            dungeonGoButton.active = isDungeonInfoMode;
        }
        if (tier1Button != null) {
            tier1Button.visible = isDungeonInfoMode;
            tier1Button.active = isDungeonInfoMode;
        }
        if (tier2Button != null) {
            tier2Button.visible = isDungeonInfoMode;
            tier2Button.active = isDungeonInfoMode;
        }
        if (tier3Button != null) {
            tier3Button.visible = isDungeonInfoMode;
            tier3Button.active = isDungeonInfoMode;
        }
        if (tier4Button != null) {
            tier4Button.visible = isDungeonInfoMode;
            tier4Button.active = isDungeonInfoMode;
        }
        if (tier5Button != null) {
            tier5Button.visible = isDungeonInfoMode;
            tier5Button.active = isDungeonInfoMode;
        }
        if (ticketExchangeButton != null) {
            ticketExchangeButton.visible = isDungeonInfoMode;
            ticketExchangeButton.active = isDungeonInfoMode;
        }
        if (exchangeT1toT2Button != null) {
            exchangeT1toT2Button.visible = isTicketExchangeMode;
            exchangeT1toT2Button.active = isTicketExchangeMode;
        }
        if (exchangeT2toT3Button != null) {
            exchangeT2toT3Button.visible = isTicketExchangeMode;
            exchangeT2toT3Button.active = isTicketExchangeMode;
        }
        if (exchangeT3toT4Button != null) {
            exchangeT3toT4Button.visible = isTicketExchangeMode;
            exchangeT3toT4Button.active = isTicketExchangeMode;
        }
        if (exchangeT4toT5Button != null) {
            exchangeT4toT5Button.visible = isTicketExchangeMode;
            exchangeT4toT5Button.active = isTicketExchangeMode;
        }

        super.render(context, mouseX, mouseY, delta);

        // Draw button highlights last
        ButtonWidget[] buttons = new ButtonWidget[]{createDungeonsPartyButton, searchGroupButton, leaveSearchButton, invitesButton, leaveDungeonsPartyButton, inviteOthersButton, startDungeonButton, invitePlayersBackButton, invitesBackButton, bossYesButton, bossNoButton, dungeonGoButton, tier1Button, tier2Button, tier3Button, tier4Button, tier5Button, ticketExchangeButton, exchangeT1toT2Button, exchangeT2toT3Button, exchangeT3toT4Button, exchangeT4toT5Button};
        for (ButtonWidget button : buttons) {
            if (button != null && button.visible && button.active && button.isHovered()) {
                try {
                    // In DUNGEON_GO, DUNGEON_INFO, BOSS_PROMPT, or TICKET_EXCHANGE mode, only highlight specific buttons if needed
                    if (mode == DungeonScreenHandler.ViewMode.DUNGEON_GO || mode == DungeonScreenHandler.ViewMode.DUNGEON_INFO || mode == DungeonScreenHandler.ViewMode.BOSS_PROMPT || mode == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE) {
                        if (button == invitePlayersBackButton || button == dungeonGoButton || button == bossYesButton || button == bossNoButton ||
                            button == tier1Button || button == tier2Button || button == tier3Button || button == tier4Button || button == tier5Button ||
                            button == ticketExchangeButton || button == exchangeT1toT2Button || button == exchangeT2toT3Button || button == exchangeT3toT4Button || button == exchangeT4toT5Button) {
                            context.fill(button.getX(), button.getY(), button.getX() + button.getWidth(), button.getY() + button.getHeight(), 0x40FFFFFF);
                        }
                    } else {
                        context.fill(button.getX(), button.getY(), button.getX() + button.getWidth(), button.getY() + button.getHeight(), 0x40FFFFFF);
                    }
                } catch (Exception ignored) {}
            }
        }

        if (createDungeonsPartyButton != null && createDungeonsPartyButton.visible && createDungeonsPartyButton.active && createDungeonsPartyButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.party").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.party_desc").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (searchGroupButton != null && searchGroupButton.visible && searchGroupButton.active && searchGroupButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.search").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.search_desc").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (leaveDungeonsPartyButton != null && leaveDungeonsPartyButton.visible && leaveDungeonsPartyButton.active && leaveDungeonsPartyButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.leave_party").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.left_click").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (inviteOthersButton != null && inviteOthersButton.visible && inviteOthersButton.active && inviteOthersButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.invite").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.left_click").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (startDungeonButton != null && startDungeonButton.visible && startDungeonButton.active && startDungeonButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.start").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.left_click").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if ((invitePlayersBackButton != null && invitePlayersBackButton.visible && invitePlayersBackButton.active && invitePlayersBackButton.isHovered()) ||
            (invitesBackButton != null && invitesBackButton.visible && invitesBackButton.active && invitesBackButton.isHovered())) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.back").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.back_desc").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (bossYesButton != null && bossYesButton.visible && bossYesButton.active && bossYesButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.boss_yes").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.boss_yes_desc").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (bossNoButton != null && bossNoButton.visible && bossNoButton.active && bossNoButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.boss_no").formatted(Formatting.RED),
                    Text.translatable("cobblemondungeons.screen.tooltip.boss_no_desc").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (dungeonGoButton != null && dungeonGoButton.visible && dungeonGoButton.active && dungeonGoButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.translatable("cobblemondungeons.screen.tooltip.start").formatted(Formatting.GREEN),
                    Text.translatable("cobblemondungeons.screen.tooltip.go").formatted(Formatting.GRAY)
            ), mouseX, mouseY);
        }

        if (tier1Button != null && tier1Button.visible && tier1Button.active && tier1Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.tier", 1).formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (tier2Button != null && tier2Button.visible && tier2Button.active && tier2Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.tier", 2).formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (tier3Button != null && tier3Button.visible && tier3Button.active && tier3Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.tier", 3).formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (tier4Button != null && tier4Button.visible && tier4Button.active && tier4Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.tier", 4).formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (tier5Button != null && tier5Button.visible && tier5Button.active && tier5Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.tier", 5).formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (ticketExchangeButton != null && ticketExchangeButton.visible && ticketExchangeButton.active && ticketExchangeButton.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.ticket_exchange").formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (exchangeT1toT2Button != null && exchangeT1toT2Button.visible && exchangeT1toT2Button.active && exchangeT1toT2Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.exchange_t1_t2").formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (exchangeT2toT3Button != null && exchangeT2toT3Button.visible && exchangeT2toT3Button.active && exchangeT2toT3Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.exchange_t2_t3").formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (exchangeT3toT4Button != null && exchangeT3toT4Button.visible && exchangeT3toT4Button.active && exchangeT3toT4Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.exchange_t3_t4").formatted(Formatting.GREEN)), mouseX, mouseY);
        }
        if (exchangeT4toT5Button != null && exchangeT4toT5Button.visible && exchangeT4toT5Button.active && exchangeT4toT5Button.isHovered()) {
            context.drawTooltip(this.textRenderer, List.of(Text.translatable("cobblemondungeons.screen.tooltip.exchange_t4_t5").formatted(Formatting.GREEN)), mouseX, mouseY);
        }

        this.drawMouseoverTooltip(context, mouseX, mouseY);
    }


    @Override
    protected void init() {
        if (this.handler == null) {
            this.close();
            return;
        }

        // Update dimensions based on current mode
        if (this.handler.getMode() == DungeonScreenHandler.ViewMode.DUNGEON_INFO || this.handler.getMode() == DungeonScreenHandler.ViewMode.BOSS_PROMPT || this.handler.getMode() == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE) {
            this.backgroundWidth = 215;
            this.backgroundHeight = 97;
        } else {
            this.backgroundWidth = 256;
            this.backgroundHeight = 256;
        }

        super.init();
        if (this.client != null && this.client.getWindow() != null && capturedMouseX != -1 && capturedMouseY != -1 && (System.currentTimeMillis() - captureTime) < 1000) {
            try {
                GLFW.glfwSetCursorPos(this.client.getWindow().getHandle(), capturedMouseX, capturedMouseY);
            } catch (Exception ignored) {}
            capturedMouseX = -1;
            capturedMouseY = -1;
        }

        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        if (this.handler.getMode() == DungeonScreenHandler.ViewMode.DUNGEON_INFO || this.handler.getMode() == DungeonScreenHandler.ViewMode.BOSS_PROMPT || this.handler.getMode() == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE) {
            if (this.handler.getMode() == DungeonScreenHandler.ViewMode.DUNGEON_INFO) {
                // Add Tier buttons for DUNGEON_INFO mode
                int btnWidth = 60;
                int btnHeight = 20;
                int gap = 7;
                int startX = x + 10;
                int row1Y = y + backgroundHeight + 4;
                int row2Y = y + backgroundHeight + 4 + 22;
                int row3Y = y + backgroundHeight + 4 + 44;

                this.tier1Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.tier1"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 10);
                    }
                }).dimensions(startX, row1Y, btnWidth, btnHeight).build());

                this.tier2Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.tier2"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 11);
                    }
                }).dimensions(startX + btnWidth + gap, row1Y, btnWidth, btnHeight).build());

                this.tier3Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.tier3"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 12);
                    }
                }).dimensions(startX + (btnWidth + gap) * 2, row1Y, btnWidth, btnHeight).build());

                this.tier4Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.tier4"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 13);
                    }
                }).dimensions(startX, row2Y, btnWidth, btnHeight).build());

                this.tier5Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.tier5"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 14);
                    }
                }).dimensions(startX + btnWidth + gap, row2Y, btnWidth, btnHeight).build());

                this.invitePlayersBackButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.cancel"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 4);
                    }
                }).dimensions(startX + (btnWidth + gap) * 2, row2Y, btnWidth, btnHeight).build());

                this.ticketExchangeButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.ticket_exchange"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 15);
                    }
                }).dimensions(startX, row3Y, btnWidth * 2 + gap, btnHeight).build());

            } else if (this.handler.getMode() == DungeonScreenHandler.ViewMode.TICKET_EXCHANGE) {
                int btnWidth = 100;
                int btnHeight = 20;
                int gap = 5;
                int startX = x + 5;
                int row1Y = y + backgroundHeight + 4;
                int row2Y = y + backgroundHeight + 4 + 22;
                int row3Y = y + backgroundHeight + 4 + 44;

                this.exchangeT1toT2Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.exchange_t1_t2"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 16);
                    }
                }).dimensions(startX, row1Y, btnWidth, btnHeight).build());

                this.exchangeT2toT3Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.exchange_t2_t3"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 17);
                    }
                }).dimensions(startX + btnWidth + gap, row1Y, btnWidth, btnHeight).build());

                this.exchangeT3toT4Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.exchange_t3_t4"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 18);
                    }
                }).dimensions(startX, row2Y, btnWidth, btnHeight).build());

                this.exchangeT4toT5Button = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.exchange_t4_t5"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 19);
                    }
                }).dimensions(startX + btnWidth + gap, row2Y, btnWidth, btnHeight).build());

                this.invitePlayersBackButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.back"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 4);
                    }
                }).dimensions(startX + (btnWidth + gap) / 2, row3Y, btnWidth, btnHeight).build());

            } else {
                // Add visible buttons for BOSS_PROMPT mode
                this.bossNoButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.no"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 8);
                    }
                }).dimensions(x + 20, y + backgroundHeight + 4, 80, 20).build());

                this.bossYesButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("cobblemondungeons.screen.button.yes"), button -> {
                    captureMouse();
                    if (this.client != null && this.client.interactionManager != null) {
                        this.client.interactionManager.clickButton(handler.syncId, 7);
                    }
                }).dimensions(x + backgroundWidth - 100, y + backgroundHeight + 4, 80, 20).build());
            }
            
            return; // Don't add the other invisible buttons in these modes
        }

        // Add Create DungeonsParty button (ID 0)
        this.createDungeonsPartyButton = this.addDrawableChild(new InvisibleButton(x + 64, y + 71, 53, 16, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 0);
            }
        }));

        // Add Search Group button (ID 1)
        this.searchGroupButton = this.addDrawableChild(new InvisibleButton(x + 138, y + 70, 53, 17, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 1);
            }
        }));

        // Add Leave Search button (ID 2)
        this.leaveSearchButton = this.addDrawableChild(new InvisibleButton(x + 64, y + 70, 54, 18, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 2);
            }
        }));

        // Add Invites button (ID 3)
        this.invitesButton = this.addDrawableChild(new InvisibleButton(x + 137, y + 70, 54, 17, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 3);
            }
        }));

        // Add Leave DungeonsParty button (ID 4)
        this.leaveDungeonsPartyButton = this.addDrawableChild(new InvisibleButton(x + 64, y + 69, 19, 19, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 4);
            }
        }));

        // Add Invite Others button (ID 5)
        this.inviteOthersButton = this.addDrawableChild(new InvisibleButton(x + 173, y + 69, 19, 19, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 5);
            }
        }));

        // Add Start Dungeon button (ID 6)
        this.startDungeonButton = this.addDrawableChild(new InvisibleButton(x + 137, y + 106, 55, 18, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 6);
            }
        }));

        // Add Invite Players Back button (ID 4 -> DungeonsParty)
        this.invitePlayersBackButton = this.addDrawableChild(new InvisibleButton(x + 45, y + 72, 17, 14, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 4);
            }
        }));

        // Add DungeonsParty Invites Back button (ID 2 -> Searching)
        this.invitesBackButton = this.addDrawableChild(new InvisibleButton(x + 45, y + 72, 17, 14, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 2);
            }
        }));

        // Add Boss Yes button (ID 7)
        this.bossYesButton = this.addDrawableChild(new InvisibleButton(x + 146, y + 67, 47, 47, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 7);
            }
        }));

        // Add Boss No button (ID 8)
        this.bossNoButton = this.addDrawableChild(new InvisibleButton(x + 62, y + 67, 47, 47, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 8);
            }
        }));

        // Add Dungeon Go button (ID 9)
        this.dungeonGoButton = this.addDrawableChild(new InvisibleButton(x + 137, y + 106, 55, 18, button -> {
            captureMouse();
            if (this.client != null && this.client.interactionManager != null) {
                this.client.interactionManager.clickButton(handler.syncId, 9);
            }
        }));
    }

    @Override
    protected void drawSlot(DrawContext context, Slot slot) {
        if (slot.id < 27 && this.handler != null) {
            DungeonScreenHandler.ViewMode mode = this.handler.getMode();
            if (mode == DungeonScreenHandler.ViewMode.DUNGEON_INFO || mode == DungeonScreenHandler.ViewMode.BOSS_PROMPT || mode == DungeonScreenHandler.ViewMode.DUNGEONS) {
                return; // Don't draw items in these modes to allow custom texture to show through
            }
        }
        super.drawSlot(context, slot);
    }

    private class InvisibleButton extends ButtonWidget {
        public InvisibleButton(int x, int y, int width, int height, PressAction onPress) {
            super(x, y, width, height, Text.empty(), onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            // Highlights are handled in DungeonScreen.render to avoid being overwritten by slot-hiding logic
        }
    }
}


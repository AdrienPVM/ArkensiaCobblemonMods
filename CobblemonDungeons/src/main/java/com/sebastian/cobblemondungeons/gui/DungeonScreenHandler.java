package com.sebastian.cobblemondungeons.gui;

import com.mojang.authlib.GameProfile;
import com.sebastian.cobblemondungeons.dungeon.DungeonInstance;
import com.sebastian.cobblemondungeons.dungeon.DungeonManager;
import com.sebastian.cobblemondungeons.party.DungeonsParty;
import com.sebastian.cobblemondungeons.party.DungeonsPartyManager;
import com.sebastian.cobblemondungeons.config.DungeonConfig;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import com.sebastian.cobblemondungeons.config.ConfigLoader;
import java.util.*;

public class DungeonScreenHandler extends ScreenHandler {
    private final Inventory inventory;
    private boolean isLeader = false;

    public enum ViewMode { DUNGEONS, DUNGEONS_PARTY, SEARCHING, INVITES, INVITE_PLAYERS, BOSS_PROMPT, DUNGEON_GO, DUNGEON_INFO, TICKET_EXCHANGE }
    private ViewMode mode = ViewMode.DUNGEONS;
    private final net.minecraft.screen.PropertyDelegate propertyDelegate;
    private String selectedDungeonId = null;

    public String getSelectedDungeonId() {
        return selectedDungeonId;
    }

    public DungeonScreenHandler(int syncId, PlayerInventory playerInventory, ViewMode mode) {
        this(syncId, playerInventory, new SimpleInventory(27), mode);
    }

    public DungeonScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory, ViewMode mode) {
        super(getHandlerType(mode), syncId);
        checkSize(inventory, 27);
        this.inventory = inventory;
        this.mode = mode;
        inventory.onOpen(playerInventory.player);

        this.propertyDelegate = new net.minecraft.screen.PropertyDelegate() {
            @Override
            public int get(int index) {
                if (index == 0) return DungeonScreenHandler.this.mode.ordinal();
                if (index == 1) {
                    if (playerInventory.player == null) return 0;
                    DungeonsParty party = DungeonsPartyManager.getParty(playerInventory.player.getUuid());
                    return (party != null && party.getLeader() != null && party.getLeader().equals(playerInventory.player.getUuid())) ? 1 : 0;
                }
                return 0;
            }

            @Override
            public void set(int index, int value) {
                if (index == 0) DungeonScreenHandler.this.mode = ViewMode.values()[value];
                if (index == 1) DungeonScreenHandler.this.isLeader = (value == 1);
            }

            @Override
            public int size() {
                return 2;
            }
        };
        this.addProperties(propertyDelegate);

        // Dungeon slots (3x9)
        // Positioned for 256x256 GUI - Centered layout
        int xOffset = 47;
        int yOffset = 100;
        if (mode == ViewMode.BOSS_PROMPT || mode == ViewMode.DUNGEON_INFO || mode == ViewMode.TICKET_EXCHANGE) {
            xOffset = 10000;
            yOffset = 10000;
        }
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int x = xOffset + col * 18;
                int y = yOffset + row * 18;
                // Special position for party members in party mode
                if (mode == ViewMode.DUNGEONS_PARTY) {
                    int slotIndex = col + row * 9;
                    if (slotIndex < 9) {
                        x = 84 + slotIndex * 18; // 16px head + 2px gap
                        y = 70;
                    }
                } else if (mode == ViewMode.INVITES || mode == ViewMode.INVITE_PLAYERS || mode == ViewMode.DUNGEON_GO) {
                    int slotIndex = col + row * 9;
                    // Place icons in rows of 7 to stay within GUI bounds
                    x = 66 + (slotIndex % 7) * 18;
                    y = 71 + (slotIndex / 7) * 18;
                }
                this.addSlot(new Slot(inventory, col + row * 9, x, y));
            }
        }

        // Player inventory
        int playerXOffset = 47;
        int playerYOffset = 157;
        if (mode == ViewMode.BOSS_PROMPT || mode == ViewMode.DUNGEON_INFO || mode == ViewMode.TICKET_EXCHANGE) {
            playerXOffset = 10000;
            playerYOffset = 10000;
        }
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, playerXOffset + col * 18, playerYOffset + row * 18));
            }
        }

        // Player hotbar
        int hotbarYOffset = 215;
        if (mode == ViewMode.BOSS_PROMPT || mode == ViewMode.DUNGEON_INFO || mode == ViewMode.TICKET_EXCHANGE) hotbarYOffset = 10000;
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, playerXOffset + col * 18, hotbarYOffset));
        }

        if (!playerInventory.player.getWorld().isClient) {
            refreshInventory(playerInventory.player);
        }
    }

    public ViewMode getMode() {
        return mode;
    }

    public boolean isLeader() {
        return isLeader;
    }

    private static ScreenHandlerType<DungeonScreenHandler> getHandlerType(ViewMode mode) {
        return switch (mode) {
            case DUNGEONS -> CobblemonDungeons.DUNGEONS_SCREEN_HANDLER;
            case DUNGEONS_PARTY -> CobblemonDungeons.DUNGEONS_PARTY_SCREEN_HANDLER;
            case SEARCHING -> CobblemonDungeons.SEARCHING_SCREEN_HANDLER;
            case INVITES -> CobblemonDungeons.INVITES_SCREEN_HANDLER;
            case INVITE_PLAYERS -> CobblemonDungeons.INVITE_PLAYERS_SCREEN_HANDLER;
            case BOSS_PROMPT -> CobblemonDungeons.BOSS_PROMPT_SCREEN_HANDLER;
            case DUNGEON_GO -> CobblemonDungeons.DUNGEON_GO_SCREEN_HANDLER;
            case DUNGEON_INFO -> CobblemonDungeons.DUNGEON_INFO_SCREEN_HANDLER;
            case TICKET_EXCHANGE -> CobblemonDungeons.TICKET_EXCHANGE_SCREEN_HANDLER;
        };
    }

    public void refreshInventory(PlayerEntity player) {
        inventory.clear();
        if (mode == ViewMode.DUNGEONS) {
            Map<String, DungeonConfig> dungeons = DungeonManager.getDungeons();
            if (dungeons.isEmpty()) {
                ItemStack empty = new ItemStack(Items.PAPER);
                empty.set(DataComponentTypes.CUSTOM_NAME, Text.translatable("cobblemondungeons.gui.no_dungeons").formatted(Formatting.RED));
                inventory.setStack(0, empty);
            } else {
                int index = 0;
                for (DungeonConfig config : dungeons.values()) {
                    if (index >= 27) break;
                    inventory.setStack(index++, createDungeonIcon(config));
                }
            }
        } else if (mode == ViewMode.DUNGEONS_PARTY) {
            DungeonsParty party = DungeonsPartyManager.getParty(player.getUuid());
            if (party != null) {
                // Leader always in slot 0
                UUID leaderUuid = party.getLeader();
                inventory.setStack(0, createPlayerHead(player, leaderUuid, true));

                int index = 1;
                for (UUID memberUuid : party.getMembers()) {
                    if (memberUuid.equals(leaderUuid)) continue;
                    if (index >= 27) break;
                    inventory.setStack(index++, createPlayerHead(player, memberUuid, false));
                }
            } else {
                ItemStack msg = new ItemStack(Items.PAPER);
                msg.set(DataComponentTypes.CUSTOM_NAME, Text.translatable("cobblemondungeons.gui.not_in_party").formatted(Formatting.GRAY));
                inventory.setStack(13, msg);
            }
        } else if (mode == ViewMode.SEARCHING) {
            // User requested to remove heads from searching GUI
        } else if (mode == ViewMode.INVITES) {
            Set<UUID> inviters = DungeonsPartyManager.getInvites(player.getUuid());
            int index = 0;
            // Only show inviters
            for (UUID uuid : inviters) {
                ServerPlayerEntity p = player.getServer().getPlayerManager().getPlayer(uuid);
                if (p == null || !DungeonsPartyManager.hasPokemon(p)) continue;

                if (index >= 27) break;
                inventory.setStack(index++, createPlayerHead(player, uuid, false));
            }
        } else if (mode == ViewMode.INVITE_PLAYERS) {
            Set<UUID> searching = DungeonsPartyManager.getSearchingPlayers();
            int index = 0;
            DungeonsParty myParty = DungeonsPartyManager.getParty(player.getUuid());
            for (UUID uuid : searching) {
                if (uuid.equals(player.getUuid())) continue;
                if (myParty != null && myParty.getMembers().contains(uuid)) continue;

                ServerPlayerEntity p = player.getServer().getPlayerManager().getPlayer(uuid);
                if (p == null || !DungeonsPartyManager.hasPokemon(p)) continue;

                if (index >= 27) break;
                inventory.setStack(index++, createPlayerHead(player, uuid, false));
            }
        } else if (mode == ViewMode.DUNGEON_GO) {
            Map<String, DungeonConfig> dungeons = DungeonManager.getDungeons();
            if (dungeons.isEmpty()) {
                ItemStack empty = new ItemStack(Items.PAPER);
                empty.set(DataComponentTypes.CUSTOM_NAME, Text.translatable("cobblemondungeons.gui.no_dungeons").formatted(Formatting.RED));
                inventory.setStack(0, empty);
            } else {
                if (selectedDungeonId != null && dungeons.containsKey(selectedDungeonId)) {
                    inventory.setStack(0, createDungeonIcon(dungeons.get(selectedDungeonId)));
                } else {
                    int index = 0;
                    for (DungeonConfig config : dungeons.values()) {
                        if (index >= 21) break; // Support up to 3 rows of 7
                        inventory.setStack(index++, createDungeonIcon(config));
                    }
                }
            }
        } else if (mode == ViewMode.BOSS_PROMPT || mode == ViewMode.DUNGEON_INFO) {
            if (selectedDungeonId != null) {
                Map<String, DungeonConfig> dungeons = DungeonManager.getDungeons();
                if (dungeons.containsKey(selectedDungeonId)) {
                    inventory.setStack(0, createDungeonIcon(dungeons.get(selectedDungeonId)));
                }
            }
        }
        if (!player.getWorld().isClient) {
            sendContentUpdates();
        }
    }

    private ItemStack createDungeonIcon(DungeonConfig config) {
        net.minecraft.item.Item iconItem = Items.PAPER;
        try {
            iconItem = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(config.general.guiIcon));
            if (iconItem == Items.AIR && !config.general.guiIcon.equals("minecraft:air")) {
                iconItem = Items.PAPER;
            }
        } catch (Exception e) {
            iconItem = Items.PAPER;
        }

        ItemStack stack = new ItemStack(iconItem);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("\u00A7a" + config.general.name).styled(s -> s.withItalic(false)));
        
        List<Text> lore = new ArrayList<>();
        lore.add(Text.translatable("cobblemondungeons.gui.dungeon_label").formatted(Formatting.GRAY));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        
        return stack;
    }

    private ItemStack createPlayerHead(PlayerEntity player, UUID uuid, boolean isLeader) {
        String name = uuid.toString();
        GameProfile profile = null;
        if (player.getServer() != null) {
            ServerPlayerEntity member = player.getServer().getPlayerManager().getPlayer(uuid);
            if (member != null) {
                name = member.getName().getString();
                profile = member.getGameProfile();
            } else {
                var cached = player.getServer().getUserCache().getByUuid(uuid);
                if (cached.isPresent()) {
                    name = cached.get().getName();
                    profile = cached.get();
                }
            }
        }

        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        String color = isLeader ? "\u00A7b" : "\u00A7a";
        head.set(DataComponentTypes.CUSTOM_NAME, Text.literal(color + name).styled(s -> s.withItalic(false)));
        
        List<Text> lore = new ArrayList<>();
        lore.add(Text.translatable(isLeader ? "cobblemondungeons.gui.leader" : "cobblemondungeons.gui.member").formatted(Formatting.GRAY));
        head.set(DataComponentTypes.LORE, new LoreComponent(lore));
        
        if (profile != null) {
            head.set(DataComponentTypes.PROFILE, new ProfileComponent(profile));
        }
        
        return head;
    }

    public static void openMode(ServerPlayerEntity player, ViewMode mode) {
        openMode(player, mode, null);
    }

    public static void openMode(ServerPlayerEntity player, ViewMode mode, String selectedDungeonId) {
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Text.empty();
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
                DungeonScreenHandler handler = new DungeonScreenHandler(syncId, playerInventory, new SimpleInventory(27), mode);
                if (selectedDungeonId != null) {
                    handler.selectedDungeonId = selectedDungeonId;
                    handler.refreshInventory(player);
                }
                return handler;
            }
        });
    }

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        if (!player.getWorld().isClient) {
            if (id == 0) { // Create/View DungeonsParty
                ViewMode nextMode = (mode == ViewMode.DUNGEONS_PARTY) ? ViewMode.DUNGEONS : ViewMode.DUNGEONS_PARTY;
                if (nextMode == ViewMode.DUNGEONS_PARTY && DungeonsPartyManager.getParty(player.getUuid()) == null) {
                    if (DungeonsPartyManager.createParty(player.getUuid(), player.getServer()) != null) {
                        player.sendMessage(Text.translatable("cobblemondungeons.party.created").formatted(Formatting.GREEN));
                        openMode((ServerPlayerEntity) player, nextMode);
                    }
                    // If failed, createParty already sent a message
                } else {
                    openMode((ServerPlayerEntity) player, nextMode);
                }
                return true;
            } else if (id == 1) { // Search Group
                ViewMode nextMode = (mode == ViewMode.SEARCHING) ? ViewMode.DUNGEONS : ViewMode.SEARCHING;
                if (nextMode == ViewMode.SEARCHING) {
                    if (DungeonsPartyManager.getParty(player.getUuid()) == null) {
                        if (DungeonsPartyManager.setSearchingForGroup(player.getUuid(), true, player.getServer())) {
                            player.sendMessage(Text.translatable("cobblemondungeons.party.searching").formatted(Formatting.GREEN));
                            openMode((ServerPlayerEntity) player, nextMode);
                        }
                    } else {
                        // Already in a party, can't search but maybe switch to searching view anyway?
                        // Usually searching is only for solo players.
                        openMode((ServerPlayerEntity) player, nextMode);
                    }
                } else {
                    DungeonsPartyManager.setSearchingForGroup(player.getUuid(), false, player.getServer());
                    openMode((ServerPlayerEntity) player, nextMode);
                }
                return true;
            } else if (id == 2) { // Leave Searching Group
                if (mode == ViewMode.INVITES) {
                    openMode((ServerPlayerEntity) player, ViewMode.SEARCHING);
                } else {
                    DungeonsPartyManager.setSearchingForGroup(player.getUuid(), false, player.getServer());
                    openMode((ServerPlayerEntity) player, ViewMode.DUNGEONS);
                }
                return true;
            } else if (id == 3) { // Invites
                ViewMode nextMode = (mode == ViewMode.INVITES) ? ViewMode.SEARCHING : ViewMode.INVITES;
                openMode((ServerPlayerEntity) player, nextMode);
                return true;
            } else if (id == 4) { // Leave DungeonsParty or Back
                if (mode == ViewMode.INVITE_PLAYERS || mode == ViewMode.DUNGEON_GO) {
                    openMode((ServerPlayerEntity) player, ViewMode.DUNGEONS_PARTY);
                } else if (mode == ViewMode.DUNGEON_INFO) {
                    openMode((ServerPlayerEntity) player, ViewMode.DUNGEON_GO, selectedDungeonId);
                } else if (mode == ViewMode.TICKET_EXCHANGE) {
                    openMode((ServerPlayerEntity) player, ViewMode.DUNGEON_INFO, selectedDungeonId);
                } else {
                    DungeonsPartyManager.leaveParty(player.getUuid(), player.getServer());
                    openMode((ServerPlayerEntity) player, ViewMode.DUNGEONS);
                }
                return true;
            } else if (id == 5) { // Invite Others
                ViewMode nextMode = (mode == ViewMode.INVITE_PLAYERS) ? ViewMode.DUNGEONS_PARTY : ViewMode.INVITE_PLAYERS;
                openMode((ServerPlayerEntity) player, nextMode);
                return true;
            } else if (id == 6) { // Open Go GUI
                openMode((ServerPlayerEntity) player, ViewMode.DUNGEON_GO);
                return true;
            } else if (id == 9) { // Start Dungeon (from Go or Info GUI)
                Map<String, DungeonConfig> dungeons = DungeonManager.getDungeons();
                if (!dungeons.isEmpty()) {
                    DungeonConfig selected = null;
                    if (this.selectedDungeonId != null) {
                        selected = dungeons.get(this.selectedDungeonId);
                    }
                    if (selected == null) {
                        selected = dungeons.values().iterator().next();
                    }
                    if (DungeonManager.startDungeon((ServerPlayerEntity) player, selected.general.id)) {
                        ((ServerPlayerEntity) player).closeHandledScreen();
                    }
                }
                return true;
            } else if (id >= 10 && id <= 14) { // Tier 1-5 buttons
                int tier = id - 9;
                Map<String, DungeonConfig> dungeons = DungeonManager.getDungeons();
                if (!dungeons.isEmpty()) {
                    DungeonConfig selected = null;
                    if (this.selectedDungeonId != null) {
                        selected = dungeons.get(this.selectedDungeonId);
                    }
                    if (selected == null) {
                        selected = dungeons.values().iterator().next();
                    }
                    if (DungeonManager.startDungeon((ServerPlayerEntity) player, selected.general.id, tier)) {
                        ((ServerPlayerEntity) player).closeHandledScreen();
                    }
                }
                return true;
            } else if (id == 15) { // Open Ticket Exchange
                openMode((ServerPlayerEntity) player, ViewMode.TICKET_EXCHANGE, selectedDungeonId);
                return true;
            } else if (id == 16) { // Exchange 5 T1 -> 1 T2
                attemptExchange(player, 1, 2, 5);
                return true;
            } else if (id == 17) { // Exchange 3 T2 -> 1 T3
                attemptExchange(player, 2, 3, 3);
                return true;
            } else if (id == 18) { // Exchange 3 T3 -> 1 T4
                attemptExchange(player, 3, 4, 3);
                return true;
            } else if (id == 19) { // Exchange 3 T4 -> 1 T5
                attemptExchange(player, 4, 5, 3);
                return true;
            } else if (id == 7) { // Boss Yes
                DungeonInstance instance = DungeonManager.getInstance(player.getUuid());
                if (instance != null) {
                    if (instance.canAccessBoss()) {
                        // Spawn the boss if not already spawned
                        DungeonManager.spawnBoss(instance, player.getServer());

                        // Teleport the entire party to the boss room
                        List<UUID> members = new ArrayList<>(instance.getPlayers());
                        for (UUID memberUuid : members) {
                            ServerPlayerEntity member = player.getServer().getPlayerManager().getPlayer(memberUuid);
                            if (member != null) {
                                instance.addBossRoomVisitor(memberUuid);
                                DungeonManager.teleportToDungeon(member, instance.getConfig().locations.bossRoomPos);
                                if (member.currentScreenHandler instanceof DungeonScreenHandler) {
                                    member.closeHandledScreen();
                                }
                            }
                        }
                        DungeonManager.broadcastToDungeon(instance, Text.translatable("cobblemondungeons.boss_fight_started", player.getName().getString()).formatted(Formatting.GOLD, Formatting.BOLD), player.getServer());
                    } else {
                        player.sendMessage(Text.translatable("cobblemondungeons.boss_room_locked").formatted(Formatting.RED), true);
                        ((ServerPlayerEntity) player).closeHandledScreen();
                    }
                }
                return true;
            } else if (id == 8) { // Boss No
                ((ServerPlayerEntity) player).closeHandledScreen();
                return true;
            }
        }
        return super.onButtonClick(player, id);
    }

    @Override
    public void onSlotClick(int slotIndex, int button, net.minecraft.screen.slot.SlotActionType actionType, PlayerEntity player) {
        if (!player.getWorld().isClient && slotIndex >= 0 && slotIndex < 27) {
            if (mode == ViewMode.DUNGEONS) {
                Map<String, DungeonConfig> dungeons = DungeonManager.getDungeons();
                if (slotIndex < dungeons.size()) {
                    DungeonConfig selected = new ArrayList<>(dungeons.values()).get(slotIndex);
                    openMode((ServerPlayerEntity) player, ViewMode.DUNGEON_GO, selected.general.id);
                }
            } else if (mode == ViewMode.DUNGEONS_PARTY) {
                // No specific slot actions for party mode anymore
            } else if (mode == ViewMode.SEARCHING) {
                // No heads to click
            } else if (mode == ViewMode.INVITES) {
                List<UUID> inviters = new ArrayList<>(DungeonsPartyManager.getInvites(player.getUuid()));
                if (slotIndex < inviters.size()) {
                    UUID target = inviters.get(slotIndex);
                    ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
                    if (DungeonsPartyManager.joinSpecificParty(serverPlayer, target)) {
                        serverPlayer.sendMessage(Text.translatable("cobblemondungeons.party.joined").formatted(Formatting.GREEN));
                        DungeonsPartyGui.open(serverPlayer);
                    } else {
                        if (DungeonsPartyManager.hasPokemon(serverPlayer)) {
                            DungeonsParty party = DungeonsPartyManager.getParty(target);
                            if (party == null) {
                                serverPlayer.sendMessage(Text.translatable("cobblemondungeons.party.join_failed_disbanded").formatted(Formatting.RED));
                            } else if (party.getMembers().size() < DungeonsPartyManager.MAX_PARTY_SIZE) {
                                // If it's not full and it exists, but join failed, it might be that the invite was removed or something else.
                                serverPlayer.sendMessage(Text.translatable("cobblemondungeons.party.join_failed").formatted(Formatting.RED));
                            }
                            // If it's full, joinSpecificParty already sent the message.
                        }
                        refreshInventory(serverPlayer); // Refresh to remove invalid invite
                    }
                }
            } else if (mode == ViewMode.INVITE_PLAYERS) {
                Set<UUID> searching = DungeonsPartyManager.getSearchingPlayers();
                List<UUID> validTargets = new ArrayList<>();
                DungeonsParty myParty = DungeonsPartyManager.getParty(player.getUuid());
                for (UUID uuid : searching) {
                    if (uuid.equals(player.getUuid())) continue;
                    if (myParty != null && myParty.getMembers().contains(uuid)) continue;
                    validTargets.add(uuid);
                }
                
                if (slotIndex < validTargets.size()) {
                    UUID targetUuid = validTargets.get(slotIndex);
                    if (DungeonsPartyManager.invitePlayer(player.getUuid(), targetUuid, player.getServer())) {
                        ServerPlayerEntity target = player.getServer().getPlayerManager().getPlayer(targetUuid);
                        player.sendMessage(Text.translatable("cobblemondungeons.party.invited_player", (target != null ? target.getName().getString() : targetUuid.toString())).formatted(Formatting.GREEN));
                    }
                }
            } else if (mode == ViewMode.DUNGEON_GO) {
                Map<String, DungeonConfig> dungeons = DungeonManager.getDungeons();
                DungeonConfig chosen = null;
                if (selectedDungeonId != null && dungeons.containsKey(selectedDungeonId)) {
                    if (slotIndex == 0) {
                        chosen = dungeons.get(selectedDungeonId);
                    }
                } else {
                    List<DungeonConfig> list = new ArrayList<>(dungeons.values());
                    if (slotIndex < list.size()) {
                        chosen = list.get(slotIndex);
                    }
                }

                if (chosen != null) {
                    openMode((ServerPlayerEntity) player, ViewMode.DUNGEON_INFO, chosen.general.id);
                }
            }
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        this.inventory.onClose(player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        ItemStack itemStack = ItemStack.EMPTY;
        if (index >= 27 && index < this.slots.size()) {
            Slot slot = this.slots.get(index);
            if (slot != null && slot.hasStack()) {
                ItemStack itemStack2 = slot.getStack();
                itemStack = itemStack2.copy();
                if (index < 54) {
                    // From player inventory to hotbar
                    if (!this.insertItem(itemStack2, 54, 63, false)) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    // From hotbar to player inventory
                    if (!this.insertItem(itemStack2, 27, 54, false)) {
                        return ItemStack.EMPTY;
                    }
                }

                if (itemStack2.isEmpty()) {
                    slot.setStack(ItemStack.EMPTY);
                } else {
                    slot.markDirty();
                }

                if (itemStack2.getCount() == itemStack.getCount()) {
                    return ItemStack.EMPTY;
                }

                slot.onTakeItem(player, itemStack2);
            }
        }
        return itemStack;
    }


    @Override
    public boolean canUse(PlayerEntity player) {
        return this.inventory.canPlayerUse(player);
    }

    private boolean attemptExchange(PlayerEntity player, int fromTier, int toTier, int countNeeded) {
        if (player.getWorld().isClient) return false;
        
        PlayerInventory inv = player.getInventory();
        int found = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(CobblemonDungeons.DUNGEON_TICKET)) {
                NbtComponent nbtComponent = stack.get(DataComponentTypes.CUSTOM_DATA);
                if (nbtComponent != null) {
                    NbtCompound nbt = nbtComponent.copyNbt();
                    if (nbt.contains("Tier") && nbt.getInt("Tier") == fromTier) {
                        found += stack.getCount();
                    }
                }
            }
        }

        if (found < countNeeded) {
            player.sendMessage(Text.translatable("cobblemondungeons.exchange.not_enough", fromTier, countNeeded).formatted(Formatting.RED));
            return false;
        }

        // Find a template for the resulting ticket
        com.sebastian.cobblemondungeons.config.DungeonTicketConfig.TicketConfig template = null;
        for (com.sebastian.cobblemondungeons.config.DungeonTicketConfig.TicketConfig t : ConfigLoader.getTicketConfig().tickets) {
            if (t.tier == toTier) {
                template = t;
                break;
            }
        }

        if (template == null) {
            player.sendMessage(Text.translatable("cobblemondungeons.exchange.no_template", toTier).formatted(Formatting.RED));
            return false;
        }

        // Consume
        int remainingToRemove = countNeeded;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(CobblemonDungeons.DUNGEON_TICKET)) {
                NbtComponent nbtComponent = stack.get(DataComponentTypes.CUSTOM_DATA);
                if (nbtComponent != null) {
                    NbtCompound nbt = nbtComponent.copyNbt();
                    if (nbt.contains("Tier") && nbt.getInt("Tier") == fromTier) {
                        int amount = Math.min(stack.getCount(), remainingToRemove);
                        stack.decrement(amount);
                        remainingToRemove -= amount;
                        if (remainingToRemove <= 0) break;
                    }
                }
            }
        }

        // Give
        ItemStack result = com.sebastian.cobblemondungeons.item.DungeonTicketItem.createStack(template, 1);
        
        if (!inv.insertStack(result)) {
            player.dropItem(result, false);
        }
        
        player.sendMessage(Text.translatable("cobblemondungeons.exchange.success", countNeeded, fromTier, toTier).formatted(Formatting.GREEN));
        return true;
    }
}


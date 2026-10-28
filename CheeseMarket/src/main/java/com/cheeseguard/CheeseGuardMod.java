package com.cheeseguard;

import com.cheeseguard.commands.CheeseGuardCommand;
import com.cheeseguard.data.*;
import com.cheeseguard.gui.RegionEditGui;
import com.cheeseguard.logic.*;
import com.cheeseguard.network.GuiSyncPayload;
import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public class CheeseGuardMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static CheeseGuardMod INSTANCE;

    private MinecraftServer server;
    private RegionStorage storage;
    private RegionManager regionManager;
    private SelectionManager selectionManager;
    private VisualizationManager visualizationManager;
    private ChunkSetStorage chunkSetStorage;
    private ChunkSuggestionStorage chunkSuggestionStorage;
    private ServerManager serverManager;

    // ── Chunk zone notification tracking ─────────────────────────────────────
    // playerUUID -> set of "setName:chunkX:chunkZ" keys the player is currently inside
    private final Map<UUID, Set<String>> playerChunkZones = new HashMap<>();
    private int chunkCheckTick = 0;

    // ── Command Shortcuts ────────────────────────────────────────────────────
    public static class ShortcutData {
        public String label;
        public String command;
        public String color;
        public boolean closeGui;
        ShortcutData(String label, String command, String color) {
            this.label = label; this.command = command; this.color = color; this.closeGui = true;
        }
        ShortcutData(String label, String command, String color, boolean closeGui) {
            this.label = label; this.command = command; this.color = color; this.closeGui = closeGui;
        }
    }
    private final List<ShortcutData> shortcuts = new ArrayList<>();
    private java.nio.file.Path shortcutsFile;

    private void loadShortcuts() {
        try {
            if (shortcutsFile == null) return;
            if (!java.nio.file.Files.exists(shortcutsFile)) return;
            String json = new String(java.nio.file.Files.readAllBytes(shortcutsFile));
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            shortcuts.clear();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                shortcuts.add(new ShortcutData(
                    o.has("label")    ? o.get("label").getAsString()            : "?",
                    o.has("command")  ? o.get("command").getAsString()          : "",
                    o.has("color")    ? o.get("color").getAsString()            : "blue",
                    !o.has("closeGui") || o.get("closeGui").getAsBoolean()
                ));
            }
        } catch (Exception e) { LOGGER.warn("[CG] Failed to load shortcuts: {}", e.getMessage()); }
    }

    private void saveShortcuts() {
        try {
            if (shortcutsFile == null) return;
            JsonArray arr = new JsonArray();
            for (ShortcutData s : shortcuts) {
                JsonObject o = new JsonObject();
                o.addProperty("label",    s.label);
                o.addProperty("command",  s.command);
                o.addProperty("color",    s.color);
                o.addProperty("closeGui", s.closeGui);
                arr.add(o);
            }
            java.nio.file.Files.writeString(shortcutsFile, new GsonBuilder().setPrettyPrinting().create().toJson(arr));
        } catch (Exception e) { LOGGER.warn("[CG] Failed to save shortcuts: {}", e.getMessage()); }
    }

    private final Map<UUID, Consumer<String>> chatInputCallbacks = new HashMap<>();

    // Per-player advancement view state (filter + page)
    private static class AdvViewState { String target; String type; String filter = ""; int page = 0; }
    private final Map<UUID, AdvViewState> advViewStates = new HashMap<>();

    // Per-player player-adv view state
    private static class PlayerAdvState { UUID targetUuid; String filter = ""; int page = 0; }
    private final Map<UUID, PlayerAdvState> playerAdvStates = new HashMap<>();

    // Per-player quest view state (for zone/group quest select)
    private static class QuestViewState { String target; String type; String filter = ""; int page = 0; }
    private final Map<UUID, QuestViewState> questViewStates = new HashMap<>();

    // Per-player player quest view state
    private static class PlayerQuestState { UUID targetUuid; String filter = ""; int page = 0; }
    private final Map<UUID, PlayerQuestState> playerQuestStates = new HashMap<>();

    // Player list search state
    private final Map<UUID, String> playerListFilter = new HashMap<>();

    private boolean ftbQuestsLoaded = false;
    private final EventManager eventManager = new EventManager();
    private final com.cheeseguard.logic.DimensionManager dimensionManager = new com.cheeseguard.logic.DimensionManager();
    // Pending dimension configs: player clicks "+ Creer" → stored here until confirmed
    private final java.util.Map<java.util.UUID, com.cheeseguard.data.DimensionConfig> dimDrafts = new java.util.HashMap<>();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        storage = new RegionStorage();
        regionManager = new RegionManager(storage);
        selectionManager = new SelectionManager();
        visualizationManager = new VisualizationManager(storage);
        chunkSetStorage = new ChunkSetStorage();
        chunkSetStorage.load();
        chunkSuggestionStorage = new ChunkSuggestionStorage();
        chunkSuggestionStorage.load();
        serverManager = new ServerManager();

        CommandRegistrationCallback.EVENT.register((d, r, e) -> CheeseGuardCommand.register(d));
        new ProtectionListener().register();
        new WandListener().register();

        PayloadTypeRegistry.playS2C().register(GuiSyncPayload.ID, GuiSyncPayload.CODEC);

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            Consumer<String> cb = chatInputCallbacks.remove(sender.getUuid());
            if (cb != null) { String t = message.getContent().getString(); server.execute(() -> cb.accept(t)); return false; }
            return true;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            this.server = s;
            this.shortcutsFile = s.getRunDirectory().resolve("cheeseguard_shortcuts.json");
            loadShortcuts();
            loadWebhookConfig();
            eventManager.init(s);
            dimensionManager.init(s);
            DiscordWebhook.serverStarting();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (!serverManager.isActive()) DiscordWebhook.serverStopping();
        });
        // Tick events
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(srv -> {
            eventManager.tick();
            dimensionManager.tick();
            tickChunkZoneNotifications(srv);
        });
        // Player join (crash-safe inventory restore)
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            eventManager.onPlayerJoin(handler.getPlayer());
            dimensionManager.onPlayerJoin(handler.getPlayer());
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            dimensionManager.onPlayerDisconnect(handler.getPlayer().getUuid());
        });
        // Player respawn (death during event + dimension keepInventory)
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) { // alive=false means they actually died (not end portal)
                eventManager.onParticipantRespawn(oldPlayer, newPlayer);
                dimensionManager.onPlayerRespawnKeepInv(oldPlayer, newPlayer);
            }
        });
        ftbQuestsLoaded = FabricLoader.getInstance().isModLoaded("ftbquests");
        LOGGER.info("CheeseGuard loaded!" + (ftbQuestsLoaded ? " (FTB Quests detected)" : ""));
    }

    private void handleGuiAction(String action, ServerPlayerEntity player) {
        LOGGER.info("[CG] GUI action: {} from {}", action, player.getName().getString());
        String[] parts = action.split(":", 3);
        String cmd = parts[0], p1 = parts.length > 1 ? parts[1] : "", p2 = parts.length > 2 ? parts[2] : "";

        try {
        switch (cmd) {
            case "_close"           -> {}
            case "open_main"        -> sendMain(player);
            case "open_region_list" -> sendRegionList(player);
            case "open_region"      -> sendRegionEdit(player, p1);
            case "open_flags"       -> sendFlags(player, p1);
            case "open_members"     -> sendMembers(player, p1);
            case "open_group_list"  -> sendGroupList(player);
            case "open_group"       -> sendGroupEdit(player, p1);
            case "open_group_flags" -> sendGroupFlags(player, p1);
            case "open_chunk_sets"  -> sendChunkSets(player);
            case "open_chunk_set"   -> sendChunkSetEdit(player, p1);
            case "open_chunk_suggestions" -> sendChunkSuggestions(player);
            case "chunk_suggestion_delete" -> {
                if (chunkSuggestionStorage != null) chunkSuggestionStorage.remove(p1);
                sendChunkSuggestions(player);
            }
            case "chunk_suggestions_clear_all" -> {
                if (chunkSuggestionStorage != null) {
                    for (ChunkSuggestion s : new ArrayList<>(chunkSuggestionStorage.getAll()))
                        chunkSuggestionStorage.remove(s.getId());
                }
                sendChunkSuggestions(player);
            }
            case "chunk_suggestion_tp" -> {
                if (chunkSuggestionStorage != null) {
                    for (ChunkSuggestion s : chunkSuggestionStorage.getAll()) {
                        if (s.getId().equals(p1)) {
                            Identifier wid = Identifier.tryParse(s.getWorld());
                            if (wid != null) {
                                for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
                                    if (w.getRegistryKey().getValue().equals(wid)) {
                                        player.teleport(w, s.getX(), s.getY(), s.getZ(), player.getYaw(), player.getPitch());
                                        break;
                                    }
                                }
                            } else {
                                player.teleport(server.getOverworld(), s.getX(), s.getY(), s.getZ(), player.getYaw(), player.getPitch());
                            }
                            break;
                        }
                    }
                }
                sendChunkSuggestions(player);
            }
            case "open_server"      -> sendServerAdmin(player);
            case "open_player_list" -> { playerListFilter.put(player.getUuid(), ""); sendPlayerList(player, ""); }
            case "open_adv_select"  -> {
                // Full: open_adv_select:region:id:entry or open_adv_select:group:id:exit
                String[] ap = action.split(":", 4);
                if (ap.length >= 4) {
                    AdvViewState st = new AdvViewState();
                    st.target = ap[1]+":"+ap[2]; st.type = ap[3]; st.filter = ""; st.page = 0;
                    advViewStates.put(player.getUuid(), st);
                    sendAdvancementSelect(player, st.target, st.type, st.filter, st.page);
                }
            }
            case "adv_search" -> {
                // adv_search:filterText
                AdvViewState st = advViewStates.get(player.getUuid());
                if (st != null) { st.filter = p1; st.page = 0; sendAdvancementSelect(player, st.target, st.type, st.filter, st.page); }
            }
            case "adv_page" -> {
                // adv_page:pageNum
                AdvViewState st = advViewStates.get(player.getUuid());
                if (st != null) { try { st.page = Integer.parseInt(p1); } catch (Exception ignored) {} sendAdvancementSelect(player, st.target, st.type, st.filter, st.page); }
            }
            case "select_parent_for" -> sendSelectParent(player, p1);
            case "select_group_for"  -> sendSelectGroup(player, p1);
            case "give_wand"  -> { giveWand(player); sendMain(player); }
            case "toggle_viz" -> { visualizationManager.toggle(player.getUuid()); sendMain(player); }
            case "toggle_test_mode" -> {
                boolean on = regionManager.toggleTestMode(player.getUuid());
                player.sendMessage(Text.literal(on
                    ? "\uD83D\uDD0D Mode test ON — vous etes traite comme un joueur lambda dans les zones"
                    : "\uD83D\uDD0D Mode test OFF — vous etes a nouveau proprio/membre")
                    .formatted(on ? Formatting.GOLD : Formatting.GREEN), false);
                sendMain(player);
            }

            // ── Flags ─────────────────────────────────────────────────────────
            case "flag_allow", "flag_deny", "flag_inherit" -> {
                Region r = storage.getRegion(p1); Flag f = Flag.fromId(p2);
                if (r != null && f != null) {
                    r.setFlag(f, cmd.equals("flag_allow") ? Boolean.TRUE : cmd.equals("flag_deny") ? Boolean.FALSE : null);
                    storage.save();
                }
                sendFlags(player, p1);
            }
            case "flags_reset" -> {
                Region r = storage.getRegion(p1);
                if (r != null) { for (Flag f : Flag.values()) r.setFlag(f, null); storage.save(); }
                sendFlags(player, p1);
            }
            case "group_flag_allow", "group_flag_deny", "group_flag_inherit" -> {
                RegionGroup g = storage.getGroup(p1); Flag f = Flag.fromId(p2);
                if (g != null && f != null) {
                    g.setFlag(f, cmd.equals("group_flag_allow") ? Boolean.TRUE : cmd.equals("group_flag_deny") ? Boolean.FALSE : null);
                    storage.save();
                }
                sendGroupFlags(player, p1);
            }
            case "group_flags_reset" -> {
                RegionGroup g = storage.getGroup(p1);
                if (g != null) { for (Flag f : Flag.values()) g.setFlag(f, null); storage.save(); }
                sendGroupFlags(player, p1);
            }

            // ── Priority ──────────────────────────────────────────────────────
            case "region_priority_up"    -> { deltaPrio(p1, +1);  sendRegionEdit(player, p1); }
            case "region_priority_down"  -> { deltaPrio(p1, -1);  sendRegionEdit(player, p1); }
            case "region_priority_up10"  -> { deltaPrio(p1, +10); sendRegionEdit(player, p1); }
            case "region_priority_down10"-> { deltaPrio(p1, -10); sendRegionEdit(player, p1); }

            // ── TP ────────────────────────────────────────────────────────────
            case "tp_region" -> {
                Region r = storage.getRegion(p1);
                if (r != null) {
                    closeGui(player);
                    int cx = r.getCenterX(), cz = r.getCenterZ(), safeY = r.getCenterY();

                    // Resolve the region's dimension — NOT the player's current world
                    net.minecraft.server.world.ServerWorld targetWorld = null;
                    for (var w : server.getWorlds()) {
                        if (w.getRegistryKey().getValue().toString().equals(r.getWorld())) {
                            targetWorld = w; break;
                        }
                    }
                    if (targetWorld == null) targetWorld = server.getOverworld();

                    // Find safe Y in the correct world
                    for (int y = r.getMaxY(); y >= r.getMinY(); y--) {
                        var bp = new net.minecraft.util.math.BlockPos(cx, y, cz);
                        var ba = new net.minecraft.util.math.BlockPos(cx, y+1, cz);
                        var bb = new net.minecraft.util.math.BlockPos(cx, y+2, cz);
                        if (!targetWorld.getBlockState(bp).isAir()
                            && !targetWorld.getBlockState(ba).isSolidBlock(targetWorld, ba)
                            && !targetWorld.getBlockState(bb).isSolidBlock(targetWorld, bb)) {
                            safeY = y + 1; break;
                        }
                    }

                    // Use teleport() to cross dimensions, preserve yaw/pitch
                    player.teleport(targetWorld, cx + 0.5, (double) safeY, cz + 0.5,
                        java.util.Set.of(), player.getYaw(), player.getPitch());
                    player.sendMessage(Text.literal("Teleporte vers " + r.getName())
                        .formatted(Formatting.GREEN), false);
                }
            }

            // ── Color ─────────────────────────────────────────────────────────
            case "cycle_color" -> {
                Region r = storage.getRegion(p1);
                if (r != null) {
                    List<String> c = VisualizationManager.getAvailableColors();
                    r.setParticleColor(c.get((c.indexOf(r.getParticleColor()) + 1) % c.size()));
                    storage.save();
                }
                sendRegionEdit(player, p1);
            }

            // ── Delete ────────────────────────────────────────────────────────
            case "delete_region" -> {
                Region r = storage.getRegion(p1);
                if (r != null) { storage.removeRegion(p1); player.sendMessage(Text.literal("Zone supprimee : " + r.getName()).formatted(Formatting.RED), false); }
                sendRegionList(player);
            }
            case "delete_group" -> {
                RegionGroup g = storage.getGroup(p1);
                if (g != null) { storage.removeGroup(p1); player.sendMessage(Text.literal("Groupe supprime : " + g.getName()).formatted(Formatting.RED), false); }
                sendGroupList(player);
            }

            // ── Text input from client GUI ────────────────────────────────────
            case "text_rename_region" -> { Region r = storage.getRegion(p1); if (r != null && !p2.isBlank()) { r.setName(p2); storage.save(); } sendRegionEdit(player, p1); }
            case "text_rename_group"  -> { RegionGroup g = storage.getGroup(p1); if (g != null && !p2.isBlank()) { g.setName(p2); storage.save(); } sendGroupEdit(player, p1); }
            case "text_entry_msg"     -> { Region r = storage.getRegion(p1); if (r != null) { r.setEntryMessage(p2); storage.save(); } sendRegionEdit(player, p1); }
            case "text_exit_msg"      -> { Region r = storage.getRegion(p1); if (r != null) { r.setExitMessage(p2); storage.save(); } sendRegionEdit(player, p1); }
            case "text_protection_msg"-> { Region r = storage.getRegion(p1); if (r != null) { r.setProtectionMessage(p2); storage.save(); } sendRegionEdit(player, p1); }
            case "text_create_region" -> {
                if (p1.isBlank()) { sendRegionList(player); return; }
                var sel = selectionManager.getSelection(player.getUuid());
                if (sel == null || !sel.isComplete()) { player.sendMessage(Text.literal("Aucune selection! Utilisez le wand.").formatted(Formatting.RED), false); sendRegionList(player); }
                else createRegion(player, p1, sel);
            }
            case "text_create_group" -> {
                if (p1.isBlank()) { sendGroupList(player); return; }
                String id = p1.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
                if (storage.hasGroup(id)) player.sendMessage(Text.literal("Groupe existant : " + id).formatted(Formatting.RED), false);
                else { storage.addGroup(new RegionGroup(id, p1)); player.sendMessage(Text.literal("Groupe cree : " + p1).formatted(Formatting.GREEN), false); }
                sendGroupList(player);
            }

            // ── Direct selection ──────────────────────────────────────────────
            case "confirm_parent" -> { Region r = storage.getRegion(p1); if (r != null && !p2.equals(p1) && storage.hasRegion(p2)) { r.setParentId(p2); storage.save(); } sendRegionEdit(player, p1); }
            case "clear_parent"   -> { Region r = storage.getRegion(p1); if (r != null) { r.setParentId(null); storage.save(); } sendRegionEdit(player, p1); }
            case "confirm_group"  -> {
                Region r = storage.getRegion(p1);
                if (r != null) {
                    if (r.getGroupId() != null) { RegionGroup old = storage.getGroup(r.getGroupId()); if (old != null) old.removeRegion(p1); }
                    RegionGroup g = storage.getGroup(p2);
                    if (g != null) { r.setGroupId(p2); g.addRegion(p1); storage.save(); }
                }
                sendRegionEdit(player, p1);
            }
            case "clear_group" -> {
                Region r = storage.getRegion(p1);
                if (r != null) { if (r.getGroupId() != null) { RegionGroup old = storage.getGroup(r.getGroupId()); if (old != null) old.removeRegion(p1); } r.setGroupId(null); storage.save(); }
                sendRegionEdit(player, p1);
            }
            case "toggle_member" -> {
                Region r = storage.getRegion(p1);
                if (r != null) { try {
                    UUID uuid = UUID.fromString(p2);
                    if (r.isMember(uuid)) { r.removeMember(uuid); } else {
                        String n = "?"; var on = server.getPlayerManager().getPlayer(uuid);
                        if (on != null) n = on.getName().getString();
                        else if (server.getUserCache() != null) { var pr = server.getUserCache().getByUuid(uuid); if (pr.isPresent()) n = pr.get().getName(); }
                        r.addMember(new Member(uuid, n));
                    }
                    storage.save();
                } catch (Exception ignored) {} }
                sendMembers(player, p1);
            }
            case "toggle_entry_msg" -> { Region r = storage.getRegion(p1); if (r != null) { r.setEntryMessageEnabled(!r.isEntryMessageEnabled()); storage.save(); } sendRegionEdit(player, p1); }
            case "toggle_exit_msg"  -> { Region r = storage.getRegion(p1); if (r != null) { r.setExitMessageEnabled(!r.isExitMessageEnabled()); storage.save(); } sendRegionEdit(player, p1); }

            // ── Old chat-based (chest GUI fallback) ───────────────────────────
            case "rename_region" -> { Region r = storage.getRegion(p1); if (r == null) return; final String rid = p1; closeGui(player); player.sendMessage(Text.literal("Nom (chat) :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), n -> { r.setName(n); storage.save(); sendRegionEdit(player, rid); }); }
            case "rename_group"  -> { RegionGroup g = storage.getGroup(p1); if (g == null) return; final String gid = p1; closeGui(player); player.sendMessage(Text.literal("Nom (chat) :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), n -> { g.setName(n); storage.save(); sendGroupEdit(player, gid); }); }
            case "set_group" -> { Region r = storage.getRegion(p1); if (r == null) return; final String rid = p1; closeGui(player); player.sendMessage(Text.literal("ID groupe (ou none) :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), input -> { if (r.getGroupId() != null) { RegionGroup old = storage.getGroup(r.getGroupId()); if (old != null) old.removeRegion(rid); } if (!input.equalsIgnoreCase("none")) { String foundId = findGroupId(input); if (foundId != null) { RegionGroup g = storage.getGroup(foundId); if (g != null) { r.setGroupId(foundId); g.addRegion(rid); } } else player.sendMessage(Text.literal("Groupe introuvable : " + input).formatted(Formatting.RED), false); } else r.setGroupId(null); storage.save(); sendRegionEdit(player, rid); }); }
            case "set_parent"    -> { Region r = storage.getRegion(p1); if (r == null) return; final String rid = p1; closeGui(player); player.sendMessage(Text.literal("ID parent (ou none) :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), input -> { if (input.equalsIgnoreCase("none")) r.setParentId(null); else { String foundId = findRegionId(input); if (foundId != null && !foundId.equals(rid)) r.setParentId(foundId); else player.sendMessage(Text.literal("Zone introuvable : " + input).formatted(Formatting.RED), false); } storage.save(); sendRegionEdit(player, rid); }); }
            case "member_add"    -> { final String rid = p1; closeGui(player); player.sendMessage(Text.literal("Nom joueur :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), n -> { Region r = storage.getRegion(rid); if (r == null) return; ServerPlayerEntity t = null; for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) { if (p.getName().getString().equalsIgnoreCase(n)) { t = p; break; } } if (t != null) { r.addMember(new Member(t.getUuid(), t.getName().getString())); storage.save(); player.sendMessage(Text.literal(t.getName().getString() + " ajoute").formatted(Formatting.GREEN), false); } else player.sendMessage(Text.literal("Joueur non trouve : " + n).formatted(Formatting.RED), false); sendMembers(player, rid); }); }
            case "member_remove" -> { Region r = storage.getRegion(p1); if (r != null) { try { r.removeMember(UUID.fromString(p2)); storage.save(); } catch (Exception ignored) {} } sendMembers(player, p1); }
            case "create_region" -> { closeGui(player); player.sendMessage(Text.literal("Nom zone (chat) :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), n -> { var sel = selectionManager.getSelection(player.getUuid()); if (sel == null || !sel.isComplete()) { player.sendMessage(Text.literal("Aucune selection!").formatted(Formatting.RED), false); sendRegionList(player); } else createRegion(player, n, sel); }); }
            case "create_group"  -> { closeGui(player); player.sendMessage(Text.literal("Nom groupe (chat) :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), n -> { String id = n.toLowerCase().replaceAll("[^a-z0-9_-]", "_"); if (!storage.hasGroup(id)) storage.addGroup(new RegionGroup(id, n)); sendGroupList(player); }); }
            case "edit_entry_msg", "edit_exit_msg", "edit_protection_msg" -> { Region r = storage.getRegion(p1); if (r == null) return; final String rid = p1; closeGui(player); player.sendMessage(Text.literal("Message (chat) :").formatted(Formatting.YELLOW), false); awaitChatInput(player.getUuid(), msg -> { if (cmd.equals("edit_entry_msg")) r.setEntryMessage(msg); else if (cmd.equals("edit_exit_msg")) r.setExitMessage(msg); else r.setProtectionMessage(msg); storage.save(); sendRegionEdit(player, rid); }); }

            // ── Chunk management ──────────────────────────────────────────────
            case "chunk_add_here" -> {
                ChunkPos cp = player.getChunkPos();
                int radius = 0;
                try { radius = Integer.parseInt(p2); } catch (Exception ignored) {}
                ChunkSet set = chunkSetStorage.getOrCreateSet(p1);
                set.addChunk(new ChunkEntry(cp.x, cp.z, radius));
                chunkSetStorage.save();
                player.sendMessage(Text.literal("Chunk ajoute au set '" + p1 + "' (radius " + radius + ")").formatted(Formatting.GREEN), false);
                sendChunkSetEdit(player, p1);
            }
            case "chunk_remove" -> {
                // action: chunk_remove:setName:chunkX,chunkZ,radius
                ChunkSet set = chunkSetStorage.getSet(p1);
                if (set != null && !p2.isEmpty()) {
                    String[] coords = p2.split(",");
                    if (coords.length == 3) {
                        try {
                            ChunkEntry entry = new ChunkEntry(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
                            set.removeChunk(entry);
                            if (set.isEmpty()) chunkSetStorage.deleteSet(p1);
                            else chunkSetStorage.save();
                        } catch (Exception ignored) {}
                    }
                }
                if (chunkSetStorage.hasSet(p1)) sendChunkSetEdit(player, p1);
                else sendChunkSets(player);
            }
            case "chunk_tp" -> {
                ChunkSet set = chunkSetStorage.getSet(p1);
                if (set != null && !p2.isEmpty()) {
                    try {
                        int idx = Integer.parseInt(p2);
                        if (idx >= 0 && idx < set.getChunks().size()) {
                            ChunkEntry e = set.getChunks().get(idx);
                            closeGui(player);
                            player.requestTeleport(e.getCenterX() + 0.5, 200, e.getCenterZ() + 0.5);
                            player.sendMessage(Text.literal("TP vers chunk " + e.getRegionFileName()).formatted(Formatting.GREEN), false);
                        }
                    } catch (Exception ignored) {}
                }
            }
            case "chunk_delete_set" -> {
                chunkSetStorage.deleteSet(p1);
                player.sendMessage(Text.literal("Set '" + p1 + "' supprime").formatted(Formatting.RED), false);
                sendChunkSets(player);
            }
            case "text_create_chunk_set" -> {
                if (!p1.isBlank()) {
                    String id = p1.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
                    chunkSetStorage.getOrCreateSet(id);
                    chunkSetStorage.save();
                    player.sendMessage(Text.literal("Set '" + id + "' cree").formatted(Formatting.GREEN), false);
                }
                sendChunkSets(player);
            }
            case "chunk_execute" -> {
                // Show confirmation view
                sendChunkConfirm(player, p1);
            }
            case "chunk_confirm_delete" -> {
                ChunkSet set = chunkSetStorage.getSet(p1);
                if (set != null && !set.isEmpty()) {
                    int chunkCount = set.getChunks().size();
                    player.sendMessage(Text.literal("Suppression des chunks en cours...").formatted(Formatting.YELLOW), false);
                    String levelName = server.getSaveProperties().getLevelName();
                    Path worldPath = server.getRunDirectory().resolve(levelName);
                    ChunkDeleter deleter = new ChunkDeleter(worldPath);
                    ChunkDeleter.DeletionResult result = deleter.deleteChunks(set);
                    player.sendMessage(Text.literal("Suppression terminee ! " + result.deletedFiles.size() + " fichiers supprimes. Redemarrez le serveur.").formatted(Formatting.GREEN), false);

                    // Webhook notification
                    DiscordWebhook.send(
                        "🔄 Régénération de chunks programmée",
                        "Le set **" + p1 + "** (" + chunkCount + " chunk(s)) va être régénéré au prochain redémarrage du serveur.\n\n"
                        + result.deletedFiles.size() + " fichier(s) de région supprimé(s). "
                        + "Les chunks concernés seront recréés automatiquement au chargement.",
                        0xE67E22 // orange
                    );
                }
                closeGui(player);
            }
            case "text_chunk_add_region" -> {
                // p1 = setName, p2 = "regionFile:radius" e.g. "r.-2.-2.mca:3"
                if (!p1.isBlank() && !p2.isBlank()) {
                    String[] rp = p2.split(":");
                    String regionFile = rp[0].trim();
                    int radius = 0;
                    if (rp.length > 1) try { radius = Integer.parseInt(rp[1].trim()); } catch (Exception ignored) {}
                    ChunkEntry entry = ChunkEntry.fromRegionFileName(regionFile, radius, "");
                    if (entry != null) {
                        ChunkSet set = chunkSetStorage.getOrCreateSet(p1);
                        set.addChunk(entry);
                        chunkSetStorage.save();
                        player.sendMessage(Text.literal("Region " + regionFile + " ajoutee (r" + radius + ")").formatted(Formatting.GREEN), false);
                    } else {
                        player.sendMessage(Text.literal("Format invalide. Ex: r.-2.-2.mca:3").formatted(Formatting.RED), false);
                    }
                }
                sendChunkSetEdit(player, p1);
            }
            case "text_chunk_label" -> {
                // p1 = setName, p2 = "chunkX,chunkZ,radius:label"
                ChunkSet set = chunkSetStorage.getSet(p1);
                if (set != null && !p2.isEmpty()) {
                    int colonIdx = p2.indexOf(':');
                    if (colonIdx > 0) {
                        String coords = p2.substring(0, colonIdx);
                        String label = p2.substring(colonIdx + 1);
                        String[] cp = coords.split(",");
                        if (cp.length == 3) {
                            try {
                                int cx = Integer.parseInt(cp[0]), cz = Integer.parseInt(cp[1]), cr = Integer.parseInt(cp[2]);
                                for (ChunkEntry e : set.getChunks()) {
                                    if (e.getChunkX() == cx && e.getChunkZ() == cz && e.getRadius() == cr) {
                                        e.setLabel(label);
                                        chunkSetStorage.save();
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                sendChunkSetEdit(player, p1);
            }

            // ── Advancement management ───────────────────────────────────────────
            case "toggle_adv" -> {
                // Full action: toggle_adv:region:regionId:entry:advId (advId may contain :)
                // Split carefully: first 4 parts are fixed, rest is advId
                int firstColon = action.indexOf(':');
                if (firstColon < 0) break;
                String rest = action.substring(firstColon + 1); // "region:regionId:entry:advId"
                String[] rp = rest.split(":", 4); // [region, regionId, entry/exit, advId_with_colons]
                if (rp.length >= 4) {
                    String targetType = rp[0];
                    String targetId = rp[1];
                    boolean isEntry = rp[2].equals("entry");
                    String advId = rp[3]; // This preserves colons in advId
                    if (targetType.equals("region")) {
                        Region r = storage.getRegion(targetId);
                        if (r != null) {
                            if (isEntry) r.toggleAdvancementEntry(advId);
                            else r.toggleAdvancementExit(advId);
                            storage.save();
                        }
                    } else if (targetType.equals("group")) {
                        RegionGroup g = storage.getGroup(targetId);
                        if (g != null) {
                            if (isEntry) g.toggleAdvancementEntry(advId);
                            else g.toggleAdvancementExit(advId);
                            storage.save();
                        }
                    }
                    AdvViewState st = advViewStates.get(player.getUuid());
                    String tgt = targetType + ":" + targetId;
                    String tp2 = isEntry ? "entry" : "exit";
                    sendAdvancementSelect(player, tgt, tp2, st != null ? st.filter : "", st != null ? st.page : 0);
                }
            }
            case "text_adv_denied_msg" -> {
                Region r = storage.getRegion(p1);
                if (r != null && !p2.isBlank()) { r.setAdvancementDeniedMessage(p2); storage.save(); }
                sendRegionEdit(player, p1);
            }

            // ── FTB Quest select (zones/groups) ────────────────────────────────
            case "open_quest_select" -> {
                // open_quest_select:region:id:entry or open_quest_select:group:id:exit
                String[] qp = action.split(":", 4);
                if (qp.length >= 4) {
                    QuestViewState st = new QuestViewState();
                    st.target = qp[1]+":"+qp[2]; st.type = qp[3]; st.filter = ""; st.page = 0;
                    questViewStates.put(player.getUuid(), st);
                    sendQuestSelect(player, st.target, st.type, st.filter, st.page);
                }
            }
            case "quest_search" -> {
                QuestViewState st = questViewStates.get(player.getUuid());
                if (st != null) { st.filter = p1; st.page = 0; sendQuestSelect(player, st.target, st.type, st.filter, st.page); }
            }
            case "quest_page" -> {
                QuestViewState st = questViewStates.get(player.getUuid());
                if (st != null) { try { st.page = Integer.parseInt(p1); } catch (Exception ignored) {} sendQuestSelect(player, st.target, st.type, st.filter, st.page); }
            }
            case "toggle_quest" -> {
                // toggle_quest:region:regionId:entry:questId
                int fc = action.indexOf(':');
                String rest2 = action.substring(fc + 1);
                String[] rq = rest2.split(":", 4);
                if (rq.length >= 4) {
                    String targetType = rq[0]; String targetId = rq[1];
                    boolean isEntry = rq[2].equals("entry"); String questId = rq[3];
                    if (targetType.equals("region")) {
                        Region r = storage.getRegion(targetId);
                        if (r != null) { if (isEntry) r.toggleQuestEntry(questId); else r.toggleQuestExit(questId); storage.save(); }
                    } else if (targetType.equals("group")) {
                        RegionGroup g = storage.getGroup(targetId);
                        if (g != null) { if (isEntry) g.toggleQuestEntry(questId); else g.toggleQuestExit(questId); storage.save(); }
                    }
                    QuestViewState st = questViewStates.get(player.getUuid());
                    sendQuestSelect(player, targetType+":"+targetId, isEntry?"entry":"exit", st!=null?st.filter:"", st!=null?st.page:0);
                }
            }

            // ── Command triggers ─────────────────────────────────────────────
            case "open_cmd_triggers" -> {
                // open_cmd_triggers:regionId:enter|exit
                String[] cp = action.split(":", 3);
                if (cp.length >= 3) sendCmdTriggers(player, cp[1], cp[2]);
            }
            case "open_region_edit" -> sendRegionEdit(player, p1);
            case "cmd_trigger_add" -> {
                // cmd_trigger_add:regionId:mode:command
                String[] cp = action.split(":", 4);
                if (cp.length >= 4) {
                    Region r = storage.getRegion(cp[1]);
                    if (r != null) {
                        String triggerCmd = cp[3].trim();
                        if (!triggerCmd.isEmpty()) {
                            if (cp[2].equals("enter")) r.addCommandOnEnter(triggerCmd);
                            else r.addCommandOnExit(triggerCmd);
                            storage.save();
                        }
                        sendCmdTriggers(player, cp[1], cp[2]);
                    }
                }
            }
            case "cmd_trigger_del" -> {
                // cmd_trigger_del:regionId:mode:index
                String[] cp = action.split(":", 4);
                if (cp.length >= 4) {
                    Region r = storage.getRegion(cp[1]);
                    if (r != null) {
                        try {
                            int idx = Integer.parseInt(cp[3]);
                            if (cp[2].equals("enter")) r.removeCommandOnEnter(idx);
                            else r.removeCommandOnExit(idx);
                            storage.save();
                        } catch (Exception ignored) {}
                        sendCmdTriggers(player, cp[1], cp[2]);
                    }
                }
            }

            // ── Player management ────────────────────────────────────────────
            case "player_search" -> { playerListFilter.put(player.getUuid(), p1); sendPlayerList(player, p1); }
            case "open_player" -> {
                try { sendPlayerDetail(player, UUID.fromString(p1)); } catch (Exception ignored) {}
            }
            case "player_tp_to" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) { executeAsConsole("tp " + player.getName().getString() + " " + target.getName().getString()); player.sendMessage(Text.literal("TP vers " + target.getName().getString()).formatted(Formatting.GREEN), false); }
                else player.sendMessage(Text.literal("Joueur hors ligne").formatted(Formatting.RED), false);
            }
            case "player_tp_here" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) { executeAsConsole("tp " + target.getName().getString() + " " + player.getName().getString()); player.sendMessage(Text.literal(target.getName().getString() + " teleporte ici").formatted(Formatting.GREEN), false); }
                else player.sendMessage(Text.literal("Joueur hors ligne").formatted(Formatting.RED), false);
            }
            case "player_tp_all" -> {
                executeAsConsole("tp @a " + player.getName().getString());
                int count = server.getPlayerManager().getPlayerList().size() - 1;
                player.sendMessage(Text.literal(count + " joueur(s) teleportes ici").formatted(Formatting.GREEN), false);
            }
            case "player_kick" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) { target.networkHandler.disconnect(Text.literal("Kicked par " + player.getName().getString())); player.sendMessage(Text.literal(target.getName().getString() + " kick").formatted(Formatting.YELLOW), false); }
                sendPlayerList(player, playerListFilter.getOrDefault(player.getUuid(), ""));
            }
            case "player_heal" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) { target.setHealth(target.getMaxHealth()); target.getHungerManager().setFoodLevel(20); player.sendMessage(Text.literal(target.getName().getString() + " heal").formatted(Formatting.GREEN), false); }
                try { sendPlayerDetail(player, UUID.fromString(p1)); } catch (Exception ignored) {}
            }
            case "player_feed" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) { target.getHungerManager().setFoodLevel(20); player.sendMessage(Text.literal(target.getName().getString() + " feed").formatted(Formatting.GREEN), false); }
                try { sendPlayerDetail(player, UUID.fromString(p1)); } catch (Exception ignored) {}
            }
            case "player_gamemode" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) {
                    GameMode current = target.interactionManager.getGameMode();
                    GameMode next = current == GameMode.SURVIVAL ? GameMode.CREATIVE : current == GameMode.CREATIVE ? GameMode.ADVENTURE : current == GameMode.ADVENTURE ? GameMode.SPECTATOR : GameMode.SURVIVAL;
                    target.changeGameMode(next);
                    player.sendMessage(Text.literal(target.getName().getString() + " -> " + next.getName()).formatted(Formatting.GREEN), false);
                }
                try { sendPlayerDetail(player, UUID.fromString(p1)); } catch (Exception ignored) {}
            }
            case "player_kill" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) { target.kill(); player.sendMessage(Text.literal(target.getName().getString() + " kill").formatted(Formatting.RED), false); }
                try { sendPlayerDetail(player, UUID.fromString(p1)); } catch (Exception ignored) {}
            }
            case "player_clear_inv" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) { target.getInventory().clear(); player.sendMessage(Text.literal(target.getName().getString() + " inventaire vide").formatted(Formatting.YELLOW), false); }
                try { sendPlayerDetail(player, UUID.fromString(p1)); } catch (Exception ignored) {}
            }
            case "player_fly" -> {
                ServerPlayerEntity target = getOnlinePlayer(p1);
                if (target != null) {
                    boolean newFly = !target.getAbilities().allowFlying;
                    target.getAbilities().allowFlying = newFly;
                    if (!newFly) target.getAbilities().flying = false;
                    target.sendAbilitiesUpdate();
                    player.sendMessage(Text.literal(target.getName().getString() + " fly " + (newFly ? "ON" : "OFF")).formatted(Formatting.GREEN), false);
                }
                try { sendPlayerDetail(player, UUID.fromString(p1)); } catch (Exception ignored) {}
            }
            case "open_player_adv" -> {
                try {
                    UUID targetUuid = UUID.fromString(p1);
                    PlayerAdvState st = new PlayerAdvState();
                    st.targetUuid = targetUuid; st.filter = ""; st.page = 0;
                    playerAdvStates.put(player.getUuid(), st);
                    sendPlayerAdvancements(player, targetUuid, "", 0);
                } catch (Exception ignored) {}
            }
            case "player_adv_search" -> {
                PlayerAdvState st = playerAdvStates.get(player.getUuid());
                if (st != null) { st.filter = p1; st.page = 0; sendPlayerAdvancements(player, st.targetUuid, st.filter, st.page); }
            }
            case "player_adv_page" -> {
                PlayerAdvState st = playerAdvStates.get(player.getUuid());
                if (st != null) { try { st.page = Integer.parseInt(p1); } catch (Exception ignored) {} sendPlayerAdvancements(player, st.targetUuid, st.filter, st.page); }
            }
            case "player_adv_toggle" -> {
                // player_adv_toggle:uuid:advId (advId may contain :)
                int firstColon = action.indexOf(':');
                String afterCmd = action.substring(firstColon + 1); // uuid:advId
                int sepIdx = afterCmd.indexOf(':');
                if (sepIdx > 0) {
                    String uuid = afterCmd.substring(0, sepIdx);
                    String advId = afterCmd.substring(sepIdx + 1);
                    try {
                        ServerPlayerEntity target = server.getPlayerManager().getPlayer(UUID.fromString(uuid));
                        if (target != null) {
                            Identifier id = Identifier.tryParse(advId);
                            if (id != null) {
                                AdvancementEntry adv = server.getAdvancementLoader().get(id);
                                if (adv != null) {
                                    PlayerAdvancementTracker tracker = target.getAdvancementTracker();
                                    if (tracker.getProgress(adv).isDone()) {
                                        // Revoke
                                        for (String criterion : adv.value().criteria().keySet()) tracker.revokeCriterion(adv, criterion);
                                    } else {
                                        // Grant
                                        for (String criterion : adv.value().criteria().keySet()) tracker.grantCriterion(adv, criterion);
                                    }
                                }
                            }
                        }
                        PlayerAdvState st = playerAdvStates.get(player.getUuid());
                        sendPlayerAdvancements(player, UUID.fromString(uuid), st != null ? st.filter : "", st != null ? st.page : 0);
                    } catch (Exception ignored) {}
                }
            }
            case "open_player_quests" -> {
                try {
                    UUID targetUuid = UUID.fromString(p1);
                    PlayerQuestState st = new PlayerQuestState();
                    st.targetUuid = targetUuid; st.filter = ""; st.page = 0;
                    playerQuestStates.put(player.getUuid(), st);
                    sendPlayerQuests(player, targetUuid, "", 0);
                } catch (Exception ignored) {}
            }
            case "player_quest_search" -> {
                PlayerQuestState st = playerQuestStates.get(player.getUuid());
                if (st != null) { st.filter = p1; st.page = 0; sendPlayerQuests(player, st.targetUuid, st.filter, st.page); }
            }
            case "player_quest_page" -> {
                PlayerQuestState st = playerQuestStates.get(player.getUuid());
                if (st != null) { try { st.page = Integer.parseInt(p1); } catch (Exception ignored) {} sendPlayerQuests(player, st.targetUuid, st.filter, st.page); }
            }
            case "player_quest_complete" -> {
                executeAsConsole("ftbquests change_progress " + getPlayerName(p1) + " complete " + p2);
                player.sendMessage(Text.literal("Quest complete: " + p2).formatted(Formatting.GREEN), false);
                PlayerQuestState st = playerQuestStates.get(player.getUuid());
                if (st != null) sendPlayerQuests(player, st.targetUuid, st.filter, st.page);
            }
            case "player_quest_reset" -> {
                executeAsConsole("ftbquests change_progress " + getPlayerName(p1) + " reset " + p2);
                player.sendMessage(Text.literal("Quest reset: " + p2).formatted(Formatting.YELLOW), false);
                PlayerQuestState st = playerQuestStates.get(player.getUuid());
                if (st != null) sendPlayerQuests(player, st.targetUuid, st.filter, st.page);
            }
            case "player_quest_reset_all" -> {
                executeAsConsole("ftbquests change_progress " + getPlayerName(p1) + " reset-all");
                player.sendMessage(Text.literal("Toutes les quetes reset pour " + getPlayerName(p1)).formatted(Formatting.RED), false);
                PlayerQuestState st = playerQuestStates.get(player.getUuid());
                if (st != null) sendPlayerQuests(player, st.targetUuid, st.filter, st.page);
            }

            // ── Server management ─────────────────────────────────────────────
            case "server_action" -> {
                // p1 = mode (restart/stop/maintenance), p2 = minutes
                ServerManager.Mode mode = ServerManager.Mode.fromId(p1);
                int mins = 5;
                try { mins = Integer.parseInt(p2); } catch (Exception ignored) {}
                serverManager.schedule(server, mode, mins);
                player.sendMessage(Text.literal(mode.label + " planifie dans " + mins + " minute(s)").formatted(Formatting.GREEN), false);
                sendServerAdmin(player);
            }
            case "server_cancel" -> {
                serverManager.cancel();
                player.sendMessage(Text.literal("Action serveur annulee").formatted(Formatting.YELLOW), false);
                sendServerAdmin(player);
            }
            case "text_server_custom" -> {
                // p1 = mode, p2 = minutes string
                ServerManager.Mode mode = ServerManager.Mode.fromId(p1);
                int mins = 5;
                try { mins = Integer.parseInt(p2); } catch (Exception ignored) {}
                serverManager.schedule(server, mode, mins);
                player.sendMessage(Text.literal(mode.label + " planifie dans " + mins + " minute(s)").formatted(Formatting.GREEN), false);
                sendServerAdmin(player);
            }
            case "set_webhook" -> {
                // URL contains ":" so we can't use p1/p2 - extract from full action
                String webhookUrl = action.substring(action.indexOf(':') + 1);
                DiscordWebhook.setUrl(webhookUrl);
                saveWebhookConfig();
                player.sendMessage(Text.literal("Webhook " + (webhookUrl.isBlank() ? "supprime" : "configure !")).formatted(Formatting.GREEN), false);
                if (!webhookUrl.isBlank()) DiscordWebhook.send("✅ Webhook connecté", "CheeseGuard Discord webhook est configuré !", 0x2ECC71);
                sendServerAdmin(player);
            }
            case "remove_webhook" -> {
                DiscordWebhook.setUrl("");
                saveWebhookConfig();
                player.sendMessage(Text.literal("Webhook supprime").formatted(Formatting.YELLOW), false);
                sendServerAdmin(player);
            }
            case "test_webhook" -> {
                if (DiscordWebhook.isConfigured()) {
                    DiscordWebhook.send("🔔 Test", "Ceci est un message de test depuis CheeseGuard !", 0x3498DB);
                    player.sendMessage(Text.literal("Webhook test envoye !").formatted(Formatting.GREEN), false);
                } else {
                    player.sendMessage(Text.literal("Aucun webhook configure").formatted(Formatting.RED), false);
                }
            }

            // ── Events system ────────────────────────────────────────────────
            case "open_events", "open_event_list" -> sendEventList(player);
            case "create_event" -> {
                ServerEvent evt = eventManager.createEvent("Nouvel evenement");
                sendEventEditorData(player, evt.getId());
            }
            case "open_event" -> sendEventEditorData(player, p1);
            // ── Event Editor (full screen) ──────────────────────────────────
            case "editor_open" -> sendEventEditorData(player, p1);
            case "editor_save" -> saveEventEditorData(player, p1);
            case "editor_reload" -> sendEventEditorData(player, p1);
            case "editor_field" -> {
                String fullP = p1+(p2.isBlank()?"":":"+p2);
                handleEditorField(player, fullP);
                sendEventEditorData(player, extractEventIdFromField(fullP));
            }
            case "event_start_editor" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !evt.isRunning()) eventManager.announceEvent(evt);
                sendEventEditorData(player, p1);
            }
            case "event_stop_editor" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && evt.isRunning()) eventManager.endEvent(evt);
                sendEventEditorData(player, p1);
            }
            case "vote_cast" -> {
                // Format: vote_cast:voteId:choice (p1=voteId, p2=choice)
                String fullChoice = p2.isBlank() ? "" : p2;
                eventManager.getVoteManager().castVote(p1, player.getUuid(), fullChoice,
                    java.util.List.of(server.getPlayerManager().getPlayerList().toArray(new net.minecraft.server.network.ServerPlayerEntity[0])));
            }
            case "advance_act" -> {
                // Format: advance_act:eventId
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && evt.isRunning()) eventManager.getTimelineExecutor().advanceAct(evt);
                sendEventEditorData(player, p1);
            }
            case "open_event_rewards" -> {
                // Rewards editor - placeholder until Phase 4
                player.sendMessage(net.minecraft.text.Text.literal("[CG] Éditeur de récompenses à venir (Phase 4)").formatted(net.minecraft.util.Formatting.YELLOW), false);
                sendEventEditorData(player, p1);
            }
            case "act_create" -> { handleActCreate(player, p1); sendEventEditorData(player, p1); }
            case "act_delete" -> {
                // Format: eventId:actId (p1=eventId, p2=actId after split(3))
                String actId2 = p2.split(":")[0];
                if (!actId2.isBlank()) {
                    handleActDelete(player, p1+":"+actId2);
                    sendEventEditorData(player, p1);
                }
            }
            case "act_rename" -> {
                // Format: eventId:actId:newName (p1=eventId, p2=actId:name)
                String[] rParts2 = p2.split(":", 2);
                if (rParts2.length == 2) {
                    ServerEvent evtR = eventManager.getEvent(p1);
                    if (evtR != null) {
                        com.cheeseguard.data.EventAct actR = evtR.getAct(rParts2[0]);
                        if (actR != null) { actR.setName(rParts2[1]); eventManager.save(); }
                    }
                    sendEventEditorData(player, p1);
                }
            }
            case "tl_block_field" -> {
                // Format: actId:blockId:fieldName:value (p1=actId, p2=blockId:field:value after split(3))
                String[] fp = p2.split(":", 3);
                if (fp.length == 3) {
                    handleTlBlockField(player, p1, fp[0], fp[1], fp[2]);
                    sendEventEditorData(player, extractActEventId(p1));
                }
            }
            case "tl_block_add" -> { String fullP = p1+(p2.isBlank()?"":":"+p2); handleTlBlockAdd(player, fullP); sendEventEditorData(player, extractActEventId(p1)); }
            case "tl_block_delete" -> { String fullP = p1+(p2.isBlank()?"":":"+p2); handleTlBlockDelete(player, fullP); sendEventEditorData(player, extractActEventId(p1)); }
            case "tl_block_move" -> { String fullP = p1+(p2.isBlank()?"":":"+p2); handleTlBlockMove(player, fullP); sendEventEditorData(player, extractActEventId(p1)); }
            case "tl_block_resize" -> { String fullP = p1+(p2.isBlank()?"":":"+p2); handleTlBlockResize(player, fullP); sendEventEditorData(player, extractActEventId(p1)); }
            case "tl_block_duplicate" -> { String fullP = p1+(p2.isBlank()?"":":"+p2); handleTlBlockDuplicate(player, fullP); sendEventEditorData(player, extractActEventId(p1)); }
            case "tl_block_prop_set" -> { String fullP = p1+(p2.isBlank()?"":":"+p2); handleTlBlockPropSet(player, fullP); sendEventEditorData(player, extractActEventId(p1)); }
            case "delete_event" -> {
                eventManager.deleteEvent(p1);
                player.sendMessage(Text.literal("Evenement supprime").formatted(Formatting.RED), false);
                sendEventList(player);
            }
            case "start_event" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !evt.isRunning()) { eventManager.announceEvent(evt); }
                sendEventList(player);
            }
            case "start_event_direct" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !evt.isRunning()) { eventManager.startEventDirect(evt); }
                sendEventList(player);
            }
            case "stop_event" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && evt.isRunning()) { eventManager.endEvent(evt); }
                sendEventList(player);
            }
            case "duplicate_event" -> {
                ServerEvent copy = eventManager.duplicateEvent(p1);
                if (copy != null) sendEventEditorData(player, copy.getId());
                else sendEventList(player);
            }
            case "text_event_name" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !p2.isBlank()) { evt.setName(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "text_event_desc" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setDescription(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "cycle_event_mode" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setMode(evt.getMode() == ServerEvent.EventMode.SERVER_EVENT ? ServerEvent.EventMode.MINIGAME : ServerEvent.EventMode.SERVER_EVENT);
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_event_color" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    String[] colors = EventManager.COLOR_OPTIONS;
                    int idx = 0;
                    for (int i = 0; i < colors.length; i++) { if (colors[i].equals(evt.getDisplayColor())) { idx = i; break; } }
                    evt.setDisplayColor(colors[(idx + 1) % colors.length]);
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_event_trigger" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    var types = ServerEvent.TriggerType.values();
                    int idx = (evt.getTrigger().ordinal() + 1) % types.length;
                    evt.setTrigger(types[idx]);
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "toggle_event_discord" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setDiscordNotify(!evt.isDiscordNotify()); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "toggle_event_bossbar" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setBossbarEnabled(!evt.isBossbarEnabled()); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "cycle_bossbar_color" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    String[] bb = {"pink","blue","red","green","yellow","purple","white"};
                    int idx = 0; for (int i = 0; i < bb.length; i++) if (bb[i].equals(evt.getBossbarColor())) { idx = i; break; }
                    evt.setBossbarColor(bb[(idx + 1) % bb.length]); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_team_mode" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    var modes = ServerEvent.TeamMode.values();
                    evt.setTeamMode(modes[(evt.getTeamMode().ordinal() + 1) % modes.length]); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_death_behavior" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    var modes = ServerEvent.DeathBehavior.values();
                    evt.setDeathBehavior(modes[(evt.getDeathBehavior().ordinal() + 1) % modes.length]); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "toggle_lightning" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setLightningMobsOnEnd(!evt.isLightningMobsOnEnd()); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_victory" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    var modes = ServerEvent.VictoryCondition.values();
                    evt.setVictoryCondition(modes[(evt.getVictoryCondition().ordinal() + 1) % modes.length]); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_return_mode" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setReturnMode(switch (evt.getReturnMode()) { case "saved" -> "spawn"; case "spawn" -> "coords"; default -> "saved"; });
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "toggle_save_inv" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setSaveInventory(!evt.isSaveInventory()); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "toggle_save_cobblemon" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setSaveTeamCobblemon(!evt.isSaveTeamCobblemon()); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "toggle_empty_inv" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setForceEmptyInventory(!evt.isForceEmptyInventory()); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "toggle_scoreboard" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setScoreboardVisible(!evt.isScoreboardVisible()); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "toggle_end_all_dead" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setEndOnAllDead(!evt.isEndOnAllDead()); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "evt_set_lightning_here" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setLightningX(player.getX()); evt.setLightningY(player.getY()); evt.setLightningZ(player.getZ());
                    evt.setLightningWorld(player.getServerWorld().getRegistryKey().getValue().toString());
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "evt_clear_lightning_pos" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.setLightningWorld(""); eventManager.save(); }
                sendEventEditorData(player, p1);
            }

            // ── Event numeric fields (via eff_set-style extended parse) ──────
            case "evt_set" -> {
                String[] ep = action.split(":", 4);
                if (ep.length >= 4) {
                    ServerEvent evt = eventManager.getEvent(ep[1]);
                    if (evt != null) {
                        String prop = ep[2], val = ep[3];
                        switch (prop) {
                            case "announceDuration" -> { try { evt.setAnnounceDurationSeconds(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "countdown" -> { try { evt.setCountdownSeconds(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "cooldown" -> { try { evt.setCooldownMinutes(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "minParticipants" -> { try { evt.setMinParticipants(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "maxParticipants" -> { try { evt.setMaxParticipants(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "teamSize" -> { try { evt.setTeamSize(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "victoryTarget" -> { try { evt.setVictoryTargetScore(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "recurringInterval" -> { try { evt.setRecurringIntervalMinutes(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "announceMsg" -> evt.setAnnounceMessage(val);
                            case "startMsg" -> evt.setStartMessage(val);
                            case "endMsg" -> evt.setEndMessage(val);
                            case "announceColor" -> evt.setAnnounceColor(val);
                            case "startColor" -> evt.setStartColor(val);
                            case "endColor" -> evt.setEndColor(val);
                            case "exclusionGroup" -> evt.setExclusionGroup(val);
                            case "exclusionPriority" -> { try { evt.setExclusionPriority(Integer.parseInt(val)); } catch (Exception ignored) {} }
                            case "chainEvent" -> evt.setChainEventId(val);
                            case "tpPX" -> { try { evt.setTpParticipantX(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "tpPY" -> { try { evt.setTpParticipantY(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "tpPZ" -> { try { evt.setTpParticipantZ(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "tpPW" -> evt.setTpParticipantWorld(val);
                            case "tpSX" -> { try { evt.setTpSpectatorX(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "tpSY" -> { try { evt.setTpSpectatorY(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "tpSZ" -> { try { evt.setTpSpectatorZ(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "tpSW" -> evt.setTpSpectatorWorld(val);
                            case "retX" -> { try { evt.setReturnX(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "retY" -> { try { evt.setReturnY(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "retZ" -> { try { evt.setReturnZ(Double.parseDouble(val)); } catch (Exception ignored) {} }
                            case "retW" -> evt.setReturnWorld(val);
                            case "lightningRadius" -> { try { evt.setLightningRadius(Integer.parseInt(val)); } catch (Exception ignored) {} }
                        }
                        eventManager.save();
                    }
                    sendEventEditorData(player, ep[1]);
                }
            }

            // ── Event scope zones ────────────────────────────────────────────
            case "evt_add_scope_zone" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !p2.isBlank() && !evt.getScopeZoneIds().contains(p2)) {
                    evt.getScopeZoneIds().add(p2); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "evt_remove_scope_zone" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.getScopeZoneIds().remove(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }

            // ── Event TP from current position ───────────────────────────────
            case "evt_set_tp_participant_here" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setTpParticipantX(player.getX()); evt.setTpParticipantY(player.getY()); evt.setTpParticipantZ(player.getZ());
                    evt.setTpParticipantWorld(player.getServerWorld().getRegistryKey().getValue().toString());
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "evt_set_tp_spectator_here" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setTpSpectatorX(player.getX()); evt.setTpSpectatorY(player.getY()); evt.setTpSpectatorZ(player.getZ());
                    evt.setTpSpectatorWorld(player.getServerWorld().getRegistryKey().getValue().toString());
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "evt_set_respawn_here" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setRespawnX(player.getX()); evt.setRespawnY(player.getY()); evt.setRespawnZ(player.getZ());
                    evt.setRespawnWorld(player.getServerWorld().getRegistryKey().getValue().toString());
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "evt_clear_respawn" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setRespawnWorld(""); // empty = not set → falls back to TP Joueurs
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "evt_set_return_here" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setReturnX(player.getX()); evt.setReturnY(player.getY()); evt.setReturnZ(player.getZ());
                    evt.setReturnWorld(player.getServerWorld().getRegistryKey().getValue().toString());
                    evt.setReturnMode("coords"); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "evt_set_ret_here" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.setReturnX(player.getX()); evt.setReturnY(player.getY()); evt.setReturnZ(player.getZ());
                    evt.setReturnWorld(player.getServerWorld().getRegistryKey().getValue().toString());
                    evt.setReturnMode("coords"); eventManager.save();
                }
                sendEventEditorData(player, p1);
            }

            // ── Kit commands ─────────────────────────────────────────────────
            case "evt_add_kit_entry" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !p2.isBlank()) { evt.getKitEntryCommands().add(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "evt_edit_kit_entry" -> {
                // evt_edit_kit_entry:eventId:index:newValue
                String[] rs = action.split(":", 4);
                if (rs.length >= 4) {
                    ServerEvent evt = eventManager.getEvent(rs[1]);
                    if (evt != null) {
                        try {
                            int i = Integer.parseInt(rs[2]);
                            if (i >= 0 && i < evt.getKitEntryCommands().size()) {
                                evt.getKitEntryCommands().set(i, rs[3]);
                                eventManager.save();
                            }
                        } catch (Exception ignored) {}
                    }
                    sendEventEditorData(player, rs[1]);
                }
            }
            case "evt_remove_kit_entry" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { try { int i = Integer.parseInt(p2); if (i >= 0 && i < evt.getKitEntryCommands().size()) evt.getKitEntryCommands().remove(i); eventManager.save(); } catch (Exception ignored) {} }
                sendEventEditorData(player, p1);
            }
            case "evt_add_kit_exit" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !p2.isBlank()) { evt.getKitExitCommands().add(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "evt_edit_kit_exit" -> {
                // evt_edit_kit_exit:eventId:index:newValue
                String[] rs = action.split(":", 4);
                if (rs.length >= 4) {
                    ServerEvent evt = eventManager.getEvent(rs[1]);
                    if (evt != null) {
                        try {
                            int i = Integer.parseInt(rs[2]);
                            if (i >= 0 && i < evt.getKitExitCommands().size()) {
                                evt.getKitExitCommands().set(i, rs[3]);
                                eventManager.save();
                            }
                        } catch (Exception ignored) {}
                    }
                    sendEventEditorData(player, rs[1]);
                }
            }
            case "evt_remove_kit_exit" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { try { int i = Integer.parseInt(p2); if (i >= 0 && i < evt.getKitExitCommands().size()) evt.getKitExitCommands().remove(i); eventManager.save(); } catch (Exception ignored) {} }
                sendEventEditorData(player, p1);
            }

            // ── Blacklist/whitelist ──────────────────────────────────────────
            case "evt_add_blacklist_item" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !p2.isBlank()) { evt.getBlacklistItems().add(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "evt_remove_blacklist_item" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    try { int i = Integer.parseInt(p2); if (i>=0&&i<evt.getBlacklistItems().size()) evt.getBlacklistItems().remove(i); eventManager.save(); } catch (Exception ignored) {}
                }
                sendEventEditorData(player, p1);
            }
            case "evt_edit_blacklist_item" -> {
                // Format: eventId:index:newValue (p1=eventId, p2=index:newValue)
                String[] ep = p2.split(":", 2);
                if (ep.length == 2) {
                    ServerEvent evt = eventManager.getEvent(p1);
                    if (evt != null) {
                        try { int i = Integer.parseInt(ep[0]); if (i>=0&&i<evt.getBlacklistItems().size()) evt.getBlacklistItems().set(i, ep[1]); eventManager.save(); } catch (Exception ignored) {}
                    }
                }
                sendEventEditorData(player, p1);
            }
            case "evt_add_blacklist_pokemon" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && !p2.isBlank()) { evt.getBlacklistPokemon().add(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }
            case "evt_remove_blacklist_pokemon" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) { evt.getBlacklistPokemon().remove(p2); eventManager.save(); }
                sendEventEditorData(player, p1);
            }

            // ── Phases ───────────────────────────────────────────────────────
            case "open_phases" -> sendEventPhases(player, p1);
            case "add_phase" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventPhase ph = new EventPhase();
                    ph.setName("Phase " + (evt.getPhases().size() + 1));
                    evt.addPhase(ph); eventManager.save();
                }
                sendEventPhases(player, p1);
            }
            case "delete_phase" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null && evt.getPhases().size() > 1) { evt.removePhase(p2); eventManager.save(); }
                else if (evt != null) player.sendMessage(Text.literal("Il faut au moins 1 phase").formatted(Formatting.RED), false);
                sendEventPhases(player, p1);
            }
            case "duplicate_phase" -> {
                // duplicate_phase:eventId:phaseId
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    com.cheeseguard.data.EventPhase src = evt.getPhase(p2);
                    if (src != null) {
                        // Deep copy via JSON, then reassign fresh ids
                        com.google.gson.JsonObject copy = src.toJson();
                        copy.addProperty("id", java.util.UUID.randomUUID().toString().substring(0, 8));
                        copy.addProperty("name", src.getName() + " (copie)");
                        // Fresh ids for each effect so they're independent
                        com.google.gson.JsonArray effs = copy.has("effects") ? copy.getAsJsonArray("effects") : new com.google.gson.JsonArray();
                        for (com.google.gson.JsonElement el : effs) {
                            el.getAsJsonObject().addProperty("id", java.util.UUID.randomUUID().toString().substring(0, 8));
                        }
                        com.cheeseguard.data.EventPhase dup = com.cheeseguard.data.EventPhase.fromJson(copy);
                        // Insert right after the source phase
                        int idx = evt.getPhases().indexOf(src);
                        evt.getPhases().add(idx + 1, dup);
                        eventManager.save();
                        player.sendMessage(Text.literal("✅ Phase dupliquee: " + dup.getName()).formatted(Formatting.GREEN), false);
                    }
                }
                sendEventPhases(player, p1);
            }
            case "open_phase" -> sendPhaseEdit(player, p1, p2);
            case "phase_set" -> {
                String[] pp = action.split(":", 5);
                if (pp.length >= 5) {
                    ServerEvent evt = eventManager.getEvent(pp[1]);
                    if (evt != null) {
                        EventPhase ph = evt.getPhase(pp[2]);
                        if (ph != null) {
                            String prop = pp[3], val = pp[4];
                            switch (prop) {
                                case "name" -> ph.setName(val);
                                case "duration" -> { try { ph.setDurationSeconds(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "title" -> ph.setTitleMessage(val);
                                case "subtitle" -> ph.setSubtitleMessage(val);
                                case "sound" -> ph.setStartSound(val);
                            }
                            eventManager.save();
                        }
                    }
                    sendPhaseEdit(player, pp[1], pp[2]);
                }
            }
            case "cycle_phase_color" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventPhase ph = evt.getPhase(p2);
                    if (ph != null) {
                        String[] colors = EventManager.COLOR_OPTIONS;
                        int idx = 0; for (int i = 0; i < colors.length; i++) if (colors[i].equals(ph.getTitleColor())) { idx = i; break; }
                        ph.setTitleColor(colors[(idx + 1) % colors.length]); eventManager.save();
                    }
                }
                sendPhaseEdit(player, p1, p2);
            }
            case "move_phase_up" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    List<EventPhase> phases = evt.getPhases();
                    for (int i = 1; i < phases.size(); i++) {
                        if (phases.get(i).getId().equals(p2)) {
                            Collections.swap(phases, i, i - 1); eventManager.save(); break;
                        }
                    }
                }
                sendEventPhases(player, p1);
            }
            case "move_phase_down" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    List<EventPhase> phases = evt.getPhases();
                    for (int i = 0; i < phases.size() - 1; i++) {
                        if (phases.get(i).getId().equals(p2)) {
                            Collections.swap(phases, i, i + 1); eventManager.save(); break;
                        }
                    }
                }
                sendEventPhases(player, p1);
            }

            // ── Phase effects (effects live on phases) ───────────────────────
            case "open_phase_effects" -> sendPhaseEffects(player, p1, p2);
            case "add_phase_effect" -> {
                // add_phase_effect:eventId:phaseId  -> type in p2 via extended parse
                String[] pe = action.split(":", 4);
                if (pe.length >= 4) {
                    ServerEvent evt = eventManager.getEvent(pe[1]);
                    if (evt != null) {
                        EventPhase ph = evt.getPhase(pe[2]);
                        if (ph != null) {
                            try { ph.addEffect(new EventEffect(EventEffect.Type.valueOf(pe[3]))); eventManager.save(); } catch (Exception ignored) {}
                        }
                    }
                    sendPhaseEffects(player, pe[1], pe[2]);
                }
            }
            case "delete_phase_effect" -> {
                // delete_phase_effect:eventId:phaseId -> effectId in p2 via extended
                String[] pe = action.split(":", 4);
                if (pe.length >= 4) {
                    ServerEvent evt = eventManager.getEvent(pe[1]);
                    if (evt != null) {
                        EventPhase ph = evt.getPhase(pe[2]);
                        if (ph != null) { ph.removeEffect(pe[3]); eventManager.save(); }
                    }
                    sendPhaseEffects(player, pe[1], pe[2]);
                }
            }
            case "open_effect_edit" -> {
                // open_effect_edit:eventId:effectId (finds phase automatically)
                sendEffectEdit(player, p1, p2);
            }

            // ── Effect property edits ────────────────────────────────────────
            case "eff_set" -> {
                String[] ep = action.split(":", 5);
                if (ep.length >= 5) {
                    ServerEvent evt = eventManager.getEvent(ep[1]);
                    if (evt != null) {
                        EventEffect eff = evt.findEffect(ep[2]);
                        if (eff != null) {
                            String prop = ep[3], val = ep[4];
                            switch (prop) {
                                case "potionId" -> eff.setPotionId(val);
                                case "amplifier" -> { try { eff.setAmplifier(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "command" -> eff.setCommand(val);
                                case "radius" -> { try { eff.setRadiusBlocks(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "interval" -> { try { eff.setIntervalSeconds(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "chance" -> { try { eff.setChancePercent(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "message" -> eff.setMessage(val);
                                case "color" -> eff.setMessageColor(val);
                                case "multType" -> eff.setMultiplierType(val);
                                case "multValue" -> { try { eff.setMultiplierValue(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "soundId" -> eff.setSoundId(val);
                                case "volume" -> { try { eff.setSoundVolume(Float.parseFloat(val)); } catch (Exception ignored) {} }
                                case "pitch" -> { try { eff.setSoundPitch(Float.parseFloat(val)); } catch (Exception ignored) {} }
                                case "titleText" -> eff.setTitleText(val);
                                case "subtitleText" -> eff.setSubtitleText(val);
                                case "fadeIn" -> { try { eff.setFadeIn(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "stay" -> { try { eff.setStay(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "fadeOut" -> { try { eff.setFadeOut(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "particleId" -> eff.setParticleId(val);
                                case "particleCount" -> { try { eff.setParticleCount(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "particleSpread" -> { try { eff.setParticleSpread(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "itemId" -> eff.setItemId(val);
                                case "itemCount" -> { try { eff.setItemCount(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "weatherType" -> eff.setWeatherType(val);
                                case "timeValue" -> { try { eff.setTimeValue(Integer.parseInt(val)); } catch (Exception ignored) {} }
                                case "freezeTime" -> eff.setFreezeTime(Boolean.parseBoolean(val));
                                case "flagId" -> eff.setFlagId(val);
                                case "targetZone" -> eff.setTargetZoneId(val);
                                case "flagValue" -> eff.setFlagValue(Boolean.parseBoolean(val));
                                case "entityCommand" -> eff.setEntityCommand(val);
                                case "spawnX" -> { try { eff.setSpawnX(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "spawnY" -> { try { eff.setSpawnY(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "spawnZ" -> { try { eff.setSpawnZ(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "relativePos" -> eff.setUseRelativePos(Boolean.parseBoolean(val));
                                case "zoneCenterX" -> { try { eff.setZoneCenterX(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "zoneCenterZ" -> { try { eff.setZoneCenterZ(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "zoneRadius" -> { try { eff.setZoneRadius(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "zoneDamage" -> { try { eff.setZoneDamage(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "showBorder" -> eff.setZoneShowBorder(Boolean.parseBoolean(val));
                                case "orbitEnabled" -> eff.setZoneOrbitEnabled(Boolean.parseBoolean(val));
                                case "orbitRadius" -> { try { eff.setZoneOrbitRadius(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "orbitSpeed" -> { try { eff.setZoneOrbitSpeed(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "orbitAngle" -> { try { eff.setZoneOrbitAngleOffset(Double.parseDouble(val)); } catch (Exception ignored) {} }
                                case "scopeZone" -> eff.setScopeZoneId(val);
                                case "dayNight" -> eff.setDayNightCondition(val);
                            }
                            eventManager.save();
                        }
                    }
                    sendEffectEdit(player, ep[1], ep[2]);
                }
            }
            case "cycle_potion" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventEffect eff = evt.findEffect(p2);
                    if (eff != null) {
                        String[] opts = EventManager.POTION_OPTIONS;
                        int idx = 0; for (int i = 0; i < opts.length; i++) if (opts[i].equals(eff.getPotionId())) { idx = i; break; }
                        eff.setPotionId(opts[(idx + 1) % opts.length]); eventManager.save();
                    }
                }
                sendEffectEdit(player, p1, p2);
            }
            case "cycle_mult_type" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventEffect eff = evt.findEffect(p2);
                    if (eff != null) {
                        String[] opts = EventManager.MULTIPLIER_OPTIONS;
                        int idx = 0; for (int i = 0; i < opts.length; i++) if (opts[i].equals(eff.getMultiplierType())) { idx = i; break; }
                        eff.setMultiplierType(opts[(idx + 1) % opts.length]); eventManager.save();
                    }
                }
                sendEffectEdit(player, p1, p2);
            }
            case "cycle_eff_color" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventEffect eff = evt.findEffect(p2);
                    if (eff != null) {
                        String[] opts = EventManager.COLOR_OPTIONS;
                        int idx = 0; for (int i = 0; i < opts.length; i++) if (opts[i].equals(eff.getMessageColor())) { idx = i; break; }
                        eff.setMessageColor(opts[(idx + 1) % opts.length]); eventManager.save();
                    }
                }
                sendEffectEdit(player, p1, p2);
            }
            case "cycle_weather_type" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventEffect eff = evt.findEffect(p2);
                    if (eff != null) {
                        String[] opts = EventManager.WEATHER_OPTIONS;
                        int idx = 0; for (int i = 0; i < opts.length; i++) if (opts[i].equals(eff.getWeatherType())) { idx = i; break; }
                        eff.setWeatherType(opts[(idx + 1) % opts.length]); eventManager.save();
                    }
                }
                sendEffectEdit(player, p1, p2);
            }
            case "cycle_daynight" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventEffect eff = evt.findEffect(p2);
                    if (eff != null) {
                        String c = eff.getDayNightCondition();
                        eff.setDayNightCondition(c.isBlank() ? "day" : c.equals("day") ? "night" : "");
                        eventManager.save();
                    }
                }
                sendEffectEdit(player, p1, p2);
            }
            case "zone_shrink_setpos" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    EventEffect eff = evt.findEffect(p2);
                    if (eff != null) {
                        eff.setZoneCenterX(Math.floor(player.getX()));
                        eff.setZoneCenterZ(Math.floor(player.getZ()));
                        eventManager.save();
                        player.sendMessage(Text.literal("Centre defini: " + (int)player.getX() + ", " + (int)player.getZ()).formatted(Formatting.GREEN), false);
                    }
                }
                sendEffectEdit(player, p1, p2);
            }

            // ── Score triggers ───────────────────────────────────────────────
            case "open_scoring" -> sendEventScoring(player, p1);
            case "add_score_trigger" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.getScoreTriggers().add(new ServerEvent.ScoreTrigger(ServerEvent.ScoreTrigger.TriggerType.CUSTOM_COMMAND, "", 1));
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "delete_score_trigger" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    try { int i = Integer.parseInt(p2); if (i >= 0 && i < evt.getScoreTriggers().size()) evt.getScoreTriggers().remove(i); eventManager.save(); } catch (Exception ignored) {}
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_trigger_type" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    try {
                        int i = Integer.parseInt(p2);
                        if (i >= 0 && i < evt.getScoreTriggers().size()) {
                            var t = evt.getScoreTriggers().get(i);
                            var types = ServerEvent.ScoreTrigger.TriggerType.values();
                            t.setType(types[(t.getType().ordinal() + 1) % types.length]);
                            eventManager.save();
                        }
                    } catch (Exception ignored) {}
                }
                sendEventScoring(player, p1);
            }
            case "trigger_set" -> {
                String[] ts = action.split(":", 5);
                if (ts.length >= 5) {
                    ServerEvent evt = eventManager.getEvent(ts[1]);
                    if (evt != null) {
                        try {
                            int i = Integer.parseInt(ts[2]);
                            if (i >= 0 && i < evt.getScoreTriggers().size()) {
                                var t = evt.getScoreTriggers().get(i);
                                switch (ts[3]) {
                                    case "filter" -> t.setFilter(ts[4]);
                                    case "points" -> { try { t.setPoints(Integer.parseInt(ts[4])); } catch (Exception ignored) {} }
                                }
                                eventManager.save();
                            }
                        } catch (Exception ignored) {}
                    }
                    sendEventScoring(player, ts[1]);
                }
            }

            // ── Rewards ──────────────────────────────────────────────────────
            case "open_rewards" -> sendEventRewards(player, p1);
            case "add_reward" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    evt.getRewards().add(new ServerEvent.EventReward("all"));
                    eventManager.save();
                }
                sendEventRewards(player, p1);
            }
            case "delete_reward" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    try { int i = Integer.parseInt(p2); if (i >= 0 && i < evt.getRewards().size()) evt.getRewards().remove(i); eventManager.save(); } catch (Exception ignored) {}
                }
                sendEventRewards(player, p1);
            }
            case "cycle_reward_rank" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    try {
                        int i = Integer.parseInt(p2);
                        if (i >= 0 && i < evt.getRewards().size()) {
                            String[] ranks = {"1", "2", "3", "top10", "all", "lottery"};
                            var r = evt.getRewards().get(i);
                            int idx = 0; for (int j = 0; j < ranks.length; j++) if (ranks[j].equals(r.getRank())) { idx = j; break; }
                            r.setRank(ranks[(idx + 1) % ranks.length]);
                            eventManager.save();
                        }
                    } catch (Exception ignored) {}
                }
                sendEventRewards(player, p1);
            }
            case "reward_add_cmd" -> {
                String[] rs = action.split(":", 4);
                if (rs.length >= 4) {
                    ServerEvent evt = eventManager.getEvent(rs[1]);
                    if (evt != null) {
                        try { int i = Integer.parseInt(rs[2]); if (i >= 0 && i < evt.getRewards().size()) { evt.getRewards().get(i).getCommands().add(rs[3]); eventManager.save(); } } catch (Exception ignored) {}
                    }
                    sendRewardCmds(player, rs[1], rs[2]);
                }
            }
            case "reward_edit_cmd" -> {
                // reward_edit_cmd:eventId:rewardIdx:cmdIdx:newValue
                String[] rs = action.split(":", 5);
                if (rs.length >= 5) {
                    ServerEvent evt = eventManager.getEvent(rs[1]);
                    if (evt != null) {
                        try {
                            int ri = Integer.parseInt(rs[2]), ci = Integer.parseInt(rs[3]);
                            if (ri >= 0 && ri < evt.getRewards().size()) {
                                var cmds = evt.getRewards().get(ri).getCommands();
                                if (ci >= 0 && ci < cmds.size()) {
                                    cmds.set(ci, rs[4]);
                                    eventManager.save();
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    sendRewardCmds(player, rs[1], rs[2]);
                }
            }
            case "reward_del_cmd" -> {
                String[] rs = action.split(":", 4);
                if (rs.length >= 4) {
                    ServerEvent evt = eventManager.getEvent(rs[1]);
                    if (evt != null) {
                        try {
                            int ri = Integer.parseInt(rs[2]), ci = Integer.parseInt(rs[3]);
                            if (ri >= 0 && ri < evt.getRewards().size()) {
                                var cmds = evt.getRewards().get(ri).getCommands();
                                if (ci >= 0 && ci < cmds.size()) cmds.remove(ci);
                                eventManager.save();
                            }
                        } catch (Exception ignored) {}
                    }
                    sendRewardCmds(player, rs[1], rs[2]);
                }
            }
            case "open_reward_cmds" -> {
                // open_reward_cmds:eventId:rewardIdx
                sendRewardCmds(player, p1, p2);
            }

            case "cycle_lunar_event" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    List<String> lunar = eventManager.getAvailableLunarEvents();
                    if (!lunar.isEmpty()) {
                        int idx = lunar.indexOf(evt.getLunarEventId());
                        evt.setLunarEventId(lunar.get((idx + 1) % lunar.size()));
                        eventManager.save();
                    }
                }
                sendEventEditorData(player, p1);
            }
            case "force_lunar" -> {
                eventManager.forceLunarEvent(p1);
                player.sendMessage(Text.literal("Lunar event force: " + p1).formatted(Formatting.GREEN), false);
            }

            // ── Mini-game type ───────────────────────────────────────────
            case "cycle_minigame_type" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    var types = com.cheeseguard.logic.MiniGameManager.MiniGameType.values();
                    String current = evt.getMiniGameType();
                    int idx = -1;
                    for (int i = 0; i < types.length; i++) { if (types[i].name().equals(current)) { idx = i; break; } }
                    idx = (idx + 1) % types.length;
                    evt.setMiniGameType(types[idx].name());
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }
            case "cycle_fishing_mode" -> {
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    String[] modes = {"", "ARCADE", "REALISTIC"};
                    String current = evt.getFishingMode();
                    int idx = 0;
                    for (int i = 0; i < modes.length; i++) { if (modes[i].equals(current)) { idx = i; break; } }
                    idx = (idx + 1) % modes.length;
                    evt.setFishingMode(modes[idx]);
                    eventManager.save();
                }
                sendEventEditorData(player, p1);
            }

            // ── Templates ────────────────────────────────────────────────
            case "open_templates" -> sendTemplateList(player);
            case "template_save" -> {
                // action = template_save:eventId:templateName
                ServerEvent evt = eventManager.getEvent(p1);
                if (evt != null) {
                    String tplName = p2.isBlank() ? evt.getName() : p2;
                    boolean ok = com.cheeseguard.logic.EventTemplate.save(server, evt, tplName);
                    player.sendMessage(Text.literal(ok ? "✅ Template sauvegarde: " + tplName : "❌ Erreur sauvegarde template")
                        .formatted(ok ? Formatting.GREEN : Formatting.RED), false);
                }
                sendTemplateList(player);
            }
            case "template_load" -> {
                ServerEvent loaded = com.cheeseguard.logic.EventTemplate.load(server, p1);
                if (loaded != null) {
                    eventManager.getEvents().add(loaded);
                    eventManager.save();
                    player.sendMessage(Text.literal("✅ Evenement cree depuis template: " + loaded.getName()).formatted(Formatting.GREEN), false);
                    sendEventList(player);
                } else {
                    player.sendMessage(Text.literal("❌ Template introuvable: " + p1).formatted(Formatting.RED), false);
                }
            }
            case "template_delete" -> {
                com.cheeseguard.logic.EventTemplate.delete(server, p1);
                sendTemplateList(player);
            }

            // ── Queue ────────────────────────────────────────────────────
            case "queue_join" -> {
                boolean ok = eventManager.getMiniGameManager().joinQueue(p1, player.getUuid());
                player.sendMessage(Text.literal(ok ? "✅ File d'attente rejoint" : "Deja dans la file").formatted(ok ? Formatting.GREEN : Formatting.YELLOW), false);
            }
            case "queue_leave" -> {
                eventManager.getMiniGameManager().leaveQueue(p1, player.getUuid());
                player.sendMessage(Text.literal("Quitte la file d'attente").formatted(Formatting.YELLOW), false);
            }

            // ── Snapshots ────────────────────────────────────────────────
            case "snapshot_save" -> {
                // action = snapshot_save:snapshotId:regionId
                var snap = new com.cheeseguard.logic.ZoneSnapshot(p1, p2);
                int count = snap.capture(server);
                if (count > 0) {
                    snap.saveToDisk(server);
                    player.sendMessage(Text.literal("✅ Snapshot '" + p1 + "': " + count + " blocs sauvegardes").formatted(Formatting.GREEN), false);
                } else {
                    player.sendMessage(Text.literal("❌ Aucun bloc trouve dans la zone " + p2).formatted(Formatting.RED), false);
                }
            }
            case "snapshot_restore" -> {
                var snap = com.cheeseguard.logic.ZoneSnapshot.loadFromDisk(server, p1);
                if (snap != null) {
                    int count = snap.restore(server);
                    player.sendMessage(Text.literal("✅ Snapshot '" + p1 + "': " + count + " blocs restaures").formatted(Formatting.GREEN), false);
                } else {
                    player.sendMessage(Text.literal("❌ Snapshot introuvable: " + p1).formatted(Formatting.RED), false);
                }
            }

            // ── Score command ────────────────────────────────────────────
            case "evt_add_score" -> {
                // action = evt_add_score:eventId:playerName:points
                String[] sp = action.split(":", 4);
                if (sp.length >= 4) {
                    ServerPlayerEntity target = server.getPlayerManager().getPlayer(sp[2]);
                    if (target != null) {
                        try {
                            int pts = Integer.parseInt(sp[3]);
                            eventManager.addScoreCommand(sp[1], target.getUuid(), pts);
                            player.sendMessage(Text.literal("+" + pts + " pts pour " + sp[2]).formatted(Formatting.GREEN), false);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // ── Dimensions system ─────────────────────────────────────────────
            case "open_dimensions" -> sendDimensionList(player);
            case "open_dimension" -> {
                // ConfigId may contain colon (e.g. minecraft:overworld for built-in dims)
                String configId = p2.isBlank() ? p1 : p1 + ":" + p2;
                sendDimensionEdit(player, configId);
            }
            case "text_create_dimension" -> {
                if (!p1.isBlank()) {
                    // Don't create world yet — store draft and let player configure first
                    String id = p1.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    if (id.isBlank()) id = "dim_" + (System.currentTimeMillis() % 10000);
                    var draft = new com.cheeseguard.data.DimensionConfig(id, p1);
                    draft.setBuiltIn(false);
                    draft.setGenType(com.cheeseguard.data.DimensionConfig.GenType.VOID);
                    draft.setSpawnY(65);
                    dimDrafts.put(player.getUuid(), draft);
                    sendDimensionDraft(player, draft);
                }
            }
            case "dim_draft_cycle_gen" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) {
                    var types = com.cheeseguard.data.DimensionConfig.GenType.values();
                    int cur = draft.getGenType().ordinal();
                    draft.setGenType(types[(cur + 1) % types.length]);
                    // Update sensible default spawn Y
                    switch (draft.getGenType()) {
                        case VOID   -> draft.setSpawnY(65);
                        case FLAT   -> draft.setSpawnY(5);
                        case NORMAL -> draft.setSpawnY(80);
                        case NETHER, END -> draft.setSpawnY(64);
                    }
                    sendDimensionDraft(player, draft);
                } else sendDimensionList(player);
            }
            case "dim_draft_set_name" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null && !p1.isBlank()) {
                    String newId = p1.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    if (newId.isBlank()) newId = "dim_" + (System.currentTimeMillis() % 10000);
                    draft.setId(newId);
                    draft.setDisplayName(p1);
                    sendDimensionDraft(player, draft);
                } else sendDimensionList(player);
            }
            case "dim_draft_set_seed" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { try { draft.setSeed(Long.parseLong(p1)); } catch (Exception ignored) {} sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_set_layers" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setFlatLayers(p1); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_set_spawn" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) {
                    String[] pp = action.split(":", 3); // dim_draft_set_spawn:X Y Z
                    if (pp.length >= 2) {
                        String[] xyz = pp[1].split(" ");
                        try {
                            if (xyz.length >= 1) draft.setSpawnX(Double.parseDouble(xyz[0]));
                            if (xyz.length >= 2) draft.setSpawnY(Double.parseDouble(xyz[1]));
                            if (xyz.length >= 3) draft.setSpawnZ(Double.parseDouble(xyz[2]));
                        } catch (NumberFormatException ignored) {}
                    }
                    sendDimensionDraft(player, draft);
                } else sendDimensionList(player);
            }
            case "dim_draft_toggle_time" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setTimeLocked(!draft.isTimeLocked()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_time_dec" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setLockedTime(Math.max(0, draft.getLockedTime() - 1000)); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_time_inc" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setLockedTime((draft.getLockedTime() + 1000) % 24000); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_toggle_weather" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setWeatherLocked(!draft.isWeatherLocked()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_cycle_weather" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) {
                    draft.setLockedWeather(switch(draft.getLockedWeather()) {
                        case "clear" -> "rain"; case "rain" -> "thunder"; default -> "clear";
                    });
                    sendDimensionDraft(player, draft);
                } else sendDimensionList(player);
            }
            case "dim_draft_toggle_pvp" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setPvp(!draft.isPvp()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_toggle_mobs" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setMobSpawning(!draft.isMobSpawning()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_toggle_keepinv" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setKeepInventory(!draft.isKeepInventory()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_cycle_difficulty" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) {
                    draft.setDifficulty(switch(draft.getDifficulty()) {
                        case "default" -> "peaceful"; case "peaceful" -> "easy";
                        case "easy" -> "normal"; case "normal" -> "hard"; default -> "default";
                    });
                    sendDimensionDraft(player, draft);
                } else sendDimensionList(player);
            }
            case "dim_draft_toggle_structures" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setGenerateStructures(!draft.isGenerateStructures()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_toggle_cmd_blocks" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setCommandBlocks(!draft.isCommandBlocks()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_toggle_ender_chest" -> {
                var draft = dimDrafts.get(player.getUuid());
                if (draft != null) { draft.setEnderChest(!draft.isEnderChest()); sendDimensionDraft(player, draft); }
                else sendDimensionList(player);
            }
            case "dim_draft_confirm" -> {
                var draft = dimDrafts.remove(player.getUuid());
                if (draft != null) {
                    var config = dimensionManager.createDimension(draft.getId(), draft.getDisplayName(), draft.getGenType());
                    if (config != null) {
                        // Apply all draft settings to the newly created config
                        config.setSeed(draft.getSeed());
                        config.setFlatLayers(draft.getFlatLayers());
                        config.setSpawnX(draft.getSpawnX()); config.setSpawnY(draft.getSpawnY()); config.setSpawnZ(draft.getSpawnZ());
                        config.setTimeLocked(draft.isTimeLocked()); config.setLockedTime(draft.getLockedTime());
                        config.setWeatherLocked(draft.isWeatherLocked()); config.setLockedWeather(draft.getLockedWeather());
                        config.setPvp(draft.isPvp()); config.setMobSpawning(draft.isMobSpawning());
                        config.setKeepInventory(draft.isKeepInventory()); config.setDifficulty(draft.getDifficulty());
                        config.setGenerateStructures(draft.isGenerateStructures());
                        config.setCommandBlocks(draft.isCommandBlocks()); config.setEnderChest(draft.isEnderChest());
                        dimensionManager.save();
                        player.sendMessage(Text.literal("✅ Dimension " + draft.getDisplayName() + " creee !").formatted(Formatting.GREEN), false);
                        sendDimensionEdit(player, config.getId());
                    } else {
                        player.sendMessage(Text.literal("❌ Echec creation — ID \"" + draft.getId() + "\" deja existant ?").formatted(Formatting.RED), false);
                        sendDimensionList(player);
                    }
                } else sendDimensionList(player);
            }
            case "dim_draft_cancel" -> {
                dimDrafts.remove(player.getUuid());
                sendDimensionList(player);
            }
            case "dim_tp" -> {
                // WorldId contains colon (e.g. minecraft:overworld) so reconstruct from p1:p2
                String worldId = p2.isBlank() ? p1 : p1 + ":" + p2;
                // Check enabled (ops bypass)
                var dimCfg = dimensionManager.getConfigByWorldId(worldId);
                if (dimCfg != null && !dimCfg.canPlayerEnter(player.getUuid().toString()) && !player.hasPermissionLevel(2)) {
                    player.sendMessage(Text.literal("\u26D4 " + dimCfg.getEnabledMessage()).formatted(Formatting.RED), false);
                } else {
                    boolean ok = dimensionManager.tpToDimension(player, worldId);
                    if (ok) player.sendMessage(Text.literal("\u27A4 Teleporte vers " + worldId).formatted(Formatting.GREEN), false);
                    else player.sendMessage(Text.literal("\u274C Dimension introuvable: " + worldId).formatted(Formatting.RED), false);
                }
            }
            case "dim_cycle_gen" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null && !cfg.isBuiltIn()) {
                    var types = com.cheeseguard.data.DimensionConfig.GenType.values();
                    cfg.setGenType(types[(cfg.getGenType().ordinal() + 1) % types.length]);
                    dimensionManager.save();
                    player.sendMessage(Text.literal("\u26A0 Le type de generation changera au prochain redemarrage ou recre-la.").formatted(Formatting.YELLOW), false);
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_set_name" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null && !da[1].isBlank()) { cfg.setDisplayName(da[1]); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_set_seed" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { try { cfg.setSeed(Long.parseLong(da[1].trim())); } catch (Exception ignored) { cfg.setSeed(0); } dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_set_layers" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setFlatLayers(da[1]); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_set_spawn" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null && !da[1].isBlank()) {
                    String[] coords = da[1].trim().split("\\s+");
                    if (coords.length >= 3) {
                        try {
                            cfg.setSpawnX(Double.parseDouble(coords[0]));
                            cfg.setSpawnY(Double.parseDouble(coords[1]));
                            cfg.setSpawnZ(Double.parseDouble(coords[2]));
                            dimensionManager.save();
                        } catch (Exception ignored) {}
                    }
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_set_spawn_here" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    cfg.setSpawnX(player.getX()); cfg.setSpawnY(player.getY()); cfg.setSpawnZ(player.getZ());
                    dimensionManager.save();
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_time" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    cfg.setTimeLocked(!cfg.isTimeLocked());
                    dimensionManager.save();
                    dimensionManager.applyTimeLock(cfg); // immediate effect
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_time_dec" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    cfg.setLockedTime((cfg.getLockedTime() - 1000 + 24000) % 24000);
                    dimensionManager.save();
                    dimensionManager.applyTimeLock(cfg);
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_time_inc" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    cfg.setLockedTime((cfg.getLockedTime() + 1000) % 24000);
                    dimensionManager.save();
                    dimensionManager.applyTimeLock(cfg);
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_weather" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setWeatherLocked(!cfg.isWeatherLocked()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_cycle_weather" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    String next = switch (cfg.getLockedWeather()) { case "clear" -> "rain"; case "rain" -> "thunder"; default -> "clear"; };
                    cfg.setLockedWeather(next); dimensionManager.save();
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_pvp" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setPvp(!cfg.isPvp()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_mobs" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setMobSpawning(!cfg.isMobSpawning()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_keepinv" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setKeepInventory(!cfg.isKeepInventory()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_cycle_difficulty" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    String next = switch (cfg.getDifficulty()) {
                        case "default"  -> "peaceful";
                        case "peaceful" -> "easy";
                        case "easy"     -> "normal";
                        case "normal"   -> "hard";
                        default         -> "default";
                    };
                    cfg.setDifficulty(next);
                    dimensionManager.save();
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_command_blocks" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setCommandBlocks(!cfg.isCommandBlocks()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_ender_chest" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setEnderChest(!cfg.isEnderChest()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_structures" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setGenerateStructures(!cfg.isGenerateStructures()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_cobblemon" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    boolean nowBlocked = !cfg.isCobblemonBlocked();
                    cfg.setCobblemonBlocked(nowBlocked);
                    dimensionManager.save();
                    // If we just blocked cobblemon, immediately purge all existing ones
                    if (nowBlocked) {
                        String wId = cfg.getWorldId();
                        dimensionManager.purgeCobblemonFromWorld(wId);
                    }
                }
                sendDimensionEdit(player, da[0]);
            }
            // ── Snapshot / Reset ────────────────────────────────────────────
            case "dim_snapshot_create" -> {
                var da = resolveDimAction(action);
                boolean ok = dimensionManager.createSnapshot(da[0]);
                player.sendMessage(net.minecraft.text.Text.literal(
                    ok ? "✅ Snapshot créé pour '" + da[0] + "'"
                       : "❌ Échec de la création du snapshot")
                    .formatted(ok ? net.minecraft.util.Formatting.GREEN : net.minecraft.util.Formatting.RED), false);
                sendDimensionEdit(player, da[0]);
            }
            case "dim_snapshot_restore" -> {
                var da = resolveDimAction(action);
                String result = dimensionManager.restoreSnapshot(da[0]);
                switch (result) {
                    case "ok"          -> player.sendMessage(net.minecraft.text.Text.literal("✅ Dimension réinitialisée depuis le snapshot.").formatted(net.minecraft.util.Formatting.GREEN), false);
                    case "pending"     -> player.sendMessage(net.minecraft.text.Text.literal("⏳ Joueurs expulsés. Reset en attente (dimension vide dans quelques secondes)...").formatted(net.minecraft.util.Formatting.YELLOW), false);
                    case "no_snapshot" -> player.sendMessage(net.minecraft.text.Text.literal("❌ Aucun snapshot trouvé. Créez-en un d'abord.").formatted(net.minecraft.util.Formatting.RED), false);
                    default            -> player.sendMessage(net.minecraft.text.Text.literal("❌ Erreur lors du reset.").formatted(net.minecraft.util.Formatting.RED), false);
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_snapshot_cycle_trigger" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    var triggers = com.cheeseguard.data.DimensionConfig.ResetTrigger.values();
                    int next = (cfg.getSnapshotResetTrigger().ordinal() + 1) % triggers.length;
                    cfg.setSnapshotResetTrigger(triggers[next]);
                    dimensionManager.save();
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_snapshot_set_hour" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) {
                    try {
                        int h = Integer.parseInt(da[1]);
                        cfg.setSnapshotResetHour(h);
                        dimensionManager.save();
                    } catch (NumberFormatException ignored) {}
                }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_toggle_enabled" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setEnabled(!cfg.isEnabled()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "dim_set_enabled_msg" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null && !da[1].isBlank()) { cfg.setEnabledMessage(da[1]); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "open_dim_whitelist" -> {
                var da = resolveDimAction(action);
                sendDimWhitelist(player, da[0]);
            }
            case "dim_toggle_wl" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null && !da[1].isBlank()) {
                    if (cfg.isWhitelisted(da[1])) cfg.removeWhitelist(da[1]);
                    else cfg.addWhitelist(da[1]);
                    dimensionManager.save();
                }
                sendDimWhitelist(player, da[0]);
            }
            case "dim_toggle_construction" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null) { cfg.setConstructionMode(!cfg.isConstructionMode()); dimensionManager.save(); }
                sendDimensionEdit(player, da[0]);
            }
            case "open_dim_builders" -> {
                var da = resolveDimAction(action);
                sendDimBuilders(player, da[0]);
            }
            case "dim_toggle_builder" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null && !da[1].isBlank()) {
                    if (cfg.isConstructionBuilder(da[1])) cfg.removeConstructionBuilder(da[1]);
                    else cfg.addConstructionBuilder(da[1]);
                    dimensionManager.save();
                }
                sendDimBuilders(player, da[0]);
            }
            case "dim_delete" -> {
                var da = resolveDimAction(action);
                var cfg = dimensionManager.getConfig(da[0]);
                if (cfg != null && !cfg.isBuiltIn()) {
                    String worldId = cfg.getWorldId();
                    if (!worldId.equals("minecraft:overworld") && !worldId.equals("minecraft:the_nether")) {
                        String name = cfg.getDisplayName();
                        dimensionManager.deleteDimension(da[0]);
                        player.sendMessage(Text.literal("\u2714 Dimension " + name + " supprimee.").formatted(Formatting.RED), false);
                    }
                }
                sendDimensionList(player);
            }

            // ── Shortcuts ─────────────────────────────────────────────────────
            case "open_shortcuts" -> sendShortcuts(player);
            case "exec_shortcut" -> {
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx >= 0 && idx < shortcuts.size()) {
                        ShortcutData sc = shortcuts.get(idx);
                        String shortcutCmd = sc.command.trim();
                        if (!shortcutCmd.isEmpty()) {
                            if (sc.closeGui) closeGui(player);
                            executeAsPlayer(shortcutCmd, player);
                            player.sendMessage(Text.literal("\u26A1 " + sc.label).formatted(Formatting.GREEN), true);
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
            case "text_shortcut_create" -> {
                // p1 = label
                if (!p1.isBlank()) {
                    shortcuts.add(new ShortcutData(p1.trim(), "", "blue"));
                    saveShortcuts();
                }
                sendShortcuts(player);
            }
            case "text_shortcut_label" -> {
                // text_shortcut_label:idx:newLabel
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx >= 0 && idx < shortcuts.size()) {
                        shortcuts.get(idx).label = p2.trim().isEmpty() ? "?" : p2.trim();
                        saveShortcuts();
                    }
                } catch (NumberFormatException ignored) {}
                sendShortcuts(player);
            }
            case "text_shortcut_cmd" -> {
                // text_shortcut_cmd:idx:command (p2 may be split)
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx >= 0 && idx < shortcuts.size()) {
                        shortcuts.get(idx).command = p2.trim();
                        saveShortcuts();
                    }
                } catch (NumberFormatException ignored) {}
                sendShortcuts(player);
            }
            case "shortcut_delete" -> {
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx >= 0 && idx < shortcuts.size()) {
                        shortcuts.remove(idx);
                        saveShortcuts();
                    }
                } catch (NumberFormatException ignored) {}
                sendShortcuts(player);
            }
            case "shortcut_toggle_close" -> {
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx >= 0 && idx < shortcuts.size()) {
                        shortcuts.get(idx).closeGui = !shortcuts.get(idx).closeGui;
                        saveShortcuts();
                    }
                } catch (NumberFormatException ignored) {}
                sendShortcuts(player);
            }
            case "shortcut_color" -> {
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx >= 0 && idx < shortcuts.size()) {
                        String[] colors = {"blue","green","orange","red","purple","aqua","gold"};
                        String cur = shortcuts.get(idx).color;
                        int ci = 0;
                        for (int i = 0; i < colors.length; i++) if (colors[i].equals(cur)) { ci = i; break; }
                        shortcuts.get(idx).color = colors[(ci + 1) % colors.length];
                        saveShortcuts();
                    }
                } catch (NumberFormatException ignored) {}
                sendShortcuts(player);
            }
            case "shortcut_move_up" -> {
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx > 0 && idx < shortcuts.size()) {
                        ShortcutData tmp = shortcuts.get(idx - 1);
                        shortcuts.set(idx - 1, shortcuts.get(idx));
                        shortcuts.set(idx, tmp);
                        saveShortcuts();
                    }
                } catch (NumberFormatException ignored) {}
                sendShortcuts(player);
            }
            case "shortcut_move_down" -> {
                try {
                    int idx = Integer.parseInt(p1);
                    if (idx >= 0 && idx < shortcuts.size() - 1) {
                        ShortcutData tmp = shortcuts.get(idx + 1);
                        shortcuts.set(idx + 1, shortcuts.get(idx));
                        shortcuts.set(idx, tmp);
                        saveShortcuts();
                    }
                } catch (NumberFormatException ignored) {}
                sendShortcuts(player);
            }

            default -> LOGGER.warn("Unknown GUI action: {}", cmd);
        }
        } catch (Exception e) {
            LOGGER.error("Error handling GUI action '{}': {}", action, e.getMessage(), e);
            player.sendMessage(Text.literal("Erreur: " + e.getMessage()).formatted(Formatting.RED), false);
        }
    }

    /**
     * Resolves dimension configId from a GUI action string.
     * Handles both "dim_cmd:customId:value" and "dim_cmd:namespace:path:value" formats.
     * Returns [configId, value].
     */
    private String[] resolveDimAction(String action) {
        String[] parts = action.split(":", 4);
        if (parts.length <= 1) return new String[]{"", ""};
        // Try p1 as configId first (custom dims: "arena_pvp")
        if (dimensionManager.getConfig(parts[1]) != null) {
            String val = parts.length > 2 ? parts[2] : "";
            return new String[]{parts[1], val};
        }
        // Try p1:p2 as configId (built-in dims: "minecraft:overworld")
        if (parts.length >= 3) {
            String configId = parts[1] + ":" + parts[2];
            String val = parts.length > 3 ? parts[3] : "";
            return new String[]{configId, val};
        }
        return new String[]{parts[1], ""};
    }

    // ── Sync builders ────────────────────────────────────────────────────────

    public void sendMain(ServerPlayerEntity player) {
        JsonObject o = new JsonObject();
        o.addProperty("regionCount", storage.getRegions().size());
        o.addProperty("groupCount",  storage.getGroups().size());
        o.addProperty("visualizing", visualizationManager.isVisualizing(player.getUuid()));
        o.addProperty("testMode", regionManager.isTestMode(player.getUuid()));
        o.addProperty("playerCount", server.getPlayerManager().getPlayerList().size());
        o.addProperty("eventCount", eventManager.getEvents().size());
        int activeEvents = (int) eventManager.getEvents().stream().filter(e -> e.isRunning()).count();
        o.addProperty("activeEventCount", activeEvents);
        // Active event details for live dashboard
        JsonArray activeArr = new JsonArray();
        for (var evt : eventManager.getEvents()) {
            if (!evt.isRunning()) continue;
            JsonObject eo = new JsonObject();
            eo.addProperty("id",   evt.getId());
            eo.addProperty("name", evt.getName());
            eo.addProperty("participants", evt.getParticipants().size());
            // Current act info
            var acts = evt.getActs();
            int actIdx = eventManager.getTimelineExecutor().getCurrentActIndex(evt.getId());
            if (acts != null && !acts.isEmpty() && actIdx >= 0 && actIdx < acts.size()) {
                var act = acts.get(actIdx);
                eo.addProperty("actName",  act.getName());
                eo.addProperty("actIndex", actIdx);
                eo.addProperty("actCount", acts.size());
            } else {
                eo.addProperty("actName",  "");
                eo.addProperty("actIndex", 0);
                eo.addProperty("actCount", 0);
            }
            activeArr.add(eo);
        }
        o.add("activeEvents", activeArr);
        // Shortcuts list
        JsonArray scArr = new JsonArray();
        for (ShortcutData sc : shortcuts) {
            JsonObject so = new JsonObject();
            so.addProperty("label",    sc.label);
            so.addProperty("command",  sc.command);
            so.addProperty("color",    sc.color);
            so.addProperty("closeGui", sc.closeGui);
            scArr.add(so);
        }
        o.add("shortcuts", scArr);
        sync(player, "MAIN", o);
    }

    private void sendShortcuts(ServerPlayerEntity player) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < shortcuts.size(); i++) {
            ShortcutData sc = shortcuts.get(i);
            JsonObject so = new JsonObject();
            so.addProperty("index",    i);
            so.addProperty("label",    sc.label);
            so.addProperty("command",  sc.command);
            so.addProperty("color",    sc.color);
            so.addProperty("closeGui", sc.closeGui);
            arr.add(so);
        }
        o.add("shortcuts", arr);
        sync(player, "SHORTCUTS", o);
    }

    private void sendRegionList(ServerPlayerEntity player) {
        JsonObject o = new JsonObject(); JsonArray arr = new JsonArray();
        for (Region r : storage.getRegions()) {
            JsonObject ro = new JsonObject();
            ro.addProperty("id", r.getId()); ro.addProperty("name", r.getName());
            int sx = r.getMaxX()-r.getMinX()+1, sy = r.getMaxY()-r.getMinY()+1, sz = r.getMaxZ()-r.getMinZ()+1;
            ro.addProperty("size", sx+"\u00D7"+sy+"\u00D7"+sz); ro.addProperty("priority", r.getPriority());
            arr.add(ro);
        }
        o.add("regions", arr); o.addProperty("total", arr.size());
        sync(player, "REGION_LIST", o);
    }

    private void sendRegionEdit(ServerPlayerEntity player, String rid) {
        Region r = storage.getRegion(rid); if (r == null) { sendRegionList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("id", r.getId()); o.addProperty("name", r.getName()); o.addProperty("world", r.getWorld());
        o.addProperty("minX", r.getMinX()); o.addProperty("minY", r.getMinY()); o.addProperty("minZ", r.getMinZ());
        o.addProperty("maxX", r.getMaxX()); o.addProperty("maxY", r.getMaxY()); o.addProperty("maxZ", r.getMaxZ());
        o.addProperty("priority", r.getPriority());
        o.addProperty("groupId", r.getGroupId() != null ? r.getGroupId() : "");
        o.addProperty("parentId", r.getParentId() != null ? r.getParentId() : "");
        o.addProperty("entryOn", r.isEntryMessageEnabled()); o.addProperty("exitOn", r.isExitMessageEnabled());
        o.addProperty("entryMsg", r.getEntryMessage()); o.addProperty("exitMsg", r.getExitMessage());
        o.addProperty("protectionMsg", r.getProtectionMessage()); o.addProperty("color", r.getParticleColor());
        o.addProperty("advEntryCount", r.getRequiredAdvancementsEntry().size());
        o.addProperty("advExitCount", r.getRequiredAdvancementsExit().size());
        o.addProperty("advDeniedMsg", r.getAdvancementDeniedMessage());
        o.addProperty("questEntryCount", r.getRequiredQuestsEntry().size());
        o.addProperty("questExitCount", r.getRequiredQuestsExit().size());
        o.addProperty("ftbQuests", ftbQuestsLoaded);
        // Command triggers count (for button labels)
        JsonArray cEnter = new JsonArray(); r.getCommandsOnEnter().forEach(cEnter::add);
        JsonArray cExit  = new JsonArray(); r.getCommandsOnExit().forEach(cExit::add);
        o.add("commandsOnEnter", cEnter);
        o.add("commandsOnExit",  cExit);
        sync(player, "REGION_EDIT", o);
    }

    private void sendCmdTriggers(ServerPlayerEntity player, String rid, String mode) {
        Region r = storage.getRegion(rid); if (r == null) { sendRegionList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("regionId", rid);
        o.addProperty("triggerMode", mode);
        JsonArray arr = new JsonArray();
        List<String> cmds = mode.equals("enter") ? r.getCommandsOnEnter() : r.getCommandsOnExit();
        cmds.forEach(arr::add);
        o.add("commands", arr);
        sync(player, "CMD_TRIGGERS", o);
    }

    private void sendFlags(ServerPlayerEntity player, String rid) {
        Region r = storage.getRegion(rid); if (r == null) { sendRegionList(player); return; }
        JsonObject o = new JsonObject(); o.addProperty("regionId", r.getId()); o.addProperty("regionName", r.getName());
        JsonObject fl = new JsonObject();
        for (Flag f : Flag.values()) { Boolean v = r.getFlag(f); if (v == null) fl.add(f.getId(), JsonNull.INSTANCE); else fl.addProperty(f.getId(), v.toString()); }
        o.add("flags", fl); sync(player, "FLAGS", o);
    }

    private void sendMembers(ServerPlayerEntity player, String rid) {
        Region r = storage.getRegion(rid); if (r == null) { sendRegionList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("regionId", r.getId()); o.addProperty("regionName", r.getName());
        o.addProperty("ownerName", r.getOwner() != null ? r.getOwner().getName() : "?");
        o.addProperty("ownerUuid", r.getOwner() != null ? r.getOwner().getUuid().toString() : "");
        JsonArray arr = new JsonArray(); Set<UUID> seen = new HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            UUID u = p.getUuid(); seen.add(u);
            JsonObject po = new JsonObject(); po.addProperty("uuid", u.toString()); po.addProperty("name", p.getName().getString());
            po.addProperty("member", r.isMember(u)); po.addProperty("online", true); arr.add(po);
        }
        for (Member m : r.getMembers()) {
            if (!seen.contains(m.getUuid())) {
                JsonObject po = new JsonObject(); po.addProperty("uuid", m.getUuid().toString()); po.addProperty("name", m.getName());
                po.addProperty("member", true); po.addProperty("online", false); arr.add(po);
            }
        }
        o.add("players", arr); sync(player, "MEMBERS", o);
    }

    private void sendGroupList(ServerPlayerEntity player) {
        JsonObject o = new JsonObject(); JsonArray arr = new JsonArray();
        for (RegionGroup g : storage.getGroups()) {
            JsonObject go = new JsonObject(); go.addProperty("id", g.getId()); go.addProperty("name", g.getName());
            go.addProperty("regionCount", g.getRegionIds().size()); go.addProperty("flagCount", g.getFlags().size());
            arr.add(go);
        }
        o.add("groups", arr); o.addProperty("total", arr.size());
        sync(player, "GROUP_LIST", o);
    }

    private void sendGroupEdit(ServerPlayerEntity player, String gid) {
        RegionGroup g = storage.getGroup(gid); if (g == null) { sendGroupList(player); return; }
        JsonObject o = new JsonObject(); o.addProperty("id", g.getId()); o.addProperty("name", g.getName());
        o.addProperty("flagCount", g.getFlags().size());
        JsonArray arr = new JsonArray(); for (String rid : g.getRegionIds()) arr.add(rid);
        o.add("regions", arr);
        o.addProperty("advEntryCount", g.getRequiredAdvancementsEntry().size());
        o.addProperty("advExitCount", g.getRequiredAdvancementsExit().size());
        o.addProperty("questEntryCount", g.getRequiredQuestsEntry().size());
        o.addProperty("questExitCount", g.getRequiredQuestsExit().size());
        o.addProperty("ftbQuests", ftbQuestsLoaded);
        sync(player, "GROUP_EDIT", o);
    }

    private void sendGroupFlags(ServerPlayerEntity player, String gid) {
        RegionGroup g = storage.getGroup(gid); if (g == null) { sendGroupList(player); return; }
        JsonObject o = new JsonObject(); o.addProperty("groupId", g.getId()); o.addProperty("groupName", g.getName());
        JsonObject fl = new JsonObject();
        for (Flag f : Flag.values()) { Boolean v = g.getFlag(f); if (v == null) fl.add(f.getId(), JsonNull.INSTANCE); else fl.addProperty(f.getId(), v.toString()); }
        o.add("flags", fl); sync(player, "GROUP_FLAGS", o);
    }

    private void sendSelectParent(ServerPlayerEntity player, String regionId) {
        JsonObject o = new JsonObject(); o.addProperty("regionId", regionId);
        JsonArray arr = new JsonArray();
        for (Region r : storage.getRegions()) { if (r.getId().equals(regionId)) continue; JsonObject ro = new JsonObject(); ro.addProperty("id", r.getId()); ro.addProperty("name", r.getName()); arr.add(ro); }
        o.add("zones", arr); sync(player, "SELECT_PARENT", o);
    }

    private void sendSelectGroup(ServerPlayerEntity player, String regionId) {
        JsonObject o = new JsonObject(); o.addProperty("regionId", regionId);
        JsonArray arr = new JsonArray();
        for (RegionGroup g : storage.getGroups()) { JsonObject go = new JsonObject(); go.addProperty("id", g.getId()); go.addProperty("name", g.getName()); arr.add(go); }
        o.add("groups", arr); sync(player, "SELECT_GROUP", o);
    }

    private void closeGui(ServerPlayerEntity p) { if (ServerPlayNetworking.canSend(p, GuiSyncPayload.ID)) ServerPlayNetworking.send(p, new GuiSyncPayload("CLOSE", "{}")); }
    private void sync(ServerPlayerEntity p, String v, JsonObject d) { if (ServerPlayNetworking.canSend(p, GuiSyncPayload.ID)) ServerPlayNetworking.send(p, new GuiSyncPayload(v, d.toString())); }
    public boolean hasClientMod(ServerPlayerEntity p) { return ServerPlayNetworking.canSend(p, GuiSyncPayload.ID); }
    private void deltaPrio(String rid, int d) { Region r = storage.getRegion(rid); if (r != null) { r.setPriority(r.getPriority()+d); storage.save(); } }

    // ── Chunk sync builders ──────────────────────────────────────────────────

    private void sendChunkSets(ServerPlayerEntity player) {
        LOGGER.info("[CG] Sending CHUNK_SETS to {}", player.getName().getString());
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, ChunkSet> entry : chunkSetStorage.getAllSets().entrySet()) {
            JsonObject so = new JsonObject();
            so.addProperty("id", entry.getKey());
            so.addProperty("name", entry.getKey());
            so.addProperty("entryCount", entry.getValue().size());
            so.addProperty("totalChunks", entry.getValue().getTotalAffectedChunks());
            arr.add(so);
        }
        o.add("sets", arr);
        // Pass suggestion count so the button can show the badge
        o.addProperty("suggestionCount", chunkSuggestionStorage != null ? chunkSuggestionStorage.size() : 0);
        sync(player, "CHUNK_SETS", o);
    }

    private void sendChunkSuggestions(ServerPlayerEntity player) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        if (chunkSuggestionStorage != null) {
            for (ChunkSuggestion s : chunkSuggestionStorage.getAll()) {
                JsonObject so = new JsonObject();
                so.addProperty("id",         s.getId());
                so.addProperty("playerName", s.getPlayerName());
                so.addProperty("x",          Math.round(s.getX()));
                so.addProperty("y",          Math.round(s.getY()));
                so.addProperty("z",          Math.round(s.getZ()));
                so.addProperty("world",      s.getWorld());
                so.addProperty("createdAt",  s.getCreatedAt());
                arr.add(so);
            }
        }
        o.add("suggestions", arr);
        sync(player, "CHUNK_SUGGESTIONS", o);
    }

    private void sendChunkSetEdit(ServerPlayerEntity player, String setName) {
        ChunkSet set = chunkSetStorage.getSet(setName);
        if (set == null) { sendChunkSets(player); return; }
        // Read sort direction from request (passed as p2 when called from sort button)
        JsonObject o = new JsonObject();
        o.addProperty("setName", setName);
        o.addProperty("totalChunks", set.getTotalAffectedChunks());
        JsonArray arr = new JsonArray();
        List<ChunkEntry> entries = new ArrayList<>(set.getChunks());
        // Default: newest first (desc)
        entries.sort((a, b) -> Long.compare(b.getAddedAt(), a.getAddedAt()));
        for (ChunkEntry e : entries) {
            JsonObject co = new JsonObject();
            co.addProperty("region", e.getRegionFileName());
            co.addProperty("chunkX", e.getChunkX());
            co.addProperty("chunkZ", e.getChunkZ());
            co.addProperty("radius", e.getRadius());
            co.addProperty("centerX", e.getCenterX());
            co.addProperty("centerZ", e.getCenterZ());
            co.addProperty("label", e.getLabel());
            co.addProperty("addedAt", e.getAddedAt());
            int d = (e.getRadius() * 2) + 1;
            co.addProperty("affected", d * d);
            arr.add(co);
        }
        o.add("entries", arr);
        sync(player, "CHUNK_SET_EDIT", o);
    }

    private void sendChunkConfirm(ServerPlayerEntity player, String setName) {
        ChunkSet set = chunkSetStorage.getSet(setName);
        if (set == null) { sendChunkSets(player); return; }
        Set<ChunkCalculator.ChunkCoord> chunks = new HashSet<>();
        for (ChunkEntry e : set.getChunks()) chunks.addAll(ChunkCalculator.getAffectedChunks(e));
        Set<ChunkCalculator.RegionCoord> regions = ChunkCalculator.getAffectedRegions(chunks);
        JsonObject o = new JsonObject();
        o.addProperty("setName", setName);
        o.addProperty("regionCount", regions.size());
        o.addProperty("chunkCount", chunks.size());
        o.addProperty("entryCount", set.size());
        sync(player, "CHUNK_CONFIRM", o);
    }

    // ── Server sync builder ──────────────────────────────────────────────────

    private void sendServerAdmin(ServerPlayerEntity player) {
        LOGGER.info("[CG] Sending SERVER_ADMIN to {}", player.getName().getString());
        JsonObject o = new JsonObject();
        o.addProperty("active", serverManager.isActive());
        if (serverManager.isActive()) {
            o.addProperty("mode", serverManager.getActiveMode().name().toLowerCase());
            o.addProperty("modeLabel", serverManager.getActiveMode().label);
            o.addProperty("remaining", serverManager.getRemainingSeconds());
        }
        o.addProperty("webhookConfigured", DiscordWebhook.isConfigured());
        String url = DiscordWebhook.getUrl();
        if (!url.isBlank() && url.length() > 20) {
            o.addProperty("webhookPreview", url.substring(0, 20) + "...");
        } else {
            o.addProperty("webhookPreview", url);
        }
        sync(player, "SERVER_ADMIN", o);
    }

    // ── Advancement sync builder ────────────────────────────────────────────

    private void sendAdvancementSelect(ServerPlayerEntity player, String target, String type) {
        sendAdvancementSelect(player, target, type, "", 0);
    }

    private void sendAdvancementSelect(ServerPlayerEntity player, String target, String type, String filter, int page) {
        // target = "region:id" or "group:id"
        // type = "entry" or "exit"
        JsonObject o = new JsonObject();
        o.addProperty("target", target);
        o.addProperty("type", type);
        o.addProperty("filter", filter);
        o.addProperty("page", page);

        // Get currently selected advancements
        Set<String> selected = new java.util.HashSet<>();
        String[] tp = target.split(":", 2);
        if (tp.length == 2) {
            if (tp[0].equals("region")) {
                Region r = storage.getRegion(tp[1]);
                if (r != null) {
                    selected.addAll(type.equals("entry") ? r.getRequiredAdvancementsEntry() : r.getRequiredAdvancementsExit());
                }
            } else if (tp[0].equals("group")) {
                RegionGroup g = storage.getGroup(tp[1]);
                if (g != null) {
                    selected.addAll(type.equals("entry") ? g.getRequiredAdvancementsEntry() : g.getRequiredAdvancementsExit());
                }
            }
        }

        // Collect and filter advancements server-side
        String filterLower = filter.toLowerCase();
        List<JsonObject> all = new ArrayList<>();
        if (server != null) {
            for (var adv : server.getAdvancementLoader().getAdvancements()) {
                if (adv.id().getPath().startsWith("recipes/")) continue;
                String name, desc;
                if (adv.value().display().isPresent()) {
                    name = adv.value().display().get().getTitle().getString();
                    desc = adv.value().display().get().getDescription().getString();
                } else {
                    name = adv.id().getPath();
                    desc = "";
                }
                // Apply filter
                if (!filterLower.isEmpty()) {
                    if (!name.toLowerCase().contains(filterLower)
                        && !adv.id().toString().toLowerCase().contains(filterLower)
                        && !desc.toLowerCase().contains(filterLower)) continue;
                }
                JsonObject ao = new JsonObject();
                ao.addProperty("id", adv.id().toString());
                ao.addProperty("name", name);
                ao.addProperty("selected", selected.contains(adv.id().toString()));
                all.add(ao);
            }
        }

        // Sort: selected first, then by name
        all.sort((a, b) -> {
            boolean sa = a.get("selected").getAsBoolean(), sb = b.get("selected").getAsBoolean();
            if (sa != sb) return sa ? -1 : 1;
            return a.get("name").getAsString().compareToIgnoreCase(b.get("name").getAsString());
        });

        // Paginate (50 per page)
        int pageSize = 50;
        int totalFiltered = all.size();
        int maxPage = Math.max(0, (totalFiltered - 1) / pageSize);
        page = Math.max(0, Math.min(page, maxPage));
        int from = page * pageSize;
        int to = Math.min(from + pageSize, totalFiltered);

        JsonArray advArr = new JsonArray();
        for (int i = from; i < to; i++) advArr.add(all.get(i));

        o.add("advancements", advArr);
        o.addProperty("selectedCount", selected.size());
        o.addProperty("totalFiltered", totalFiltered);
        o.addProperty("pageSize", pageSize);
        o.addProperty("maxPage", maxPage);
        sync(player, "ADV_SELECT", o);
    }

    // ── Player management sync builders ─────────────────────────────────────

    private void sendPlayerList(ServerPlayerEntity player, String filter) {
        JsonObject o = new JsonObject();
        o.addProperty("filter", filter);
        o.addProperty("ftbQuests", ftbQuestsLoaded);

        String filterLower = filter.toLowerCase();
        List<JsonObject> all = new ArrayList<>();
        Set<UUID> onlineUuids = new HashSet<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) onlineUuids.add(p.getUuid());

        // Online players
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String name = p.getName().getString();
            if (!filterLower.isEmpty() && !name.toLowerCase().contains(filterLower)) continue;
            JsonObject po = new JsonObject();
            po.addProperty("uuid", p.getUuid().toString());
            po.addProperty("name", name);
            po.addProperty("online", true);
            all.add(po);
        }

        // Offline players from usercache
        try {
            java.nio.file.Path cacheFile = server.getRunDirectory().resolve("usercache.json");
            if (java.nio.file.Files.exists(cacheFile)) {
                JsonArray cache = JsonParser.parseString(java.nio.file.Files.readString(cacheFile)).getAsJsonArray();
                for (JsonElement e : cache) {
                    JsonObject ce = e.getAsJsonObject();
                    String name = ce.get("name").getAsString();
                    UUID uuid = UUID.fromString(ce.get("uuid").getAsString());
                    if (onlineUuids.contains(uuid)) continue;
                    if (!filterLower.isEmpty() && !name.toLowerCase().contains(filterLower)) continue;
                    JsonObject po = new JsonObject();
                    po.addProperty("uuid", uuid.toString());
                    po.addProperty("name", name);
                    po.addProperty("online", false);
                    all.add(po);
                }
            }
        } catch (Exception ignored) {}

        // Sort: online first, then alphabetical
        all.sort((a, b) -> {
            boolean ao = a.get("online").getAsBoolean(), bo = b.get("online").getAsBoolean();
            if (ao != bo) return ao ? -1 : 1;
            return a.get("name").getAsString().compareToIgnoreCase(b.get("name").getAsString());
        });

        JsonArray arr = new JsonArray();
        for (JsonObject po : all) arr.add(po);
        o.add("players", arr);
        o.addProperty("onlineCount", onlineUuids.size());
        o.addProperty("totalCount", all.size());
        sync(player, "PLAYER_LIST", o);
    }

    private void sendPlayerDetail(ServerPlayerEntity admin, UUID targetUuid) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", targetUuid.toString());
        o.addProperty("ftbQuests", ftbQuestsLoaded);

        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        if (target != null) {
            o.addProperty("name", target.getName().getString());
            o.addProperty("online", true);
            o.addProperty("world", shortWorld(target.getServerWorld().getRegistryKey().getValue().toString()));
            o.addProperty("x", (int)target.getX());
            o.addProperty("y", (int)target.getY());
            o.addProperty("z", (int)target.getZ());
            o.addProperty("health", Math.round(target.getHealth() * 10.0f) / 10.0f);
            o.addProperty("maxHealth", Math.round(target.getMaxHealth() * 10.0f) / 10.0f);
            o.addProperty("food", target.getHungerManager().getFoodLevel());
            o.addProperty("xpLevel", target.experienceLevel);
            o.addProperty("gamemode", target.interactionManager.getGameMode().getName());
            o.addProperty("fly", target.getAbilities().allowFlying);
            // Count advancements
            int done = 0, total = 0;
            PlayerAdvancementTracker tracker = target.getAdvancementTracker();
            for (var adv : server.getAdvancementLoader().getAdvancements()) {
                if (adv.id().getPath().startsWith("recipes/")) continue;
                total++;
                if (tracker.getProgress(adv).isDone()) done++;
            }
            o.addProperty("advDone", done);
            o.addProperty("advTotal", total);
        } else {
            // Offline player - minimal info from usercache
            String name = getPlayerName(targetUuid.toString());
            o.addProperty("name", name);
            o.addProperty("online", false);
        }
        sync(admin, "PLAYER_DETAIL", o);
    }

    private void sendPlayerAdvancements(ServerPlayerEntity admin, UUID targetUuid, String filter, int page) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", targetUuid.toString());
        o.addProperty("name", getPlayerName(targetUuid.toString()));
        o.addProperty("filter", filter);
        o.addProperty("page", page);

        ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
        PlayerAdvancementTracker tracker = target != null ? target.getAdvancementTracker() : null;

        String filterLower = filter.toLowerCase();
        List<JsonObject> all = new ArrayList<>();
        for (var adv : server.getAdvancementLoader().getAdvancements()) {
            if (adv.id().getPath().startsWith("recipes/")) continue;
            String name;
            if (adv.value().display().isPresent()) name = adv.value().display().get().getTitle().getString();
            else name = adv.id().getPath();
            if (!filterLower.isEmpty() && !name.toLowerCase().contains(filterLower) && !adv.id().toString().toLowerCase().contains(filterLower)) continue;
            JsonObject ao = new JsonObject();
            ao.addProperty("id", adv.id().toString());
            ao.addProperty("name", name);
            ao.addProperty("done", tracker != null && tracker.getProgress(adv).isDone());
            all.add(ao);
        }

        // Sort: done first, then alphabetical
        all.sort((a, b) -> {
            boolean da = a.get("done").getAsBoolean(), db = b.get("done").getAsBoolean();
            if (da != db) return da ? -1 : 1;
            return a.get("name").getAsString().compareToIgnoreCase(b.get("name").getAsString());
        });

        int pageSize = 50;
        int totalFiltered = all.size();
        int maxPage = Math.max(0, (totalFiltered - 1) / pageSize);
        page = Math.max(0, Math.min(page, maxPage));
        int from = page * pageSize, to = Math.min(from + pageSize, totalFiltered);
        JsonArray advArr = new JsonArray();
        for (int i = from; i < to; i++) advArr.add(all.get(i));

        o.add("advancements", advArr);
        o.addProperty("totalFiltered", totalFiltered);
        o.addProperty("maxPage", maxPage);
        o.addProperty("playerOnline", target != null);
        sync(admin, "PLAYER_ADV", o);
    }

    private void sendPlayerQuests(ServerPlayerEntity admin, UUID targetUuid, String filter, int page) {
        JsonObject o = FtbQuestHelper.buildPlayerQuestPage(filter, page);
        o.addProperty("uuid", targetUuid.toString());
        o.addProperty("name", getPlayerName(targetUuid.toString()));
        o.addProperty("ftbQuests", ftbQuestsLoaded);
        sync(admin, "PLAYER_QUESTS", o);
    }

    private void sendQuestSelect(ServerPlayerEntity player, String target, String type, String filter, int page) {
        // Get currently selected quests
        Set<String> selected = new java.util.HashSet<>();
        String[] tp = target.split(":", 2);
        if (tp.length == 2) {
            if (tp[0].equals("region")) {
                Region r = storage.getRegion(tp[1]);
                if (r != null) selected.addAll(type.equals("entry") ? r.getRequiredQuestsEntry() : r.getRequiredQuestsExit());
            } else if (tp[0].equals("group")) {
                RegionGroup g = storage.getGroup(tp[1]);
                if (g != null) selected.addAll(type.equals("entry") ? g.getRequiredQuestsEntry() : g.getRequiredQuestsExit());
            }
        }
        JsonObject o = FtbQuestHelper.buildQuestPage(filter, page, selected);
        o.addProperty("target", target);
        o.addProperty("type", type);
        sync(player, "QUEST_SELECT", o);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ServerPlayerEntity getOnlinePlayer(String uuidStr) {
        try { return server.getPlayerManager().getPlayer(UUID.fromString(uuidStr)); } catch (Exception e) { return null; }
    }

    private String getPlayerName(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) return p.getName().getString();
            if (server.getUserCache() != null) { var pr = server.getUserCache().getByUuid(uuid); if (pr.isPresent()) return pr.get().getName(); }
        } catch (Exception ignored) {}
        return "?";
    }

    // ── Webhook config persistence ──────────────────────────────────────────

    private void loadWebhookConfig() {
        try {
            java.nio.file.Path file = server.getRunDirectory().resolve("config").resolve("cheeseguard_webhook.txt");
            if (java.nio.file.Files.exists(file)) {
                String url = java.nio.file.Files.readString(file).trim();
                DiscordWebhook.setUrl(url);
                LOGGER.info("[CG] Webhook loaded: " + (url.isBlank() ? "none" : "configured"));
            }
        } catch (Exception e) { LOGGER.warn("[CG] Failed to load webhook config: {}", e.getMessage()); }
    }

    private void saveWebhookConfig() {
        try {
            java.nio.file.Path dir = server.getRunDirectory().resolve("config");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("cheeseguard_webhook.txt"), DiscordWebhook.getUrl());
        } catch (Exception e) { LOGGER.warn("[CG] Failed to save webhook config: {}", e.getMessage()); }
    }

    private String shortWorld(String w) { if (w.contains("overworld")) return "Overworld"; if (w.contains("nether")) return "Nether"; if (w.contains("end")) return "End"; return w; }

    private void executeAsConsole(String command) {
        try {
            String normalized = command.startsWith("/") ? command.substring(1) : command;
            server.getCommandManager().getDispatcher().execute(normalized, server.getCommandSource());
        } catch (Exception e) {
            LOGGER.error("Failed to execute command: {}", command, e);
        }
    }

    /**
     * Execute a command as the given player (player is the source).
     * Supports @s self-reference, heal, gamemode, etc.
     * If the command requires operator level, the player must be OP.
     */
    private void executeAsPlayer(String command, ServerPlayerEntity player) {
        try {
            String normalized = command.startsWith("/") ? command.substring(1) : command;
            server.getCommandManager().getDispatcher().execute(normalized, player.getCommandSource());
        } catch (Exception e) {
            // Fallback: try as console (for commands that need higher perms like /forceload)
            LOGGER.debug("[CG] Shortcut failed as player ({}), retrying as console: {}", e.getMessage(), command);
            executeAsConsole(command);
        }
    }

    public static CheeseGuardMod getInstance() { return INSTANCE; }
    public MinecraftServer getServer() { return server; }
    public RegionStorage getStorage() { return storage; }
    public RegionManager getRegionManager() { return regionManager; }
    public SelectionManager getSelectionManager() { return selectionManager; }
    public VisualizationManager getVisualizationManager() { return visualizationManager; }
    public ChunkSetStorage getChunkSetStorage() { return chunkSetStorage; }
    public ChunkSuggestionStorage getChunkSuggestionStorage() { return chunkSuggestionStorage; }
    public ServerManager getServerManager() { return serverManager; }

    public void giveWand(ServerPlayerEntity player) {
        ItemStack wand = new ItemStack(Items.WOODEN_SWORD);
        wand.set(DataComponentTypes.CUSTOM_NAME, Text.literal("\u00A76[CheeseGuard Wand]").formatted(Formatting.GOLD));
        wand.set(DataComponentTypes.UNBREAKABLE, new net.minecraft.component.type.UnbreakableComponent(true));
        if (!player.getInventory().insertStack(wand.copy())) player.dropItem(wand, false);
        player.sendMessage(Text.literal("Wand obtenu ! Clic G = Pos1 | Clic D = Pos2").formatted(Formatting.GOLD), false);
    }

    public void createRegion(ServerPlayerEntity player, String name, SelectionManager.Selection sel) {
        String id = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        String base = id; int s = 1; while (storage.hasRegion(id)) id = base + "_" + s++;
        Region region = new Region(id, name, sel.world, sel.pos1.getX(), sel.pos1.getY(), sel.pos1.getZ(), sel.pos2.getX(), sel.pos2.getY(), sel.pos2.getZ(), new Member(player.getUuid(), player.getName().getString()));
        storage.addRegion(region); selectionManager.clear(player.getUuid());
        player.sendMessage(Text.literal("Zone creee : " + name + " [" + id + "]").formatted(Formatting.GREEN), false);
        sendRegionEdit(player, id);
    }

    /** Find region by ID first, then by name (case-insensitive). Returns the ID or null. */
    private String findRegionId(String input) {
        if (storage.hasRegion(input)) return input;
        String lower = input.toLowerCase();
        for (Region r : storage.getRegions()) {
            if (r.getId().toLowerCase().equals(lower) || r.getName().toLowerCase().equals(lower)) return r.getId();
        }
        return null;
    }

    /** Find group by ID first, then by name (case-insensitive). Returns the ID or null. */
    private String findGroupId(String input) {
        if (storage.hasGroup(input)) return input;
        String lower = input.toLowerCase();
        for (RegionGroup g : storage.getGroups()) {
            if (g.getId().toLowerCase().equals(lower) || g.getName().toLowerCase().equals(lower)) return g.getId();
        }
        return null;
    }

    public void awaitChatInput(UUID id, Consumer<String> cb) { chatInputCallbacks.put(id, cb); new Thread(() -> { try { Thread.sleep(60_000); chatInputCallbacks.remove(id); } catch (InterruptedException ignored) {} }, "cg-expire").start(); }
    public boolean hasPendingChatInput(UUID id) { return chatInputCallbacks.containsKey(id); }
    public void cancelChatInput(UUID id) { chatInputCallbacks.remove(id); }

    public EventManager getEventManager() { return eventManager; }
    public com.cheeseguard.logic.DimensionManager getDimensionManager() { return dimensionManager; }

    // ═════════════════════════════════════════════════════════════════════════
    // EVENT SYSTEM - Send methods
    // ═════════════════════════════════════════════════════════════════════════

    private void sendEventList(ServerPlayerEntity player) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (ServerEvent evt : eventManager.getEvents()) {
            JsonObject eo = new JsonObject();
            eo.addProperty("id", evt.getId());
            eo.addProperty("name", evt.getName());
            eo.addProperty("color", evt.getDisplayColor());
            eo.addProperty("mode", evt.getMode().name());
            eo.addProperty("trigger", evt.getTrigger().icon + " " + evt.getTrigger().label);
            eo.addProperty("duration", evt.getDurationMinutes() + " min");
            eo.addProperty("phases", evt.getPhases().size());
            eo.addProperty("effects", evt.getAllEffects().size());
            eo.addProperty("state", evt.getState().name());
            eo.addProperty("running", evt.isRunning());
            if (evt.isRunning()) {
                eo.addProperty("remaining", evt.getRemainingSeconds());
                eo.addProperty("participants", evt.getParticipantCount());
                eo.addProperty("spectators", evt.getSpectatorCount());
            }
            arr.add(eo);
        }
        o.add("events", arr);
        o.addProperty("total", arr.size());
        o.addProperty("ecLoaded", eventManager.isEnhancedCelestialsLoaded());
        sync(player, "EVENT_LIST", o);
    }

    private void sendEventEdit(ServerPlayerEntity player, String eventId) {
        sendEventEdit(player, eventId, "EVENT_EDIT");
    }

    private void sendEventEdit(ServerPlayerEntity player, String eventId, String viewName) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        JsonObject o = new JsonObject();
        // Identity
        o.addProperty("id", evt.getId());
        o.addProperty("name", evt.getName());
        o.addProperty("description", evt.getDescription());
        o.addProperty("displayColor", evt.getDisplayColor());
        o.addProperty("mode", evt.getMode().name());
        o.addProperty("modeLabel", evt.getMode() == ServerEvent.EventMode.SERVER_EVENT ? "Evenement serveur" : "Mini-jeu");
        o.addProperty("miniGameType", evt.getMiniGameType());
        String mgLabel = "";
        if (!evt.getMiniGameType().isBlank()) {
            try { mgLabel = com.cheeseguard.logic.MiniGameManager.MiniGameType.valueOf(evt.getMiniGameType()).displayName; }
            catch (Exception e) { mgLabel = evt.getMiniGameType(); }
        }
        o.addProperty("miniGameLabel", mgLabel);
        o.addProperty("queueSize", eventManager.getMiniGameManager().getQueueSize(evt.getId()));
        o.addProperty("fishingMode", evt.getFishingMode());
        o.addProperty("cobblemonLoaded", CobblemonHook.isAvailable());

        // Trigger
        o.addProperty("trigger", evt.getTrigger().name());
        o.addProperty("triggerLabel", evt.getTrigger().icon + " " + evt.getTrigger().label);
        o.addProperty("lunarEventId", evt.getLunarEventId());
        o.addProperty("recurringInterval", evt.getRecurringIntervalMinutes());
        o.addProperty("triggerZone", evt.getTriggerZoneId());

        // Timing
        o.addProperty("duration", evt.getDurationMinutes());
        o.addProperty("totalSeconds", evt.getTotalDurationSeconds());
        o.addProperty("announceDuration", evt.getAnnounceDurationSeconds());
        o.addProperty("countdown", evt.getCountdownSeconds());
        o.addProperty("cooldown", evt.getCooldownMinutes());

        // Inscription
        o.addProperty("minParticipants", evt.getMinParticipants());
        o.addProperty("maxParticipants", evt.getMaxParticipants());
        o.addProperty("teamMode", evt.getTeamMode().name());
        o.addProperty("teamSize", evt.getTeamSize());
        o.addProperty("deathBehavior", evt.getDeathBehavior().name());
        o.addProperty("endOnAllDead", evt.isEndOnAllDead());
        o.addProperty("lightningMobsOnEnd", evt.isLightningMobsOnEnd());
        o.addProperty("lightningRadius", evt.getLightningRadius());
        o.addProperty("lightningX", evt.getLightningX()); o.addProperty("lightningY", evt.getLightningY()); o.addProperty("lightningZ", evt.getLightningZ());
        o.addProperty("lightningPosSet", evt.hasLightningPos());
        o.addProperty("victoryCondition", evt.getVictoryCondition().name());
        o.addProperty("victoryTarget", evt.getVictoryTargetScore());

        // Messages
        o.addProperty("announceMsg", evt.getAnnounceMessage());
        o.addProperty("announceColor", evt.getAnnounceColor());
        o.addProperty("startMsg", evt.getStartMessage());
        o.addProperty("startColor", evt.getStartColor());
        o.addProperty("endMsg", evt.getEndMessage());
        o.addProperty("endColor", evt.getEndColor());

        // Features
        o.addProperty("discordNotify", evt.isDiscordNotify());
        o.addProperty("bossbarEnabled", evt.isBossbarEnabled());
        o.addProperty("bossbarColor", evt.getBossbarColor());
        o.addProperty("scoreboardVisible", evt.isScoreboardVisible());

        // Locations
        o.addProperty("tpPX", evt.getTpParticipantX()); o.addProperty("tpPY", evt.getTpParticipantY());
        o.addProperty("tpPZ", evt.getTpParticipantZ()); o.addProperty("tpPW", evt.getTpParticipantWorld());
        o.addProperty("respawnX", evt.getRespawnX()); o.addProperty("respawnY", evt.getRespawnY());
        o.addProperty("respawnZ", evt.getRespawnZ()); o.addProperty("respawnSet", evt.hasRespawnPos());
        o.addProperty("tpSX", evt.getTpSpectatorX()); o.addProperty("tpSY", evt.getTpSpectatorY());
        o.addProperty("tpSZ", evt.getTpSpectatorZ()); o.addProperty("tpSW", evt.getTpSpectatorWorld());
        o.addProperty("returnMode", evt.getReturnMode());
        o.addProperty("retX", evt.getReturnX()); o.addProperty("retY", evt.getReturnY());
        o.addProperty("retZ", evt.getReturnZ()); o.addProperty("retW", evt.getReturnWorld());

        // Kit
        JsonArray kitEntry = new JsonArray(); for (String c : evt.getKitEntryCommands()) kitEntry.add(c);
        JsonArray kitExit = new JsonArray(); for (String c : evt.getKitExitCommands()) kitExit.add(c);
        o.add("kitEntry", kitEntry); o.add("kitExit", kitExit);
        o.addProperty("saveInventory", evt.isSaveInventory());
        o.addProperty("saveTeamCobblemon", evt.isSaveTeamCobblemon());
        o.addProperty("forceEmptyInventory", evt.isForceEmptyInventory());

        // Restrictions
        JsonArray bl = new JsonArray(); for (String i : evt.getBlacklistItems()) bl.add(i);
        JsonArray bp = new JsonArray(); for (String i : evt.getBlacklistPokemon()) bp.add(i);
        o.add("blacklistItems", bl); o.add("blacklistPokemon", bp);

        // Scope zones
        JsonArray zones = new JsonArray(); for (String z : evt.getScopeZoneIds()) zones.add(z);
        o.add("scopeZones", zones);

        // Phases & effects summary (legacy)
        o.addProperty("phaseCount", evt.getPhases().size());
        o.addProperty("effectCount", evt.getAllEffects().size());
        o.addProperty("triggerCount", evt.getScoreTriggers().size());
        o.addProperty("rewardCount", evt.getRewards().size());

        // Acts (new timeline system) - ensure at least one act
        evt.ensureDefaultAct();
        JsonArray actsArr = new JsonArray();
        for (com.cheeseguard.data.EventAct act : evt.getActs()) actsArr.add(act.toJson());
        o.add("acts", actsArr);
        o.addProperty("useActSystem", evt.isUseActSystem());
        // Scoreboard display
        o.addProperty("sbPosition", evt.getScoreboardPosition());
        o.addProperty("sbCustomX", evt.getScoreboardCustomX());
        o.addProperty("sbCustomY", evt.getScoreboardCustomY());
        o.addProperty("sbMaxRows", evt.getScoreboardMaxRows());
        o.addProperty("sbCompact", evt.isScoreboardCompact());
        o.addProperty("sbTitleColor", evt.getScoreboardTitleColor());
        o.addProperty("sbTextColor", evt.getScoreboardTextColor());
        o.addProperty("sbBgColor", evt.getScoreboardBgColor());
        o.addProperty("sbBgOpacity", evt.getScoreboardBgOpacity());
        o.addProperty("sbShowRank", evt.isScoreboardShowRank());
        o.addProperty("sbShowScore", evt.isScoreboardShowScore());
        o.addProperty("sbPrefix", evt.getScoreboardPrefix());
        // Score triggers
        JsonArray stArr = new JsonArray();
        for (com.cheeseguard.data.ServerEvent.ScoreTrigger st : evt.getScoreTriggers()) stArr.add(st.toJson());
        o.add("scoreTriggers", stArr);

        // Chaining & exclusion
        o.addProperty("chainEvent", evt.getChainEventId());
        o.addProperty("exclusionGroup", evt.getExclusionGroup());
        o.addProperty("exclusionPriority", evt.getExclusionPriority());

        // Runtime state
        o.addProperty("state", evt.getState().name());
        o.addProperty("running", evt.isRunning());

        // Available data for selectors
        o.addProperty("ecLoaded", eventManager.isEnhancedCelestialsLoaded());
        JsonArray lunarArr = new JsonArray();
        for (String le : eventManager.getAvailableLunarEvents()) lunarArr.add(le);
        o.add("lunarEvents", lunarArr);
        // Available zones for scope selector
        JsonArray availableZones = new JsonArray();
        for (var r : regionManager.getStorage().getRegions()) {
            JsonObject zo = new JsonObject(); zo.addProperty("id", r.getId()); zo.addProperty("name", r.getName());
            availableZones.add(zo);
        }
        o.add("availableZones", availableZones);
        // Available events for chaining
        JsonArray chainable = new JsonArray();
        for (ServerEvent e : eventManager.getEvents()) {
            if (!e.getId().equals(evt.getId())) {
                JsonObject ce = new JsonObject(); ce.addProperty("id", e.getId()); ce.addProperty("name", e.getName());
                chainable.add(ce);
            }
        }
        o.add("chainableEvents", chainable);

        sync(player, viewName, o);
    }

    // ── Phases sub-page ──────────────────────────────────────────────────────

    private void sendEventPhases(ServerPlayerEntity player, String eventId) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("eventId", evt.getId());
        o.addProperty("eventName", evt.getName());
        JsonArray arr = new JsonArray();
        for (EventPhase ph : evt.getPhases()) {
            JsonObject po = new JsonObject();
            po.addProperty("id", ph.getId());
            po.addProperty("name", ph.getName());
            po.addProperty("duration", ph.getDurationSeconds());
            po.addProperty("effectCount", ph.getEffects().size());
            po.addProperty("hasTitle", !ph.getTitleMessage().isBlank());
            po.addProperty("hasSound", !ph.getStartSound().isBlank());
            arr.add(po);
        }
        o.add("phases", arr);
        o.addProperty("totalSeconds", evt.getTotalDurationSeconds());
        sync(player, "EVENT_PHASES", o);
    }

    private void sendPhaseEdit(ServerPlayerEntity player, String eventId, String phaseId) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        EventPhase ph = evt.getPhase(phaseId);
        if (ph == null) { sendEventPhases(player, eventId); return; }
        JsonObject o = new JsonObject();
        o.addProperty("eventId", evt.getId());
        o.addProperty("eventName", evt.getName());
        o.addProperty("phaseId", ph.getId());
        o.addProperty("name", ph.getName());
        o.addProperty("duration", ph.getDurationSeconds());
        o.addProperty("titleMessage", ph.getTitleMessage());
        o.addProperty("subtitleMessage", ph.getSubtitleMessage());
        o.addProperty("titleColor", ph.getTitleColor());
        o.addProperty("startSound", ph.getStartSound());
        o.addProperty("effectCount", ph.getEffects().size());
        sync(player, "EVENT_PHASE_EDIT", o);
    }

    // ── Phase effects ────────────────────────────────────────────────────────

    private void sendPhaseEffects(ServerPlayerEntity player, String eventId, String phaseId) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        EventPhase ph = evt.getPhase(phaseId);
        if (ph == null) { sendEventPhases(player, eventId); return; }
        JsonObject o = new JsonObject();
        o.addProperty("eventId", evt.getId());
        o.addProperty("eventName", evt.getName());
        o.addProperty("phaseId", ph.getId());
        o.addProperty("phaseName", ph.getName());
        JsonArray arr = new JsonArray();
        for (EventEffect eff : ph.getEffects()) {
            JsonObject eo = new JsonObject();
            eo.addProperty("id", eff.getId());
            eo.addProperty("type", eff.getType().name());
            eo.addProperty("icon", eff.getType().icon);
            eo.addProperty("label", eff.getType().label);
            eo.addProperty("summary", eff.getSummary());
            arr.add(eo);
        }
        o.add("effects", arr);
        // Send effect type options for the add dialog
        JsonArray types = new JsonArray();
        for (EventEffect.Type t : EventEffect.Type.values()) {
            JsonObject to = new JsonObject(); to.addProperty("name", t.name()); to.addProperty("label", t.icon + " " + t.label);
            types.add(to);
        }
        o.add("effectTypes", types);
        sync(player, "PHASE_EFFECTS", o);
    }

    private void sendEffectEdit(ServerPlayerEntity player, String eventId, String effectId) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        EventEffect eff = evt.findEffect(effectId);
        if (eff == null) { sendEventPhases(player, eventId); return; }
        EventPhase parentPhase = evt.findPhaseForEffect(effectId);

        JsonObject o = new JsonObject();
        o.addProperty("eventId", evt.getId());
        o.addProperty("eventName", evt.getName());
        o.addProperty("effectId", eff.getId());
        o.addProperty("phaseId", parentPhase != null ? parentPhase.getId() : "");
        o.addProperty("phaseName", parentPhase != null ? parentPhase.getName() : "");
        o.addProperty("type", eff.getType().name());
        o.addProperty("typeLabel", eff.getType().icon + " " + eff.getType().label);

        // Common fields
        o.addProperty("scopeZone", eff.getScopeZoneId());
        o.addProperty("dayNight", eff.getDayNightCondition());

        // Type-specific fields
        switch (eff.getType()) {
            case POTION -> {
                o.addProperty("potionId", eff.getPotionId());
                o.addProperty("potionLabel", sId(eff.getPotionId()));
                o.addProperty("amplifier", eff.getAmplifier());
            }
            case COMMAND -> {
                o.addProperty("command", eff.getCommand());
                o.addProperty("radius", eff.getRadiusBlocks());
                o.addProperty("interval", eff.getIntervalSeconds());
                o.addProperty("chance", eff.getChancePercent());
            }
            case BROADCAST -> {
                o.addProperty("message", eff.getMessage());
                o.addProperty("color", eff.getMessageColor());
                o.addProperty("interval", eff.getIntervalSeconds());
            }
            case MULTIPLIER -> {
                o.addProperty("multType", eff.getMultiplierType());
                o.addProperty("multValue", eff.getMultiplierValue());
            }
            case SOUND -> {
                o.addProperty("soundId", eff.getSoundId());
                o.addProperty("volume", eff.getSoundVolume());
                o.addProperty("pitch", eff.getSoundPitch());
                o.addProperty("interval", eff.getIntervalSeconds());
            }
            case TITLE -> {
                o.addProperty("titleText", eff.getTitleText());
                o.addProperty("subtitleText", eff.getSubtitleText());
                o.addProperty("titleColor", eff.getTitleColor());
                o.addProperty("fadeIn", eff.getFadeIn());
                o.addProperty("stay", eff.getStay());
                o.addProperty("fadeOut", eff.getFadeOut());
                o.addProperty("interval", eff.getIntervalSeconds());
            }
            case PARTICLE -> {
                o.addProperty("particleId", eff.getParticleId());
                o.addProperty("particleCount", eff.getParticleCount());
                o.addProperty("particleSpread", eff.getParticleSpread());
                o.addProperty("interval", eff.getIntervalSeconds());
            }
            case ITEM_DROP -> {
                o.addProperty("itemId", eff.getItemId());
                o.addProperty("itemCount", eff.getItemCount());
                o.addProperty("interval", eff.getIntervalSeconds());
                o.addProperty("chance", eff.getChancePercent());
            }
            case WEATHER -> o.addProperty("weatherType", eff.getWeatherType());
            case TIME -> {
                o.addProperty("timeValue", eff.getTimeValue());
                o.addProperty("freezeTime", eff.isFreezeTime());
            }
            case FLAG_OVERRIDE -> {
                o.addProperty("flagId", eff.getFlagId());
                o.addProperty("targetZone", eff.getTargetZoneId());
                o.addProperty("flagValue", eff.getFlagValue());
            }
            case ENTITY_SPAWN -> {
                o.addProperty("entityCommand", eff.getEntityCommand());
                o.addProperty("spawnX", eff.getSpawnX());
                o.addProperty("spawnY", eff.getSpawnY());
                o.addProperty("spawnZ", eff.getSpawnZ());
                o.addProperty("relativePos", eff.isUseRelativePos());
                o.addProperty("interval", eff.getIntervalSeconds());
            }
            case ZONE_SHRINK -> {
                o.addProperty("zoneCenterX", eff.getZoneCenterX());
                o.addProperty("zoneCenterZ", eff.getZoneCenterZ());
                o.addProperty("zoneRadius", eff.getZoneRadius());
                o.addProperty("zoneDamage", eff.getZoneDamage());
                o.addProperty("showBorder", eff.isZoneShowBorder());
                o.addProperty("orbitEnabled", eff.isZoneOrbitEnabled());
                o.addProperty("orbitRadius", eff.getZoneOrbitRadius());
                o.addProperty("orbitSpeed", eff.getZoneOrbitSpeed());
                o.addProperty("orbitAngle", eff.getZoneOrbitAngleOffset());
            }
        }

        // Available zones for scope selector
        JsonArray zones = new JsonArray();
        for (var r : regionManager.getStorage().getRegions()) {
            JsonObject zo = new JsonObject(); zo.addProperty("id", r.getId()); zo.addProperty("name", r.getName());
            zones.add(zo);
        }
        o.add("availableZones", zones);

        sync(player, "EFFECT_EDIT", o);
    }

    private static String sId(String id) { return id.contains(":") ? id.substring(id.indexOf(':') + 1).replace("_", " ") : id; }

    // ── Scoring sub-page ─────────────────────────────────────────────────────

    private void sendEventScoring(ServerPlayerEntity player, String eventId) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("eventId", evt.getId());
        o.addProperty("eventName", evt.getName());
        o.addProperty("victoryCondition", evt.getVictoryCondition().name());
        o.addProperty("victoryTarget", evt.getVictoryTargetScore());
        JsonArray arr = new JsonArray();
        int idx = 0;
        for (var t : evt.getScoreTriggers()) {
            JsonObject to = new JsonObject();
            to.addProperty("index", idx++);
            to.addProperty("type", t.getType().name());
            to.addProperty("filter", t.getFilter());
            to.addProperty("points", t.getPoints());
            arr.add(to);
        }
        o.add("triggers", arr);
        // Available trigger types
        JsonArray types = new JsonArray();
        for (var tt : ServerEvent.ScoreTrigger.TriggerType.values()) types.add(tt.name());
        o.add("triggerTypes", types);
        sync(player, "EVENT_SCORING", o);
    }

    // ── Rewards sub-page ─────────────────────────────────────────────────────

    private void sendEventRewards(ServerPlayerEntity player, String eventId) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("eventId", evt.getId());
        o.addProperty("eventName", evt.getName());
        JsonArray arr = new JsonArray();
        int idx = 0;
        for (var r : evt.getRewards()) {
            JsonObject ro = new JsonObject();
            ro.addProperty("index", idx++);
            ro.addProperty("rank", r.getRank());
            ro.addProperty("rankLabel", switch (r.getRank()) {
                case "1" -> "🥇 1er"; case "2" -> "🥈 2eme"; case "3" -> "🥉 3eme";
                case "top10" -> "🏅 Top 10"; case "all" -> "👥 Tous"; case "lottery" -> "🎰 Loterie";
                default -> r.getRank();
            });
            ro.addProperty("lotteryCount", r.getLotteryCount());
            JsonArray cmds = new JsonArray(); for (String c : r.getCommands()) cmds.add(c);
            ro.add("commands", cmds);
            arr.add(ro);
        }
        o.add("rewards", arr);
        sync(player, "EVENT_REWARDS", o);
    }

    private void sendRewardCmds(ServerPlayerEntity player, String eventId, String rewardIdxStr) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) { sendEventList(player); return; }
        int ri;
        try { ri = Integer.parseInt(rewardIdxStr); } catch (Exception e) { return; }
        if (ri < 0 || ri >= evt.getRewards().size()) { sendEventRewards(player, eventId); return; }
        var reward = evt.getRewards().get(ri);
        JsonObject o = new JsonObject();
        o.addProperty("eventId", evt.getId());
        o.addProperty("eventName", evt.getName());
        o.addProperty("rewardIdx", ri);
        o.addProperty("rankLabel", switch (reward.getRank()) {
            case "1" -> "🥇 1er"; case "2" -> "🥈 2eme"; case "3" -> "🥉 3eme";
            case "top10" -> "🏅 Top 10"; case "all" -> "👥 Tous"; case "lottery" -> "🎰 Loterie";
            default -> reward.getRank();
        });
        JsonArray cmds = new JsonArray();
        int ci = 0;
        for (String c : reward.getCommands()) {
            JsonObject co = new JsonObject();
            co.addProperty("index", ci++);
            co.addProperty("command", c);
            cmds.add(co);
        }
        o.add("commands", cmds);
        sync(player, "REWARD_CMDS", o);
    }

    // ── Templates sub-page ───────────────────────────────────────────────────

    private void sendTemplateList(ServerPlayerEntity player) {
        JsonObject o = new JsonObject();
        List<String> templates = com.cheeseguard.logic.EventTemplate.list(server);
        JsonArray arr = new JsonArray();
        for (String name : templates) arr.add(name);
        o.add("templates", arr);
        o.addProperty("total", templates.size());
        // Add events for "save as template" dropdown
        JsonArray events = new JsonArray();
        for (ServerEvent e : eventManager.getEvents()) {
            JsonObject eo = new JsonObject();
            eo.addProperty("id", e.getId());
            eo.addProperty("name", e.getName());
            events.add(eo);
        }
        o.add("events", events);
        sync(player, "TEMPLATE_LIST", o);
    }

    // ── Dimension sync ──────────────────────────────────────────────────────

    private void sendDimensionDraft(ServerPlayerEntity player, com.cheeseguard.data.DimensionConfig draft) {
        JsonObject o = new JsonObject();
        o.addProperty("displayName", draft.getDisplayName());
        o.addProperty("genType", draft.getGenType().name());
        o.addProperty("genLabel", draft.getGenType().label);
        o.addProperty("genIcon", draft.getGenType().icon);
        o.addProperty("seed", draft.getSeed());
        o.addProperty("flatLayers", draft.getFlatLayers());
        o.addProperty("spawnX", draft.getSpawnX());
        o.addProperty("spawnY", draft.getSpawnY());
        o.addProperty("spawnZ", draft.getSpawnZ());
        o.addProperty("timeLocked", draft.isTimeLocked());
        o.addProperty("lockedTime", draft.getLockedTime());
        o.addProperty("weatherLocked", draft.isWeatherLocked());
        o.addProperty("lockedWeather", draft.getLockedWeather());
        o.addProperty("pvp", draft.isPvp());
        o.addProperty("mobSpawning", draft.isMobSpawning());
        o.addProperty("keepInventory", draft.isKeepInventory());
        o.addProperty("difficulty", draft.getDifficulty());
        o.addProperty("generateStructures", draft.isGenerateStructures());
        o.addProperty("commandBlocks", draft.isCommandBlocks());
        o.addProperty("enderChest", draft.isEnderChest());
        sync(player, "DIMENSION_DRAFT", o);
    }

    private void sendDimensionList(ServerPlayerEntity player) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        var allDims = dimensionManager.getAllDimensions();
        for (var dim : allDims) {
            JsonObject d = new JsonObject();
            d.addProperty("id", dim.getId());
            d.addProperty("worldId", dim.getWorldId());
            d.addProperty("displayName", dim.getDisplayName());
            d.addProperty("builtIn", dim.isBuiltIn());
            d.addProperty("custom", !dim.isBuiltIn());
            d.addProperty("genLabel", dim.getGenType().label);
            d.addProperty("icon", dim.getGenType().icon);
            // Count players in this dimension
            int pCount = 0;
            for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(dim.getWorldId())) {
                    pCount = w.getPlayers().size();
                    break;
                }
            }
            d.addProperty("players", pCount);
            d.addProperty("enabled", dim.isEnabled());
            d.addProperty("constructionMode", dim.isConstructionMode());
            arr.add(d);
        }
        o.add("dimensions", arr);
        o.addProperty("total", allDims.size());
        o.addProperty("playerWorld", player.getServerWorld().getRegistryKey().getValue().toString());
        sync(player, "DIMENSION_LIST", o);
    }

    private void sendDimensionEdit(ServerPlayerEntity player, String configId) {
        var cfg = dimensionManager.getConfig(configId);
        if (cfg == null) { sendDimensionList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("id", cfg.getId());
        o.addProperty("worldId", cfg.getWorldId());
        o.addProperty("displayName", cfg.getDisplayName());
        o.addProperty("genType", cfg.getGenType().name());
        o.addProperty("genLabel", cfg.getGenType().label);
        o.addProperty("seed", cfg.getSeed());
        o.addProperty("flatLayers", cfg.getFlatLayers());
        o.addProperty("spawnX", cfg.getSpawnX());
        o.addProperty("spawnY", cfg.getSpawnY());
        o.addProperty("spawnZ", cfg.getSpawnZ());
        o.addProperty("timeLocked", cfg.isTimeLocked());
        o.addProperty("lockedTime", cfg.getLockedTime());
        o.addProperty("weatherLocked", cfg.isWeatherLocked());
        o.addProperty("lockedWeather", cfg.getLockedWeather());
        o.addProperty("pvp", cfg.isPvp());
        o.addProperty("mobSpawning", cfg.isMobSpawning());
        o.addProperty("keepInventory", cfg.isKeepInventory());
        o.addProperty("difficulty", cfg.getDifficulty());
        o.addProperty("builtIn", cfg.isBuiltIn());
        // Can delete if not built-in and not overworld/nether
        boolean canDelete = !cfg.isBuiltIn()
            && !cfg.getWorldId().equals("minecraft:overworld")
            && !cfg.getWorldId().equals("minecraft:the_nether");
        o.addProperty("canDelete", canDelete);
        // Player count
        int pCount = 0;
        for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(cfg.getWorldId())) {
                pCount = w.getPlayers().size();
                break;
            }
        }
        o.addProperty("players", pCount);
        o.addProperty("enabled", cfg.isEnabled());
        o.addProperty("enabledMessage", cfg.getEnabledMessage());
        o.addProperty("whitelistCount", cfg.getWhitelist().size());
        o.addProperty("constructionMode", cfg.isConstructionMode());
        o.addProperty("builderCount", cfg.getConstructionBuilders().size());
        o.addProperty("commandBlocks", cfg.isCommandBlocks());
        o.addProperty("enderChest", cfg.isEnderChest());
        o.addProperty("generateStructures", cfg.isGenerateStructures());
        o.addProperty("cobblemonBlocked", cfg.isCobblemonBlocked());
        // Snapshot info
        o.addProperty("snapshotEnabled", cfg.isSnapshotEnabled());
        o.addProperty("snapshotResetTrigger", cfg.getSnapshotResetTrigger().name());
        o.addProperty("snapshotResetTriggerLabel", cfg.getSnapshotResetTrigger().label);
        o.addProperty("snapshotResetHour", cfg.getSnapshotResetHour());
        o.addProperty("lastResetAt", cfg.getLastResetAt());
        o.addProperty("snapshotPending", dimensionManager.hasPendingReset(cfg.getId()));
        long snapKb = dimensionManager.getSnapshotSizeKb(cfg.getId());
        o.addProperty("snapshotSizeKb", snapKb);
        sync(player, "DIMENSION_EDIT", o);
    }

    private void sendDimWhitelist(ServerPlayerEntity player, String configId) {
        var cfg = dimensionManager.getConfig(configId);
        if (cfg == null) { sendDimensionList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("dimId", cfg.getId());
        o.addProperty("dimName", cfg.getDisplayName());
        JsonArray arr = new JsonArray();
        Set<String> seen = new HashSet<>();
        // Online players first
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String uuid = p.getUuid().toString();
            seen.add(uuid);
            JsonObject po = new JsonObject();
            po.addProperty("uuid", uuid);
            po.addProperty("name", p.getName().getString());
            po.addProperty("whitelisted", cfg.isWhitelisted(uuid));
            po.addProperty("online", true);
            arr.add(po);
        }
        // Offline whitelisted players
        for (String uuid : cfg.getWhitelist()) {
            if (!seen.contains(uuid)) {
                JsonObject po = new JsonObject();
                po.addProperty("uuid", uuid);
                String name = uuid;
                try {
                    var profile = server.getUserCache().getByUuid(java.util.UUID.fromString(uuid));
                    if (profile.isPresent()) name = profile.get().getName();
                } catch (Exception ignored) {}
                po.addProperty("name", name);
                po.addProperty("whitelisted", true);
                po.addProperty("online", false);
                arr.add(po);
            }
        }
        o.add("players", arr);
        sync(player, "DIM_WHITELIST", o);
    }

    private void sendDimBuilders(ServerPlayerEntity player, String configId) {
        var cfg = dimensionManager.getConfig(configId);
        if (cfg == null) { sendDimensionList(player); return; }
        JsonObject o = new JsonObject();
        o.addProperty("dimId", cfg.getId());
        o.addProperty("dimName", cfg.getDisplayName());
        JsonArray arr = new JsonArray();
        Set<String> seen = new HashSet<>();
        // Online players first
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String uuid = p.getUuid().toString();
            seen.add(uuid);
            JsonObject po = new JsonObject();
            po.addProperty("uuid", uuid);
            po.addProperty("name", p.getName().getString());
            po.addProperty("builder", cfg.isConstructionBuilder(uuid));
            po.addProperty("online", true);
            arr.add(po);
        }
        // Offline builders
        for (String uuid : cfg.getConstructionBuilders()) {
            if (!seen.contains(uuid)) {
                JsonObject po = new JsonObject();
                po.addProperty("uuid", uuid);
                String name = uuid;
                try {
                    var profile = server.getUserCache().getByUuid(java.util.UUID.fromString(uuid));
                    if (profile.isPresent()) name = profile.get().getName();
                } catch (Exception ignored) {}
                po.addProperty("name", name);
                po.addProperty("builder", true);
                po.addProperty("online", false);
                arr.add(po);
            }
        }
        o.add("players", arr);
        sync(player, "DIM_BUILDERS", o);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ── New Event Editor Server Methods ─────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    /** Send full event data to client for the event editor */
    private void sendEventEditorData(ServerPlayerEntity player, String eventId) {
        sendEventEdit(player, eventId, "EVENT_EDITOR");
    }

    /** Helper: extract event ID from "actId:blockId" or "eventId:..." style params */
    private String extractActEventId(String p1) {
        // For act operations, p1 = "actId:..." - we need to find the event
        // Search all events for this act
        if (p1 == null || p1.isBlank()) return "";
        String firstPart = p1.split(":")[0];
        for (ServerEvent evt : eventManager.getEvents()) {
            if (evt.getAct(firstPart) != null) return evt.getId();
        }
        return firstPart; // fallback: treat as event ID
    }

    /** Save event data field from editor */
    private void handleEditorField(ServerPlayerEntity player, String p1) {
        // Format: fieldKey:eventId:value
        if (p1 == null || p1.isBlank()) return;
        String[] parts = p1.split(":", 3);
        if (parts.length < 3) return;
        String fieldKey = parts[0];
        String eventId  = parts[1];
        String value    = parts[2];
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) return;

        switch (fieldKey) {
            case "name"             -> evt.setName(value);
            case "description"      -> evt.setDescription(value);
            case "displayColor"     -> evt.setDisplayColor(value);
            case "mode"             -> { try { evt.setMode(ServerEvent.EventMode.valueOf(value)); } catch (Exception ignored) {} }
            case "trigger"          -> { try { evt.setTrigger(ServerEvent.TriggerType.valueOf(value)); } catch (Exception ignored) {} }
            case "triggerZone"      -> evt.setTriggerZoneId(value);
            case "announceDuration" -> { try { evt.setAnnounceDurationSeconds(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "countdown"        -> { try { evt.setCountdownSeconds(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "cooldown"         -> { try { evt.setCooldownMinutes(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "announceMessage"  -> evt.setAnnounceMessage(value);
            case "announceColor"    -> evt.setAnnounceColor(value);
            case "startMessage"     -> evt.setStartMessage(value);
            case "startColor"       -> evt.setStartColor(value);
            case "endMessage"       -> evt.setEndMessage(value);
            case "endColor"         -> evt.setEndColor(value);
            case "discordNotify"    -> evt.setDiscordNotify(Boolean.parseBoolean(value));
            case "bossbarEnabled"   -> evt.setBossbarEnabled(Boolean.parseBoolean(value));
            case "bossbarColor"     -> evt.setBossbarColor(value);
            case "scoreboardVisible"-> evt.setScoreboardVisible(Boolean.parseBoolean(value));
            case "minParticipants"  -> { try { evt.setMinParticipants(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "maxParticipants"  -> { try { evt.setMaxParticipants(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "teamMode"         -> { try { evt.setTeamMode(ServerEvent.TeamMode.valueOf(value)); } catch (Exception ignored) {} }
            case "teamSize"         -> { try { evt.setTeamSize(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "deathBehavior"    -> { try { evt.setDeathBehavior(ServerEvent.DeathBehavior.valueOf(value)); } catch (Exception ignored) {} }
            case "endOnAllDead"     -> evt.setEndOnAllDead(Boolean.parseBoolean(value));
            case "victoryCondition" -> { try { evt.setVictoryCondition(ServerEvent.VictoryCondition.valueOf(value)); } catch (Exception ignored) {} }
            case "victoryTarget"    -> { try { evt.setVictoryTargetScore(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "chainEvent"       -> evt.setChainEventId(value);
            case "exclusionGroup"   -> evt.setExclusionGroup(value);
            case "exclusionPriority"-> { try { evt.setExclusionPriority(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "returnMode"       -> evt.setReturnMode(value);
            case "saveInventory"    -> evt.setSaveInventory(Boolean.parseBoolean(value));
            case "saveTeamCobblemon"-> evt.setSaveTeamCobblemon(Boolean.parseBoolean(value));
            case "forceEmptyInventory" -> evt.setForceEmptyInventory(Boolean.parseBoolean(value));
            // Scoreboard
            case "sbPosition"   -> evt.setScoreboardPosition(value);
            case "sbCustomX"    -> { try { evt.setScoreboardCustomX(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "sbCustomY"    -> { try { evt.setScoreboardCustomY(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "sbMaxRows"    -> { try { evt.setScoreboardMaxRows(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "sbCompact"    -> evt.setScoreboardCompact(Boolean.parseBoolean(value));
            case "sbTitleColor" -> evt.setScoreboardTitleColor(value);
            case "sbTextColor"  -> evt.setScoreboardTextColor(value);
            case "sbBgColor"    -> evt.setScoreboardBgColor(value);
            case "sbBgOpacity"  -> { try { evt.setScoreboardBgOpacity(Integer.parseInt(value)); } catch (Exception ignored) {} }
            case "sbShowRank"   -> evt.setScoreboardShowRank(Boolean.parseBoolean(value));
            case "sbShowScore"  -> evt.setScoreboardShowScore(Boolean.parseBoolean(value));
            case "sbPrefix"     -> evt.setScoreboardPrefix(value);
        }
        eventManager.save();
    }

    private void saveEventEditorData(ServerPlayerEntity player, String p1) {
        // p1 = eventId — save and confirm
        ServerEvent evt = eventManager.getEvent(p1);
        if (evt == null) return;
        eventManager.save();
        player.sendMessage(Text.literal("✔ Événement sauvegardé").formatted(Formatting.GREEN), true);
        sendEventEditorData(player, p1);
    }

    private void handleActCreate(ServerPlayerEntity player, String eventId) {
        ServerEvent evt = eventManager.getEvent(eventId);
        if (evt == null) return;
        com.cheeseguard.data.EventAct act = new com.cheeseguard.data.EventAct();
        act.setName("Acte " + (evt.getActs().size() + 1));
        act.setDurationSeconds(300);
        act.setOrder(evt.getActs().size());
        evt.addAct(act);
        evt.setUseActSystem(true);
        eventManager.save();
    }

    private void handleActDelete(ServerPlayerEntity player, String p1) {
        // p1 = eventId:actId
        String[] parts = p1.split(":", 2);
        if (parts.length < 2) return;
        ServerEvent evt = eventManager.getEvent(parts[0]);
        if (evt == null) return;
        evt.removeAct(parts[1]);
        evt.ensureDefaultAct();
        eventManager.save();
    }

    private String extractEventIdFromField(String p1) {
        // Format: fieldKey:eventId:value
        if (p1 == null) return "";
        String[] parts = p1.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    private void handleTlBlockField(ServerPlayerEntity player, String actId, String blockId, String field, String value) {
        for (ServerEvent evt : eventManager.getEvents()) {
            com.cheeseguard.data.EventAct act = evt.getAct(actId);
            if (act != null) {
                com.cheeseguard.data.TimelineBlock block = act.getBlock(blockId);
                if (block != null) {
                    try {
                        switch (field) {
                            case "start"    -> block.setStartSeconds(Integer.parseInt(value));
                            case "duration" -> block.setDurationSeconds(Integer.parseInt(value));
                            case "repeat"   -> block.setRepeatIntervalSeconds(Integer.parseInt(value));
                            case "label"    -> block.setLabel(value);
                        }
                    } catch (Exception ignored) {}
                    eventManager.save();
                }
                return;
            }
        }
    }

    private void handleTlBlockAdd(ServerPlayerEntity player, String p1) {
        // p1 = actId:track:startSeconds
        String[] parts = p1.split(":", 3);
        if (parts.length < 3) return;
        String actId = parts[0], track = parts[1];
        int startSec = 0;
        try { startSec = Integer.parseInt(parts[2]); } catch (Exception ignored) {}

        // Find event containing this act
        for (ServerEvent evt : eventManager.getEvents()) {
            com.cheeseguard.data.EventAct act = evt.getAct(actId);
            if (act != null) {
                com.cheeseguard.data.TimelineBlock block = new com.cheeseguard.data.TimelineBlock();
                block.setTrack(track);
                block.setStartSeconds(startSec);
                block.setDurationSeconds(30); // default 30s
                act.addBlock(block);
                eventManager.save();
                return;
            }
        }
    }

    private void handleTlBlockDelete(ServerPlayerEntity player, String p1) {
        // p1 = actId:blockId
        String[] parts = p1.split(":", 2);
        if (parts.length < 2) return;
        String actId = parts[0], blockId = parts[1];
        for (ServerEvent evt : eventManager.getEvents()) {
            com.cheeseguard.data.EventAct act = evt.getAct(actId);
            if (act != null) {
                act.removeBlock(blockId);
                eventManager.save();
                return;
            }
        }
    }

    private void handleTlBlockMove(ServerPlayerEntity player, String p1) {
        // p1 = blockId:newStart:newTrack (from tl_block_move action)
        String[] outer = p1.split(":", 2);
        if (outer.length < 2) return;
        String blockId = outer[0];
        String[] inner = outer[1].split(":", 2);
        int newStart = 0;
        try { newStart = Integer.parseInt(inner[0]); } catch (Exception ignored) {}
        String newTrack = inner.length > 1 ? inner[1] : null;

        for (ServerEvent evt : eventManager.getEvents()) {
            for (com.cheeseguard.data.EventAct act : evt.getActs()) {
                com.cheeseguard.data.TimelineBlock block = act.getBlock(blockId);
                if (block != null) {
                    block.setStartSeconds(Math.max(0, newStart));
                    if (newTrack != null && !newTrack.isBlank()) block.setTrack(newTrack);
                    eventManager.save();
                    return;
                }
            }
        }
    }

    private void handleTlBlockResize(ServerPlayerEntity player, String p1) {
        // p1 = blockId:newDuration
        String[] parts = p1.split(":", 2);
        if (parts.length < 2) return;
        String blockId = parts[0];
        int newDur = 30;
        try { newDur = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        for (ServerEvent evt : eventManager.getEvents()) {
            for (com.cheeseguard.data.EventAct act : evt.getActs()) {
                com.cheeseguard.data.TimelineBlock block = act.getBlock(blockId);
                if (block != null) {
                    block.setDurationSeconds(Math.max(1, newDur));
                    eventManager.save();
                    return;
                }
            }
        }
    }

    private void handleTlBlockDuplicate(ServerPlayerEntity player, String p1) {
        // p1 = actId:blockId
        String[] parts = p1.split(":", 2);
        if (parts.length < 2) return;
        String actId = parts[0], blockId = parts[1];
        for (ServerEvent evt : eventManager.getEvents()) {
            com.cheeseguard.data.EventAct act = evt.getAct(actId);
            if (act != null) {
                com.cheeseguard.data.TimelineBlock orig = act.getBlock(blockId);
                if (orig != null) {
                    // Clone via JSON
                    com.cheeseguard.data.TimelineBlock copy = com.cheeseguard.data.TimelineBlock.fromJson(orig.toJson());
                    // Give new ID
                    copy = new com.cheeseguard.data.TimelineBlock();
                    copy.setTrack(orig.getTrack());
                    copy.setEffectType(orig.getEffectType());
                    copy.setStartSeconds(orig.getStartSeconds() + orig.getDurationSeconds() + 5);
                    copy.setDurationSeconds(orig.getDurationSeconds());
                    copy.setProperties(orig.getProperties().deepCopy());
                    copy.setLabel(orig.getLabel() + " (copie)");
                    act.addBlock(copy);
                    eventManager.save();
                }
                return;
            }
        }
    }

    private void handleTlBlockPropSet(ServerPlayerEntity player, String p1) {
        // p1 = actId:blockId:propKey:value
        String[] parts = p1.split(":", 4);
        if (parts.length < 4) return;
        String actId = parts[0], blockId = parts[1], propKey = parts[2], value = parts[3];
        for (ServerEvent evt : eventManager.getEvents()) {
            com.cheeseguard.data.EventAct act = evt.getAct(actId);
            if (act != null) {
                com.cheeseguard.data.TimelineBlock block = act.getBlock(blockId);
                if (block != null) {
                    block.setProp(propKey, value);
                    eventManager.save();
                }
                return;
            }
        }
    }

    // ── Chunk zone notifications ──────────────────────────────────────────────

    private void tickChunkZoneNotifications(MinecraftServer srv) {
        chunkCheckTick++;
        if (chunkCheckTick % 40 != 0) return; // every 2 seconds
        if (chunkSetStorage == null) return;

        Map<String, ChunkSet> allSets = chunkSetStorage.getAllSets();
        if (allSets.isEmpty()) return;

        // Pre-compute: for each set, build a set of affected region keys "setName:rX:rZ"
        // A region is 512x512 blocks → chunkX>>5, chunkZ>>5 gives region coords
        Map<String, Set<String>> setRegions = new HashMap<>();
        for (Map.Entry<String, ChunkSet> setEntry : allSets.entrySet()) {
            String setName = setEntry.getKey();
            Set<ChunkCalculator.RegionCoord> regions = ChunkCalculator.getAffectedRegions(
                ChunkCalculator.getAffectedChunks(setEntry.getValue())
            );
            Set<String> keys = new HashSet<>();
            for (ChunkCalculator.RegionCoord r : regions) {
                keys.add(setName + ":" + r.x + ":" + r.z);
            }
            setRegions.put(setName, keys);
        }

        for (ServerPlayerEntity player : srv.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            // Region the player is currently in (one region = 32×32 chunks = 512×512 blocks)
            int playerRegionX = (int) Math.floor(player.getX()) >> 9; // block >> 4 (chunk) >> 5 (region) = >> 9
            int playerRegionZ = (int) Math.floor(player.getZ()) >> 9;

            Set<String> currentZones = new HashSet<>();
            for (Map.Entry<String, Set<String>> e : setRegions.entrySet()) {
                String setName = e.getKey();
                String regionKey = setName + ":" + playerRegionX + ":" + playerRegionZ;
                if (e.getValue().contains(regionKey)) {
                    currentZones.add(regionKey);
                }
            }

            Set<String> previousZones = playerChunkZones.computeIfAbsent(uuid, k -> new HashSet<>());

            // Entered regions
            for (String key : currentZones) {
                if (!previousZones.contains(key)) {
                    String setName = key.split(":")[0];
                    player.sendMessage(
                        net.minecraft.text.Text.literal(
                            "⚠ Vous entrez dans une région du set \"" + setName +
                            "\" pouvant être régénérée par l'administrateur. Ne construisez pas votre base ici !")
                            .formatted(net.minecraft.util.Formatting.GOLD),
                        false
                    );
                }
            }

            // Exited regions
            for (String key : previousZones) {
                if (!currentZones.contains(key)) {
                    String setName = key.split(":")[0];
                    player.sendMessage(
                        net.minecraft.text.Text.literal(
                            "✓ Vous quittez la zone régénérable \"" + setName + "\".")
                            .formatted(net.minecraft.util.Formatting.GRAY),
                        false
                    );
                }
            }

            playerChunkZones.put(uuid, currentZones);
        }
    }

}
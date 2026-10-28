package com.cheeseitems.morpho;

import com.cheeseitems.network.MorphoCodexSyncS2CPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glue between the persistent {@link MorphoCodex} and {@link MorphoCatalog}.
 *
 * Toggles drive the {@code cshift} command directly — there is no endurance/drain
 * mechanic per morpho. The catalog is the single source of truth for species/form/shiny.
 */
public class MorphoManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");

    private final MorphoCodex codex;
    /** Tracks players currently transformed via the toggle, so the next press reverts. */
    private final java.util.Map<UUID, String> transformedBy = new ConcurrentHashMap<>();

    public MorphoManager(MorphoCodex codex) {
        this.codex = codex;
    }

    public MorphoCodex getCodex() { return codex; }

    /**
     * Add a catalog entry to the player's codex (no item to consume).
     * Returns true if added, false if already present.
     */
    public boolean addToCodex(ServerPlayerEntity player, MorphoEntry entry) {
        if (codex.hasInCodex(player.getUuid(), entry.getId())) return false;
        codex.addToCodex(player.getUuid(),
            new MorphoCodexEntry(entry.getId(), entry.getSpecies(), entry.getForm(), entry.isShiny(),
                System.currentTimeMillis()));
        sendSync(player);
        return true;
    }

    public void setActive(ServerPlayerEntity player, String morphoId) {
        codex.setActive(player.getUuid(), morphoId);
        sendSync(player);
    }

    /**
     * Build and send the codex sync payload. Display titles come from the catalog
     * so renames in the dashboard propagate without restart.
     */
    public void sendSync(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        MorphoCatalog catalog = MorphoCatalog.getInstance();

        // Prune codex entries whose catalog ID no longer exists. The morpho was
        // deleted from the dashboard catalog, so it has no commands, no image, and
        // no purpose — keeping the orphan would only confuse the GUI.
        java.util.List<String> orphans = new java.util.ArrayList<>();
        for (MorphoCodexEntry e : codex.getEntries(uuid)) {
            if (catalog.get(e.getItemId()) == null) orphans.add(e.getItemId());
        }
        for (String id : orphans) {
            codex.removeFromCodex(uuid, id);
            LOGGER.info("[CheeseItems] Pruned orphan morpho '{}' from {}'s codex (no catalog entry)",
                id, player.getName().getString());
        }

        JsonObject root = new JsonObject();
        String active = codex.getActive(uuid);
        root.addProperty("active", active == null ? "" : active);
        // Tell the client which port CheeseDash listens on so it can fetch catalog images.
        try {
            int port = com.cheesedash.CheeseDashMod.INSTANCE.getConfig().getPort();
            root.addProperty("cheesedashPort", port);
        } catch (Exception ignored) {}
        // Wallet balance (premium currency) — pulled from CheeseStore via reflection.
        // -1 means CheeseStore is not loaded; the GUI hides the line in that case.
        long balance = com.cheeseitems.integration.CheeseStoreHook.getBalance(uuid);
        root.addProperty("balance", balance);
        String currencyName = com.cheeseitems.integration.CheeseStoreHook.getCurrencyName();
        if (currencyName != null && !currencyName.isEmpty()) {
            root.addProperty("currencyName", currencyName);
        }
        JsonArray arr = new JsonArray();
        for (MorphoCodexEntry e : codex.getEntries(uuid)) {
            MorphoEntry catEntry = catalog.get(e.getItemId());
            if (catEntry == null) continue; // safety: orphans were pruned above
            JsonObject o = new JsonObject();
            o.addProperty("itemId", e.getItemId()); // morphoId — kept name for backward compat
            o.addProperty("species", e.getSpecies());
            o.addProperty("form", e.getForm());
            o.addProperty("shiny", e.isShiny());
            String displayName = (catEntry.getTitle() != null && !catEntry.getTitle().isEmpty())
                ? catEntry.getTitle() : e.getSpecies();
            o.addProperty("displayName", displayName == null ? "" : displayName);
            o.addProperty("hasImage", catEntry.hasImage());
            o.addProperty("legendary", catEntry.isLegendary());
            o.addProperty("terrestrial", catEntry.isTerrestrial());
            o.addProperty("aquatic", catEntry.isAquatic());
            o.addProperty("aerial", catEntry.isAerial());
            arr.add(o);
        }
        root.add("entries", arr);
        try {
            ServerPlayNetworking.send(player, new MorphoCodexSyncS2CPayload(root.toString()));
        } catch (Exception ex) {
            LOGGER.warn("[CheeseItems] Failed to send morpho codex sync to {}: {}",
                player.getName().getString(), ex.getMessage());
        }
    }

    /**
     * Toggle the active morpho transformation. First press transforms the player into
     * the species; second press reverts. Driven entirely by {@code cshift transform}
     * and {@code cshift revert} via the server console.
     */
    public void handleToggle(ServerPlayerEntity player) {
        String activeId = codex.getActive(player.getUuid());
        if (activeId == null) {
            player.sendMessage(Text.translatable("cheeseitems.morpho.no_active").formatted(Formatting.YELLOW), true);
            return;
        }
        MorphoEntry entry = MorphoCatalog.getInstance().get(activeId);
        if (entry == null || entry.getSpecies() == null || entry.getSpecies().isEmpty()) {
            player.sendMessage(Text.translatable("cheeseitems.morpho.invalid_active").formatted(Formatting.RED), true);
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (transformedBy.containsKey(player.getUuid())) {
            doRevert(server, player, entry);
        } else {
            doTransform(server, player, entry);
        }
    }

    /** Forced revert from the endurance tracker when the pool is depleted. */
    public void forceRevert(ServerPlayerEntity player, String reason) {
        String activeId = transformedBy.get(player.getUuid());
        if (activeId == null) return;
        MorphoEntry entry = MorphoCatalog.getInstance().get(activeId);
        if (entry == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        doRevert(server, player, entry);
    }

    private void doTransform(MinecraftServer server, ServerPlayerEntity player, MorphoEntry entry) {
        String name = player.getName().getString();

        // 1. cshift transform <player> <species> [form] [shiny]
        StringBuilder cmd = new StringBuilder("cshift transform ")
            .append(name).append(' ').append(entry.getSpecies());
        if (entry.getForm() != null && !entry.getForm().isEmpty()) {
            cmd.append(' ').append(entry.getForm());
        }
        if (entry.isShiny()) cmd.append(" shiny");
        execute(server, cmd.toString());

        // 2. Extra commands defined on the catalog entry.
        for (String c : entry.getCommandsOnTransform()) {
            if (c == null || c.isEmpty()) continue;
            execute(server, c.replace("{player}", name));
        }

        // 3. Endurance pool drain (uses the catalog's resolved settings — global default unless overridden).
        MorphoEnduranceTracker.getInstance().start(player, entry);

        transformedBy.put(player.getUuid(), entry.getId());
        String label = (entry.getTitle() == null || entry.getTitle().isEmpty())
            ? entry.getSpecies() : entry.getTitle();
        player.sendMessage(Text.translatable("cheeseitems.morpho.transformed", label)
            .formatted(Formatting.GREEN), true);
    }

    private void doRevert(MinecraftServer server, ServerPlayerEntity player, MorphoEntry entry) {
        String name = player.getName().getString();

        // 1. cshift revert.
        execute(server, "cshift revert " + name);

        // 2. Extra revert commands.
        for (String c : entry.getCommandsOnRevert()) {
            if (c == null || c.isEmpty()) continue;
            execute(server, c.replace("{player}", name));
        }

        // 3. Stop draining (regen takes over until the pool is full).
        MorphoEnduranceTracker.getInstance().stop(player.getUuid());

        transformedBy.remove(player.getUuid());
        player.sendMessage(Text.translatable("cheeseitems.morpho.reverted").formatted(Formatting.GRAY), true);
    }

    private static void execute(MinecraftServer server, String cmd) {
        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
        } catch (Exception e) {
            LOGGER.warn("[CheeseItems] cshift command failed: {} ({})", cmd, e.getMessage());
        }
    }

    /** Called once for each connecting player so the codex reaches the client at login. */
    public void onPlayerJoin(ServerPlayerEntity player, MinecraftServer server) {
        sendSync(player);
    }
}

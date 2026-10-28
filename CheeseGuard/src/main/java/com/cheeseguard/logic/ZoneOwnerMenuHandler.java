package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import com.cheeseguard.data.Member;
import com.cheeseguard.data.Region;
import com.cheeseguard.network.ZoneMenuDataS2C;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side logic for the zone-owner client menu (opened via the K keybind).
 *
 * Resolves the player's current zone, validates ownership, processes member /
 * ownership / flag actions, and pushes refreshed snapshots back to the client.
 */
public final class ZoneOwnerMenuHandler {

    private ZoneOwnerMenuHandler() {}

    /** Subset of flags an owner is allowed to toggle from the in-game menu. */
    private static final Flag[] OWNER_TOGGLEABLE = { Flag.PVP, Flag.MOB_SPAWNING };

    // ── Open request ────────────────────────────────────────────────────────

    public static void onOpenRequest(ServerPlayerEntity player) {
        Region zone = findOwnedZoneAt(player);
        if (zone == null) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.not_owner")
                .formatted(Formatting.RED), true);
            return;
        }
        sendSnapshot(player, zone);
    }

    // ── Action dispatch ─────────────────────────────────────────────────────

    public static void onAction(ServerPlayerEntity player, String zoneId, String action, String target) {
        if (zoneId == null || action == null) return;
        var storage = CheeseGuardMod.getInstance().getStorage();
        Region zone = storage.getRegion(zoneId);
        if (zone == null) {
            sendClose(player);
            return;
        }
        // Re-validate ownership at every action — the client cannot be trusted.
        if (zone.getOwner() == null || !zone.getOwner().getUuid().equals(player.getUuid())) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.not_owner")
                .formatted(Formatting.RED), true);
            sendClose(player);
            return;
        }

        switch (action) {
            case "add"        -> doAddMember(player, zone, target);
            case "remove"     -> doRemoveMember(player, zone, target);
            case "transfer"   -> doTransferOwner(player, zone, target);
            case "rename"     -> doRename(player, zone, target);
            case "toggle_pvp" -> doToggleFlag(player, zone, Flag.PVP);
            case "toggle_mob" -> doToggleFlag(player, zone, Flag.MOB_SPAWNING);
            case "leave"      -> doLeave(player, zone);
            default -> {
                CheeseGuardMod.LOGGER.warn("[CheeseGuard] Unknown zone-menu action '{}' from {}",
                    action, player.getName().getString());
                return;
            }
        }
        storage.save();
        // Re-send unless ownership was abandoned (zone has no owner now).
        if (zone.getOwner() != null && zone.getOwner().getUuid().equals(player.getUuid())) {
            sendSnapshot(player, zone);
        } else {
            sendClose(player);
        }
    }

    // ── Action implementations ──────────────────────────────────────────────

    private static void doAddMember(ServerPlayerEntity player, Region zone, String name) {
        if (name == null || name.isBlank()) return;
        MinecraftServer server = player.getServer();
        if (server == null || server.getUserCache() == null) return;
        Optional<GameProfile> profOpt = server.getUserCache().findByName(name.trim());
        if (profOpt.isEmpty()) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.player_not_found", name)
                .formatted(Formatting.RED), false);
            return;
        }
        GameProfile prof = profOpt.get();
        if (prof.getId().equals(player.getUuid())) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.cant_add_self")
                .formatted(Formatting.YELLOW), false);
            return;
        }
        if (zone.isMember(prof.getId())) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.already_member", prof.getName())
                .formatted(Formatting.YELLOW), false);
            return;
        }
        zone.addMember(new Member(prof.getId(), prof.getName(), Member.Role.MEMBER));
        player.sendMessage(Text.translatable("cheeseguard.zone_menu.member_added", prof.getName())
            .formatted(Formatting.GREEN), false);
    }

    private static void doRemoveMember(ServerPlayerEntity player, Region zone, String uuidStr) {
        if (uuidStr == null || uuidStr.isBlank()) return;
        UUID uuid;
        try { uuid = UUID.fromString(uuidStr); }
        catch (IllegalArgumentException e) { return; }
        if (zone.getOwner() != null && zone.getOwner().getUuid().equals(uuid)) {
            // Refuse to "remove" the owner via this path; they must transfer or leave.
            return;
        }
        // Find the member's display name before removing for a nicer message.
        String removedName = zone.getMembers().stream()
            .filter(m -> m.getUuid().equals(uuid))
            .map(Member::getName).findFirst().orElse(uuidStr);
        zone.removeMember(uuid);
        player.sendMessage(Text.translatable("cheeseguard.zone_menu.member_removed", removedName)
            .formatted(Formatting.GREEN), false);
    }

    private static void doTransferOwner(ServerPlayerEntity player, Region zone, String name) {
        if (name == null || name.isBlank()) return;
        MinecraftServer server = player.getServer();
        if (server == null || server.getUserCache() == null) return;
        Optional<GameProfile> profOpt = server.getUserCache().findByName(name.trim());
        if (profOpt.isEmpty()) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.player_not_found", name)
                .formatted(Formatting.RED), false);
            return;
        }
        GameProfile prof = profOpt.get();
        if (prof.getId().equals(player.getUuid())) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.cant_transfer_self")
                .formatted(Formatting.YELLOW), false);
            return;
        }
        zone.transferOwnership(new Member(prof.getId(), prof.getName(), Member.Role.OWNER));
        player.sendMessage(Text.translatable("cheeseguard.zone_menu.owner_transferred", prof.getName())
            .formatted(Formatting.GOLD), false);
        // Notify the new owner if they're online.
        ServerPlayerEntity newOwnerPlayer = server.getPlayerManager().getPlayer(prof.getId());
        if (newOwnerPlayer != null) {
            newOwnerPlayer.sendMessage(
                Text.translatable("cheeseguard.zone_menu.you_received_zone", zone.getName())
                    .formatted(Formatting.GOLD), false);
        }
    }

    private static void doRename(ServerPlayerEntity player, Region zone, String newName) {
        if (newName == null) return;
        String trimmed = newName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.bad_name")
                .formatted(Formatting.RED), false);
            return;
        }
        zone.setName(trimmed);
        player.sendMessage(Text.translatable("cheeseguard.zone_menu.renamed", trimmed)
            .formatted(Formatting.GREEN), false);
    }

    private static void doToggleFlag(ServerPlayerEntity player, Region zone, Flag flag) {
        Boolean current = zone.getFlag(flag);
        // Cycle: null (inherit) → true → false → null
        Boolean next;
        if (current == null) next = Boolean.TRUE;
        else if (current) next = Boolean.FALSE;
        else next = null;
        zone.setFlag(flag, next);
        String state = (next == null) ? "inherit" : (next ? "allow" : "deny");
        player.sendMessage(Text.translatable("cheeseguard.zone_menu.flag_toggled",
            flag.name(), state).formatted(Formatting.GREEN), false);
    }

    private static void doLeave(ServerPlayerEntity player, Region zone) {
        zone.removeOwner();
        player.sendMessage(Text.translatable("cheeseguard.zone_menu.left_zone", zone.getName())
            .formatted(Formatting.YELLOW), false);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Find the highest-priority region containing the player where the player is owner.
     * Returns null if there is no such zone at the player's current position.
     */
    public static Region findOwnedZoneAt(ServerPlayerEntity player) {
        var rm = CheeseGuardMod.getInstance().getRegionManager();
        String world = player.getServerWorld().getRegistryKey().getValue().toString();
        var pos = player.getBlockPos();
        List<Region> regions = rm.getRegionsAt(world, pos.getX(), pos.getY(), pos.getZ());
        UUID uuid = player.getUuid();
        for (Region r : regions) {
            Member o = r.getOwner();
            if (o != null && o.getUuid().equals(uuid)) return r;
        }
        return null;
    }

    private static void sendSnapshot(ServerPlayerEntity player, Region zone) {
        if (!ServerPlayNetworking.canSend(player, ZoneMenuDataS2C.ID)) {
            // Client doesn't have CheeseGuard installed — fall back to a chat hint.
            player.sendMessage(Text.translatable("cheeseguard.zone_menu.no_client_mod")
                .formatted(Formatting.RED), false);
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("zoneId", zone.getId());
        root.addProperty("name", zone.getName());
        root.addProperty("world", zone.getWorld());
        root.addProperty("size", (zone.getMaxX() - zone.getMinX() + 1)
            + "x" + (zone.getMaxZ() - zone.getMinZ() + 1)
            + "x" + (zone.getMaxY() - zone.getMinY() + 1));

        JsonObject owner = new JsonObject();
        if (zone.getOwner() != null) {
            owner.addProperty("uuid", zone.getOwner().getUuid().toString());
            owner.addProperty("name", zone.getOwner().getName());
        }
        root.add("owner", owner);

        JsonArray members = new JsonArray();
        for (Member m : zone.getMembers()) {
            // Skip the owner if they accidentally appear in the members list.
            if (zone.getOwner() != null && zone.getOwner().getUuid().equals(m.getUuid())) continue;
            JsonObject mo = new JsonObject();
            mo.addProperty("uuid", m.getUuid().toString());
            mo.addProperty("name", m.getName());
            members.add(mo);
        }
        root.add("members", members);

        JsonObject flags = new JsonObject();
        for (Flag f : OWNER_TOGGLEABLE) {
            Boolean v = zone.getFlag(f);
            // null → "inherit", true → "allow", false → "deny"
            flags.addProperty(f.name(), v == null ? "inherit" : (v ? "allow" : "deny"));
        }
        root.add("flags", flags);

        ServerPlayNetworking.send(player, new ZoneMenuDataS2C(root.toString()));
    }

    private static void sendClose(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, ZoneMenuDataS2C.ID)) return;
        ServerPlayNetworking.send(player, new ZoneMenuDataS2C(""));
    }
}

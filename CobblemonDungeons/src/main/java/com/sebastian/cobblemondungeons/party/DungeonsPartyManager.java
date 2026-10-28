package com.sebastian.cobblemondungeons.party;

import com.cobblemon.mod.common.Cobblemon;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import com.sebastian.cobblemondungeons.gui.DungeonScreenHandler;

import java.util.*;

public class DungeonsPartyManager {
    public static final int MAX_PARTY_SIZE = 5;
    private static final Map<UUID, DungeonsParty> playerToDungeonsParty = new HashMap<>();
    private static final Map<UUID, Set<UUID>> invites = new HashMap<>(); // invited -> set of inviters
    private static final Set<UUID> searchingForGroup = new HashSet<>();

    public static boolean hasPokemon(ServerPlayerEntity player) {
        try {
            return !Cobblemon.INSTANCE.getStorage().getParty(player).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean setSearchingForGroup(UUID playerUuid, boolean searching, MinecraftServer server) {
        if (searching) {
            if (server != null) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
                if (player != null && !hasPokemon(player)) {
                    player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
                    return false;
                }
            }
            searchingForGroup.add(playerUuid);
        } else {
            searchingForGroup.remove(playerUuid);
        }
        if (server != null) notifySearchingUpdate(server);
        return true;
    }

    public static void notifySearchingUpdate(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.currentScreenHandler instanceof DungeonScreenHandler handler) {
                DungeonScreenHandler.ViewMode mode = handler.getMode();
                if (mode == DungeonScreenHandler.ViewMode.INVITE_PLAYERS || mode == DungeonScreenHandler.ViewMode.INVITES) {
                    handler.refreshInventory(player);
                }
            }
        }
    }

    public static boolean isSearchingForGroup(UUID player) {
        return searchingForGroup.contains(player);
    }

    public static Set<UUID> getSearchingPlayers() {
        return Collections.unmodifiableSet(searchingForGroup);
    }

    public static DungeonsParty createParty(UUID leaderUuid, MinecraftServer server) {
        if (playerToDungeonsParty.containsKey(leaderUuid)) return null;

        if (server != null) {
            ServerPlayerEntity leader = server.getPlayerManager().getPlayer(leaderUuid);
            if (leader != null && !hasPokemon(leader)) {
                leader.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
                return null;
            }
        }

        searchingForGroup.remove(leaderUuid);
        DungeonsParty party = new DungeonsParty(leaderUuid);
        playerToDungeonsParty.put(leaderUuid, party);
        if (server != null) notifySearchingUpdate(server);
        return party;
    }

    public static DungeonsParty getParty(UUID player) {
        return playerToDungeonsParty.get(player);
    }

    public static boolean invitePlayer(UUID inviterUuid, UUID invitedUuid, MinecraftServer server) {
        if (!playerToDungeonsParty.containsKey(inviterUuid)) {
            if (createParty(inviterUuid, server) == null) {
                // If createParty failed (likely due to no pokemon), stop here
                return false;
            }
            ServerPlayerEntity inviterPlayer = server != null ? server.getPlayerManager().getPlayer(inviterUuid) : null;
            if (inviterPlayer != null) {
                inviterPlayer.sendMessage(Text.translatable("cobblemondungeons.party.created").formatted(net.minecraft.util.Formatting.GREEN));
            }
        } else {
            DungeonsParty party = playerToDungeonsParty.get(inviterUuid);
            if (party != null && party.getMembers().size() >= MAX_PARTY_SIZE) {
                ServerPlayerEntity inviterPlayer = server != null ? server.getPlayerManager().getPlayer(inviterUuid) : null;
                if (inviterPlayer != null) {
                    inviterPlayer.sendMessage(Text.translatable("cobblemondungeons.party.full").formatted(net.minecraft.util.Formatting.RED));
                }
                return false;
            }
        }
        invites.computeIfAbsent(invitedUuid, k -> new HashSet<>()).add(inviterUuid);
        if (server != null) {
            notifySearchingUpdate(server);
            ServerPlayerEntity invited = server.getPlayerManager().getPlayer(invitedUuid);
            ServerPlayerEntity inviter = server.getPlayerManager().getPlayer(inviterUuid);
            if (invited != null && inviter != null) {
                Text message = Text.translatable("cobblemondungeons.party.invite_received", inviter.getName().getString()).formatted(net.minecraft.util.Formatting.GREEN)
                    .append(Text.translatable("cobblemondungeons.party.invite_click").formatted(net.minecraft.util.Formatting.YELLOW, net.minecraft.util.Formatting.UNDERLINE)
                        .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/DungeonsParty join " + inviter.getName().getString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("cobblemondungeons.party.invite_hover", inviter.getName().getString())))));
                invited.sendMessage(message);
            }
        }
        return true;
    }

    public static Set<UUID> getInvites(UUID invited) {
        return invites.getOrDefault(invited, Collections.emptySet());
    }

    public static boolean joinParty(ServerPlayerEntity invitedPlayer) {
        if (!hasPokemon(invitedPlayer)) {
            invitedPlayer.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
            return false;
        }
        UUID invited = invitedPlayer.getUuid();
        Set<UUID> inviters = invites.get(invited);
        if (inviters != null && !inviters.isEmpty()) {
            // Join the first available invite
            UUID inviter = inviters.iterator().next();
            return joinSpecificParty(invitedPlayer, inviter);
        }
        return false;
    }

    public static boolean joinSpecificParty(ServerPlayerEntity invitedPlayer, UUID inviter) {
        if (!hasPokemon(invitedPlayer)) {
            invitedPlayer.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
            return false;
        }

        UUID invited = invitedPlayer.getUuid();
        Set<UUID> inviters = invites.get(invited);
        if (inviters != null && inviters.contains(inviter)) {
            DungeonsParty party = playerToDungeonsParty.get(inviter);
            if (party != null) {
                if (party.getMembers().size() >= MAX_PARTY_SIZE) {
                    invitedPlayer.sendMessage(Text.translatable("cobblemondungeons.party.is_full").formatted(net.minecraft.util.Formatting.RED));
                    return false;
                }
                inviters.remove(inviter);
                if (inviters.isEmpty()) invites.remove(invited);
                
                searchingForGroup.remove(invited);
                party.addMember(invited);
                playerToDungeonsParty.put(invited, party);

                // Notify all members to refresh their GUI
                notifyPartyUpdate(party, invitedPlayer.getServer());
                notifySearchingUpdate(invitedPlayer.getServer());
                return true;
            }
        }
        return false;
    }

    public static void declineInvite(UUID invited, UUID inviter, MinecraftServer server) {
        Set<UUID> inviters = invites.get(invited);
        if (inviters != null) {
            inviters.remove(inviter);
            if (inviters.isEmpty()) invites.remove(invited);
            if (server != null) notifySearchingUpdate(server);
        }
    }

    private static void notifyPartyUpdate(DungeonsParty party, MinecraftServer server) {
        for (UUID memberUuid : party.getMembers()) {
            ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUuid);
            if (member != null && member.currentScreenHandler instanceof DungeonScreenHandler handler) {
                handler.refreshInventory(member);
            }
        }
    }

    public static void leaveParty(UUID playerUuid, MinecraftServer server) {
        DungeonsParty party = playerToDungeonsParty.get(playerUuid);
        if (party == null) return;

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        String playerName = player != null ? player.getName().getString() : "Someone";

        playerToDungeonsParty.remove(playerUuid);
        party.removeMember(playerUuid);

        if (player != null) {
            player.sendMessage(Text.translatable("cobblemondungeons.party.left").formatted(net.minecraft.util.Formatting.YELLOW));
            if (player.currentScreenHandler instanceof DungeonScreenHandler handler) {
                handler.refreshInventory(player);
            }
        }

        if (party.getLeader().equals(playerUuid)) {
            // Leader left, disband the party
            Text disbandMessage = Text.translatable("cobblemondungeons.party.disbanded").formatted(net.minecraft.util.Formatting.RED);
            for (UUID memberUuid : party.getMembers()) {
                playerToDungeonsParty.remove(memberUuid);
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUuid);
                if (member != null) {
                    member.sendMessage(disbandMessage);
                    if (member.currentScreenHandler instanceof DungeonScreenHandler handler) {
                        handler.refreshInventory(member);
                    }
                }
            }
        } else if (!party.getMembers().isEmpty()) {
            // Member left, notify and refresh for others
            notifyPartyUpdate(party, server);

            Text message = Text.translatable("cobblemondungeons.party.member_left", playerName).formatted(net.minecraft.util.Formatting.YELLOW);
            for (UUID memberUuid : party.getMembers()) {
                ServerPlayerEntity member = server.getPlayerManager().getPlayer(memberUuid);
                if (member != null) {
                    member.sendMessage(message);
                }
            }
        }
    }
}

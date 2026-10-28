package com.cheesestore.cosmetic;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;

/**
 * Applies the equipped NAME_COLOR (recolours the player display name) and CHAT_TAG
 * (a coloured prefix shown before the name in chat, the tab list and above the head)
 * via SCOREBOARD TEAMS.
 *
 * <p>Scoreboard teams are the vanilla mechanism every name-rendering path already
 * respects — chat (including styled-chat, which renders the team-decorated display
 * name), the player list / tab, and the name-tag above the head all pick up a team's
 * {@code color} and {@code prefix}. By driving everything through a per-player team we
 * stay compatible with styled-chat without touching the chat pipeline ourselves.
 *
 * <p>Each online player gets a dedicated team named {@code cos_<uuidshort>} (kept under
 * the 16-char scoreboard team-name limit). The team carries:
 * <ul>
 *   <li>the {@link Formatting} colour resolved from the equipped NAME_COLOR's
 *       {@code color} param, applied with {@link Team#setColor};</li>
 *   <li>the {@link Text} prefix built from the equipped CHAT_TAG's {@code tag} param
 *       (plus a trailing space), coloured by that tag's {@code color} param, applied
 *       with {@link Team#setPrefix}.</li>
 * </ul>
 *
 * <p>{@link #refresh(ServerPlayerEntity)} is the single re-application entry point:
 * called on join and intended to be called by {@link EquipManager}/the GUI whenever the
 * equipped NAME_COLOR or CHAT_TAG changes. If neither is equipped the player's team is
 * removed entirely (neutral / vanilla rendering). On disconnect the player is removed
 * from the team and the now-empty team is deleted (cleanup).
 *
 * <p>Contract (do not change): {@code public static void init()} — idempotent, registers
 * its own Fabric join / disconnect events.
 */
public final class NameTagManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");

    private static final String TEAM_PREFIX = "cos_";

    private static volatile boolean initialised = false;

    private NameTagManager() {}

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        // Apply on join (after the player + their scoreboard are available).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (player != null) refresh(player);
        });

        // Cleanup on disconnect: drop the player from their team and delete it.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (player != null) cleanup(player);
        });

        LOGGER.info("[CheeseStore] NameTagManager initialised (NAME_COLOR + CHAT_TAG via scoreboard teams)");
    }

    /**
     * Re-apply the equipped NAME_COLOR / CHAT_TAG for {@code player}. Safe to call any
     * time the player's equipped cosmetics change. If neither a NAME_COLOR nor a CHAT_TAG
     * is equipped, the player's cosmetic team is removed so the name renders vanilla.
     */
    public static void refresh(ServerPlayerEntity player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        try {
            Scoreboard scoreboard = server.getScoreboard();
            UUID uuid = player.getUuid();

            Formatting color = resolveNameColor(uuid);
            Text prefix = resolveChatTag(uuid);

            // Nothing equipped → remove any existing cosmetic team and bail.
            if (color == null && prefix == null) {
                removeTeam(scoreboard, player);
                return;
            }

            Team team = getOrCreateTeam(scoreboard, player);

            // Colour: equipped NAME_COLOR, else reset to default white.
            team.setColor(color != null ? color : Formatting.RESET);

            // Prefix: equipped CHAT_TAG (with trailing space), else clear.
            team.setPrefix(prefix != null ? prefix : Text.empty());

            // Ensure the player is a member of *their* team (and only it).
            String memberName = player.getNameForScoreboard();
            if (scoreboard.getScoreHolderTeam(memberName) != team) {
                scoreboard.addScoreHolderToTeam(memberName, team);
            }
        } catch (Exception ex) {
            LOGGER.warn("[CheeseStore] NameTagManager.refresh failed for {}: {}",
                    player.getNameForScoreboard(), ex.toString());
        }
    }

    /** Remove the player from their cosmetic team and delete that team. */
    public static void cleanup(ServerPlayerEntity player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        try {
            removeTeam(server.getScoreboard(), player);
        } catch (Exception ex) {
            LOGGER.warn("[CheeseStore] NameTagManager.cleanup failed for {}: {}",
                    player.getNameForScoreboard(), ex.toString());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Formatting resolveNameColor(UUID uuid) {
        EquipManager equip = EquipManager.getInstance();
        CosmeticManager catalog = CosmeticManager.getInstance();
        if (equip == null || catalog == null) return null;

        String id = equip.getEquipped(uuid, "NAME_COLOR");
        if (id == null) return null;
        CosmeticEntry entry = catalog.get(id);
        if (entry == null || !entry.isEnabled()) return null;

        return parseColor(entry.param("color", null));
    }

    private static Text resolveChatTag(UUID uuid) {
        EquipManager equip = EquipManager.getInstance();
        CosmeticManager catalog = CosmeticManager.getInstance();
        if (equip == null || catalog == null) return null;

        String id = equip.getEquipped(uuid, "CHAT_TAG");
        if (id == null) return null;
        CosmeticEntry entry = catalog.get(id);
        if (entry == null || !entry.isEnabled()) return null;

        String tag = entry.param("tag", null);
        if (tag == null || tag.isBlank()) return null;

        // Trailing space so the tag sits cleanly before the name.
        Text label = Text.literal(tag + " ");
        Formatting tagColor = parseColor(entry.param("color", null));
        if (tagColor != null) {
            label = ((net.minecraft.text.MutableText) label).formatted(tagColor);
        }
        return label;
    }

    /** Parse a Minecraft colour name ("dark_blue", "gold", …) to a {@link Formatting}, or null. */
    private static Formatting parseColor(String name) {
        if (name == null || name.isBlank()) return null;
        Formatting f = Formatting.byName(name.trim().toLowerCase(Locale.ROOT));
        return (f != null && f.isColor()) ? f : null;
    }

    private static String teamName(ServerPlayerEntity player) {
        // Short, stable, <=16 chars: "cos_" (4) + first 12 hex of the UUID.
        String hex = player.getUuid().toString().replace("-", "");
        return TEAM_PREFIX + hex.substring(0, Math.min(12, hex.length()));
    }

    private static Team getOrCreateTeam(Scoreboard scoreboard, ServerPlayerEntity player) {
        String name = teamName(player);
        Team team = scoreboard.getTeam(name);
        if (team == null) {
            team = scoreboard.addTeam(name);
            // Don't let these cosmetic teams interfere with vanilla collision/visibility rules.
            team.setCollisionRule(AbstractTeam.CollisionRule.ALWAYS);
            team.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.ALWAYS);
        }
        return team;
    }

    private static void removeTeam(Scoreboard scoreboard, ServerPlayerEntity player) {
        Team team = scoreboard.getTeam(teamName(player));
        if (team == null) return;
        // Detach the member then delete the (now cosmetic-only) team.
        scoreboard.clearTeam(player.getNameForScoreboard());
        scoreboard.removeTeam(team);
    }
}

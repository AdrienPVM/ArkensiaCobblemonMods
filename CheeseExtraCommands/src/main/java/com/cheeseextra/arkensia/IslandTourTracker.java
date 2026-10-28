package com.cheeseextra.arkensia;

import com.cheeseextra.CheeseExtraMod;
import com.cheeseextra.network.IslandTourPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the Arkensia "tour des habitants" HUD.
 * Reads scoreboards each second and pushes a delta packet only when state changes.
 *
 * Quest state machine (jenny.npc.nbt dialogs):
 *   jenny_intro  : 0 = never spoke, 1 = quest taken, 2 = completed & rewarded
 *   spawn_tour   : count of NPCs met (0..26), incremented by each NPC's first-meet dialog
 *   {key}_met    : 0 or 1, set when player first talks to that NPC
 *
 * HUD shows when: jenny_intro >= 1 AND jenny_intro < 2 AND spawn_tour < 26
 */
public final class IslandTourTracker {

    /** key (used in "<key>_met" objective) → display name (alphabetical by display name). */
    public static final Map<String, String> NPCS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("alfred_main", "Alfred");
        m.put("bjorn",       "Bjorn");
        m.put("brick",       "Brick");
        m.put("chen",        "Chen");
        m.put("dante",       "Dante");
        m.put("dino",        "Dino");
        m.put("ectoplasma",  "Ectoplasma");
        m.put("finn",        "Finn");
        m.put("flint",       "Flint");
        m.put("joelle",      "Joelle");
        m.put("kai",         "Kai");
        m.put("lucius",      "Lucius");
        m.put("max",         "Max");
        m.put("metamorph",   "Metamorph");
        m.put("mira",        "Mira");
        m.put("nemo",        "Nemo");
        m.put("nina",        "Nina");
        m.put("primo",       "Primo");
        m.put("rosalia",     "Rosalia");
        m.put("selena",      "Selena");
        m.put("stella",      "Stella");
        m.put("sven",        "Sven");
        m.put("suzy",        "Suzy");
        m.put("violette",    "Violette");
        m.put("woody",       "Woody");
        m.put("zora",        "Zora");
        NPCS = java.util.Collections.unmodifiableMap(m);
    }
    public static final int TOTAL = NPCS.size(); // 26

    private static final int TICK_INTERVAL = 20; // 1 second
    private static int tickCounter = 0;

    /** Last payload sent per player, to skip resending unchanged state. */
    private record Snapshot(boolean show, int met, List<String> remaining) {}
    private static final Map<UUID, Snapshot> lastSent = new ConcurrentHashMap<>();

    private IslandTourTracker() {}

    /** Call from ServerTickEvents.END_SERVER_TICK. */
    public static void onServerTick(MinecraftServer server) {
        if (++tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;

        Scoreboard sb = server.getScoreboard();
        ScoreboardObjective intro = sb.getNullableObjective("jenny_intro");
        ScoreboardObjective tour  = sb.getNullableObjective("spawn_tour");

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            update(player, sb, intro, tour);
        }
    }

    /** Force an update for a single player (e.g. on join). Cached snapshot is cleared first. */
    public static void onPlayerJoin(ServerPlayerEntity player) {
        lastSent.remove(player.getUuid());
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Scoreboard sb = server.getScoreboard();
        update(player, sb, sb.getNullableObjective("jenny_intro"), sb.getNullableObjective("spawn_tour"));
    }

    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        lastSent.remove(player.getUuid());
    }

    private static void update(ServerPlayerEntity player, Scoreboard sb,
                               ScoreboardObjective intro, ScoreboardObjective tour) {
        int introVal = readScore(sb, player, intro);
        int tourVal  = readScore(sb, player, tour);

        boolean show = introVal >= 1 && introVal < 2 && tourVal < TOTAL;

        List<String> remaining;
        int met;
        if (show) {
            remaining = new ArrayList<>(TOTAL);
            for (Map.Entry<String, String> e : NPCS.entrySet()) {
                ScoreboardObjective obj = sb.getNullableObjective(e.getKey() + "_met");
                if (readScore(sb, player, obj) < 1) remaining.add(e.getValue());
            }
            met = TOTAL - remaining.size();
        } else {
            remaining = List.of();
            met = Math.min(tourVal, TOTAL);
        }

        Snapshot next = new Snapshot(show, met, remaining);
        Snapshot prev = lastSent.get(player.getUuid());
        if (prev != null && prev.show == next.show && prev.met == next.met && prev.remaining.equals(next.remaining)) {
            return; // unchanged → don't resend
        }
        lastSent.put(player.getUuid(), next);

        try {
            ServerPlayNetworking.send(player, new IslandTourPayload(show, met, TOTAL, remaining));
        } catch (Exception e) {
            CheeseExtraMod.LOGGER.debug("[CheeseExtra] Failed to send IslandTourPayload to {}: {}",
                player.getName().getString(), e.getMessage());
        }
    }

    private static int readScore(Scoreboard sb, ServerPlayerEntity player, ScoreboardObjective obj) {
        if (obj == null) return 0;
        ReadableScoreboardScore s = sb.getScore(player, obj);
        return s == null ? 0 : s.getScore();
    }
}

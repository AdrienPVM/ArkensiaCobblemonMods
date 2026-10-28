package com.cheeseguard.logic;

import com.cheeseguard.network.GuiSyncPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * Manages generic votes during events.
 * A vote can be:
 *  - PLAYER_VOTE   : vote to eliminate/choose a player
 *  - OPTION_VOTE   : list of text options
 *  - YESNO         : simple yes/no
 */
public class VoteManager {

    public enum VoteType { PLAYER_VOTE, OPTION_VOTE, YESNO }

    public static class ActiveVote {
        public final String voteId;
        public final String eventId;
        public final String question;
        public final VoteType type;
        public final List<String> options;       // text options or player names
        public final Map<UUID, String> votes = new HashMap<>(); // playerUUID -> choice
        public final long startedAt;
        public final int durationSeconds;
        public boolean closed = false;
        public String winnerId = null;

        public ActiveVote(String eventId, String voteId, String question, VoteType type,
                          List<String> options, int durationSeconds) {
            this.eventId = eventId;
            this.voteId = voteId;
            this.question = question;
            this.type = type;
            this.options = new ArrayList<>(options);
            this.startedAt = System.currentTimeMillis();
            this.durationSeconds = durationSeconds;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - startedAt >= (long)durationSeconds * 1000;
        }

        /** Map option -> vote count */
        public Map<String, Integer> tally() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String opt : options) counts.put(opt, 0);
            for (String v : votes.values()) counts.merge(v, 1, Integer::sum);
            return counts;
        }

        /** Determine the winner (most votes; random tie-break) */
        public String computeWinner() {
            Map<String, Integer> tally = tally();
            int max = tally.values().stream().mapToInt(v -> v).max().orElse(0);
            List<String> tied = new ArrayList<>();
            for (var e : tally.entrySet()) if (e.getValue() == max) tied.add(e.getKey());
            if (tied.isEmpty()) return null;
            return tied.get(new Random().nextInt(tied.size()));
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("voteId", voteId);
            o.addProperty("eventId", eventId);
            o.addProperty("question", question);
            o.addProperty("type", type.name());
            o.addProperty("remainingSecs", Math.max(0, durationSeconds - (int)((System.currentTimeMillis()-startedAt)/1000)));
            o.addProperty("closed", closed);
            o.addProperty("winnerId", winnerId != null ? winnerId : "");
            JsonArray opts = new JsonArray();
            Map<String, Integer> tally = tally();
            int total = Math.max(1, votes.size());
            for (String opt : options) {
                JsonObject oo = new JsonObject();
                oo.addProperty("id", opt);
                oo.addProperty("count", tally.getOrDefault(opt, 0));
                oo.addProperty("pct", (int)(100.0 * tally.getOrDefault(opt, 0) / total));
                opts.add(oo);
            }
            o.add("options", opts);
            o.addProperty("totalVotes", votes.size());
            return o;
        }
    }

    private final Map<String, ActiveVote> activeVotes = new HashMap<>(); // voteId -> vote
    private final Map<String, String> eventCurrentVote = new HashMap<>(); // eventId -> voteId
    private MinecraftServer server;

    public void init(MinecraftServer server) { this.server = server; }

    // ── Start a vote ──────────────────────────────────────────────────────────

    public ActiveVote startVote(String eventId, String question, VoteType type,
                                List<String> options, int durationSeconds,
                                Collection<ServerPlayerEntity> participants) {
        // Close any current vote for this event
        closeCurrentVote(eventId);

        String voteId = eventId + "_vote_" + System.currentTimeMillis();
        ActiveVote vote = new ActiveVote(eventId, voteId, question, type, options, durationSeconds);
        activeVotes.put(voteId, vote);
        eventCurrentVote.put(eventId, voteId);

        // Broadcast to all participants
        broadcastVoteState(vote, participants);

        return vote;
    }

    // ── Cast a vote ───────────────────────────────────────────────────────────

    public boolean castVote(String voteId, UUID playerUuid, String choice, Collection<ServerPlayerEntity> participants) {
        ActiveVote vote = activeVotes.get(voteId);
        if (vote == null || vote.closed) return false;
        if (!vote.options.contains(choice)) return false;
        vote.votes.put(playerUuid, choice);
        broadcastVoteState(vote, participants);
        return true;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    /**
     * Called every second during an active event.
     * Returns the concluded vote if one just finished, null otherwise.
     */
    public ActiveVote tick(String eventId, Collection<ServerPlayerEntity> participants) {
        String voteId = eventCurrentVote.get(eventId);
        if (voteId == null) return null;
        ActiveVote vote = activeVotes.get(voteId);
        if (vote == null || vote.closed) return null;

        // Broadcast update every 5s or when nearly done
        long elapsed = System.currentTimeMillis() - vote.startedAt;
        long remaining = vote.durationSeconds * 1000L - elapsed;
        if (remaining <= 0 || (elapsed % 5000 < 50)) {
            broadcastVoteState(vote, participants);
        }

        if (vote.isExpired()) {
            return concludeVote(vote, participants);
        }
        return null;
    }

    // ── Conclude ─────────────────────────────────────────────────────────────

    private ActiveVote concludeVote(ActiveVote vote, Collection<ServerPlayerEntity> participants) {
        vote.closed = true;
        vote.winnerId = vote.computeWinner();

        // Broadcast result
        for (ServerPlayerEntity p : participants) {
            p.sendMessage(Text.literal("═══ Résultat du vote ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
            Map<String, Integer> tally = vote.tally();
            for (var entry : tally.entrySet()) {
                String bar = "█".repeat(Math.min(20, entry.getValue() * 2));
                String line = entry.getValue() > 0 && entry.getKey().equals(vote.winnerId)
                    ? "§a§l▶ " + entry.getKey() + ": " + entry.getValue() + " vote(s) " + bar
                    : "§7  " + entry.getKey() + ": " + entry.getValue() + " vote(s) §8" + bar;
                p.sendMessage(Text.literal(line), false);
            }
            p.sendMessage(Text.literal("§7Résultat: §e§l" + (vote.winnerId != null ? vote.winnerId : "Égalité")), false);
        }
        broadcastVoteState(vote, participants);

        eventCurrentVote.remove(vote.eventId);
        return vote;
    }

    private void closeCurrentVote(String eventId) {
        String voteId = eventCurrentVote.get(eventId);
        if (voteId != null) {
            ActiveVote v = activeVotes.get(voteId);
            if (v != null) v.closed = true;
            eventCurrentVote.remove(eventId);
        }
    }

    // ── UI Sync ───────────────────────────────────────────────────────────────

    private void broadcastVoteState(ActiveVote vote, Collection<ServerPlayerEntity> participants) {
        if (server == null) return;
        JsonObject payload = vote.toJson();
        String json = payload.toString();
        for (ServerPlayerEntity p : participants) {
            if (ServerPlayNetworking.canSend(p, GuiSyncPayload.ID)) {
                ServerPlayNetworking.send(p, new GuiSyncPayload("VOTE_OVERLAY", json));
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public ActiveVote getVote(String voteId) { return activeVotes.get(voteId); }
    public ActiveVote getCurrentVote(String eventId) {
        String id = eventCurrentVote.get(eventId);
        return id != null ? activeVotes.get(id) : null;
    }
    public boolean hasActiveVote(String eventId) {
        return eventCurrentVote.containsKey(eventId) &&
               !activeVotes.getOrDefault(eventCurrentVote.get(eventId), new ActiveVote("","","",VoteType.YESNO,List.of(),0)).closed;
    }

    public void clearEvent(String eventId) {
        String voteId = eventCurrentVote.remove(eventId);
        if (voteId != null) activeVotes.remove(voteId);
    }
}

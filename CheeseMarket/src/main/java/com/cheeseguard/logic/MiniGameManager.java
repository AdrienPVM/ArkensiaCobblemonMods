package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.EventParticipant;
import com.cheeseguard.data.ServerEvent;
import com.google.gson.*;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages mini-game specific logic for events.
 * Each mini-game type has its own setup, tick, and cleanup behavior.
 */
public class MiniGameManager {

    // ── Mini-game types ──────────────────────────────────────────────────────

    public enum MiniGameType {
        // Combat
        DEATHMATCH("Deathmatch", "Dernier survivant", "⚔"),
        TEAM_BATTLE("Bataille equipe", "Equipe vs equipe", "⚔"),
        MOB_ARENA("Arene de mobs", "Survivre aux vagues", "\uD83D\uDC79"),

        // Construction
        SPEED_BUILD("Speed Build", "Construire le plus vite", "\uD83C\uDFD7"),
        BLOCK_PARTY("Block Party", "Placer le bon bloc", "\uD83C\uDFB2"),

        // Deplacement
        PARKOUR("Parkour", "Course a obstacles", "\uD83C\uDFC3"),
        RACE("Course", "Premier arrive", "\uD83C\uDFC1"),
        ELYTRA_RACE("Elytra Race", "Course en elytra", "\uD83E\uDD85"),
        SPLEEF("Spleef", "Detruire le sol", "\u2744"),

        // Collection
        SCAVENGER_HUNT("Chasse au tresor", "Trouver les objets", "\uD83D\uDD0E"),
        FISHING_CONTEST("Concours peche", "Pecher le plus", "\uD83C\uDFA3"),
        MINING_RACE("Course au minage", "Miner le plus", "\u26CF"),

        // Pokemon (Cobblemon)
        POKEMON_CATCH("Catch Contest", "Attraper le plus", "\uD83D\uDD34"),
        POKEMON_BATTLE("Tournoi Pokemon", "Batailles Pokemon", "\u26A1"),
        SHINY_HUNT("Shiny Hunt", "Trouver un shiny", "\u2728"),

        // Social
        HIDE_SEEK("Cache-cache", "Se cacher ou chercher", "\uD83D\uDC41"),
        KING_OF_HILL("Roi de la colline", "Rester au sommet", "\uD83D\uDC51"),

        // Puzzle
        TRIVIA("Quiz", "Repondre aux questions", "\u2753"),
        TREASURE_TRAIL("Piste au tresor", "Suivre les indices", "\uD83D\uDDFA"),

        // Custom
        CUSTOM("Custom", "Logique personnalisee", "\u2699");

        public final String displayName, description, icon;

        MiniGameType(String d, String desc, String i) {
            displayName = d; description = desc; icon = i;
        }
    }

    // ── Active mini-game state ───────────────────────────────────────────────

    private static class MiniGameState {
        MiniGameType type;
        String eventId;
        ZoneSnapshot snapshot;
        int waveNumber = 0;
        long lastWaveSpawn = 0;
        Set<UUID> eliminated = new HashSet<>();
        Map<UUID, Integer> zoneTimers = new HashMap<>(); // for KING_OF_HILL, STAY_IN_ZONE
        List<UUID> entityWaypoints = new ArrayList<>(); // tracked entities
        int triviaQuestionIndex = 0;

        MiniGameState(MiniGameType type, String eventId) {
            this.type = type;
            this.eventId = eventId;
        }
    }

    private final Map<String, MiniGameState> activeGames = new ConcurrentHashMap<>();
    private MinecraftServer server;

    // ── Queue system ─────────────────────────────────────────────────────────

    private final Map<String, List<UUID>> queues = new ConcurrentHashMap<>(); // eventId -> player queue

    public void init(MinecraftServer server) {
        this.server = server;
    }

    // ── Queue management ─────────────────────────────────────────────────────

    public boolean joinQueue(String eventId, UUID playerId) {
        queues.computeIfAbsent(eventId, k -> new ArrayList<>());
        List<UUID> queue = queues.get(eventId);
        if (queue.contains(playerId)) return false;
        queue.add(playerId);
        return true;
    }

    public void leaveQueue(String eventId, UUID playerId) {
        List<UUID> queue = queues.get(eventId);
        if (queue != null) queue.remove(playerId);
    }

    public List<UUID> getQueue(String eventId) {
        return queues.getOrDefault(eventId, Collections.emptyList());
    }

    public int getQueueSize(String eventId) {
        return queues.containsKey(eventId) ? queues.get(eventId).size() : 0;
    }

    /**
     * Check if queue has enough players to start. If so, move them to the event.
     * @return true if event should start
     */
    public boolean checkQueueReady(ServerEvent event) {
        List<UUID> queue = queues.get(event.getId());
        if (queue == null || queue.isEmpty()) return false;
        if (event.getMinParticipants() > 0 && queue.size() < event.getMinParticipants()) return false;

        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        int max = event.getMaxParticipants() > 0 ? event.getMaxParticipants() : queue.size();
        int toAdd = Math.min(max, queue.size());
        List<UUID> selected = new ArrayList<>(queue.subList(0, toAdd));

        for (UUID uuid : selected) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl != null) {
                em.joinEvent(event, pl, EventParticipant.Role.PARTICIPANT);
            }
            queue.remove(uuid);
        }
        return true;
    }

    public void clearQueue(String eventId) {
        queues.remove(eventId);
    }

    // ── Mini-game lifecycle ──────────────────────────────────────────────────

    /**
     * Called when a mini-game event starts. Sets up type-specific logic.
     */
    public void onEventStart(ServerEvent event, MiniGameType type) {
        MiniGameState state = new MiniGameState(type, event.getId());
        activeGames.put(event.getId(), state);

        switch (type) {
            case SPLEEF -> {
                // Save the floor for restoration after the game
                if (!event.getScopeZoneIds().isEmpty()) {
                    String zoneId = event.getScopeZoneIds().getFirst();
                    state.snapshot = new ZoneSnapshot("spleef_" + event.getId(), zoneId);
                    int saved = state.snapshot.capture(server);
                    CheeseGuardMod.LOGGER.info("[CG] Spleef: saved {} blocks for restoration", saved);
                }
            }
            case SPEED_BUILD -> {
                // Clear the build area
                if (!event.getScopeZoneIds().isEmpty()) {
                    String zoneId = event.getScopeZoneIds().getFirst();
                    state.snapshot = new ZoneSnapshot("build_" + event.getId(), zoneId);
                    state.snapshot.capture(server);
                    state.snapshot.clear(server);
                }
            }
            case MOB_ARENA -> {
                state.waveNumber = 0;
                state.lastWaveSpawn = System.currentTimeMillis();
            }
            case DEATHMATCH, HIDE_SEEK -> {
                // All participants start alive
                state.eliminated.clear();
            }
            default -> {}
        }

        CheeseGuardMod.LOGGER.info("[CG] Mini-game started: {} (type={})", event.getName(), type.displayName);
    }

    /**
     * Called every second for active mini-games.
     */
    public void tickMiniGame(ServerEvent event) {
        MiniGameState state = activeGames.get(event.getId());
        if (state == null) return;

        switch (state.type) {
            case DEATHMATCH -> tickDeathmatch(event, state);
            case MOB_ARENA -> tickMobArena(event, state);
            case SPLEEF -> tickSpleef(event, state);
            case KING_OF_HILL -> tickKingOfHill(event, state);
            default -> {} // Other types use standard scoring triggers
        }
    }

    /**
     * Called when a mini-game event ends. Cleans up type-specific state.
     */
    public void onEventEnd(ServerEvent event) {
        MiniGameState state = activeGames.remove(event.getId());
        if (state == null) return;

        // Restore snapshots
        if (state.snapshot != null && !state.snapshot.isEmpty()) {
            state.snapshot.restore(server);
            CheeseGuardMod.LOGGER.info("[CG] Mini-game '{}': zone restored", event.getName());
        }

        // Remove tracked entities
        for (UUID entityUuid : state.entityWaypoints) {
            for (var world : server.getWorlds()) {
                Entity entity = world.getEntity(entityUuid);
                if (entity != null) {
                    entity.discard();
                    break;
                }
            }
        }

        clearQueue(event.getId());
    }

    // ── Type-specific tick logic ─────────────────────────────────────────────

    private void tickDeathmatch(ServerEvent event, MiniGameState state) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        // Check for dead participants — mark them eliminated
        int alive = 0;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            if (state.eliminated.contains(p.getPlayerUuid())) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null || pl.isDead()) {
                state.eliminated.add(p.getPlayerUuid());
                broadcast(event, "☠ " + p.getPlayerName() + " est elimine !", "red");
            } else {
                alive++;
            }
        }
        // Last player standing wins
        if (alive <= 1 && event.getParticipantCount() > 1) {
            // Find winner
            for (var p : event.getParticipants().values()) {
                if (p.getRole() == EventParticipant.Role.PARTICIPANT && !state.eliminated.contains(p.getPlayerUuid())) {
                    em.addScore(event, p.getPlayerUuid(), 100);
                    break;
                }
            }
            em.endEvent(event);
        }
    }

    private void tickSpleef(ServerEvent event, MiniGameState state) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        // Players who fall below the floor Y level are eliminated
        int floorY = state.snapshot != null ? state.snapshot.getSnapshotId().isEmpty() ? 0 : -1 : 0;
        // Use minY from scope zone
        if (!event.getScopeZoneIds().isEmpty()) {
            var region = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(event.getScopeZoneIds().getFirst());
            if (region != null) floorY = region.getMinY() - 1;
        }

        int alive = 0;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            if (state.eliminated.contains(p.getPlayerUuid())) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null || pl.getY() < floorY) {
                state.eliminated.add(p.getPlayerUuid());
                broadcast(event, "💀 " + p.getPlayerName() + " est tombe !", "aqua");
                // TP to spectator area
                if (pl != null && !event.getTpSpectatorWorld().isBlank()) {
                    String cmd = String.format("tp %s %.2f %.2f %.2f", pl.getName().getString(),
                        event.getTpSpectatorX(), event.getTpSpectatorY(), event.getTpSpectatorZ());
                    try { server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd); } catch (Exception ignored) {}
                    p.setRole(EventParticipant.Role.SPECTATOR);
                }
            } else {
                alive++;
            }
        }
        if (alive <= 1 && event.getParticipantCount() > 1) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() == EventParticipant.Role.PARTICIPANT && !state.eliminated.contains(p.getPlayerUuid())) {
                    em.addScore(event, p.getPlayerUuid(), 100);
                    break;
                }
            }
            em.endEvent(event);
        }
    }

    private void tickMobArena(ServerEvent event, MiniGameState state) {
        long now = System.currentTimeMillis();
        // Spawn waves every 30 seconds
        if (now - state.lastWaveSpawn >= 30000) {
            state.waveNumber++;
            state.lastWaveSpawn = now;
            broadcast(event, "⚠ Vague " + state.waveNumber + " !", "red");
            // Use kit entry commands or effect ENTITY_SPAWN for wave spawning
            // Admins configure the spawn commands in the event's effects
        }
        // Check for all dead
        boolean anyAlive = false;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null && !pl.isDead()) { anyAlive = true; break; }
        }
        if (!anyAlive && event.getParticipantCount() > 0) {
            broadcast(event, "☠ Tous les joueurs sont tombes a la vague " + state.waveNumber + " !", "red");
            CheeseGuardMod.getInstance().getEventManager().endEvent(event);
        }
    }

    private void tickKingOfHill(ServerEvent event, MiniGameState state) {
        if (event.getScopeZoneIds().isEmpty()) return;
        String hillZone = event.getScopeZoneIds().getFirst(); // First zone = the hill

        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null) continue;

            String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();
            var regions = CheeseGuardMod.getInstance().getRegionManager()
                .getRegionsAt(worldId, (int) pl.getX(), (int) pl.getY(), (int) pl.getZ());
            boolean onHill = regions.stream().anyMatch(r -> r.getId().equals(hillZone));

            if (onHill) {
                int timer = state.zoneTimers.getOrDefault(p.getPlayerUuid(), 0) + 1;
                state.zoneTimers.put(p.getPlayerUuid(), timer);
                // Score 1 point per second on the hill
                CheeseGuardMod.getInstance().getEventManager().addScore(event, p.getPlayerUuid(), 1);
            }
        }
    }

    // ── Entity waypoint system ───────────────────────────────────────────────

    public void trackEntity(String eventId, UUID entityUuid) {
        MiniGameState state = activeGames.get(eventId);
        if (state != null) {
            state.entityWaypoints.add(entityUuid);
        }
    }

    public List<UUID> getTrackedEntities(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null ? state.entityWaypoints : Collections.emptyList();
    }

    // ── Player elimination ───────────────────────────────────────────────────

    public void eliminatePlayer(String eventId, UUID playerUuid) {
        MiniGameState state = activeGames.get(eventId);
        if (state != null) state.eliminated.add(playerUuid);
    }

    public boolean isEliminated(String eventId, UUID playerUuid) {
        MiniGameState state = activeGames.get(eventId);
        return state != null && state.eliminated.contains(playerUuid);
    }

    // ── Query ────────────────────────────────────────────────────────────────

    public boolean isActive(String eventId) { return activeGames.containsKey(eventId); }
    public MiniGameType getType(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null ? state.type : null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void broadcast(ServerEvent event, String msg, String color) {
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) {
                pl.sendMessage(Text.literal(msg).formatted(EventManager.colorFromName(color)), false);
            }
        }
    }

    public static MiniGameType[] getAllTypes() { return MiniGameType.values(); }
}

package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.*;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.CommandBossBar;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import org.joml.Vector3f;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.scoreboard.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Heightmap;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class EventManager {

    private final List<ServerEvent> events = new ArrayList<>();
    private MinecraftServer server;
    private final ScoreManager scoreManager = new ScoreManager();
    private final MiniGameManager miniGameManager = new MiniGameManager();
    private final PlayerStateFlags playerFlags = new PlayerStateFlags();
    private final VoteManager voteManager = new VoteManager();
    private TimelineExecutor timelineExecutor; // initialized in init()
    private int tickCounter = 0;
    private long serverStartTime = System.currentTimeMillis();
    private boolean enhancedCelestialsLoaded = false;
    private boolean sophisticatedBackpacksLoaded = false;
    private final List<String> availableLunarEvents = new ArrayList<>();

    // Crash-safe: tracks players with pending inventory restores
    private final Map<UUID, PendingRestore> pendingRestores = new HashMap<>();

    // Deferred respawn TPs: keyed by player UUID, value = Runnable to execute on next tick.
    // AFTER_RESPAWN fires before the player entity is fully ready; we must wait one tick.
    private final Map<UUID, Runnable> pendingRespawnTps = new HashMap<>();

    // ── Constants ────────────────────────────────────────────────────────────

    public static final String[] POTION_OPTIONS = {
        "minecraft:speed", "minecraft:slowness", "minecraft:haste", "minecraft:mining_fatigue",
        "minecraft:strength", "minecraft:jump_boost", "minecraft:regeneration", "minecraft:resistance",
        "minecraft:fire_resistance", "minecraft:water_breathing", "minecraft:invisibility",
        "minecraft:night_vision", "minecraft:weakness", "minecraft:poison", "minecraft:glowing",
        "minecraft:slow_falling", "minecraft:luck", "minecraft:darkness", "minecraft:absorption"
    };

    public static final String[] MULTIPLIER_OPTIONS = { "score", "xp", "shiny_rate", "alpha_rate", "fishing_luck", "mob_drops" };

    public static final String[] COLOR_OPTIONS = {
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
        "gold", "gray", "dark_gray", "blue", "green", "aqua", "red",
        "light_purple", "yellow", "white"
    };

    public static final String[] WEATHER_OPTIONS = { "clear", "rain", "thunder" };

    // ── Init ─────────────────────────────────────────────────────────────────

    public void init(MinecraftServer server) {
        this.server = server;
        this.serverStartTime = System.currentTimeMillis();
        enhancedCelestialsLoaded = FabricLoader.getInstance().isModLoaded("enhancedcelestials");
        sophisticatedBackpacksLoaded = FabricLoader.getInstance().isModLoaded("sophisticatedbackpacks");
        load();
        scoreManager.init(server.getRunDirectory());
        miniGameManager.init(server);
        voteManager.init(server);
        timelineExecutor = new TimelineExecutor(server, playerFlags, voteManager);
        if (enhancedCelestialsLoaded) scanLunarEvents();
        CobblemonHook.tryInit();
        PokemonFishingTable.reload(server);
        loadPendingRestores(); // Crash-safe: restore inventories from previous crash
        CheeseGuardMod.LOGGER.info("[CG] EventManager: {} events, EC={}, SB={}", events.size(), enhancedCelestialsLoaded, sophisticatedBackpacksLoaded);
    }

    // ── Main tick (every server tick) ────────────────────────────────────────

    public void tick() {
        tickCounter++;

        // ── High-frequency tier: zone border particles (every 3 ticks ≈ 20fps) ──
        // This runs BEFORE the 1Hz gate so the border is smooth and continuous.
        if (tickCounter % 3 == 0 && server != null) {
            // Flush pending respawn TPs (deferred by 1 tick to let player entity fully initialize)
            if (!pendingRespawnTps.isEmpty()) {
                var toRun = new ArrayList<>(pendingRespawnTps.values());
                pendingRespawnTps.clear();
                for (Runnable r : toRun) r.run();
            }
            for (ServerEvent event : new ArrayList<>(events)) {
                if (event.getState() != ServerEvent.State.IDLE) {
                    tickZoneBorderParticles(event);
                }
            }
        }

        if (tickCounter % 20 != 0) return; // 1 Hz
        if (server == null) return;
        long now = System.currentTimeMillis();

        // Check auto-triggers for IDLE events (every 5s)
        if (tickCounter % 100 == 0) {
            tickAutoTriggers(now);
        }

        // Crash-safe: try to restore inventories for players who joined back
        if (!pendingRestores.isEmpty() && tickCounter % 60 == 0) {
            tickPendingRestores();
        }

        // Clean stale fish encounter tracking (every 30s)
        if (tickCounter % 600 == 0) {
            CobblemonHook.cleanupStale();
        }

        for (ServerEvent event : events) {
            if (event.getState() == ServerEvent.State.IDLE) continue;
            tickEvent(event, now);
        }
    }

    // ── Auto-triggers ─────────────────────────────────────────────────────────

    private void tickAutoTriggers(long now) {
        for (ServerEvent event : events) {
            if (event.getState() != ServerEvent.State.IDLE) continue;
            // Check cooldown
            if (event.getCooldownMinutes() > 0 && event.getLastRunTimestamp() > 0) {
                long cooldownMs = event.getCooldownMinutes() * 60_000L;
                if (now - event.getLastRunTimestamp() < cooldownMs) continue;
            }
            // Check exclusion group: skip if another event in same group is running
            if (!event.getExclusionGroup().isBlank()) {
                boolean groupBusy = events.stream().anyMatch(e ->
                    e != event && e.isRunning() && e.getExclusionGroup().equals(event.getExclusionGroup()));
                if (groupBusy) continue;
            }

            switch (event.getTrigger()) {
                case RECURRING -> {
                    long intervalMs = event.getRecurringIntervalMinutes() * 60_000L;
                    if (event.getLastRunTimestamp() == 0 || now - event.getLastRunTimestamp() >= intervalMs) {
                        announceEvent(event);
                    }
                }
                case SCHEDULED -> {
                    // Trigger once after scheduledDelayMinutes since last run (or since server start if never run)
                    long baseTime = event.getLastRunTimestamp() > 0 ? event.getLastRunTimestamp() : serverStartTime;
                    long delayMs = event.getScheduledDelayMinutes() * 60_000L;
                    if (delayMs > 0 && now - baseTime >= delayMs) {
                        announceEvent(event);
                    }
                }
                case LUNAR -> {
                    // Auto-trigger when the matching Enhanced Celestials lunar event is active
                    if (enhancedCelestialsLoaded && !event.getLunarEventId().isBlank()) {
                        if (isLunarEventActive(event.getLunarEventId())) {
                            announceEvent(event);
                        }
                    }
                }
                case ZONE_ENTRY -> {
                    if (!event.getTriggerZoneId().isBlank()) {
                        for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                            String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();
                            var regions = CheeseGuardMod.getInstance().getRegionManager()
                                .getRegionsAt(worldId, (int) pl.getX(), (int) pl.getY(), (int) pl.getZ());
                            boolean inZone = regions.stream().anyMatch(r -> r.getId().equals(event.getTriggerZoneId()));
                            if (inZone) { announceEvent(event); break; }
                        }
                    }
                }
                default -> {} // MANUAL handled via GUI/command only
            }

            // Queue auto-start: if queue has enough players, start the event
            if (event.getMode() == ServerEvent.EventMode.MINIGAME && miniGameManager.getQueueSize(event.getId()) > 0) {
                if (miniGameManager.checkQueueReady(event)) {
                    announceEvent(event);
                }
            }
        }
    }

    private void tickEvent(ServerEvent event, long now) {
        long elapsed = now - event.getStateStartedAt();

        switch (event.getState()) {
            case ANNOUNCED -> {
                if (elapsed >= event.getAnnounceDurationSeconds() * 1000L) {
                    // Check min participants
                    if (event.getMinParticipants() > 0 && event.getParticipantCount() < event.getMinParticipants()) {
                        broadcast(event, "Pas assez de participants. Evenement annule.", "red");
                        cleanupEvent(event);
                    } else {
                        transitionTo(event, ServerEvent.State.COUNTDOWN);
                    }
                }
            }
            case COUNTDOWN -> {
                int remaining = event.getCountdownSeconds() - (int)(elapsed / 1000);
                if (remaining <= 0) {
                    startEventActive(event);
                } else if (remaining <= 5 || remaining == 10 || remaining == 30) {
                    // Countdown ticks (only once per second via modulo)
                    if (elapsed % 1000 < 50) {
                        for (var p : event.getParticipants().values()) {
                            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                            if (pl != null) sendTitle(pl, "§e" + remaining, "", 0, 25, 5);
                        }
                    }
                }
            }
            case ACTIVE -> {
                tickActiveEvent(event, now);
            }
            case ENDING -> {
                if (elapsed >= 5000) { // 5s ending phase
                    cleanupEvent(event);
                }
            }
            default -> {}
        }

        // Update bossbar
        if (event.isBossbarEnabled() && event.getState() != ServerEvent.State.IDLE) {
            updateBossbar(event);
        }
    }

    private void tickActiveEvent(ServerEvent event, long now) {
        // ── New act/timeline system ────────────────────────────────────────────
        if (!event.getActs().isEmpty()) {
            // Tick timeline executor every second
            if (tickCounter % 20 == 0) {
                timelineExecutor.tick(event);
                // Check if timeline signaled end
                if (event.isSignalEnd()) {
                    event.clearSignalEnd();
                    endEvent(event);
                    return;
                }
            }
        } else {
            // ── Legacy phase system ────────────────────────────────────────────
            EventPhase phase = event.getCurrentPhase();
            if (phase == null) {
                endEvent(event);
                return;
            }
            long phaseElapsed = now - event.getPhaseStartedAt();
            if (phaseElapsed >= phase.getDurationSeconds() * 1000L) {
                int nextIdx = event.getCurrentPhaseIndex() + 1;
                if (nextIdx < event.getPhases().size()) {
                    startPhase(event, nextIdx);
                } else {
                    endEvent(event);
                }
                return;
            }
        }

        // Check victory conditions
        if (event.getVictoryCondition() == ServerEvent.VictoryCondition.FIRST_TO_X) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() == EventParticipant.Role.PARTICIPANT && p.getScore() >= event.getVictoryTargetScore()) {
                    endEvent(event);
                    return;
                }
            }
        } else if (event.getVictoryCondition() == ServerEvent.VictoryCondition.COOP_GOAL) {
            int totalScore = event.getParticipants().values().stream()
                .filter(p -> p.getRole() == EventParticipant.Role.PARTICIPANT)
                .mapToInt(EventParticipant::getScore).sum();
            if (totalScore >= event.getVictoryTargetScore()) {
                endEvent(event);
                return;
            }
        }

        // Zone boundary enforcement (every 3s)
        if (tickCounter % 60 == 0 && !event.getScopeZoneIds().isEmpty()) {
            enforceZoneBoundary(event);
        }

        // Zone-based scoring (ENTER_ZONE, STAY_IN_ZONE, REACH_ZONE) — every 2s
        if (tickCounter % 40 == 0 && !event.getScoreTriggers().isEmpty()) {
            tickZoneScoring(event);
        }

        // Mini-game type-specific tick
        if (miniGameManager.isActive(event.getId())) {
            miniGameManager.tickMiniGame(event);
        }

        // Tick legacy phase effects (only when NOT using the new act system)
        if (event.getActs().isEmpty()) {
            EventPhase phase = event.getCurrentPhase();
            if (phase != null) {
                for (EventEffect effect : phase.getEffects()) {
                    tickEffect(event, effect, now);
                }
            }
        }
    }

    /**
     * Zone boundary enforcement: TP participants back if they leave the event zone.
     */
    private void enforceZoneBoundary(ServerEvent event) {
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null) continue;
            String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();
            var regions = CheeseGuardMod.getInstance().getRegionManager()
                .getRegionsAt(worldId, (int) pl.getX(), (int) pl.getY(), (int) pl.getZ());
            boolean inZone = regions.stream().anyMatch(r -> event.getScopeZoneIds().contains(r.getId()));
            if (!inZone) {
                // TP back to event TP point
                if (p.getRole() == EventParticipant.Role.PARTICIPANT && !event.getTpParticipantWorld().isBlank()) {
                    tpPlayer(pl, event.getTpParticipantX(), event.getTpParticipantY(), event.getTpParticipantZ());
                    pl.sendMessage(Text.literal("⚠ Vous ne pouvez pas quitter la zone de l'evenement !").formatted(Formatting.RED), true);
                } else if (p.getRole() == EventParticipant.Role.SPECTATOR && !event.getTpSpectatorWorld().isBlank()) {
                    tpPlayer(pl, event.getTpSpectatorX(), event.getTpSpectatorY(), event.getTpSpectatorZ());
                }
            }
        }
    }

    /**
     * Zone-based scoring: ENTER_ZONE, STAY_IN_ZONE, REACH_ZONE triggers.
     */
    private void tickZoneScoring(ServerEvent event) {
        for (ServerEvent.ScoreTrigger trigger : event.getScoreTriggers()) {
            if (trigger.getFilter().isBlank()) continue; // zone-based triggers need a zone filter
            boolean isZoneTrigger = trigger.getType() == ServerEvent.ScoreTrigger.TriggerType.ENTER_ZONE
                || trigger.getType() == ServerEvent.ScoreTrigger.TriggerType.STAY_IN_ZONE
                || trigger.getType() == ServerEvent.ScoreTrigger.TriggerType.REACH_ZONE;
            if (!isZoneTrigger) continue;

            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl == null) continue;

                String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();
                var regions = CheeseGuardMod.getInstance().getRegionManager()
                    .getRegionsAt(worldId, (int) pl.getX(), (int) pl.getY(), (int) pl.getZ());
                boolean inZone = regions.stream().anyMatch(r -> r.getId().equals(trigger.getFilter()));

                switch (trigger.getType()) {
                    case STAY_IN_ZONE -> {
                        // Award points every tick (2s) while in zone
                        if (inZone) addScore(event, p.getPlayerUuid(), trigger.getPoints());
                    }
                    case ENTER_ZONE -> {
                        // Award points once when entering zone (track via effectLastRun)
                        String key = "zone_enter_" + trigger.getFilter() + "_" + p.getPlayerUuid();
                        boolean wasIn = event.getEffectLastRun(key) > 0;
                        if (inZone && !wasIn) {
                            addScore(event, p.getPlayerUuid(), trigger.getPoints());
                            event.setEffectLastRun(key, 1);
                        } else if (!inZone && wasIn) {
                            event.setEffectLastRun(key, 0); // Reset when leaving
                        }
                    }
                    case REACH_ZONE -> {
                        // Award points once ever when reaching zone
                        String key = "zone_reach_" + trigger.getFilter() + "_" + p.getPlayerUuid();
                        if (inZone && event.getEffectLastRun(key) == 0) {
                            addScore(event, p.getPlayerUuid(), trigger.getPoints());
                            event.setEffectLastRun(key, 1);
                        }
                    }
                    default -> {}
                }
            }
        }
    }

    /**
     * Custom command scoring: called via /cg event score <eventId> <player> <points>
     */
    public void addScoreCommand(String eventId, UUID playerUuid, int points) {
        ServerEvent event = getEvent(eventId);
        if (event == null || !event.isActive()) return;
        addScore(event, playerUuid, points);
    }

    private void tickEffect(ServerEvent event, EventEffect effect, long now) {
        // Day/night condition
        if (!effect.getDayNightCondition().isBlank() && server.getOverworld() != null) {
            long timeOfDay = server.getOverworld().getTimeOfDay() % 24000;
            boolean isDay = timeOfDay < 13000;
            if (effect.getDayNightCondition().equals("day") && !isDay) return;
            if (effect.getDayNightCondition().equals("night") && isDay) return;
        }

        List<ServerPlayerEntity> targets = getEffectTargets(event, effect);

        switch (effect.getType()) {
            case POTION -> {
                if (tickCounter % 20 != 0) return; // Every 1s (was 5s)
                applyPotion(effect, targets);
            }
            case COMMAND -> {
                if (!checkInterval(event, effect, now)) return;
                runCommand(effect, targets);
            }
            case BROADCAST -> {
                if (!checkInterval(event, effect, now)) return;
                if (!effect.getMessage().isBlank()) {
                    Formatting c = colorFromName(effect.getMessageColor());
                    for (ServerPlayerEntity pl : targets) pl.sendMessage(Text.literal(effect.getMessage()).formatted(c), false);
                }
            }
            case SOUND -> {
                if (!checkInterval(event, effect, now)) return;
                CheeseGuardMod.LOGGER.info("[CG] Playing sound {} (interval={}s) to {} targets",
                    effect.getSoundId(), effect.getIntervalSeconds(), targets.size());
                playSound(effect, targets);
            }
            case TITLE -> {
                if (!checkInterval(event, effect, now)) return;
                for (ServerPlayerEntity pl : targets) sendTitle(pl, effect.getTitleText(), effect.getSubtitleText(), effect.getFadeIn(), effect.getStay(), effect.getFadeOut());
            }
            case PARTICLE -> {
                if (!checkInterval(event, effect, now)) return;
                spawnParticles(effect, targets);
            }
            case ITEM_DROP -> {
                if (!checkInterval(event, effect, now)) return;
                giveItem(effect, targets);
            }
            case WEATHER -> {
                if (tickCounter % 200 != 0) return; // Re-apply every 10s
                setWeather(effect, event);
            }
            case TIME -> {
                if (effect.isFreezeTime() && tickCounter % 20 == 0) setTime(effect, event);
                else if (tickCounter % 200 == 0) setTime(effect, event);
            }
            case ENTITY_SPAWN -> {
                if (!checkInterval(event, effect, now)) return;
                spawnEntity(effect, targets, event);
            }
            case MULTIPLIER, FLAG_OVERRIDE -> {} // Passive, read by other systems
            case ZONE_SHRINK -> {
                if (tickCounter % 20 != 0) return; // Every 1s

                // Compute effective zone center (static or orbital)
                double cx, cz;
                if (effect.isZoneOrbitEnabled() && effect.getZoneOrbitRadius() > 0) {
                    // Orbital: center moves in a circle around the configured center point
                    long phaseElapsed = now - event.getPhaseStartedAt();
                    double elapsedSec = phaseElapsed / 1000.0;
                    double angleDeg = effect.getZoneOrbitAngleOffset() + (elapsedSec * effect.getZoneOrbitSpeed());
                    double angleRad = Math.toRadians(angleDeg);
                    cx = effect.getZoneCenterX() + effect.getZoneOrbitRadius() * Math.cos(angleRad);
                    cz = effect.getZoneCenterZ() + effect.getZoneOrbitRadius() * Math.sin(angleRad);
                } else {
                    cx = effect.getZoneCenterX();
                    cz = effect.getZoneCenterZ();
                }

                double radius = effect.getZoneRadius();
                double radiusSq = radius * radius;
                float dmg = (float) effect.getZoneDamage();

                // Damage only PARTICIPANTS outside the zone
                for (ServerPlayerEntity pl : targets) {
                    double dx = pl.getX() - cx;
                    double dz = pl.getZ() - cz;
                    double distSq = dx * dx + dz * dz;
                    if (distSq > radiusSq) {
                        pl.damage(pl.getServerWorld().getDamageSources().magic(), dmg);
                        pl.sendMessage(Text.literal("\u26A0 Retournez dans la zone ! (-" + (int)dmg + " \u2764)")
                            .formatted(Formatting.RED), true);
                    }
                }

                // Particle border rendering is handled by tickZoneBorderParticles()
                // which runs every 3 ticks for smooth 20fps visuals.
            }
        }
    }

    private boolean checkInterval(ServerEvent event, EventEffect effect, long now) {
        if (effect.getIntervalSeconds() <= 0) return false;
        long last = event.getEffectLastRun(effect.getId());
        if (now - last < effect.getIntervalSeconds() * 1000L) return false;
        event.setEffectLastRun(effect.getId(), now);
        return true;
    }

    private List<ServerPlayerEntity> getEffectTargets(ServerEvent event, EventEffect effect) {
        List<ServerPlayerEntity> targets = new ArrayList<>();
        // Get relevant players
        if (event.getMode() == ServerEvent.EventMode.MINIGAME || !event.getParticipants().isEmpty()) {
            // Only participants
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) targets.add(pl);
            }
        } else {
            // Global event: all players
            targets.addAll(server.getPlayerManager().getPlayerList());
        }

        // Zone scope filter
        if (!effect.getScopeZoneId().isBlank()) {
            targets.removeIf(pl -> {
                String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();
                var regions = CheeseGuardMod.getInstance().getRegionManager()
                    .getRegionsAt(worldId, (int) pl.getX(), (int) pl.getY(), (int) pl.getZ());
                return regions.stream().noneMatch(r -> r.getId().equals(effect.getScopeZoneId()));
            });
        }
        return targets;
    }

    // ── Effect implementations ───────────────────────────────────────────────

    private void applyPotion(EventEffect effect, List<ServerPlayerEntity> targets) {
        Identifier id = Identifier.tryParse(effect.getPotionId());
        if (id == null) return;
        Optional<RegistryEntry.Reference<StatusEffect>> opt = Registries.STATUS_EFFECT.getEntry(id);
        if (opt.isEmpty()) return;
        RegistryEntry<StatusEffect> entry = opt.get();
        // Duration: if effect has a scope zone, use 30 ticks (1.5s) so it fades
        // ~1.5s after leaving the zone. Refreshed every 1s while in scope.
        // Without scope, keep the configured duration (persistent buffs).
        boolean hasScope = !effect.getScopeZoneId().isBlank();
        int duration = hasScope ? 30 : effect.getPotionDurationTicks();
        for (ServerPlayerEntity pl : targets) {
            pl.addStatusEffect(new StatusEffectInstance(entry, duration, effect.getAmplifier(), false, true, true));
        }
    }

    private void runCommand(EventEffect effect, List<ServerPlayerEntity> targets) {
        if (effect.getCommand().isBlank()) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (ServerPlayerEntity pl : targets) {
            if (effect.getChancePercent() < 100 && rng.nextInt(100) >= effect.getChancePercent()) continue;
            String cmd = effect.getCommand()
                .replace("{player}", pl.getName().getString())
                .replace("{x}", String.valueOf((int) pl.getX()))
                .replace("{y}", String.valueOf((int) pl.getY()))
                .replace("{z}", String.valueOf((int) pl.getZ()));
            try { server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd); }
            catch (Exception e) { CheeseGuardMod.LOGGER.debug("[CG] Cmd err: {}", e.getMessage()); }
        }
    }

    private void playSound(EventEffect effect, List<ServerPlayerEntity> targets) {
        Identifier id = Identifier.tryParse(effect.getSoundId());
        if (id == null) return;
        SoundEvent sound = SoundEvent.of(id);
        for (ServerPlayerEntity pl : targets) {
            pl.playSoundToPlayer(sound, SoundCategory.MASTER, effect.getSoundVolume(), effect.getSoundPitch());
        }
    }

    private void spawnParticles(EventEffect effect, List<ServerPlayerEntity> targets) {
        for (ServerPlayerEntity pl : targets) {
            String cmd = String.format("particle %s %.1f %.1f %.1f %.1f %.1f %.1f 0 %d force @a[distance=..32]",
                effect.getParticleId(), pl.getX(), pl.getY() + 1, pl.getZ(),
                effect.getParticleSpread(), effect.getParticleSpread(), effect.getParticleSpread(), effect.getParticleCount());
            execAs(pl, cmd);
        }
    }

    /**
     * Called every 3 ticks (≈20fps) for all active events.
     * Finds ZONE_SHRINK effects with showBorder=true and renders the wall.
     * Separated from the 1Hz damage tick so orbit and particles are smooth.
     */
    private void tickZoneBorderParticles(ServerEvent event) {
        if (event.getCurrentPhase() == null) return; // event not yet started (countdown)
        for (EventEffect effect : event.getCurrentPhase().getEffects()) {
            if (effect.getType() != EventEffect.Type.ZONE_SHRINK) continue;
            if (!effect.isZoneShowBorder()) continue;

            // zoneCenterX/Z = PIVOT (the point the zone orbits around).
            // cx/cz         = current zone center (= pivot when orbit disabled).
            double cx, cz;
            if (effect.isZoneOrbitEnabled() && effect.getZoneOrbitRadius() > 0) {
                long elapsed = System.currentTimeMillis() - event.getPhaseStartedAt();
                double angleRad = Math.toRadians(
                    effect.getZoneOrbitAngleOffset() + (elapsed / 1000.0) * effect.getZoneOrbitSpeed());
                cx = effect.getZoneCenterX() + effect.getZoneOrbitRadius() * Math.cos(angleRad);
                cz = effect.getZoneCenterZ() + effect.getZoneOrbitRadius() * Math.sin(angleRad);
            } else {
                cx = effect.getZoneCenterX();
                cz = effect.getZoneCenterZ();
            }

            List<ServerPlayerEntity> viewers = new ArrayList<>();
            for (var p : event.getParticipants().values()) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) viewers.add(pl);
            }
            if (viewers.isEmpty()) continue;

            drawZoneBorder(viewers, cx, cz, effect.getZoneRadius(), tickCounter);
        }
    }

    // ── Particle presets — allocated once, reused every frame ────────────────

    /** Main wall dust accent: orange → red, small scale so it fades quickly */
    private static final DustColorTransitionParticleEffect P_WALL =
        new DustColorTransitionParticleEffect(
            new Vector3f(1.0f, 0.55f, 0.0f), new Vector3f(1.0f, 0.0f, 0.0f), 1.0f);

    /** Danger near (r+2 .. r+8): dark crimson, small scale */
    private static final DustParticleEffect P_DANGER_NEAR =
        new DustParticleEffect(new Vector3f(0.60f, 0.0f, 0.0f), 1.0f);

    /** Danger mid (r+10 .. r+22): very dark maroon */
    private static final DustParticleEffect P_DANGER_MID =
        new DustParticleEffect(new Vector3f(0.30f, 0.0f, 0.0f), 1.0f);

    /** Danger far (r+24 .. r+40): near-black void */
    private static final DustParticleEffect P_DANGER_FAR =
        new DustParticleEffect(new Vector3f(0.12f, 0.0f, 0.02f), 1.0f);

    /**
     * Draws a multi-layer cylindrical barrier.
     *
     * Cross-section (center → outside):
     *
     *  ← SAFE (no particles) ║ WALL ║ crimson .. maroon .. void/ash
     *                         ║ r±0.4║ r+2 to r+40
     *
     * baseY is derived from the world heightmap at (cx,cz) — NOT from player
     * positions, so the wall height is stable regardless of where players stand.
     *
     * The main wall ring uses ELECTRIC_SPARK (≈5 tick lifetime) so old rings
     * disappear almost instantly when the zone moves (orbit mode).
     * Dust particles use scale=1.0 so residual fade is barely visible.
     */
    private void drawZoneBorder(List<ServerPlayerEntity> viewers, double cx, double cz,
                                 double radius, int animTick) {
        if (viewers.isEmpty()) return;

        ServerWorld world = viewers.get(0).getServerWorld();

        // baseY from world surface at zone center — stable, independent of player Y
        double baseY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int)cx, (int)cz);

        final int    POINTS   = Math.min(96, Math.max(48, (int)(2.0 * Math.PI * radius / 1.8)));
        final double WALL_TOP = 6.0;
        final double[] WALL_YS = {0, 1.5, 3.0, 4.5, WALL_TOP};

        double crownPulse = Math.sin(animTick * 0.10) * 0.8;

        for (int pi = 0; pi < POINTS; pi++) {
            double angle = 2.0 * Math.PI * pi / POINTS;
            double cos = Math.cos(angle), sin = Math.sin(angle);

            // ── Main wall (r-0.4, r+0.4) ─────────────────────────────────────────
            // ELECTRIC_SPARK: bright white-blue, ~5 tick lifetime → disappears almost
            // instantly when zone moves. Dust accent at scale 1.0 for color.
            for (double dr : new double[]{-0.4, 0.4}) {
                double wx = cx + (radius + dr) * cos, wz = cz + (radius + dr) * sin;
                for (double wy : WALL_YS) {
                    double py = baseY + wy;
                    for (var v : viewers) {
                        world.spawnParticles(v, ParticleTypes.ELECTRIC_SPARK, true, wx, py, wz, 1, 0,0,0,0);
                        world.spawnParticles(v, P_WALL, true, wx, py, wz, 1, 0,0,0,0);
                    }
                }
            }

            // ── Danger near (r+2, r+4, r+6, r+8) — every 2nd point ───────────────
            if (pi % 2 == 0) {
                for (double dr : new double[]{2.0, 4.0, 6.0, 8.0}) {
                    double dx = cx + (radius + dr) * cos, dz = cz + (radius + dr) * sin;
                    for (double y : new double[]{baseY + 1, baseY + 3, baseY + 5})
                        for (var v : viewers)
                            world.spawnParticles(v, P_DANGER_NEAR, true, dx, y, dz, 1, 0,0,0,0);
                }
                // LAVA drip at ground level just outside
                double lx = cx + (radius + 1.5) * cos, lz = cz + (radius + 1.5) * sin;
                for (var v : viewers)
                    world.spawnParticles(v, ParticleTypes.LAVA, true, lx, baseY + 0.5, lz, 1, 0,0,0,0);
            }

            // ── Danger mid (r+12, r+16, r+20, r+24) — every 3rd point ────────────
            if (pi % 3 == 0) {
                for (double dr : new double[]{12.0, 16.0, 20.0, 24.0}) {
                    double dx = cx + (radius + dr) * cos, dz = cz + (radius + dr) * sin;
                    for (double y : new double[]{baseY + 1, baseY + 4})
                        for (var v : viewers)
                            world.spawnParticles(v, P_DANGER_MID, true, dx, y, dz, 1, 0,0,0,0);
                }
                double sx = cx + (radius + 14.0) * cos, sz = cz + (radius + 14.0) * sin;
                for (var v : viewers)
                    world.spawnParticles(v, ParticleTypes.LARGE_SMOKE, true, sx, baseY + 3, sz, 1, 0, 0.3, 0, 0.02);
            }

            // ── Danger far (r+28, r+34, r+40) + ASH — every 5th point ────────────
            if (pi % 5 == 0) {
                for (double dr : new double[]{28.0, 34.0, 40.0}) {
                    double dx = cx + (radius + dr) * cos, dz = cz + (radius + dr) * sin;
                    for (var v : viewers)
                        world.spawnParticles(v, P_DANGER_FAR, true, dx, baseY + 2, dz, 1, 0,0,0,0);
                }
                double ax = cx + (radius + 36.0) * cos, az = cz + (radius + 36.0) * sin;
                for (var v : viewers)
                    world.spawnParticles(v, ParticleTypes.ASH, true, ax, baseY + 1.5, az, 2, 1.5, 1.5, 1.5, 0.01);
            }

            // ── Crown: SOUL_FIRE_FLAME + SCULK_SOUL — every 3rd point ────────────
            if (pi % 3 == 0) {
                double crownX = cx + radius * cos, crownZ = cz + radius * sin;
                double crownY = baseY + WALL_TOP + crownPulse;
                for (var v : viewers)
                    world.spawnParticles(v, ParticleTypes.SOUL_FIRE_FLAME, true, crownX, crownY, crownZ, 1, 0,0,0,0);
                if (pi % 6 == 0)
                    for (var v : viewers)
                        world.spawnParticles(v, ParticleTypes.SCULK_SOUL, true, crownX, crownY + 1.2, crownZ, 1, 0.2, 0.2, 0.2, 0.01);
            }
        }
    }


    private void giveItem(EventEffect effect, List<ServerPlayerEntity> targets) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (ServerPlayerEntity pl : targets) {
            if (effect.getChancePercent() < 100 && rng.nextInt(100) >= effect.getChancePercent()) continue;
            String cmd = String.format("give %s %s %d", pl.getName().getString(), effect.getItemId(), effect.getItemCount());
            execAs(pl, cmd);
        }
    }

    private void setWeather(EventEffect effect, ServerEvent event) {
        ServerWorld world = getEventWorld(event);
        int duration = 999999;
        switch (effect.getWeatherType()) {
            case "rain" -> world.setWeather(0, duration, true, false);
            case "thunder" -> world.setWeather(0, duration, true, true);
            default -> world.setWeather(duration, 0, false, false); // clear
        }
    }

    private void setTime(EventEffect effect, ServerEvent event) {
        if (effect.getTimeValue() < 0) return;
        ServerWorld world = getEventWorld(event);
        world.setTimeOfDay(effect.getTimeValue());
    }

    private void spawnEntity(EventEffect effect, List<ServerPlayerEntity> targets, ServerEvent event) {
        if (effect.getEntityCommand().isBlank()) return;

        // Collect participant players (living, in same world) for aggro assignment
        List<ServerPlayerEntity> participants = event.getParticipants().values().stream()
            .filter(p -> p.getRole() == EventParticipant.Role.PARTICIPANT)
            .map(p -> server.getPlayerManager().getPlayer(p.getPlayerUuid()))
            .filter(pl -> pl != null && pl.isAlive())
            .collect(java.util.stream.Collectors.toList());

        ServerWorld spawnWorld;
        if (effect.isUseRelativePos()) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (ServerPlayerEntity pl : targets) {
                if (effect.getChancePercent() < 100 && rng.nextInt(100) >= effect.getChancePercent()) continue;
                String cmd = effect.getEntityCommand()
                    .replace("{x}", String.valueOf((int) pl.getX()))
                    .replace("{y}", String.valueOf((int) pl.getY()))
                    .replace("{z}", String.valueOf((int) pl.getZ()));
                execAs(pl, cmd);
                aggroNewHostilesNear(pl.getServerWorld(), pl.getX(), pl.getY(), pl.getZ(), 6, participants);
            }
        } else {
            // Absolute position: resolve spawn coords from the command (first 3 numbers after the entity type)
            spawnWorld = targets.isEmpty() ? getEventWorld(event) : targets.get(0).getServerWorld();
            execIn(spawnWorld, effect.getEntityCommand());
            // Extract approximate coords to find the spawned entities
            double sx = 0, sy = 0, sz = 0;
            try {
                String[] parts = effect.getEntityCommand().trim().split("\\s+");
                // "summon <type> <x> <y> <z> ..."
                if (parts.length >= 5) {
                    sx = Double.parseDouble(parts[2]);
                    sy = Double.parseDouble(parts[3]);
                    sz = Double.parseDouble(parts[4]);
                }
            } catch (Exception ignored) {}
            aggroNewHostilesNear(spawnWorld, sx, sy, sz, 10, participants);
        }
    }

    /**
     * Find all HostileEntity within [radius] blocks of (cx,cy,cz) that have no current target,
     * and assign each one the nearest participant as its target.
     * Called immediately after a summon command so fresh spawns are instantly aggressive.
     */
    private void aggroNewHostilesNear(ServerWorld world, double cx, double cy, double cz,
                                       double radius, List<ServerPlayerEntity> participants) {
        if (participants.isEmpty()) return;
        var box = new net.minecraft.util.math.Box(cx - radius, cy - radius, cz - radius,
                                                   cx + radius, cy + radius, cz + radius);
        world.getEntitiesByClass(net.minecraft.entity.mob.HostileEntity.class, box,
            mob -> mob.isAlive() && mob.getTarget() == null)
        .forEach(mob -> {
            // Pick nearest participant
            ServerPlayerEntity nearest = null;
            double bestDist = Double.MAX_VALUE;
            for (ServerPlayerEntity pl : participants) {
                double d = mob.squaredDistanceTo(pl);
                if (d < bestDist) { bestDist = d; nearest = pl; }
            }
            if (nearest != null) {
                mob.setTarget(nearest);
                mob.setAttacking(true);
            }
        });
    }

    // ── Lifecycle transitions ────────────────────────────────────────────────

    public void announceEvent(ServerEvent event) {
        transitionTo(event, ServerEvent.State.ANNOUNCED);

        // Broadcast with clickable buttons
        Text msg = Text.literal(event.getAnnounceMessage() + " ")
            .formatted(colorFromName(event.getAnnounceColor()), Formatting.BOLD)
            .append(Text.literal("[Participer]").styled(s -> s
                .withColor(Formatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cg event join " + event.getId()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Cliquez pour participer")))))
            .append(Text.literal(" "))
            .append(Text.literal("[Spectateur]").styled(s -> s
                .withColor(Formatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cg event spectate " + event.getId()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Cliquez pour observer")))));
        server.getPlayerManager().broadcast(msg, false);

        // Description line (if set)
        if (!event.getDescription().isBlank()) {
            Text descMsg = Text.literal("  ➤ " + event.getDescription())
                .formatted(Formatting.GRAY, Formatting.ITALIC);
            server.getPlayerManager().broadcast(descMsg, false);
        }

        // Discord
        if (event.isDiscordNotify() && DiscordWebhook.isConfigured()) {
            String discordDesc = event.getAnnounceMessage();
            if (!event.getDescription().isBlank()) {
                discordDesc += "\n\n*" + event.getDescription() + "*";
            }
            discordDesc += "\n\nInscription ouverte pendant " + event.getAnnounceDurationSeconds() + "s";
            DiscordWebhook.send("\uD83D\uDCE2 " + event.getName(), discordDesc, mcColorToHex(event.getAnnounceColor()));
        }

        // Create bossbar
        if (event.isBossbarEnabled()) createBossbar(event);

        CheeseGuardMod.LOGGER.info("[CG] Event announced: {}", event.getName());
    }

    public void startEventDirect(ServerEvent event) {
        // Skip announce/countdown, go straight to active
        startEventActive(event);
    }

    private void startEventActive(ServerEvent event) {
        transitionTo(event, ServerEvent.State.ACTIVE);

        // TP participants
        tpParticipants(event);

        // Save inventories if needed
        if (event.isSaveInventory()) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() == EventParticipant.Role.PARTICIPANT) {
                    ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                    if (pl != null) {
                        p.saveInventory(pl);
                        // Crash-safe: persist to disk
                        savePendingRestore(p.getPlayerUuid(), p);
                        if (event.isForceEmptyInventory()) {
                            EventParticipant.clearInventoryForEvent(pl);
                        }
                    }
                }
            }
        }

        // Enforce blacklist items (confiscate forbidden items — they'll come back on restore)
        enforceItemBlacklist(event);

        // Kit entry commands
        for (String cmd : event.getKitEntryCommands()) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) {
                    String c = cmd.replace("{player}", pl.getName().getString());
                    try {
                        // Use dispatcher directly — more reliable for modded commands
                        String clean = c.startsWith("/") ? c.substring(1) : c;
                        int result = server.getCommandManager().getDispatcher()
                            .execute(clean, server.getCommandSource().withSilent());
                        if (result == 0) {
                            CheeseGuardMod.LOGGER.warn("[CG] Kit command returned 0: '{}'", clean);
                        } else {
                            CheeseGuardMod.LOGGER.info("[CG] Kit OK for {}: {}", pl.getName().getString(), clean);
                        }
                    } catch (Exception e) {
                        CheeseGuardMod.LOGGER.error("[CG] Kit command FAILED: '{}' — {}", c, e.getMessage());
                        // Try fallback: executeWithPrefix
                        try {
                            String clean = c.startsWith("/") ? c : "/" + c;
                            server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), clean);
                        } catch (Exception e2) {
                            CheeseGuardMod.LOGGER.error("[CG] Kit fallback also failed: '{}' — {}", c, e2.getMessage());
                        }
                    }
                }
            }
        }

        // Start timeline executor (new act system) OR legacy phases (backward compat)
        if (!event.getActs().isEmpty()) {
            timelineExecutor.start(event);
        } else if (!event.getPhases().isEmpty()) {
            startPhase(event, 0);
        }

        // Broadcast start
        if (!event.getStartMessage().isBlank()) {
            server.getPlayerManager().broadcast(Text.literal(event.getStartMessage())
                .formatted(colorFromName(event.getStartColor()), Formatting.BOLD), false);
        }

        // Discord
        if (event.isDiscordNotify() && DiscordWebhook.isConfigured()) {
            int secs = event.getTotalDurationSeconds();
            DiscordWebhook.send("🎉 " + event.getName(), event.getStartMessage() +
                "\nDuree: " + (secs / 60) + "min" + (secs % 60 > 0 ? secs % 60 + "s" : "") +
                "\nParticipants: " + event.getParticipantCount(), mcColorToHex(event.getDisplayColor()));
        }

        // Scoreboard
        if (event.isScoreboardVisible()) createScoreboard(event);

        // Force lunar event
        if (!event.getLunarEventId().isBlank()) forceLunarEvent(event.getLunarEventId());

        // Start mini-game logic if applicable
        if (event.getMode() == ServerEvent.EventMode.MINIGAME && !event.getMiniGameType().isBlank()) {
            try {
                MiniGameManager.MiniGameType mgType = MiniGameManager.MiniGameType.valueOf(event.getMiniGameType());
                miniGameManager.onEventStart(event, mgType);
            } catch (IllegalArgumentException ignored) {}
        }

        CheeseGuardMod.LOGGER.info("[CG] Event started: {} ({} participants)", event.getName(), event.getParticipantCount());
    }

    /**
     * Remove blacklisted items from participant inventories.
     * Items are already saved via saveInventory(), so they'll be restored on event end.
     */
    private void enforceItemBlacklist(ServerEvent event) {
        if (event.getBlacklistItems().isEmpty()) return;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null) continue;
            int removed = 0;
            for (int i = 0; i < pl.getInventory().size(); i++) {
                ItemStack stack = pl.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                String itemId = Registries.ITEM.getId(stack.getItem()).toString();
                if (event.getBlacklistItems().contains(itemId)) {
                    pl.getInventory().setStack(i, ItemStack.EMPTY);
                    removed++;
                }
            }
            if (removed > 0) {
                pl.currentScreenHandler.sendContentUpdates();
                pl.sendMessage(Text.literal("⚠ " + removed + " item(s) interdit(s) confisque(s) pour l'evenement.").formatted(Formatting.YELLOW), false);
            }
        }
    }

    private void startPhase(ServerEvent event, int index) {
        event.setCurrentPhaseIndex(index);
        event.getEffectLastRunMap().clear(); // Reset intervals so effects fire immediately
        EventPhase phase = event.getCurrentPhase();
        if (phase == null) return;

        // Title message
        if (!phase.getTitleMessage().isBlank()) {
            for (var p : event.getParticipants().values()) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) sendTitle(pl, phase.getTitleMessage(), phase.getSubtitleMessage(), 10, 60, 10);
            }
        }

        // Start sound
        if (!phase.getStartSound().isBlank()) {
            Identifier id = Identifier.tryParse(phase.getStartSound());
            if (id != null) {
                SoundEvent sound = SoundEvent.of(id);
                for (var p : event.getParticipants().values()) {
                    ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                    if (pl != null) pl.playSoundToPlayer(sound, SoundCategory.MASTER, 1.0f, 1.0f);
                }
            }
        }

        CheeseGuardMod.LOGGER.info("[CG] Phase started: {} ({})", phase.getName(), event.getName());
    }

    public void endEvent(ServerEvent event) {
        transitionTo(event, ServerEvent.State.ENDING);

        // Determine winners (includes spectators who died — they still have scores)
        List<EventParticipant> ranked = new ArrayList<>();
        for (var p : event.getParticipants().values()) {
            if (p.getRole() == EventParticipant.Role.PARTICIPANT || p.getRole() == EventParticipant.Role.SPECTATOR) {
                ranked.add(p);
            }
        }
        ranked.sort(Comparator.comparingInt(EventParticipant::getScore).reversed());

        // Store ranked list for reward distribution after cleanup
        event.setRankedCache(ranked);

        // Record scores
        scoreManager.recordEventResult(event);

        // Broadcast end
        if (!event.getEndMessage().isBlank()) {
            server.getPlayerManager().broadcast(Text.literal(event.getEndMessage())
                .formatted(colorFromName(event.getEndColor())), false);
        }

        // Announce ranking (show at least enough for all rewards, min 3, max 10)
        if (!ranked.isEmpty() && event.getVictoryCondition() != ServerEvent.VictoryCondition.COOP_GOAL) {
            int maxRewardRank = 3;
            for (var reward : event.getRewards()) {
                try { maxRewardRank = Math.max(maxRewardRank, Integer.parseInt(reward.getRank())); }
                catch (NumberFormatException ignored) {}
            }
            int showCount = Math.min(ranked.size(), Math.max(maxRewardRank, 5));

            server.getPlayerManager().broadcast(Text.literal("").formatted(Formatting.GOLD), false);
            server.getPlayerManager().broadcast(Text.literal("═══ 🏆 Classement Final ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
            String[] medals = {"🥇", "🥈", "🥉"};
            Formatting[] colors = {Formatting.GOLD, Formatting.GRAY, Formatting.DARK_RED};
            for (int i = 0; i < showCount; i++) {
                EventParticipant rp = ranked.get(i);
                String prefix = i < 3 ? medals[i] : (i + 1) + ".";
                Formatting color = i < 3 ? colors[i] : Formatting.WHITE;
                boolean bold = i == 0;
                server.getPlayerManager().broadcast(Text.literal(
                    prefix + " " + rp.getPlayerName() + " — " + rp.getScore() + " pts"
                ).formatted(color, bold ? Formatting.BOLD : Formatting.ITALIC), false);
            }
            if (ranked.size() > showCount) {
                server.getPlayerManager().broadcast(Text.literal(
                    "   ... et " + (ranked.size() - showCount) + " autres participants"
                ).formatted(Formatting.GRAY), false);
            }
            server.getPlayerManager().broadcast(Text.literal("═══════════════════════").formatted(Formatting.GOLD), false);
        }

        // Discord
        if (event.isDiscordNotify() && DiscordWebhook.isConfigured()) {
            StringBuilder sb = new StringBuilder(event.getEndMessage());
            if (!ranked.isEmpty()) {
                sb.append("\n\n🏆 Classement:");
                for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                    sb.append(String.format("\n%d. %s — %d pts", i + 1, ranked.get(i).getPlayerName(), ranked.get(i).getScore()));
                }
            }
            DiscordWebhook.send("⏹ " + event.getName(), sb.toString(), 0x95A5A6);
        }

        // Lightning strike mobs near event zone (using execute command — reliable across all mob types)
        if (event.isLightningMobsOnEnd()) {
            strikeLightningMobs(event);
        }

        // Mini-game cleanup (zone restore, entity removal)
        if (miniGameManager.isActive(event.getId())) {
            miniGameManager.onEventEnd(event);
        }

        CheeseGuardMod.LOGGER.info("[CG] Event ending: {}", event.getName());
    }

    private void cleanupEvent(ServerEvent event) {
        // Stop timeline executor
        if (timelineExecutor != null && !event.getActs().isEmpty()) {
            timelineExecutor.end(event);
        }

        // Kill surviving mobs near lightning zone (belt-and-suspenders, ~5s after the strike in endEvent)
        killLightningMobs(event);

        // ── Kit exit commands — run FIRST, while players are still in the event world ──
        // Uses execAs (player world context) so commands like "effect clear @s" work correctly.
        // Errors are logged explicitly so issues are visible in the console.
        if (!event.getKitExitCommands().isEmpty()) {
            for (var p : event.getParticipants().values()) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl == null) continue;
                for (String cmd : event.getKitExitCommands()) {
                    String c = cmd.replace("{player}", pl.getName().getString());
                    try {
                        String clean = c.startsWith("/") ? c.substring(1) : c;
                        int result = server.getCommandManager().getDispatcher()
                            .execute(clean, server.getCommandSource().withSilent()
                                .withWorld(pl.getServerWorld())
                                .withPosition(pl.getPos())
                                .withEntity(pl));
                        if (result == 0) {
                            CheeseGuardMod.LOGGER.warn("[CG] Kit exit cmd returned 0 for {}: '{}'", pl.getName().getString(), clean);
                        }
                    } catch (Exception e) {
                        CheeseGuardMod.LOGGER.error("[CG] Kit exit cmd FAILED for {}: '{}' — {}", pl.getName().getString(), c, e.getMessage());
                    }
                }
            }
        }

        // Restore inventories
        if (event.isSaveInventory()) {
            for (var p : event.getParticipants().values()) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null && p.hasInventorySaved()) {
                    // Restore gamemode BEFORE inventory so spectators can receive items
                    restoreGameMode(pl, p);
                    p.restoreInventory(pl);
                    clearPendingRestore(p.getPlayerUuid()); // crash-safe: remove disk backup
                } else if (pl != null && !p.hasInventorySaved()) {
                    // Online but no inventory saved (e.g. spectator) — just restore gamemode
                    restoreGameMode(pl, p);
                } else if (p.hasInventorySaved()) {
                    // Offline participant with inventory saved — pending restore file already on
                    // disk from event start. Make sure gamemode is flagged for restoration too.
                    if (p.hasOriginalGameMode()) {
                        var pr = pendingRestores.get(p.getPlayerUuid());
                        if (pr != null) pr.pendingGameMode = p.getOriginalGameMode();
                        // else: disk file has originalGameMode from savePendingRestore, will be
                        // loaded correctly on next server start via loadPendingRestores()
                    }
                    CheeseGuardMod.LOGGER.warn("[CG] Player {} offline at cleanup, inventory pending restore", p.getPlayerName());
                } else if (p.hasOriginalGameMode()) {
                    // Offline spectator (no inventory saved) — save a minimal pending restore
                    // so their gamemode is restored the next time they log in
                    savePendingGameModeRestore(p.getPlayerUuid(), p);
                }
            }
        } else {
            // Even without save-inventory, restore gamemode for spectators
            for (var p : event.getParticipants().values()) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) {
                    restoreGameMode(pl, p);
                } else if (p.hasOriginalGameMode()) {
                    // Player offline — save a minimal pending restore so their gamemode
                    // is restored the next time they log in (e.g. spectator that crashed)
                    savePendingGameModeRestore(p.getPlayerUuid(), p);
                }
            }
        }

        // Distribute rewards AFTER inventory restore so items aren't lost
        if (event.getRankedCache() != null) {
            distributeRewards(event, event.getRankedCache());
        }

        // TP back
        returnParticipants(event);

        // Remove bossbar & scoreboard
        removeBossbar(event);
        removeScoreboard(event);

        // Update timestamp
        event.setLastRunTimestamp(System.currentTimeMillis());

        // Chain event
        if (!event.getChainEventId().isBlank()) {
            ServerEvent next = getEvent(event.getChainEventId());
            if (next != null && next.getState() == ServerEvent.State.IDLE) {
                announceEvent(next);
            }
        }

        event.resetRuntime();
        save();
        CheeseGuardMod.LOGGER.info("[CG] Event cleanup complete: {}", event.getName());
    }

    // ── Participant management ───────────────────────────────────────────────

    public boolean joinEvent(ServerEvent event, ServerPlayerEntity player, EventParticipant.Role role) {
        // Only allow joining during ANNOUNCED, COUNTDOWN, or ACTIVE
        if (event.getState() != ServerEvent.State.ANNOUNCED
            && event.getState() != ServerEvent.State.COUNTDOWN
            && event.getState() != ServerEvent.State.ACTIVE) return false;
        if (event.getParticipants().containsKey(player.getUuid())) return false;
        if (role == EventParticipant.Role.PARTICIPANT && event.getMaxParticipants() > 0
            && event.getParticipantCount() >= event.getMaxParticipants()) return false;

        EventParticipant p = new EventParticipant(player.getUuid(), player.getName().getString(), role);
        p.savePosition(player);
        event.addParticipant(p);

        player.sendMessage(Text.literal(role == EventParticipant.Role.PARTICIPANT ?
            "✅ Inscrit a l'evenement: " + event.getName() :
            "👁 Spectateur de: " + event.getName()).formatted(Formatting.GREEN), false);

        // If event is already active (late join), apply full setup
        if (event.isActive() && role == EventParticipant.Role.PARTICIPANT) {
            // Save & clear inventory
            if (event.isSaveInventory()) {
                p.saveInventory(player);
                savePendingRestore(p.getPlayerUuid(), p);
                if (event.isForceEmptyInventory()) {
                    EventParticipant.clearInventoryForEvent(player);
                }
            }
            // Enforce blacklist
            if (!event.getBlacklistItems().isEmpty()) {
                enforceItemBlacklistForPlayer(event, player);
            }
            // Kit entry commands
            for (String cmd : event.getKitEntryCommands()) {
                String c = cmd.replace("{player}", player.getName().getString());
                try {
                    String clean = c.startsWith("/") ? c.substring(1) : c;
                    server.getCommandManager().getDispatcher().execute(clean, server.getCommandSource().withSilent());
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.error("[CG] Late-join kit cmd failed: '{}' — {}", c, e.getMessage());
                }
            }
        }

        // TP
        if (event.isActive()) {
            if (role == EventParticipant.Role.PARTICIPANT && !event.getTpParticipantWorld().isBlank()) {
                tpPlayer(player, event.getTpParticipantWorld(), event.getTpParticipantX(), event.getTpParticipantY(), event.getTpParticipantZ());
            } else if (role == EventParticipant.Role.SPECTATOR) {
                applySpectatorMode(player, p);
                if (!event.getTpSpectatorWorld().isBlank()) {
                    tpPlayer(player, event.getTpSpectatorWorld(), event.getTpSpectatorX(), event.getTpSpectatorY(), event.getTpSpectatorZ());
                }
            }
        }
        return true;
    }

    /** Enforce item blacklist for a single player. */
    private void enforceItemBlacklistForPlayer(ServerEvent event, ServerPlayerEntity pl) {
        int removed = 0;
        for (int i = 0; i < pl.getInventory().size(); i++) {
            ItemStack stack = pl.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            if (event.getBlacklistItems().contains(itemId)) {
                pl.getInventory().setStack(i, ItemStack.EMPTY);
                removed++;
            }
        }
        if (removed > 0) {
            pl.currentScreenHandler.sendContentUpdates();
            pl.sendMessage(Text.literal("⚠ " + removed + " item(s) interdit(s) confisque(s).").formatted(Formatting.YELLOW), false);
        }
    }

    public void leaveEvent(ServerEvent event, ServerPlayerEntity player) {
        EventParticipant p = event.getParticipant(player.getUuid());
        if (p == null) return;

        // Restore gamemode (spectators have originalGameMode saved)
        restoreGameMode(player, p);

        // Restore inventory if saved
        if (p.hasInventorySaved()) {
            p.restoreInventory(player);
            clearPendingRestore(player.getUuid());
        }

        // TP back
        returnPlayer(event, player, p);

        event.removeParticipant(player.getUuid());
        player.sendMessage(Text.literal("Vous avez quitte l'evenement.").formatted(Formatting.YELLOW), false);
    }

    // ── Death handling ─────────────────────────────────────────────────────

    /**
     * Find the active event a player is participating in (as PARTICIPANT), if any.
     */
    public ServerEvent getActiveEventForPlayer(UUID uuid) {
        for (ServerEvent event : events) {
            if (event.getState() != ServerEvent.State.ACTIVE) continue;
            EventParticipant p = event.getParticipant(uuid);
            if (p != null && p.getRole() == EventParticipant.Role.PARTICIPANT) return event;
        }
        return null;
    }

    /**
     * Called from mixin when a participant dies during an active event.
     * Saves their inventory and clears it so items don't drop.
     */
    public void onParticipantDeath(ServerPlayerEntity player) {
        ServerEvent event = getActiveEventForPlayer(player.getUuid());
        if (event == null) return;
        EventParticipant p = event.getParticipant(player.getUuid());
        if (p == null) return;

        // Save inventory snapshot and clear so nothing drops
        p.saveDeathInventory(player);

        CheeseGuardMod.LOGGER.info("[CG] Participant {} died during event {}", player.getName().getString(), event.getName());
    }

    /**
     * Called from AFTER_RESPAWN callback. Handles TP back to event or convert to spectator.
     */
    public void onParticipantRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer) {
        // Find event by UUID (newPlayer has same UUID)
        ServerEvent event = null;
        EventParticipant participant = null;
        for (ServerEvent ev : events) {
            if (ev.getState() != ServerEvent.State.ACTIVE) continue;
            EventParticipant p = ev.getParticipant(newPlayer.getUuid());
            if (p != null && p.isDeathPending()) {
                event = ev;
                participant = p;
                break;
            }
        }
        if (event == null || participant == null) return;

        switch (event.getDeathBehavior()) {
            case RESPAWN_EVENT -> {
                // Restore inventory from death snapshot immediately (inventory is fine right away)
                participant.restoreDeathInventory(newPlayer);

                // TP must be deferred by one tick: AFTER_RESPAWN fires before the player entity
                // is fully ready on the server. If we teleport() immediately, vanilla resets
                // the position to the world spawn point a few ms later. Deferring guarantees
                // our TP wins.
                final ServerEvent ev = event;
                pendingRespawnTps.put(newPlayer.getUuid(), () -> {
                    ServerPlayerEntity live = server.getPlayerManager().getPlayer(newPlayer.getUuid());
                    if (live == null) return;
                    if (ev.hasRespawnPos()) {
                        tpPlayer(live, ev.getRespawnWorld(), ev.getRespawnX(), ev.getRespawnY(), ev.getRespawnZ());
                    } else {
                        tpToEvent(ev, live);
                    }
                    live.sendMessage(Text.literal("\u2764 Vous revenez dans l'evenement !")
                        .formatted(Formatting.GREEN), false);
                });
            }
            case BECOME_SPECTATOR -> {
                // Clear death pending, don't restore inventory (it's saved from event start)
                participant.setDeathPending(false);

                // Switch to spectator role AND gamemode
                participant.setRole(EventParticipant.Role.SPECTATOR);
                // Save original gamemode for restoration at cleanup
                if (!participant.hasOriginalGameMode()) {
                    participant.setOriginalGameMode(newPlayer.interactionManager.getGameMode());
                }
                newPlayer.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);

                // TP to spectator position (or participant position if no spectator TP set)
                double sx = event.getTpSpectatorX(), sy = event.getTpSpectatorY(), sz = event.getTpSpectatorZ();
                if (sx == 0 && sy == 0 && sz == 0) {
                    tpToEvent(event, newPlayer);
                } else {
                    tpPlayer(newPlayer, event.getTpSpectatorWorld(), sx, sy, sz);
                }

                newPlayer.sendMessage(Text.literal("\u2620 Vous etes elimine ! Vous passez spectateur.")
                    .formatted(Formatting.RED), false);

                // Broadcast to other participants
                for (var pp : event.getParticipants().values()) {
                    if (pp.getPlayerUuid().equals(newPlayer.getUuid())) continue;
                    ServerPlayerEntity other = server.getPlayerManager().getPlayer(pp.getPlayerUuid());
                    if (other != null) {
                        other.sendMessage(Text.literal("\u2620 " + newPlayer.getName().getString() + " est elimine !")
                            .formatted(Formatting.GRAY), false);
                    }
                }

                // Check if only one participant left (for SURVIVAL victory)
                long alive = event.getParticipants().values().stream()
                    .filter(pp -> pp.getRole() == EventParticipant.Role.PARTICIPANT).count();
                if (event.getVictoryCondition() == ServerEvent.VictoryCondition.SURVIVAL && alive <= 1) {
                    endEvent(event);
                }
                // Check endOnAllDead: if all participants are now spectators, end the event
                if (event.isEndOnAllDead() && alive == 0) {
                    endEvent(event);
                }
            }
        }
    }

    /**
     * TP player to the event's participant TP position.
     */
    private void tpToEvent(ServerEvent event, ServerPlayerEntity player) {
        double px = event.getTpParticipantX(), py = event.getTpParticipantY(), pz = event.getTpParticipantZ();
        if (px == 0 && py == 0 && pz == 0) return; // no TP configured
        tpPlayer(player, event.getTpParticipantWorld(), px, py, pz);
    }

    // ── Score management ─────────────────────────────────────────────────────

    public void addScore(ServerEvent event, UUID playerUuid, int amount) {
        EventParticipant p = event.getParticipant(playerUuid);
        if (p != null && p.getRole() == EventParticipant.Role.PARTICIPANT) {
            // Apply score multiplier from current phase effects
            double mult = 1.0;
            EventPhase phase = event.getCurrentPhase();
            if (phase != null) {
                for (EventEffect eff : phase.getEffects()) {
                    if (eff.getType() == EventEffect.Type.MULTIPLIER && "score".equals(eff.getMultiplierType())) {
                        mult *= eff.getMultiplierValue();
                    }
                }
            }
            int finalAmount = (int) Math.round(amount * mult);
            p.addScore(finalAmount);
            if (event.isScoreboardVisible()) updateScoreboard(event);
        }
    }

    public ScoreManager getScoreManager() { return scoreManager; }
    public MiniGameManager getMiniGameManager() { return miniGameManager; }
    public PlayerStateFlags getPlayerFlags() { return playerFlags; }
    public VoteManager getVoteManager() { return voteManager; }
    public TimelineExecutor getTimelineExecutor() { return timelineExecutor; }
    public MinecraftServer getServer() { return server; }

    // ── TP helpers ───────────────────────────────────────────────────────────

    private void tpParticipants(ServerEvent event) {
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null) continue;
            if (p.getRole() == EventParticipant.Role.PARTICIPANT && !event.getTpParticipantWorld().isBlank()) {
                tpPlayer(pl, event.getTpParticipantWorld(), event.getTpParticipantX(), event.getTpParticipantY(), event.getTpParticipantZ());
            } else if (p.getRole() == EventParticipant.Role.SPECTATOR) {
                applySpectatorMode(pl, p);
                if (!event.getTpSpectatorWorld().isBlank()) {
                    tpPlayer(pl, event.getTpSpectatorWorld(), event.getTpSpectatorX(), event.getTpSpectatorY(), event.getTpSpectatorZ());
                }
            }
        }
    }

    /** Save original gamemode (if not already saved) and switch to SPECTATOR. */
    private void applySpectatorMode(ServerPlayerEntity pl, EventParticipant p) {
        if (!p.hasOriginalGameMode()) {
            p.setOriginalGameMode(pl.interactionManager.getGameMode());
        }
        pl.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
    }

    /** Restore saved gamemode. No-op if originalGameMode was never saved. */
    private void restoreGameMode(ServerPlayerEntity pl, EventParticipant p) {
        if (p.hasOriginalGameMode()) {
            pl.changeGameMode(p.getOriginalGameMode());
        }
    }

    private void returnParticipants(ServerEvent event) {
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) returnPlayer(event, pl, p);
        }
    }

    private void returnPlayer(ServerEvent event, ServerPlayerEntity pl, EventParticipant p) {
        switch (event.getReturnMode()) {
            case "saved" -> tpPlayer(pl, p.getSavedWorld(), p.getSavedX(), p.getSavedY(), p.getSavedZ());
            case "spawn" -> {
                var sp = server.getOverworld().getSpawnPos();
                tpPlayer(pl, "minecraft:overworld", sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5);
            }
            case "coords" -> tpPlayer(pl, event.getReturnWorld(), event.getReturnX(), event.getReturnY(), event.getReturnZ());
        }
    }

    /**
     * Cross-dimension TP: resolves world ID to ServerWorld and teleports.
     * Falls back to same-world /tp if world not found.
     */
    private void tpPlayer(ServerPlayerEntity pl, String worldId, double x, double y, double z) {
        ServerWorld target = null;
        if (worldId != null && !worldId.isBlank()) {
            for (ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(worldId)) { target = w; break; }
            }
        }
        if (target != null && target != pl.getServerWorld()) {
            pl.teleport(target, x, y, z, Set.of(), pl.getYaw(), pl.getPitch());
        } else {
            // Same world or unknown world → use direct teleport on current world
            ServerWorld currentWorld = pl.getServerWorld();
            pl.teleport(currentWorld, x, y, z, Set.of(), pl.getYaw(), pl.getPitch());
        }
    }

    /** Same-world TP (backward compatibility). */
    private void tpPlayer(ServerPlayerEntity pl, double x, double y, double z) {
        tpPlayer(pl, null, x, y, z);
    }

    // ── Reward distribution ──────────────────────────────────────────────────

    private void distributeRewards(ServerEvent event, List<EventParticipant> ranked) {
        for (ServerEvent.EventReward reward : event.getRewards()) {
            List<EventParticipant> targets = new ArrayList<>();
            switch (reward.getRank()) {
                case "1" -> { if (ranked.size() > 0) targets.add(ranked.get(0)); }
                case "2" -> { if (ranked.size() > 1) targets.add(ranked.get(1)); }
                case "3" -> { if (ranked.size() > 2) targets.add(ranked.get(2)); }
                case "top10" -> targets.addAll(ranked.subList(0, Math.min(10, ranked.size())));
                case "all" -> targets.addAll(ranked);
                case "lottery" -> {
                    List<EventParticipant> pool = new ArrayList<>(ranked);
                    Collections.shuffle(pool);
                    targets.addAll(pool.subList(0, Math.min(reward.getLotteryCount(), pool.size())));
                }
            }
            for (EventParticipant t : targets) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(t.getPlayerUuid());
                if (pl == null) {
                    CheeseGuardMod.LOGGER.warn("[CG] Reward target {} offline, skipping", t.getPlayerName());
                    continue;
                }
                for (String cmd : reward.getCommands()) {
                    String c = cmd.replace("{player}", pl.getName().getString())
                        .replace("{score}", String.valueOf(t.getScore()));
                    try {
                        String clean = c.startsWith("/") ? c.substring(1) : c;
                        int result = server.getCommandManager().getDispatcher()
                            .execute(clean, server.getCommandSource().withSilent());
                        if (result == 0) {
                            CheeseGuardMod.LOGGER.warn("[CG] Reward command returned 0: '{}' for {}", clean, pl.getName().getString());
                        } else {
                            CheeseGuardMod.LOGGER.info("[CG] Reward given to {}: {}", pl.getName().getString(), clean);
                        }
                    } catch (Exception e) {
                        CheeseGuardMod.LOGGER.error("[CG] Reward command failed: '{}' — {}", c, e.getMessage());
                        pl.sendMessage(Text.literal("⚠ Recompense echouee: " + c).formatted(Formatting.RED), false);
                        // Fallback
                        try {
                            String clean = c.startsWith("/") ? c : "/" + c;
                            server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), clean);
                        } catch (Exception ignored) {}
                    }
                }
                pl.sendMessage(Text.literal("🎁 Recompense recue ! (Rang " + reward.getRank() + ")").formatted(Formatting.GREEN), false);
            }
        }
    }

    // ── Bossbar ──────────────────────────────────────────────────────────────

    private void createBossbar(ServerEvent event) {
        String cmd = String.format("bossbar add cg_%s \"%s\"", event.getId(), event.getName());
        exec(cmd);
        exec("bossbar set cg_" + event.getId() + " color " + event.getBossbarColor());
        exec("bossbar set cg_" + event.getId() + " players @a");
        exec("bossbar set cg_" + event.getId() + " visible true");
        exec("bossbar set cg_" + event.getId() + " max " + Math.max(1, event.getTotalDurationSeconds()));
        exec("bossbar set cg_" + event.getId() + " value " + event.getTotalDurationSeconds());
    }

    private void updateBossbar(ServerEvent event) {
        int remaining = event.getRemainingSeconds();
        exec("bossbar set cg_" + event.getId() + " value " + remaining);
        String phaseName = event.getCurrentPhase() != null ? " - " + event.getCurrentPhase().getName() : "";
        int min = remaining / 60, sec = remaining % 60;
        exec(String.format("bossbar set cg_%s name \"%s%s [%d:%02d]\"", event.getId(), event.getName(), phaseName, min, sec));
    }

    private void removeBossbar(ServerEvent event) {
        exec("bossbar remove cg_" + event.getId());
    }

    // ── Scoreboard ───────────────────────────────────────────────────────────

    private void createScoreboard(ServerEvent event) {
        exec("scoreboard objectives add cg_" + event.getId() + " dummy \"" + event.getName() + "\"");
        exec("scoreboard objectives setdisplay sidebar cg_" + event.getId());
    }

    private void updateScoreboard(ServerEvent event) {
        for (var p : event.getParticipants().values()) {
            if (p.getRole() == EventParticipant.Role.PARTICIPANT) {
                exec("scoreboard players set " + p.getPlayerName() + " cg_" + event.getId() + " " + p.getScore());
            }
        }
    }

    private void removeScoreboard(ServerEvent event) {
        exec("scoreboard objectives remove cg_" + event.getId());
    }

    // ── Title helper ─────────────────────────────────────────────────────────

    private void sendTitle(ServerPlayerEntity pl, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        String cmd = String.format("title %s times %d %d %d", pl.getName().getString(), fadeIn, stay, fadeOut);
        exec(cmd);
        if (!title.isBlank()) exec(String.format("title %s title \"%s\"", pl.getName().getString(), title));
        if (!subtitle.isBlank()) exec(String.format("title %s subtitle \"%s\"", pl.getName().getString(), subtitle));
    }

    // ── Multiplier API (read by other systems) ───────────────────────────────

    public double getActiveMultiplier(String type) {
        double total = 1.0;
        for (ServerEvent event : events) {
            if (!event.isActive()) continue;
            EventPhase phase = event.getCurrentPhase();
            if (phase == null) continue;
            for (EventEffect eff : phase.getEffects()) {
                if (eff.getType() == EventEffect.Type.MULTIPLIER && eff.getMultiplierType().equals(type)) {
                    total *= eff.getMultiplierValue();
                }
            }
        }
        return total;
    }

    // ── Enhanced Celestials ──────────────────────────────────────────────────

    private void scanLunarEvents() {
        availableLunarEvents.clear();
        Path ecDir = server.getRunDirectory()
            .resolve("config").resolve("enhancedcelestials")
            .resolve("enhancedcelestials").resolve("lunar").resolve("event");
        if (Files.exists(ecDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(ecDir, "*.json")) {
                for (Path p : stream) availableLunarEvents.add("enhancedcelestials:" + p.getFileName().toString().replace(".json", ""));
            } catch (IOException ignored) {}
        }
        if (availableLunarEvents.isEmpty()) {
            for (String n : new String[]{"default", "blood_moon", "super_blood_moon", "harvest_moon", "super_harvest_moon", "blue_moon", "super_blue_moon", "super_moon"})
                availableLunarEvents.add("enhancedcelestials:" + n);
        }
        Collections.sort(availableLunarEvents);
    }

    public void forceLunarEvent(String eventId) {
        if (!enhancedCelestialsLoaded) return;
        exec("enhancedcelestials setLunarEvent " + eventId);
    }

    /**
     * Check if a specific Enhanced Celestials lunar event is currently active.
     * Uses reflection to query the EC API without compile-time dependency.
     */
    private boolean isLunarEventActive(String eventId) {
        if (!enhancedCelestialsLoaded || server.getOverworld() == null) return false;
        try {
            // Try to access EnhancedCelestialsAPI via reflection
            Class<?> apiClass = Class.forName("corgitaco.enhancedcelestials.api.EnhancedCelestialsAPI");
            var getLunarContext = apiClass.getMethod("getLunarContext", ServerWorld.class);
            Object lunarContext = getLunarContext.invoke(null, server.getOverworld());
            if (lunarContext == null) return false;
            // Get current lunar event
            var getCurrentEvent = lunarContext.getClass().getMethod("getCurrentEvent");
            Object currentEvent = getCurrentEvent.invoke(lunarContext);
            if (currentEvent == null) return false;
            // Get event key/name
            var getKey = currentEvent.getClass().getMethod("getKey");
            Object key = getKey.invoke(currentEvent);
            return key != null && key.toString().equalsIgnoreCase(eventId);
        } catch (Exception e) {
            // Fallback: check moon phase via vanilla time (rough approximation)
            // Full moon = day 0, new moon = day 4 in MC cycle of 8
            return false;
        }
    }

    public List<String> getAvailableLunarEvents() { return availableLunarEvents; }
    public boolean isEnhancedCelestialsLoaded() { return enhancedCelestialsLoaded; }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public List<ServerEvent> getEvents() { return events; }
    public ServerEvent getEvent(String id) { for (ServerEvent e : events) if (e.getId().equals(id)) return e; return null; }

    public ServerEvent createEvent(String name) {
        String id = "evt_" + System.currentTimeMillis() % 100000;
        ServerEvent e = new ServerEvent(id, name);
        // Add default phase
        EventPhase p = new EventPhase();
        p.setName("Phase 1");
        p.setDurationSeconds(300);
        e.addPhase(p);
        events.add(e);
        save();
        return e;
    }

    public ServerEvent duplicateEvent(String id) {
        ServerEvent orig = getEvent(id);
        if (orig == null) return null;
        JsonObject json = orig.toJson();
        json.addProperty("id", "evt_" + System.currentTimeMillis() % 100000);
        json.addProperty("name", orig.getName() + " (copie)");
        ServerEvent copy = ServerEvent.fromJson(json);
        events.add(copy);
        save();
        return copy;
    }

    public void deleteEvent(String id) {
        ServerEvent e = getEvent(id);
        if (e != null) {
            if (e.isRunning()) cleanupEvent(e);
            events.remove(e);
            save();
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private Path getConfigPath() { return server.getRunDirectory().resolve("config").resolve("cheeseguard_events.json"); }

    public void save() {
        try {
            JsonArray arr = new JsonArray();
            for (ServerEvent e : events) arr.add(e.toJson());
            Path path = getConfigPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(arr));
        } catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG] Save events err: {}", e.getMessage()); }
    }

    public void load() {
        events.clear();
        try {
            Path path = getConfigPath();
            if (!Files.exists(path)) return;
            JsonArray arr = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            for (JsonElement el : arr) {
                try { events.add(ServerEvent.fromJson(el.getAsJsonObject())); } catch (Exception ignored) {}
            }
        } catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG] Load events err: {}", e.getMessage()); }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void exec(String cmd) {
        try { server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd); }
        catch (Exception e) { CheeseGuardMod.LOGGER.debug("[CG] Exec err: {}", e.getMessage()); }
    }

    /** Execute a command in a specific world's context (dimension-aware). */
    /**
     * Strike lightning on all non-player entities within the lightning radius of the configured center.
     * Uses "execute as @e[...] at @s run summon lightning_bolt" — matches the vanilla command approach.
     * Also sets a pending kill flag so cleanupEvent() finishes off survivors 5 seconds later.
     */
    private void strikeLightningMobs(ServerEvent event) {
        // Resolve center: use dedicated lightning pos if set, otherwise fall back to participant TP pos
        ServerWorld world;
        double cx, cy, cz;
        if (event.hasLightningPos()) {
            cx = event.getLightningX(); cy = event.getLightningY(); cz = event.getLightningZ();
            world = null;
            for (ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(event.getLightningWorld())) { world = w; break; }
            }
        } else {
            cx = event.getTpParticipantX(); cy = event.getTpParticipantY(); cz = event.getTpParticipantZ();
            world = getEventWorld(event);
        }
        if (world == null) world = server.getOverworld();

        int r = event.getLightningRadius();
        // Lightning strike: every non-player entity in radius
        String strikeCmd = String.format(
            "execute as @e[type=!minecraft:player,x=%d,y=%d,z=%d,distance=..%d] at @s run summon minecraft:lightning_bolt ~ ~ ~",
            (int)cx, (int)cy, (int)cz, r);
        execIn(world, strikeCmd);
        CheeseGuardMod.LOGGER.info("[CG] Lightning strike fired at ({},{},{}) r={} in {}", (int)cx,(int)cy,(int)cz, r, world.getRegistryKey().getValue());
    }

    /**
     * Kill surviving mobs in the lightning zone — called at the start of cleanupEvent (~5s after lightning).
     * Belt-and-suspenders: ensures all mobs are dead before players TP back.
     */
    private void killLightningMobs(ServerEvent event) {
        if (!event.isLightningMobsOnEnd()) return;
        ServerWorld world;
        double cx, cy, cz;
        if (event.hasLightningPos()) {
            cx = event.getLightningX(); cy = event.getLightningY(); cz = event.getLightningZ();
            world = null;
            for (ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(event.getLightningWorld())) { world = w; break; }
            }
        } else {
            cx = event.getTpParticipantX(); cy = event.getTpParticipantY(); cz = event.getTpParticipantZ();
            world = getEventWorld(event);
        }
        if (world == null) world = server.getOverworld();

        int r = event.getLightningRadius();
        String killCmd = String.format(
            "kill @e[type=!minecraft:player,x=%d,y=%d,z=%d,distance=..%d]",
            (int)cx, (int)cy, (int)cz, r);
        execIn(world, killCmd);
        CheeseGuardMod.LOGGER.info("[CG] Kill survivors fired at ({},{},{}) r={}", (int)cx,(int)cy,(int)cz, r);
    }

    private void execIn(ServerWorld world, String cmd) {
        try {
            var src = server.getCommandSource().withSilent()
                .withWorld(world)
                .withPosition(new net.minecraft.util.math.Vec3d(0, 64, 0));
            server.getCommandManager().executeWithPrefix(src, cmd);
        } catch (Exception e) { CheeseGuardMod.LOGGER.debug("[CG] ExecIn err: {}", e.getMessage()); }
    }

    /** Execute a command as a player (uses the player's world and position). */
    private void execAs(ServerPlayerEntity player, String cmd) {
        try {
            var src = server.getCommandSource().withSilent()
                .withWorld(player.getServerWorld())
                .withPosition(player.getPos())
                .withEntity(player);
            server.getCommandManager().executeWithPrefix(src, cmd);
        } catch (Exception e) { CheeseGuardMod.LOGGER.debug("[CG] ExecAs err: {}", e.getMessage()); }
    }

    /** Resolve the ServerWorld for an event based on the participant TP world setting. */
    private ServerWorld getEventWorld(ServerEvent event) {
        String worldId = event.getTpParticipantWorld();
        if (worldId != null && !worldId.isBlank()) {
            for (ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(worldId)) return w;
            }
        }
        return server.getOverworld();
    }

    private void transitionTo(ServerEvent event, ServerEvent.State state) {
        event.setState(state);
    }

    private void broadcast(ServerEvent event, String msg, String color) {
        server.getPlayerManager().broadcast(Text.literal(msg).formatted(colorFromName(color)), false);
    }

    public static Formatting colorFromName(String name) {
        if (name == null) return Formatting.WHITE;
        return switch (name.toLowerCase()) {
            case "black" -> Formatting.BLACK; case "dark_blue" -> Formatting.DARK_BLUE;
            case "dark_green" -> Formatting.DARK_GREEN; case "dark_aqua" -> Formatting.DARK_AQUA;
            case "dark_red" -> Formatting.DARK_RED; case "dark_purple" -> Formatting.DARK_PURPLE;
            case "gold" -> Formatting.GOLD; case "gray" -> Formatting.GRAY;
            case "dark_gray" -> Formatting.DARK_GRAY; case "blue" -> Formatting.BLUE;
            case "green" -> Formatting.GREEN; case "aqua" -> Formatting.AQUA;
            case "red" -> Formatting.RED; case "light_purple" -> Formatting.LIGHT_PURPLE;
            case "yellow" -> Formatting.YELLOW; default -> Formatting.WHITE;
        };
    }

    public static int mcColorToHex(String name) {
        return switch (name.toLowerCase()) {
            case "dark_blue" -> 0x0000AA; case "dark_green" -> 0x00AA00; case "dark_aqua" -> 0x00AAAA;
            case "dark_red" -> 0xAA0000; case "dark_purple" -> 0xAA00AA; case "gold" -> 0xFFAA00;
            case "gray" -> 0xAAAAAA; case "dark_gray" -> 0x555555; case "blue" -> 0x5555FF;
            case "green" -> 0x55FF55; case "aqua" -> 0x55FFFF; case "red" -> 0xFF5555;
            case "light_purple" -> 0xFF55FF; case "yellow" -> 0xFFFF55; default -> 0xFFFFFF;
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CRASH-SAFE INVENTORY PERSISTENCE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Represents a pending inventory restore for a player who was in an event
     * when the server crashed or was stopped unexpectedly.
     */
    private static class PendingRestore {
        UUID playerUuid;
        String playerName;
        NbtCompound inventoryData; // Contains: inventory (NbtList), armor (NbtList), offhand (NbtCompound), position
        double savedX, savedY, savedZ;
        String savedWorld;
        /** GameMode to restore on reconnect (for spectators that crashed mid-event). */
        net.minecraft.world.GameMode pendingGameMode = null;

        PendingRestore(UUID uuid, String name, NbtCompound data) {
            this.playerUuid = uuid;
            this.playerName = name;
            this.inventoryData = data;
        }
    }

    /**
     * Save a minimal pending restore for offline spectators that only need their gamemode back.
     * Called when isSaveInventory=false but the player was spectator and is now offline.
     */
    private void savePendingGameModeRestore(UUID uuid, EventParticipant p) {
        try {
            Path dir = getPendingDir();
            Files.createDirectories(dir);
            // Don't overwrite a full inventory restore that may already be there
            Path file = dir.resolve(uuid.toString() + ".nbt");
            if (Files.exists(file)) return; // full restore takes priority

            NbtCompound root = new NbtCompound();
            root.putString("playerName", p.getPlayerName());
            root.putDouble("savedX", p.getSavedX());
            root.putDouble("savedY", p.getSavedY());
            root.putDouble("savedZ", p.getSavedZ());
            root.putString("savedWorld", p.getSavedWorld());
            root.putInt("format", 4); // v4 = gamemode-only restore
            root.putString("originalGameMode", p.getOriginalGameMode().name());

            NbtIo.writeCompressed(root, file);

            PendingRestore pr = new PendingRestore(uuid, p.getPlayerName(), root);
            pr.pendingGameMode = p.getOriginalGameMode();
            pr.savedX = p.getSavedX(); pr.savedY = p.getSavedY(); pr.savedZ = p.getSavedZ();
            pr.savedWorld = p.getSavedWorld();
            pendingRestores.put(uuid, pr);
            CheeseGuardMod.LOGGER.debug("[CG] Saved pending gamemode restore for spectator {}", p.getPlayerName());
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save pending gamemode restore for {}: {}", uuid, e.getMessage());
        }
    }

    /**
     * Save a player's inventory to disk as NBT for crash recovery.
     */
    private void savePendingRestore(UUID uuid, EventParticipant p) {
        try {
            Path dir = getPendingDir();
            Files.createDirectories(dir);
            NbtCompound root = new NbtCompound();
            root.putString("playerName", p.getPlayerName());
            root.putDouble("savedX", p.getSavedX());
            root.putDouble("savedY", p.getSavedY());
            root.putDouble("savedZ", p.getSavedZ());
            root.putString("savedWorld", p.getSavedWorld());
            root.putInt("format", 3); // v3 = full player writeNbt

            // Store originalGameMode so spectators get their gamemode back even after a crash
            if (p.hasOriginalGameMode()) {
                root.putString("originalGameMode", p.getOriginalGameMode().name());
            }

            // Store the full player NBT snapshot
            NbtCompound playerNbt = p.getSavedPlayerNbt();
            if (playerNbt != null) {
                root.put("playerData", playerNbt);
            }

            Path file = dir.resolve(uuid.toString() + ".nbt");
            NbtIo.writeCompressed(root, file);

            pendingRestores.put(uuid, new PendingRestore(uuid, p.getPlayerName(), root));
            CheeseGuardMod.LOGGER.debug("[CG] Saved pending restore for {}", p.getPlayerName());
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save pending restore for {}: {}", uuid, e.getMessage());
        }
    }

    /**
     * Clear a pending restore (called after successful inventory restore).
     */
    private void clearPendingRestore(UUID uuid) {
        pendingRestores.remove(uuid);
        try {
            Path file = getPendingDir().resolve(uuid.toString() + ".nbt");
            Files.deleteIfExists(file);
        } catch (Exception ignored) {}
    }

    /**
     * Load pending restores from disk on server start (crash recovery).
     */
    private void loadPendingRestores() {
        Path dir = getPendingDir();
        if (!Files.exists(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : stream) {
                try {
                    NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
                    if (root == null) continue;
                    String filename = file.getFileName().toString().replace(".nbt", "");
                    UUID uuid = UUID.fromString(filename);
                    String name = root.contains("playerName") ? root.getString("playerName") : "?";
                    PendingRestore pr = new PendingRestore(uuid, name, root);
                    pr.savedX = root.getDouble("savedX");
                    pr.savedY = root.getDouble("savedY");
                    pr.savedZ = root.getDouble("savedZ");
                    pr.savedWorld = root.contains("savedWorld") ? root.getString("savedWorld") : "";
                    if (root.contains("originalGameMode")) {
                        try { pr.pendingGameMode = net.minecraft.world.GameMode.valueOf(root.getString("originalGameMode")); }
                        catch (Exception ignored) {}
                    }
                    pendingRestores.put(uuid, pr);
                    CheeseGuardMod.LOGGER.info("[CG] Found pending inventory restore for: {} ({})", name, uuid);
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.warn("[CG] Failed to load pending restore {}: {}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to scan pending restores: {}", e.getMessage());
        }
        if (!pendingRestores.isEmpty()) {
            CheeseGuardMod.LOGGER.info("[CG] {} pending inventory restores from previous crash", pendingRestores.size());
        }
    }

    /**
     * Called periodically to check if any players with pending restores have logged back in.
     */
    private void tickPendingRestores() {
        var it = pendingRestores.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID uuid = entry.getKey();
            PendingRestore pr = entry.getValue();

            // Check if player is already in an active event (don't restore yet)
            boolean inEvent = events.stream().anyMatch(e -> e.isActive() && e.getParticipant(uuid) != null);
            if (inEvent) continue;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) continue;

            // Restore inventory from NBT
            try {
                NbtCompound root = pr.inventoryData;

                // v4 format: gamemode-only restore (spectator that was offline when event ended)
                if (!root.contains("playerData") && !root.contains("inventoryNative") && !root.contains("inventory")) {
                    // No inventory data — just restore gamemode and TP if needed
                    if (pr.pendingGameMode != null) {
                        player.changeGameMode(pr.pendingGameMode);
                        player.sendMessage(Text.literal("✅ Mode de jeu restaure.").formatted(Formatting.GREEN), false);
                    }
                    if (pr.savedY > 0) {
                        tpPlayer(player, pr.savedX, pr.savedY, pr.savedZ);
                    }
                    it.remove();
                    try { Files.deleteIfExists(getPendingDir().resolve(uuid.toString() + ".nbt")); } catch (Exception ignored) {}
                    continue;
                }

                // v3 format: full player.readNbt() (nuclear restore)
                if (root.contains("playerData")) {
                    NbtCompound playerData = root.getCompound("playerData");
                    // Capture current state to preserve
                    double cx = player.getX(), cy = player.getY(), cz = player.getZ();
                    float cyaw = player.getYaw(), cpitch = player.getPitch();
                    float ch = player.getHealth();
                    int cf = player.getHungerManager().getFoodLevel();
                    // If we have a pendingGameMode (e.g. spectator whose event ended offline),
                    // use that instead of the current SPECTATOR mode so it isn't re-applied.
                    var cgm = pr.pendingGameMode != null ? pr.pendingGameMode
                                : player.interactionManager.getGameMode();
                    int cxl = player.experienceLevel;
                    float cxp = player.experienceProgress;
                    int ctx2 = player.totalExperience;

                    player.readNbt(playerData);

                    player.refreshPositionAndAngles(cx, cy, cz, cyaw, cpitch);
                    player.setHealth(ch);
                    player.getHungerManager().setFoodLevel(cf);
                player.interactionManager.changeGameMode(cgm);
                    player.experienceLevel = cxl;
                    player.experienceProgress = cxp;
                    player.totalExperience = ctx2;
                }
                // v2 format: native inventory writeNbt/readNbt
                else if (root.contains("inventoryNative")) {
                    player.getInventory().clear();
                    NbtList nativeInv = root.getList("inventoryNative", 10);
                    player.getInventory().readNbt(nativeInv);
                }
                // v1 format: legacy slot-by-slot
                else if (root.contains("inventory")) {
                    player.getInventory().clear();
                    NbtList inv = root.getList("inventory", 10);
                    for (int i = 0; i < inv.size(); i++) {
                        NbtCompound tag = inv.getCompound(i);
                        int slot = tag.getInt("Slot");
                        if (tag.contains("Item")) {
                            ItemStack stack = ItemStack.fromNbt(player.getRegistryManager(), tag.getCompound("Item")).orElse(ItemStack.EMPTY);
                            if (slot >= 0 && slot < player.getInventory().size()) {
                                player.getInventory().setStack(slot, stack);
                            }
                        }
                    }
                }

                player.getInventory().markDirty();
                player.currentScreenHandler.sendContentUpdates();
                player.playerScreenHandler.sendContentUpdates();
                player.playerScreenHandler.onContentChanged(player.getInventory());

                // TP back to saved position
                if (pr.savedY > 0) {
                    tpPlayer(player, pr.savedX, pr.savedY, pr.savedZ);
                }

                // Restore gamemode (critical for spectators who crashed mid-event)
                if (pr.pendingGameMode != null) {
                    player.changeGameMode(pr.pendingGameMode);
                }

                player.sendMessage(Text.literal("✅ Votre inventaire a ete restaure apres un arret du serveur.").formatted(Formatting.GREEN), false);
                CheeseGuardMod.LOGGER.info("[CG] Restored inventory for {} from crash backup", pr.playerName);
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] Failed to restore inventory for {}: {}", pr.playerName, e.getMessage());
                player.sendMessage(Text.literal("⚠ Erreur lors de la restauration de votre inventaire. Contactez un admin.").formatted(Formatting.RED), false);
            }

            // Cleanup
            it.remove();
            try {
                Files.deleteIfExists(getPendingDir().resolve(uuid.toString() + ".nbt"));
            } catch (Exception ignored) {}
        }
    }

    private Path getPendingDir() {
        return server.getRunDirectory().resolve("config").resolve("cheeseguard_pending_inv");
    }

    /**
     * Called when a player joins the server — check for pending restore immediately.
     */
    public void onPlayerJoin(ServerPlayerEntity player) {
        if (pendingRestores.containsKey(player.getUuid())) {
            // Delay restore by 2 seconds to ensure player is fully loaded
            // (handled by tickPendingRestores which runs every 3s)
            player.sendMessage(Text.literal("⏳ Restauration de votre inventaire en cours...").formatted(Formatting.YELLOW), false);
        }
    }
}

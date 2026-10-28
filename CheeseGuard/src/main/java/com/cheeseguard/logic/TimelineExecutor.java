package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.*;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

import java.util.*;

/**
 * Drives act transitions and fires timeline blocks for a running event.
 *
 * Lifecycle per event:
 *   1. EventManager calls executor.start(event) when event becomes ACTIVE
 *   2. EventManager calls executor.tick(event, now) every second (20 ticks)
 *   3. EventManager calls executor.end(event) on event end
 *
 * Act transitions happen automatically based on TransitionType:
 *   DURATION  → after configured seconds
 *   CONDITION → checked every second
 *   MANUAL    → only via admin command
 */
public class TimelineExecutor {

    // ── Per-event runtime state ───────────────────────────────────────────────
    private static class EventRuntime {
        int currentActIndex = 0;
        long actStartedAt = 0;           // wall-clock ms when current act began
        int actElapsedSeconds = 0;       // seconds since act started (incremented each tick)

        // blockId -> last execution time in seconds within act (for repeat)
        final Map<String, Integer> blockLastFiredAt = new HashMap<>();
        // blockId -> true if this block has been "started" (for duration blocks)
        final Set<String> activeBlocks = new HashSet<>();
        // blockId -> true if this block has fired at least once (for instant one-shots)
        final Set<String> firedBlocks = new HashSet<>();
    }

    private final Map<String, EventRuntime> runtimes = new HashMap<>();
    private final MinecraftServer server;
    private final PlayerStateFlags flags;
    private final VoteManager voteManager;

    // Frozen players (set by FREEZE_PLAYERS, cleared by UNFREEZE_PLAYERS or event end)
    private final Map<String, Set<UUID>> frozenPlayers = new HashMap<>(); // eventId -> set

    // ── ZONE_POWERUP state ──────────────────────────────────────────────────────
    private static class PowerupZoneState {
        final Set<UUID> consumed = new HashSet<>();
        final Map<UUID, Long> cooldowns = new HashMap<>(); // UUID -> timestamp (ms) when cooldown expires
        boolean active = true;
        BlockPos visualBlockPos;
    }
    // eventId -> (blockId -> state)
    private final Map<String, Map<String, PowerupZoneState>> powerupStates = new HashMap<>();

    public TimelineExecutor(MinecraftServer server, PlayerStateFlags flags, VoteManager voteManager) {
        this.server = server;
        this.flags = flags;
        this.voteManager = voteManager;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start(ServerEvent event) {
        EventRuntime rt = new EventRuntime();
        rt.actStartedAt = System.currentTimeMillis();
        runtimes.put(event.getId(), rt);
        frozenPlayers.put(event.getId(), new HashSet<>());
        startAct(event, rt, 0);
    }

    public void end(ServerEvent event) {
        String eid = event.getId();
        // Unfreeze all frozen players
        Set<UUID> frozen = frozenPlayers.remove(eid);
        if (frozen != null) {
            for (UUID uuid : frozen) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) setFrozen(p, false);
            }
        }
        runtimes.remove(eid);
        powerupStates.remove(eid);
        flags.clearEvent(eid);
        voteManager.clearEvent(eid);
    }

    /**
     * Called exactly once per second (every 20 ticks) during an active event.
     */
    public void tick(ServerEvent event) {
        EventRuntime rt = runtimes.get(event.getId());
        if (rt == null) return;

        rt.actElapsedSeconds++;

        // Enforce frozen players (can't move)
        tickFrozen(event);

        // Tick active vote
        Collection<ServerPlayerEntity> participants = getParticipants(event);
        VoteManager.ActiveVote concluded = voteManager.tick(event.getId(), participants);
        if (concluded != null) {
            onVoteConcluded(event, concluded, rt);
        }

        // Get current act
        List<EventAct> acts = event.getActs();
        if (acts.isEmpty()) return;
        if (rt.currentActIndex >= acts.size()) return;
        EventAct act = acts.get(rt.currentActIndex);

        // Fire timeline blocks
        tickBlocks(event, act, rt);

        // Check act transition
        checkActTransition(event, act, rt);
    }

    // ── Act management ────────────────────────────────────────────────────────

    private void startAct(ServerEvent event, EventRuntime rt, int actIndex) {
        List<EventAct> acts = event.getActs();
        if (actIndex >= acts.size()) return;
        rt.currentActIndex = actIndex;
        rt.actStartedAt = System.currentTimeMillis();
        rt.actElapsedSeconds = 0;
        rt.blockLastFiredAt.clear();
        rt.activeBlocks.clear();
        rt.firedBlocks.clear();

        EventAct act = acts.get(actIndex);
        CheeseGuardMod.LOGGER.info("[CG-TL] Starting act {}/{}: '{}' ({}s)", actIndex + 1, acts.size(), act.getName(), act.getEffectiveDurationSeconds());

        // Play entry title
        if (!act.getEntryTitleText().isBlank()) {
            for (ServerPlayerEntity p : getParticipants(event)) {
                sendTitle(p, colorize(act.getEntryTitleColor(), act.getEntryTitleText()),
                    act.getEntrySubtitleText(), act.getEntryFadeIn(), act.getEntryStay(), act.getEntryFadeOut());
            }
        }

        // Play entry sound
        if (!act.getEntrySound().isBlank()) {
            Identifier id = Identifier.tryParse(act.getEntrySound());
            if (id != null) {
                SoundEvent snd = SoundEvent.of(id);
                for (ServerPlayerEntity p : getParticipants(event)) {
                    p.playSoundToPlayer(snd, SoundCategory.MASTER, 1f, 1f);
                }
            }
        }
    }

    private void checkActTransition(ServerEvent event, EventAct act, EventRuntime rt) {
        boolean shouldAdvance = false;

        switch (act.getTransitionType()) {
            case DURATION -> {
                if (rt.actElapsedSeconds >= act.getDurationSeconds()) shouldAdvance = true;
            }
            case CONDITION -> shouldAdvance = checkTransitionCondition(event, act);
            case MANUAL -> { /* no auto transition */ }
        }

        if (shouldAdvance) {
            int next = rt.currentActIndex + 1;
            List<EventAct> acts = event.getActs();
            if (next < acts.size()) {
                CheeseGuardMod.LOGGER.info("[CG-TL] Act {} done → advancing to act {}", rt.currentActIndex + 1, next + 1);
                startAct(event, rt, next);
            } else {
                CheeseGuardMod.LOGGER.info("[CG-TL] Last act done, ending event '{}'", event.getName());
                // Signal EventManager to end the event
                event.setSignalEnd(true);
            }
        }
    }

    private boolean checkTransitionCondition(ServerEvent event, EventAct act) {
        Collection<ServerPlayerEntity> parts = getParticipants(event);
        int alive = (int) parts.stream()
            .filter(p -> {
                EventParticipant ep = event.getParticipants().get(p.getUuid());
                return ep != null && ep.getRole() == EventParticipant.Role.PARTICIPANT;
            }).count();

        return switch (act.getTransitionCondition()) {
            case PLAYERS_ALIVE_BELOW -> alive < (int) act.getTransitionConditionValue();
            case SCORE_REACHED -> event.getParticipants().values().stream()
                    .anyMatch(p -> p.getScore() >= (int) act.getTransitionConditionValue());
            case ALL_TASKS_COMPLETE -> {
                // Count players whose 'tasks_done' flag >= transitionConditionValue
                int required = (int) act.getTransitionConditionValue();
                yield event.getParticipants().values().stream()
                    .filter(p -> p.getRole() == EventParticipant.Role.PARTICIPANT)
                    .allMatch(p -> flags.getInt(event.getId(), p.getPlayerUuid(), "tasks_done", 0) >= required);
            }
            case VOTE_COMPLETE -> !voteManager.hasActiveVote(event.getId());
            case CUSTOM_FLAG -> {
                String flagKey = act.getTransitionConditionParam();
                String expected = String.valueOf((int)act.getTransitionConditionValue());
                yield event.getParticipants().values().stream()
                    .filter(p -> p.getRole() == EventParticipant.Role.PARTICIPANT)
                    .anyMatch(p -> flags.is(event.getId(), p.getPlayerUuid(), flagKey, expected));
            }
            default -> false;
        };
    }

    // ── Block execution ───────────────────────────────────────────────────────

    private void tickBlocks(ServerEvent event, EventAct act, EventRuntime rt) {
        int elapsed = rt.actElapsedSeconds;

        for (TimelineBlock block : act.getBlocks()) {
            if (block.getEffectType() == null) continue;
            if (!checkBlockCondition(event, block)) continue;

            int start = block.getStartSeconds();
            int end = start + block.getDurationSeconds();
            boolean isInstant = block.getDurationSeconds() == 0;
            int repeat = block.getRepeatIntervalSeconds();

            // Block hasn't started yet
            if (elapsed < start) {
                if (rt.activeBlocks.contains(block.getId())) {
                    // Was active, now expired
                    rt.activeBlocks.remove(block.getId());
                    executeBlockEnd(event, block);
                }
                continue;
            }

            // Instant block: fire once at start time
            if (isInstant) {
                if (!rt.firedBlocks.contains(block.getId())) {
                    rt.firedBlocks.add(block.getId());
                    executeBlockInstant(event, block);
                }
                // Handle repeat for instant blocks
                if (repeat > 0) {
                    int lastFired = rt.blockLastFiredAt.getOrDefault(block.getId(), start - repeat);
                    if (elapsed - lastFired >= repeat) {
                        rt.blockLastFiredAt.put(block.getId(), elapsed);
                        executeBlockInstant(event, block);
                    }
                }
                continue;
            }

            // Duration block: fire start, tick, end
            if (elapsed >= start && elapsed < end) {
                if (!rt.activeBlocks.contains(block.getId())) {
                    rt.activeBlocks.add(block.getId());
                    rt.firedBlocks.add(block.getId());
                    executeBlockStart(event, block);
                }
                // Tick (every second by default, or every repeat interval)
                int lastFired = rt.blockLastFiredAt.getOrDefault(block.getId(), start - 1);
                int interval = repeat > 0 ? repeat : 1;
                if (elapsed - lastFired >= interval) {
                    rt.blockLastFiredAt.put(block.getId(), elapsed);
                    executeBlockTick(event, block, elapsed - start);
                }
            } else if (elapsed >= end && rt.activeBlocks.contains(block.getId())) {
                rt.activeBlocks.remove(block.getId());
                executeBlockEnd(event, block);
            }
        }
    }

    // ── Block condition check ─────────────────────────────────────────────────

    private boolean checkBlockCondition(ServerEvent event, TimelineBlock block) {
        if (block.getConditionType() == TimelineBlock.ConditionType.NONE) return true;
        Collection<EventParticipant> parts = event.getParticipants().values();
        int alive = (int) parts.stream().filter(p -> p.getRole() == EventParticipant.Role.PARTICIPANT).count();

        return switch (block.getConditionType()) {
            case PLAYER_COUNT_BELOW  -> alive < (int) block.getConditionValue();
            case PLAYER_COUNT_ABOVE  -> alive > (int) block.getConditionValue();
            case SCORE_ABOVE -> parts.stream().anyMatch(p -> p.getScore() > (int) block.getConditionValue());
            case SCORE_BELOW -> parts.stream().anyMatch(p -> p.getScore() < (int) block.getConditionValue());
            case PLAYER_FLAG_TRUE  -> flags.countPlayersWithFlag(event.getId(), block.getConditionParam(), "true") > 0;
            case PLAYER_FLAG_FALSE -> flags.countPlayersWithFlag(event.getId(), block.getConditionParam(), "true") == 0;
            case RANDOM_CHANCE -> Math.random() * 100 < block.getConditionValue();
            default -> true;
        };
    }

    // ── Instant block execution ───────────────────────────────────────────────

    private void executeBlockInstant(ServerEvent event, TimelineBlock block) {
        Collection<ServerPlayerEntity> parts = getParticipants(event);
        switch (block.getEffectType()) {
            case BROADCAST           -> executeBroadcast(event, block, parts);
            case TITLE               -> executeTitle(event, block, parts);
            case COMMAND             -> executeCommand(event, block, parts, false);
            case COMMAND_PLAYER      -> executeCommand(event, block, parts, true);
            case ENTITY_SPAWN        -> executeEntitySpawn(event, block);
            case POKEMON_SPAWN       -> executePokemonSpawn(event, block);
            case WEATHER             -> executeWeather(event, block);
            case TIME                -> executeTime(event, block);
            case SOUND               -> executeSound(event, block, parts);
            case FREEZE_PLAYERS      -> executeFreezeAll(event, true);
            case UNFREEZE_PLAYERS    -> executeFreezeAll(event, false);
            case ASSIGN_ROLE_PRIVATE -> executeAssignRole(event, block, parts);
            case TRANSFER_ROLE       -> executeTransferRole(event, block, parts);
            case GIVE_ITEM_TO_PLAYER -> executeGiveItem(event, block, parts);
            case TAKE_ITEM_FROM_PLAYER -> executeTakeItem(event, block, parts);
            case PLAYER_FLAG_SET     -> executePlayerFlagSet(event, block, parts);
            case START_VOTE          -> executeStartVote(event, block, parts);
            case WEIGHTED_RANDOM_COMMAND -> executeWeightedRandom(event, block, parts);
            case SET_BLOCK_AT_POS    -> executeSetBlock(event, block);
            case SPAWN_ITEM_AT_POS   -> executeSpawnItem(event, block);
            case FLAG_OVERRIDE       -> executeFlagOverride(event, block, true);
            case TELEPORT_PLAYER_TO_PLAYER -> executeTpToPlayer(event, block, parts);
            case DEATH_BEHAVIOR_OVERRIDE -> executeDeathBehaviorOverride(event, block);
            case TRANSFORM_ALL -> executeTransformAll(event, block, parts);
            case TRANSFORM_RANDOM -> executeTransformRandom(event, block, parts);
            case TRANSFORM_BY_TEAM -> executeTransformByTeam(event, block, parts);
            case TRANSFORM_REVERT_ALL -> CobbleShiftHook.revertAll();
            case DUNGEON_OPEN -> executeDungeonOpen(event, block, parts);
            case DUNGEON_TICKET_GIVE -> executeDungeonTicketGive(event, block, parts);
            case DUNGEON_MODIFIER -> executeDungeonModifier(event, block);
            case KART_START_ALL -> executeKartStartAll(event, block, parts);
            case KART_STOP_ALL -> executeKartStopAll(event, block, parts);
            default -> CheeseGuardMod.LOGGER.debug("[CG-TL] Unhandled instant block type: {}", block.getEffectType());
        }
    }

    // ── Duration block start/tick/end ─────────────────────────────────────────

    private void executeBlockStart(ServerEvent event, TimelineBlock block) {
        Collection<ServerPlayerEntity> parts = getParticipants(event);
        switch (block.getEffectType()) {
            case POTION             -> {} // applied in tick
            case ACTIONBAR_PERSISTENT -> {} // displayed in tick
            case ZONE_SHRINK        -> {} // interpolated in tick
            case ZONE_HEAL          -> {} // applied in tick
            case ZONE_DAMAGE        -> {} // applied in tick
            case MULTIPLIER         -> executeMultiplierSet(event, block, true);
            case FLAG_OVERRIDE      -> executeFlagOverride(event, block, true);
            case COMMAND            -> executeCommand(event, block, parts, false); // fire at start too
            case BROADCAST          -> executeBroadcast(event, block, parts);
            case TITLE              -> executeTitle(event, block, parts);
            case WEATHER            -> executeWeather(event, block);
            case TIME               -> executeTime(event, block);
            case FREEZE_PLAYERS     -> executeFreezeAll(event, true);
            case SPAWN_STRUCTURE    -> executeSpawnStructure(event, block);
            case ZONE_POWERUP       -> executeZonePowerupStart(event, block);
            default -> executeBlockInstant(event, block);
        }
    }

    private void executeBlockTick(ServerEvent event, TimelineBlock block, int secondsActive) {
        Collection<ServerPlayerEntity> parts = getParticipants(event);
        switch (block.getEffectType()) {
            case POTION             -> executePotion(event, block, parts);
            case ACTIONBAR_PERSISTENT -> executeActionbar(event, block, parts);
            case ZONE_SHRINK        -> executeZoneShrink(event, block, secondsActive);
            case ZONE_HEAL          -> executeZoneHeal(event, block, parts);
            case ZONE_DAMAGE        -> executeZoneDamage(event, block, parts);
            case COMMAND            -> executeCommand(event, block, parts, false);
            case TIME               -> { if (block.getPropBool("freeze", false)) executeTime(event, block); }
            case ZONE_POWERUP       -> executeZonePowerupTick(event, block, secondsActive);
            default -> {} // most things only fire once
        }
    }

    private void executeBlockEnd(ServerEvent event, TimelineBlock block) {
        switch (block.getEffectType()) {
            case MULTIPLIER     -> executeMultiplierSet(event, block, false);
            case FLAG_OVERRIDE  -> executeFlagOverride(event, block, false);
            case FREEZE_PLAYERS -> executeFreezeAll(event, false);
            case ZONE_POWERUP   -> executeZonePowerupEnd(event, block);
            default -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ── Effect implementations ────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    private void executeBroadcast(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String msg = block.getProp("message", "");
        String color = block.getProp("color", "white");
        String channel = block.getProp("channel", "chat"); // chat | actionbar | title
        if (msg.isBlank()) return;
        String colored = colorize(color, msg);
        Text text = Text.literal(colored);
        switch (channel) {
            case "actionbar" -> parts.forEach(p -> p.sendMessage(text, true));
            case "title" -> {
                String sub = block.getProp("subtitle", "");
                parts.forEach(p -> sendTitle(p, colored, colorize(color, sub), 10, 60, 10));
            }
            default -> {
                // Chat: to all participants or global
                boolean global = block.getPropBool("global", false);
                if (global) server.getPlayerManager().broadcast(text, false);
                else parts.forEach(p -> p.sendMessage(text, false));
            }
        }
    }

    private void executeTitle(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String title = block.getProp("titleText", "");
        String sub = block.getProp("subtitleText", "");
        String color = block.getProp("titleColor", "white");
        int fi = block.getPropInt("fadeIn", 10);
        int st = block.getPropInt("stay", 60);
        int fo = block.getPropInt("fadeOut", 10);
        for (ServerPlayerEntity p : parts) sendTitle(p, colorize(color, title), colorize("white", sub), fi, st, fo);
    }

    private void executeActionbar(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String msg = block.getProp("message", "");
        String color = block.getProp("color", "white");
        if (msg.isBlank()) return;
        Text text = Text.literal(colorize(color, msg));
        parts.forEach(p -> p.sendMessage(text, true));
    }

    private void executePotion(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String potionId = block.getProp("potionId", "minecraft:speed");
        int amplifier = block.getPropInt("amplifier", 0);
        int durationTicks = block.getPropInt("potionDuration", 1200);

        Identifier id = Identifier.tryParse(potionId);
        if (id == null) return;
        var opt = Registries.STATUS_EFFECT.getEntry(id);
        if (opt.isEmpty()) return;
        net.minecraft.registry.entry.RegistryEntry<StatusEffect> entry = opt.get();

        String target = block.getProp("target", "all");
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            p.addStatusEffect(new StatusEffectInstance(entry, durationTicks, amplifier, false, false, true));
        }
    }

    private void executeSound(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String soundId = block.getProp("soundId", "minecraft:entity.experience_orb.pickup");
        float volume = (float) block.getPropDouble("volume", 1.0);
        float pitch = (float) block.getPropDouble("pitch", 1.0);
        Identifier id = Identifier.tryParse(soundId);
        if (id == null) return;
        SoundEvent sound = SoundEvent.of(id);
        String target = block.getProp("target", "all");
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            p.playSoundToPlayer(sound, SoundCategory.MASTER, volume, pitch);
        }
    }

    private void executeWeather(ServerEvent event, TimelineBlock block) {
        String type = block.getProp("weatherType", "clear");
        ServerWorld world = getEventWorld(event);
        if (world == null) return;
        int duration = block.getDurationSeconds() > 0 ? block.getDurationSeconds() * 20 : 6000;
        switch (type) {
            case "clear" -> { world.setWeather(duration, 0, false, false); }
            case "rain"  -> { world.setWeather(0, duration, true, false); }
            case "thunder" -> { world.setWeather(0, duration, true, true); }
        }
    }

    private void executeTime(ServerEvent event, TimelineBlock block) {
        long time = block.getPropInt("timeValue", 6000);
        ServerWorld world = getEventWorld(event);
        if (world == null) return;
        if (block.getPropBool("freeze", false)) {
            world.setTimeOfDay(time);
        } else {
            world.setTimeOfDay(time);
        }
    }

    private void executeCommand(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts, boolean asPlayer) {
        String cmd = block.getProp("command", "");
        if (cmd.isBlank()) return;
        String target = block.getProp("target", "all"); // all | zone | random | team
        String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;

        Collection<ServerPlayerEntity> targets = filterByTarget(event, parts, target, block);
        for (ServerPlayerEntity p : targets) {
            String c = clean.replace("{player}", p.getName().getString())
                            .replace("{uuid}", p.getUuidAsString())
                            .replace("{x}", String.format("%.1f", p.getX()))
                            .replace("{y}", String.format("%.1f", p.getY()))
                            .replace("{z}", String.format("%.1f", p.getZ()));
            try {
                if (asPlayer) {
                    server.getCommandManager().getDispatcher().execute(c, p.getCommandSource().withSilent());
                } else {
                    server.getCommandManager().getDispatcher().execute(c, server.getCommandSource().withSilent());
                }
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG-TL] Command failed: '{}' – {}", c, e.getMessage());
            }
        }
    }

    private void executeEntitySpawn(ServerEvent event, TimelineBlock block) {
        String cmd = block.getProp("command", "");
        if (cmd.isBlank()) return;
        String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        try {
            server.getCommandManager().getDispatcher().execute(clean, server.getCommandSource().withSilent());
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] Entity spawn command failed: '{}'", clean);
        }
    }

    private void executePokemonSpawn(ServerEvent event, TimelineBlock block) {
        if (!CobblemonHook.isAvailable()) return;
        String species = block.getProp("species", "");
        String level = block.getProp("level", "50");
        double x = block.getPropDouble("x", 0), y = block.getPropDouble("y", 64), z = block.getPropDouble("z", 0);
        String world = block.getProp("world", "");
        ServerWorld sw = getWorldByKey(world, event);
        if (sw == null) return;
        String spawnCmd = "pokespawn " + species + " level=" + level + " pos=" + (int)x + " " + (int)y + " " + (int)z;
        try {
            server.getCommandManager().getDispatcher().execute(spawnCmd, server.getCommandSource().withSilent().withWorld(sw));
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] Pokemon spawn failed: {}", e.getMessage());
        }
    }

    private void executeZoneShrink(ServerEvent event, TimelineBlock block, int secondsActive) {
        // Accept both old keys (cx/cz) and new keys (zoneCenterX/Z) for backward compat
        double cx = block.getPropDouble("zoneCenterX", block.getPropDouble("cx", 0));
        double cz = block.getPropDouble("zoneCenterZ", block.getPropDouble("cz", 0));
        double ri = block.getPropDouble("radiusInitial", 200), rf = block.getPropDouble("radiusFinal", 50);
        int total = block.getDurationSeconds();
        if (total <= 0) return;

        double progress = Math.min(1.0, (double)secondsActive / total);
        // Apply easing curve
        String curve = block.getProp("curve", "linear");
        double t = switch (curve) {
            case "ease_in"  -> progress * progress;
            case "ease_out" -> 1 - (1 - progress) * (1 - progress);
            case "ease_io"  -> progress < 0.5 ? 2*progress*progress : 1-Math.pow(-2*progress+2,2)/2;
            default -> progress; // linear
        };

        double currentRadius = ri + (rf - ri) * t;

        // Orbit support: center rotates around the pivot point
        if (block.getPropBool("orbitEnabled", false)) {
            double orbitR = block.getPropDouble("orbitRadius", 0);
            double orbitSpd = block.getPropDouble("orbitSpeed", 30);
            if (orbitR > 0) {
                double angleRad = Math.toRadians(secondsActive * orbitSpd);
                cx = cx + orbitR * Math.cos(angleRad);
                cz = cz + orbitR * Math.sin(angleRad);
            }
        }

        double damage = block.getPropDouble("zoneDamage", block.getPropDouble("damagePerSec", 1.0));

        // Apply damage to players outside radius
        Collection<ServerPlayerEntity> parts = getParticipants(event);
        for (ServerPlayerEntity p : parts) {
            EventParticipant ep = event.getParticipants().get(p.getUuid());
            if (ep == null || ep.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            double dist = Math.sqrt(Math.pow(p.getX()-cx,2) + Math.pow(p.getZ()-cz,2));
            if (dist > currentRadius) {
                p.damage(p.getDamageSources().magic(), (float)damage);
                p.sendMessage(Text.translatable("cheeseguard.timeline.zone_outside").formatted(Formatting.RED), true);
            }
        }
    }

    private void executeZoneHeal(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        float amount = (float)block.getPropDouble("amount", 1.0);
        String target = block.getProp("target", "all");
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            float cur = p.getHealth(), max = p.getMaxHealth();
            p.setHealth(Math.min(max, cur + amount));
        }
    }

    private void executeZoneDamage(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        float amount = (float)block.getPropDouble("amount", 1.0);
        String target = block.getProp("target", "all");
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            p.damage(p.getDamageSources().magic(), amount);
        }
    }

    private void executeMultiplierSet(ServerEvent event, TimelineBlock block, boolean start) {
        // Multipliers are passive (checked by EventManager during score calculation)
        // We store the current active multiplier on the event's properties
        if (start) {
            String type = block.getProp("multType", "score");
            double value = block.getPropDouble("multValue", 2.0);
            event.setActiveMultiplier(type, value);
        } else {
            event.clearActiveMultiplier(block.getProp("multType", "score"));
        }
    }

    private void executeFlagOverride(ServerEvent event, TimelineBlock block, boolean start) {
        String flagId = block.getProp("flagId", "");
        String zoneId = block.getProp("targetZone", "");
        boolean value = block.getPropBool("flagValue", true);
        if (flagId.isBlank()) return;
        // Store override in event's transient override map
        if (start) event.setFlagOverride(zoneId + ":" + flagId, value);
        else event.clearFlagOverride(zoneId + ":" + flagId);
    }

    private void executeFreezeAll(ServerEvent event, boolean freeze) {
        Set<UUID> frozenSet = frozenPlayers.computeIfAbsent(event.getId(), k -> new HashSet<>());
        for (ServerPlayerEntity p : getParticipants(event)) {
            EventParticipant ep = event.getParticipants().get(p.getUuid());
            if (ep == null || ep.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            setFrozen(p, freeze);
            if (freeze) frozenSet.add(p.getUuid());
            else frozenSet.remove(p.getUuid());
        }
    }

    private static final Identifier ID_SLOWNESS   = Identifier.of("minecraft", "slowness");
    private static final Identifier ID_JUMP_BOOST = Identifier.of("minecraft", "jump_boost");

    private void setFrozen(ServerPlayerEntity p, boolean frozen) {
        if (frozen) {
            applyFreezeEffects(p);
        }
    }

    private void applyFreezeEffects(ServerPlayerEntity p) {
        var slowOpt = Registries.STATUS_EFFECT.getEntry(ID_SLOWNESS);
        slowOpt.ifPresent(e -> p.addStatusEffect(new StatusEffectInstance(e, 30, 254, false, false, false)));
        // Cancel all movement — velocity approach is more reliable than Jump Boost potion
        p.setVelocity(0, Math.min(0, p.getVelocity().y), 0);
        p.velocityModified = true;
    }

    private void tickFrozen(ServerEvent event) {
        Set<UUID> frozenSet = frozenPlayers.get(event.getId());
        if (frozenSet == null || frozenSet.isEmpty()) return;
        for (UUID uuid : frozenSet) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) applyFreezeEffects(p);
        }
    }

    private void executeAssignRole(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String roleName = block.getProp("roleName", "inconnu");
        String strategy = block.getProp("strategy", "random"); // random | first | all
        List<ServerPlayerEntity> eligible = parts.stream()
            .filter(p -> {
                EventParticipant ep = event.getParticipants().get(p.getUuid());
                return ep != null && ep.getRole() == EventParticipant.Role.PARTICIPANT;
            }).collect(java.util.stream.Collectors.toList());

        if (eligible.isEmpty()) return;

        int count = block.getPropInt("count", 1); // how many players get the role

        List<ServerPlayerEntity> chosen;
        if ("all".equals(strategy)) {
            chosen = eligible;
        } else if ("first".equals(strategy)) {
            chosen = eligible.subList(0, Math.min(count, eligible.size()));
        } else {
            // random
            Collections.shuffle(eligible);
            chosen = eligible.subList(0, Math.min(count, eligible.size()));
        }

        for (ServerPlayerEntity p : chosen) {
            flags.set(event.getId(), p.getUuid(), "role", roleName);
            // Private title reveal
            String revealMsg = block.getProp("revealMessage", "Votre rôle: " + roleName);
            String revealColor = block.getProp("revealColor", "gold");
            sendTitle(p, colorize(revealColor, "🎭 " + roleName), colorize("white", revealMsg), 10, 80, 20);
            p.sendMessage(Text.translatable("cheeseguard.timeline.role_assigned", roleName).formatted(EventManager.colorFromName(revealColor)), false);
        }

        // Optionally tell others they're the other role
        String otherRole = block.getProp("otherRoleName", "");
        if (!otherRole.isBlank()) {
            for (ServerPlayerEntity p : eligible) {
                if (!chosen.contains(p)) {
                    flags.set(event.getId(), p.getUuid(), "role", otherRole);
                    String revealMsg = block.getProp("otherRevealMessage", "Votre rôle: " + otherRole);
                    String revealColor = block.getProp("otherRevealColor", "white");
                    sendTitle(p, colorize(revealColor, "🎭 " + otherRole), colorize("white", revealMsg), 10, 80, 20);
                }
            }
        }

        CheeseGuardMod.LOGGER.info("[CG-TL] Assigned role '{}' to {}/{} players", roleName, chosen.size(), eligible.size());
    }

    private void executeTransferRole(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String fromRole = block.getProp("fromRole", "");
        String toRole = block.getProp("toRole", "");
        if (fromRole.isBlank() || toRole.isBlank()) return;
        for (ServerPlayerEntity p : parts) {
            if (flags.is(event.getId(), p.getUuid(), "role", fromRole)) {
                flags.set(event.getId(), p.getUuid(), "role", toRole);
                p.sendMessage(Text.translatable("cheeseguard.timeline.role_changed", toRole).formatted(Formatting.YELLOW), false);
            }
        }
    }

    private void executeGiveItem(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String itemId = block.getProp("itemId", "minecraft:diamond");
        int count = block.getPropInt("count", 1);
        String target = block.getProp("target", "all");
        String cmd = "give {player} " + itemId + " " + count;
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            String c = cmd.replace("{player}", p.getName().getString());
            try { server.getCommandManager().getDispatcher().execute(c, server.getCommandSource().withSilent()); }
            catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG-TL] give failed: {}", e.getMessage()); }
        }
    }

    private void executeTakeItem(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String itemId = block.getProp("itemId", "");
        if (itemId.isBlank()) return;
        String target = block.getProp("target", "all");
        String cmd = "clear {player} " + itemId;
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            String c = cmd.replace("{player}", p.getName().getString());
            try { server.getCommandManager().getDispatcher().execute(c, server.getCommandSource().withSilent()); }
            catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG-TL] clear failed: {}", e.getMessage()); }
        }
    }

    private void executePlayerFlagSet(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String key = block.getProp("flagKey", "");
        String value = block.getProp("flagValue", "");
        String target = block.getProp("target", "all");
        if (key.isBlank()) return;
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            flags.set(event.getId(), p.getUuid(), key, value);
        }
    }

    private void executeStartVote(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        if (voteManager.hasActiveVote(event.getId())) return; // already voting
        String question = block.getProp("question", "Quel est votre choix ?");
        String typeStr = block.getProp("voteType", "OPTION_VOTE");
        VoteManager.VoteType type;
        try { type = VoteManager.VoteType.valueOf(typeStr); } catch (Exception e) { type = VoteManager.VoteType.YESNO; }
        int duration = block.getPropInt("duration", 30);

        List<String> options = new ArrayList<>();
        if (type == VoteManager.VoteType.YESNO) {
            options.add("Yes"); options.add("No");
        } else if (type == VoteManager.VoteType.PLAYER_VOTE) {
            // Options = all living participants
            for (ServerPlayerEntity p : parts) {
                EventParticipant ep = event.getParticipants().get(p.getUuid());
                if (ep != null && ep.getRole() == EventParticipant.Role.PARTICIPANT) options.add(p.getName().getString());
            }
        } else {
            // Options from properties: opt0, opt1, opt2…
            for (int i = 0; block.getProperties().has("opt" + i); i++) {
                options.add(block.getProp("opt" + i, "Option " + i));
            }
            if (options.isEmpty()) { options.add("Option A"); options.add("Option B"); }
        }

        voteManager.startVote(event.getId(), question, type, options, duration, parts);
        CheeseGuardMod.LOGGER.info("[CG-TL] Vote started: '{}' ({} options, {}s)", question, options.size(), duration);
    }

    private void executeWeightedRandom(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        // Commands stored as: cmd0, weight0, cmd1, weight1, …
        List<String> cmds = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (int i = 0; block.getProperties().has("cmd" + i); i++) {
            cmds.add(block.getProp("cmd" + i, ""));
            weights.add(block.getPropInt("weight" + i, 1));
        }
        if (cmds.isEmpty()) return;

        // Weighted random selection
        int totalWeight = weights.stream().mapToInt(Integer::intValue).sum();
        int rand = (int)(Math.random() * totalWeight);
        int chosen = 0, sum = 0;
        for (int i = 0; i < weights.size(); i++) {
            sum += weights.get(i);
            if (rand < sum) { chosen = i; break; }
        }

        String cmd = cmds.get(chosen);
        if (cmd.isBlank()) return;
        String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        String target = block.getProp("target", "all");
        for (ServerPlayerEntity p : filterByTarget(event, parts, target, block)) {
            String c = clean.replace("{player}", p.getName().getString());
            try { server.getCommandManager().getDispatcher().execute(c, server.getCommandSource().withSilent()); }
            catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG-TL] weighted_random cmd failed: {}", e.getMessage()); }
        }
    }

    private void executeSetBlock(ServerEvent event, TimelineBlock block) {
        String blockId = block.getProp("blockId", "minecraft:air");
        int bx = block.getPropInt("bx", 0), by = block.getPropInt("by", 64), bz = block.getPropInt("bz", 0);
        String worldStr = block.getProp("world", "");
        ServerWorld world = getWorldByKey(worldStr, event);
        if (world == null) return;
        try {
            net.minecraft.block.Block b = Registries.BLOCK.get(Identifier.tryParse(blockId));
            world.setBlockState(new BlockPos(bx, by, bz), b.getDefaultState());
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] setblock failed: {}", e.getMessage());
        }
    }

    private void executeSpawnItem(ServerEvent event, TimelineBlock block) {
        String itemId = block.getProp("itemId", "minecraft:diamond");
        double x = block.getPropDouble("x", 0), y = block.getPropDouble("y", 64), z = block.getPropDouble("z", 0);
        String cmd = "summon minecraft:item " + (int)x + " " + (int)y + " " + (int)z + " {Item:{id:\"" + itemId + "\",Count:1}}";
        try { server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource().withSilent()); }
        catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG-TL] spawnItem failed: {}", e.getMessage()); }
    }

    private void executeTpToPlayer(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String targetRole = block.getProp("targetRole", "");
        // Find the target player by role or name
        ServerPlayerEntity targetP = null;
        for (ServerPlayerEntity p : parts) {
            if (!targetRole.isBlank() && flags.is(event.getId(), p.getUuid(), "role", targetRole)) {
                targetP = p; break;
            }
        }
        if (targetP == null) return;
        for (ServerPlayerEntity p : parts) {
            if (!p.equals(targetP)) p.teleport(targetP.getServerWorld(), targetP.getX(), targetP.getY(), targetP.getZ(), targetP.getYaw(), targetP.getPitch());
        }
    }

    private void executeDeathBehaviorOverride(ServerEvent event, TimelineBlock block) {
        String behavior = block.getProp("behavior", "RESPAWN_EVENT");
        // Store in event runtime so the death handler reads it
        event.setRuntimeDeathBehaviorOverride(behavior);
    }

    private void executeSpawnStructure(ServerEvent event, TimelineBlock block) {
        String name = block.getProp("structureName", "");
        int x = block.getPropInt("x", 0), y = block.getPropInt("y", 64), z = block.getPropInt("z", 0);
        String cmd = "place structure " + name + " " + x + " " + y + " " + z;
        try { server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource().withSilent()); }
        catch (Exception e) { CheeseGuardMod.LOGGER.warn("[CG-TL] spawnStructure failed: {}", e.getMessage()); }
    }

    private void onVoteConcluded(ServerEvent event, VoteManager.ActiveVote vote, EventRuntime rt) {
        // Set event-level flag with the winner
        if (vote.winnerId != null) {
            flags.set(event.getId(), UUID.fromString("00000000-0000-0000-0000-000000000000"),
                "last_vote_winner", vote.winnerId);
        }
        CheeseGuardMod.LOGGER.info("[CG-TL] Vote concluded for event '{}'. Winner: {}", event.getName(), vote.winnerId);
    }

    // ── ZONE_POWERUP Effects ─────────────────────────────────────────────────

    private static final Map<String, float[]> POWERUP_COLORS = Map.of(
        "SPEED",         new float[]{0.2f, 0.6f, 1.0f},
        "JUMP_BOOST",    new float[]{0.2f, 0.9f, 0.3f},
        "STRENGTH",      new float[]{0.9f, 0.2f, 0.2f},
        "RESISTANCE",    new float[]{0.7f, 0.7f, 0.7f},
        "REGENERATION",  new float[]{1.0f, 0.4f, 0.7f},
        "HASTE",         new float[]{1.0f, 0.8f, 0.0f},
        "SLOW_FALLING",  new float[]{0.8f, 0.9f, 1.0f}
    );

    private static final Map<String, String> POWERUP_POTION_IDS = Map.of(
        "SPEED",         "minecraft:speed",
        "JUMP_BOOST",    "minecraft:jump_boost",
        "STRENGTH",      "minecraft:strength",
        "RESISTANCE",    "minecraft:resistance",
        "REGENERATION",  "minecraft:regeneration",
        "HASTE",         "minecraft:haste",
        "SLOW_FALLING",  "minecraft:slow_falling"
    );

    private void executeZonePowerupStart(ServerEvent event, TimelineBlock block) {
        double px = block.getPropDouble("powerX", 0);
        double py = block.getPropDouble("powerY", 64);
        double pz = block.getPropDouble("powerZ", 0);
        String worldKey = block.getProp("powerWorld", "");
        String visualBlockId = block.getProp("visualBlock", "minecraft:end_rod");

        ServerWorld world = getWorldByKey(worldKey, event);
        if (world == null) return;

        BlockPos pos = new BlockPos((int) px, (int) py, (int) pz);

        // Place decorative block
        try {
            Block vBlock = Registries.BLOCK.get(Identifier.tryParse(visualBlockId));
            if (vBlock != null && vBlock != Blocks.AIR) {
                world.setBlockState(pos, vBlock.getDefaultState());
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] ZONE_POWERUP: failed to place visual block: {}", e.getMessage());
        }

        // Create state
        PowerupZoneState state = new PowerupZoneState();
        state.visualBlockPos = pos;
        powerupStates.computeIfAbsent(event.getId(), k -> new HashMap<>()).put(block.getId(), state);

        // Play activation sound to all participants
        SoundEvent snd = SoundEvent.of(Identifier.of("minecraft", "block.beacon.activate"));
        for (ServerPlayerEntity p : getParticipants(event)) {
            p.playSoundToPlayer(snd, SoundCategory.MASTER, 1f, 1f);
        }

        CheeseGuardMod.LOGGER.info("[CG-TL] ZONE_POWERUP started at {},{},{} type={}", (int) px, (int) py, (int) pz, block.getProp("powerType", "SPEED"));
    }

    private void executeZonePowerupTick(ServerEvent event, TimelineBlock block, int secondsActive) {
        Map<String, PowerupZoneState> eventStates = powerupStates.get(event.getId());
        if (eventStates == null) return;
        PowerupZoneState state = eventStates.get(block.getId());
        if (state == null || !state.active) return;

        double px = block.getPropDouble("powerX", 0);
        double py = block.getPropDouble("powerY", 64);
        double pz = block.getPropDouble("powerZ", 0);
        double radius = block.getPropDouble("powerRadius", 3.0);
        String powerType = block.getProp("powerType", "SPEED");
        int amplifier = block.getPropInt("powerAmplifier", 1);
        int durationTicks = block.getPropInt("powerDurationTicks", 200);
        boolean singleUse = block.getPropBool("singleUse", false);
        int cooldownSec = block.getPropInt("cooldownSeconds", 0);
        String label = block.getProp("powerLabel", "");
        if (label.isBlank()) label = powerType.replace("_", " ");
        String worldKey = block.getProp("powerWorld", "");
        ServerWorld world = getWorldByKey(worldKey, event);
        if (world == null) return;

        float[] rgb = POWERUP_COLORS.getOrDefault(powerType, new float[]{1f, 1f, 1f});
        long now = System.currentTimeMillis();

        // Check proximity for each participant
        Collection<ServerPlayerEntity> parts = getParticipants(event);
        for (ServerPlayerEntity p : parts) {
            EventParticipant ep = event.getParticipants().get(p.getUuid());
            if (ep == null || ep.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            if (!p.getServerWorld().equals(world)) continue;

            double dist = Math.sqrt(Math.pow(p.getX() - px, 2) + Math.pow(p.getY() - py, 2) + Math.pow(p.getZ() - pz, 2));
            if (dist > radius) continue;

            // Check consumed (single-use)
            if (singleUse && state.consumed.contains(p.getUuid())) continue;
            // Check cooldown
            Long cooldownEnd = state.cooldowns.get(p.getUuid());
            if (cooldownEnd != null && now < cooldownEnd) continue;

            // Apply potion effect
            String potionId = POWERUP_POTION_IDS.getOrDefault(powerType, "minecraft:speed");
            Identifier effectId = Identifier.tryParse(potionId);
            if (effectId != null) {
                var opt = Registries.STATUS_EFFECT.getEntry(effectId);
                opt.ifPresent(entry -> p.addStatusEffect(new StatusEffectInstance(entry, durationTicks, amplifier, false, true, true)));
            }

            // Actionbar message
            p.sendMessage(Text.translatable("cheeseguard.minigame.powerup", label).formatted(Formatting.GOLD), true);

            // Sound
            SoundEvent snd = SoundEvent.of(Identifier.of("minecraft", "entity.player.levelup"));
            p.playSoundToPlayer(snd, SoundCategory.MASTER, 0.8f, 1.5f);

            // Particle burst around player
            DustParticleEffect dust = new DustParticleEffect(new Vector3f(rgb[0], rgb[1], rgb[2]), 1.2f);
            for (int i = 0; i < 30; i++) {
                double ox = (Math.random() - 0.5) * 2.0;
                double oy = Math.random() * 2.0;
                double oz = (Math.random() - 0.5) * 2.0;
                world.spawnParticles(dust, p.getX() + ox, p.getY() + oy, p.getZ() + oz, 1, 0, 0, 0, 0);
            }

            // Mark consumed or set cooldown
            if (singleUse) {
                state.consumed.add(p.getUuid());
                state.active = false;
                // Remove visual block
                world.setBlockState(state.visualBlockPos, Blocks.AIR.getDefaultState());
                CheeseGuardMod.LOGGER.info("[CG-TL] ZONE_POWERUP consumed by {} (single-use)", p.getName().getString());
                return; // Zone is done
            } else if (cooldownSec > 0) {
                state.cooldowns.put(p.getUuid(), now + cooldownSec * 1000L);
            }
        }

        // Ambient particles (if still active)
        if (state.active) {
            DustParticleEffect dust = new DustParticleEffect(new Vector3f(rgb[0], rgb[1], rgb[2]), 0.8f);
            // Column: 5 levels above center
            for (int level = 0; level < 5; level++) {
                double y = py + 0.5 + level * 0.6;
                for (int i = 0; i < 2; i++) {
                    double ox = (Math.random() - 0.5) * 0.4;
                    double oz = (Math.random() - 0.5) * 0.4;
                    world.spawnParticles(dust, px + 0.5 + ox, y, pz + 0.5 + oz, 1, 0, 0, 0, 0);
                }
            }
            // Ring: 12 particles at ground level at radius distance
            for (int i = 0; i < 12; i++) {
                double angle = (2 * Math.PI / 12) * i;
                double rx = px + 0.5 + radius * Math.cos(angle);
                double rz = pz + 0.5 + radius * Math.sin(angle);
                world.spawnParticles(dust, rx, py + 0.2, rz, 1, 0, 0, 0, 0);
            }
        }
    }

    private void executeZonePowerupEnd(ServerEvent event, TimelineBlock block) {
        Map<String, PowerupZoneState> eventStates = powerupStates.get(event.getId());
        if (eventStates == null) return;
        PowerupZoneState state = eventStates.remove(block.getId());
        if (state == null) return;

        // Remove visual block if still placed
        if (state.active && state.visualBlockPos != null) {
            String worldKey = block.getProp("powerWorld", "");
            ServerWorld world = getWorldByKey(worldKey, event);
            if (world != null) {
                world.setBlockState(state.visualBlockPos, Blocks.AIR.getDefaultState());
            }
        }
    }

    // ── CobbleShift Transform Effects ────────────────────────────────────────

    private void executeTransformAll(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        if (!CobbleShiftHook.isAvailable()) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] TRANSFORM_ALL: CobbleShift not available");
            return;
        }
        String species = block.getProp("transformSpecies", "pikachu");
        CobbleShiftHook.transformAll(parts, species);
        CheeseGuardMod.LOGGER.info("[CG-TL] TRANSFORM_ALL: {} players → {}", parts.size(), species);
    }

    private void executeTransformRandom(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        if (!CobbleShiftHook.isAvailable()) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] TRANSFORM_RANDOM: CobbleShift not available");
            return;
        }
        String speciesList = block.getProp("transformSpeciesList", "pikachu,charizard,mewtwo");
        boolean allowDuplicates = block.getPropBool("allowDuplicates", true);
        CobbleShiftHook.transformRandom(parts, speciesList, allowDuplicates);
        CheeseGuardMod.LOGGER.info("[CG-TL] TRANSFORM_RANDOM: {} players from [{}]", parts.size(), speciesList);
    }

    private void executeTransformByTeam(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        if (!CobbleShiftHook.isAvailable()) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] TRANSFORM_BY_TEAM: CobbleShift not available");
            return;
        }
        String teamMapJson = block.getProp("transformTeamMap", "{}");
        // Assign teams based on participant order and teamSize config
        int teamSize = Math.max(1, event.getTeamSize());
        java.util.List<ServerPlayerEntity> ordered = new java.util.ArrayList<>(parts);
        CobbleShiftHook.transformByTeam(ordered, teamMapJson, player -> {
            int idx = ordered.indexOf(player);
            if (idx < 0) return "0";
            return String.valueOf(idx / teamSize);
        });
        CheeseGuardMod.LOGGER.info("[CG-TL] TRANSFORM_BY_TEAM: {} players, map={}", parts.size(), teamMapJson);
    }

    // ── CobblemonDungeons effects ─────────────────────────────────────────────

    private void executeDungeonOpen(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        if (!CobblemonDungeonsHook.isAvailable()) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] DUNGEON_OPEN: CobblemonDungeons not available");
            return;
        }
        String dungeonId = block.getProp("dungeonId", "");
        int tier = block.getPropInt("dungeonTier", 1);
        if (dungeonId.isBlank()) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] DUNGEON_OPEN: no dungeonId specified");
            return;
        }
        if (!CobblemonDungeonsHook.dungeonExists(dungeonId)) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] DUNGEON_OPEN: dungeon '{}' not found", dungeonId);
            return;
        }
        // Start the dungeon for the first participant as leader
        for (ServerPlayerEntity leader : parts) {
            boolean ok = CobblemonDungeonsHook.startDungeon(leader, dungeonId, tier);
            if (ok) {
                CheeseGuardMod.LOGGER.info("[CG-TL] DUNGEON_OPEN: started {} T{} for {}", dungeonId, tier, leader.getName().getString());
            }
            break; // only need one leader to start
        }
    }

    private void executeDungeonTicketGive(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        if (!CobblemonDungeonsHook.isAvailable()) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] DUNGEON_TICKET_GIVE: CobblemonDungeons not available");
            return;
        }
        String dungeonId = block.getProp("dungeonId", "");
        int tier = block.getPropInt("dungeonTier", 1);
        int count = block.getPropInt("ticketCount", 1);
        if (dungeonId.isBlank()) {
            CheeseGuardMod.LOGGER.warn("[CG-TL] DUNGEON_TICKET_GIVE: no dungeonId specified");
            return;
        }
        int given = 0;
        for (ServerPlayerEntity player : parts) {
            if (CobblemonDungeonsHook.giveDungeonTicket(player, dungeonId, tier, count)) {
                given++;
            }
        }
        CheeseGuardMod.LOGGER.info("[CG-TL] DUNGEON_TICKET_GIVE: {} T{} x{} given to {} players", dungeonId, tier, count, given);
    }

    private void executeDungeonModifier(ServerEvent event, TimelineBlock block) {
        // DUNGEON_MODIFIER is a flag-type effect — it stores metadata that can be read by
        // other systems. The modifier is applied as a broadcast notification for now.
        // Future: integrate with DungeonManager to apply runtime modifiers.
        String dungeonId = block.getProp("dungeonId", "");
        String modType = block.getProp("modifierType", "DOUBLE_LOOT");
        double modValue = block.getPropDouble("modifierValue", 2.0);
        CheeseGuardMod.LOGGER.info("[CG-TL] DUNGEON_MODIFIER: {} {} = {} (broadcast only)", dungeonId, modType, modValue);

        // Broadcast to all online players
        if (server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                p.sendMessage(Text.translatable("cheeseguard.timeline.dungeon_modifier", dungeonId, modType, String.format("%.1f", modValue))
                    .formatted(Formatting.GOLD), false);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Collection<ServerPlayerEntity> getParticipants(ServerEvent event) {
        List<ServerPlayerEntity> result = new ArrayList<>();
        for (var ep : event.getParticipants().values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(ep.getPlayerUuid());
            if (p != null) result.add(p);
        }
        return result;
    }

    private Collection<ServerPlayerEntity> filterByTarget(ServerEvent event,
            Collection<ServerPlayerEntity> all, String target, TimelineBlock block) {
        if ("all".equals(target) || target == null || target.isBlank()) return all;
        String role = block.getProp("targetRole", "");
        List<ServerPlayerEntity> filtered = new ArrayList<>();
        for (ServerPlayerEntity p : all) {
            boolean include = switch (target) {
                case "role" -> flags.is(event.getId(), p.getUuid(), "role", role);
                case "random" -> Math.random() < 0.5;
                default -> true;
            };
            if (include) filtered.add(p);
        }
        return filtered;
    }

    private ServerWorld getEventWorld(ServerEvent event) {
        String tpWorld = event.getTpParticipantWorld();
        if (tpWorld.isBlank()) return server.getOverworld();
        return getWorldByKey(tpWorld, event);
    }

    private ServerWorld getWorldByKey(String key, ServerEvent event) {
        if (key == null || key.isBlank()) return getEventWorld(event);
        Identifier id = Identifier.tryParse(key);
        if (id == null) return server.getOverworld();
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().equals(id)) return w;
        }
        return server.getOverworld();
    }

    private static String colorize(String colorName, String text) {
        Formatting fmt = Formatting.byName(colorName);
        return (fmt != null ? fmt.toString() : "") + text;
    }

    private static void sendTitle(ServerPlayerEntity p, String title, String sub, int fi, int stay, int fo) {
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(fi, stay, fo));
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal(sub)));
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal(title)));
    }

    // ── Public queries ────────────────────────────────────────────────────────

    public int getCurrentActIndex(String eventId) {
        EventRuntime rt = runtimes.get(eventId);
        return rt != null ? rt.currentActIndex : 0;
    }

    public int getActElapsedSeconds(String eventId) {
        EventRuntime rt = runtimes.get(eventId);
        return rt != null ? rt.actElapsedSeconds : 0;
    }

    /** Data holder for an active ZONE_SHRINK border that needs particle rendering. */
    public record ActiveZoneBorder(double cx, double cz, double radius, boolean showBorder) {}

    /**
     * Returns all active ZONE_SHRINK blocks for a given event that have showBorder=true.
     * Used by EventManager.tickZoneBorderParticles() for particle rendering every 3 ticks.
     */
    public List<ActiveZoneBorder> getActiveZoneBorders(ServerEvent event) {
        EventRuntime rt = runtimes.get(event.getId());
        if (rt == null || event.getActs().isEmpty()) return List.of();
        int actIdx = rt.currentActIndex;
        if (actIdx < 0 || actIdx >= event.getActs().size()) return List.of();
        EventAct act = event.getActs().get(actIdx);
        int elapsed = rt.actElapsedSeconds;

        List<ActiveZoneBorder> result = new ArrayList<>();
        for (TimelineBlock block : act.getBlocks()) {
            if (block.getEffectType() != EventEffect.Type.ZONE_SHRINK) continue;
            if (!block.getPropBool("showBorder", false)) continue;

            int start = block.getStartSeconds();
            int end = start + block.getDurationSeconds();
            if (elapsed < start || elapsed >= end) continue;

            int secondsActive = elapsed - start;
            double cx = block.getPropDouble("zoneCenterX", block.getPropDouble("cx", 0));
            double cz = block.getPropDouble("zoneCenterZ", block.getPropDouble("cz", 0));
            double ri = block.getPropDouble("radiusInitial", 200);
            double rf = block.getPropDouble("radiusFinal", 50);
            int total = block.getDurationSeconds();
            if (total <= 0) continue;

            double progress = Math.min(1.0, (double) secondsActive / total);
            String curve = block.getProp("curve", "linear");
            double t = switch (curve) {
                case "ease_in"  -> progress * progress;
                case "ease_out" -> 1 - (1 - progress) * (1 - progress);
                case "ease_io"  -> progress < 0.5 ? 2*progress*progress : 1-Math.pow(-2*progress+2,2)/2;
                default -> progress;
            };
            double currentRadius = ri + (rf - ri) * t;

            // Orbit support
            if (block.getPropBool("orbitEnabled", false)) {
                double orbitR = block.getPropDouble("orbitRadius", 0);
                double orbitSpd = block.getPropDouble("orbitSpeed", 30);
                if (orbitR > 0) {
                    double angleRad = Math.toRadians(secondsActive * orbitSpd);
                    cx = cx + orbitR * Math.cos(angleRad);
                    cz = cz + orbitR * Math.sin(angleRad);
                }
            }

            result.add(new ActiveZoneBorder(cx, cz, currentRadius, true));
        }
        return result;
    }

    public boolean isPlayerFrozen(String eventId, UUID uuid) {
        Set<UUID> set = frozenPlayers.get(eventId);
        return set != null && set.contains(uuid);
    }

    /** Manually advance to next act (admin command) */
    public void advanceAct(ServerEvent event) {
        EventRuntime rt = runtimes.get(event.getId());
        if (rt == null) return;
        int next = rt.currentActIndex + 1;
        if (next < event.getActs().size()) startAct(event, rt, next);
    }

    // ── CheeseKart effects ──────────────────────────────────────────────────

    private void executeKartStartAll(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        String presetId = block.getProp("kartPresetId", "rally");
        String speciesOverride = block.getProp("kartSpeciesOverride", "");
        CheeseKartHook.startAll(parts, presetId, speciesOverride);
        CheeseGuardMod.LOGGER.info("[CG-TL] KART_START_ALL: {} players, preset={}, species={}", parts.size(), presetId, speciesOverride.isEmpty() ? "preset-default" : speciesOverride);
    }

    private void executeKartStopAll(ServerEvent event, TimelineBlock block, Collection<ServerPlayerEntity> parts) {
        CheeseKartHook.stopAll(parts);
        CheeseGuardMod.LOGGER.info("[CG-TL] KART_STOP_ALL: {} players", parts.size());
    }
}

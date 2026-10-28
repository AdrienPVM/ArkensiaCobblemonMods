package com.cheeseraids.logic;

import com.cheeseraids.CheeseRaidsMod;
import com.cheeseraids.data.*;
import com.cheeseraids.util.Mc;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Orchestrator: loads raid definitions, drives the per-instance state machine, runs the
 * fixed-time scheduler (anti-replay on reboot), and persists instances crash-safely.
 */
public class RaidManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MinecraftServer server;
    private Path baseDir, defsDir, instancesDir, stateFile;

    private final Map<String, RaidDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, RaidInstance> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> lastRunDates = new HashMap<>(); // raidId → ISO date

    private final StarArenaGenerator generator = new StarArenaGenerator();
    private final WaveManager waves = new WaveManager();
    private final CheckpointTracker tracker = new CheckpointTracker();
    private final RewardManager rewards = new RewardManager();
    // BossProvider is stateless (all per-raid state lives on RaidInstance) — resolve it LOCALLY per
    // advance()/cleanup() so two concurrent raids never share one provider instance.
    private final java.util.List<FxTask> fx = new ArrayList<>(); // ticked cinematic apparition sequences

    private int tickCounter = 0;

    /** A multi-tick cinematic FX sequence (apparitions). Holds its own age; tick() returns true when done. */
    private abstract static class FxTask {
        private int age = 0;
        final boolean tick() { return step(age++); } // age = 0 on first call
        abstract boolean step(int age);
    }
    private void addFx(FxTask t) { fx.add(t); }
    private void tickFx() {
        if (fx.isEmpty()) return;
        for (java.util.Iterator<FxTask> it = fx.iterator(); it.hasNext(); ) {
            boolean done;
            try { done = it.next().tick(); }
            catch (Throwable e) { CheeseRaidsMod.LOGGER.warn("[CheeseRaids] FX task error: {}", e.getMessage()); done = true; }
            if (done) it.remove(); // never let an FX bug stall the raid
        }
    }

    /** Cosmetic (no-damage) lightning bolt at a position — the dramatic apparition punctuation. */
    private void coBolt(ServerWorld w, double x, double y, double z) {
        try {
            net.minecraft.entity.LightningEntity b = net.minecraft.entity.EntityType.LIGHTNING_BOLT.create(w);
            if (b != null) { b.refreshPositionAfterTeleport(x, y, z); b.setCosmetic(true); w.spawnEntity(b); }
        } catch (Throwable ignored) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void onServerStart(MinecraftServer server) {
        this.server = server;
        this.baseDir = server.getRunDirectory().resolve("config").resolve("cheeseraids");
        this.defsDir = baseDir.resolve("raids");
        this.instancesDir = baseDir.resolve("instances");
        this.stateFile = baseDir.resolve("state.json");
        try {
            Files.createDirectories(defsDir);
            Files.createDirectories(instancesDir);
        } catch (IOException e) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] cannot create config dirs: {}", e.getMessage());
        }
        loadDefinitions();
        loadState();
        loadInstances();
        CheeseRaidsMod.LOGGER.info("[CheeseRaids] Loaded {} raid definition(s), {} active instance(s)",
            definitions.size(), active.size());
    }

    public void onServerStop() {
        for (RaidInstance inst : active.values()) saveInstance(inst);
        saveState();
    }

    // ── Definitions / state I/O ────────────────────────────────────────────────

    private void loadDefinitions() {
        definitions.clear();
        int fileCount = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(defsDir, "*.json")) {
            for (Path f : ds) {
                fileCount++;
                try {
                    RaidDefinition def = GSON.fromJson(Files.readString(f), RaidDefinition.class);
                    if (def != null && def.id != null) definitions.put(def.id, def);
                } catch (Exception e) {
                    // DO NOT overwrite a malformed file with defaults (that silently reverts edits).
                    CheeseRaidsMod.LOGGER.error("[CheeseRaids] FAILED to parse raid def {} — fix the JSON syntax and /cheeseraids reload. Left untouched. ({})", f, e.getMessage());
                }
            }
        } catch (IOException ignored) {}
        // Only seed the default when the directory is genuinely empty (first run).
        if (definitions.isEmpty() && fileCount == 0) {
            RaidDefinition hoopa = RaidDefinition.defaultHoopa();
            definitions.put(hoopa.id, hoopa);
            saveDefinition(hoopa);
        }
    }

    public void saveDefinition(RaidDefinition def) {
        try {
            Files.writeString(defsDir.resolve(def.id + ".json"), GSON.toJson(def));
        } catch (IOException e) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] save def {} failed: {}", def.id, e.getMessage());
        }
    }

    public void reload() {
        loadDefinitions();
    }

    private void loadInstances() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(instancesDir, "*.json")) {
            for (Path f : ds) {
                try {
                    RaidInstance inst = GSON.fromJson(Files.readString(f), RaidInstance.class);
                    if (inst != null && inst.raidId != null && inst.phase != RaidPhase.IDLE) {
                        active.put(inst.raidId, inst);
                    }
                } catch (Exception e) {
                    CheeseRaidsMod.LOGGER.warn("[CheeseRaids] bad instance {}: {}", f, e.getMessage());
                }
            }
        } catch (IOException ignored) {}
    }

    private void saveInstance(RaidInstance inst) {
        try {
            Files.writeString(instancesDir.resolve(inst.raidId + ".json"), GSON.toJson(inst));
        } catch (IOException e) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] save instance {} failed: {}", inst.raidId, e.getMessage());
        }
    }

    private void deleteInstance(String raidId) {
        try { Files.deleteIfExists(instancesDir.resolve(raidId + ".json")); } catch (IOException ignored) {}
    }

    private void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> m = GSON.fromJson(Files.readString(stateFile), t);
            if (m != null) lastRunDates.putAll(m);
        } catch (Exception e) {
            // Don't silently wipe the anti-replay record (a scheduled raid could re-fire on reboot).
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] anti-replay state.json unreadable: {}", e.getMessage());
        }
    }

    private void saveState() {
        try { Files.writeString(stateFile, GSON.toJson(lastRunDates)); } catch (IOException ignored) {}
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    // Return copies — these are read from the HTTP thread while tick() mutates on the server thread.
    public Collection<RaidDefinition> definitions() { return new ArrayList<>(definitions.values()); }
    public RaidDefinition definition(String id) { return definitions.get(id); }
    public RaidInstance activeInstance(String id) { return active.get(id); }
    public Collection<RaidInstance> activeInstances() { return new ArrayList<>(active.values()); }

    // ── Diagnostics / test helpers (commands) ───────────────────────────────────
    public String heraldDiagnose(String raidId) {
        RaidDefinition def = definitions.get(raidId);
        if (def == null) return "Raid inconnu: " + raidId;
        return com.cheeseraids.hooks.EasyNpcHook.diagnose(server, def);
    }
    public int heraldToggle(String raidId, boolean visible) {
        RaidDefinition def = definitions.get(raidId);
        if (def == null) return -1;
        return com.cheeseraids.hooks.EasyNpcHook.setVisible(server, def, visible);
    }
    public boolean testReward(ServerPlayerEntity player, String raidId) {
        RaidDefinition def = definitions.get(raidId);
        if (def == null) return false;
        rewards.testGrant(server, def, player);
        return true;
    }

    /** Start a raid manually or via scheduler. Returns false if unknown or already active. */
    public boolean start(String raidId) {
        RaidDefinition def = definitions.get(raidId);
        if (def == null || !def.enabled) return false;
        if (active.containsKey(raidId)) return false;
        RaidInstance inst = new RaidInstance(raidId);
        inst.startedAt = System.currentTimeMillis();
        active.put(raidId, inst);
        transition(def, inst, RaidPhase.ANNOUNCE);
        saveInstance(inst);
        return true;
    }

    public boolean stop(String raidId) {
        RaidInstance inst = active.get(raidId);
        if (inst == null) return false;
        RaidDefinition def = definitions.get(raidId);
        if (def != null) cleanup(def, inst);
        return true;
    }

    /** Player clicks "Enter" on the herald. Only valid during OPEN. */
    public boolean enter(ServerPlayerEntity player, String raidId) {
        RaidInstance inst = active.get(raidId);
        RaidDefinition def = definitions.get(raidId);
        if (inst == null || def == null) { msg(player, "Aucune invasion active."); return false; }
        if (inst.phase != RaidPhase.OPEN) { msg(player, "La faille n'est pas ouverte."); return false; }

        String uuid = player.getUuidAsString();
        PlayerRaidState ps = inst.players.get(uuid);
        ServerWorld rift = generator.ensureRift(server, def);
        if (rift == null) { msg(player, "La faille n'a pas pu s'ouvrir."); return false; }

        if (ps == null) {
            int slot = inst.nextFreeSlot(def.arena.maxSlots);
            if (slot < 0) { msg(player, "La faille est pleine."); return false; }
            ps = new PlayerRaidState(uuid, player.getName().getString());
            ps.slot = slot;
            ps.seed = player.getUuid().getMostSignificantBits()
                ^ ((long) raidId.hashCode() * 0x9E3779B1L) ^ (long) slot;
            inst.players.put(uuid, ps);
            inst.usedSlots.add(slot);
            Pos spawn = generator.generateBranch(rift, def, ps);
            ps.returnPos = def.exitPos;
            teleport(player, spawn);
        } else {
            // Re-entry (relog) — NEVER regenerate; restore to last checkpoint / spawn platform.
            BlockPos t = ps.lastCheckpoint != null
                ? new BlockPos(ps.lastCheckpoint[0], ps.lastCheckpoint[1], ps.lastCheckpoint[2])
                : generator.spawnPlatform(def, ps.slot, ps.seed).up();
            teleport(player, Pos.of(rift.getRegistryKey().getValue().toString(), t, player.getYaw(), player.getPitch()));
        }
        generator.applyNight(server, def); // re-pin midnight for the player who just entered
        ps.entered = true;
        saveInstance(inst);
        return true;
    }

    // ── Tick / state machine ─────────────────────────────────────────────────────

    public void tick() {
        if (server == null) return;
        tickCounter++;

        generator.tick();
        tickFx(); // drive cinematic apparition sequences

        // Scheduler (once per second)
        if (tickCounter % 20 == 0) checkScheduler();

        if (!active.isEmpty()) {
            for (RaidInstance inst : new ArrayList<>(active.values())) {
                RaidDefinition def = definitions.get(inst.raidId);
                if (def == null) continue;
                advance(def, inst);
            }
            // periodic persistence (checkpoints, etc.)
            if (tickCounter % 100 == 0) for (RaidInstance inst : active.values()) saveInstance(inst);
            // keep the thunderstorm pinned while a raid is running (weather command lasts ~60s)
            if (tickCounter % 600 == 0) {
                for (RaidInstance inst : active.values()) {
                    RaidDefinition def = definitions.get(inst.raidId);
                    if (def != null && inst.phase != RaidPhase.CLEANUP && inst.phase != RaidPhase.IDLE)
                        setStorm(def, true);
                }
            }
        }
    }

    private void checkScheduler() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        for (RaidDefinition def : definitions.values()) {
            if (!def.enabled || !def.schedule.autoTrigger) continue;
            if (active.containsKey(def.id)) continue;
            if (now.getHour() != def.schedule.hour || now.getMinute() != def.schedule.minute) continue;
            String last = lastRunDates.get(def.id);
            if (last != null) {
                try {
                    if (ChronoUnit.DAYS.between(LocalDate.parse(last), today) < Math.max(1, def.schedule.everyNDays)) continue;
                } catch (Exception ignored) {}
            }
            CheeseRaidsMod.LOGGER.info("[CheeseRaids] Scheduler triggers raid '{}'", def.id);
            lastRunDates.put(def.id, today.toString());
            saveState();
            start(def.id);
        }
    }

    private void advance(RaidDefinition def, RaidInstance inst) {
        long now = System.currentTimeMillis();
        long elapsed = now - inst.phaseStartedAt;
        switch (inst.phase) {
            case ANNOUNCE -> {
                if (elapsed >= Math.max(0, def.wave.graceSecondsBeforeWave) * 1000L) {
                    waves.fireWave(server, def, inst);
                    addFx(waveApparitionFx(def)); // a Hoopa portal tears open as the alpha + minions pour out
                    transition(def, inst, RaidPhase.OVERWORLD_WAVE);
                }
            }
            case OVERWORLD_WAVE -> {
                if (tickCounter % 5 == 0) waves.tickEffects(server, def, inst); // marker aura on mobs
                boolean alphaDown = waves.alphaJustDefeated(server, def, inst);
                if (alphaDown) {
                    titleAll("{\"text\":\"L'Alpha Hoopa s'enfuit !\",\"color\":\"dark_purple\",\"bold\":true}",
                             "{\"text\":\"Il s'enfouit dans sa dimension... la faille s'ouvre !\",\"color\":\"light_purple\"}");
                    riftOpeningFx(def);
                }
                if (waves.isCapturing(inst)) {
                    waves.captureTick(server, def, inst);
                } else if (inst.waveTotal == 0) {
                    CheeseRaidsMod.LOGGER.warn("[CheeseRaids] '{}' spawned 0 wave mob(s) — proceeding.", inst.raidId);
                    transition(def, inst, RaidPhase.NPC_REVEAL);
                } else if (alphaDown || (tickCounter % Math.max(1, def.wave.checkIntervalTicks) == 0
                        && waves.isCleared(server, def, inst))) {
                    // Felling the ALPHA leader (or clearing the whole wave) tears the rift open — this is
                    // what the "la faille s'ouvre !" message promises. Remaining mobs flee (despawned).
                    CheeseRaidsMod.LOGGER.info("[CheeseRaids] '{}' wave done ({}) — opening rift.",
                        inst.raidId, alphaDown ? "alpha defeated" : "all cleared");
                    waves.cleanup(server, def, inst);
                    transition(def, inst, RaidPhase.NPC_REVEAL);
                } else if (elapsed >= Math.max(5, def.wave.timeoutSeconds) * 1000L) {
                    CheeseRaidsMod.LOGGER.warn("[CheeseRaids] '{}' wave timed out — proceeding.", inst.raidId);
                    waves.cleanup(server, def, inst);
                    transition(def, inst, RaidPhase.NPC_REVEAL);
                }
            }
            case NPC_REVEAL -> {
                generator.ensureRift(server, def);
                // Count nearby heralds WITHOUT revealing (the crescendo flips them visible at its climax).
                int heralds = com.cheeseraids.hooks.EasyNpcHook.setVisible(server, def, false);
                if (heralds > 0) {
                    addFx(heraldRevealFx(def)); // ~52-tick crescendo → NPC appears at the climax flash
                } else {
                    // No EasyNPC herald found (misplaced / chunk unloaded / API change): don't dead-end the
                    // raid — open it and tell players the command entry path instead.
                    CheeseRaidsMod.LOGGER.warn("[CheeseRaids] '{}' NPC_REVEAL: aucun héraut EasyNPC trouvé — "
                        + "entrée par commande proposée à la place.", inst.raidId);
                    broadcast("§5§lLa faille de Hoopa s'ouvre ! §dTapez §f/cheeseraids enter " + inst.raidId
                        + "§d pour entrer.");
                    riftOpeningFx(def);
                }
                transition(def, inst, RaidPhase.OPEN);
            }
            case OPEN -> {
                tracker.tick(server, def, inst, generator);
                boolean anyArrived = inst.players.values().stream().anyMatch(p -> p.arrived);
                if (anyArrived && inst.bossGraceDeadline == 0)
                    inst.bossGraceDeadline = now + (long) (def.boss.graceSeconds * 1000);
                boolean graceOver = inst.bossGraceDeadline > 0 && now > inst.bossGraceDeadline;
                boolean openTimeoutOver = inst.openDeadline > 0 && now > inst.openDeadline;
                boolean entered = inst.players.values().stream().anyMatch(p -> p.entered);
                if (entered && (inst.allArrived() || graceOver || openTimeoutOver)) {
                    // Register the apparition crescendo; the boss actually SPAWNS at its climax (~36t).
                    // COMBAT runs during the build-up but bossSpawned stays false → no false victory.
                    addFx(bossApparitionFx(def, inst, inst.participantCount()));
                    broadcast("§5§lQuelque chose d'immense s'éveille au cœur de la faille...");
                    transition(def, inst, RaidPhase.COMBAT);
                } else if (openTimeoutOver && !entered) {
                    CheeseRaidsMod.LOGGER.info("[CheeseRaids] '{}' OPEN timed out with no participants — aborting.", inst.raidId);
                    transition(def, inst, RaidPhase.CLEANUP);
                }
            }
            case COMBAT -> {
                BossProvider boss = BossProvider.forDefinition(def);
                if (boss.isCapturing(inst)) boss.captureTick(server, def, inst);
                boss.combatTick(server, def, inst); // capture the boss's battle id (to wait for battle end)
                if (tickCounter % 3 == 0) boss.tickEffects(server, def, inst); // boss combat aura
                tracker.tick(server, def, inst, generator);
                if (boss.isDefeated(server, def, inst)) {
                    transition(def, inst, RaidPhase.REWARD);
                } else if (elapsed >= Math.max(10, def.boss.combatTimeoutSeconds) * 1000L) {
                    CheeseRaidsMod.LOGGER.warn("[CheeseRaids] '{}' COMBAT timed out — cleanup without reward.", inst.raidId);
                    ejectAll(def, inst);
                    transition(def, inst, RaidPhase.CLEANUP);
                }
            }
            case REWARD -> {
                if (inst.rewardStartedAt == 0) {
                    inst.rewardStartedAt = now;
                    applyVictorySequence(def, inst);          // effect on players still in the rift
                } else if (now - inst.rewardStartedAt >= 4500) {
                    ejectAll(def, inst);                      // tp out to the overworld anchor
                    rewards.grantRewards(server, def, inst);   // fireworks + Hoopa portals + fromage
                    transition(def, inst, RaidPhase.CLEANUP);
                } else if (tickCounter % 4 == 0) {
                    victorySparkle(def, inst);                 // ongoing celebration before the tp
                }
            }
            case CLEANUP -> cleanup(def, inst);
            default -> {}
        }
    }

    private void transition(RaidDefinition def, RaidInstance inst, RaidPhase phase) {
        inst.phase = phase;
        inst.phaseStartedAt = System.currentTimeMillis();
        if (phase == RaidPhase.ANNOUNCE) doAnnounce(def);
        if (phase == RaidPhase.OPEN)
            inst.openDeadline = System.currentTimeMillis() + Math.max(5, def.arena.openTimeoutSeconds) * 1000L;
        broadcastPhase(def, inst);
        saveInstance(inst);
    }

    private void cleanup(RaidDefinition def, RaidInstance inst) {
        setStorm(def, false); // restore clear weather over the wave location
        try {
            ServerWorld rift = Mc.world(server, "cheeseguard:" + def.arena.riftDimId);
            if (rift != null) {
                for (PlayerRaidState ps : inst.players.values()) generator.clearBranch(rift, def, ps);
                generator.clearCenter(rift, def);
            }
            waves.cleanup(server, def, inst);
            BossProvider.forDefinition(def).cleanup(server, def, inst);
            hideHerald(def);
            generator.clearReveals();
            fx.clear();
        } catch (Exception e) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] cleanup error: {}", e.getMessage());
        }
        active.remove(inst.raidId);
        deleteInstance(inst.raidId);
        CheeseRaidsMod.LOGGER.info("[CheeseRaids] Raid '{}' ended and cleaned up.", inst.raidId);
    }

    private void ejectAll(RaidDefinition def, RaidInstance inst) {
        for (PlayerRaidState ps : inst.players.values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(ps.uuid));
            if (p != null) {
                Pos exit = ps.returnPos != null ? ps.returnPos : def.exitPos;
                teleport(p, exit);
                ps.finished = true;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private void doAnnounce(RaidDefinition def) {
        if (def.announce.chat != null && !def.announce.chat.isBlank())
            broadcast("§d" + def.announce.chat);
        Mc.run(server, "title @a title {\"text\":\"" + esc(def.announce.title) + "\",\"color\":\"light_purple\"}");
        if (def.announce.subtitle != null && !def.announce.subtitle.isBlank())
            Mc.run(server, "title @a subtitle {\"text\":\"" + esc(def.announce.subtitle) + "\",\"color\":\"gray\"}");
        if (def.announce.discord)
            CheeseRaidsMod.LOGGER.info("[CheeseRaids][Discord] {}", def.announce.discordMessage);
        hideHerald(def); // herald stays hidden through the wave, revealed at NPC_REVEAL
        setStorm(def, true); // night + thunderstorm over the wave location for the event
    }

    /** Reveal the herald(s); returns how many EasyNPC were found near the configured position (0 = none). */
    private int showHerald(RaidDefinition def) {
        int count = com.cheeseraids.hooks.EasyNpcHook.setVisible(server, def, true); // reliable Java reveal
        if (def.npc.showCommand != null && !def.npc.showCommand.isBlank()) runNpcCommand(def, def.npc.showCommand);
        riftOpeningFx(def); // a visible Hoopa-rift portal tears open where the event is
        // dramatic reveal fx at the herald position
        ServerWorld w = Mc.world(server, def.npc.pos.world);
        if (w != null) {
            BlockPos p = def.npc.pos.blockPos();
            Mc.particles(w, "TOTEM", p.up(), 80);
            Mc.particles(w, "END_ROD", p.up(2), 40);
            Mc.sound(w, "minecraft:entity.illusioner.cast_spell", p, 1.4f, 0.7f);
            Mc.sound(w, "minecraft:block.beacon.activate", p, 1.0f, 1.2f);
        }
        return count;
    }

    /** Dramatic "the Hoopa rift tears open" portal effect at the overworld wave/herald location. */
    private void riftOpeningFx(RaidDefinition def) {
        ServerWorld w = Mc.world(server, def.wave.arena.world);
        if (w == null) return;
        BlockPos ap = def.wave.arena.blockPos();
        double cx = ap.getX() + 0.5, cy = ap.getY() + 1.0, cz = ap.getZ() + 0.5;
        // a swirling vertical portal column (a Hoopa ring tearing the sky)
        for (int i = 0; i < 64; i++) {
            double a = (Math.PI * 2 / 16) * i, rr = 2.2;
            w.spawnParticles(net.minecraft.particle.ParticleTypes.REVERSE_PORTAL,
                cx + Math.cos(a) * rr, cy + (i % 8) * 0.6, cz + Math.sin(a) * rr, 1, 0, 0, 0, 0);
        }
        w.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL, cx, cy + 1.6, cz, 140, 1.3, 2.2, 1.3, 0.5);
        w.spawnParticles(net.minecraft.particle.ParticleTypes.FLASH, cx, cy + 1.6, cz, 2, 0, 0, 0, 0);
        w.spawnParticles(net.minecraft.particle.ParticleTypes.DRAGON_BREATH, cx, cy + 0.5, cz, 40, 1.0, 0.3, 1.0, 0.0);
        Mc.sound(w, "minecraft:entity.enderman.teleport", ap, 1.6f, 0.6f);
        Mc.sound(w, "minecraft:block.end_portal.spawn", ap, 1.2f, 1.0f);
        Mc.sound(w, "minecraft:entity.illusioner.cast_spell", ap, 1.4f, 0.7f);
    }

    /** A Hoopa portal tears open at the wave arena as the alpha + minions pour out (~36 ticks). */
    private FxTask waveApparitionFx(RaidDefinition def) {
        final ServerWorld w = Mc.world(server, def.wave.arena.world);
        final BlockPos ap = def.wave.arena.blockPos();
        final double cx = ap.getX() + 0.5, cy = ap.getY() + 1.0, cz = ap.getZ() + 0.5;
        return new FxTask() {
            @Override boolean step(int age) {
                if (w == null) return true;
                if (age == 0) { // the rift flashes open (the mobs were spawned this tick)
                    w.spawnParticles(ParticleTypes.FLASH, cx, cy + 1, cz, 2, 0, 0, 0, 0);
                    w.spawnParticles(ParticleTypes.PORTAL, cx, cy + 1, cz, 140, 2.5, 1.5, 2.5, 0.6);
                    w.spawnParticles(ParticleTypes.REVERSE_PORTAL, cx, cy + 0.5, cz, 60, 2.0, 1.0, 2.0, 0.3);
                    w.spawnParticles(ParticleTypes.DRAGON_BREATH, cx, cy + 0.5, cz, 40, 2.0, 0.3, 2.0, 0.0);
                    Mc.sound(w, "minecraft:block.portal.trigger", ap, 1.4f, 0.7f);
                    Mc.sound(w, "minecraft:block.end_portal.spawn", ap, 1.4f, 1.0f);
                    Mc.sound(w, "minecraft:entity.enderman.teleport", ap, 1.6f, 0.6f);
                    Mc.sound(w, "minecraft:entity.wither.spawn", ap, 1.0f, 1.5f);
                }
                if (age > 0 && age < 36 && age % 2 == 0) { // swirling portal lingering as the wave settles
                    for (int i = 0; i < 10; i++) {
                        double a = (Math.PI * 2 / 10) * i + age * 0.2, r = 3.0 - age * 0.07;
                        if (r > 0.3) w.spawnParticles(ParticleTypes.PORTAL, cx + Math.cos(a) * r, cy + (i % 4) * 0.5, cz + Math.sin(a) * r, 1, 0, 0, 0, 0.3);
                    }
                    if (age % 6 == 0) Mc.sound(w, "minecraft:block.beacon.ambient", ap, 0.4f, Math.min(1.6f, 0.8f + age * 0.02f));
                }
                return age >= 36;
            }
        };
    }

    /** ~5-second herald crescendo: a 3s ominous gather, THEN gathering golden/portal energy + lightning
     *  around the (still hidden) herald; the EasyNPC flips visible only at the climax flash. The whole
     *  thing is delayed ~3s after the rift opens (per request) so it doesn't arrive too early. */
    private FxTask heraldRevealFx(RaidDefinition def) {
        final ServerWorld w = Mc.world(server, def.npc.pos.world);
        final BlockPos p = def.npc.pos.blockPos();
        final double hx = p.getX() + 0.5, hy = p.getY() + 0.5, hz = p.getZ() + 0.5;
        return new FxTask() {
            @Override boolean step(int age) {
                if (w == null) return true;
                if (age == 0) { riftOpeningFx(def); Mc.sound(w, "minecraft:item.goat_horn.sound.0", p, 1.2f, 0.5f); }
                // 0–59: a 3-second ominous build (sky darkens, low rumble) before the reveal sequence.
                if (age < 60) {
                    if (age % 12 == 0) { Mc.sound(w, "minecraft:ambient.cave", p, 1.2f, 0.5f); w.spawnParticles(ParticleTypes.PORTAL, hx, hy + 0.5, hz, 12, 1.2, 0.6, 1.2, 0.2); }
                    return false;
                }
                int t = age - 60; // reveal sequence runs on t (climax at t==40 → age 100)
                if (t < 38 && t % 2 == 0) {
                    double r = 3.2 * (1 - t / 38.0) + 0.2, a = t * 0.6;
                    w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, hx + Math.cos(a) * r, hy + 1.2, hz + Math.sin(a) * r, 6, 0.15, 0.4, 0.15, 0.05);
                    w.spawnParticles(ParticleTypes.END_ROD, hx, hy + 0.7, hz, 3, 0.2, 0.7, 0.2, 0.02);
                    Mc.sound(w, "minecraft:block.beacon.ambient", p, 0.6f, Math.min(1.8f, 0.8f + t * 0.03f));
                }
                if (t == 20 || t == 30) { coBolt(w, hx, p.getY(), hz); Mc.sound(w, "minecraft:entity.lightning_bolt.thunder", p, 1.4f, 1.1f); }
                if (t == 40) { // CLIMAX — the herald is revealed (≈5s after the rift opened)
                    com.cheeseraids.hooks.EasyNpcHook.setVisible(server, def, true);
                    if (def.npc.showCommand != null && !def.npc.showCommand.isBlank()) runNpcCommand(def, def.npc.showCommand);
                    coBolt(w, hx, p.getY(), hz);
                    w.spawnParticles(ParticleTypes.FLASH, hx, hy + 1, hz, 3, 0, 0, 0, 0);
                    w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, hx, hy + 1, hz, 120, 1.2, 1.6, 1.2, 0.6);
                    w.spawnParticles(ParticleTypes.END_ROD, hx, hy + 1, hz, 40, 0.6, 0.8, 0.6, 0.2);
                    Mc.sound(w, "minecraft:block.beacon.activate", p, 1.6f, 1.0f);
                    Mc.sound(w, "minecraft:ui.toast.challenge_complete", p, 0.9f, 1.4f);
                    Mc.sound(w, "minecraft:entity.ender_dragon.flap", p, 1.4f, 0.8f);
                    broadcast("§6§lLe héraut Ho-Oh surgit dans un éclair ! §r§dParlez-lui pour entrer dans la faille.");
                }
                if (t > 40 && t % 2 == 0) w.spawnParticles(ParticleTypes.FLAME, hx, hy + 1.5, hz, 6, 0.5, 0.3, 0.5, -0.02);
                return t >= 52;
            }
        };
    }

    /** ~44-tick crescendo at the rift centre: a Hoopa-portal tear + rising roar + lightning; the boss
     *  actually SPAWNS at the climax (tick 36) so it appears at the dramatic peak, not abruptly. */
    private FxTask bossApparitionFx(RaidDefinition def, RaidInstance inst, int participants) {
        final ServerWorld w = Mc.world(server, "cheeseguard:" + def.arena.riftDimId);
        final BlockPos c = def.arena.center.blockPos();
        final double cx = c.getX() + 0.5, cy = c.getY() + 1.5, cz = c.getZ() + 0.5;
        return new FxTask() {
            @Override boolean step(int age) {
                if (w == null) { if (age == 0) BossProvider.forDefinition(def).spawnBoss(server, def, inst, participants); return true; }
                if (age == 0) { Mc.sound(w, "minecraft:block.end_portal.spawn", c, 1.4f, 0.7f); Mc.sound(w, "minecraft:entity.wither.ambient", c, 1.4f, 0.6f); }
                if (age < 34 && age % 2 == 0) {
                    double rr = 3.0 * (age / 34.0);
                    for (int i = 0; i < 24; i++) {
                        double a = (Math.PI * 2 / 24) * i;
                        w.spawnParticles(ParticleTypes.REVERSE_PORTAL, cx + Math.cos(a) * rr, cy + Math.sin(a) * 3.0 + 3.0, cz, 1, 0, 0, 0, 0);
                    }
                    w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, cx, cy, cz, 12, 1.0, 1.5, 1.0, 0.02);
                    Mc.sound(w, "minecraft:entity.ender_dragon.growl", c, 1.4f, Math.min(1.2f, 0.7f + age * 0.012f));
                }
                if (age == 18 || age == 28) { coBolt(w, cx, c.getY(), cz); Mc.sound(w, "minecraft:entity.lightning_bolt.thunder", c, 1.6f, 0.9f); }
                if (age == 36) { // CLIMAX — the boss is pulled through
                    BossProvider.forDefinition(def).spawnBoss(server, def, inst, participants);
                    bossRevealFx(def);
                    broadcast("§5§l⚡ Le gardien de la faille SURGIT ! ⚡");
                }
                return age >= 44;
            }
        };
    }

    private void hideHerald(RaidDefinition def) {
        com.cheeseraids.hooks.EasyNpcHook.setVisible(server, def, false);
        if (def.npc.hideCommand != null && !def.npc.hideCommand.isBlank()) runNpcCommand(def, def.npc.hideCommand);
    }

    /** Epic boss-spawn sequence: a cosmetic lightning strike, particle burst, roars and a title. */
    private void bossRevealFx(RaidDefinition def) {
        String rift = "cheeseguard:" + def.arena.riftDimId;
        ServerWorld w = Mc.world(server, rift);
        if (w == null) return;
        BlockPos c = def.arena.center.blockPos();
        try {
            net.minecraft.entity.LightningEntity bolt = net.minecraft.entity.EntityType.LIGHTNING_BOLT.create(w);
            if (bolt != null) {
                bolt.refreshPositionAfterTeleport(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5);
                bolt.setCosmetic(true); // visual only — no fire, no damage
                w.spawnEntity(bolt);
            }
        } catch (Throwable ignored) {}
        // a Hoopa portal ring bursts open as the boss is pulled through
        double cx = c.getX() + 0.5, cy = c.getY() + 1.5, cz = c.getZ() + 0.5;
        for (int i = 0; i < 48; i++) {
            double a = (Math.PI * 2 / 48) * i, rr = 3.0;
            w.spawnParticles(net.minecraft.particle.ParticleTypes.REVERSE_PORTAL,
                cx + Math.cos(a) * rr, cy + Math.sin(a) * 3.0 + 3.0, cz, 1, 0, 0, 0, 0);
        }
        w.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL, cx, cy + 1.0, cz, 160, 2.0, 1.5, 2.0, 0.6);
        Mc.particles(w, "SOUL_FIRE_FLAME", c.up(), 120);
        Mc.particles(w, "DRAGON_BREATH", c.up(2), 80);
        Mc.sound(w, "minecraft:entity.wither.spawn", c, 2.0f, 0.8f);
        Mc.sound(w, "minecraft:entity.ender_dragon.growl", c, 1.6f, 0.9f);
        // Title only to players actually in the rift (dimension-scoped, no overworld leak).
        Text title = Text.literal("⚡ LE BOSS SURGIT ⚡").formatted(Formatting.DARK_PURPLE, Formatting.BOLD);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.getServerWorld() == w) {
                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(title));
            }
        }
    }

    /** Victory effect on players still inside the rift, before they are teleported out. */
    private void applyVictorySequence(RaidDefinition def, RaidInstance inst) {
        ServerWorld rift = Mc.world(server, "cheeseguard:" + def.arena.riftDimId);
        Text main = Text.literal("🏆 VICTOIRE !").formatted(Formatting.GOLD, Formatting.BOLD);
        Text sub = Text.literal("La faille s'effondre — l'invasion est repoussée !").formatted(Formatting.LIGHT_PURPLE);
        for (PlayerRaidState ps : inst.players.values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(ps.uuid));
            if (p == null) continue;
            p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.GLOWING, 200, 0, false, false));
            p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.REGENERATION, 100, 1, false, false));
            p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOW_FALLING, 200, 0, false, false));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(main));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(sub));
            if (rift != null) {
                Mc.particles(rift, "TOTEM", p.getBlockPos().up(), 80);
                Mc.sound(rift, "minecraft:ui.toast.challenge_complete", p.getBlockPos(), 1.0f, 1.0f);
                Mc.sound(rift, "minecraft:entity.player.levelup", p.getBlockPos(), 1.0f, 1.0f);
            }
        }
    }

    private void victorySparkle(RaidDefinition def, RaidInstance inst) {
        ServerWorld rift = Mc.world(server, "cheeseguard:" + def.arena.riftDimId);
        if (rift == null) return;
        for (PlayerRaidState ps : inst.players.values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(ps.uuid));
            if (p != null) Mc.particles(rift, "TOTEM", p.getBlockPos().up(), 20);
        }
    }

    /** Big title + subtitle to all players (server-wide announcement). main/sub are JSON text. */
    private void titleAll(String mainJson, String subJson) {
        Mc.run(server, "title @a times 10 70 20");
        Mc.run(server, "title @a title " + mainJson);
        if (subJson != null) Mc.run(server, "title @a subtitle " + subJson);
    }

    /** Force night + thunderstorm at the overworld wave location for atmosphere (restored at cleanup). */
    private void setStorm(RaidDefinition def, boolean on) {
        if (!def.wave.overworldStorm) return;
        String world = def.wave.arena.world;
        if (on) {
            Mc.run(server, "execute in " + world + " run weather thunder 1200");
            Mc.run(server, "execute in " + world + " run time set midnight");
        } else {
            Mc.run(server, "execute in " + world + " run weather clear");
        }
    }

    /** Substitute {npc} {type} {x} {y} {z} from the NPC config and run the command. */
    private void runNpcCommand(RaidDefinition def, String tpl) {
        if (tpl == null || tpl.isBlank()) return;
        BlockPos p = def.npc.pos.blockPos();
        Map<String, String> v = new HashMap<>();
        v.put("npc", def.npc.easyNpcName);
        v.put("type", def.npc.entityType);
        v.put("world", def.npc.pos.world);
        v.put("x", String.valueOf(p.getX()));
        v.put("y", String.valueOf(p.getY()));
        v.put("z", String.valueOf(p.getZ()));
        Mc.run(server, Mc.subst(tpl, v));
    }

    private void teleport(ServerPlayerEntity player, Pos pos) {
        ServerWorld w = Mc.world(server, pos.world);
        if (w == null) return;
        player.teleport(w, pos.x, pos.y, pos.z, Set.of(), pos.yaw, pos.pitch);
    }

    private void broadcast(String msg) {
        server.getPlayerManager().broadcast(Text.literal(msg), false);
    }

    private void broadcastPhase(RaidDefinition def, RaidInstance inst) {
        if (CheeseRaidsMod.DASHBOARD_BROADCAST != null) {
            CheeseRaidsMod.DASHBOARD_BROADCAST.accept(inst);
        }
    }

    private void msg(ServerPlayerEntity p, String text) {
        p.sendMessage(Text.literal(text).formatted(Formatting.LIGHT_PURPLE), false);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

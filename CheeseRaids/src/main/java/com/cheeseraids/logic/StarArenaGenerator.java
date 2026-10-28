package com.cheeseraids.logic;

import com.cheeseraids.CheeseRaidsMod;
import com.cheeseraids.data.PlayerRaidState;
import com.cheeseraids.data.Pos;
import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.util.Mc;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Procedural "star" arena: a central boss colosseum with one radial parkour branch per player.
 * Geometry is a PURE function of (definition, slot, seed) so it can be recomputed for cleanup
 * and checkpoint tracking, and is identical across relogs (never regenerated — hard constraint).
 */
public class StarArenaGenerator {

    private final List<BranchRevealTask> reveals = new ArrayList<>();
    private final Set<String> nightLocked = new HashSet<>(); // rift ids whose midnight lock is already persisted this session
    private final Set<String> builtCenters = new HashSet<>(); // rifts whose arena temple is already built this raid

    // ── Rift dimension + center colosseum ────────────────────────────────────

    /** Ensure the rift dimension exists (create via CheeseGuard if absent), force night, build the centre. */
    public ServerWorld ensureRift(MinecraftServer server, RaidDefinition def) {
        String fullId = "cheeseguard:" + def.arena.riftDimId;
        ServerWorld world = Mc.world(server, fullId);
        if (world == null) {
            try {
                var dm = com.cheeseguard.CheeseGuardMod.INSTANCE.getDimensionManager();
                dm.createDimension(def.arena.riftDimId, def.displayName + " — Faille",
                    com.cheeseguard.data.DimensionConfig.GenType.VOID, cfg -> {
                        cfg.setSpawnX(def.arena.center.x);
                        cfg.setSpawnY(def.arena.center.y + 1);
                        cfg.setSpawnZ(def.arena.center.z);
                        cfg.setKeepInventory(def.arena.keepInventory);
                        cfg.setPvp(false);
                        cfg.setMobSpawning(false);
                        cfg.setTimeLocked(true);
                        cfg.setLockedTime(18000); // midnight — dramatic
                        cfg.setWeatherLocked(true);
                        cfg.setRaidDensEnabled(false);
                        cfg.setEnabled(false);    // closed: only entered via /cheeseraids enter
                    });
                world = Mc.world(server, fullId);
            } catch (Throwable t) {
                CheeseRaidsMod.LOGGER.error("[CheeseRaids] Failed to create rift dimension {}: {}", fullId, t.getMessage(), t);
                return null;
            }
        }
        // Idempotent night patch: a rift created by an earlier run keeps its OLD config (createDimension
        // early-returns when it already exists), so force the lock here — but persist to disk only ONCE
        // per rift per session (the blocking dm.save() must not run on every player entry).
        lockNight(server, def);
        if (world != null) {
            if (builtCenters.add(def.arena.riftDimId)) buildCenter(world, def); // build the arena once per raid
            forceCenterChunks(world, def, true); // keep the centre loaded so the boss can spawn
        }
        return world;
    }

    /**
     * Keep the arena centre chunk(s) loaded for the whole raid. The boss spawns at the centre while all
     * players are far out on the parkour, so without this the centre chunk unloads and
     * {@code world.spawnEntity} is refused ("chunk not loaded"). Unforced at cleanup ({@link #clearCenter}).
     */
    private void forceCenterChunks(ServerWorld world, RaidDefinition def, boolean forced) {
        try {
            BlockPos c = def.arena.center.blockPos();
            int ccx = c.getX() >> 4, ccz = c.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    world.setChunkForced(ccx + dx, ccz + dz, forced);
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] forceCenterChunks({}) failed: {}", forced, t.getMessage());
        }
    }

    /** Persist the midnight lock once per rift/session (dm.save = blocking I/O — avoid per-entry calls). */
    private void lockNight(MinecraftServer server, RaidDefinition def) {
        if (nightLocked.contains(def.arena.riftDimId)) return;
        try {
            var dm = com.cheeseguard.CheeseGuardMod.INSTANCE.getDimensionManager();
            com.cheeseguard.data.DimensionConfig cfg = dm.getConfig(def.arena.riftDimId);
            if (cfg != null) {
                cfg.setTimeLocked(true);
                cfg.setLockedTime(18000);
                dm.applyTimeLock(cfg);
                dm.save();
                nightLocked.add(def.arena.riftDimId);
            }
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] lockNight failed: {}", t.getMessage());
        }
    }

    /** Cheap re-pin of the client midnight display for a player who just entered — NO disk write. */
    public void applyNight(MinecraftServer server, RaidDefinition def) {
        try {
            var dm = com.cheeseguard.CheeseGuardMod.INSTANCE.getDimensionManager();
            com.cheeseguard.data.DimensionConfig cfg = dm.getConfig(def.arena.riftDimId);
            if (cfg != null) dm.applyTimeLock(cfg); // re-pin display only, no persist
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] applyNight failed: {}", t.getMessage());
        }
    }

    /**
     * Big boss colosseum with a COMPLETELY CLEAR walkable surface (so a branch landing from any
     * direction is never blocked): a large flat floor with flush concentric glow/gold rings, plus
     * decorations only FLUSH (floor) or HIGH ABOVE (floating Hoopa rings + light crown out of jump
     * range). Nothing is raised on the disk itself.
     */
    private void buildCenter(ServerWorld world, RaidDefinition def) {
        BlockPos c = def.arena.center.blockPos();
        int r = Math.max(6, def.arena.centerRadius);
        BlockState floor    = Mc.block(def.arena.centerBlock);     // crying_obsidian
        BlockState glow     = Mc.block("minecraft:sea_lantern");
        BlockState gold     = Mc.block("minecraft:gold_block");
        BlockState amethyst = Mc.block("minecraft:amethyst_block");
        BlockState purpur   = Mc.block("minecraft:purpur_block");
        BlockState rod      = Mc.block("minecraft:end_rod");
        BlockState black    = Mc.block("minecraft:blackstone");

        // ── Flat "dimensional seal" floor (banded mandala) — fully walkable, never blocks a landing.
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int d2 = dx * dx + dz * dz;
            if (d2 > r * r) continue;
            int ring = (int) Math.round(Math.sqrt(d2));
            BlockState b;
            if (ring == r)            b = floor;        // dark containment edge
            else if (ring == r - 1)   b = glow;         // bright rim
            else if (ring == r - 2)   b = gold;         // gold seal ring
            else if (ring <= 2)       b = amethyst;     // central summon glyph (boss stands here)
            else if (ring == 3)       b = purpur;       // magenta inlay around the glyph
            else if (ring % 5 == 0)   b = glow;         // glow accent ring
            else if (ring % 5 == 2)   b = gold;         // gold accent ring
            else                      b = floor;
            world.setBlockState(c.add(dx, 0, dz), b, Block.NOTIFY_LISTENERS);
        }
        // ── 12 radial spokes (gold/amethyst), each aligned with a branch landing lane. Flush in the floor.
        int slots = Math.max(1, def.arena.maxSlots);
        for (int k = 0; k < slots; k++) {
            double a = (2 * Math.PI / slots) * k;
            for (int t = 4; t <= r - 3; t++) {
                int px = (int) Math.round(t * Math.cos(a));
                int pz = (int) Math.round(t * Math.sin(a));
                world.setBlockState(c.add(px, 0, pz), (t % 2 == 0) ? gold : amethyst, Block.NOTIFY_LISTENERS);
            }
        }
        // ── Floating crown of end-rod lights high above the rim (y+6 — well clear of any jump),
        //    with a taller glow beacon every third point for rhythm.
        for (int k = 0; k < 12; k++) {
            double a = Math.toRadians(k * 30);
            int px = (int) Math.round((r - 1) * Math.cos(a));
            int pz = (int) Math.round((r - 1) * Math.sin(a));
            world.setBlockState(c.add(px, 6, pz), rod, Block.NOTIFY_LISTENERS);
            if (k % 3 == 0) {
                world.setBlockState(c.add(px, 7, pz), glow, Block.NOTIFY_LISTENERS);
                world.setBlockState(c.add(px, 8, pz), rod, Block.NOTIFY_LISTENERS);
            }
        }
        // ── Interlocking dual-tone "Hoopa rings" high above the arena (pure spectacle, out of reach).
        int hr = Math.max(5, r / 3);
        buildHoop(world, c.add(0, 9, 0), true,  hr,     gold);
        buildHoop(world, c.add(0, 9, 0), true,  hr - 1, purpur);
        buildHoop(world, c.add(0, 9, 0), false, hr,     gold);
        buildHoop(world, c.add(0, 9, 0), false, hr - 1, purpur);
        buildHoop(world, c.add(0, 9 + hr + 3, 0), true, Math.max(3, hr - 2), gold); // upper small ring
        // flat golden halo overhead at y+9
        for (int dx = -(hr + 3); dx <= hr + 3; dx++) for (int dz = -(hr + 3); dz <= hr + 3; dz++) {
            int ring = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            if (ring == hr + 3) world.setBlockState(c.add(dx, 9, dz), gold, Block.NOTIFY_LISTENERS);
        }
        // ── Ruined "Temple of the Rift" around the arena. EVERYTHING radial is half-sector / diagonal so it
        //    never shares a branch lane angle (final platforms sit at radius centerRadius+2 on the lanes).
        BlockState gilded   = Mc.block("minecraft:gilded_blackstone");
        BlockState ochre    = Mc.block("minecraft:ochre_froglight");
        BlockState shroom   = Mc.block("minecraft:shroomlight");
        // Colonnade — one pillar between each pair of lanes (radius 20), some ruined-short for variety.
        for (int k = 0; k < slots; k++) {
            double a = (2 * Math.PI / slots) * (k + 0.5);
            int px = (int) Math.round(20 * Math.cos(a)), pz = (int) Math.round(20 * Math.sin(a));
            int htop = (k % 3 == 2) ? 7 : 11;
            for (int dy = 1; dy <= htop; dy++) world.setBlockState(c.add(px, dy, pz), black, Block.NOTIFY_LISTENERS);
            world.setBlockState(c.add(px, 4, pz), gilded, Block.NOTIFY_LISTENERS);
            world.setBlockState(c.add(px, 6, pz), gilded, Block.NOTIFY_LISTENERS);
            if (htop >= 11) {
                world.setBlockState(c.add(px, 8, pz), gold, Block.NOTIFY_LISTENERS);
                world.setBlockState(c.add(px, 9, pz), amethyst, Block.NOTIFY_LISTENERS);
                world.setBlockState(c.add(px, 10, pz), ochre, Block.NOTIFY_LISTENERS);
                world.setBlockState(c.add(px, 11, pz), rod, Block.NOTIFY_LISTENERS);
            }
            int bx = (int) Math.round(18 * Math.cos(a)), bz = (int) Math.round(18 * Math.sin(a));
            world.setBlockState(c.add(bx, 1, bz), shroom, Block.NOTIFY_LISTENERS); // brazier
        }
        // Four guardian "Ho-Oh feather" spires at the diagonals (radius 26 — between lanes for 12 slots).
        for (int s = 0; s < 4; s++) {
            double a = Math.PI / 4 + s * (Math.PI / 2);
            int px = (int) Math.round(26 * Math.cos(a)), pz = (int) Math.round(26 * Math.sin(a));
            for (int dy = 1; dy <= 14; dy++)
                world.setBlockState(c.add(px, dy, pz), (dy % 2 == 0 ? gold : amethyst), Block.NOTIFY_LISTENERS);
            for (int[] o : new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                world.setBlockState(c.add(px + o[0], 10, pz + o[1]), gold, Block.NOTIFY_LISTENERS); // wings
            world.setBlockState(c.add(px, 15, pz), purpur, Block.NOTIFY_LISTENERS);
            world.setBlockState(c.add(px, 16, pz), shroom, Block.NOTIFY_LISTENERS);
            world.setBlockState(c.add(px, 17, pz), rod, Block.NOTIFY_LISTENERS);
        }
        // Suspended vertical Hoopa rings around the edge (radius 24, half-sector even k).
        for (int k = 0; k < slots; k += 2) {
            double a = (2 * Math.PI / slots) * (k + 0.5);
            int rx = (int) Math.round(24 * Math.cos(a)), rz = (int) Math.round(24 * Math.sin(a));
            boolean yz = Math.abs(Math.cos(a)) < Math.abs(Math.sin(a));
            buildHoop(world, c.add(rx, 8, rz), yz, 4, gold);
            buildHoop(world, c.add(rx, 8, rz), yz, 3, purpur);
        }
        // Beams of light rising into the void at the 4 diagonal gaps (radius 28).
        BlockState sea = Mc.block("minecraft:sea_lantern");
        for (int s = 0; s < 4; s++) {
            double a = Math.PI / 4 + s * (Math.PI / 2);
            int px = (int) Math.round(28 * Math.cos(a)), pz = (int) Math.round(28 * Math.sin(a));
            for (int dy = 1; dy <= 18; dy++)
                world.setBlockState(c.add(px, dy, pz), (dy % 3 == 0 ? glow : (dy % 3 == 1 ? ochre : sea)), Block.NOTIFY_LISTENERS);
        }
    }

    /** A vertical ring of {@code b} (radius {@code R}) in the YZ plane ({@code yzPlane}) or the XY plane. */
    private void buildHoop(ServerWorld w, BlockPos center, boolean yzPlane, int R, BlockState b) {
        int stepDeg = Math.max(4, 360 / (R * 8)); // denser ring for bigger radius (reads solid, not dotted)
        for (int deg = 0; deg < 360; deg += stepDeg) {
            double a = Math.toRadians(deg);
            int u = (int) Math.round(R * Math.cos(a)); // vertical
            int v = (int) Math.round(R * Math.sin(a)); // horizontal
            BlockPos p = yzPlane ? center.add(0, u, v) : center.add(v, u, 0);
            w.setBlockState(p, b, Block.NOTIFY_LISTENERS);
        }
    }

    // ── Branch generation ────────────────────────────────────────────────────

    /**
     * Generate (or, if blocks already exist from a prior session, re-reveal) the player's branch.
     * Places the spawn platform + its decor immediately, reveals the rest progressively.
     * @return standing position on the spawn platform (where to teleport the player).
     */
    public Pos generateBranch(ServerWorld world, RaidDefinition def, PlayerRaidState ps) {
        LinkedHashMap<BlockPos, BlockState> blocks = computeBranch(def, ps.slot, ps.seed);
        BlockPos spawnPlatform = spawnPlatform(def, ps.slot, ps.seed);

        // Place the spawn platform (3×3) + decorative gateway immediately so the player lands safely.
        BlockState plat = Mc.block(def.arena.platformBlock);
        Set<BlockPos> spawnBlocks = shape(spawnPlatform, 1);
        for (BlockPos b : spawnBlocks) world.setBlockState(b, plat, Block.NOTIFY_LISTENERS);
        spawnDecor(spawnPlatform).forEach((p, st) -> world.setBlockState(p, st, Block.NOTIFY_LISTENERS));

        // Reveal the rest progressively (outer → center).
        List<Map.Entry<BlockPos, BlockState>> ordered = new ArrayList<>(blocks.entrySet());
        ordered.removeIf(e -> spawnBlocks.contains(e.getKey()));
        // Clear the path to air FIRST — the rift persists between runs and the geometry is deterministic
        // per (slot, seed), so leftover blocks from a prior session would already be in place and the
        // reveal animation would be invisible. Emptying it now guarantees the animation always plays.
        for (Map.Entry<BlockPos, BlockState> e : ordered)
            world.setBlockState(e.getKey(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        reveals.add(new BranchRevealTask(world, ordered, def.arena.revealBlocksPerTick,
            def.arena.revealParticle, def.arena.revealSound, def.arena.revealDelayTicks, spawnPlatform));

        ps.lastCheckpoint = null; // start = spawn platform
        ps.returnPos = def.exitPos;
        return Pos.of(world.getRegistryKey().getValue().toString(), spawnPlatform.up(), branchYaw(def, ps.slot), 0f);
    }

    /** Recompute & air-fill a player's branch + decor (bounded cleanup, no giant region fill). */
    public void clearBranch(ServerWorld world, RaidDefinition def, PlayerRaidState ps) {
        LinkedHashMap<BlockPos, BlockState> blocks = computeBranch(def, ps.slot, ps.seed);
        for (BlockPos p : blocks.keySet())
            world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        for (BlockPos p : spawnDecor(spawnPlatform(def, ps.slot, ps.seed)).keySet())
            world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    public void clearCenter(ServerWorld world, RaidDefinition def) {
        BlockPos c = def.arena.center.blockPos();
        // Cover everything buildCenter places: disk + temple ring (colonnade 20, rings 24, spires 26,
        // light beams 28) and the top hoops/spires up high.
        int base = Math.max(6, def.arena.centerRadius);
        int r = base + 17;                          // ≈32 — beyond the radius-28 light beams
        int top = 9 + Math.max(5, base / 3) + 13;   // ≈27 — above the spires/beams
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -1; dy <= top; dy++)
                    world.setBlockState(c.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        forceCenterChunks(world, def, false); // release the forced centre chunks
        builtCenters.remove(def.arena.riftDimId); // next raid rebuilds the arena
    }

    /** A grand Hoopa "ring gateway" around a spawn platform. Posts stay at the ±2 diagonals (proven not to
     *  block the first jump); all the grandeur (dual-tone ring, crystals) is overhead (y≥6, out of reach). */
    private LinkedHashMap<BlockPos, BlockState> spawnDecor(BlockPos plat) {
        LinkedHashMap<BlockPos, BlockState> m = new LinkedHashMap<>();
        BlockState post = Mc.block("minecraft:blackstone");
        BlockState gilded = Mc.block("minecraft:gilded_blackstone");
        BlockState gold = Mc.block("minecraft:gold_block");
        BlockState magenta = Mc.block("minecraft:purpur_block");
        BlockState rod  = Mc.block("minecraft:end_rod");
        BlockState ochre = Mc.block("minecraft:ochre_froglight");
        BlockState amethyst = Mc.block("minecraft:amethyst_block");
        // Diagonal monument posts (±2 — unchanged height, proven clear of the first jump), gilded crown.
        int[][] corners = {{2, 2}, {2, -2}, {-2, 2}, {-2, -2}};
        for (int[] o : corners) {
            for (int dy = 1; dy <= 3; dy++) m.put(plat.add(o[0], dy, o[1]), dy == 2 ? gilded : post);
            m.put(plat.add(o[0], 4, o[1]), gold);
            m.put(plat.add(o[0], 5, o[1]), rod);
        }
        // Signature floating Hoopa ring overhead (y6–8): R3 gold + R2 magenta + glowing nodes + a top pip.
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            int ring = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            if (ring == 3) m.put(plat.add(dx, 6, dz), gold);
            else if (ring == 2) m.put(plat.add(dx, 7, dz), magenta);
        }
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) m.put(plat.add(o[0], 6, o[1]), ochre);
        m.put(plat.add(0, 8, 0), rod);
        // Floating amethyst crystals on the cardinals (y5–6, well above the pad — never in a jump arc).
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            m.put(plat.add(o[0], 5, o[1]), amethyst);
            m.put(plat.add(o[0], 6, o[1]), Mc.block("minecraft:amethyst_cluster"));
        }
        return m;
    }

    // ── Deterministic geometry ────────────────────────────────────────────────

    private Random rng(long seed, int slot) {
        return new Random(seed ^ (0x9E3779B97F4A7C15L * (slot + 1)));
    }

    private double sectorAngle(RaidDefinition def, int slot) {
        return (2 * Math.PI / Math.max(1, def.arena.maxSlots)) * slot;
    }

    private float branchYaw(RaidDefinition def, int slot) {
        // Face from the outer spawn platform TOWARD the centre — i.e. down the parkour path, where the
        // blocks reveal. Look direction = (centre - spawn) = (-cos a, -sin a) in (x,z); MC yaw = atan2(-dx, dz).
        double a = sectorAngle(def, slot);
        return (float) Math.toDegrees(Math.atan2(Math.cos(a), -Math.sin(a)));
    }

    /** A jump "style": name + MECHANIC + (dy, distance band, lateral amp) + base weight. */
    private record Jump(String name, String mech, int dy, double dLo, double dHi, double latAmp, int weight) {}
    /** A generated platform: its centre + the MECHANIC for the jump INTO it (drives blocks + reach). */
    private record Step(BlockPos pos, String mech) {}

    // 13 styles across 5 vanilla-FEASIBLE MECHANICS — each changes HOW you cross: plain jump/drop, ICE
    // (slippery precision + slight momentum), TIGHTROPE (1×1 precision), COBWEB (slow-fall descent), HONEY
    // (sticky short hop). LADDER (scaffolding) is reserved for the final disk-climb. Verticality up = +1
    // (plain) only — no slime-bounce/ladder-up (those aren't reliably doable from a same-level pad). Proven
    // feasible (temp/sim_parkour_v3.py: 0 impossible).
    private static final Jump[] STYLES = {
        new Jump("PRECISE",   "FLAT",      0, 2.6, 3.0, 0.25, 3),
        new Jump("FLAT",      "FLAT",      0, 3.8, 4.4, 2.2,  4),
        new Jump("LONG",      "FLAT",      0, 4.2, 4.5, 1.2,  3),
        new Jump("TURN",      "FLAT",      0, 3.4, 4.2, 3.4,  3),
        new Jump("DROP1",     "FLAT",     -1, 4.0, 4.5, 2.4,  3),
        new Jump("DROP2",     "FLAT",     -2, 4.0, 4.8, 2.4,  2),
        new Jump("DROP3",     "FLAT",     -3, 4.0, 4.8, 2.0,  1),
        new Jump("UP1",       "FLAT",      1, 3.0, 3.5, 0.5,  3),
        new Jump("UP1_PREC",  "FLAT",      1, 2.6, 3.0, 0.3,  2),
        new Jump("ICE_RUN",   "ICE",       0, 4.0, 4.6, 0.6,  3),
        new Jump("TIGHTROPE", "TIGHTROPE", 0, 3.6, 4.4, 0.4,  3),
        new Jump("COBWEB_DN", "COBWEB",   -2, 2.0, 2.6, 0.3,  2),
        new Jump("HONEY_HOP", "HONEY",     0, 2.8, 3.2, 0.6,  2),
    };
    private static final double MIN_GAP = 2.2;

    /** Reach ceiling for a mechanic at a realized dy (−1 = the mechanic can't achieve that dy). */
    private static double ceilFor(String mech, int dy) {
        switch (mech) {
            case "ICE": return dy <= 0 ? 4.6 : -1.0;       // slippery; slight momentum, never used to climb
            case "LADDER": return 2.2;                      // scaffolding climb (final disk approach only)
            case "COBWEB": return dy <= 0 ? 2.8 : -1.0;     // slow-fall descent
            case "TIGHTROPE": return dy <= 0 ? 4.5 : (dy == 1 ? 3.6 : -1.0); // 1×1 precision (flat reach)
            case "HONEY": return dy <= 0 ? 3.2 : (dy == 1 ? 2.8 : -1.0);     // sticky short hop
            default: return dy > 1 ? -1.0 : (dy <= 0 ? 4.5 : 3.6); // FLAT plain jump
        }
    }
    /** Max upward Y a mechanic can achieve in one step (only LADDER/scaffolding climbs higher). */
    private static int maxUp(String mech) {
        return "LADDER".equals(mech) ? 12 : 1;
    }

    /** A macro zone (outer→centre): share of the path, difficulty scalar, per-style weight bias. */
    private record Zone(String name, double frac, double diff, java.util.Map<String, Double> bias) {}

    private static final Zone[] ZONES = {
        new Zone("amethyst_caverns", 0.20, 0.30, java.util.Map.of("PRECISE", 1.6, "FLAT", 1.4, "UP1", 1.2, "DROP1", 1.2)),
        new Zone("frostbridge",      0.20, 0.55, java.util.Map.of("ICE_RUN", 3.0, "FLAT", 1.2, "LONG", 1.4, "TURN", 1.2)),
        new Zone("mosscaverns",      0.20, 0.70, java.util.Map.of("COBWEB_DN", 2.5, "HONEY_HOP", 2.0, "UP1", 1.4, "FLAT", 1.2)),
        new Zone("plunge_gorge",     0.20, 0.85, java.util.Map.of("DROP2", 2.2, "DROP3", 1.8, "COBWEB_DN", 1.8, "DROP1", 1.5, "LONG", 1.3)),
        new Zone("crystal_spires",   0.20, 1.00, java.util.Map.of("TIGHTROPE", 3.0, "HONEY_HOP", 2.0, "PRECISE", 1.8, "TURN", 1.4, "LONG", 1.3)),
    };
    // Themed palette per zone {cap, body, accent} — banded along the path, matched to the zone signature.
    private static final String[][] ZONE_PALETTE = {
        {"minecraft:amethyst_block", "minecraft:calcite", "minecraft:purpur_block"},
        {"minecraft:packed_ice", "minecraft:snow_block", "minecraft:sea_lantern"},
        {"minecraft:moss_block", "minecraft:mossy_cobblestone", "minecraft:pearlescent_froglight"},
        {"minecraft:crying_obsidian", "minecraft:obsidian", "minecraft:blackstone"},
        {"minecraft:amethyst_block", "minecraft:purpur_pillar", "minecraft:purpur_block"},
    };

    private int zoneIndex(double progress) {
        double acc = 0;
        for (int i = 0; i < ZONES.length; i++) { acc += ZONES[i].frac(); if (progress < acc) return i; }
        return ZONES.length - 1;
    }

    /**
     * Generate the branch as MECHANIC-TAGGED platforms (spawn → inward to the disk). Each step carries the
     * mechanic of the jump INTO it (FLAT/ICE/SLIME/TIGHTROPE/LADDER/COBWEB/HONEY) — which sets both the
     * feasibility ceiling ({@link #ceilFor}) and the blocks placed. Proven 0-impossible (sim_parkour_v3).
     */
    private List<Step> platformSteps(RaidDefinition def, int slot, long seed) {
        Random rnd = rng(seed, slot);
        BlockPos c = def.arena.center.blockPos();
        double baseAngle = sectorAngle(def, slot);
        double ux = Math.cos(baseAngle), uz = Math.sin(baseAngle);
        double px = -Math.sin(baseAngle), pz = Math.cos(baseAngle);
        double rOuter = def.arena.starRadius, rEnd = def.arena.centerRadius + 2.0;
        int yLo = c.getY() - 8, yHi = c.getY() + 10; // wider envelope: slime/ladder genuinely climb

        List<Step> out = new ArrayList<>();
        double radius = rOuter, lat = 0;
        int y = c.getY();
        BlockPos prev = at(c, ux, uz, px, pz, radius, lat, y);
        out.add(new Step(prev, "SPAWN"));

        int every = Math.max(1, def.arena.checkpointEvery);
        int zig = rnd.nextBoolean() ? 1 : -1;
        int sinceFlip = 0, flipEvery = 2 + rnd.nextInt(2);
        int guard = 0;
        while (radius - rEnd > 3.6 && guard++ < 90) {
            double rem = radius - rEnd;
            double progress = 1.0 - rem / Math.max(1.0, rOuter - rEnd);
            int zidx = zoneIndex(Math.max(0, Math.min(0.999, progress)));
            boolean cp = (out.size() % every == 0);
            String mech; int dyPick; double dLo, dHi, latAmp;
            if (cp) {
                mech = "FLAT"; dyPick = (rnd.nextInt(3) == 0 ? -1 : 0); dLo = 3.0; dHi = 3.6; latAmp = 0.2;
            } else {
                Jump j = pickStyle(rnd, zidx);
                mech = j.mech(); dyPick = j.dy(); dLo = j.dLo(); dHi = j.dHi(); latAmp = j.latAmp();
                double diff = ZONES[zidx].diff();
                dLo = dHi - (dHi - dLo) * (1 - 0.6 * diff);
            }
            int upcap = maxUp(mech);
            if (dyPick > upcap) dyPick = upcap;
            int newY = Math.max(yLo, Math.min(yHi, y + dyPick));
            // landing funnel near centre: descend-only, at most a +1 step, plain FLAT (no mechanic needed)
            if (rem < 16) {
                int fHi = c.getY() + (rem > 10 ? 2 : 1), fLo = c.getY() - 1;
                newY = Math.max(fLo, Math.min(fHi, newY));
                if (newY > y + 1) newY = y + 1;
                mech = "FLAT";
            }
            int dy = newY - y;
            // RECONCILE the mechanic with the realized dy (funnel/envelope may have changed it).
            if (ceilFor(mech, dy) < 0) {
                if (dy > 1) { newY = y + 1; dy = 1; }
                mech = "FLAT";
            }
            double ceil = ceilFor(mech, dy);
            dHi = Math.min(dHi, ceil);
            if (dLo > dHi) dLo = Math.max(2.0, dHi - 0.3);
            if (++sinceFlip >= flipEvery) { zig = -zig; sinceFlip = 0; flipEvery = 2 + rnd.nextInt(2); }
            double latStep = zig * rnd.nextDouble() * latAmp;

            double adv = dHi, rTry = radius, latTry = lat;
            BlockPos next = prev;
            for (int g = 0; g < 48; g++) {
                rTry = Math.max(rEnd, radius - adv);
                double latScale = Math.min(1.0, (rTry - rEnd) / 18.0);
                latTry = Math.max(-7.0, Math.min(7.0, lat + latStep)) * latScale;
                next = at(c, ux, uz, px, pz, rTry, latTry, newY);
                double d = horizDist(prev, next);
                if (d > dHi) adv -= 0.12;
                else if (d < dLo) { if (radius - (adv + 0.12) < rEnd) break; adv += 0.12; }
                else break;
                if (adv < 1.2) break;
            }
            next = clampReach(prev, next, ceil);
            if (horizDist(prev, next) < MIN_GAP && !mech.equals("LADDER") && !mech.equals("COBWEB")) {
                double tx = next.getX() - prev.getX(), tz = next.getZ() - prev.getZ();
                double tl = Math.sqrt(tx * tx + tz * tz);
                if (tl > 1e-4) {
                    double f = Math.min(MIN_GAP, ceil) / tl;
                    BlockPos pushed = new BlockPos((int) Math.round(prev.getX() + tx * f), newY,
                        (int) Math.round(prev.getZ() + tz * f));
                    double pr = (pushed.getX() - c.getX()) * ux + (pushed.getZ() - c.getZ()) * uz;
                    if (horizDist(prev, pushed) <= ceil && pr < radius) next = pushed;
                }
            }
            radius = (next.getX() - c.getX()) * ux + (next.getZ() - c.getZ()) * uz;
            lat = latTry; y = newY; prev = next;
            out.add(new Step(next, mech));
        }
        // Final platform on the disk floor — re-fit the last hops so each is a doable FLAT/UP1 jump.
        BlockPos finalP = at(c, ux, uz, px, pz, rEnd, 0, c.getY() - 1);
        int pops = 0;
        while (out.size() > 1 && horizDist(out.get(out.size() - 1).pos(), finalP) < 3.0 && pops++ < 3)
            out.remove(out.size() - 1);
        int inserts = 0;
        while (horizDist(out.get(out.size() - 1).pos(), finalP) > 4.3 && inserts++ < 10) {
            BlockPos last = out.get(out.size() - 1).pos();
            int ny = last.getY();
            if (ny < c.getY() - 1) ny = Math.min(c.getY() - 1, ny + 1);   // climb at most +1 toward floor
            else if (ny > c.getY() - 1) ny = ny - 1;                      // descend toward floor (free)
            BlockPos mid = new BlockPos((int) Math.round((last.getX() + finalP.getX()) / 2.0), ny,
                (int) Math.round((last.getZ() + finalP.getZ()) / 2.0));
            int dyb = ny - last.getY();
            BlockPos mc = clampReach(last, mid, dyb > 0 ? 3.6 : 4.5);
            if (horizDist(last, mc) < 2.0) break;
            out.add(new Step(mc, "FLAT"));
        }
        BlockPos last = out.get(out.size() - 1).pos();
        if (last.getY() < c.getY() - 2) { // too deep — a final vertical climb cell (always doable)
            BlockPos lift = new BlockPos(last.getX(), c.getY() - 2, last.getZ());
            out.add(new Step(lift, "LADDER"));
            last = lift;
        }
        int dyf = (c.getY() - 1) - last.getY();
        BlockPos fp = clampReach(last, finalP, dyf > 0 ? 3.6 : 4.5);
        out.add(new Step(fp, "FLAT"));
        return out;
    }

    /** Just the platform centres (positions) — used by checkpoints/spawn/cleanup. */
    private List<BlockPos> platformCenters(RaidDefinition def, int slot, long seed) {
        List<BlockPos> ps = new ArrayList<>();
        for (Step s : platformSteps(def, slot, seed)) ps.add(s.pos());
        return ps;
    }

    /** Pick a jump style weighted by the current zone's bias. */
    private Jump pickStyle(Random rnd, int zidx) {
        Zone z = ZONES[zidx];
        double[] w = new double[STYLES.length];
        double total = 0;
        for (int i = 0; i < STYLES.length; i++) {
            double bias = z.bias().getOrDefault(STYLES[i].name(), 1.0);
            w[i] = Math.max(1, Math.round(STYLES[i].weight() * bias));
            total += w[i];
        }
        double roll = rnd.nextDouble() * total, acc = 0;
        for (int i = 0; i < STYLES.length; i++) { acc += w[i]; if (roll < acc) return STYLES[i]; }
        return STYLES[1];
    }

    /** Pull {@code b} back toward {@code a} (XZ) until the gap ≤ {@code ceil} — feasibility guarantee. If
     *  straight-line rounding can't land on a cell ≤ ceil (integer-grid aliasing), search a 5×5 cell
     *  neighbourhood for the nearest-to-target cell within ceil (and ≥1.4 so it never merges). */
    private BlockPos clampReach(BlockPos a, BlockPos b, double ceil) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= ceil || d < 1e-4) return b;
        double f = ceil / d;
        BlockPos nb = b;
        for (int g = 0; g < 14; g++) {
            nb = new BlockPos((int) Math.round(a.getX() + dx * f), b.getY(), (int) Math.round(a.getZ() + dz * f));
            if (horizDist(a, nb) <= ceil) return nb;
            f -= 0.05;
        }
        BlockPos best = null; double bestD = 1e9;
        for (int ox = -2; ox <= 2; ox++) for (int oz = -2; oz <= 2; oz++) {
            BlockPos cand = new BlockPos(nb.getX() + ox, b.getY(), nb.getZ() + oz);
            double ga = horizDist(a, cand);
            if (ga <= ceil && ga >= 1.4) {
                double db = horizDist(b, cand);
                if (db < bestD) { bestD = db; best = cand; }
            }
        }
        return best != null ? best : nb;
    }

    private BlockPos at(BlockPos c, double ux, double uz, double px, double pz,
                        double radius, double lat, int y) {
        double wx = c.getX() + radius * ux + lat * px;
        double wz = c.getZ() + radius * uz + lat * pz;
        return new BlockPos((int) Math.round(wx), y, (int) Math.round(wz));
    }

    /** Horizontal (XZ) block-to-block distance between two platform centers. */
    private double horizDist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public BlockPos spawnPlatform(RaidDefinition def, int slot, long seed) {
        return platformCenters(def, slot, seed).get(0);
    }

    /** Checkpoint platform centers (every checkpointEvery platforms, excluding spawn and final). */
    public List<BlockPos> checkpoints(RaidDefinition def, int slot, long seed) {
        List<BlockPos> centers = platformCenters(def, slot, seed);
        List<BlockPos> cps = new ArrayList<>();
        int every = Math.max(1, def.arena.checkpointEvery);
        for (int i = every; i < centers.size() - 1; i += every) cps.add(centers.get(i));
        return cps;
    }

    /**
     * All blocks of a branch, ordered outer→inner. Spawn/final/checkpoints are 3×3 plates (+ below-plane
     * skirt + beacon); every other platform is a 1×1 LANDING cap above a themed below-plane STRUCTURE
     * (pillar/island/crystal — all strictly below the landing plane so they never enter a jump corridor),
     * plus dimensional-ruin ambiance far to the side. Themed by the same 5 zones as the geometry.
     */
    private LinkedHashMap<BlockPos, BlockState> computeBranch(RaidDefinition def, int slot, long seed) {
        LinkedHashMap<BlockPos, BlockState> map = new LinkedHashMap<>();
        List<Step> steps = platformSteps(def, slot, seed);
        List<BlockPos> centers = new ArrayList<>();
        for (Step s : steps) centers.add(s.pos());
        Set<BlockPos> cps = new HashSet<>(checkpoints(def, slot, seed));
        BlockState plat = Mc.block(def.arena.platformBlock);
        BlockState cp = Mc.block(def.arena.checkpointBlock);
        BlockState marker = Mc.block("minecraft:sea_lantern");
        int n = steps.size();
        BlockPos c = def.arena.center.blockPos();
        double rOuter = def.arena.starRadius, rEnd = def.arena.centerRadius + 2.0;

        // Pass 1 — special plates (spawn / checkpoints / final): 3×3 + below-plane skirt + beacon marker.
        for (int i = 0; i < n; i++) {
            BlockPos ctr = centers.get(i);
            boolean isCp = cps.contains(ctr) && i != 0 && i != n - 1;
            boolean special = isCp || i == 0 || i == n - 1;
            if (!special) continue;
            BlockState st = isCp ? cp : plat;
            for (BlockPos b : shape(ctr, 1)) { map.put(b, st); map.put(b.down(1), st); } // plate + skirt
            map.put(ctr.down(2), marker);
            if (isCp) map.put(ctr.down(3), marker);
        }
        // Pass 2 — per-platform MECHANIC: cap + the visible blocks the player SEES and uses (ice runways,
        // web wells, honey, tightrope ropes, scaffolding climbs) + a visible base column.
        for (int i = 1; i < n; i++) {
            BlockPos ctr = centers.get(i), prev = centers.get(i - 1);
            boolean special = (cps.contains(ctr) && i != n - 1) || i == n - 1;
            if (special) continue; // specials stay clean 3×3 rests
            String[] pal = ZONE_PALETTE[zoneIndex(zoneProgress(ctr, c, rOuter, rEnd))];
            placeMechanic(map, prev, ctr, steps.get(i).mech(), pal, marker);
        }
        // Pass 3 — dimensional-ruin ambiance far beside the path (lateral 10..13, never in a jump arc).
        double a = sectorAngle(def, slot);
        double pxa = -Math.sin(a), pza = Math.cos(a);
        Random arnd = rng(seed ^ 0xABCDEFL, slot);
        for (int i = 3; i < n - 1; i += 3) {
            BlockPos ctr = centers.get(i);
            int side = arnd.nextBoolean() ? 1 : -1;
            double off = 10 + arnd.nextInt(4);
            BlockPos anchor = ctr.add((int) Math.round(off * side * pxa), 0, (int) Math.round(off * side * pza));
            double ar = Math.hypot(anchor.getX() - c.getX(), anchor.getZ() - c.getZ());
            if (ar >= 17 && ar <= 30) continue; // don't overwrite the temple ring (cosmetic clip)
            placeRuin(map, anchor, zoneIndex(zoneProgress(ctr, c, rOuter, rEnd)), arnd);
        }
        return map;
    }

    private double zoneProgress(BlockPos ctr, BlockPos c, double rOuter, double rEnd) {
        double radius = Math.hypot(ctr.getX() - c.getX(), ctr.getZ() - c.getZ());
        return Math.max(0, Math.min(0.999, 1.0 - (radius - rEnd) / Math.max(1.0, rOuter - rEnd)));
    }

    /** Place a 1×1 landing cap + the VISIBLE mechanic blocks the player uses to reach it (ice runway, web
     *  well, honey, tightrope rope, scaffolding climb) + a visible below-plane base column. The landing
     *  cell + the two cells above it always stay clear air. */
    private void placeMechanic(LinkedHashMap<BlockPos, BlockState> map, BlockPos prev, BlockPos ctr,
                               String mech, String[] pal, BlockState glow) {
        BlockState body = Mc.block(pal[1]), accent = Mc.block(pal[2]);
        BlockState cap = switch (mech) {
            case "ICE" -> Mc.block("minecraft:packed_ice");
            case "HONEY" -> Mc.block("minecraft:honey_block");
            case "TIGHTROPE" -> Mc.block("minecraft:chiseled_quartz_block");
            default -> Mc.block(pal[0]);
        };
        map.put(ctr, cap);
        for (int dy = 1; dy <= 4; dy++) map.putIfAbsent(ctr.down(dy), body); // visible base column
        map.putIfAbsent(ctr.down(2), glow);                                  // glowing core
        int ax = Integer.signum(prev.getX() - ctr.getX()), az = Integer.signum(prev.getZ() - ctr.getZ());
        switch (mech) {
            case "ICE" -> { // a blue-ice momentum runway behind the launch (sprint across it then leap)
                BlockState ice = Mc.block("minecraft:blue_ice");
                map.put(prev, ice);
                map.putIfAbsent(prev.add(ax, 0, az), ice);
                map.putIfAbsent(prev.add(2 * ax, 0, 2 * az), ice);
            }
            case "COBWEB" -> { // a webbed well you slow-fall down into this (lower) cap
                for (int yy = ctr.getY() + 1; yy <= prev.getY(); yy++)
                    map.putIfAbsent(new BlockPos(ctr.getX(), yy, ctr.getZ()), Mc.block("minecraft:cobweb"));
            }
            case "LADDER" -> { // a scaffolding climb up to this (higher) cap (final disk approach)
                int mx = (prev.getX() + ctr.getX()) / 2, mz = (prev.getZ() + ctr.getZ()) / 2;
                for (int yy = prev.getY(); yy <= ctr.getY(); yy++)
                    map.putIfAbsent(new BlockPos(mx, yy, mz), Mc.block("minecraft:scaffolding"));
            }
            case "TIGHTROPE" -> { // a visible rope of posts under the line (precision 1×1 landing)
                map.putIfAbsent(ctr.add(ax, -1, az), Mc.block("minecraft:cobbled_deepslate_wall"));
                map.putIfAbsent(ctr.down(1), Mc.block("minecraft:cobbled_deepslate_wall"));
            }
            default -> { // FLAT/HONEY: a flared collar for visual body
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) map.putIfAbsent(ctr.add(o[0], -1, o[1]), accent);
            }
        }
    }

    /** A small themed ruin spire BESIDE the path (≥10 lateral — outside every jump corridor). */
    private void placeRuin(LinkedHashMap<BlockPos, BlockState> map, BlockPos a, int zidx, Random r) {
        String[] pal = ZONE_PALETTE[zidx];
        BlockState body = Mc.block(pal[1]), accent = Mc.block(pal[0]);
        BlockState glow = Mc.block(zidx >= 3 ? "minecraft:shroomlight" : "minecraft:ochre_froglight");
        int h = 4 + r.nextInt(5);
        for (int dy = 0; dy < h; dy++) map.putIfAbsent(a.up(dy), (dy == h - 1) ? accent : body);
        map.putIfAbsent(a.up(h), glow);
        map.putIfAbsent(a.up(h + 1), Mc.block("minecraft:end_rod"));
        if (r.nextBoolean()) map.putIfAbsent(a.add(1, h - 2, 0), accent);
        if (r.nextBoolean()) map.putIfAbsent(a.add(-1, h - 1, 0), accent);
    }

    /** A (2·half+1)² square of block positions at {@code center}'s Y. */
    private Set<BlockPos> shape(BlockPos center, int half) {
        java.util.LinkedHashSet<BlockPos> s = new java.util.LinkedHashSet<>();
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                s.add(center.add(dx, 0, dz));
        return s;
    }

    // ── Reveal tick ────────────────────────────────────────────────────────────

    public void tick() {
        if (reveals.isEmpty()) return;
        Iterator<BranchRevealTask> it = reveals.iterator();
        while (it.hasNext()) {
            if (it.next().tick()) it.remove();
        }
    }

    public void clearReveals() { reveals.clear(); }

    /**
     * Places a branch's blocks progressively, with particles + sound, a few per tick. Waits
     * {@code delay} ticks after the player lands (a brief "charging" cue at the spawn) before the
     * path animates in, so the player actually sees it appear.
     */
    private static final class BranchRevealTask {
        final ServerWorld world;
        final List<Map.Entry<BlockPos, BlockState>> blocks;
        final int perTick;
        final String particle, sound;
        final BlockPos spawn;
        int index = 0;
        int delay;
        final int initialDelay;
        boolean finished = false;

        BranchRevealTask(ServerWorld world, List<Map.Entry<BlockPos, BlockState>> blocks,
                         int perTick, String particle, String sound, int delay, BlockPos spawn) {
            this.world = world; this.blocks = blocks;
            this.perTick = Math.max(1, perTick); this.particle = particle; this.sound = sound;
            this.delay = Math.max(0, delay); this.initialDelay = Math.max(0, delay); this.spawn = spawn;
        }

        boolean tick() {
            double sx = spawn.getX() + 0.5, sy = spawn.getY() + 1.0, sz = spawn.getZ() + 0.5;
            // ── Act 1 — anticipation charge: a tightening portal column with a rising beacon pitch.
            if (delay > 0) {
                float prog = initialDelay <= 0 ? 1f : (initialDelay - delay) / (float) initialDelay;
                if (delay % 4 == 0) {
                    world.spawnParticles(ParticleTypes.REVERSE_PORTAL, sx, sy, sz,
                        8, 0.6 * (1 - prog) + 0.1, 0.8, 0.6 * (1 - prog) + 0.1, 0.05);
                    world.spawnParticles(ParticleTypes.END_ROD, sx, sy + 0.3, sz, 4, 0.25, 0.7, 0.25, 0.02);
                    world.spawnParticles(ParticleTypes.WAX_OFF, sx, sy, sz, 6, 0.4, 0.5, 0.4, 0.04);
                    Mc.sound(world, "minecraft:block.beacon.ambient", spawn, 0.5f, 1.3f + prog * 0.6f);
                }
                if (--delay == 0) { // ignition cue
                    world.spawnParticles(ParticleTypes.FLASH, sx, sy, sz, 1, 0, 0, 0, 0);
                    Mc.sound(world, "minecraft:block.conduit.activate", spawn, 1.0f, 1.4f);
                    Mc.sound(world, "minecraft:entity.illusioner.prepare_mirror", spawn, 0.8f, 0.8f);
                }
                return false;
            }
            // ── Act 2 — construction sweep: each block materializes, a build-beam races to the leading edge.
            int end = Math.min(index + perTick, blocks.size());
            for (; index < end; index++) {
                Map.Entry<BlockPos, BlockState> e = blocks.get(index);
                BlockPos bp = e.getKey();
                world.setBlockState(bp, e.getValue(), Block.NOTIFY_LISTENERS);
                double bx = bp.getX() + 0.5, by = bp.getY() + 0.6, bz = bp.getZ() + 0.5;
                Mc.particles(world, particle, bp, 2); // configured revealParticle
                world.spawnParticles(ParticleTypes.END_ROD, bx, by, bz, 2, 0.1, 0.4, 0.1, 0.04);
                world.spawnParticles(ParticleTypes.ENCHANT, bx, by + 0.4, bz, 4, 0.2, 0.2, 0.2, 0.6);
                world.spawnParticles(ParticleTypes.WAX_OFF, bx, by - 0.2, bz, 3, 0.25, 0.1, 0.25, 0.02);
                if (index % 4 == 0)
                    world.spawnParticles(ParticleTypes.DRAGON_BREATH, bx, by, bz, 2, 0.2, 0.05, 0.2, 0.0);
            }
            if (index < blocks.size()) {
                BlockPos lead = blocks.get(index).getKey();
                BlockPos from = blocks.get(Math.max(0, index - 1)).getKey();
                for (int s = 1; s <= 5; s++) {
                    double f = s / 5.0;
                    world.spawnParticles(ParticleTypes.END_ROD,
                        from.getX() + (lead.getX() - from.getX()) * f + 0.5,
                        from.getY() + (lead.getY() - from.getY()) * f + 1.0,
                        from.getZ() + (lead.getZ() - from.getZ()) * f + 0.5, 1, 0, 0, 0, 0);
                }
                world.spawnParticles(ParticleTypes.FIREWORK, lead.getX() + 0.5, lead.getY() + 1.0, lead.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.05);
                world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, lead.getX() + 0.5, lead.getY() + 0.8, lead.getZ() + 0.5, 3, 0.15, 0.15, 0.15, 0.01);
            }
            if (index > 0 && index % 6 == 0) {
                BlockPos at = blocks.get(index - 1).getKey();
                float t = index / (float) Math.max(1, blocks.size());
                float pitch = 0.9f + t * 1.0f; // musical climb 0.9 → 1.9
                Mc.sound(world, sound, at, 0.7f, pitch);
                Mc.sound(world, "minecraft:block.amethyst_cluster.place", at, 0.4f, pitch);
            }
            // ── Act 3 — connection flourish (fires once): the path links to the boss disk.
            if (index >= blocks.size() && !finished) {
                finished = true;
                if (!blocks.isEmpty()) {
                    BlockPos endP = blocks.get(blocks.size() - 1).getKey();
                    world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, endP.getX() + 0.5, endP.getY() + 1.2, endP.getZ() + 0.5, 40, 0.6, 0.8, 0.6, 0.15);
                    world.spawnParticles(ParticleTypes.FIREWORK, endP.getX() + 0.5, endP.getY() + 1.5, endP.getZ() + 0.5, 20, 0.5, 0.5, 0.5, 0.1);
                    world.spawnParticles(ParticleTypes.END_ROD, endP.getX() + 0.5, endP.getY() + 1.0, endP.getZ() + 0.5, 16, 0.4, 0.6, 0.4, 0.05);
                    Mc.sound(world, "minecraft:block.conduit.activate", endP, 1.0f, 1.0f);
                    Mc.sound(world, "minecraft:ui.toast.challenge_complete", endP, 0.6f, 1.2f);
                }
                world.spawnParticles(ParticleTypes.END_ROD, sx, spawn.getY() + 1.2, sz, 12, 0.5, 0.5, 0.5, 0.04);
                Mc.sound(world, "minecraft:block.beacon.activate", spawn, 0.7f, 1.6f);
            }
            return index >= blocks.size();
        }
    }
}

package com.cheeseraids.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Static configuration of a raid (Gson-serialized, public fields with defaults).
 * One definition = one kind of raid. The framework is generic; "hoopa" is the first.
 * Loaded from config/cheeseraids/raids/&lt;id&gt;.json (a default hoopa.json is written on
 * first run via {@link #defaultHoopa()}).
 */
public class RaidDefinition {

    public String id = "hoopa";
    public String displayName = "Invasion Hoopa";
    public String icon = "👻"; // 👻
    public boolean enabled = true;

    public Schedule schedule = new Schedule();
    public Announce announce = new Announce();
    public Wave wave = new Wave();
    public Npc npc = new Npc();
    public Arena arena = new Arena();
    public Boss boss = new Boss();
    public Reward reward = new Reward();
    /** Where players are ejected after the raid ends. */
    public Pos exitPos = new Pos();

    // ── Nested config ───────────────────────────────────────────────────────

    public static class Schedule {
        public boolean autoTrigger = false; // off by default until configured in-game
        public int hour = 20;   // 0-23, server local time
        public int minute = 0;
        public int everyNDays = 1; // 1 = daily, 2 = every other day, ...
    }

    public static class Announce {
        public String title = "Invasion Hoopa";
        public String subtitle = "Une faille dimensionnelle s'ouvre...";
        public String chat = "Une invasion Hoopa commence ! Rejoignez l'arene pour repousser l'attaque.";
        public boolean discord = true;
        public String discordMessage = "👻 Une **Invasion Hoopa** vient de commencer !";
    }

    /** Overworld wave: uncatchable mobs that must all be defeated to summon the herald. */
    public static class Wave {
        public Pos arena = new Pos();   // overworld spawn center
        public double spread = 6.0;
        public List<SpawnEntry> spawns = new ArrayList<>();
        public int graceSecondsBeforeWave = 5;
        public boolean overworldStorm = true; // force night + thunderstorm at the wave location during the event
        /** Console template run once per mob. Placeholders: {dim} {x} {y} {z} {species} {level} {aspects}.
         *  {aspects} expands to "aspect=<a> ..." (Cobblemon pokespawn syntax). */
        public String spawnCommand = "execute in {dim} positioned {x} {y} {z} run pokespawn {species} {aspects} level={level}";
        public String entityType = "cobblemon:pokemon"; // entity id used to detect spawned mobs
        public double detectRadius = 18.0;               // box half-size around arena to capture spawns
        public int checkIntervalTicks = 20;              // wave-clear poll cadence
        public int captureWindowMs = 3000;               // window to capture async-spawned mobs after the command
        public int timeoutSeconds = 180;                 // force-advance if the wave never clears
    }

    public static class SpawnEntry {
        public String species = "hoopa";
        public String aspects = "";   // e.g. "alpha" — comma list
        public int level = 70;
        public int count = 1;
        public boolean leader = false; // the alpha boss of the wave
    }

    /** The Ho-Oh herald (EasyNPC) that appears once the wave is cleared. */
    public static class Npc {
        public String easyNpcName = "hooh-raid";              // informative label / vanilla custom name
        public String entityType = "easy_npc:humanoid_slim";  // EasyNPC entity id for the position selector
        public Pos pos = new Pos();
        // The herald is revealed/hidden in Java (EasyNpcHook scans for the EasyNPC near {pos} and
        // toggles vanilla invisibility + glow) — reliable, no selector/dimension pitfalls. These
        // optional command templates run IN ADDITION if set. Placeholders: {world} {type} {x} {y} {z} {npc}.
        public String showCommand = "";
        public String hideCommand = "";
        public int despawnAfterSeconds = 600; // auto-hide herald if raid never started
    }

    /** Shared "rift" dimension + procedural star arena. */
    public static class Arena {
        public String model = "SHARED";          // SHARED | PER_PLAYER
        public String riftDimId = "hoopa_rift";  // → cheeseguard:hoopa_rift
        public Pos center = new Pos("cheeseguard:hoopa_rift", 0.5, 100, 0.5); // boss platform centre
        public double starRadius = 75.0;         // centre → branch spawn platform (longer = more jumps)
        public int maxSlots = 12;                // max simultaneous branches
        public int parkourLength = 24;           // (informative; actual count is derived from gap)
        public double gapMin = 2.8, gapMax = 3.6;// centre-to-centre jump distance range (1×1, reliably doable)
        public double riseMax = 1.0;             // (informative)
        public int checkpointEvery = 6;          // checkpoint cadence (fewer = harder)
        public int revealBlocksPerTick = 8;      // BranchRevealTask speed
        public int revealDelayTicks = 60;        // wait after the player lands before the path animates in (20=1s)
        public String revealParticle = "ENCHANT";
        public String revealSound = "minecraft:block.amethyst_block.chime";
        public String platformBlock = "minecraft:purpur_block";
        public String checkpointBlock = "minecraft:sea_lantern";
        public String centerBlock = "minecraft:crying_obsidian";
        public int centerRadius = 15;            // boss arena radius (big colosseum)
        public double fallBelow = 12.0;          // blocks below centre Y before respawn (relative)
        public int openTimeoutSeconds = 180;     // OPEN → COMBAT fallback even if nobody reaches centre
        public boolean keepInventory = true;     // players fight with their team
    }

    public static class Boss {
        public String provider = "CUSTOM_GIANT"; // CUSTOM_GIANT | RAID_DEN
        public String species = "hoopa";
        public String aspects = "unbound";
        public double baseScale = 1.5;
        public int baseLevel = 80;
        public int levelPerExtraPlayer = 3;       // scaling with participant count
        public double graceSeconds = 30;          // wait for stragglers after first arrival
        /** Console template to spawn the boss. Placeholders: {dim} {x} {y} {z} {species} {level} {aspects}. */
        public String spawnCommand = "execute in {dim} positioned {x} {y} {z} run pokespawn {species} {aspects} level={level}";
        public String entityType = "cobblemon:pokemon";
        public double detectRadius = 8.0;
        /** Optional command run on the spawned boss to enlarge it. Placeholders: {uuid} {scale}. Empty = skip. */
        public String scaleCommand = "";
        public int captureWindowMs = 3000;        // window to capture the async-spawned boss
        public int combatTimeoutSeconds = 900;    // force CLEANUP if the boss is never defeated
    }

    public static class Reward {
        public int portalDivisor = 3;             // floor(participants / divisor)
        public int minPortals = 1;
        public Pos portalAnchor = new Pos();      // where portals are built (overworld)
        public double portalSpacing = 4.0;
        /** Console template run once per portal. Placeholders: {dim} {x} {y} {z} {index}. Empty = skip. */
        public String portalCommand = "";
        public long fromage = 0;                  // optional currency per winner (CheeseStore wallet)
        public List<String> commands = new ArrayList<>(); // per-winner reward commands, {player} placeholder
    }

    // ── Default Hoopa definition (written on first run) ──────────────────────

    public static RaidDefinition defaultHoopa() {
        RaidDefinition d = new RaidDefinition();
        SpawnEntry leader = new SpawnEntry();
        leader.species = "hoopa"; leader.aspects = "unbound"; leader.level = 80; leader.count = 1; leader.leader = true;
        SpawnEntry minions = new SpawnEntry();
        minions.species = "sableye"; minions.level = 60; minions.count = 4;
        d.wave.spawns.add(leader);
        d.wave.spawns.add(minions);
        return d;
    }
}

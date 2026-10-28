package com.cheeseraids.data;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runtime state of an ACTIVE raid. Persisted to config/cheeseraids/instances/&lt;raidId&gt;.json
 * so a server crash/restart mid-raid recovers cleanly (no regeneration, no item dupe — cf.
 * the conseil4 + EggAndCheese crash-loop lessons).
 */
public class RaidInstance {
    public String raidId;
    public RaidPhase phase = RaidPhase.IDLE;
    public long startedAt;        // epoch ms of ANNOUNCE
    public long phaseStartedAt;   // epoch ms of current phase

    /** uuid → per-player state. LinkedHashMap keeps join order (slot assignment). */
    public Map<String, PlayerRaidState> players = new LinkedHashMap<>();
    public Set<Integer> usedSlots = new HashSet<>();

    // Overworld wave tracking
    public int waveTotal;         // mobs to defeat
    public java.util.List<String> waveEntityUuids = new java.util.ArrayList<>();
    public Set<String> waveBefore = new HashSet<>();   // entity uuids present before spawn (diff base)
    public long waveCaptureUntil; // epoch ms — keep capturing async-spawned mobs until this
    public String alphaUuid;      // the wave leader (alpha) entity uuid, tracked for its own fx + defeat msg
    public boolean alphaAnnounced; // big "alpha fled" message shown once

    // OPEN phase
    public long openDeadline;     // epoch ms — force COMBAT even if nobody reaches centre

    // Boss tracking (COMBAT phase)
    public boolean bossSpawned = false;
    public String bossEntityUuid;
    public String bossBattleId;    // Cobblemon battle uuid the boss engaged in (to wait for battle end)
    public int bossSpawnTick;      // server tick the boss was (re)spawned, for early-vanish detection
    public int bossRespawns;       // safety respawns if the boss vanished pre-battle (suffocation)
    public long rewardStartedAt;   // epoch ms the victory sequence began (effect → tp delay)
    public long bossGraceDeadline; // epoch ms — spawn boss when all arrived OR deadline passed
    public Set<String> bossBefore = new HashSet<>();
    public long bossCaptureUntil;  // epoch ms — keep trying to capture the async-spawned boss

    public RaidInstance() {}
    public RaidInstance(String raidId) { this.raidId = raidId; }

    public PlayerRaidState player(String uuid) { return players.get(uuid); }

    public int nextFreeSlot(int maxSlots) {
        for (int i = 0; i < maxSlots; i++) if (!usedSlots.contains(i)) return i;
        return -1;
    }

    public boolean allArrived() {
        if (players.isEmpty()) return false;
        for (PlayerRaidState p : players.values()) {
            if (p.entered && !p.arrived) return false;
        }
        return true;
    }

    public int participantCount() { return players.size(); }
}

package com.cheeseraids.data;

/**
 * Per-player runtime state inside a raid instance. Persisted with the instance so a
 * relog/crash NEVER regenerates a player's branch — we restore them to their last
 * checkpoint instead (hard constraint).
 */
public class PlayerRaidState {
    public String uuid;
    public String name;
    public int slot = -1;          // star branch slot (angular index)
    public long seed;              // deterministic seed for this player's branch layout
    public boolean entered = false; // teleported into the rift
    public boolean arrived = false; // reached the center platform
    public boolean finished = false; // raid completed for this player
    public int[] lastCheckpoint;   // {x,y,z} in rift; null = branch spawn platform
    public Pos returnPos;          // where to eject this player to

    public PlayerRaidState() {}
    public PlayerRaidState(String uuid, String name) {
        this.uuid = uuid; this.name = name;
    }
}

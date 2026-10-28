package com.sebastian.cobblemondungeons.party;

import java.util.*;

public class DungeonsParty {
    private final UUID leader;
    private final Set<UUID> members = new HashSet<>();
    private final String partyId;

    public DungeonsParty(UUID leader) {
        this.leader = leader;
        this.members.add(leader);
        this.partyId = UUID.randomUUID().toString().substring(0, 8);
    }

    public UUID getLeader() {
        return leader;
    }

    public Set<UUID> getMembers() {
        return members;
    }

    public void addMember(UUID player) {
        members.add(player);
    }

    public void removeMember(UUID player) {
        members.remove(player);
    }

    public String getPartyId() {
        return partyId;
    }
}

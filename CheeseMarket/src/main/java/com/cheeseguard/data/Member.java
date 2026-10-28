package com.cheeseguard.data;

import java.util.UUID;

public class Member {
    private final UUID uuid;
    private String name; // cached, may change if player renames

    public Member(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Member)) return false;
        return uuid.equals(((Member) o).uuid);
    }

    @Override
    public int hashCode() { return uuid.hashCode(); }

    @Override
    public String toString() { return name + " (" + uuid + ")"; }
}

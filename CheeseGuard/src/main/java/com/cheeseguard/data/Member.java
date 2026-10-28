package com.cheeseguard.data;

import java.util.UUID;

public class Member {

    public enum Role { OWNER, MEMBER }

    private final UUID uuid;
    private String name; // cached, may change if player renames
    private Role role;

    public Member(UUID uuid, String name) {
        this(uuid, name, Role.MEMBER);
    }

    public Member(UUID uuid, String name, Role role) {
        this.uuid = uuid;
        this.name = name;
        this.role = role != null ? role : Role.MEMBER;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role != null ? role : Role.MEMBER; }

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

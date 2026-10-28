package com.cheesekart;

import java.util.UUID;

public record KartSession(
    UUID playerUuid,
    String playerName,
    String presetId,
    String species,
    int boatEntityId,
    UUID boatUuid,
    long startedAt,
    boolean locked
) {
    public long durationMs() {
        return System.currentTimeMillis() - startedAt;
    }
}

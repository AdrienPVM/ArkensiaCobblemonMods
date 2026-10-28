package com.cobblecap.client;

/**
 * Shared re-entry guard for the player-statue freeze mixins.
 * Both BipedEntityModelMixin and PlayerEntityModelMixin re-invoke
 * setAngles(..., 0, 0, 0, 0, 0) on themselves to override the entity's
 * pose with a frozen one. Without a shared flag, the two mixins would
 * recurse into each other indefinitely.
 */
public final class StatueFreezeGuard {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private StatueFreezeGuard() {}

    public static boolean active() { return Boolean.TRUE.equals(ACTIVE.get()); }
    public static void set(boolean v) { ACTIVE.set(v); }
}

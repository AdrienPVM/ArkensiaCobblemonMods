package com.cheesestore.cosmetic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single entry point that wires up the whole cosmetic system. Called once from
 * {@code CheeseStoreMod#onInitialize}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Initialise the data managers ({@link CosmeticManager}, {@link OwnershipManager},
 *       {@link EquipManager}) — idempotent, safe to call multiple times.</li>
 *   <li>Initialise each cosmetic sub-system ({@link TrailManager}, {@link NameTagManager},
 *       {@link PetManager}, {@link MountManager}, {@link CombatEffectsManager}) which register
 *       their own Fabric events.</li>
 *   <li>Register the {@code /cosmetics} command via {@link CosmeticsCommands} and the
 *       {@code /mount} command via {@link MountCommand}.</li>
 * </ul>
 */
public final class CosmeticsModule {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static boolean initialised = false;

    private CosmeticsModule() {}

    /** Idempotent. Call from the mod's {@code onInitialize}. */
    public static synchronized void init() {
        if (initialised) return;
        initialised = true;

        LOGGER.info("[CheeseStore] Initialising cosmetics module...");

        // ── Data layer (managers) ──────────────────────────────────────────────
        CosmeticManager.init();
        OwnershipManager.init();
        EquipManager.init();

        // ── Behaviour layer (each registers its own Fabric events; idempotent) ──
        TrailManager.init();
        NameTagManager.init();
        PetManager.init();
        MountManager.init();
        CombatEffectsManager.init();

        // ── Networking (CAPE cosmetic broadcast — registers payload + JOIN hook) ──
        com.cheesestore.net.CapeNetworking.init();

        // ── Commands (self-registers its own CommandRegistrationCallback) ───────
        CosmeticsCommands.register();
        MountCommand.register();

        LOGGER.info("[CheeseStore] Cosmetics module initialised");
    }
}

package com.aetherialbridge;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AetherialBridgeMod implements ModInitializer {
    public static final String MOD_ID = "aetherialbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[AetherialBridge] Initialized — registers '{}' GenTypeProvider with CheeseGuard.",
            FloatingIslandsProvider.PROVIDER_ID);
    }
}

package com.roicheese.eggandcheese;

import com.roicheese.eggandcheese.advancement.EggFoundCriterion;
import com.roicheese.eggandcheese.block.AdminEggPlacedEvent;
import com.roicheese.eggandcheese.network.ModPackets;
import com.roicheese.eggandcheese.network.ModPayloads;
import com.roicheese.eggandcheese.network.PacketExtras;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import com.roicheese.eggandcheese.registry.ModBlocks;
import com.roicheese.eggandcheese.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EggAndCheese implements ModInitializer {
    public static final String MOD_ID = "eggandcheese";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[EggAndCheese] Initializing...");

        ModBlocks.init();
        ModItems.init();
        ModBlockEntities.init();

        Registry.register(Registries.CRITERION, EggFoundCriterion.ID, EggFoundCriterion.INSTANCE);

        // Must register payloads before handlers
        ModPayloads.registerAll();
        ModPackets.registerServerHandlers();
        PacketExtras.register();

        AdminEggPlacedEvent.register();
        com.roicheese.eggandcheese.util.AdminCommand.register();
        com.roicheese.eggandcheese.util.PedestalCommand.register();

        LOGGER.info("[EggAndCheese] Ready!");
    }
}

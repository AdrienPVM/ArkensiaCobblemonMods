package com.sebastian.cobblemondungeons.util;

import com.google.gson.Gson;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import com.gitlab.srcmc.rctapi.api.RCTApi;
import com.gitlab.srcmc.rctapi.api.models.TrainerModel;
import com.gitlab.srcmc.rctapi.api.models.converter.PokemonModelConverter;
import com.gitlab.srcmc.rctapi.api.models.converter.TrainerModelConverter;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctapi.api.errors.RCTErrors;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.npc.NPCPlayerModelType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;

public class TrainerLoader {
    private static final Path TRAINERS_DIR = FabricLoader.getInstance().getConfigDir().resolve("cobblemon_dungeons").resolve("trainers");

    public static void load(MinecraftServer server) {
        File folder = TRAINERS_DIR.toFile();
        if (!folder.exists()) {
            folder.mkdirs();
        }

        createExampleTrainerFile();

        RCTApi api = RCTApi.initInstance(CobblemonDungeons.MOD_ID);
        Gson gson = api.gsonBuilder().create();
        PokemonModelConverter pmc = new PokemonModelConverter();
        TrainerModelConverter tmc = new TrainerModelConverter(server, pmc);

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                EnhancedTrainerModel model = gson.fromJson(reader, EnhancedTrainerModel.class);
                if (model != null) {
                    String id = file.getName().replace(".json", "");
                    TrainerNPC trainer = tmc.toTarget(model, RCTErrors.create());
                    if (trainer != null) {
                        // Apply custom entity if specified
                        if (model.entity != null && !model.entity.isBlank()) {
                            try {
                                Identifier entityId = Identifier.of(model.entity);
                                EntityType<?> type = Registries.ENTITY_TYPE.get(entityId);
                                LivingEntity entity = (LivingEntity) type.create(server.getOverworld());
                                if (entity != null) {
                                    entity.setInvulnerable(true);
                                    entity.addCommandTag("DungeonTrainer");
                                    if (model.getName() != null) {
                                        entity.setCustomName(model.getName().getComponent());
                                        entity.setCustomNameVisible(true);
                                    }
                                    if (entity instanceof NPCEntity npc) {
                                        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Configuring template NPC for trainer: {}", id);
                                        
                                        // Set default NPC class to prevent "The Kitchen Sink" fallback issues
                                        var npcClass = NPCClasses.INSTANCE.getByName("standard");
                                        if (npcClass != null) {
                                            npc.setNpc(npcClass);
                                        }

                                        if (model.skin != null && !model.skin.isBlank()) {
                                            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Template NPC '{}' skin: {}", id, model.skin);
                                            npc.loadTextureFromGameProfileName(model.skin);
                                            entity.addCommandTag("DungeonSkin:" + model.skin);
                                        } else if (model.texture != null && !model.texture.isBlank()) {
                                            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Template NPC '{}' texture: {}", id, model.texture);
                                            NPCPlayerModelType mt = NPCPlayerModelType.DEFAULT;
                                            if ("SLIM".equalsIgnoreCase(model.modelType) || "ALEX".equalsIgnoreCase(model.modelType)) {
                                                mt = NPCPlayerModelType.SLIM;
                                            }
                                            npc.loadTexture(new java.net.URI(model.texture), mt);
                                            entity.addCommandTag("DungeonTexture:" + model.texture);
                                            if (model.modelType != null) {
                                                entity.addCommandTag("DungeonModel:" + model.modelType);
                                            }
                                        }
                                    }
                                    trainer.setEntity(entity);
                                }
                            } catch (Exception e) {
                                CobblemonDungeons.LOGGER.error("Failed to set custom entity for trainer: " + id, e);
                            }
                        }
                        api.getTrainerRegistry().registerNPC(id, trainer);
                        CobblemonDungeons.LOGGER.info("Registered custom trainer from file: " + file.getName());
                    } else {
                        CobblemonDungeons.LOGGER.error("Failed to convert trainer model from file: " + file.getName());
                    }
                }
            } catch (Exception e) {
                CobblemonDungeons.LOGGER.error("Failed to load custom trainer file: " + file.getName(), e);
            }
        }
    }

    public static class EnhancedTrainerModel extends TrainerModel {
        public String entity;
        public String skin;
        public String texture;
        public String modelType;
    }

    private static void createExampleTrainerFile() {
        File exampleFile = TRAINERS_DIR.resolve("example_trainer.json").toFile();
        if (exampleFile.exists()) return;

        try (java.io.FileWriter writer = new java.io.FileWriter(exampleFile)) {
            writer.write("{\n" +
                "  \"name\": \"Example Guardian\",\n" +
                "  \"entity\": \"cobblemon:npc\",\n" +
                "  \"skin\": \"Steve\",\n" +
                "  \"team\": [\n" +
                "    {\n" +
                "      \"species\": \"pikachu\",\n" +
                "      \"level\": 20\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        } catch (java.io.IOException e) {
            CobblemonDungeons.LOGGER.error("Failed to create example trainer file", e);
        }
    }
}

package com.sebastian.cobblemondungeons.dungeon;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.Cobblemon;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.entity.LivingEntity;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;

public class BattleHandler {
    public static void init() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(com.cobblemon.mod.common.api.Priority.HIGHEST, event -> {
            boolean hasAlpha = false;
            for (BattleActor actor : event.getBattle().getActors()) {
                for (BattlePokemon bp : actor.getPokemonList()) {
                    if (bp.getOriginalPokemon().getAspects().contains("alpha")) {
                        hasAlpha = true;
                        break;
                    }
                }
                if (hasAlpha) break;
            }

            if (hasAlpha) {
                event.cancel();
                Object gameInstance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
                if (gameInstance instanceof net.minecraft.server.MinecraftServer server) {
                    for (BattleActor actor : event.getBattle().getActors()) {
                        if (actor.getType() == ActorType.PLAYER) {
                            ServerPlayerEntity player = server.getPlayerManager().getPlayer(actor.getName().getString());
                            if (player != null) {
                                player.sendMessage(Text.translatable("cobblemondungeons.alpha_pvp_only").formatted(Formatting.RED), true);
                            }
                        }
                    }
                }
            }
            return kotlin.Unit.INSTANCE;
        });

        CobblemonEvents.BATTLE_VICTORY.subscribe(com.cobblemon.mod.common.api.Priority.NORMAL, event -> {
            Object gameInstance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
            if (!(gameInstance instanceof net.minecraft.server.MinecraftServer server)) return kotlin.Unit.INSTANCE;

            // Check losers for Alphas and Guardians
            int alphaDefeatedCount = 0;
            int guardiansDefeatedCount = 0;

            for (BattleActor loser : event.getLosers()) {
                boolean thisLoserIsGuardian = false;
                LivingEntity loserEntity = null;

                if (loser instanceof EntityBackedBattleActor<?> ebba) {
                    loserEntity = ebba.getEntity();
                    if (loserEntity != null && loserEntity.getCommandTags().contains("DungeonGuardian")) {
                        thisLoserIsGuardian = true;
                    }
                }

                if (loser.getType() == ActorType.WILD) {
                    for (BattlePokemon bp : loser.getPokemonList()) {
                        if (bp.getOriginalPokemon().getAspects().contains("alpha")) {
                            alphaDefeatedCount++;
                            break;
                        }
                    }
                } else if (loser.getType() == ActorType.NPC) {
                    String npcName = loser.getName().getString();
                    String npcNameLower = npcName.toLowerCase();
                    
                    // Check winners to find the dungeon instance and check guardianTrainerIds
                    boolean matchesGuardianName = npcNameLower.contains("guardian");
                    
                    if (!matchesGuardianName) {
                        for (BattleActor winner : event.getWinners()) {
                            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                                if (winner.getName().getString().equals(player.getName().getString())) {
                                    var instance = DungeonManager.getInstance(player.getUuid());
                                    if (instance != null) {
                                        if (instance.getConfig().spawning.guardianTrainerIds.contains(npcName)) {
                                            matchesGuardianName = true;
                                            break;
                                        }
                                        if (!matchesGuardianName) {
                                            for (com.sebastian.cobblemondungeons.config.DungeonConfig.GuardianPoolConfig g : instance.getConfig().spawning.guardianPool) {
                                                if (g.trainerId.equals(npcName)) {
                                                    matchesGuardianName = true;
                                                    break;
                                                }
                                            }
                                        }
                                        // Also check mod-specific RCT registry if not found yet
                                        if (!matchesGuardianName) {
                                            var api = com.gitlab.srcmc.rctapi.api.RCTApi.getInstance(com.sebastian.cobblemondungeons.CobblemonDungeons.MOD_ID);
                                            if (api != null && api.getTrainerRegistry().getById(npcName) != null) {
                                                matchesGuardianName = true;
                                            }
                                        }
                                    }
                                }
                            }
                            if (matchesGuardianName) break;
                        }
                    }

                    if (matchesGuardianName) {
                        thisLoserIsGuardian = true;
                    }
                }

                if (thisLoserIsGuardian) {
                    guardiansDefeatedCount++;
                    if (loserEntity != null) {
                        loserEntity.discard();
                    }
                }
            }

            ServerPlayerEntity winnerPlayer = null;
            DungeonInstance instance = null;

            for (BattleActor actor : event.getWinners()) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (actor.getName().getString().equals(player.getName().getString())) {
                        instance = DungeonManager.getInstance(player.getUuid());
                        if (instance != null) {
                            winnerPlayer = player;
                            break;
                        }
                    }
                }
                if (winnerPlayer != null) break;
            }

            if (winnerPlayer != null && instance != null) {
                if (alphaDefeatedCount > 0) {
                    for (int i = 0; i < alphaDefeatedCount; i++) {
                        DungeonManager.handleAlphaDefeated(winnerPlayer);
                    }
                }
                if (guardiansDefeatedCount > 0) DungeonManager.handleGuardianDefeated(winnerPlayer, guardiansDefeatedCount);

                if (!instance.isBossDefeated()) {
                    // Check if this was a boss battle
                    boolean bossDefeatedInBattle = false;
                    for (BattleActor actor : event.getBattle().getActors()) {
                        if (actor.getType() == ActorType.WILD) {
                            for (BattlePokemon bp : actor.getPokemonList()) {
                                Text nickname = bp.getOriginalPokemon().getNickname();
                                if (nickname != null && nickname.getString().equals(instance.getConfig().boss.customName)) {
                                    bossDefeatedInBattle = true;
                                    break;
                                }
                            }
                        }
                        if (bossDefeatedInBattle) break;
                    }
                    if (bossDefeatedInBattle) {
                        DungeonManager.handleBossDefeated(winnerPlayer);
                    }
                }
            }
            return kotlin.Unit.INSTANCE;
        });
    }
}


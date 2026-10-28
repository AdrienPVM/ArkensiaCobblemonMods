package com.sebastian.cobblemondungeons.dungeon;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.UUID;

public class DeathHandler {
    public static void init() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayerEntity player) {
                DungeonManager.handlePlayerDeath(player);
                return;
            }
            if (entity instanceof PokemonEntity pokemonEntity) {
                if (pokemonEntity.getPokemon().getAspects().contains("alpha")) {
                    if (pokemonEntity.isBattling()) return;
                    Entity attacker = damageSource.getAttacker();
                    ServerPlayerEntity owner = null;
                    if (attacker instanceof ServerPlayerEntity playerAttacker) {
                        owner = playerAttacker;
                    } else if (attacker instanceof PokemonEntity attackingPokemon) {
                        owner = attackingPokemon.getPokemon().getOwnerPlayer();
                    }
                    if (owner != null) {
                        DungeonInstance instance = DungeonManager.getInstance(owner.getUuid());
                        if (instance != null && pokemonEntity.getCommandTags().contains("DungeonBoss")) {
                            DungeonManager.handleBossDefeated(owner);
                        } else {
                            DungeonManager.handleAlphaDefeated(owner);
                        }
                    }
                } else {
                    // This is a non-alpha pokemon, likely player-owned
                    UUID ownerUuid = pokemonEntity.getPokemon().getOwnerUUID();
                    if (ownerUuid != null) {
                        Object gameInstance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
                        if (gameInstance instanceof net.minecraft.server.MinecraftServer server) {
                            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
                            if (owner != null) {
                                // If it's the last pokemon, handlePokemonTeamWipe will be called by tick.
                                // But we can trigger a check immediately.
                                if (DungeonManager.getInstance(ownerUuid) != null) {
                                    if (DungeonManager.isTeamWiped(owner)) {
                                        DungeonManager.handlePokemonTeamWipe(owner);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    }
}

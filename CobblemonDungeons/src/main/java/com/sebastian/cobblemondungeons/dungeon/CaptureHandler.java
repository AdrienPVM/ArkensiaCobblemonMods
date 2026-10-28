package com.sebastian.cobblemondungeons.dungeon;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class CaptureHandler {
    public static void init() {
        // Prevent world capture
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.NORMAL, event -> {
            PokemonEntity pokemonEntity = event.getPokemon();
            if (pokemonEntity.getPokemon().getAspects().contains("alpha")) {
                event.cancel();
            }
            return kotlin.Unit.INSTANCE;
        });

        // Prevent battle capture (and fallback for world capture if THROWN_POKEBALL_HIT is bypassed)
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.NORMAL, event -> {
            PokemonEntity pokemonEntity = event.getPokemonEntity();
            if (pokemonEntity.getPokemon().getAspects().contains("alpha")) {
                event.setCatchRate(0.0f);
                if (event.getThrower() instanceof ServerPlayerEntity player) {
                    player.sendMessage(Text.translatable("cobblemondungeons.alpha_no_catch").formatted(Formatting.RED));
                }
            }
            return kotlin.Unit.INSTANCE;
        });
    }
}

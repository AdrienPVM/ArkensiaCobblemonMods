package com.roicheese.eggandcheese.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancement.criterion.AbstractCriterion;
import net.minecraft.predicate.entity.LootContextPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Optional;

public class EggFoundCriterion extends AbstractCriterion<EggFoundCriterion.Conditions> {
    public static final Identifier ID = Identifier.of("eggandcheese", "egg_found");
    public static final EggFoundCriterion INSTANCE = new EggFoundCriterion();

    @Override
    public Codec<Conditions> getConditionsCodec() { return Conditions.CODEC; }

    public static void trigger(ServerPlayerEntity player, String eggId) {
        INSTANCE.trigger(player, cond -> cond.matches(eggId));
    }

    public record Conditions(Optional<LootContextPredicate> player, Optional<String> eggId)
            implements AbstractCriterion.Conditions {

        public static final Codec<Conditions> CODEC = RecordCodecBuilder.create(i -> i.group(
            LootContextPredicate.CODEC.optionalFieldOf("player").forGetter(Conditions::player),
            Codec.STRING.optionalFieldOf("egg_id").forGetter(Conditions::eggId)
        ).apply(i, Conditions::new));

        public boolean matches(String foundId) {
            return eggId.isEmpty() || eggId.get().equals("*") || eggId.get().equals(foundId);
        }
    }
}

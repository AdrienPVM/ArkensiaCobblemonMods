package com.cheesecompat.mixin;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerDataContainer;
import net.minecraft.village.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Verrouille la profession des villageois "entrepreneur" (LegendaryMonuments).
 *
 * Le NPC "entrepreneur" est un {@code minecraft:villager} vanilla portant la
 * profession custom {@code legendarymonuments:entrepreneur}. Son poste de
 * travail ({@code legendarymonuments:suitcase_block} / POI
 * {@code entrepreneur_poi}) est ajouté au tag vanilla {@code acquirable_job_site}.
 * Résultat : un villageois entrepreneur jamais "verrouillé par commerce" qui ne
 * voit plus sa valise réclamée à portée perd sa profession (comportement vanilla)
 * et en repique une autre via n'importe quel poste de travail proche — il "change
 * de métier" en jeu.
 *
 * Ce mixin annule tout {@code setVillagerData} qui ferait QUITTER la profession
 * entrepreneur une fois acquise. L'IA et le commerce restent intacts ; un
 * villageois peut toujours DEVENIR entrepreneur (NONE → entrepreneur passe), mais
 * ne peut plus la perdre. Ciblé : n'affecte que les villageois déjà entrepreneurs.
 *
 * Conditionnel à {@code legendarymonuments} chargé (voir CheeseCompatMixinPlugin).
 */
@Mixin(VillagerEntity.class)
public abstract class EntrepreneurProfessionLockMixin implements VillagerDataContainer {

    private static final String ENTREPRENEUR_ID = "legendarymonuments:entrepreneur";

    @Inject(method = "setVillagerData", at = @At("HEAD"), cancellable = true)
    private void cheesecompat$lockEntrepreneur(VillagerData newData, CallbackInfo ci) {
        VillagerData current = this.getVillagerData();
        if (current == null || newData == null) return;
        if (!isEntrepreneur(current.getProfession())) return;   // pas (encore) entrepreneur → laisser passer
        if (isEntrepreneur(newData.getProfession())) return;     // reste entrepreneur (ex. level up) → OK
        // Tentative de quitter la profession entrepreneur → bloquer.
        ci.cancel();
    }

    private static boolean isEntrepreneur(VillagerProfession profession) {
        if (profession == null) return false;
        Identifier id = Registries.VILLAGER_PROFESSION.getId(profession);
        return id != null && id.toString().equals(ENTREPRENEUR_ID);
    }
}

package com.cheesecompat.mixin.client;

import battle.tower.network.BattleTowerNetwork;
import com.cheesecompat.CheeseCompatMod;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.storage.ClientParty;
import com.cobblemon.mod.common.client.storage.ClientStorageManager;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;

import java.util.HashSet;
import java.util.Set;

/**
 * Battle Tower client UI fix: the party-select screen renders each Pokémon by passing only
 * `Set.of("shiny")` (or empty) to FloatingState.setCurrentAspects(), stripping every alt-form
 * aspect (regional variants, megas, origin forms, etc). The original aspects never crossed
 * the network — they were lost in BattleTowerNetwork$PartyEntry which only carries
 * speciesId/shiny.
 *
 * This mixin reads the real aspects from the Cobblemon client party mirror and merges them
 * into the set passed to setCurrentAspects(). It is a pure-visual fix; the player's Pokémon
 * objects (IV/EV/form/species/aspects/heldItem/etc.) are NEVER mutated. On any failure the
 * original (broken) set is returned unchanged, so the worst-case fallback is the existing
 * behaviour — no new bug surface.
 */
@Pseudo
@Mixin(targets = "battle.tower.client.BattleTowerScreen", remap = false)
public abstract class BattleTowerScreenAspectMixin {

    private static final ThreadLocal<BattleTowerNetwork.PartyEntry> CCMF_CURRENT_ENTRY = new ThreadLocal<>();
    private static volatile boolean CCMF_LOGGED_FAILURE = false;
    // Diagnostic flags — log each early-return scenario once to avoid log spam
    private static volatile boolean CCMF_LOGGED_PARTY_NULL = false;
    private static volatile boolean CCMF_LOGGED_SLOT_INVALID = false;
    private static volatile boolean CCMF_LOGGED_POKEMON_NULL = false;
    private static volatile boolean CCMF_LOGGED_SPECIES_NULL = false;
    private static volatile boolean CCMF_LOGGED_ID_MISMATCH = false;
    private static volatile boolean CCMF_LOGGED_ASPECTS_EMPTY = false;
    private static volatile boolean CCMF_LOGGED_ENRICH_SUCCESS = false;

    @Inject(method = "renderPokemonInSlot", at = @At("HEAD"))
    private void cheesecompat$captureEntry(DrawContext gui, BattleTowerNetwork.PartyEntry entry,
                                            int x, int y, int slotPixelSize, int slotIdx, float partialTicks,
                                            CallbackInfo ci) {
        CCMF_CURRENT_ENTRY.set(entry);
    }

    @ModifyArg(method = "renderPokemonInSlot",
               at = @At(value = "INVOKE",
                        target = "Lcom/cobblemon/mod/common/client/render/models/blockbench/FloatingState;setCurrentAspects(Ljava/util/Set;)V"))
    private Set<String> cheesecompat$enrichAspects(Set<String> originalAspects) {
        BattleTowerNetwork.PartyEntry entry = CCMF_CURRENT_ENTRY.get();
        if (entry == null) return originalAspects;
        try {
            CobblemonClient client = CobblemonClient.INSTANCE;
            if (client == null) return originalAspects;
            ClientStorageManager storage = client.getStorage();
            if (storage == null) return originalAspects;
            ClientParty party = storage.getMyParty();
            if (party == null) {
                if (!CCMF_LOGGED_PARTY_NULL) {
                    CCMF_LOGGED_PARTY_NULL = true;
                    CheeseCompatMod.LOGGER.info("[CheeseCompat][BTAspect] DIAG: party=null for slot={} speciesId={} — Cobblemon client mirror not populated", entry.slot(), entry.speciesId());
                }
                return originalAspects;
            }

            int slot = entry.slot();
            if (slot < 0) {
                if (!CCMF_LOGGED_SLOT_INVALID) {
                    CCMF_LOGGED_SLOT_INVALID = true;
                    CheeseCompatMod.LOGGER.info("[CheeseCompat][BTAspect] DIAG: slot<0 ({}) speciesId={}", slot, entry.speciesId());
                }
                return originalAspects;
            }
            Pokemon pokemon = party.get(slot);
            if (pokemon == null) {
                if (!CCMF_LOGGED_POKEMON_NULL) {
                    CCMF_LOGGED_POKEMON_NULL = true;
                    CheeseCompatMod.LOGGER.info("[CheeseCompat][BTAspect] DIAG: party.get({}) returned null — speciesId={} — party size may differ from BT roster", slot, entry.speciesId());
                }
                return originalAspects;
            }

            String serverSpeciesId = entry.speciesId();
            if (serverSpeciesId == null) return originalAspects;
            var species = pokemon.getSpecies();
            if (species == null) {
                if (!CCMF_LOGGED_SPECIES_NULL) {
                    CCMF_LOGGED_SPECIES_NULL = true;
                    CheeseCompatMod.LOGGER.info("[CheeseCompat][BTAspect] DIAG: pokemon.getSpecies()=null slot={} serverSpeciesId={}", slot, serverSpeciesId);
                }
                return originalAspects;
            }
            Object rid = species.getClass().getMethod("getResourceIdentifier").invoke(species);
            if (rid == null) return originalAspects;
            String clientSpeciesId = rid.toString();
            if (!serverSpeciesId.equals(clientSpeciesId)) {
                if (!CCMF_LOGGED_ID_MISMATCH) {
                    CCMF_LOGGED_ID_MISMATCH = true;
                    CheeseCompatMod.LOGGER.info("[CheeseCompat][BTAspect] DIAG: speciesId MISMATCH server='{}' client='{}' slot={} — this is the most likely cause of the fix not applying", serverSpeciesId, clientSpeciesId, slot);
                }
                return originalAspects;
            }

            Set<String> realAspects = pokemon.getAspects();
            if (realAspects == null || realAspects.isEmpty()) {
                if (!CCMF_LOGGED_ASPECTS_EMPTY) {
                    CCMF_LOGGED_ASPECTS_EMPTY = true;
                    CheeseCompatMod.LOGGER.info("[CheeseCompat][BTAspect] DIAG: realAspects=empty for species='{}' slot={} — no alt forms on this pokemon (this is fine for base-form mons)", serverSpeciesId, slot);
                }
                return originalAspects;
            }

            Set<String> merged = new HashSet<>(originalAspects);
            merged.addAll(realAspects);
            if (!CCMF_LOGGED_ENRICH_SUCCESS) {
                CCMF_LOGGED_ENRICH_SUCCESS = true;
                CheeseCompatMod.LOGGER.info("[CheeseCompat][BTAspect] DIAG: enrich SUCCESS — species='{}' slot={} originalAspects={} realAspects={} merged={}", serverSpeciesId, slot, originalAspects, realAspects, merged);
            }
            return merged;
        } catch (Throwable t) {
            if (!CCMF_LOGGED_FAILURE) {
                CCMF_LOGGED_FAILURE = true;
                CheeseCompatMod.LOGGER.warn("[CheeseCompat][BTAspect] enrich failed (one-time): {}", t.toString(), t);
            }
            return originalAspects;
        }
    }

    @Inject(method = "renderPokemonInSlot", at = @At("RETURN"))
    private void cheesecompat$clearEntry(DrawContext gui, BattleTowerNetwork.PartyEntry entry,
                                          int x, int y, int slotPixelSize, int slotIdx, float partialTicks,
                                          CallbackInfo ci) {
        CCMF_CURRENT_ENTRY.remove();
    }
}

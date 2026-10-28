package com.cheesecompat.mixin;

import com.cheesecompat.CheeseCompatMod;
import dev.corgitaco.dataanchor.data.registry.TrackedDataKey;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

/**
 * Fallback générique : pour toute dimension <code>cheeseguard:*</code> qui n'a
 * pas d'entrée dédiée dans le registre <code>enhancedcelestials:lunar/dimension_settings</code>,
 * réutilise les settings de <code>cheeseguard:arkensia</code> à la volée.
 * <p>
 * Sans ce hook, les dims CheeseGuard créées dynamiquement (bases, terres_sauvages,
 * conseil4, etc.) reçoivent {@code Optional.empty()} du registre, la factory EC
 * retourne null, et le bloc Lunar Forecast Hologram affiche du vide. Avec ce hook,
 * elles héritent automatiquement du même cycle d'évènements lunaires qu'arkensia
 * — ce qui correspond à ce que veut l'admin (toutes les dims overworld-like de
 * CheeseGuard suivent le même rythme de phénomènes cosmiques).
 * <p>
 * On hook par {@code @Redirect} sur l'unique appel à {@code Registry.getEntry()}
 * dans {@code factory()}. Le mixin est inerte si EnhancedCelestials n'est pas
 * chargé (cf. {@code CheeseCompatMixinPlugin.shouldApplyMixin}).
 */
@Mixin(targets = "dev.corgitaco.enhancedcelestials.lunarevent.EnhancedCelestialsLunarForecastWorldData", remap = false)
public class EnhancedCelestialsFactoryFallbackMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Redirect(
        method = "factory",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/registry/Registry;getEntry(Lnet/minecraft/registry/RegistryKey;)Ljava/util/Optional;",
            remap = true
        )
    )
    private static Optional cheesecompat$fallbackToArkensiaSettings(
            Registry registry,
            RegistryKey requestedKey,
            // Arguments capturés de factory(TrackedDataKey, World) — les types doivent
            // matcher exactement, sinon Mixin runtime rejette l'injection.
            TrackedDataKey trackedDataKey,
            World level) {

        Optional result = registry.getEntry(requestedKey);
        if (result.isPresent()) return result;

        Identifier dimId = level.getRegistryKey().getValue();
        if (!"cheeseguard".equals(dimId.getNamespace())) return result;
        if ("arkensia".equals(dimId.getPath())) return result; // déjà OK, n'aurait pas dû être empty

        // Fallback : essayer cheeseguard:arkensia avec la même registry-ref que la clé demandée.
        try {
            RegistryKey<?> arkensiaKey = RegistryKey.of(
                requestedKey.getRegistryRef(),
                Identifier.of("cheeseguard", "arkensia")
            );
            Optional fallback = registry.getEntry((RegistryKey) arkensiaKey);
            if (fallback.isPresent()) {
                CheeseCompatMod.LOGGER.info(
                    "[CheeseCompat] Lunar fallback: {} → reusing cheeseguard:arkensia settings",
                    dimId
                );
            }
            return fallback;
        } catch (Throwable t) {
            CheeseCompatMod.LOGGER.warn(
                "[CheeseCompat] Lunar fallback for {} failed: {}",
                dimId, t.getMessage()
            );
            return result;
        }
    }
}

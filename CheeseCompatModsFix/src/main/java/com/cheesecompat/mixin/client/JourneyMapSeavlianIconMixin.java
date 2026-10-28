package com.cheesecompat.mixin.client;

import journeymap.client.model.entity.EntityHelper;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mixin: rewrite the icon resource path for Cobblemon entities whose texture
 * lives in the {@code seavlbizarremon} namespace (the Seavl's Bizarremons pack
 * fakemons such as Vaporipotent, Clockage, Dragash, etc.).
 *
 * <h3>The bug</h3>
 *
 * <p>JourneyMap's {@code EntityHelper.getIconData(Entity)} resolves a Pokémon
 * entity's icon by reading {@code RenderFacade.getEntityTexture(...)} — which
 * for Cobblemon returns the species variation texture, e.g.
 * {@code seavlbizarremon:textures/pokemon/vaporipotent/vaporipotent.png}.</p>
 *
 * <p>JourneyMap then attempts a path transformation, but the transformation is
 * <b>hard-coded to the {@code cobblemon} namespace only</b> (decompiled
 * bytecode of {@code EntityHelper}, offsets 613-649 — the
 * {@code .equals("cobblemon")} guard before the
 * {@code .replace("pokemon", "entity_icon")} call). For non-cobblemon
 * namespaces the texture path is used verbatim as the icon — which means JM
 * tries to render the high-res 3D model texture as a tiny minimap icon (looks
 * wrong / blurry / "bugged").</p>
 *
 * <h3>The fix</h3>
 *
 * <p>{@code @Inject} at {@code @At("RETURN")} on {@code getIconData}. Read the
 * returned {@code IconData.icon}; if its namespace is {@code seavlbizarremon}
 * (or any other configured Cobblemon-fakemon namespace) AND the path contains
 * {@code /pokemon/}, rewrite the icon to
 * {@code <namespace>:textures/entity_icon/<rest>.png}. Companion icons must be
 * provided at that location in the dCIM resourcepack.</p>
 *
 * <h3>Why post-RETURN and not pre/inline?</h3>
 *
 * <p>{@code getIconData} also sets {@code IconData.type} and {@code .texture}
 * and runs other logic we want to keep. A simple post-process at RETURN keeps
 * the upstream method intact and is robust against JM internal refactors.</p>
 *
 * <h3>Adding more fakemon namespaces</h3>
 *
 * <p>Add to {@link #FAKEMON_NAMESPACES}. The icons must be present at
 * {@code assets/<namespace>/textures/entity_icon/...}.</p>
 */
@Mixin(value = EntityHelper.class, remap = false)
public abstract class JourneyMapSeavlianIconMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix/JMSeavlIcon");

    /** Cobblemon-fakemon resource namespaces whose texture path lives outside
     * {@code cobblemon:}. JourneyMap's hard-coded namespace check ignores them. */
    private static final String[] FAKEMON_NAMESPACES = { "seavlbizarremon" };

    private static final ConcurrentHashMap<Identifier, Identifier> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong REWRITTEN = new AtomicLong(0);
    private static final java.util.Set<String> LOGGED_FIRSTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "getIconData", at = @At("RETURN"), require = 1)
    private static void cheesecompat$rewriteSeavlIcon(Entity entity, CallbackInfoReturnable<EntityHelper.IconData> cir) {
        try {
            EntityHelper.IconData data = cir.getReturnValue();
            if (data == null) return;
            IconDataAccessor acc = (IconDataAccessor) (Object) data;
            Identifier icon = acc.cheesecompat$getIcon();
            if (icon == null) return;
            String ns = icon.getNamespace();
            boolean isFakemonNs = false;
            for (String n : FAKEMON_NAMESPACES) {
                if (n.equals(ns)) { isFakemonNs = true; break; }
            }
            if (!isFakemonNs) return;
            String path = icon.getPath();
            if (!path.contains("/pokemon/")) return;

            Identifier rewritten = CACHE.get(icon);
            if (rewritten == null) {
                String newPath = path.replace("/pokemon/", "/entity_icon/");
                rewritten = Identifier.of(ns, newPath);
                CACHE.put(icon, rewritten);
            }
            acc.cheesecompat$setIcon(rewritten);

            long n = REWRITTEN.incrementAndGet();
            String key = ns + ":" + path;
            if (LOGGED_FIRSTS.add(key)) {
                LOGGER.info("[CCMF/JMSeavlIcon] Rewrote icon: {} -> {} (count since startup: {})",
                        icon, rewritten, n);
            }
        } catch (Throwable t) {
            // Don't let icon-fix errors break the minimap
            LOGGER.warn("[CCMF/JMSeavlIcon] Rewrite threw (silently dropping): {}", t.toString());
        }
    }
}

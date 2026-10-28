package com.cheesecompat;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Adds custom mega aspects (mega_z, etc.) to MegaShowdown's FormChangeHelper.mega_aspects set.
 *
 * <p>MegaShowdown hardcodes mega_aspects = HashSet(Set.of("mega","mega_x","mega_y")) in its
 * static initializer of {@code com.cobblemon.yajatkaul.mega_showdown.formChangeLogic.FormChangeHelper}.
 * Without "mega_z", the hasMega() cap-check (one mega per player per battle) bypasses
 * mega-Z evolutions, and post-battle Devolve() never triggers — so a Pokémon that mega-evolved
 * into a Mega-Z form stays in that form forever.</p>
 *
 * <p>This patch reflectively appends "mega_z" to the live HashSet at mod init. The set is
 * a regular HashSet (decompiled bytecode confirms), so mutation is safe. If the field
 * is absent (mod missing or upstream renamed it), we log and bail without crashing.</p>
 */
public final class MegaShowdownAspectPatch {

    private static final String CLASS_NAME = "com.cobblemon.yajatkaul.mega_showdown.formChangeLogic.FormChangeHelper";
    private static final String FIELD_NAME = "mega_aspects";
    private static final String[] EXTRA_ASPECTS = { "mega_z" };

    public static void apply() {
        if (!FabricLoader.getInstance().isModLoaded("mega_showdown")) {
            return;
        }
        try {
            Class<?> cls = Class.forName(CLASS_NAME);
            Field field = cls.getDeclaredField(FIELD_NAME);
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<String> set = (Set<String>) field.get(null);
            if (set == null) {
                CheeseCompatMod.LOGGER.warn("[CheeseCompat][MegaPatch] {}.{} is null — bailing", CLASS_NAME, FIELD_NAME);
                return;
            }
            int added = 0;
            for (String aspect : EXTRA_ASPECTS) {
                if (set.add(aspect)) added++;
            }
            CheeseCompatMod.LOGGER.info("[CheeseCompat][MegaPatch] Added {} aspect(s) to mega_aspects (now {} entries: {})",
                    added, set.size(), set);
        } catch (ClassNotFoundException e) {
            CheeseCompatMod.LOGGER.info("[CheeseCompat][MegaPatch] {} not found — mod_showdown layout changed, skipping", CLASS_NAME);
        } catch (NoSuchFieldException e) {
            CheeseCompatMod.LOGGER.warn("[CheeseCompat][MegaPatch] field {} not found in {} — upstream renamed?", FIELD_NAME, CLASS_NAME);
        } catch (UnsupportedOperationException e) {
            CheeseCompatMod.LOGGER.warn("[CheeseCompat][MegaPatch] mega_aspects is immutable in this version — cannot patch: {}", e.toString());
        } catch (Throwable t) {
            CheeseCompatMod.LOGGER.warn("[CheeseCompat][MegaPatch] failed to extend mega_aspects: {}", t.toString(), t);
        }
    }

    private MegaShowdownAspectPatch() {}
}

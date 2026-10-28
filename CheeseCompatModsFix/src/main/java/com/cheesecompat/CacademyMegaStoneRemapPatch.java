package com.cheesecompat;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Bridges CacademyMons custom Mega Stones to MegaShowdown's Showdown sim.
 *
 * The CacademyMons datapack ships Mega Stones as plain {@code minecraft:brick}
 * items (and a handful of other vanilla items) distinguished by a unique
 * {@code custom_model_data} value. Cobblemon's {@code CobblemonHeldItemManager}
 * builds the held-item id sent to the Showdown sim from the item's registry
 * path ({@code "brick"}) and CANNOT resolve that to a Showdown stone id like
 * {@code "lucarionitez"} — so {@code pokemon.heldItem} arrives empty in the sim,
 * {@code canMegaEvo()} returns null, and the Mega button is silently disabled.
 *
 * This patch registers a {@code stackRemap} function via Cobblemon's official
 * public API ({@code CobblemonHeldItemManager.registerStackRemap}) that maps
 * the (item registry id, custom_model_data) tuple to the correct Showdown
 * stone id. Cobblemon will consult our function for every held-item lookup
 * during battle setup, and our return value primes the sim so that the
 * existing Showdown items.js / mega_showdown held_items.js entries (which
 * declare {@code megaStone}/{@code megaEvolves}) take effect.
 *
 * Scope: only the 42 CacademyMons stones with a unique {@code (item_id, cmd)}.
 * Items where {@code cmd == 0} on {@code minecraft:brick} collide with each
 * other and are skipped (they were placeholder entries in the datapack).
 */
public final class CacademyMegaStoneRemapPatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheesecompat");

    /** Key = "<item_registry_id>#<custom_model_data>", value = Showdown stone id. */
    private static final Map<String, String> REMAP = new HashMap<>();

    /**
     * Base items pouvant porter une de nos stones — early-return pour tout le
     * reste afin d'éviter la réflexion + le log sur 99% des lookups held-item.
     * Inclut les items vanilla partagés par les stones Z-A Server-Side et
     * AllTheMons (coal, redstone, diamond, dyes, …). Un item ordinaire de ce
     * type (cmd 0/absent) ne matchera aucune entrée REMAP → null → vanilla.
     */
    private static final Set<String> RELEVANT_ITEMS = Set.of(
            "minecraft:brick", "minecraft:netherite_hoe", "minecraft:trial_key",
            "minecraft:nether_star", "minecraft:prismarine_shard", "minecraft:dragon_breath",
            "minecraft:redstone", "minecraft:brown_dye", "minecraft:blaze_rod",
            "minecraft:coal", "minecraft:arrow", "minecraft:emerald",
            "minecraft:yellow_dye", "minecraft:black_dye", "minecraft:diamond",
            "minecraft:charcoal", "minecraft:red_dye", "minecraft:purple_dye");

    static {
        put("minecraft:brick", 261001, "absolitez");
        put("minecraft:netherite_hoe", 260660, "alterianite");
        put("minecraft:brick", 261002, "barbaracite");
        put("minecraft:brick", 261003, "baxcalibrite");
        put("minecraft:trial_key", 260787, "beedrillinite");
        put("minecraft:brick", 261004, "chandelurite");
        put("minecraft:brick", 261005, "chimechoite");
        put("minecraft:brick", 261006, "clefablite");
        put("minecraft:brick", 261007, "crabominite");
        put("minecraft:brick", 261008, "darkranite");
        put("minecraft:nether_star", 260657, "diancinite");
        put("minecraft:brick", 261009, "dragalgite");
        put("minecraft:brick", 261010, "drampanite");
        put("minecraft:brick", 261011, "eelektrossite");
        put("minecraft:brick", 261012, "emboarite");
        put("minecraft:brick", 261013, "excadrite");
        put("minecraft:brick", 261014, "falinksite");
        put("minecraft:brick", 261015, "feraligite");
        put("minecraft:brick", 261016, "floettite");
        put("minecraft:brick", 261017, "froslassite");
        put("minecraft:brick", 261018, "garchompitez");
        put("minecraft:brick", 261019, "glimmoranite");
        put("minecraft:brick", 261020, "golisopite");
        put("minecraft:brick", 261021, "golurkite");
        put("minecraft:brick", 261022, "heatranite");
        put("minecraft:brick", 261023, "lucarionitez");
        put("minecraft:brick", 261024, "magearnite");
        put("minecraft:brick", 261025, "meganiumite");
        put("minecraft:brick", 261026, "meowsticite");
        put("minecraft:brick", 261027, "pyroarite");
        put("minecraft:prismarine_shard", 260658, "sableyenite");
        put("minecraft:brick", 261028, "scolipite");
        put("minecraft:brick", 261029, "scovillainite");
        put("minecraft:brick", 261030, "scraftinite");
        put("minecraft:brick", 261031, "skarmorite");
        put("minecraft:dragon_breath", 260656, "slowkingnite");
        put("minecraft:brick", 261032, "staraptite");
        put("minecraft:brick", 261033, "starminite");
        put("minecraft:brick", 261034, "tatsugirinite");
        put("minecraft:brick", 261035, "zeraorite");
        put("minecraft:brick", 261036, "zygardite");

        // --- Dé-collision des 15 stones CacademyMons qui étaient sur brick#0 ---
        // (collision : cmd==0 → patch skip + itemValidator court-circuit). On leur
        // attribue un custom_model_data unique (261037-261051) ; le datapack
        // CacademyMons (mega def + recette) est mis à jour en parallèle pour
        // produire brick#<ce cmd>. Sans ça, leur méga était cassée en combat ET
        // hors combat.
        put("minecraft:brick", 261037, "alloettite");
        put("minecraft:brick", 261038, "alloettitevex");
        put("minecraft:brick", 261039, "clockworkleavannite");
        put("minecraft:brick", 261040, "coppergolurkite");
        put("minecraft:brick", 261041, "darmanitanitez");
        put("minecraft:brick", 261042, "dudunsparcitex");
        put("minecraft:brick", 261043, "dudunsparcitey");
        put("minecraft:brick", 261044, "electrodite");
        put("minecraft:brick", 261045, "falinksitex");
        put("minecraft:brick", 261046, "falinksitey");
        put("minecraft:brick", 261047, "golisopedite");
        put("minecraft:brick", 261048, "klinklanite");
        put("minecraft:brick", 261049, "sandstonestonjournite");
        put("minecraft:brick", 261050, "starstellarite");
        put("minecraft:brick", 261051, "tanglarite");

        // --- Stones Z-A Megas Server-Side (items vanilla distincts, déjà recette+held_item) ---
        // Absentes du remap → bouton méga absent EN COMBAT. Hors combat marchait
        // déjà (MegaLogic lit item_id+cmd). On les câble pour le combat.
        put("minecraft:redstone", 260653, "chesnaughtite");
        put("minecraft:brown_dye", 260652, "delphoxite");
        put("minecraft:blaze_rod", 260630, "dragonitite");
        put("minecraft:coal", 260651, "greninjite");
        put("minecraft:arrow", 260644, "hawluchite");
        put("minecraft:emerald", 260650, "malamarite");
        put("minecraft:yellow_dye", 260654, "raichunitex");
        put("minecraft:black_dye", 260655, "raichunitey");
        put("minecraft:diamond", 260640, "victreebelite");
        put("minecraft:charcoal", 260660, "wobbuffetite");

        // --- Stones AllTheMons x Mega Showdown (dyes partagées) ---
        // slowbronite est un item dédié (mega_showdown:slowbronite) → déjà OK, non mappé.
        put("minecraft:red_dye", 260615, "shadowmewtwonitex");
        put("minecraft:purple_dye", 260616, "shadowmewtwonitey");
        put("minecraft:red_dye", 260621, "slowbrogonite");
        put("minecraft:purple_dye", 260619, "victinite");
    }

    private static void put(String itemId, int cmd, String showdownId) {
        REMAP.put(itemId + "#" + cmd, showdownId);
    }

    private CacademyMegaStoneRemapPatch() {}

    /** Resolve an {@link ItemStack} to a CacademyMons Showdown stone id, or null. */
    private static String resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null) return null;
        // Only investigate items that COULD be one of our stones — filters out
        // 99% of calls (e.g., a Charcoal-holding Pokémon) without log spam.
        String itemKey = itemId.toString();
        if (!RELEVANT_ITEMS.contains(itemKey)) return null;

        // Read custom_model_data via mapping-agnostic reflection: find the
        // record accessor that takes 0 args and returns int (excluding
        // hashCode). Works whether the class is `class_9280.comp_2382()`
        // (intermediary), `CustomModelDataComponent.value()` (yarn), or
        // anything else a remapper produces. Direct `.value()` call would
        // bind to a specific mapping name at compile and may NoSuchMethodError
        // depending on how the runtime jar was remapped.
        int cmd = 0;
        String cmdDebug = "no_component";
        try {
            Object c = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (c != null) {
                cmdDebug = "scan:" + c.getClass().getSimpleName();
                for (Method m : c.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 0
                            && m.getReturnType() == int.class
                            && !m.getName().equals("hashCode")) {
                        m.setAccessible(true);
                        Object v = m.invoke(c);
                        if (v instanceof Integer i) { cmd = i; cmdDebug = m.getName() + "=" + cmd; break; }
                    }
                }
            }
        } catch (Throwable t) {
            cmdDebug = "exc:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
        String key = itemKey + "#" + cmd;
        String hit = REMAP.get(key);
        // Ne logger que les résolutions POSITIVES : depuis l'élargissement du
        // filtre aux items vanilla courants (diamond, coal, dyes…), un INFO par
        // lookup spammerait les logs. Les misses passent en TRACE.
        if (hit != null) {
            LOGGER.info("[CheeseCompat][MegaStoneRemap] resolve item={} cmd={} key={} -> {}",
                    itemKey, cmd, key, hit);
        } else {
            LOGGER.trace("[CheeseCompat][MegaStoneRemap] miss item={} cmd_debug={} key={}",
                    itemKey, cmdDebug, key);
        }
        return hit;
    }

    /**
     * Install the stack remap into {@code CobblemonHeldItemManager} via reflection.
     * Returns true on success.
     */
    @SuppressWarnings("unchecked")
    public static boolean apply() {
        try {
            Class<?> mgrCls = Class.forName(
                    "com.cobblemon.mod.common.pokemon.helditem.CobblemonHeldItemManager");
            Object instance = mgrCls.getField("INSTANCE").get(null);
            // Find registerStackRemap(Function<ItemStack, String>)
            Method register = null;
            for (Method m : mgrCls.getDeclaredMethods()) {
                if (m.getName().equals("registerStackRemap") && m.getParameterCount() == 1) {
                    register = m;
                    break;
                }
            }
            if (register == null) {
                LOGGER.warn("[CheeseCompat][MegaStoneRemap] registerStackRemap not found on {}",
                        mgrCls.getName());
                return false;
            }
            register.setAccessible(true);
            Function<ItemStack, String> fn = CacademyMegaStoneRemapPatch::resolve;
            register.invoke(instance, fn);
            LOGGER.info("[CheeseCompat][MegaStoneRemap] Registered {} CacademyMons stone remaps " +
                    "(includes lucarionitez=261023, glimmoranite=261019, garchompitez=261018, absolitez=261001)",
                    REMAP.size());
            return true;
        } catch (Throwable t) {
            LOGGER.error("[CheeseCompat][MegaStoneRemap] apply failed: {}", t.toString(), t);
            return false;
        }
    }
}

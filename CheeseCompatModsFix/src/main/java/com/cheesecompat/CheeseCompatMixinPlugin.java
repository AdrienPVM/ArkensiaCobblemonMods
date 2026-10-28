package com.cheesecompat;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Conditionally apply mixins based on whether target mods are loaded.
 */
public class CheeseCompatMixinPlugin implements IMixinConfigPlugin {

    private static final boolean BT_LOADED =
        FabricLoader.getInstance().isModLoaded("cobblemon_battle_tower");
    private static final boolean LM_LOADED =
        FabricLoader.getInstance().isModLoaded("legendarymonuments");
    private static final boolean MINERALLY_LOADED =
        FabricLoader.getInstance().isModLoaded("minerally");
    private static final boolean COBBLESAFARI_LOADED =
        FabricLoader.getInstance().isModLoaded("cobblesafari");
    private static final boolean CTE_LOADED =
        FabricLoader.getInstance().isModLoaded("cobblemontrialsedition");
    private static final boolean RAIDDENS_LOADED =
        FabricLoader.getInstance().isModLoaded("cobblemonraiddens");
    private static final boolean DAYCAREPLUS_LOADED =
        FabricLoader.getInstance().isModLoaded("daycareplus");
    private static final boolean ACADEMY_LOADED =
        FabricLoader.getInstance().isModLoaded("academy");
    private static final boolean COBBLEWARDEN_LOADED =
        FabricLoader.getInstance().isModLoaded("cobblewarden_boss");
    private static final boolean ENHANCEDCELESTIALS_LOADED =
        FabricLoader.getInstance().isModLoaded("enhancedcelestials");
    private static final boolean FIGHTORFLIGHT_LOADED =
        FabricLoader.getInstance().isModLoaded("fightorflight");
    private static final boolean EXTRAQUESTS_LOADED =
        FabricLoader.getInstance().isModLoaded("extraquests");
    private static final boolean JOURNEYMAP_LOADED =
        FabricLoader.getInstance().isModLoaded("journeymap");
    private static final boolean NUMISMATIC_LOADED =
        FabricLoader.getInstance().isModLoaded("numismatic-overhaul");
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode targetClass, String mixinClass, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode targetClass, String mixinClass, IMixinInfo info) {}

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Battle Tower mixins
        if (mixinClassName.contains("BattleTower") || mixinClassName.contains("TeamSnapshot")
                || mixinClassName.contains("NetworkHelper")) {
            return BT_LOADED;
        }
        // Legendary Monuments mixins (Arc Phone + Sanctuary + entrepreneur profession lock)
        if (mixinClassName.contains("ArcPhone") || mixinClassName.contains("Sanctuary")
                || mixinClassName.contains("EntrepreneurProfessionLock")) {
            return LM_LOADED;
        }
        // Minerally mixin (OrePile crash fix)
        if (mixinClassName.contains("OrePile")) {
            return MINERALLY_LOADED;
        }
        // CobbleSafari mixins (initLogic patch + teleporter + dungeon portal spawning + dungeon dimensions)
        if (mixinClassName.contains("CobbleSafari") || mixinClassName.contains("SafariTeleporter")
                || mixinClassName.contains("DungeonPortalSpawn") || mixinClassName.contains("DungeonDimensions")) {
            return COBBLESAFARI_LOADED;
        }
        // CobblemonTrialsEdition mixin (Natures static method bridge for Cobblemon 1.6.x)
        if (mixinClassName.contains("TrialsNature")) {
            return CTE_LOADED;
        }
        // CobblemonRaidDens mixins (block Tera in raids + boss facing + fire spread)
        if (mixinClassName.contains("RaidTera") || mixinClassName.contains("RaidBoss")
                || mixinClassName.contains("RaidFire") || mixinClassName.contains("RaidEntity")) {
            return RAIDDENS_LOADED;
        }
        // Battle Tower full team restore (backup + restore from snapshot)
        if (mixinClassName.contains("TeamFullRestore")) {
            return BT_LOADED;
        }
        // Battle Tower arena position fix (teleportation offsets)
        if (mixinClassName.contains("BattleArenaPosition")) {
            return BT_LOADED;
        }
        // DaycarePlus mixins (vanilla IV inheritance, simple-mode toggle, GUI, fertility clamp)
        if (mixinClassName.contains("Daycare") || mixinClassName.contains("Fertility")) {
            return DAYCAREPLUS_LOADED;
        }
        // Academy slingshot protection — skip discard() on uncatchable bosses
        if (mixinClassName.contains("AcademySlingshot")) {
            return ACADEMY_LOADED;
        }
        // Cobblewarden boss — intercept sculk shriek directly for instant boss spawn
        if (mixinClassName.contains("SculkShriekerBossSpawn")) {
            return COBBLEWARDEN_LOADED;
        }
        // EnhancedCelestials lunar fallback — make every cheeseguard:* dim inherit
        // arkensia's lunar settings if it has none of its own.
        if (mixinClassName.contains("EnhancedCelestialsFactoryFallback")) {
            return ENHANCEDCELESTIALS_LOADED;
        }
        // FoF owned-Giratina aggro filter — only if fightorflight is loaded
        if (mixinClassName.contains("FoFOwnedGiratinaNoAggro")) {
            return FIGHTORFLIGHT_LOADED;
        }
        // Battle Tower chunk preload (fix invisible NPC pokemon)
        if (mixinClassName.contains("BattleTowerChunkPreload")) {
            return BT_LOADED;
        }
        // ExtraQuests timer reset guard (daily reset race condition)
        if (mixinClassName.contains("ExtraQuestsTimerResetGuard")) {
            return EXTRAQUESTS_LOADED;
        }
        // JourneyMap Seavlian fakemon icon fix (client-only)
        if (mixinClassName.contains("JourneyMapSeavlianIcon")
                || mixinClassName.contains("IconDataAccessor")) {
            return JOURNEYMAP_LOADED;
        }
        // numismatic-overhaul shop FPS distance cull (client-only)
        if (mixinClassName.contains("NumismaticShopCull")) {
            return NUMISMATIC_LOADED;
        }
        return true;
    }
}

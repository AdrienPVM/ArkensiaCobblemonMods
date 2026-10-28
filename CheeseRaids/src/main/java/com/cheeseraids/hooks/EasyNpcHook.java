package com.cheeseraids.hooks;

import com.cheeseraids.CheeseRaidsMod;
import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.util.Mc;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Reliable EasyNPC reveal. EasyNPC entities OVERRIDE vanilla {@code isInvisible()} to read their own
 * {@code VISIBLE} display attribute, so {@code setInvisible(...)} is ignored. The real lever is
 * {@code setDisplayAttribute(VISIBLE, BOOLEAN, true/false)} (writes auto-syncing synched data). We
 * scan for the EasyNPC near the herald position and toggle that attribute (plus a glow outline),
 * logging the count so a placement mistake is obvious instead of silent.
 */
public final class EasyNpcHook {
    private EasyNpcHook() {}

    private static volatile boolean init = false;
    private static Object VISIBLE_ATTR, BOOLEAN_VAL;
    private static Class<?> attrType, valType;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized void initApi() {
        if (init) return;
        try {
            attrType = Class.forName("de.markusbordihn.easynpc.data.display.DisplayAttributeType");
            valType = Class.forName("de.markusbordihn.easynpc.data.type.ValueType");
            VISIBLE_ATTR = Enum.valueOf((Class) attrType, "VISIBLE");
            BOOLEAN_VAL = Enum.valueOf((Class) valType, "BOOLEAN");
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] EasyNPC visibility API not found: {}", t.getMessage());
        }
        init = true;
    }

    /** Make every EasyNPC within ~10 blocks of the herald position visible/invisible. Returns the count. */
    public static int setVisible(MinecraftServer server, RaidDefinition def, boolean visible) {
        ServerWorld w = Mc.world(server, def.npc.pos.world);
        if (w == null) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] herald world '{}' not loaded — cannot toggle herald", def.npc.pos.world);
            return 0;
        }
        initApi();
        BlockPos p = def.npc.pos.blockPos();
        Box box = new Box(p).expand(10.0);
        List<Entity> npcs = w.getOtherEntities(null, box,
            e -> Mc.typeId(e).startsWith("easy_npc") && e.isAlive());
        int toggled = 0;
        for (Entity e : npcs) {
            if (setDisplayVisible(e, visible)) toggled++;
            try { e.setGlowing(visible); } catch (Throwable ignored) {}
        }
        if (visible && npcs.isEmpty()) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] HERALD: no EasyNPC found within 10 blocks of {} in {} — "
                + "place the Ho-Oh NPC there (type easy_npc:*).", p.toShortString(), def.npc.pos.world);
        } else {
            CheeseRaidsMod.LOGGER.info("[CheeseRaids] herald setVisible({}) — {} EasyNPC(s) near {}, {} toggled via display-attr",
                visible, npcs.size(), p.toShortString(), toggled);
        }
        return npcs.size();
    }

    /** Human-readable diagnostic: where the herald is expected, and every nearby/loaded EasyNPC. */
    public static String diagnose(MinecraftServer server, RaidDefinition def) {
        ServerWorld w = Mc.world(server, def.npc.pos.world);
        if (w == null) return "Monde '" + def.npc.pos.world + "' non chargé.";
        BlockPos p = def.npc.pos.blockPos();
        StringBuilder sb = new StringBuilder();
        sb.append("Héraut attendu: ").append(p.toShortString()).append(" dans ").append(def.npc.pos.world);
        List<Entity> near = w.getOtherEntities(null, new Box(p).expand(10.0), e -> Mc.typeId(e).startsWith("easy_npc"));
        sb.append("\nEasyNPC dans 10 blocs: ").append(near.size());
        for (Entity e : near) {
            double d = Math.sqrt(e.squaredDistanceTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
            sb.append("\n  • ").append(Mc.typeId(e)).append(" @ ").append(e.getBlockPos().toShortString())
              .append(String.format(" (dist %.1f, invisible=%b)", d, e.isInvisible()));
        }
        List<Entity> wide = w.getOtherEntities(null, new Box(p).expand(128.0), e -> Mc.typeId(e).startsWith("easy_npc"));
        if (wide.size() > near.size()) {
            sb.append("\nAutres EasyNPC dans 128 blocs (mauvaise position ?):");
            for (Entity e : wide) if (!near.contains(e))
                sb.append("\n  - ").append(Mc.typeId(e)).append(" @ ").append(e.getBlockPos().toShortString());
        }
        if (wide.isEmpty()) sb.append("\n⚠ AUCUN EasyNPC chargé à proximité — chunk non chargé ou NPC ailleurs.");
        return sb.toString();
    }

    /**
     * Reflectively set the EasyNPC's VISIBLE display attribute (the value vanilla isInvisible() reads).
     * Resolved by PARAMETER TYPE — {@code (DisplayAttributeType, ValueType, Object)} — not by method name,
     * per the project's anti-Yarn-name reflection rule, so an EasyNPC rename can't silently break the reveal.
     */
    private static boolean setDisplayVisible(Entity npc, boolean visible) {
        if (attrType == null || valType == null) return false;
        try {
            for (Method m : npc.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0] == attrType && p[1] == valType
                        && p[2].isAssignableFrom(Boolean.class)) {
                    m.invoke(npc, VISIBLE_ATTR, BOOLEAN_VAL, visible);
                    return true;
                }
            }
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] setDisplayAttribute(VISIBLE,...) not found on {} — "
                + "EasyNPC display API may have changed", Mc.typeId(npc));
            return false;
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] setDisplayAttribute(VISIBLE) failed on {}: {}",
                Mc.typeId(npc), t.getMessage());
            return false;
        }
    }
}

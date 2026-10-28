package com.cheeseraids.logic;

import com.cheeseraids.CheeseRaidsMod;
import com.cheeseraids.data.PlayerRaidState;
import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.data.RaidInstance;
import com.cheeseraids.hooks.CobbleSafariHook;
import com.cheeseraids.util.Mc;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Victory rewards: opens {@code floor(participants / divisor)} (min N) Hoopa Ring portals via
 * CobbleSafari, launches celebratory fireworks, grants fromage (CheeseStore wallet, reflection) and
 * per-winner loot commands.
 */
public class RewardManager {

    // Hoopa theme: gold + purple + magenta.
    private static final IntList HOOPA_COLORS = IntList.of(0xF5A623, 0x9400D3, 0xD300FF);
    private static final IntList HOOPA_FADE = IntList.of(0xFFD700, 0xFF55FF);

    public int portalCount(RaidDefinition def, int participants) {
        int n = participants / Math.max(1, def.reward.portalDivisor);
        return Math.max(def.reward.minPortals, n);
    }

    public void grantRewards(MinecraftServer server, RaidDefinition def, RaidInstance inst) {
        int participants = inst.participantCount();
        int portals = portalCount(def, participants);
        ServerWorld anchorWorld = Mc.world(server, def.reward.portalAnchor.world);
        BlockPos anchor = def.reward.portalAnchor.blockPos();

        // an online participant to own/anchor the CobbleSafari portal
        ServerPlayerEntity owner = null;
        for (PlayerRaidState ps : inst.players.values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(ps.uuid));
            if (p != null) { owner = p; break; }
        }

        // 1) Hoopa Ring portals (CobbleSafari), spaced around the anchor + a firework each.
        // CobbleSafari's spawnPortal uses the owner for announce/log context and may NPE on a null owner,
        // so require an online participant; otherwise fall through to the command fallback below.
        int opened = 0;
        if (anchorWorld != null && owner != null) {
            for (int i = 0; i < portals; i++) {
                double ang = (2 * Math.PI / Math.max(1, portals)) * i;
                BlockPos pos = anchor.add(
                    (int) Math.round(Math.cos(ang) * def.reward.portalSpacing), 0,
                    (int) Math.round(Math.sin(ang) * def.reward.portalSpacing));
                if (CobbleSafariHook.spawnPortal(anchorWorld, pos, owner)) opened++;
                fireworks(anchorWorld, pos, 2);
            }
        }
        // optional command fallback if no portal opened and a template is configured
        if (opened == 0 && def.reward.portalCommand != null && !def.reward.portalCommand.isBlank()) {
            for (int i = 0; i < portals; i++) {
                Map<String, String> v = new HashMap<>();
                v.put("dim", def.reward.portalAnchor.world);
                v.put("x", fmt(anchor.getX() + i * def.reward.portalSpacing));
                v.put("y", fmt(anchor.getY())); v.put("z", fmt(anchor.getZ()));
                v.put("index", String.valueOf(i));
                Mc.run(server, Mc.subst(def.reward.portalCommand, v));
            }
        }
        if (opened == 0 && (def.reward.portalCommand == null || def.reward.portalCommand.isBlank()))
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] No Hoopa portal opened ({} earned) — CobbleSafari absent, or it requires the overworld (anchor is in {}).",
                portals, def.reward.portalAnchor.world);

        // 2) Fireworks over each winner + loot.
        for (PlayerRaidState ps : inst.players.values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(ps.uuid));
            if (p == null) continue;
            ServerWorld pw = p.getServerWorld();
            fireworks(pw, p.getBlockPos(), 3);
            Mc.sound(pw, "minecraft:entity.firework_rocket.launch", p.getBlockPos(), 1.5f, 1.0f);
            if (def.reward.fromage > 0) addFromage(p.getUuid(), p.getName().getString(), def.reward.fromage);
            for (String cmd : def.reward.commands) {
                Map<String, String> v = new HashMap<>();
                v.put("player", p.getName().getString());
                Mc.run(server, Mc.subst(cmd, v));
            }
        }
        CheeseRaidsMod.LOGGER.info("[CheeseRaids] Rewards for {}: {}/{} portal(s) opened, {} winner(s)",
            def.id, opened, portals, participants);
    }

    /** Test helper (command): open the minimum Hoopa portals + fireworks at the anchor, owned by the caller. */
    public void testGrant(MinecraftServer server, RaidDefinition def, ServerPlayerEntity owner) {
        ServerWorld anchorWorld = Mc.world(server, def.reward.portalAnchor.world);
        BlockPos anchor = def.reward.portalAnchor.blockPos();
        int portals = Math.max(1, def.reward.minPortals);
        int opened = 0;
        if (anchorWorld != null) {
            for (int i = 0; i < portals; i++) {
                double ang = (2 * Math.PI / portals) * i;
                BlockPos pos = anchor.add(
                    (int) Math.round(Math.cos(ang) * def.reward.portalSpacing), 0,
                    (int) Math.round(Math.sin(ang) * def.reward.portalSpacing));
                if (CobbleSafariHook.spawnPortal(anchorWorld, pos, owner)) opened++;
                fireworks(anchorWorld, pos, 3);
            }
        } else {
            owner.sendMessage(net.minecraft.text.Text.literal("§cMonde d'ancrage introuvable: " + def.reward.portalAnchor.world), false);
            return;
        }
        owner.sendMessage(net.minecraft.text.Text.literal("§dTest récompense: " + opened + "/" + portals
            + " portail(s) Hoopa à " + anchor.toShortString() + " (" + def.reward.portalAnchor.world + ")"), false);
    }

    /** Launch {@code count} Hoopa-themed fireworks at a position. */
    private void fireworks(ServerWorld w, BlockPos pos, int count) {
        if (w == null) return;
        FireworkExplosionComponent.Type[] shapes = FireworkExplosionComponent.Type.values();
        for (int i = 0; i < count; i++) {
            try {
                FireworkExplosionComponent.Type shape = shapes[w.random.nextInt(shapes.length)];
                FireworkExplosionComponent explosion = new FireworkExplosionComponent(
                    shape, HOOPA_COLORS, HOOPA_FADE, true, true);
                ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
                rocket.set(DataComponentTypes.FIREWORKS, new FireworksComponent(1, List.of(explosion)));
                FireworkRocketEntity fw = new FireworkRocketEntity(w,
                    pos.getX() + 0.5 + (w.random.nextDouble() - 0.5) * 2,
                    pos.getY() + 1.0,
                    pos.getZ() + 0.5 + (w.random.nextDouble() - 0.5) * 2, rocket);
                w.spawnEntity(fw);
            } catch (Throwable t) {
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] firework spawn failed: {}", t.getMessage());
                return;
            }
        }
    }

    /** Add fromage via CheeseStore WalletManager (reflection; graceful no-op if absent). */
    private void addFromage(UUID uuid, String name, long amount) {
        try {
            Class<?> wm = Class.forName("com.cheesestore.WalletManager");
            Object inst = wm.getMethod("getInstance").invoke(null);
            if (inst == null) return;
            wm.getMethod("add", UUID.class, String.class, long.class).invoke(inst, uuid, name, amount);
        } catch (ClassNotFoundException e) {
            // CheeseStore not installed — skip
        } catch (Exception e) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] fromage reward failed: {}", e.getMessage());
        }
    }

    private static String fmt(double d) { return String.valueOf(Math.round(d * 100.0) / 100.0); }
}

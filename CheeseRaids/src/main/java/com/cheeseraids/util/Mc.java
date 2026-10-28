package com.cheeseraids.util;

import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.util.List;

/** Small MC helpers shared across CheeseRaids (block/particle/sound/world/command resolution). */
public final class Mc {
    private Mc() {}

    /**
     * Run a command as a level-4 console source, but route any command feedback/error to our log
     * instead of swallowing it (the old {@code withSilent()} hid Brigadier errors like a refused
     * pokespawn). Returns true on a non-zero command result.
     */
    public static boolean run(MinecraftServer server, String command) {
        if (command == null || command.isBlank()) return false;
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        final StringBuilder feedback = new StringBuilder();
        CommandOutput out = new CommandOutput() {
            @Override public void sendMessage(Text message) {
                if (feedback.length() > 0) feedback.append(" | ");
                feedback.append(message.getString());
            }
            @Override public boolean shouldReceiveFeedback() { return true; }
            @Override public boolean shouldTrackOutput() { return true; }
            @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        };
        ServerCommandSource src = new ServerCommandSource(out, Vec3d.ZERO, Vec2f.ZERO,
            server.getOverworld(), 4, "CheeseRaids", Text.literal("CheeseRaids"), server, null);
        try {
            // Use the dispatcher directly so a CommandSyntaxException (e.g. a refused spawn) propagates
            // here to be logged, instead of being swallowed the way executeWithPrefix + silent does.
            int result = server.getCommandManager().getDispatcher().execute(cmd, src);
            return result > 0;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            com.cheeseraids.CheeseRaidsMod.LOGGER.warn("[CheeseRaids] command '{}' failed: {}{}", cmd, e.getMessage(),
                feedback.length() > 0 ? " (" + feedback + ")" : "");
            return false;
        } catch (Exception e) {
            com.cheeseraids.CheeseRaidsMod.LOGGER.warn("[CheeseRaids] command '{}' error: {}", cmd, e.getMessage());
            return false;
        }
    }

    public static String subst(String tpl, java.util.Map<String, String> vars) {
        if (tpl == null) return "";
        String s = tpl;
        for (var e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s.replaceAll("\\s+", " ").trim();
    }

    public static ServerWorld world(MinecraftServer server, String fullId) {
        try {
            Identifier id = Identifier.of(fullId);
            return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
        } catch (Exception e) {
            return null;
        }
    }

    public static RegistryKey<World> worldKey(String fullId) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(fullId));
    }

    public static BlockState block(String id) {
        try {
            Block b = Registries.BLOCK.get(Identifier.of(id));
            return b != null ? b.getDefaultState() : Blocks.PURPUR_BLOCK.getDefaultState();
        } catch (Exception e) {
            return Blocks.PURPUR_BLOCK.getDefaultState();
        }
    }

    public static ParticleEffect particle(String name) {
        if (name == null) return ParticleTypes.ENCHANT;
        return switch (name.toUpperCase()) {
            case "PORTAL" -> ParticleTypes.PORTAL;
            case "FLAME" -> ParticleTypes.FLAME;
            case "SOUL" -> ParticleTypes.SOUL;
            case "SOUL_FIRE", "SOUL_FIRE_FLAME" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "END_ROD" -> ParticleTypes.END_ROD;
            case "WITCH" -> ParticleTypes.WITCH;
            case "CLOUD" -> ParticleTypes.CLOUD;
            case "CRIT" -> ParticleTypes.CRIT;
            case "DRAGON_BREATH" -> ParticleTypes.DRAGON_BREATH;
            case "REVERSE_PORTAL" -> ParticleTypes.REVERSE_PORTAL;
            case "SCULK", "SCULK_SOUL" -> ParticleTypes.SCULK_SOUL;
            case "GLOW" -> ParticleTypes.GLOW;
            case "TOTEM" -> ParticleTypes.TOTEM_OF_UNDYING;
            default -> ParticleTypes.ENCHANT;
        };
    }

    public static void particles(ServerWorld w, String name, BlockPos pos, int count) {
        try {
            w.spawnParticles(particle(name),
                pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5,
                count, 0.3, 0.3, 0.3, 0.02);
        } catch (Exception ignored) {}
    }

    public static String typeId(Entity e) {
        try { return Registries.ENTITY_TYPE.getId(e.getType()).toString(); }
        catch (Exception ex) { return ""; }
    }

    /** Living entities whose registry id equals {@code typeKey}, within {@code radius} of {@code center}. */
    public static List<Entity> entitiesOfType(ServerWorld w, String typeKey, BlockPos center, double radius) {
        Box box = new Box(center).expand(radius);
        return w.getOtherEntities(null, box, e -> typeKey.equals(typeId(e)) && e.isAlive());
    }

    public static void sound(ServerWorld w, String id, BlockPos pos, float vol, float pitch) {
        if (id == null || id.isBlank()) return;
        try {
            SoundEvent ev = Registries.SOUND_EVENT.get(Identifier.of(id));
            if (ev != null) w.playSound(null, pos, ev, SoundCategory.MASTER, vol, pitch);
        } catch (Exception ignored) {}
    }
}

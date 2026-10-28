package com.cheesepatrol;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.argument.UuidArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

public class PatrolCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("npcpatrol")
                .requires(src -> src.hasPermissionLevel(2))

                // /npcpatrol wand
                .then(CommandManager.literal("wand")
                        .executes(PatrolCommand::giveWand))

                // /npcpatrol select <uuid>
                .then(CommandManager.literal("select")
                        .then(CommandManager.argument("uuid", UuidArgumentType.uuid())
                                .executes(PatrolCommand::selectNpc)))

                // /npcpatrol add [wait_seconds] or /npcpatrol add <x> <y> <z> [wait_seconds]
                .then(CommandManager.literal("add")
                        .executes(PatrolCommand::addWaypointAtFeet)
                        .then(CommandManager.argument("waitSeconds", IntegerArgumentType.integer(0, 3600))
                                .executes(PatrolCommand::addWaypointAtFeetWithWait))
                        .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(PatrolCommand::addWaypointAtPos)
                                                .then(CommandManager.argument("waitSecondsPos", IntegerArgumentType.integer(0, 3600))
                                                        .executes(PatrolCommand::addWaypointAtPosWithWait))))))

                // /npcpatrol remove <index>
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                                .executes(PatrolCommand::removeWaypoint)))

                // /npcpatrol start
                .then(CommandManager.literal("start")
                        .executes(PatrolCommand::startPatrol))

                // /npcpatrol stop
                .then(CommandManager.literal("stop")
                        .executes(PatrolCommand::stopPatrol))

                // /npcpatrol clear
                .then(CommandManager.literal("clear")
                        .executes(PatrolCommand::clearPatrol))

                // /npcpatrol list
                .then(CommandManager.literal("list")
                        .executes(PatrolCommand::listPatrols))

                // /npcpatrol info
                .then(CommandManager.literal("info")
                        .executes(PatrolCommand::patrolInfo))

                // /npcpatrol speed <value>
                .then(CommandManager.literal("speed")
                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0.1, 5.0))
                                .executes(PatrolCommand::setSpeed)))

                // /npcpatrol radius <value>
                .then(CommandManager.literal("radius")
                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(1.0, 32.0))
                                .executes(PatrolCommand::setRadius)))

                // /npcpatrol wait <index> <seconds>
                .then(CommandManager.literal("wait")
                        .then(CommandManager.argument("index", IntegerArgumentType.integer(1))
                                .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
                                        .executes(PatrolCommand::setWaypointWait))))

                // /npcpatrol show
                .then(CommandManager.literal("show")
                        .executes(PatrolCommand::showParticles))

                // /npcpatrol markers
                .then(CommandManager.literal("markers")
                        .executes(PatrolCommand::toggleMarkers))

                // /npcpatrol particles <type>
                .then(CommandManager.literal("particles")
                        .then(CommandManager.argument("type", StringArgumentType.word())
                                .suggests(PARTICLE_SUGGESTIONS)
                                .executes(PatrolCommand::setParticles)))

                // /npcpatrol particleheight <value>
                .then(CommandManager.literal("particleheight")
                        .then(CommandManager.argument("offset", DoubleArgumentType.doubleArg(-5.0, 5.0))
                                .executes(PatrolCommand::setParticleOffset)))

                // /npcpatrol particlerange <blocks>
                .then(CommandManager.literal("particlerange")
                        .then(CommandManager.argument("range", IntegerArgumentType.integer(16, 512))
                                .executes(PatrolCommand::setParticleRange)))

                // /npcpatrol particledepth <value> — forward/backward offset
                .then(CommandManager.literal("particledepth")
                        .then(CommandManager.argument("depth", DoubleArgumentType.doubleArg(-5.0, 5.0))
                                .executes(PatrolCommand::setParticleDepth)))
        );
    }

    private static final SuggestionProvider<ServerCommandSource> PARTICLE_SUGGESTIONS = (ctx, builder) -> {
        for (String effect : PatrolMarker.PARTICLE_EFFECTS) {
            if (effect.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(effect);
            }
        }
        return builder.buildFuture();
    };

    private static int giveWand(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.literal("Player only")); return 0; }
        player.getInventory().insertStack(PatrolWand.createWand());
        msg(ctx, "Patrol Wand given. Right-click an EasyNPC to select it, then use /npcpatrol add.");
        return 1;
    }

    private static int selectNpc(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.literal("Player only")); return 0; }

        UUID uuid = UuidArgumentType.getUuid(ctx, "uuid");
        var stack = player.getMainHandStack();
        if (!PatrolWand.isWand(stack)) {
            err(ctx, "Hold a Patrol Wand first! (/npcpatrol wand)");
            return 0;
        }

        // Verify entity exists
        Entity entity = player.getServerWorld().getEntity(uuid);
        if (entity == null) {
            err(ctx, "No entity found with UUID " + uuid);
            return 0;
        }

        PatrolWand.setSelectedNpc(stack, uuid);
        PatrolData patrol = PatrolManager.getOrCreate(uuid);
        patrol.setDimension(player.getServerWorld().getRegistryKey().getValue().toString());
        msg(ctx, "Selected NPC: " + uuid.toString().substring(0, 8) + "... (" + entity.getName().getString() + ")");
        return 1;
    }

    private static int addWaypointAtFeet(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.literal("Player only")); return 0; }

        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        return addWaypoint(ctx, npcUuid, player.getBlockPos(), 0);
    }

    private static int addWaypointAtFeetWithWait(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.literal("Player only")); return 0; }

        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        int waitSeconds = IntegerArgumentType.getInteger(ctx, "waitSeconds");
        return addWaypoint(ctx, npcUuid, player.getBlockPos(), waitSeconds);
    }

    private static int addWaypointAtPos(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        return addWaypoint(ctx, npcUuid, new BlockPos(x, y, z), 0);
    }

    private static int addWaypointAtPosWithWait(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        int waitSeconds = IntegerArgumentType.getInteger(ctx, "waitSecondsPos");
        return addWaypoint(ctx, npcUuid, new BlockPos(x, y, z), waitSeconds);
    }

    private static int addWaypoint(CommandContext<ServerCommandSource> ctx, UUID npcUuid, BlockPos pos, int waitSeconds) {
        PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
        patrol.addWaypoint(pos, waitSeconds * 20);
        PatrolManager.save();
        String waitStr = waitSeconds > 0 ? " (wait " + waitSeconds + "s)" : "";
        msg(ctx, "Added waypoint #" + patrol.getWaypoints().size() + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + waitStr);
        return 1;
    }

    private static int removeWaypoint(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        int index = IntegerArgumentType.getInteger(ctx, "index") - 1; // 1-based to 0-based
        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null || index < 0 || index >= patrol.getWaypoints().size()) {
            err(ctx, "Invalid waypoint index.");
            return 0;
        }

        patrol.removeWaypoint(index);
        PatrolManager.save();
        msg(ctx, "Removed waypoint #" + (index + 1) + ". " + patrol.getWaypoints().size() + " remaining.");
        return 1;
    }

    private static int startPatrol(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null || patrol.getWaypoints().size() < 2) {
            err(ctx, "Need at least 2 waypoints to start a patrol.");
            return 0;
        }

        patrol.setActive(true);
        patrol.setPausedBySystem(false);
        patrol.resetMissingTicks();
        if (patrol.getCurrentWaypointIndex() >= patrol.getWaypoints().size()) {
            patrol.setCurrentWaypointIndex(0);
        }

        // Force kick-start: clear AI goals, stop nav, and immediately move to waypoint
        MinecraftServer server = ctx.getSource().getServer();
        boolean kicked = PatrolManager.kickStartPatrol(server, patrol);

        PatrolManager.save();
        String extra = kicked ? " (NPC kick-started)" : " (NPC not found — will start when loaded)";
        msg(ctx, "Patrol started at waypoint #" + (patrol.getCurrentWaypointIndex() + 1) + "/" + patrol.getWaypoints().size() + extra);
        return 1;
    }

    private static int stopPatrol(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null) {
            err(ctx, "No patrol data for this NPC.");
            return 0;
        }

        patrol.setActive(false);
        patrol.setPausedBySystem(false); // admin-stop: don't auto-resume
        PatrolManager.releaseForcedChunks(ctx.getSource().getServer(), patrol);
        PatrolManager.save();
        msg(ctx, "Patrol stopped.");
        return 1;
    }

    private static int clearPatrol(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol != null) {
            PatrolManager.releaseForcedChunks(ctx.getSource().getServer(), patrol);
            patrol.clearWaypoints();
            if (player != null) {
                PatrolMarker.removeMarkers(player.getServerWorld(), npcUuid);
            }
        }
        PatrolManager.remove(npcUuid, ctx.getSource().getServer());
        PatrolManager.save();
        msg(ctx, "Patrol cleared and all waypoints removed.");
        return 1;
    }

    private static int listPatrols(CommandContext<ServerCommandSource> ctx) {
        var all = PatrolManager.getAll();
        if (all.isEmpty()) {
            msg(ctx, "No patrols configured.");
            return 1;
        }

        StringBuilder sb = new StringBuilder("Patrols (" + all.size() + "):\n");
        for (PatrolData p : all) {
            String status = p.isActive() ? "\u00a7a[ACTIVE]" : "\u00a77[INACTIVE]";
            sb.append(status).append(" \u00a7f")
                    .append(p.getNpcUuid().toString().substring(0, 8)).append("... | ")
                    .append(p.getWaypoints().size()).append(" WP | speed=").append(p.getSpeed())
                    .append(" | radius=").append(p.getPlayerDetectionRadius())
                    .append("\n");
        }
        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int patrolInfo(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null) {
            err(ctx, "No patrol data for this NPC.");
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\u00a76=== Patrol Info ===\n");
        sb.append("\u00a7fNPC: ").append(patrol.getNpcUuid()).append("\n");
        sb.append("\u00a7fDim: ").append(patrol.getDimension()).append("\n");
        sb.append("\u00a7fStatus: ").append(patrol.isActive() ? "\u00a7aACTIVE" : "\u00a7cINACTIVE").append("\n");
        sb.append("\u00a7fSpeed: ").append(patrol.getSpeed()).append("\n");
        sb.append("\u00a7fRadius: ").append(patrol.getPlayerDetectionRadius()).append("\n");
        sb.append("\u00a7fWaypoints (").append(patrol.getWaypoints().size()).append("):\n");
        for (int i = 0; i < patrol.getWaypoints().size(); i++) {
            BlockPos p = patrol.getWaypoints().get(i);
            String marker = (i == patrol.getCurrentWaypointIndex()) ? " \u00a7e<-- current" : "";
            int wt = patrol.getWaypointWait(i);
            String waitStr = wt > 0 ? " \u00a7b[wait " + (wt / 20) + "s]" : "";
            sb.append("  \u00a77#").append(i + 1).append(": \u00a7f")
                    .append(p.getX()).append(" ").append(p.getY()).append(" ").append(p.getZ())
                    .append(waitStr).append(marker).append("\n");
        }
        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int setSpeed(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        double value = DoubleArgumentType.getDouble(ctx, "value");
        PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
        patrol.setSpeed(value);
        PatrolManager.save();
        msg(ctx, "Speed set to " + patrol.getSpeed());
        return 1;
    }

    private static int setRadius(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        double value = DoubleArgumentType.getDouble(ctx, "value");
        PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
        patrol.setPlayerDetectionRadius(value);
        PatrolManager.save();
        msg(ctx, "Player detection radius set to " + patrol.getPlayerDetectionRadius());
        return 1;
    }

    private static int showParticles(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.literal("Player only")); return 0; }

        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null || patrol.getWaypoints().isEmpty()) {
            err(ctx, "No waypoints to show.");
            return 0;
        }

        PatrolManager.toggleShow(player, npcUuid);
        if (PatrolManager.isShowing(player)) {
            PatrolMarker.showParticles(player, patrol);
            msg(ctx, "Particles ON. Run again to hide.");
        } else {
            msg(ctx, "Particles OFF.");
        }
        return 1;
    }

    private static int toggleMarkers(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.literal("Player only")); return 0; }

        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null || patrol.getWaypoints().isEmpty()) {
            err(ctx, "No waypoints to mark.");
            return 0;
        }

        ServerWorld world = player.getServerWorld();
        PatrolMarker.refreshMarkers(world, patrol);
        msg(ctx, "Armor stand markers placed at " + patrol.getWaypoints().size() + " waypoint(s). Run again to refresh.");
        return 1;
    }

    private static int setWaypointWait(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        int index = IntegerArgumentType.getInteger(ctx, "index") - 1;
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null || index < 0 || index >= patrol.getWaypoints().size()) {
            err(ctx, "Invalid waypoint index.");
            return 0;
        }

        patrol.setWaypointWait(index, seconds * 20);
        PatrolManager.save();
        if (seconds == 0) {
            msg(ctx, "Waypoint #" + (index + 1) + " set to smooth (no wait).");
        } else {
            msg(ctx, "Waypoint #" + (index + 1) + " wait set to " + seconds + "s.");
        }
        return 1;
    }

    private static int setParticleDepth(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        float depth = (float) DoubleArgumentType.getDouble(ctx, "depth");
        PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
        patrol.setParticleZOffset(depth);
        PatrolManager.save();
        msg(ctx, "Particle depth offset set to " + depth + " (negative=ahead, positive=behind)");
        return 1;
    }

    private static int setParticleRange(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        int range = IntegerArgumentType.getInteger(ctx, "range");
        PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
        patrol.setParticleRange(range);
        PatrolManager.save();
        msg(ctx, "Particle visibility range set to " + range + " blocks");
        return 1;
    }

    private static int setParticleOffset(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        float offset = (float) DoubleArgumentType.getDouble(ctx, "offset");
        PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
        patrol.setParticleYOffset(offset);
        PatrolManager.save();
        msg(ctx, "Particle Y offset set to " + offset);
        return 1;
    }

    private static int setParticles(CommandContext<ServerCommandSource> ctx) {
        UUID npcUuid = getSelectedNpc(ctx);
        if (npcUuid == null) return 0;

        String type = StringArgumentType.getString(ctx, "type").toLowerCase();
        if (!PatrolMarker.PARTICLE_EFFECTS.contains(type)) {
            err(ctx, "Unknown particle type: " + type + ". Available: " + String.join(", ", PatrolMarker.PARTICLE_EFFECTS));
            return 0;
        }

        PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
        patrol.setParticleEffect(type);
        PatrolManager.save();

        if ("none".equals(type)) {
            msg(ctx, "Particle trail disabled.");
        } else {
            msg(ctx, "Particle trail set to: " + type);
        }
        return 1;
    }

    // --- Helpers ---

    private static UUID getSelectedNpc(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Player only"));
            return null;
        }

        var stack = player.getMainHandStack();
        UUID npcUuid = PatrolWand.getSelectedNpc(stack);
        if (npcUuid == null) {
            err(ctx, "No NPC selected. Hold a Patrol Wand and /npcpatrol select <uuid>, or right-click an EasyNPC.");
            return null;
        }
        return npcUuid;
    }

    private static void msg(CommandContext<ServerCommandSource> ctx, String message) {
        ctx.getSource().sendFeedback(() -> Text.literal("[CheesePatrol] " + message).formatted(Formatting.GOLD), false);
    }

    private static void err(CommandContext<ServerCommandSource> ctx, String message) {
        ctx.getSource().sendError(Text.literal("[CheesePatrol] " + message));
    }
}

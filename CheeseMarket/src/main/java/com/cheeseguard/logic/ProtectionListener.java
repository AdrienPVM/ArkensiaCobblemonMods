package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import com.cheeseguard.data.Region;
import com.cheeseguard.data.RegionGroup;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancement.AdvancementEntry;
import java.util.List;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

public class ProtectionListener {

    // Track player positions for entry/exit detection
    private final Map<UUID, BlockPos> lastPositions = new HashMap<>();
    private int tickCounter = 0;
    private static boolean cobblemonLoaded = false;
    private static boolean ftbQuestsLoaded = false;

    public void register() {
        // Detect optional mods at startup
        cobblemonLoaded = FabricLoader.getInstance().isModLoaded("cobblemon");
        ftbQuestsLoaded = FabricLoader.getInstance().isModLoaded("ftbquests");

        // ── Block Break ──────────────────────────────────────────────────────
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return true;
            if (isWand(player.getMainHandStack())) return false; // wand intercepts
            return checkAndNotify(sp, world, pos, Flag.BLOCK_BREAK);
        });

        // ── Block Interact ───────────────────────────────────────────────────
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (isWand(player.getStackInHand(hand))) return ActionResult.PASS;

            ItemStack heldStack = player.getStackInHand(hand);
            BlockPos pos = hitResult.getBlockPos();

            if (!heldStack.isEmpty() && heldStack.getItem() instanceof BlockItem) {
                return ActionResult.PASS;
            }

            Item heldItem = heldStack.getItem();
            if (heldItem instanceof FlintAndSteelItem || heldItem instanceof BucketItem
                    || heldItem instanceof FireChargeItem || heldItem == Items.BONE_MEAL) {
                BlockPos offsetPos = pos.offset(hitResult.getSide());
                if (!checkAndNotify(sp, world, pos, Flag.USE_ITEMS) || !checkAndNotify(sp, world, offsetPos, Flag.USE_ITEMS))
                    return ActionResult.FAIL;
                return ActionResult.PASS;
            }

            if (!checkAndNotify(sp, world, pos, Flag.INTERACT)) return ActionResult.FAIL;

            // ── Dimension-level block restrictions ──
            String worldId = world.getRegistryKey().getValue().toString();
            var dimMgr = CheeseGuardMod.getInstance().getDimensionManager();
            if (dimMgr != null) {
                net.minecraft.block.BlockState state = world.getBlockState(pos);
                // Command block restriction
                if (!dimMgr.isCommandBlocksAllowed(worldId)
                        && (state.getBlock() instanceof net.minecraft.block.CommandBlock
                            || state.getBlock() instanceof net.minecraft.block.StructureBlock)) {
                    sp.sendMessage(Text.literal("\u26D4 Blocs de commande desactives dans cette dimension"), true);
                    return ActionResult.FAIL;
                }
                // Ender chest restriction
                if (!dimMgr.isEnderChestAllowed(worldId)
                        && state.getBlock() instanceof net.minecraft.block.EnderChestBlock) {
                    sp.sendMessage(Text.literal("\u26D4 Coffre de l'Ender desactive dans cette dimension"), true);
                    return ActionResult.FAIL;
                }
            }

            return ActionResult.PASS;
        });

        // ── Entity Interact ──────────────────────────────────────────────────
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            BlockPos pos = entity.getBlockPos();
            if (!checkAndNotify(sp, world, pos, Flag.INTERACT)) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        // ── Attack Entity ────────────────────────────────────────────────────
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            BlockPos pos = entity.getBlockPos();
            Flag flag = (entity instanceof PlayerEntity) ? Flag.PVP : Flag.ENTITY_DAMAGE;
            if (!checkAndNotify(sp, world, pos, flag)) return ActionResult.FAIL;
            // Dimension PvP rule (overrides zone if dimension says no PvP)
            if (entity instanceof PlayerEntity) {
                String worldId = world.getRegistryKey().getValue().toString();
                var dimMgr = CheeseGuardMod.getInstance().getDimensionManager();
                if (dimMgr != null && !dimMgr.isPvpAllowed(worldId)) {
                    sp.sendMessage(Text.literal("\u26D4 PvP desactive dans cette dimension"), true);
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });

        // ── Item Use ─────────────────────────────────────────────────────────
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(player.getStackInHand(hand));
            ItemStack stack = player.getStackInHand(hand);
            Item item = stack.getItem();

            if (item instanceof EnderPearlItem) {
                BlockPos pos = player.getBlockPos();
                if (!checkAndNotify(sp, world, pos, Flag.ENDER_PEARL))
                    return TypedActionResult.fail(stack);
            }

            if (item instanceof BucketItem || item instanceof FlintAndSteelItem
                    || item == Items.TNT || item instanceof FireChargeItem) {
                BlockPos pos = player.getBlockPos();
                if (!checkAndNotify(sp, world, pos, Flag.USE_ITEMS))
                    return TypedActionResult.fail(stack);
            }

            return TypedActionResult.pass(stack);
        });

        // ── Entry / Exit + Fly check + Visualization tick ────────────────────
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 10 == 0) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    checkEntryExit(player);
                    checkFly(player);
                }
            }
            if (tickCounter % 20 == 0) {
                VisualizationManager vm = CheeseGuardMod.getInstance().getVisualizationManager();
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    vm.tick(player);
                }
            }
        });
    }

    // ── Wand detection ───────────────────────────────────────────────────────

    private boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof SwordItem)) return false;
        if (!stack.getItem().equals(Items.WOODEN_SWORD)) return false;
        return stack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME)
            && stack.getName().getString().contains("§6[CheeseGuard Wand]");
    }

    // ── Protection check helper ───────────────────────────────────────────────

    private boolean checkAndNotify(ServerPlayerEntity player, World world, BlockPos pos, Flag flag) {
        String worldId = world.getRegistryKey().getValue().toString();
        RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();

        boolean allowed = rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), flag);
        if (!allowed) {
            String msg = rm.getProtectionMessage(worldId, pos.getX(), pos.getY(), pos.getZ());
            player.sendMessage(Text.literal(msg), true); // action bar
        }
        return allowed;
    }

    // ── Fly + Mount + Pokemon check ────────────────────────────────────────

    private void checkFly(ServerPlayerEntity player) {
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        BlockPos pos = player.getBlockPos();
        RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();

        // Check FLY flag (elytra + creative fly)
        if (!rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.FLY)) {
            if (player.isFallFlying()) {
                player.stopFallFlying();
                player.sendMessage(Text.literal("⛔ Vol interdit dans cette zone !"), true);
            }
            if (player.getAbilities().flying && !player.isSpectator()) {
                player.getAbilities().flying = false;
                player.sendAbilitiesUpdate();
                player.sendMessage(Text.literal("⛔ Vol interdit dans cette zone !"), true);
            }
        }

        // Cobblemon checks (only if loaded)
        if (!cobblemonLoaded) return;

        // Check COBBLEMON_FLY flag (flying mounts)
        if (!rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.COBBLEMON_FLY)) {
            Entity vehicle = player.getVehicle();
            if (vehicle != null) {
                String namespace = net.minecraft.entity.EntityType.getId(vehicle.getType()).getNamespace();
                if ("cobblemon".equals(namespace) && !vehicle.isOnGround()) {
                    player.stopRiding();
                    player.sendMessage(Text.literal("⛔ Monture volante interdite dans cette zone !"), true);
                }
            }
        }

        // Check COBBLEMON_MOUNT flag (ground mounts)
        if (!rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.COBBLEMON_MOUNT)) {
            Entity vehicle = player.getVehicle();
            if (vehicle != null) {
                String namespace = net.minecraft.entity.EntityType.getId(vehicle.getType()).getNamespace();
                if ("cobblemon".equals(namespace)) {
                    player.stopRiding();
                    player.sendMessage(Text.literal("⛔ Monture interdite dans cette zone !"), true);
                }
            }
        }

        // Check COBBLEMON_SUMMON flag — recall pokemon if player walks into a deny zone
        // The send-out is already blocked by CobblemonHook's event handler,
        // but this handles the case where a player walks INTO a zone with pokemon already out.
        if (!rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.COBBLEMON_SUMMON)) {
            // Check if any cobblemon entity is near this player
            boolean hasCobblemonNearby = false;
            var box = player.getBoundingBox().expand(32);
            for (Entity entity : player.getServerWorld().getOtherEntities(player, box)) {
                String ns = net.minecraft.entity.EntityType.getId(entity.getType()).getNamespace();
                if ("cobblemon".equals(ns) && entity.squaredDistanceTo(player) < 16 * 16) {
                    hasCobblemonNearby = true;
                    break;
                }
            }
            if (hasCobblemonNearby) {
                CobblemonHook.recallPlayerPokemon(player);
                player.sendMessage(Text.literal("⛔ Pokémon interdits dans cette zone ! Rappel automatique.").formatted(Formatting.RED), true);
            }
        }
    }

    // ── Entry / Exit with advancement check ──────────────────────────────────

    private void checkEntryExit(ServerPlayerEntity player) {
        BlockPos current = player.getBlockPos();
        BlockPos last = lastPositions.get(player.getUuid());

        if (last != null && !last.equals(current)) {
            String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
            RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();

            var previousRegions = rm.getRegionsAt(worldId, last.getX(), last.getY(), last.getZ());
            var currentRegions  = rm.getRegionsAt(worldId, current.getX(), current.getY(), current.getZ());

            // Entry: regions in current but not in previous
            for (var region : currentRegions) {
                if (previousRegions.stream().noneMatch(r -> r.getId().equals(region.getId()))) {
                    // Check entry flag
                    if (!rm.isAllowed(player, worldId, current.getX(), current.getY(), current.getZ(), Flag.ENTRY)) {
                        pushBack(player, last, "⛔ Vous ne pouvez pas entrer dans cette zone !");
                        return;
                    }
                    // Check advancement requirements for entry
                    List<String> requiredAdv = getRequiredAdvancements(region, true);
                    if (!requiredAdv.isEmpty()) {
                        List<String> missing = getMissingAdvancements(player, requiredAdv);
                        if (!missing.isEmpty()) {
                            String msg = region.getAdvancementDeniedMessage();
                            pushBack(player, last, msg);
                            player.sendMessage(Text.literal("Advancements manquants: " + String.join(", ", missing))
                                .formatted(Formatting.RED), false);
                            return;
                        }
                    }
                    // Check FTB Quest requirements for entry
                    if (ftbQuestsLoaded) {
                        List<String> requiredQuests = getRequiredQuests(region, true);
                        if (!requiredQuests.isEmpty()) {
                            List<String> missingQ = getMissingQuests(player, requiredQuests);
                            if (!missingQ.isEmpty()) {
                                pushBack(player, last, region.getAdvancementDeniedMessage());
                                player.sendMessage(Text.literal("Quetes manquantes: " + String.join(", ", missingQ))
                                    .formatted(Formatting.RED), false);
                                return;
                            }
                        }
                    }
                    if (region.isEntryMessageEnabled()) {
                        player.sendMessage(Text.literal(region.getEntryMessage()), false);
                    }
                    // Execute ON_ENTER commands
                    executeRegionCommands(player, region.getCommandsOnEnter());
                }
            }

            // Exit: regions in previous but not in current
            for (var region : previousRegions) {
                if (currentRegions.stream().noneMatch(r -> r.getId().equals(region.getId()))) {
                    if (!rm.isAllowed(player, worldId, last.getX(), last.getY(), last.getZ(), Flag.EXIT)) {
                        pushBack(player, last, "⛔ Vous ne pouvez pas quitter cette zone !");
                        return;
                    }
                    // Check advancement requirements for exit
                    List<String> requiredAdv = getRequiredAdvancements(region, false);
                    if (!requiredAdv.isEmpty()) {
                        List<String> missing = getMissingAdvancements(player, requiredAdv);
                        if (!missing.isEmpty()) {
                            String msg = region.getAdvancementDeniedMessage();
                            pushBack(player, last, msg);
                            player.sendMessage(Text.literal("Advancements manquants: " + String.join(", ", missing))
                                .formatted(Formatting.RED), false);
                            return;
                        }
                    }
                    // Check FTB Quest requirements for exit
                    if (ftbQuestsLoaded) {
                        List<String> requiredQuests = getRequiredQuests(region, false);
                        if (!requiredQuests.isEmpty()) {
                            List<String> missingQ = getMissingQuests(player, requiredQuests);
                            if (!missingQ.isEmpty()) {
                                pushBack(player, last, region.getAdvancementDeniedMessage());
                                player.sendMessage(Text.literal("Quetes manquantes: " + String.join(", ", missingQ))
                                    .formatted(Formatting.RED), false);
                                return;
                            }
                        }
                    }
                    if (region.isExitMessageEnabled()) {
                        player.sendMessage(Text.literal(region.getExitMessage()), false);
                    }
                    // Execute ON_EXIT commands
                    executeRegionCommands(player, region.getCommandsOnExit());
                }
            }
        }

        lastPositions.put(player.getUuid(), current);
    }

    /**
     * Executes a list of server commands as if run by the server console.
     * Supports {player} placeholder which is replaced by the player's name.
     * Commands must NOT start with "/".
     */
    private void executeRegionCommands(ServerPlayerEntity player, List<String> commands) {
        if (commands == null || commands.isEmpty()) return;
        var server = player.getServer();
        if (server == null) return;
        var dispatcher = server.getCommandManager().getDispatcher();
        var source = server.getCommandSource(); // server source = op-level
        String name = player.getName().getString();
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String cmd = raw.replace("{player}", name).trim();
            // Strip leading "/" if present
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                dispatcher.execute(cmd, source);
            } catch (Exception e) {
                // Log but don't crash
                server.sendMessage(Text.literal("[CheeseGuard] Erreur commande zone \"" + cmd + "\": " + e.getMessage()));
            }
        }
    }

    private void pushBack(ServerPlayerEntity player, BlockPos last, String message) {
        // teleport() instead of requestTeleport() to guarantee same world is used
        player.teleport(player.getServerWorld(), last.getX() + 0.5, last.getY(), last.getZ() + 0.5,
            java.util.Set.of(), player.getYaw(), player.getPitch());
        player.sendMessage(Text.literal(message), true);
        lastPositions.put(player.getUuid(), last);
    }

    /**
     * Get combined advancement requirements from region + its group.
     * @param entry true = entry requirements, false = exit requirements
     */
    private List<String> getRequiredAdvancements(Region region, boolean entry) {
        Set<String> required = new LinkedHashSet<>();
        // Region's own requirements
        List<String> regionAdv = entry ? region.getRequiredAdvancementsEntry() : region.getRequiredAdvancementsExit();
        required.addAll(regionAdv);
        // Group's requirements (inheritance)
        if (region.getGroupId() != null) {
            RegionGroup group = CheeseGuardMod.getInstance().getStorage().getGroup(region.getGroupId());
            if (group != null) {
                List<String> groupAdv = entry ? group.getRequiredAdvancementsEntry() : group.getRequiredAdvancementsExit();
                required.addAll(groupAdv);
            }
        }
        return new ArrayList<>(required);
    }

    /**
     * Check which required advancements the player is missing.
     */
    private List<String> getMissingAdvancements(ServerPlayerEntity player, List<String> required) {
        List<String> missing = new ArrayList<>();
        PlayerAdvancementTracker tracker = player.getAdvancementTracker();
        var server = player.getServer();
        if (server == null) return required;

        for (String advId : required) {
            Identifier id = Identifier.tryParse(advId);
            if (id == null) continue;
            AdvancementEntry adv = server.getAdvancementLoader().get(id);
            if (adv == null) continue;
            if (!tracker.getProgress(adv).isDone()) {
                // Use display name if available, otherwise raw ID
                String displayName = advId;
                if (adv.value().display().isPresent()) {
                    displayName = adv.value().display().get().getTitle().getString();
                }
                missing.add(displayName);
            }
        }
        return missing;
    }

    // ── FTB Quest requirements ───────────────────────────────────────────────

    private List<String> getRequiredQuests(Region region, boolean entry) {
        Set<String> required = new LinkedHashSet<>();
        List<String> regionQuests = entry ? region.getRequiredQuestsEntry() : region.getRequiredQuestsExit();
        required.addAll(regionQuests);
        if (region.getGroupId() != null) {
            RegionGroup group = CheeseGuardMod.getInstance().getStorage().getGroup(region.getGroupId());
            if (group != null) {
                List<String> groupQuests = entry ? group.getRequiredQuestsEntry() : group.getRequiredQuestsExit();
                required.addAll(groupQuests);
            }
        }
        return new ArrayList<>(required);
    }

    /**
     * Check which required FTB quests the player is missing.
     * Uses reflection to access FTB Quests API without compile-time dependency.
     */
    private List<String> getMissingQuests(ServerPlayerEntity player, List<String> requiredQuestIds) {
        List<String> missing = new ArrayList<>();
        if (!ftbQuestsLoaded) return requiredQuestIds;

        // Build quest name lookup
        Map<String, String> questNames = new HashMap<>();
        for (FtbQuestHelper.QuestInfo q : FtbQuestHelper.getAllQuests()) {
            questNames.put(q.id(), q.title());
        }

        try {
            // Access FTB Quests API via reflection
            Class<?> sqfClass = Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
            Object questFile = sqfClass.getField("INSTANCE").get(null);
            if (questFile == null) return requiredQuestIds;

            // Get team data for this player
            Object teamData = null;
            try {
                // Try modern API: getData(ServerPlayer)
                var getDataMethod = questFile.getClass().getMethod("getData", player.getClass());
                teamData = getDataMethod.invoke(questFile, player);
            } catch (NoSuchMethodException e) {
                try {
                    // Try alternate: getOrCreateTeamData(ServerPlayer)
                    var getDataMethod = questFile.getClass().getMethod("getOrCreateTeamData",
                        Class.forName("net.minecraft.server.network.ServerPlayerEntity"));
                    teamData = getDataMethod.invoke(questFile, player);
                } catch (NoSuchMethodException e2) {
                    try {
                        var getDataMethod = questFile.getClass().getMethod("getOrCreateTeamData",
                            Class.forName("net.minecraft.server.level.ServerPlayer"));
                        teamData = getDataMethod.invoke(questFile, player);
                    } catch (NoSuchMethodException e3) {
                        // Can't access team data
                        return requiredQuestIds;
                    }
                }
            }

            if (teamData == null) return requiredQuestIds;

            for (String questHexId : requiredQuestIds) {
                try {
                    long questLongId = Long.parseUnsignedLong(questHexId, 16);
                    // Get quest object: questFile.getBase(long)
                    Object quest = null;
                    try {
                        quest = questFile.getClass().getMethod("getBase", long.class).invoke(questFile, questLongId);
                    } catch (Exception ex) {
                        try {
                            quest = questFile.getClass().getMethod("get", long.class).invoke(questFile, questLongId);
                        } catch (Exception ignored) {}
                    }

                    if (quest == null) {
                        missing.add(questNames.getOrDefault(questHexId, questHexId));
                        continue;
                    }

                    // Check completion: teamData.isCompleted(quest)
                    boolean completed = false;
                    try {
                        completed = (boolean) teamData.getClass().getMethod("isCompleted", quest.getClass())
                            .invoke(teamData, quest);
                    } catch (Exception ex) {
                        // Try with superclass
                        for (var method : teamData.getClass().getMethods()) {
                            if (method.getName().equals("isCompleted") && method.getParameterCount() == 1) {
                                try {
                                    completed = (boolean) method.invoke(teamData, quest);
                                    break;
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    if (!completed) {
                        missing.add(questNames.getOrDefault(questHexId, questHexId));
                    }
                } catch (NumberFormatException e) {
                    missing.add(questNames.getOrDefault(questHexId, questHexId));
                }
            }
        } catch (Exception e) {
            // FTB Quests API not accessible - treat all as missing
            CheeseGuardMod.LOGGER.debug("[CG] FTB Quest check failed: {}", e.getMessage());
            for (String qid : requiredQuestIds) {
                missing.add(questNames.getOrDefault(qid, qid));
            }
        }
        return missing;
    }
}

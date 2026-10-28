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
    // Track nametag hide state per player (to avoid sending redundant packets)
    private final Map<UUID, Boolean> nametagHideState = new HashMap<>();
    // Track minimap hide state per player
    private final Map<UUID, Boolean> minimapHideState = new HashMap<>();
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

            // Sale sign interaction — zone ID stored in back_text line 0
            if (world instanceof ServerWorld) {
                BlockState state = world.getBlockState(hitResult.getBlockPos());
                if (state.getBlock() instanceof net.minecraft.block.AbstractSignBlock) {
                    net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(hitResult.getBlockPos());
                    if (be instanceof net.minecraft.block.entity.SignBlockEntity sign) {
                        String zoneId = sign.getBackText().getMessage(0, false).getString().trim();
                        if (!zoneId.isEmpty()) {
                            var storage = CheeseGuardMod.getInstance().getStorage();
                            var region = storage.getRegion(zoneId);
                            if (region != null && region.getSaleConfig() != null) {
                                handleSaleInteraction(sp, zoneId, world, hitResult.getBlockPos());
                                return ActionResult.SUCCESS;
                            }
                        }
                    }
                }
            }

            ItemStack heldStack = player.getStackInHand(hand);
            BlockPos pos = hitResult.getBlockPos();

            if (!heldStack.isEmpty() && heldStack.getItem() instanceof BlockItem blockItem) {
                // ── Construction mode: block forbidden item placement ──────────
                String worldId2 = world.getRegistryKey().getValue().toString();
                var dimMgr2 = CheeseGuardMod.getInstance().getDimensionManager();
                if (dimMgr2 != null) {
                    var cfg2 = dimMgr2.getConfigByWorldId(worldId2);
                    if (cfg2 != null && cfg2.isConstructionMode()
                            && cfg2.isConstructionBuilder(sp.getUuid().toString())) {
                        String itemId2 = net.minecraft.registry.Registries.ITEM.getId(blockItem).toString();
                        if (dimMgr2.isConstructionForbiddenItem(itemId2)) {
                            sp.sendMessage(Text.translatable("cheeseguard.protection.construction_forbidden_item", itemId2)
                                .formatted(Formatting.RED), true);
                            return ActionResult.FAIL;
                        }
                    }
                    // Dimension-wide ender chest block (regardless of builder)
                    if (!dimMgr2.isEnderChestAllowed(worldId2)
                            && blockItem.getBlock() instanceof net.minecraft.block.EnderChestBlock) {
                        sp.sendMessage(Text.translatable("cheeseguard.protection.ender_chest_disabled"), true);
                        return ActionResult.FAIL;
                    }
                }
                // ── Cobblemon pasture per-chunk limits ─────────────────────────
                if (cobblemonLoaded && world instanceof ServerWorld sw) {
                    String blockId2 = net.minecraft.registry.Registries.BLOCK.getId(blockItem.getBlock()).toString();
                    if ("cobblemon:pasture".equals(blockId2)) {
                        BlockPos placePos = pos.offset(hitResult.getSide());
                        var plMgr = CheeseGuardMod.getInstance().getPastureLimitsManager();
                        var reason = plMgr.canPlace(sw, placePos, sp.getUuid());
                        if (reason != null) {
                            int limit = (reason == com.cheeseguard.logic.PastureLimitsManager.DenyReason.CHUNK_FULL)
                                    ? plMgr.getConfig().getMaxPerChunk()
                                    : plMgr.getConfig().getMaxPerChunkPerPlayer();
                            String key = (reason == com.cheeseguard.logic.PastureLimitsManager.DenyReason.CHUNK_FULL)
                                    ? "cheeseguard.protection.pasture_chunk_full"
                                    : "cheeseguard.protection.pasture_player_full";
                            sp.sendMessage(Text.translatable(key, limit).formatted(Formatting.RED), true);
                            return ActionResult.FAIL;
                        }
                    }
                }
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
                    sp.sendMessage(Text.translatable("cheeseguard.protection.command_blocks_disabled"), true);
                    return ActionResult.FAIL;
                }
                // Ender chest restriction
                if (!dimMgr.isEnderChestAllowed(worldId)
                        && state.getBlock() instanceof net.minecraft.block.EnderChestBlock) {
                    sp.sendMessage(Text.translatable("cheeseguard.protection.ender_chest_disabled"), true);
                    return ActionResult.FAIL;
                }
            }

            return ActionResult.PASS;
        });

        // ── Entity Interact ──────────────────────────────────────────────────
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;

            // Sale hologram interaction
            if (world instanceof ServerWorld) {
                if (entity instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity) {
                    String entityUuid = entity.getUuidAsString();
                    var storage = CheeseGuardMod.getInstance().getStorage();
                    for (var region : storage.getRegions()) {
                        if (region.getSaleConfig() != null && entityUuid.equals(region.getSaleConfig().getHologramUuid())) {
                            // Hologram, not a sign block — no sign to break on purchase.
                            handleSaleInteraction(sp, region.getId(), null, null);
                            return ActionResult.SUCCESS;
                        }
                    }
                }
            }

            BlockPos pos = entity.getBlockPos();
            if (!checkAndNotify(sp, world, pos, Flag.INTERACT)) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        // ── Attack Entity ────────────────────────────────────────────────────
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            BlockPos pos = entity.getBlockPos();
            Flag flag = (entity instanceof PlayerEntity) ? Flag.PVP : Flag.ENTITY_DAMAGE;
            // PropHunt PVP override: allow hunters to attack disguised hiders
            if (flag == Flag.PVP && entity instanceof ServerPlayerEntity targetPlayer) {
                var emPh = CheeseGuardMod.getInstance().getEventManager();
                if (emPh != null && emPh.getMiniGameManager().isPropHuntCombatAllowed(
                        sp.getUuid(), targetPlayer.getUuid())) {
                    // Apply virtual damage to the hider's health pool
                    emPh.getMiniGameManager().onPropHuntPlayerHit(sp, targetPlayer);
                    return ActionResult.PASS;
                }
            }
            if (!checkAndNotify(sp, world, pos, flag)) return ActionResult.FAIL;
            // Dimension PvP rule (overrides zone if dimension says no PvP)
            if (entity instanceof PlayerEntity) {
                String worldId = world.getRegistryKey().getValue().toString();
                var dimMgr = CheeseGuardMod.getInstance().getDimensionManager();
                if (dimMgr != null && !dimMgr.isPvpAllowed(worldId)) {
                    sp.sendMessage(Text.translatable("cheeseguard.protection.pvp_disabled"), true);
                    return ActionResult.FAIL;
                }
            }
            // Prop Hunt: penalty when hunter melees a wild Pokémon (not a player)
            if (!(entity instanceof PlayerEntity)) {
                var em = CheeseGuardMod.getInstance().getEventManager();
                if (em != null) {
                    em.getMiniGameManager().onPropHuntEntityHit(sp, entity);
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
                boolean refreshOwnerHint = (tickCounter % 40 == 0);
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    checkEntryExit(player);
                    checkFly(player);
                    checkTransform(player);
                    checkNametagHide(player);
                    checkMinimapHide(player);
                    // Action-bar reminder when player is inside a zone they own.
                    // Refreshed every 40 ticks (~2s) so it stays visible without flicker.
                    if (refreshOwnerHint) checkOwnerZoneHint(player);
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

        // RegionManager.isAllowed already handles the full hierarchy:
        // OP bypass → member/owner exemption (memberExempt flags) → explicit zone flag →
        // group flag → dimension fallback. A second post-check on dimension flags here
        // would override the member exemption, so we trust isAllowed() as the source of truth.
        boolean allowed = rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), flag);
        if (!allowed) {
            String msg = rm.getProtectionMessage(worldId, pos.getX(), pos.getY(), pos.getZ());
            player.sendMessage(Text.literal(msg), true); // action bar
        }
        return allowed;
    }

    // ── Owner zone hint (action bar) ───────────────────────────────────────

    /**
     * If the player is currently inside a zone they own, refresh an action-bar
     * hint reminding them they can press the zone-menu keybind to manage it.
     * Uses {@link Text#keybind(String)} so the displayed key reflects the player's
     * own (possibly rebound) binding.
     */
    private void checkOwnerZoneHint(ServerPlayerEntity player) {
        com.cheeseguard.data.Region zone =
            com.cheeseguard.logic.ZoneOwnerMenuHandler.findOwnedZoneAt(player);
        if (zone == null) return;
        player.sendMessage(
            Text.translatable("cheeseguard.zone_menu.action_bar",
                zone.getName(),
                Text.keybind("key.cheeseguard.zone_menu"))
                .formatted(Formatting.YELLOW),
            true);
    }

    // ── Sale interaction helper ────────────────────────────────────────────

    private void handleSaleInteraction(ServerPlayerEntity player, String zoneId, World world, BlockPos signPos) {
        var storage = CheeseGuardMod.getInstance().getStorage();
        var region = storage.getRegion(zoneId);
        if (region == null || region.getSaleConfig() == null || !region.getSaleConfig().isEnabled()) {
            player.sendMessage(Text.translatable("cheeseguard.sale.notForSale"), false);
            return;
        }
        if (region.getSaleConfig().isSold()) {
            player.sendMessage(Text.translatable("cheeseguard.sale.alreadySold"), false);
            return;
        }
        // Remember which sign block this player clicked, so we can break it on purchase confirm.
        // The hologram path passes null/null — in that case we explicitly clear any stale sign-click
        // record so a previous click can't bleed into a hologram-initiated purchase.
        if (world != null && signPos != null) {
            String worldId = world.getRegistryKey().getValue().toString();
            SaleManager.recordSignClick(player.getUuid(), worldId, signPos, zoneId);
        } else {
            SaleManager.clearSignClick(player.getUuid());
        }
        SaleManager.sendPurchasePrompt(player, region);
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
                player.sendMessage(Text.translatable("cheeseguard.protection.fly_denied"), true);
            }
            if (player.getAbilities().flying && !player.isSpectator()) {
                player.getAbilities().flying = false;
                player.sendAbilitiesUpdate();
                player.sendMessage(Text.translatable("cheeseguard.protection.fly_denied"), true);
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
                    player.sendMessage(Text.translatable("cheeseguard.protection.cobblemon_fly_denied"), true);
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
                    player.sendMessage(Text.translatable("cheeseguard.protection.cobblemon_mount_denied"), true);
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
                player.sendMessage(Text.translatable("cheeseguard.protection.cobblemon_summon_denied").formatted(Formatting.RED), true);
            }
        }
    }

    // ── CobbleShift transform check ─────────────────────────────────────────

    /**
     * Enforce the COBBLESHIFT_TRANSFORM flag: when a player is inside a zone/dimension
     * that denies CobbleShift transformations, revert them if they are transformed.
     * Runs every 10 ticks, so it covers BOTH walking into a deny area while transformed
     * AND transforming while already standing inside one (reverted within ~0.5s).
     * No-op if CobbleShift isn't installed. revertPlayer() returns true only when it
     * actually reverted a transformed player, so the notice shows exactly once.
     */
    private void checkTransform(ServerPlayerEntity player) {
        if (!CobbleShiftHook.isAvailable()) return;
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        BlockPos pos = player.getBlockPos();
        RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();
        if (!rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.COBBLESHIFT_TRANSFORM)) {
            if (CobbleShiftHook.revertPlayer(player)) {
                // A CobbleShift transform leaves behind potion effects and (for flying
                // species) granted flight. The revert alone doesn't clean those up, so we
                // also clear effects and disable flight to return the player to a clean state.
                player.clearStatusEffects();
                if (!player.isCreative() && !player.isSpectator()) {
                    var abilities = player.getAbilities();
                    if (abilities.flying || abilities.allowFlying) {
                        abilities.flying = false;
                        abilities.allowFlying = false;
                        player.sendAbilitiesUpdate();
                    }
                }
                player.sendMessage(Text.translatable("cheeseguard.protection.transform_denied").formatted(Formatting.RED), true);
            }
        }
    }

    // ── Nametag hide check ──────────────────────────────────────────────────

    private void checkNametagHide(ServerPlayerEntity player) {
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        BlockPos pos = player.getBlockPos();
        RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();

        // NAMETAG_HIDE flag: when DENIED, nametags should be hidden
        boolean shouldHide = !rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.NAMETAG_HIDE);
        Boolean current = nametagHideState.get(player.getUuid());
        if (current == null || current != shouldHide) {
            nametagHideState.put(player.getUuid(), shouldHide);
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, com.cheeseguard.network.GuiSyncPayload.ID)) {
                try {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        new com.cheeseguard.network.GuiSyncPayload("NAMETAG_ZONE", "{\"hide\":" + shouldHide + "}"));
                } catch (Exception e) {
                    // Silently ignore if packet fails (player disconnecting, etc.)
                }
            }
        }
    }

    // ── Minimap hide check ─────────────────────────────────────────────────

    private void checkMinimapHide(ServerPlayerEntity player) {
        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        BlockPos pos = player.getBlockPos();
        RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();

        boolean shouldHide = !rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.MINIMAP_HIDE);
        Boolean current = minimapHideState.get(player.getUuid());
        if (current == null || current != shouldHide) {
            minimapHideState.put(player.getUuid(), shouldHide);
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, com.cheeseguard.network.GuiSyncPayload.ID)) {
                try {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        new com.cheeseguard.network.GuiSyncPayload("MINIMAP_ZONE", "{\"hide\":" + shouldHide + "}"));
                } catch (Exception e) {
                    // Silently ignore if packet fails
                }
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

            // OP bypass: operators skip advancement/quest requirements (not flag checks)
            boolean isOp = player.hasPermissionLevel(2);

            // Entry: regions in current but not in previous
            for (var region : currentRegions) {
                if (previousRegions.stream().noneMatch(r -> r.getId().equals(region.getId()))) {
                    // Check entry flag
                    if (!rm.isAllowed(player, worldId, current.getX(), current.getY(), current.getZ(), Flag.ENTRY)) {
                        pushBackTranslatable(player, last, "cheeseguard.protection.entry_denied");
                        return;
                    }
                    // Check advancement requirements for entry (OP bypass)
                    if (!isOp) {
                        List<String> requiredAdv = getRequiredAdvancements(region, true);
                        if (!requiredAdv.isEmpty()) {
                            List<String> missing = getMissingAdvancements(player, requiredAdv);
                            if (!missing.isEmpty()) {
                                String msg = region.getAdvancementDeniedMessage();
                                pushBack(player, last, msg);
                                player.sendMessage(Text.translatable("cheeseguard.protection.missing_advancements", String.join(", ", missing))
                                    .formatted(Formatting.RED), false);
                                return;
                            }
                        }
                    }
                    // Check FTB Quest requirements for entry (OP bypass)
                    if (!isOp && ftbQuestsLoaded) {
                        List<String> requiredQuests = getRequiredQuests(region, true);
                        if (!requiredQuests.isEmpty()) {
                            List<String> missingQ = getMissingQuests(player, requiredQuests);
                            if (!missingQ.isEmpty()) {
                                pushBack(player, last, region.getAdvancementDeniedMessage());
                                player.sendMessage(Text.translatable("cheeseguard.protection.missing_quests", String.join(", ", missingQ))
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
                        pushBackTranslatable(player, last, "cheeseguard.protection.exit_denied");
                        return;
                    }
                    // Check advancement requirements for exit (OP bypass)
                    if (!isOp) {
                        List<String> requiredAdv = getRequiredAdvancements(region, false);
                        if (!requiredAdv.isEmpty()) {
                            List<String> missing = getMissingAdvancements(player, requiredAdv);
                            if (!missing.isEmpty()) {
                                String msg = region.getAdvancementDeniedMessage();
                                pushBack(player, last, msg);
                                player.sendMessage(Text.translatable("cheeseguard.protection.missing_advancements", String.join(", ", missing))
                                    .formatted(Formatting.RED), false);
                                return;
                            }
                        }
                    }
                    // Check FTB Quest requirements for exit (OP bypass)
                    if (!isOp && ftbQuestsLoaded) {
                        List<String> requiredQuests = getRequiredQuests(region, false);
                        if (!requiredQuests.isEmpty()) {
                            List<String> missingQ = getMissingQuests(player, requiredQuests);
                            if (!missingQ.isEmpty()) {
                                pushBack(player, last, region.getAdvancementDeniedMessage());
                                player.sendMessage(Text.translatable("cheeseguard.protection.missing_quests", String.join(", ", missingQ))
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
        // Use server source (op-level) but with the player's world context for per-player dimensions
        var source = server.getCommandSource()
            .withWorld(player.getServerWorld())
            .withPosition(player.getPos());
        String name = player.getName().getString();
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) continue;
            String cmd = raw.replace("{player}", name).trim();
            // Strip leading "/" if present — both formats accepted
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            try {
                dispatcher.execute(cmd, source);
            } catch (Exception e) {
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

    private void pushBackTranslatable(ServerPlayerEntity player, BlockPos last, String translationKey) {
        player.teleport(player.getServerWorld(), last.getX() + 0.5, last.getY(), last.getZ() + 0.5,
            java.util.Set.of(), player.getYaw(), player.getPitch());
        player.sendMessage(Text.translatable(translationKey), true);
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
            if (questFile == null) {
                CheeseGuardMod.LOGGER.warn("[CG] FTB ServerQuestFile.INSTANCE is null");
                return requiredQuestIds;
            }

            // Get team data — scan by method name + assignable param type (robust across remappings)
            Object teamData = null;
            for (String methodName : new String[]{"getData", "getOrCreateTeamData"}) {
                for (var method : questFile.getClass().getMethods()) {
                    if (method.getName().equals(methodName) && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isAssignableFrom(player.getClass())) {
                        try {
                            teamData = method.invoke(questFile, player);
                            if (teamData != null) break;
                        } catch (Exception ignored) {}
                    }
                }
                if (teamData != null) break;
            }

            if (teamData == null) {
                CheeseGuardMod.LOGGER.warn("[CG] FTB Quest teamData is null for player {}", player.getName().getString());
                return requiredQuestIds;
            }

            for (String questHexId : requiredQuestIds) {
                try {
                    long questLongId = Long.parseUnsignedLong(questHexId, 16);

                    // Get quest object — try getBase(long), get(long), and scan fallback
                    Object quest = null;
                    for (String methodName : new String[]{"getBase", "get", "getQuestObject"}) {
                        for (var method : questFile.getClass().getMethods()) {
                            if (method.getName().equals(methodName) && method.getParameterCount() == 1
                                    && (method.getParameterTypes()[0] == long.class || method.getParameterTypes()[0] == Long.class)) {
                                try {
                                    quest = method.invoke(questFile, questLongId);
                                    if (quest != null) break;
                                } catch (Exception ignored) {}
                            }
                        }
                        if (quest != null) break;
                    }

                    if (quest == null) {
                        CheeseGuardMod.LOGGER.debug("[CG] FTB Quest object not found for ID {}", questHexId);
                        missing.add(questNames.getOrDefault(questHexId, questHexId));
                        continue;
                    }

                    // Check completion — scan for isCompleted method with assignable param
                    boolean completed = false;
                    for (var method : teamData.getClass().getMethods()) {
                        if (method.getName().equals("isCompleted") && method.getParameterCount() == 1
                                && method.getParameterTypes()[0].isAssignableFrom(quest.getClass())) {
                            try {
                                completed = (boolean) method.invoke(teamData, quest);
                                break;
                            } catch (Exception ignored) {}
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
            CheeseGuardMod.LOGGER.warn("[CG] FTB Quest check failed: {}", e.getMessage());
            for (String qid : requiredQuestIds) {
                missing.add(questNames.getOrDefault(qid, qid));
            }
        }
        return missing;
    }
}

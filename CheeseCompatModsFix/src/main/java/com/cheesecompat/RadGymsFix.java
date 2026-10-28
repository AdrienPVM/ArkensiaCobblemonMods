package com.cheesecompat;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixes for Rad Gyms mod:
 *
 * 1. RESCUE: Teleports players stuck in rad-gyms dimension after crash/restart.
 *    Rad Gyms stores gym instances in-memory (PLAYER_GYMS LinkedHashMap).
 *    On crash, this map is lost → players stuck with no way to leave.
 *
 * 2. THREAD SAFETY: Replaces PLAYER_GYMS LinkedHashMap with ConcurrentHashMap.
 *    Unsynchronized LinkedHashMap can cause infinite loops in HashMap.get()
 *    when accessed concurrently, hanging the server.
 *
 * 3. BADGE TYPE FIX: Tracks the original gym type key per player and restores
 *    the correct GymTemplate before loot distribution. The GymTemplate is a
 *    singleton with static mutable state — if two players enter different gym
 *    types, the template data races and the wrong loot/badge type is given.
 *    We subscribe to Cobblemon's BATTLE_VICTORY event at HIGH priority
 *    (before rad-gyms at NORMAL) to restore the correct template.
 *
 * 4. TRAINER INTERACTION SAFETY: Pre-validates state before player interacts
 *    with a gym Trainer entity. Rad Gyms' Trainer.interactMob() has zero
 *    error handling — if the player has no active GymInstance (e.g., after
 *    disconnect/reconnect in gym dim), it throws NPE (60+ occurrences in logs).
 *    We use UseEntityCallback to block the interaction with a user-friendly message.
 */
public class RadGymsFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");
    private static final String RAD_GYMS_DIM = "rad-gyms:rad-gyms_dim";

    // Tracks original gym type key (e.g., "electric") per player UUID
    private static final ConcurrentHashMap<UUID, String> PLAYER_GYM_TYPES = new ConcurrentHashMap<>();

    public static void register() {
        // 1. Replace PLAYER_GYMS with ConcurrentHashMap at startup
        ServerLifecycleEvents.SERVER_STARTING.register(server -> replacePlayerGymsMap());

        // 2. Rescue players stuck in gym dimension on login
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String dimId = player.getServerWorld().getRegistryKey().getValue().toString();
            if (RAD_GYMS_DIM.equals(dimId)) {
                server.execute(() -> {
                    if (!player.isRemoved()) {
                        rescuePlayer(player, server);
                    }
                });
            }
        });

        // 3. Track gym type when player enters gym dimension
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            String destDim = destination.getRegistryKey().getValue().toString();
            String origDim = origin.getRegistryKey().getValue().toString();

            if (RAD_GYMS_DIM.equals(destDim)) {
                // Player entering gym — capture the current template type
                captureGymType(player.getUuid());
            } else if (RAD_GYMS_DIM.equals(origDim)) {
                // Player leaving gym — clean up
                PLAYER_GYM_TYPES.remove(player.getUuid());
            }
        });

        // 4. Clean up on disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PLAYER_GYM_TYPES.remove(handler.getPlayer().getUuid());
        });

        // 5. Subscribe to Cobblemon battle victory for badge type fix
        ServerLifecycleEvents.SERVER_STARTED.register(server -> subscribeToBattleEvents());

        // 6. Pre-validate trainer interactions to prevent NPE crashes
        registerTrainerInteractionGuard();

        // 7. Periodic purge of orphan trainer entities + chunks when gym dim is empty
        registerOrphanGymPurge();
    }

    // ── 7. Orphan gym purge ────────────────────────────────────────────────────

    /**
     * Périodiquement (toutes les 600 ticks = 30s), si la dimension rad-gyms n'a
     * AUCUN joueur, on discard toutes les entités "Trainer" rad-gyms encore
     * présentes. Ces orphelines accumulent chunks et entity-tracking → c'est ce
     * qui cause les freezes c2me ({@code Entity.move → getEntityCollisions →
     * getChunkBlocking → fastutil infinite loop}) quand un joueur réentre.
     */
    private static void registerOrphanGymPurge() {
        final RegistryKey<World> dimKey = RegistryKey.of(
            RegistryKeys.WORLD,
            Identifier.of("rad-gyms", "rad-gyms_dim")
        );
        LOGGER.info("[CheeseCompat] Rad-gyms orphan trainer purge REGISTERED (scans every 30s when dim is empty)");
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 600 != 0) return; // every 30s
            ServerWorld gymWorld = server.getWorld(dimKey);
            if (gymWorld == null) return;
            int playerCount = gymWorld.getPlayers().size();
            if (playerCount > 0) return;
            try {
                int discarded = 0;
                int scanned = 0;
                // Scan the entire gym world for orphan trainers (small world, OK)
                Box wholeWorld = new Box(
                    -30000000, gymWorld.getBottomY(), -30000000,
                    30000000, gymWorld.getTopY(), 30000000
                );
                for (Entity e : gymWorld.getEntitiesByClass(Entity.class, wholeWorld, ent -> {
                    if (ent == null || ent.isRemoved()) return false;
                    return "lol.gito.radgyms.entity.Trainer".equals(ent.getClass().getName());
                })) {
                    scanned++;
                    e.discard();
                    discarded++;
                }
                if (discarded > 0) {
                    LOGGER.info("[CheeseCompat] Rad-gyms orphan purge — discarded {} trainer entity/entities (dim was empty, scanned={})", discarded, scanned);
                }
            } catch (Throwable t) {
                LOGGER.warn("[CheeseCompat] Rad-gyms orphan trainer purge FAILED: {}", t.toString());
            }
        });
    }

    // ── 1. ConcurrentHashMap replacement ────────────────────────────────────────

    private static void replacePlayerGymsMap() {
        try {
            Class<?> gmClass = Class.forName("lol.gito.radgyms.gym.GymManager");
            Field field = gmClass.getDeclaredField("PLAYER_GYMS");
            field.setAccessible(true);

            // Use Unsafe to write to final static field
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            long offset = unsafe.staticFieldOffset(field);
            @SuppressWarnings("unchecked")
            Map<Object, Object> oldMap = (Map<Object, Object>) unsafe.getObject(gmClass, offset);

            ConcurrentHashMap<Object, Object> newMap = new ConcurrentHashMap<>();
            if (oldMap != null && !oldMap.isEmpty()) {
                newMap.putAll(oldMap);
            }

            unsafe.putObject(gmClass, offset, newMap);
            LOGGER.info("[CheeseCompat] Replaced PLAYER_GYMS with ConcurrentHashMap (thread-safety fix)");
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] Could not replace PLAYER_GYMS: {}", e.getMessage());
        }
    }

    // ── 2. Rescue stuck players ─────────────────────────────────────────────────

    private static void rescuePlayer(ServerPlayerEntity player, MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        BlockPos spawn = overworld.getSpawnPos();

        player.teleport(overworld,
                spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5,
                Set.of(), player.getYaw(), player.getPitch());

        player.sendMessage(
                Text.translatable("cheesecompat.radgyms.rescued").formatted(Formatting.YELLOW), false
        );

        LOGGER.info("[CheeseCompat] Rescued {} from rad-gyms dimension (post-crash recovery)",
                player.getGameProfile().getName());
    }

    // ── 3. Gym type tracking ────────────────────────────────────────────────────

    /**
     * Captures the current GymTemplate.type (static field) when a player enters the gym dimension.
     * At this point, GymManager.initInstance() has already called fromGymDto() which sets the type.
     */
    private static void captureGymType(UUID playerUuid) {
        try {
            Class<?> templateClass = Class.forName("lol.gito.radgyms.gym.GymTemplate");
            Field typeField = templateClass.getDeclaredField("type");
            typeField.setAccessible(true);
            String typeKey = (String) typeField.get(null);
            if (typeKey != null) {
                PLAYER_GYM_TYPES.put(playerUuid, typeKey);
                LOGGER.debug("[CheeseCompat] Captured gym type '{}' for player {}", typeKey, playerUuid);
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] Could not capture gym type: {}", e.getMessage());
        }
    }

    // ── 4. Battle victory — restore correct template ────────────────────────────

    /**
     * Subscribes to Cobblemon's BATTLE_VICTORY event at HIGH priority.
     * Rad Gyms subscribes at NORMAL. Our handler runs first, restoring the correct
     * GymTemplate before rad-gyms reads it for loot distribution.
     */
    private static void subscribeToBattleEvents() {
        try {
            Class<?> eventsClass = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Field victoryField = eventsClass.getField("BATTLE_VICTORY");
            Object battleVictory = victoryField.get(null);

            Class<?> priorityClass = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object highPriority = priorityClass.getField("HIGH").get(null);

            // Find subscribe(Priority, Function1) method
            Method subscribeMethod = null;
            for (Method m : battleVictory.getClass().getMethods()) {
                if ("subscribe".equals(m.getName()) && m.getParameterCount() == 2) {
                    subscribeMethod = m;
                    break;
                }
            }
            if (subscribeMethod == null) {
                LOGGER.warn("[CheeseCompat] Could not find subscribe method on BATTLE_VICTORY");
                return;
            }

            // Subscribe with a Kotlin Function1 lambda
            subscribeMethod.invoke(battleVictory, highPriority,
                    (kotlin.jvm.functions.Function1<Object, kotlin.Unit>) event -> {
                        onBattleVictory(event);
                        return kotlin.Unit.INSTANCE;
                    });

            LOGGER.info("[CheeseCompat] Subscribed to battle victory events (gym badge type fix)");
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] Could not subscribe to battle events: {}", e.getMessage());
        }
    }

    /**
     * Called before rad-gyms' battle handler. Checks if a player in the gym dimension
     * won a battle, and restores the correct GymTemplate for their gym type.
     */
    private static void onBattleVictory(Object event) {
        try {
            // Get winners from the event
            Method getWinners = event.getClass().getMethod("getWinners");
            Iterable<?> winners = (Iterable<?>) getWinners.invoke(event);

            for (Object actor : winners) {
                // Check actor type is PLAYER
                Method getActorType = actor.getClass().getMethod("getType");
                Object actorType = getActorType.invoke(actor);
                if (!"PLAYER".equals(actorType.toString())) continue;

                // Get entity via reflection (PlayerBattleActor.getEntity())
                Method getEntity = null;
                for (Method m : actor.getClass().getMethods()) {
                    if ("getEntity".equals(m.getName()) && m.getParameterCount() == 0) {
                        getEntity = m;
                        break;
                    }
                }
                if (getEntity == null) continue;

                Object entity = getEntity.invoke(actor);
                if (!(entity instanceof ServerPlayerEntity player)) continue;

                // Check if player is in the rad-gyms dimension
                String dim = player.getServerWorld().getRegistryKey().getValue().toString();
                if (!RAD_GYMS_DIM.equals(dim)) continue;

                // Get stored type key for this player
                String typeKey = PLAYER_GYM_TYPES.get(player.getUuid());
                if (typeKey == null) continue;

                // Restore correct template before rad-gyms reads it for loot
                restoreGymTemplate(typeKey);
                return;
            }
        } catch (Exception e) {
            // Silent fail — don't break the battle flow
        }
    }

    // ── 5. Trainer interaction guard ───────────────────────────────────────────

    /**
     * Registers a UseEntityCallback that intercepts player interactions with rad-gyms
     * Trainer entities. If the player has no active GymInstance, the interaction is
     * blocked to prevent the NPE in Trainer.interactMob() (60+ occurrences in prod logs).
     */
    private static void registerTrainerInteractionGuard() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;

            // Only intercept rad-gyms Trainer entities
            if (!"lol.gito.radgyms.entity.Trainer".equals(entity.getClass().getName())) {
                return ActionResult.PASS;
            }

            // Check if player has an active gym instance
            try {
                Class<?> gmClass = Class.forName("lol.gito.radgyms.gym.GymManager");
                Field gymsField = gmClass.getDeclaredField("PLAYER_GYMS");
                gymsField.setAccessible(true);
                Map<?, ?> playerGyms = (Map<?, ?>) gymsField.get(null);

                if (!playerGyms.containsKey(player.getUuid())) {
                    // No gym instance — interaction would NPE
                    if (player instanceof ServerPlayerEntity spe) {
                        spe.sendMessage(
                                Text.translatable("cheesecompat.radgyms.no_gym")
                                        .formatted(Formatting.RED), false);
                    }
                    return ActionResult.FAIL;
                }

                // Also check that the trainer's UUID exists in the gym's NPC list
                Object gymInstance = playerGyms.get(player.getUuid());
                if (gymInstance != null) {
                    Method getNpcList = gymInstance.getClass().getMethod("getNpcList");
                    Iterable<?> npcList = (Iterable<?>) getNpcList.invoke(gymInstance);
                    UUID trainerUuid = entity.getUuid();
                    boolean found = false;
                    for (Object pair : npcList) {
                        Method getFirst = pair.getClass().getMethod("getFirst");
                        UUID npcUuid = (UUID) getFirst.invoke(pair);
                        if (trainerUuid.equals(npcUuid)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        // Trainer not in this player's gym — stale entity
                        if (player instanceof ServerPlayerEntity spe) {
                            spe.sendMessage(
                                    Text.translatable("cheesecompat.radgyms.wrong_trainer")
                                            .formatted(Formatting.RED), false);
                        }
                        return ActionResult.FAIL;
                    }
                }
            } catch (Exception e) {
                // Reflection failed — let interaction proceed, vanilla will catch any NPE
                LOGGER.debug("[CheeseCompat] Trainer guard check failed: {}", e.getMessage());
            }

            return ActionResult.PASS;
        });
    }

    /**
     * Re-invokes GymTemplate.fromGymDto() with the correct GymDTO for the given type key.
     * This restores the singleton's loot tables, structure, etc. to the correct type.
     */
    private static void restoreGymTemplate(String typeKey) {
        try {
            // Get GYM_TEMPLATES map
            Class<?> gmClass = Class.forName("lol.gito.radgyms.gym.GymManager");
            Field templatesField = gmClass.getDeclaredField("GYM_TEMPLATES");
            templatesField.setAccessible(true);
            Map<?, ?> templates = (Map<?, ?>) templatesField.get(null);

            String key = templates.containsKey(typeKey) ? typeKey : "default";
            Object gymDTO = templates.get(key);
            if (gymDTO == null) return;

            // Get GymTemplate.INSTANCE
            Class<?> templateClass = Class.forName("lol.gito.radgyms.gym.GymTemplate");
            Field instanceField = templateClass.getField("INSTANCE");
            Object instance = instanceField.get(null);

            // Find and call fromGymDto(GymDTO, int level, String type)
            // Level=1 is fine — it only affects trainer team generation (already spawned)
            // The loot table list is set from the DTO regardless of level
            for (Method m : templateClass.getMethods()) {
                if ("fromGymDto".equals(m.getName()) && m.getParameterCount() == 3) {
                    m.invoke(instance, gymDTO, 1, typeKey);
                    LOGGER.debug("[CheeseCompat] Restored gym template to type '{}'", typeKey);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] Template restore failed: {}", e.getMessage());
        }
    }
}

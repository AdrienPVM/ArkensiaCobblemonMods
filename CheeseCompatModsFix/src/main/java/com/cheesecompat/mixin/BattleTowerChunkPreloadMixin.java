package com.cheesecompat.mixin;

import battle.tower.battle.BattleTowerManager;
import battle.tower.data.BattleSettings;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Précharge les chunks entre le player et le NPC du Battle Tower avant que
 * createTrainer ne spawn l'entité.
 *
 * Cause du bug "Pokémon avec barre de vie mais entité invisible" :
 *   - Notre BattleArenaPositionMixin met le NPC à anchor + 18 blocs (cf design).
 *   - Le NPC peut donc se retrouver dans un chunk DIFFÉRENT de celui de l'anchor.
 *   - BattleTowerManager.ensureChunkLoaded ne charge QUE le chunk de l'anchor.
 *   - Quand Cobblemon spawn le PokemonEntity du trainer à la position du NPC,
 *     le ClientboundAddEntityPacket part du serveur mais est dropped par
 *     l'entity tracker si le chunk n'est pas dans le tracking-radius du player.
 *   - Résultat : barre de vie reçue via les packets Showdown mais l'entité
 *     n'apparaît jamais visuellement → combat bloqué.
 *
 * Fix : pré-charger un carré 3×3 chunks autour du midpoint player↔NPC.
 * Coût : 1 force-load par création de combat (~50-100ms TPS spike acceptable).
 *
 * Logging (v1.2.39+) :
 *   - INFO : chaque préload avec joueur, positions, et chunks ciblés
 *   - WARN : si le hook est appelé avec un état invalide (player null, etc.)
 *   - ERROR : si le préload throw (filet de sécurité, ne casse pas le combat)
 */
@Mixin(value = BattleTowerManager.class, remap = false)
public class BattleTowerChunkPreloadMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix/BTPreload");

    @Inject(
        method = "createTrainer",
        at = @At("HEAD"),
        require = 0
    )
    private static void cheesecompat$preloadChunks(
        ServerPlayerEntity player,
        BlockPos npcPos,
        float yaw,
        int floor,
        Pokemon[] team,
        BattleSettings settings,
        String name,
        CallbackInfoReturnable<Object> cir
    ) {
        try {
            if (player == null || npcPos == null) {
                LOGGER.warn("[CCMF] BT preload — invalid state (player={}, npcPos={}), skipping",
                        player, npcPos);
                return;
            }
            if (!(player.getWorld() instanceof ServerWorld world)) {
                LOGGER.warn("[CCMF] BT preload — player world is not ServerWorld (player={})",
                        player.getGameProfile().getName());
                return;
            }
            BlockPos playerPos = player.getBlockPos();
            int midX = (npcPos.getX() + playerPos.getX()) >> 1;
            int midZ = (npcPos.getZ() + playerPos.getZ()) >> 1;
            ChunkPos mid = new ChunkPos(midX >> 4, midZ >> 4);
            ChunkPos playerChunk = new ChunkPos(playerPos);
            ChunkPos npcChunk = new ChunkPos(npcPos);
            // Force-load 3x3 chunks centered on midpoint — covers anchor↔NPC span
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getChunk(mid.x + dx, mid.z + dz);
                }
            }
            // Also ensure the NPC chunk specifically is loaded
            world.getChunk(npcPos);
            LOGGER.info("[CCMF] BT preload OK — player={}, floor={}, playerChunk={}, npcChunk={}, midChunk={}, npcPos={}, distance={} blocks",
                    player.getGameProfile().getName(),
                    floor,
                    playerChunk,
                    npcChunk,
                    mid,
                    npcPos.toShortString(),
                    (int) Math.sqrt(playerPos.getSquaredDistance(npcPos)));
        } catch (Throwable t) {
            // Fail silently combat-wise — never break battle tower flow — but log loudly
            LOGGER.error("[CCMF] BT preload THREW (battle will proceed without preload — Pokémon may be invisible): {}",
                    t.toString(), t);
        }
    }
}

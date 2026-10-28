package com.roicheese.eggandcheese.blockentity;

import com.roicheese.eggandcheese.advancement.EggFoundCriterion;
import com.roicheese.eggandcheese.data.CollectibleEntry;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.data.LiveAdminEggIndex;
import com.roicheese.eggandcheese.dna.CollectibleDNA;
import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.ftb.FtbQuestsHook;
import com.roicheese.eggandcheese.item.CollectibleItem;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class AdminCollectibleBlockEntity extends BlockEntity {
    private CollectibleDNA dna = CollectibleDNA.generate();
    private CollectibleType collectibleType;
    private String registryId = "";
    private float scale = 1.0f;
    private int renderDistanceOverride = -1; // -1 = use global, >= 0 = custom distance
    public float yRotation = 0f;

    public AdminCollectibleBlockEntity(BlockPos pos, BlockState state, CollectibleType type) {
        super(ModBlockEntities.ADMIN_COLLECTIBLE, pos, state);
        this.collectibleType = type;
    }

    public static void tick(World world, BlockPos pos, BlockState state, AdminCollectibleBlockEntity be) {
        be.yRotation = (be.yRotation + 0.5f) % 360f;
    }

    public void handlePlayerDiscovery(ServerPlayerEntity player) {
        if (registryId.isEmpty()) return;
        EggRegistryData registry = EggRegistryData.get(player.getServer());
        CollectibleEntry entry = registry.getEntry(registryId).orElse(null);
        if (entry == null) return;

        if (entry.hasDiscovered(player.getUuid())) {
            player.sendMessage(Text.translatable("eggandcheese.already_found"), true);
            return;
        }
        entry.addDiscovery(player.getUuid());
        entry.markDecoReceived(player.getUuid());
        registry.markDirty();

        var reward = CollectibleItem.create(collectibleType, dna);
        if (!player.getInventory().insertStack(reward)) player.dropItem(reward, false);

        EggFoundCriterion.trigger(player, registryId);
        world.playSound(null, pos, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1f, 1.2f);
        player.sendMessage(Text.translatable("eggandcheese.found", registryId), true);

        // Complete linked FTB quest — auto-create if missing (egg placed before FTB was enabled)
        if (registry.isFtbQuestsEnabled() && FtbQuestsHook.isAvailable()) {
            long qid = entry.getFtbQuestId();
            if (qid == 0) {
                qid = FtbQuestsHook.ensureQuestExists(registry.getFtbQuestsChapter(), entry, player.getServer());
                if (qid != 0) {
                    entry.setFtbQuestId(qid);
                    registry.markDirty();
                }
            }
            if (qid != 0) {
                FtbQuestsHook.completeForPlayer(qid, player);
            }
        }

        // WS broadcast: notify dashboard of discovery
        try {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cheesedash")) {
                com.google.gson.JsonObject wsMsg = new com.google.gson.JsonObject();
                wsMsg.addProperty("type", "egg_discovery");
                wsMsg.addProperty("playerName", player.getName().getString());
                wsMsg.addProperty("eggId", registryId);
                wsMsg.addProperty("timestamp", System.currentTimeMillis());
                com.cheesedash.DashboardRegistry.broadcast("eggs", wsMsg.toString());
            }
        } catch (NoClassDefFoundError ignored) {}
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        dna = CollectibleDNA.fromNbt(nbt.getCompound("dna"));
        collectibleType = CollectibleType.fromId(nbt.getString("collectibleType"));
        registryId = nbt.getString("registryId");
        scale = nbt.contains("scale") ? nbt.getFloat("scale") : 1.0f;
        renderDistanceOverride = nbt.contains("renderDistanceOverride") ? nbt.getInt("renderDistanceOverride") : -1;
    }

    @Override
    public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.put("dna", dna.toNbt());
        nbt.putString("collectibleType", collectibleType.getId());
        nbt.putString("registryId", registryId);
        nbt.putFloat("scale", scale);
        nbt.putInt("renderDistanceOverride", renderDistanceOverride);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) {
        return createNbt(registries);
    }

    @Override
    public net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket toUpdatePacket() {
        return net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket.create(this);
    }

    /** Admin right-click → give a copy of the deco collectible egg to the admin. */
    public void giveDecoItemTo(ServerPlayerEntity player) {
        net.minecraft.item.ItemStack decoStack = CollectibleItem.create(collectibleType, dna);
        // Encode the registryId so it can be associated with this specific egg
        if (!registryId.isEmpty()) {
            net.minecraft.nbt.NbtCompound existing = decoStack.get(
                net.minecraft.component.DataComponentTypes.CUSTOM_DATA) != null
                ? decoStack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA).copyNbt()
                : new net.minecraft.nbt.NbtCompound();
            existing.putString("registryId", registryId);
            decoStack.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA,
                net.minecraft.component.type.NbtComponent.of(existing));
        }
        if (!player.getInventory().insertStack(decoStack)) player.dropItem(decoStack, false);
        player.sendMessage(net.minecraft.text.Text.translatable(
            "eggandcheese.deco_given", registryId)
            .formatted(net.minecraft.util.Formatting.GREEN), true);
    }

    /** Returns true if this player has already received the deco egg from this block. */
    public boolean hasPlayerReceivedDeco(java.util.UUID uuid) {
        if (registryId.isEmpty()) return false;
        EggRegistryData registry = world != null && !world.isClient
            ? EggRegistryData.get(((net.minecraft.server.world.ServerWorld) world).getServer()) : null;
        if (registry == null) return false;
        return registry.getEntry(registryId).map(e -> e.hasReceivedDeco(uuid)).orElse(false);
    }

    public CollectibleDNA getDna()             { return dna; }
    public void setDna(CollectibleDNA dna) {
        this.dna = dna;
        markDirty();
        // Force sync to clients so the new texture renders immediately
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }
    public CollectibleType getCollectibleType() { return collectibleType; }
    public String getRegistryId()               { return registryId; }
    public void setRegistryId(String id) {
        String old = this.registryId;
        this.registryId = id == null ? "" : id;
        markDirty();
        // Maintain the live index of LOADED admin egg blocks (server-side only).
        // This backs the duplicate-ID reconciliation (one physical block per registryId).
        if (world != null && !world.isClient) {
            String dim = world.getRegistryKey().getValue().toString();
            if (old != null && !old.isEmpty() && !old.equals(this.registryId)) LiveAdminEggIndex.remove(old, dim, pos);
            if (!this.registryId.isEmpty()) LiveAdminEggIndex.add(this.registryId, dim, pos);
        }
    }
    public float getScale()                     { return scale; }
    public void setScale(float scale) {
        this.scale = scale;
        markDirty();
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }
    public int getRenderDistanceOverride()      { return renderDistanceOverride; }
    public void setRenderDistanceOverride(int v) {
        this.renderDistanceOverride = v;
        markDirty();
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }
}

package com.cheeseguard.mixin;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("worlds")
    Map<RegistryKey<World>, ServerWorld> cg_getWorlds();

    @Accessor("session")
    LevelStorage.Session cg_getSession();
}

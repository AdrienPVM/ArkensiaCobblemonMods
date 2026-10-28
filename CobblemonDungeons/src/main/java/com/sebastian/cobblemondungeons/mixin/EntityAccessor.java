package com.sebastian.cobblemondungeons.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("dimensions")
    EntityDimensions getDimensions();

    @Accessor("dimensions")
    void setDimensions(EntityDimensions dimensions);

    @Invoker("calculateDimensions")
    void callCalculateDimensions();
}

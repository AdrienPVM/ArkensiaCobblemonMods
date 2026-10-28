package com.cheesecompat.mixin.client;

import journeymap.client.model.entity.EntityHelper;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the package-private {@code EntityHelper.IconData.icon} field.
 * Needed because we want to post-fix the icon path for Seavlian fakemons
 * after JourneyMap has built the IconData.
 */
@Mixin(value = EntityHelper.IconData.class, remap = false)
public interface IconDataAccessor {

    @Accessor("icon")
    Identifier cheesecompat$getIcon();

    @Accessor("icon")
    void cheesecompat$setIcon(Identifier icon);
}

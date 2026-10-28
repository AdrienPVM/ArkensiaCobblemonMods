package com.cheesefixquests.mixin;

import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.util.QuestKey;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TeamData.class, remap = false)
public interface TeamDataAccessor {

    @Accessor("taskProgress")
    Long2LongMap cheesefixquests$getTaskProgress();

    @Accessor("started")
    Long2LongMap cheesefixquests$getStarted();

    @Accessor("completed")
    Long2LongMap cheesefixquests$getCompleted();

    @Accessor("claimedRewards")
    Object2LongMap<QuestKey> cheesefixquests$getClaimedRewards();
}

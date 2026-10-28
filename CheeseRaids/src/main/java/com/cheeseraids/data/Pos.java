package com.cheeseraids.data;

import net.minecraft.util.math.BlockPos;

/** A world-qualified position (Gson-friendly, public fields). */
public class Pos {
    public String world = "minecraft:overworld";
    public double x = 0.5, y = 65, z = 0.5;
    public float yaw = 0, pitch = 0;

    public Pos() {}
    public Pos(String world, double x, double y, double z) {
        this.world = world; this.x = x; this.y = y; this.z = z;
    }

    public BlockPos blockPos() {
        return BlockPos.ofFloored(x, y, z);
    }

    public static Pos of(String world, BlockPos p, float yaw, float pitch) {
        Pos pos = new Pos(world, p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        pos.yaw = yaw; pos.pitch = pitch;
        return pos;
    }
}

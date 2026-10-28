package com.cheesecompat.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Optional;

/**
 * Detects a rectangular frame of CobbleSafari portal frame blocks and fills
 * the interior with portal blocks. Inspired by vanilla NetherPortal/AreaHelper.
 */
public class CobbleSafariPortalHelper {

    private static final int MIN_WIDTH = 2;
    private static final int MAX_WIDTH = 21;
    private static final int MIN_HEIGHT = 3;
    private static final int MAX_HEIGHT = 21;

    private final World world;
    private final Direction.Axis axis;
    private final Direction rightDir;
    private final Direction leftDir;
    private BlockPos bottomLeft;
    private int width;
    private int height;

    private CobbleSafariPortalHelper(World world, BlockPos origin, Direction.Axis axis) {
        this.world = world;
        this.axis = axis;
        if (axis == Direction.Axis.X) {
            this.rightDir = Direction.EAST;
            this.leftDir = Direction.WEST;
        } else {
            this.rightDir = Direction.SOUTH;
            this.leftDir = Direction.NORTH;
        }
        this.bottomLeft = findBottomLeft(origin);
        if (this.bottomLeft != null) {
            this.width = calculateWidth();
            if (this.width >= MIN_WIDTH) {
                this.height = calculateHeight();
            }
        }
    }

    /**
     * Try to find a valid portal at the given position. Checks both axes.
     */
    public static Optional<CobbleSafariPortalHelper> getNewPortal(World world, BlockPos pos) {
        CobbleSafariPortalHelper helperX = new CobbleSafariPortalHelper(world, pos, Direction.Axis.X);
        if (helperX.isValid()) return Optional.of(helperX);

        CobbleSafariPortalHelper helperZ = new CobbleSafariPortalHelper(world, pos, Direction.Axis.Z);
        if (helperZ.isValid()) return Optional.of(helperZ);

        return Optional.empty();
    }

    public boolean isValid() {
        return bottomLeft != null && width >= MIN_WIDTH && width <= MAX_WIDTH
            && height >= MIN_HEIGHT && height <= MAX_HEIGHT;
    }

    /**
     * Fill the interior of the portal frame with portal blocks.
     */
    public void createPortal() {
        Block portalBlock = CobbleSafariBlocks.COBBLESAFARI_PORTAL;
        BlockState portalState = portalBlock.getDefaultState()
            .with(Properties.AXIS, this.axis);

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                BlockPos pos = bottomLeft.offset(rightDir, x).up(y);
                world.setBlockState(pos, portalState, Block.NOTIFY_ALL);
            }
        }
    }

    private BlockPos findBottomLeft(BlockPos origin) {
        // Walk left to find the left edge
        BlockPos pos = origin;
        for (int i = 0; i < MAX_WIDTH; i++) {
            BlockPos next = pos.offset(leftDir);
            if (isFrame(world.getBlockState(next))) {
                // Hit a frame block — this is the left edge
                // Walk down to find the bottom
                BlockPos bottomPos = pos;
                for (int j = 0; j < MAX_HEIGHT; j++) {
                    BlockPos below = bottomPos.down();
                    if (isFrame(world.getBlockState(below))) {
                        return bottomPos;
                    }
                    if (!isInsidePortal(world.getBlockState(below))) {
                        return null;
                    }
                    bottomPos = below;
                }
                return null;
            }
            if (!isInsidePortal(world.getBlockState(next))) {
                return null;
            }
            pos = next;
        }
        return null;
    }

    private int calculateWidth() {
        for (int i = 0; i < MAX_WIDTH + 1; i++) {
            BlockPos pos = bottomLeft.offset(rightDir, i);
            BlockState state = world.getBlockState(pos);
            if (isFrame(state)) {
                return i; // hit right wall
            }
            if (!isInsidePortal(state)) {
                return 0;
            }
            // Check bottom frame
            if (!isFrame(world.getBlockState(pos.down()))) {
                return 0;
            }
        }
        return 0;
    }

    private int calculateHeight() {
        for (int y = 0; y < MAX_HEIGHT + 1; y++) {
            // Check entire row at height y
            boolean allClear = true;
            for (int x = 0; x < width; x++) {
                BlockPos pos = bottomLeft.offset(rightDir, x).up(y);
                BlockState state = world.getBlockState(pos);
                if (isFrame(state)) {
                    if (x == 0) {
                        // Hit top — check the entire top row is frame
                        for (int cx = 0; cx < width; cx++) {
                            if (!isFrame(world.getBlockState(bottomLeft.offset(rightDir, cx).up(y)))) {
                                return 0;
                            }
                        }
                        return y;
                    }
                    return 0;
                }
                if (!isInsidePortal(state)) {
                    return 0;
                }
            }
            // Check left and right wall at this height
            if (!isFrame(world.getBlockState(bottomLeft.offset(leftDir).up(y)))) {
                return 0;
            }
            if (!isFrame(world.getBlockState(bottomLeft.offset(rightDir, width).up(y)))) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean isFrame(BlockState state) {
        return state.isOf(CobbleSafariBlocks.COBBLESAFARI_PORTAL_FRAME);
    }

    private static boolean isInsidePortal(BlockState state) {
        return state.isAir() || state.isOf(CobbleSafariBlocks.COBBLESAFARI_PORTAL);
    }
}

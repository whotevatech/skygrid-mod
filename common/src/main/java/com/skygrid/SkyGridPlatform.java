package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places the starter platform near the top of the grid and pins world spawn to
 * it.
 *
 * Why near the top rather than a fixed height: a player arriving in the middle
 * of the grid is boxed in on all six sides and has to mine out before seeing
 * anything. From up here they get the view, and every direction of travel is
 * downward into the grid, which is where the game is.
 *
 * Why one layer DOWN from the very top: the topmost layer leaves only three
 * blocks between the pad and the build limit, which is not enough to build
 * anything at spawn. Dropping a single grid layer costs nothing and turns that
 * into seven.
 *
 * Runs post-worldgen against a real ServerLevel, so unlike the chunk generator it
 * is not confined to a single chunk and can build wherever vanilla put spawn.
 *
 * 1.21.1 uses the simple spawn API: getSharedSpawnPos() / setDefaultSpawnPos().
 * (On 1.21.11 this became a LevelData.RespawnData record — see the
 * multiloader-1.21.11 branch.)
 */
public final class SkyGridPlatform {

    /** 4 gives a 9x9 pad — room for several players to land without crowding. */
    private static final int RADIUS = 4;

    /**
     * Blocks of clear air required above the floor. This one constant does three
     * jobs, which is why changing it moves the whole platform:
     *
     *   1. topGridY() steps DOWN a grid layer until floor + HEADROOM fits under
     *      the build limit. At 5 the top layer (316) no longer fits, so the pad
     *      lands on the second layer (312) with seven blocks of space above it.
     *   2. It is how far up the build loop clears, so it also deletes the grid
     *      layer that would otherwise hang four blocks over the pad.
     *   3. It guards the setBlock loop against the build ceiling.
     *
     * Drop it back to 3 to sit on the very top layer again.
     */
    private static final int HEADROOM = 5;

    /**
     * Floor is obsidian so the pad survives creepers and cannot be lost by
     * accident. The lip is glass on both courses: it still blocks the login
     * shove, but a player with no tools at all can clear it by hand instead of
     * being fenced in by a block that needs a diamond pickaxe.
     */
    private static final BlockState FLOOR = Blocks.OBSIDIAN.defaultBlockState();
    private static final BlockState WALL  = Blocks.GLASS.defaultBlockState();
    private static final BlockState RAIL  = Blocks.GLASS.defaultBlockState();
    private static final BlockState AIR   = Blocks.AIR.defaultBlockState();

    private SkyGridPlatform() {}

    public static void placeIfNeeded(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        if (!(level.getChunkSource().getGenerator() instanceof SkyGridChunkGenerator gen)) return;

        BlockPos spawn  = level.getSharedSpawnPos();
        int floorY      = gen.topGridY(level, HEADROOM);
        BlockPos centre = new BlockPos(spawn.getX(), floorY, spawn.getZ());
        BlockPos stand  = centre.above();

        if (alreadyBuilt(level, centre)) return;

        SkyGridMod.LOGGER.info("Placing SkyGrid starter platform at {}", centre);

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                boolean edge = (Math.abs(x) == RADIUS || Math.abs(z) == RADIUS);

                level.setBlock(centre.offset(x, 0, z), FLOOR, Block.UPDATE_CLIENTS);

                // Clear headroom and raise the lip. The lip is for multiplayer:
                // several players logging in at once share one spawn block and
                // are shoved apart by entity collision. On an open pad this high
                // that is a void death before anyone has touched a key.
                for (int dy = 1; dy <= HEADROOM; dy++) {
                    if (floorY + dy >= level.getMaxBuildHeight()) break;
                    BlockState fill = edge && dy == 1 ? WALL
                                    : edge && dy == 2 ? RAIL
                                    : AIR;
                    level.setBlock(centre.offset(x, dy, z), fill, Block.UPDATE_CLIENTS);
                }
            }
        }

        level.setDefaultSpawnPos(stand, 0.0F);
        SkyGridMod.LOGGER.info("Starter platform placed. Spawn set to {}", stand);
    }

    /**
     * Have we built here before?
     *
     * The obvious tests both fail on a grid world. Checking whether the centre
     * block is air is what the previous version did, and it meant the platform
     * never got built once: the centre sits ON a grid layer, so a grid block is
     * nearly always already there and the check bailed every time. Checking the
     * spawn point is no better, because vanilla's own spawn search lands on the
     * topmost grid block by itself — the exact spot the pad would occupy.
     *
     * So test the glass rail on the corners instead. It sits at floorY+2, which
     * is not a grid layer at the default spacing of 4, and the generator only
     * ever writes to grid layers — so nothing natural can put a block there. At
     * a spacing of 1 or 2 that guarantee weakens, which is why both opposite
     * corners are checked rather than one.
     */
    private static boolean alreadyBuilt(ServerLevel level, BlockPos centre) {
        return level.getBlockState(centre.offset( RADIUS, 2,  RADIUS)).is(Blocks.GLASS)
            && level.getBlockState(centre.offset(-RADIUS, 2, -RADIUS)).is(Blocks.GLASS);
    }
}

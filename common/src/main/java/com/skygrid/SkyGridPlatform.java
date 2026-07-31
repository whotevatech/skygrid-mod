package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;

/**
 * Places the starter platform on the TOP layer of the grid and pins world spawn
 * to it.
 *
 * Why the top rather than a fixed height: a player arriving in the middle of the
 * grid is boxed in on all six sides and has to mine out before seeing anything.
 * From the top they get the view, and every direction of travel is downward into
 * the grid, which is where the game is.
 *
 * Runs post-worldgen against a real ServerLevel, so unlike the chunk generator it
 * is not confined to a single chunk and can build wherever vanilla put spawn.
 *
 * Note on 1.21.11: getSharedSpawnPos()/setDefaultSpawnPos() no longer exist.
 * Spawn is now a LevelData.RespawnData record (GlobalPos + yaw + pitch), read
 * and written via Level.getRespawnData() / setRespawnData(). The 1.21.1 branch
 * still uses the older pair.
 */
public final class SkyGridPlatform {

    /** 4 gives a 9x9 pad — room for several players to land without crowding. */
    private static final int RADIUS = 4;

    /** Floor, plus two blocks of lip, must fit under the build limit. */
    private static final int HEADROOM = 3;

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

        LevelData.RespawnData respawn = level.getRespawnData();
        BlockPos spawn  = respawn.globalPos().pos();
        int floorY      = gen.topGridY(level, HEADROOM);
        BlockPos centre = new BlockPos(spawn.getX(), floorY, spawn.getZ());
        BlockPos stand  = centre.above();

        if (alreadyBuilt(level, centre)) return;

        SkyGridMod.LOGGER.info("Placing SkyGrid starter platform at {}", centre);

        int maxYExclusive = level.getMinY() + level.getHeight();

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                boolean edge = (Math.abs(x) == RADIUS || Math.abs(z) == RADIUS);

                level.setBlock(centre.offset(x, 0, z), FLOOR, Block.UPDATE_CLIENTS);

                // Clear headroom and raise the lip. The lip is for multiplayer:
                // several players logging in at once share one spawn block and
                // are shoved apart by entity collision. On an open pad this high
                // that is a void death before anyone has touched a key.
                for (int dy = 1; dy <= HEADROOM; dy++) {
                    if (floorY + dy >= maxYExclusive) break;
                    BlockState fill = edge && dy == 1 ? WALL
                                    : edge && dy == 2 ? RAIL
                                    : AIR;
                    level.setBlock(centre.offset(x, dy, z), fill, Block.UPDATE_CLIENTS);
                }
            }
        }

        level.setRespawnData(LevelData.RespawnData.of(
            Level.OVERWORLD, stand, respawn.yaw(), respawn.pitch()));

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

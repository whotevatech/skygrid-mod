package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Places a 5x5 starter platform at spawn so players don't fall into the void.
 *
 * 1.21.1 uses the simple spawn API: getSharedSpawnPos() / setDefaultSpawnPos().
 * (On 1.21.11 this became a LevelData.RespawnData record — see the
 * multiloader-1.21.11 branch.)
 */
public final class SkyGridPlatform {

    private static final int RADIUS = 2;
    private static final int PLATFORM_Y = 64;

    private SkyGridPlatform() {}

    public static void placeIfNeeded(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        if (!(level.getChunkSource().getGenerator() instanceof SkyGridChunkGenerator)) return;

        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos centre = new BlockPos(spawn.getX(), PLATFORM_Y, spawn.getZ());

        // Already placed if something is already sitting at the centre.
        if (!level.getBlockState(centre).isAir()) return;

        SkyGridMod.LOGGER.info("Placing SkyGrid starter platform at {}", centre);

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                level.setBlock(centre.offset(x, 0, z),
                    Blocks.OAK_PLANKS.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
            }
        }

        BlockPos standingOn = centre.above();
        level.setDefaultSpawnPos(standingOn, 0.0F);

        SkyGridMod.LOGGER.info("Starter platform placed. Spawn set to {}", standingOn);
    }
}

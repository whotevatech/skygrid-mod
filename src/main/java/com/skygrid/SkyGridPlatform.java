package com.skygrid;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Places a small starter platform at the world spawn point the first time
 * a Sky Grid world is loaded. Without it, players would spawn in mid-air
 * and immediately fall into the void.
 *
 * The platform is 5x5 oak planks. It is only placed once — detected by
 * checking whether a non-air block already exists at the spawn centre.
 */
public class SkyGridPlatform {

    /** Size of the platform on each side of centre (2 = 5x5). */
    private static final int RADIUS = 2;

    /** Y level for the platform. */
    private static final int PLATFORM_Y = 64;

    public static void placeIfNeeded(ServerWorld world) {
        // Only place a platform in the overworld
        if (world.getRegistryKey() != World.OVERWORLD) return;

        // Only place in a Sky Grid world
        if (!(world.getChunkManager().getChunkGenerator() instanceof
                com.skygrid.world.SkyGridChunkGenerator)) return;

        BlockPos spawnPos = world.getSpawnPos();
        BlockPos centre = new BlockPos(spawnPos.getX(), PLATFORM_Y, spawnPos.getZ());

        // Already placed if a non-air block exists at centre
        if (!world.getBlockState(centre).isAir()) {
            return;
        }

        SkyGridMod.LOGGER.info("Placing SkyGrid starter platform at {}", centre);

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                world.setBlockState(
                    centre.add(x, 0, z),
                    Blocks.OAK_PLANKS.getDefaultState(),
                    net.minecraft.block.Block.NOTIFY_LISTENERS
                );
            }
        }

        // Move the world spawn on top of the platform so players respawn safely
        world.setSpawnPos(centre.up(), 0f);
        SkyGridMod.LOGGER.info("Starter platform placed. Spawn set to {}", centre.up());
    }
}

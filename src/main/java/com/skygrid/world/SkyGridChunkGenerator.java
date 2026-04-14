package com.skygrid.world;

import com.skygrid.SkyGridConfig;
import com.skygrid.SkyGridMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.*;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.loot.LootTables;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * SkyGridChunkGenerator — places blocks on a 3D grid with configurable spacing.
 *
 * Grid positions: every block where (x % spacing == 0) AND (y % spacing == 0) AND (z % spacing == 0)
 * Each grid position has a small chance to be a mob spawner or a loot chest,
 * otherwise it picks randomly from a large pool of Overworld blocks.
 */
public class SkyGridChunkGenerator extends ChunkGenerator {

    // -------------------------------------------------------------------------
    // Codec — lets Minecraft save/load this generator to disk
    // -------------------------------------------------------------------------
    public static final MapCodec<SkyGridChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(gen -> gen.biomeSource),
            Codec.INT.optionalFieldOf("grid_spacing", 4).forGetter(gen -> gen.gridSpacing)
        ).apply(instance, SkyGridChunkGenerator::new)
    );

    // -------------------------------------------------------------------------
    // Block pool — built dynamically at runtime from ALL registered blocks,
    // including blocks added by other installed mods.
    // -------------------------------------------------------------------------

    // Blocks that should never appear in the grid
    private static final Set<Block> EXCLUDED_BLOCKS = Set.of(
        Blocks.AIR, Blocks.VOID_AIR, Blocks.CAVE_AIR,
        Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_VOID,
        Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW,
        Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_GATEWAY,
        Blocks.NETHER_PORTAL, Blocks.MOVING_PISTON,
        Blocks.SPAWNER, Blocks.CHEST  // handled separately with special logic
    );

    // Lazily built on first world generation — captures all mod blocks automatically
    private static volatile BlockState[] dynamicBlockPool = null;

    /** Public accessor for the debug command. */
    public static BlockState[] getPublicBlockPool() {
        return dynamicBlockPool;
    }

    private static BlockState[] getBlockPool() {
        if (dynamicBlockPool == null) {
            synchronized (SkyGridChunkGenerator.class) {
                if (dynamicBlockPool == null) {
                    dynamicBlockPool = buildBlockPool();
                }
            }
        }
        return dynamicBlockPool;
    }

    private static BlockState[] buildBlockPool() {
        List<BlockState> pool = new ArrayList<>();
        SkyGridConfig config = SkyGridConfig.get();

        for (Block block : Registries.BLOCK) {
            // Skip always-excluded technical/special blocks
            if (EXCLUDED_BLOCKS.contains(block)) continue;

            BlockState state = block.getDefaultState();
            if (state.isAir()) continue;

            // Check against the player-configurable block list
            String blockId = Registries.BLOCK.getId(block).toString();
            if (!config.isAllowed(blockId)) continue;

            pool.add(state);
        }

        SkyGridMod.LOGGER.info("SkyGrid block pool built: {}/{} blocks allowed (mode: {}).",
            pool.size(), Registries.BLOCK.size(), config.getMode());
        return pool.toArray(new BlockState[0]);
    }

    // -------------------------------------------------------------------------
    // Mob types that can appear in spawners
    // -------------------------------------------------------------------------
    private static final EntityType<?>[] SPAWNER_MOBS = {
        EntityType.ZOMBIE,
        EntityType.SKELETON,
        EntityType.SPIDER,
        EntityType.CAVE_SPIDER,
        EntityType.CREEPER,
        EntityType.ENDERMAN,
        EntityType.WITCH,
        EntityType.BLAZE,
        EntityType.SLIME,
        EntityType.PHANTOM,
        EntityType.HUSK,
        EntityType.STRAY,
        EntityType.DROWNED,
        EntityType.SILVERFISH,
    };

    private final int gridSpacing;

    public SkyGridChunkGenerator(BiomeSource biomeSource, int gridSpacing) {
        super(biomeSource);
        this.gridSpacing = gridSpacing;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    // -------------------------------------------------------------------------
    // Main generation — place blocks at each grid position
    // -------------------------------------------------------------------------
    @Override
    public CompletableFuture<Chunk> populateNoise(
            Blender blender,
            NoiseConfig noiseConfig,
            StructureAccessor structureAccessor,
            Chunk chunk) {

        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int minY   = chunk.getBottomY();
        int maxY   = chunk.getTopY();

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = minY; y < maxY; y++) {

                    // Only place a block if this is a grid intersection
                    if (x % gridSpacing == 0 && y % gridSpacing == 0 && z % gridSpacing == 0) {
                        mutablePos.set(x, y, z);

                        // Deterministic random per position (same world = same grid every time)
                        Random rand = new Random(hashPos(x, y, z));
                        double roll = rand.nextDouble();

                        BlockState state;
                        if (roll < 0.008) {
                            // ~0.8%: mob spawner
                            state = Blocks.SPAWNER.getDefaultState();
                        } else if (roll < 0.022) {
                            // ~1.4%: loot chest
                            state = Blocks.CHEST.getDefaultState();
                        } else {
                            // Everything else: pick a random block from the pool
                            // (includes blocks from all installed mods)
                            BlockState[] pool = getBlockPool();
                            state = pool[rand.nextInt(pool.length)];
                        }

                        // Leaves placed during world gen must be persistent so they don't decay
                        if (state.getBlock() instanceof LeavesBlock) {
                            state = state.with(LeavesBlock.PERSISTENT, true);
                        }

                        // Saplings: place dirt at the grid position, sapling on top
                        if (isSapling(state) && y + 1 < maxY) {
                            chunk.setBlockState(mutablePos, Blocks.DIRT.getDefaultState(), false);
                            mutablePos.set(x, y + 1, z);
                            chunk.setBlockState(mutablePos, state, false);
                        // MA seeds: place farmland at the grid position, seed on top
                        } else if (needsFarmland(state) && y + 1 < maxY) {
                            chunk.setBlockState(mutablePos, Blocks.FARMLAND.getDefaultState(), false);
                            mutablePos.set(x, y + 1, z);
                            chunk.setBlockState(mutablePos, state, false);
                        } else {
                            chunk.setBlockState(mutablePos, state, false);
                        }
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // -------------------------------------------------------------------------
    // Entity population — configure spawners & chest loot tables
    // -------------------------------------------------------------------------
    @Override
    public void populateEntities(ChunkRegion region) {
        ChunkPos chunkPos = region.getCenterPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = region.getBottomY(); y < region.getTopY(); y++) {

                    if (x % gridSpacing == 0 && y % gridSpacing == 0 && z % gridSpacing == 0) {
                        pos.set(x, y, z);
                        BlockState state = region.getBlockState(pos);

                        if (state.isOf(Blocks.SPAWNER)) {
                            // Assign a random mob type to this spawner
                            if (region.getBlockEntity(pos) instanceof MobSpawnerBlockEntity spawner) {
                                Random rand = new Random(hashPos(x, y, z) + 999L);
                                EntityType<?> mob = SPAWNER_MOBS[rand.nextInt(SPAWNER_MOBS.length)];
                                spawner.setEntityType(mob, region.getRandom());
                            }

                        } else if (state.isOf(Blocks.CHEST)) {
                            // Assign dungeon loot to this chest
                            if (region.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                                // Pick one of several loot tables for variety
                                Random rand = new Random(hashPos(x, y, z) + 777L);
                                RegistryKey<net.minecraft.loot.LootTable> lootTable = pickLootTable(rand);
                                chest.setLootTable(lootTable, hashPos(x, y, z));
                            }
                        }
                    }
                }
            }
        }
    }

    /** Choose a loot table for a chest based on random roll. */
    private RegistryKey<net.minecraft.loot.LootTable> pickLootTable(Random rand) {
        int choice = rand.nextInt(5);
        return switch (choice) {
            case 0  -> LootTables.SIMPLE_DUNGEON_CHEST;
            case 1  -> LootTables.ABANDONED_MINESHAFT_CHEST;
            case 2  -> LootTables.STRONGHOLD_LIBRARY_CHEST;
            case 3  -> LootTables.JUNGLE_TEMPLE_CHEST;
            default -> LootTables.DESERT_PYRAMID_CHEST;
        };
    }

    // -------------------------------------------------------------------------
    // Required overrides — Sky Grid has no surface or noise pass
    // -------------------------------------------------------------------------

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures,
                             NoiseConfig noiseConfig, Chunk chunk) {
        // Sky Grid: blocks are already placed in populateNoise — nothing more to do here.
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                      BiomeAccess biomeAccess, StructureAccessor structureAccessor,
                      Chunk chunk, GenerationStep.Carver carverStep) {
        // Sky Grid: no cave carving — everything is already floating in the void.
    }

    @Override
    public int getSeaLevel() {
        return 63; // Standard Minecraft sea level
    }

    @Override
    public int getWorldHeight() {
        return 384; // Standard overworld height (-64 to 320)
    }

    @Override
    public int getMinimumY() {
        return -64; // Standard overworld minimum Y
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap,
                         HeightLimitView world, NoiseConfig noiseConfig) {
        return world.getBottomY(); // No solid surface, return minimum
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z,
                                               HeightLimitView world,
                                               NoiseConfig noiseConfig) {
        // Return an all-air column (the grid handles actual block placement)
        BlockState[] states = new BlockState[world.getHeight()];
        Arrays.fill(states, Blocks.AIR.getDefaultState());
        return new VerticalBlockSample(world.getBottomY(), states);
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("Sky Grid | Spacing: " + gridSpacing + " | Pos: " + pos.toShortString());
    }

    // -------------------------------------------------------------------------
    // Helper — check if a block is a sapling or plant that needs dirt below it
    // -------------------------------------------------------------------------
    private static boolean isSapling(BlockState state) {
        return state.getBlock() instanceof SaplingBlock
            || state.isOf(Blocks.BAMBOO)
            || state.isOf(Blocks.AZALEA)
            || state.isOf(Blocks.FLOWERING_AZALEA)
            || state.isOf(Blocks.MANGROVE_PROPAGULE);
    }

    /** Returns true for Mystical Agriculture seeds, which need farmland placed below them. */
    private static boolean needsFarmland(BlockState state) {
        net.minecraft.util.Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id.getNamespace().equals("mysticalagriculture") && id.getPath().endsWith("_seeds");
    }

    // -------------------------------------------------------------------------
    // Helper — stable hash for a world position
    // -------------------------------------------------------------------------
    private long hashPos(int x, int y, int z) {
        return x * 341873128712L + y * 132897987541L + z * 4392818741L ^ 0xDEADBEEFL;
    }
}
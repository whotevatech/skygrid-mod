package com.skygrid.world;

import com.skygrid.SkyGridConfig;
import com.skygrid.SkyGridMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.*;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.loot.LootTables;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SkyGridChunkGenerator extends ChunkGenerator {

    // -------------------------------------------------------------------------
    // Codec — includes dimension so the generator is saved/loaded correctly
    // -------------------------------------------------------------------------
    public static final MapCodec<SkyGridChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(gen -> gen.biomeSource),
            Codec.INT.optionalFieldOf("grid_spacing", 4).forGetter(gen -> gen.gridSpacing),
            Codec.STRING.optionalFieldOf("dimension", "overworld").forGetter(gen -> gen.dimension)
        ).apply(instance, SkyGridChunkGenerator::new)
    );

    // -------------------------------------------------------------------------
    // Always-excluded technical blocks
    // -------------------------------------------------------------------------
    private static final Set<Block> EXCLUDED_BLOCKS = Set.of(
        Blocks.AIR, Blocks.VOID_AIR, Blocks.CAVE_AIR,
        Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_VOID,
        Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
        Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW,
        Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_GATEWAY,
        Blocks.NETHER_PORTAL, Blocks.MOVING_PISTON,
        Blocks.SPAWNER, Blocks.CHEST
    );

    // -------------------------------------------------------------------------
    // Per-dimension block pools — built lazily on first generation
    // -------------------------------------------------------------------------
    private static final Map<String, BlockState[]> DIMENSION_POOLS = new ConcurrentHashMap<>();

    public static BlockState[] getPublicBlockPool(String dimension) {
        return DIMENSION_POOLS.get(dimension);
    }

    /** Legacy accessor for the debug command — returns overworld pool. */
    public static BlockState[] getPublicBlockPool() {
        return DIMENSION_POOLS.get("overworld");
    }

    /** Clears all cached pools so they rebuild on next generation — called by /skygrid reload. */
    public static void clearPools() {
        DIMENSION_POOLS.clear();
    }

    private static BlockState[] getBlockPool(String dimension) {
        return DIMENSION_POOLS.computeIfAbsent(dimension, SkyGridChunkGenerator::buildBlockPool);
    }

    private static BlockState[] buildBlockPool(String dimension) {
        List<BlockState> pool = new ArrayList<>();
        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);

        for (Block block : Registries.BLOCK) {
            if (EXCLUDED_BLOCKS.contains(block)) continue;
            BlockState state = block.getDefaultState();
            if (state.isAir()) continue;
            String blockId = Registries.BLOCK.getId(block).toString();
            if (!config.isAllowed(blockId)) continue;
            pool.add(state);
        }

        SkyGridMod.LOGGER.info("SkyGrid [{}] block pool: {}/{} blocks (mode: {}).",
            dimension, pool.size(), Registries.BLOCK.size(), config.getMode());
        return pool.toArray(new BlockState[0]);
    }

    // -------------------------------------------------------------------------
    // Dimension-specific spawner mobs
    // -------------------------------------------------------------------------
    private static final EntityType<?>[] OVERWORLD_MOBS = {
        EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CAVE_SPIDER,
        EntityType.CREEPER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.SLIME,
        EntityType.PHANTOM, EntityType.HUSK, EntityType.STRAY, EntityType.DROWNED,
        EntityType.SILVERFISH,
    };

    private static final EntityType<?>[] NETHER_MOBS = {
        EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.ZOMBIFIED_PIGLIN,
        EntityType.MAGMA_CUBE, EntityType.HOGLIN, EntityType.PIGLIN_BRUTE,
        EntityType.STRIDER, EntityType.GHAST,
    };

    private static final EntityType<?>[] END_MOBS = {
        EntityType.ENDERMAN, EntityType.ENDERMAN, EntityType.ENDERMAN, // weighted — more endermen
        EntityType.ENDERMITE, EntityType.SHULKER,
    };

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private final int gridSpacing;
    private final String dimension;

    public SkyGridChunkGenerator(BiomeSource biomeSource, int gridSpacing, String dimension) {
        super(biomeSource);
        this.gridSpacing = gridSpacing;
        this.dimension   = dimension;
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
            Blender blender, NoiseConfig noiseConfig,
            StructureAccessor structureAccessor, Chunk chunk) {

        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int minY   = chunk.getBottomY();
        int maxY   = chunk.getTopY();

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        for (int x = startX; x < startX + 16; x++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    if (x % gridSpacing != 0 || y % gridSpacing != 0 || z % gridSpacing != 0) continue;

                    mutablePos.set(x, y, z);
                    Random rand = new Random(hashPos(x, y, z));
                    double roll = rand.nextDouble();

                    BlockState state;
                    if (roll < 0.008) {
                        state = Blocks.SPAWNER.getDefaultState();
                    } else if (roll < 0.022) {
                        state = Blocks.CHEST.getDefaultState();
                    } else {
                        BlockState[] pool = getBlockPool(dimension);
                        state = pool[rand.nextInt(pool.length)];
                    }

                    // Leaves never decay
                    if (state.getBlock() instanceof LeavesBlock) {
                        state = state.with(LeavesBlock.PERSISTENT, true);
                    }

                    // Saplings → dirt below, sapling on top
                    if (isSapling(state) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.DIRT.getDefaultState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    // Nether fungi → matching nylium below, fungus on top
                    } else if (state.isOf(Blocks.CRIMSON_FUNGUS) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.CRIMSON_NYLIUM.getDefaultState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    } else if (state.isOf(Blocks.WARPED_FUNGUS) && y + 1 < maxY) {
                        chunk.setBlockState(mutablePos, Blocks.WARPED_NYLIUM.getDefaultState(), false);
                        mutablePos.set(x, y + 1, z);
                        chunk.setBlockState(mutablePos, state, false);
                    // MA seeds → farmland below, seed on top
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

        return CompletableFuture.completedFuture(chunk);
    }

    // -------------------------------------------------------------------------
    // Entity population — configure spawners and chest loot
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
                    if (x % gridSpacing != 0 || y % gridSpacing != 0 || z % gridSpacing != 0) continue;

                    pos.set(x, y, z);
                    BlockState state = region.getBlockState(pos);

                    if (state.isOf(Blocks.SPAWNER)) {
                        if (region.getBlockEntity(pos) instanceof MobSpawnerBlockEntity spawner) {
                            Random rand = new Random(hashPos(x, y, z) + 999L);
                            EntityType<?> mob = pickMob(rand);
                            spawner.setEntityType(mob, region.getRandom());
                        }
                    } else if (state.isOf(Blocks.CHEST)) {
                        if (region.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                            Random rand = new Random(hashPos(x, y, z) + 777L);
                            chest.setLootTable(pickLootTable(rand), hashPos(x, y, z));
                        }
                    }
                }
            }
        }
    }

    private EntityType<?> pickMob(Random rand) {
        EntityType<?>[] mobs = switch (dimension) {
            case "nether" -> NETHER_MOBS;
            case "end"    -> END_MOBS;
            default       -> OVERWORLD_MOBS;
        };
        return mobs[rand.nextInt(mobs.length)];
    }

    private RegistryKey<net.minecraft.loot.LootTable> pickLootTable(Random rand) {
        return switch (dimension) {
            case "nether" -> rand.nextInt(2) == 0
                ? LootTables.NETHER_BRIDGE_CHEST
                : LootTables.BASTION_TREASURE_CHEST;
            case "end"    -> LootTables.END_CITY_TREASURE_CHEST;
            default -> switch (rand.nextInt(5)) {
                case 0  -> LootTables.SIMPLE_DUNGEON_CHEST;
                case 1  -> LootTables.ABANDONED_MINESHAFT_CHEST;
                case 2  -> LootTables.STRONGHOLD_LIBRARY_CHEST;
                case 3  -> LootTables.JUNGLE_TEMPLE_CHEST;
                default -> LootTables.DESERT_PYRAMID_CHEST;
            };
        };
    }

    // -------------------------------------------------------------------------
    // Required overrides
    // -------------------------------------------------------------------------

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures,
                             NoiseConfig noiseConfig, Chunk chunk) {}

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                      BiomeAccess biomeAccess, StructureAccessor structureAccessor,
                      Chunk chunk, GenerationStep.Carver carverStep) {}

    @Override public int getSeaLevel()    { return 63;  }
    @Override public int getWorldHeight() { return 384; }
    @Override public int getMinimumY()    { return -64; }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap,
                         HeightLimitView world, NoiseConfig noiseConfig) {
        return world.getBottomY();
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z,
                                               HeightLimitView world, NoiseConfig noiseConfig) {
        BlockState[] states = new BlockState[world.getHeight()];
        Arrays.fill(states, Blocks.AIR.getDefaultState());
        return new VerticalBlockSample(world.getBottomY(), states);
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("Sky Grid | Dim: " + dimension + " | Spacing: " + gridSpacing + " | Pos: " + pos.toShortString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isSapling(BlockState state) {
        return state.getBlock() instanceof SaplingBlock
            || state.isOf(Blocks.BAMBOO)
            || state.isOf(Blocks.AZALEA)
            || state.isOf(Blocks.FLOWERING_AZALEA)
            || state.isOf(Blocks.MANGROVE_PROPAGULE);
    }

    private static boolean needsFarmland(BlockState state) {
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id.getNamespace().equals("mysticalagriculture") && id.getPath().endsWith("_seeds");
    }

    private long hashPos(int x, int y, int z) {
        return x * 341873128712L + y * 132897987541L + z * 4392818741L ^ 0xDEADBEEFL;
    }
}

package com.skygrid.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skygrid.SkyGridConfig;
import com.skygrid.SkyGridMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places blocks on a 3D grid with configurable spacing, per dimension.
 *
 * ---- 1.21.1 BRANCH ----
 * The 1.21.1 backport of the 2.0 generator. The API is much closer to 1.21.11
 * than expected; only these actually differ:
 *   - ResourceLocation, not Identifier
 *   - ChunkAccess.setBlockState takes a boolean ("moved"), not int flags
 *   - ChunkAccess exposes getMinBuildHeight()/getMaxBuildHeight()
 *     (ChunkGenerator still declares an abstract getMinY())
 *   - applyCarvers keeps its trailing GenerationStep.Carving parameter
 *   - Registry.getTags() yields Pair<TagKey, HolderSet.Named>, not HolderSet.Named
 *
 * Everything else — no Executor on fillFromNoise, no RandomState.legacyLevelSeed(),
 * setBlockEntity rather than addAndRegisterBlockEntity, split setLootTable /
 * setLootTableSeed — matches 1.21.11.
 */
public class SkyGridChunkGenerator extends ChunkGenerator {

    /** Our own positional random factory key, so we don't perturb vanilla's ore randomness. */
    private static final ResourceLocation RANDOM_KEY =
        ResourceLocation.fromNamespaceAndPath(SkyGridMod.MOD_ID, "grid");

    /**
     * Block-update behaviour for worldgen writes.
     *
     * On 1.21.1 ChunkAccess.setBlockState's third argument is a boolean ("moved"),
     * not the int flags of later versions. Vanilla worldgen passes false.
     */
    private static final boolean GEN_MOVED = false;

    /** Single choke point for every block written during generation. */
    private static void place(ChunkAccess chunk, BlockPos pos, BlockState state) {
        chunk.setBlockState(pos, state, GEN_MOVED);
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------
    public static final MapCodec<SkyGridChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(gen -> gen.biomeSource),
            Codec.INT.optionalFieldOf("grid_spacing", 4).forGetter(gen -> gen.gridSpacing),
            Codec.STRING.optionalFieldOf("dimension", "overworld").forGetter(gen -> gen.dimension),
            Codec.BOOL.optionalFieldOf("seed_aware", true).forGetter(gen -> gen.seedAware)
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
    // Per-dimension block pools
    // -------------------------------------------------------------------------
    private static final Map<String, BlockState[]> DIMENSION_POOLS = new ConcurrentHashMap<>();

    public static BlockState[] getPublicBlockPool(String dimension) {
        return DIMENSION_POOLS.get(dimension);
    }

    public static BlockState[] getPublicBlockPool() {
        return DIMENSION_POOLS.get("overworld");
    }

    /** Clears cached pools so they rebuild on next generation — used by /skygrid reload. */
    public static void clearPools() {
        DIMENSION_POOLS.clear();
    }

    private static BlockState[] getBlockPool(String dimension) {
        return DIMENSION_POOLS.computeIfAbsent(dimension, SkyGridChunkGenerator::buildBlockPool);
    }

    private static BlockState[] buildBlockPool(String dimension) {
        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);
        boolean whitelist = "whitelist".equalsIgnoreCase(config.getMode());

        Map<Block, Integer> weights = new LinkedHashMap<>();
        Set<Block> denied = new HashSet<>();
        List<String> unresolved = new ArrayList<>();
        int tagCount = 0, fromTags = 0;

        // ---- Pass 1: tags ----
        for (SkyGridConfig.BlockEntry entry : config.getBlockEntries()) {
            if (!entry.isTag()) continue;
            tagCount++;

            ResourceLocation tagId = ResourceLocation.tryParse(entry.tagId());
            if (tagId == null) { unresolved.add(entry.id()); continue; }

            TagKey<Block> key = TagKey.create(Registries.BLOCK, tagId);
            int matched = 0;
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
                Block block = holder.value();
                if (whitelist) weights.putIfAbsent(block, entry.weight());
                else           denied.add(block);
                matched++;
            }
            fromTags += matched;
            if (matched == 0) unresolved.add(entry.id());
        }

        // ---- Pass 2: explicit block IDs (override tag-derived weights) ----
        for (SkyGridConfig.BlockEntry entry : config.getBlockEntries()) {
            if (entry.isTag()) continue;

            ResourceLocation id = ResourceLocation.tryParse(entry.id());
            Block block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
            // BuiltInRegistries.BLOCK is a defaulted registry: unknown IDs return AIR
            // rather than null, so an explicit AIR check is required here.
            if (block == null || block == Blocks.AIR) { unresolved.add(entry.id()); continue; }

            if (whitelist) weights.put(block, entry.weight());
            else           denied.add(block);
        }

        // ---- Blacklist mode: everything not denied, at weight 1 ----
        if (!whitelist) {
            for (Block block : BuiltInRegistries.BLOCK) {
                if (!denied.contains(block)) weights.put(block, 1);
            }
        }

        // ---- Flatten to the weighted pool ----
        List<BlockState> pool = new ArrayList<>();
        for (Map.Entry<Block, Integer> e : weights.entrySet()) {
            Block block = e.getKey();
            if (EXCLUDED_BLOCKS.contains(block)) continue;
            BlockState state = block.defaultBlockState();
            if (state.isAir()) continue;
            for (int i = Math.max(1, e.getValue()); i > 0; i--) pool.add(state);
        }

        if (tagCount > 0) {
            SkyGridMod.LOGGER.info("SkyGrid [{}] resolved {} tag(s) to {} block entries.",
                dimension, tagCount, fromTags);
        }

        long uniqueBlocks = pool.stream().distinct().count();
        SkyGridMod.LOGGER.info("SkyGrid [{}] block pool: {}/{} unique blocks, {} weighted slots (mode: {}).",
            dimension, uniqueBlocks, BuiltInRegistries.BLOCK.size(), pool.size(), config.getMode());

        if (!unresolved.isEmpty()) {
            SkyGridMod.LOGGER.info(
                "SkyGrid [{}] skipped {} config entr{} that resolved to no blocks "
              + "(mod not installed?): {}",
                dimension, unresolved.size(), unresolved.size() == 1 ? "y" : "ies",
                unresolved.size() > 12
                    ? String.join(", ", unresolved.subList(0, 12)) + ", …"
                    : String.join(", ", unresolved));
        }

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
        EntityType.ENDERMAN, EntityType.ENDERMAN, EntityType.ENDERMAN,
        EntityType.ENDERMITE, EntityType.SHULKER,
    };

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private final int gridSpacing;
    private final String dimension;
    private final boolean seedAware;

    public SkyGridChunkGenerator(BiomeSource biomeSource, int gridSpacing,
                                 String dimension, boolean seedAware) {
        super(biomeSource);
        this.gridSpacing = Math.max(1, gridSpacing);
        this.dimension   = dimension;
        this.seedAware   = seedAware;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // -------------------------------------------------------------------------
    // Main generation
    // -------------------------------------------------------------------------
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk) {

        ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();

        int minY = chunk.getMinBuildHeight();
        int maxYExclusive = chunk.getMaxBuildHeight();

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = firstGridAtOrAfter(startX); x < startX + 16; x += gridSpacing) {
            for (int z = firstGridAtOrAfter(startZ); z < startZ + 16; z += gridSpacing) {
                for (int y = firstGridAtOrAfter(minY); y < maxYExclusive; y += gridSpacing) {

                    mutablePos.set(x, y, z);
                    RandomSource rand = randomAt(randomState, x, y, z);
                    double roll = rand.nextDouble();

                    if (roll < 0.008) {
                        placeSpawner(chunk, x, y, z, rand);
                        continue;
                    } else if (roll < 0.022) {
                        placeChest(chunk, x, y, z, rand);
                        continue;
                    }

                    BlockState[] pool = getBlockPool(dimension);
                    if (pool.length == 0) continue;
                    BlockState state = pool[rand.nextInt(pool.length)];

                    if (state.getBlock() instanceof LeavesBlock) {
                        state = state.setValue(LeavesBlock.PERSISTENT, true);
                    }

                    boolean canStack = y + 1 < maxYExclusive;

                    if (isSapling(state) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.DIRT.defaultBlockState(), state);

                    } else if (state.is(Blocks.CRIMSON_FUNGUS) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.CRIMSON_NYLIUM.defaultBlockState(), state);
                    } else if (state.is(Blocks.WARPED_FUNGUS) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.WARPED_NYLIUM.defaultBlockState(), state);

                    } else if (needsFarmland(state) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.FARMLAND.defaultBlockState(), state);

                    } else if (state.is(Blocks.CACTUS) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.SAND.defaultBlockState(), state);

                    } else if (state.is(Blocks.SUGAR_CANE) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.SAND.defaultBlockState(), state);

                    } else if (isOre(state) && rand.nextDouble() < 0.02) {
                        placeCluster(chunk, x, y, z, state, startX, startZ, minY, maxYExclusive, rand);

                    } else {
                        place(chunk, mutablePos, state);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * Deterministic per-position randomness.
     *
     * RandomState does not expose the world seed on 1.21.1 either, so seed-aware
     * mode uses a named PositionalRandomFactory (itself derived from the seed).
     * Legacy mode reproduces the old seed-independent layout exactly.
     */
    private RandomSource randomAt(RandomState randomState, int x, int y, int z) {
        return seedAware
            ? randomState.getOrCreateRandomFactory(RANDOM_KEY).at(x, y, z)
            : RandomSource.create(hashPos(x, y, z));
    }

    /**
     * Places a mob spawner and its block entity.
     *
     * The BlockPos handed to a BlockEntity must be immutable — the shared
     * MutableBlockPos used by the main loop would be mutated out from under it.
     */
    private void placeSpawner(ChunkAccess chunk, int x, int y, int z, RandomSource rand) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = Blocks.SPAWNER.defaultBlockState();
        place(chunk, pos, state);

        SpawnerBlockEntity be = new SpawnerBlockEntity(pos, state);
        // Level is null during generation; BaseSpawner accepts that.
        be.setEntityId(pickMob(rand), rand);
        chunk.setBlockEntity(be);
    }

    /** Places a loot chest and its block entity. */
    private void placeChest(ChunkAccess chunk, int x, int y, int z, RandomSource rand) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = Blocks.CHEST.defaultBlockState();
        place(chunk, pos, state);

        ChestBlockEntity be = new ChestBlockEntity(pos, state);
        // On 1.21.1 the table and its seed are set together.
        be.setLootTable(pickLootTable(rand));
        be.setLootTableSeed(rand.nextLong());
        chunk.setBlockEntity(be);
    }

    /** Places {@code below} at (x,y,z) and {@code above} at (x,y+1,z). */
    private static void stack(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                              int x, int y, int z, BlockState below, BlockState above) {
        pos.set(x, y, z);
        place(chunk, pos, below);
        pos.set(x, y + 1, z);
        place(chunk, pos, above);
        pos.set(x, y, z);
    }

    /**
     * No-op. Spawners and chests get their block entities at placement time in
     * fillFromNoise, so there is nothing to do in a second pass.
     */
    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    private EntityType<?> pickMob(RandomSource rand) {
        EntityType<?>[] mobs = switch (dimension) {
            case "nether" -> NETHER_MOBS;
            case "end"    -> END_MOBS;
            default       -> OVERWORLD_MOBS;
        };
        return mobs[rand.nextInt(mobs.length)];
    }

    private ResourceKey<LootTable> pickLootTable(RandomSource rand) {
        return switch (dimension) {
            case "nether" -> rand.nextInt(2) == 0
                ? BuiltInLootTables.NETHER_BRIDGE
                : BuiltInLootTables.BASTION_TREASURE;
            case "end"    -> BuiltInLootTables.END_CITY_TREASURE;
            default -> switch (rand.nextInt(5)) {
                case 0  -> BuiltInLootTables.SIMPLE_DUNGEON;
                case 1  -> BuiltInLootTables.ABANDONED_MINESHAFT;
                case 2  -> BuiltInLootTables.STRONGHOLD_LIBRARY;
                case 3  -> BuiltInLootTables.JUNGLE_TEMPLE;
                default -> BuiltInLootTables.DESERT_PYRAMID;
            };
        };
    }

    // -------------------------------------------------------------------------
    // No-op passes
    // -------------------------------------------------------------------------

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {}

    // Takes WorldGenLevel, not WorldGenRegion — same as 1.21.11.
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structureManager) {}

    /** 1.21.1 keeps the trailing GenerationStep.Carving parameter. */
    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving carvingStep) {}

    @Override public int getSeaLevel() { return 63;  }
    @Override public int getGenDepth() { return 384; }
    @Override public int getMinY()     { return -64; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor level, RandomState state) {
        return level.getMinBuildHeight();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState state) {
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        return new NoiseColumn(level.getMinBuildHeight(), states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState state, BlockPos pos) {
        info.add("Sky Grid | Dim: " + dimension
               + " | Spacing: " + gridSpacing
               + " | SeedAware: " + seedAware);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** First grid-aligned coordinate greater than or equal to {@code from}. */
    private int firstGridAtOrAfter(int from) {
        return from + Math.floorMod(-from, gridSpacing);
    }

    private static boolean isSapling(BlockState state) {
        return state.getBlock() instanceof SaplingBlock
            || state.is(Blocks.BAMBOO)
            || state.is(Blocks.AZALEA)
            || state.is(Blocks.FLOWERING_AZALEA)
            || state.is(Blocks.MANGROVE_PROPAGULE);
    }

    /** Matches any vanilla or modded ore by ID convention. */
    private static boolean isOre(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    private static boolean needsFarmland(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals("mysticalagriculture") && id.getPath().endsWith("_seeds");
    }

    /** 2x2x2 or 3x3x3 cluster centred on the grid point, clipped to the chunk. */
    private static void placeCluster(ChunkAccess chunk, int x, int y, int z, BlockState state,
                                     int startX, int startZ, int minY, int maxYExclusive,
                                     RandomSource rand) {
        int size   = rand.nextBoolean() ? 2 : 3;
        int origin = size == 2 ? 0 : -1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = origin; cx < origin + size; cx++) {
            for (int cy = origin; cy < origin + size; cy++) {
                for (int cz = origin; cz < origin + size; cz++) {
                    int bx = x + cx, by = y + cy, bz = z + cz;
                    if (bx < startX || bx >= startX + 16) continue;
                    if (bz < startZ || bz >= startZ + 16) continue;
                    if (by < minY   || by >= maxYExclusive) continue;
                    pos.set(bx, by, bz);
                    place(chunk, pos, state);
                }
            }
        }
    }

    /** Stable per-position hash. Mixed with the world seed by the caller. */
    private static long hashPos(int x, int y, int z) {
        return x * 341873128712L + y * 132897987541L + z * 4392818741L ^ 0xDEADBEEFL;
    }
}

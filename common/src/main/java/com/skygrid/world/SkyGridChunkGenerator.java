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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.WorldGenRegion;
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
 * Ported from the 1.21.1 Fabric/Yarn version. All signatures below are checked
 * against the decompiled 1.21.11 sources, not inferred.
 *
 * Notable 1.21.11 differences from 1.21.1:
 *   - ResourceLocation was renamed to Identifier
 *   - GenerationStep.Carving no longer exists; applyCarvers takes no step arg
 *   - fillFromNoise has no Executor parameter
 *   - ChunkAccess.setBlockState takes int flags, not a boolean
 *   - ChunkAccess has getMinY() + getHeight(), but no getMaxY()
 *   - RandomState has no legacyLevelSeed(); use a PositionalRandomFactory
 *   - setLootTable / setLootTableSeed are now two separate calls
 */
public class SkyGridChunkGenerator extends ChunkGenerator {

    /** Our own positional random factory key, so we don't perturb vanilla's ore randomness. */
    private static final Identifier RANDOM_KEY =
        Identifier.fromNamespaceAndPath(SkyGridMod.MOD_ID, "grid");

    /**
     * Block-update flags for worldgen writes: none.
     *
     * IMPORTANT: do NOT use the two-argument ChunkAccess.setBlockState(pos, state)
     * overload here. It hardcodes flag 3 (UPDATE_NEIGHBORS | UPDATE_CLIENTS), which
     * schedules a block update for every block placed. During generation that makes
     * sand and gravel fall and water and lava spread the moment the chunk ticks.
     * Vanilla worldgen always passes 0.
     */
    private static final int GEN_FLAGS = 0;

    /** Single choke point for every block written during generation. */
    private static void place(ChunkAccess chunk, BlockPos pos, BlockState state) {
        chunk.setBlockState(pos, state, GEN_FLAGS);
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

    /**
     * The highest grid layer that still leaves {@code headroom} blocks beneath
     * the build limit. Used by SkyGridPlatform to sit the spawn pad on the top
     * of the grid rather than at a hardcoded height.
     *
     * Derived rather than hardcoded because grid spacing is configurable and the
     * Nether has a 128-block column, so 316 is only ever right for the overworld.
     *
     * 1.21.11: LevelHeightAccessor has getMinY() and getHeight() but no getMaxY(),
     * so the exclusive ceiling is minY + height. On the 1.21.1 branch this reads
     * getMaxBuildHeight() directly.
     */
    public int topGridY(LevelHeightAccessor level, int headroom) {
        int minY          = level.getMinY();
        int maxYExclusive = minY + level.getHeight();

        int first = firstGridAtOrAfter(minY);
        if (first >= maxYExclusive) return minY;

        int top = first + ((maxYExclusive - 1 - first) / gridSpacing) * gridSpacing;

        // Step down a layer at a time until the walls fit under the ceiling.
        while (top + headroom >= maxYExclusive && top - gridSpacing >= minY) {
            top -= gridSpacing;
        }
        return top;
    }

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

        // Resolved block -> weight. LinkedHashMap so the pool order stays stable
        // between rebuilds, which keeps /skygrid blocks output readable.
        Map<Block, Integer> weights = new LinkedHashMap<>();
        Set<Block> denied = new HashSet<>();
        List<String> unresolved = new ArrayList<>();
        int tagCount = 0, fromTags = 0;

        // ---- Pass 1: tags ----
        // Expanded first so that an explicit entry for the same block can
        // override the weight the tag would have given it.
        for (SkyGridConfig.BlockEntry entry : config.getBlockEntries()) {
            if (!entry.isTag()) continue;
            tagCount++;

            Identifier tagId = Identifier.tryParse(entry.tagId());
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
            // An empty tag usually means the mod defining it is not installed.
            if (matched == 0) unresolved.add(entry.id());
        }

        // ---- Pass 2: explicit block IDs (override tag-derived weights) ----
        for (SkyGridConfig.BlockEntry entry : config.getBlockEntries()) {
            if (entry.isTag()) continue;

            Identifier id = Identifier.tryParse(entry.id());
            Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
            if (block == null) { unresolved.add(entry.id()); continue; }

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

        // Entries that resolved to nothing — an unknown block, a malformed ID, or
        // an empty/absent tag. Almost always means a mod isn't installed. Harmless,
        // but reporting it explains why the pool is smaller than the config looks.
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

    /**
     * When true the grid layout depends on the world seed (correct behaviour).
     * When false the pre-2.0 behaviour is kept: identical layout in every world.
     * Existing 1.21.1 worlds should set this false to avoid a visible seam where
     * newly generated chunks meet already-generated ones.
     */
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

        // ChunkAccess exposes getMinY() and getHeight(); there is no getMaxY().
        int minY = chunk.getMinY();
        int maxYExclusive = minY + chunk.getHeight();

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        // Read the tunables once per chunk rather than once per grid point.
        // A single roll decides spawner / chest / ordinary block, so the chest
        // band is the two chances summed — keeping them separate in config means
        // changing one does not silently shift the other.
        SkyGridConfig cfg = SkyGridConfig.getForDimension(dimension);
        double spawnerCutoff    = cfg.getSpawnerChance();
        double chestCutoff      = spawnerCutoff + cfg.getChestChance();
        double oreClusterChance = cfg.getOreClusterChance();

        // Step directly between grid positions rather than testing every block.
        // ~64x fewer iterations than the 1.21.1 version at the default spacing.
        for (int x = firstGridAtOrAfter(startX); x < startX + 16; x += gridSpacing) {
            for (int z = firstGridAtOrAfter(startZ); z < startZ + 16; z += gridSpacing) {
                for (int y = firstGridAtOrAfter(minY); y < maxYExclusive; y += gridSpacing) {

                    mutablePos.set(x, y, z);
                    RandomSource rand = randomAt(randomState, x, y, z);
                    double roll = rand.nextDouble();

                    // Spawners and chests are placed here, complete with their block
                    // entities. Creating the BlockEntity is NOT automatic: a chest
                    // without a ChestBlockEntity is invisible (chests are drawn by a
                    // BlockEntityRenderer) and holds nothing.
                    if (roll < spawnerCutoff) {
                        placeSpawner(chunk, x, y, z, rand);
                        continue;
                    } else if (roll < chestCutoff) {
                        placeChest(chunk, x, y, z, rand);
                        continue;
                    }

                    BlockState[] pool = getBlockPool(dimension);
                    if (pool.length == 0) continue;
                    BlockState state = pool[rand.nextInt(pool.length)];

                    // Leaves never decay
                    if (state.getBlock() instanceof LeavesBlock) {
                        state = state.setValue(LeavesBlock.PERSISTENT, true);
                    }

                    boolean canStack = y + 1 < maxYExclusive;

                    // Saplings -> dirt below, sapling on top
                    if (isSapling(state) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.DIRT.defaultBlockState(), state);

                    // Nether fungi -> matching nylium below
                    } else if (state.is(Blocks.CRIMSON_FUNGUS) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.CRIMSON_NYLIUM.defaultBlockState(), state);
                    } else if (state.is(Blocks.WARPED_FUNGUS) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.WARPED_NYLIUM.defaultBlockState(), state);

                    // Mystical Agriculture seeds -> farmland below
                    } else if (needsFarmland(state) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.FARMLAND.defaultBlockState(), state);

                    // Cactus -> sand below
                    } else if (state.is(Blocks.CACTUS) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.SAND.defaultBlockState(), state);

                    // Sugar cane -> sand below.
                    //
                    // No water is placed beside it any more. Sugar cane is out of the
                    // default whitelist for exactly this reason; if you re-add it, the
                    // cane will pop off the first time it receives a block update,
                    // but nothing will flood.
                    } else if (state.is(Blocks.SUGAR_CANE) && canStack) {
                        stack(chunk, mutablePos, x, y, z, Blocks.SAND.defaultBlockState(), state);

                    // Ores -> 2% chance of a cluster
                    } else if (isOre(state) && rand.nextDouble() < oreClusterChance) {
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
     * RandomState no longer exposes the world seed, so seed-aware mode uses a
     * named PositionalRandomFactory (itself derived from the world seed).
     * Legacy mode reproduces the old seed-independent layout exactly.
     */
    private RandomSource randomAt(RandomState randomState, int x, int y, int z) {
        return seedAware
            ? randomState.getOrCreateRandomFactory(RANDOM_KEY).at(x, y, z)
            : RandomSource.create(legacyHash(x, y, z));
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
        // BaseSpawner.setEntityId accepts a null Level, which is what we have
        // during generation.
        be.setEntityId(pickMob(rand), rand);
        chunk.setBlockEntity(be);
    }

    /** Places a loot chest and its block entity. */
    private void placeChest(ChunkAccess chunk, int x, int y, int z, RandomSource rand) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = Blocks.CHEST.defaultBlockState();
        place(chunk, pos, state);

        ChestBlockEntity be = new ChestBlockEntity(pos, state);
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
     * fillFromNoise (see placeSpawner / placeChest).
     *
     * This previously did a second full sweep of the chunk looking up block
     * entities with region.getBlockEntity(). That never worked: worldgen does not
     * create block entities implicitly, so the lookups always failed and every
     * chest ended up invisible and empty. Removing the pass also halves the work
     * done per chunk.
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
    // No-op passes — SkyGrid has no surface, carvers or biome decoration
    // -------------------------------------------------------------------------

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {}

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk) {}

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structureManager) {}

    @Override public int getSeaLevel() { return 63;  }
    @Override public int getGenDepth() { return 384; }
    @Override public int getMinY()     { return -64; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor level, RandomState state) {
        return level.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState state) {
        BlockState[] states = new BlockState[level.getHeight()];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        return new NoiseColumn(level.getMinY(), states);
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
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
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

    // NOTE: fluid containment (glass shells around water/lava) was tried and
    // removed. Sealing a source block on all six faces did not reliably stop it
    // spreading in testing, and the extra loop range needed to seal fluids across
    // chunk borders added real complexity for no benefit. Water and lava are now
    // simply absent from the default block pools — see SkyGridConfig.
    //
    // If you revisit this, note that ProtoChunk.setBlockState IGNORES its update
    // flags entirely, so passing 0 vs 3 during generation changes nothing.

    /**
     * The original 1.21.1 position hash. Retained for legacy (seed-independent)
     * mode and as a stable salt for loot/spawner seeding.
     */
    private static long legacyHash(int x, int y, int z) {
        return x * 341873128712L + y * 132897987541L + z * 4392818741L ^ 0xDEADBEEFL;
    }
}

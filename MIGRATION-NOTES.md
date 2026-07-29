# SkyGrid — 1.21.1 → 1.21.11 multiloader migration

## What changed structurally

The old setup was two unrelated projects:

| Old | New |
|---|---|
| `skygrid-mod/` (Fabric, Yarn mappings, `com.skygrid`) | `common/` + `fabric/` |
| `Neoforge Skygrid Mod/skygrid-mod/` (NeoForge, Mojang mappings, `com.example.skygrid`) | `common/` + `neoforge/` |

Everything now lives once in `common/`, compiled against **Mojang official mappings**.
The Fabric module uses `loom.officialMojangMappings()` rather than Yarn, so all three
modules read identically.

`common/` must never import `net.fabricmc.*` or `net.neoforged.*`. Loader-specific
behaviour goes behind `com.skygrid.platform.IPlatformHelper`, resolved at runtime via
`ServiceLoader` (`META-INF/services/` in each loader module).

## Decisions taken

- **Fabric 1.2.0 is the feature baseline.** It is a strict superset of the NeoForge code:
  per-dimension pools, block weights, `/skygrid` commands, starter platform,
  ore clusters, sugar cane / cactus / nether fungi placement.
  The NeoForge `BlockPool` + TOML config are dropped in favour of the JSON config.
- **Config format is JSON**, three files (`skygrid.json`, `skygrid_nether.json`,
  `skygrid_end.json`). The NeoForge TOML spec is gone. NeoForge users' existing
  config will not carry over.
- **Generator ID unified.** Fabric registered `skygrid:skygrid`; NeoForge registered
  `skygrid:skygrid_generator`. Both now use `skygrid:skygrid`.
  *This breaks existing NeoForge SkyGrid worlds* — their `level.dat` references
  the old ID. See "Breaking changes" below.

## Bugs found in the 1.21.1 code (fix during the port)

1. **World seed ignored (Fabric).** `hashPos(x,y,z)` is a pure function of coordinates,
   so *every* Fabric world generates a byte-identical grid regardless of seed.
   The NeoForge version did this correctly (`posSeed ^= randomState.legacyLevelSeed()`).
   Fix: mix the seed in. Note this changes existing worlds' generation — see below.

2. **Full-column iteration.** Both generators loop all 384 Y values for every
   x/z in the chunk (98,304 iterations/chunk), then `spawnOriginalMobs` does it
   again. Only every 4th Y is a grid position. Stepping the loop by `gridSpacing`
   instead of testing `y % gridSpacing` cuts this ~64x.

3. **Uncaptured feature.** `placeFluidShoreline` exists only in your uncommitted
   working copy (saved to `local-uncommitted-edits.patch`), not in origin/main.
   Carried forward into the port.

## Breaking changes for existing worlds

- Seed fix (#1) changes the block layout of any world regenerating new chunks.
  Already-generated chunks are unaffected; the boundary will be visible.
  If you want existing 1.21.1 worlds to stay consistent, keep the old
  `hashPos` and gate the fix behind a config flag.
- NeoForge generator ID change orphans existing NeoForge worlds.

## API changes 1.21.1 -> 1.21.11 (CONFIRMED by compiling)

All resolved against the decompiled sources in
`common/build/moddev/artifacts/vanilla-1.21.11-20251209.172050-sources.jar`.
Build is green on all three modules as of 2026-07-29.

| 1.21.1 | 1.21.11 | Notes |
|---|---|---|
| `ResourceLocation` | **`Identifier`** | `net.minecraft.resources.Identifier`. Mojang adopted the Yarn name — affects every file that touched IDs. |
| `GenerationStep.Carving` | **removed** | Only `GenerationStep.Decoration` remains. `applyCarvers` takes no step arg. |
| `applyCarvers(..., Carving)` | `applyCarvers(WorldGenRegion, long, RandomState, BiomeManager, StructureManager, ChunkAccess)` | 6 params. |
| `fillFromNoise(Executor, ...)` | `fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)` | **No `Executor`.** |
| `setBlockState(pos, state, boolean)` | `setBlockState(pos, state, int flags)` | 2-arg overload defaults to flag 3. Boolean form is gone. |
| `getMaxBuildHeight()` / `getMaxY()` | **neither exists** on `ChunkAccess` | Use `getMinY() + getHeight()`. Both my guesses were wrong. |
| `RandomState.legacyLevelSeed()` | **removed** | Use `randomState.getOrCreateRandomFactory(Identifier).at(x,y,z)`. More idiomatic anyway. |
| `spawner.setEntityType(type, random)` | `spawner.setEntityId(EntityType, RandomSource)` | Two args. |
| `chest.setLootTable(key, seed)` | `setLootTable(key)` + `setLootTableSeed(seed)` | Split into two calls. |
| `level.getSharedSpawnPos()` / `setDefaultSpawnPos()` | `getRespawnData()` / `setRespawnData(LevelData.RespawnData)` | Spawn is now a `GlobalPos` + yaw + pitch record. |
| `src.hasPermission(2)` | `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` | Integer permission levels replaced by `PermissionSet`/`PermissionCheck`. |
| `applyBiomeDecoration(WorldGenRegion, ...)` | `applyBiomeDecoration(WorldGenLevel, ...)` | Param type widened. |
| `LootTables.SIMPLE_DUNGEON_CHEST` | `BuiltInLootTables.SIMPLE_DUNGEON` | As expected. |
| `Registries.BLOCK.getId(b)` | `BuiltInRegistries.BLOCK.getKey(b)` | As expected. |
| `getWorldHeight()` / `getMinimumY()` | `getGenDepth()` / `getMinY()` | As expected. |

### Build toolchain (all verified against upstream maven)

| | Value | Note |
|---|---|---|
| Gradle | 9.6.1 | Loom 1.17.13 requires >= 9.5 |
| fabric-loom | 1.17.13 | Must be >= the Loom Fabric API was built with (1.13.3) |
| moddev | 2.0.141 | |
| NeoForm | 1.21.11-20251209.172050 | |
| NeoForge | 21.11.44 | |
| Fabric Loader / API | 0.19.3 / 0.141.4+1.21.11 | Only `fabric-command-api-v2` + `fabric-lifecycle-events-v1` are depended on |

Gradle JVM must be **JDK 21** (Gradle 9.6.1 does not accept JDK 25).

### Still unverified at runtime

- `LevelEvent.Load` in `SkyGridNeoForge` compiles but has not been exercised;
  the starter platform on NeoForge is untested.
- `isDevelopmentEnvironment()` was removed from `IPlatformHelper` — it was never
  called, and `FMLLoader.isProduction()` became non-static in 21.11.

### Historical: pre-build guesses (kept for reference)

The table below is what I *assumed* before compiling. Several entries were wrong;
it is retained only to show which assumptions failed.

| 1.21.1 (Yarn) | Assumed 1.21.11 (Mojang) | Risk |
|---|---|---|
| `chunk.getBottomY()` | `chunk.getMinY()` | **High** — renamed from `getMinBuildHeight()` around 1.21.2 |
| `chunk.getTopY()` | `chunk.getMaxY()` | **High** — `getMaxY()` is *inclusive*, old `getMaxBuildHeight()` was *exclusive*. Off-by-one risk in every loop bound. |
| `populateNoise(...)` | `fillFromNoise(Executor, Blender, RandomState, StructureManager, ChunkAccess)` | Medium — `Executor` param present in Mojang signature |
| `populateEntities(ChunkRegion)` | `spawnOriginalMobs(WorldGenRegion)` | Medium |
| `spawner.setEntityType(type, random)` | `spawnerBE.getSpawner().setEntityId(type, level, random, pos)` | **High** — signature differs; NeoForge code even comments uncertainty here |
| `chest.setLootTable(key, seed)` | `chest.setLootTable(key, seed)` | Low |
| `LootTables.SIMPLE_DUNGEON_CHEST` | `BuiltInLootTables.SIMPLE_DUNGEON` | Low |
| `Registries.BLOCK.getId(b)` | `BuiltInRegistries.BLOCK.getKey(b)` | Low |
| `carve(...)` | `applyCarvers(...)` | Medium |
| `getWorldHeight()` | `getGenDepth()` | Medium |
| `getHeight(...)` | `getBaseHeight(...)` | Low |
| `getColumnSample(...)` | `getBaseColumn(...)` | Low |
| `getDebugHudText(...)` | `addDebugScreenInfo(...)` | Low |
| `state.getDefaultState()` | `state.defaultBlockState()` | Low |
| `state.isOf(b)` | `state.is(b)` | Low |
| `state.with(p, v)` | `state.setValue(p, v)` | Low |

Build-tool versions in `gradle.properties` and the `build.gradle` files marked
`VERIFY` also need checking — particularly the NeoForm build string for 1.21.11
and the `fabric-loom` / `moddev` plugin versions.

## Verified version numbers (checked 2026-07-29)

- Minecraft 1.21.11 (released Dec 2025)
- NeoForge `21.11.44` (latest for 1.21.11)
- Fabric Loader `0.19.3`
- Fabric API `0.141.4+1.21.11`

# WT Sky Grid — agent instructions

Sky Grid world generation mod. Multiloader: Fabric + NeoForge, one shared codebase.
Repo: https://github.com/whotevatech/skygrid-mod · Licence MIT · Author Whoteva

---

## 1. Read this before touching anything

**This repo is branch-per-Minecraft-version. There is no `main`.**

| Branch | Minecraft | NeoForge | Fabric API |
|---|---|---|---|
| `1.21.1` | 1.21.1 | 21.1.244 | 0.116.12+1.21.1 |
| `multiloader-1.21.11` | 1.21.11 | 21.11.44 | 0.141.4+1.21.11 |

The branches use **different Minecraft APIs** — not different config, different
method signatures. `ResourceLocation` is `Identifier` on 1.21.11. `fillFromNoise`
has no `Executor`. `legacyLevelSeed()` no longer exists. The full table is in
`MIGRATION-NOTES.md` and it is authoritative — consult it before writing worldgen
code, and never assume a symbol exists on both branches.

**Check which branch you are on before you start** (`git rev-parse --abbrev-ref HEAD`)
and say so in your first message.

**Never merge these branches.** A feature that belongs on both gets written or
cherry-picked deliberately, then compiled on each. `git merge` between them will
produce code that does not build.

The 1.21.1 branch exists because Create, Mekanism, AE2 and Thermal skipped 1.21.11.
A tech-oriented pack has to be built on 1.21.1. That is not going to change soon.

---

## 2. Module rules

```
common/     all game logic — compiled against vanilla, Mojang official mappings
fabric/     Fabric loader wrapper
neoforge/   NeoForge loader wrapper
```

- **`common/` must never import `net.fabricmc.*` or `net.neoforged.*`.** No exceptions.
  Loader-specific behaviour goes behind `com.skygrid.platform.IPlatformHelper`,
  resolved at runtime via `ServiceLoader` (`META-INF/services/` in each loader module).
- Fabric uses `loom.officialMojangMappings()`, **not Yarn**. All three modules read
  identically. Do not reintroduce Yarn names.
- A change to gameplay belongs in `common/`. If you find yourself writing the same
  logic twice in `fabric/` and `neoforge/`, stop — it belongs in `common/` behind
  the platform helper.

---

## 3. Build

```bash
./gradlew build                 # all modules
./gradlew :neoforge:runClient   # dev client
./gradlew :fabric:runClient
```

**Gradle's JVM must be JDK 21.** Gradle 9.6.1 does not accept JDK 25 — if you see a
toolchain error, this is why. `org.gradle.java.home` or `JAVA_HOME` must point at 21.

Jars land in `fabric/build/libs/` and `neoforge/build/libs/`, named
`skygrid-<loader>-<mcversion>-<modversion>.jar`.

A change is not done until **both** loader modules compile on the branch you are on.
Run the build. Do not report success from reading the code.

---

## 4. Conventions

- **Every version number lives in `gradle.properties`.** Never hardcode a Minecraft,
  loader, or mod version in Java or in a resource file — `processResources` expands
  them into `neoforge.mods.toml`, `fabric.mod.json` and `pack.mcmeta`.
- `minecraft_version_range` has an upper bound on purpose. Keep it. Without it the
  mod claims compatibility with every future release and NeoForge will load it into
  versions where it cannot work.
- Java 21 language level, UTF-8, `options.release = 21`.
- `run/`, `build/`, `.gradle/` are gitignored. Never commit anything under them and
  never edit generated config there expecting it to persist — the JSON configs are
  produced from `SkyGridConfig` at runtime.
- Commit style is terse and imperative, often version-prefixed:
  `2.3.0: move spawn platform down one grid layer for headroom`.

---

## 5. Config surface (user-facing — don't break casually)

Three JSON files in the user's `config/`: `skygrid.json`, `skygrid_nether.json`,
`skygrid_end.json`. Entries are a block ID string or `{"id": ..., "weight": n}`.
Modes: `whitelist` (default) and `blacklist`.

Generator ID is `skygrid:skygrid` on **both** loaders. It was
`skygrid:skygrid_generator` on old NeoForge builds; changing it again orphans
existing worlds' `level.dat`. Don't.

Unregistered block IDs are skipped harmlessly and logged at startup. Keep that
behaviour — packs ship configs referencing mods the user may not have.

---

## 6. Known-open items

- `LevelEvent.Load` in `SkyGridNeoForge` compiles but has never been exercised —
  the starter platform on NeoForge is **untested**. Highest-value thing to verify.
- `.github/cf-notify-state.json` is the notifier's legacy state file. The workflow
  now keeps state on the orphan `ci-state` branch and migrates this file across on
  its first run. **Delete it from this branch after one successful notifier run.**
- The notifier workflow lives on this branch **only, by design** — GitHub schedules
  `on: schedule` workflows from the default branch alone, and that is `1.21.1`.
  The build workflow and these instruction files exist on both branches.
- `origin/main` still holds v1.2.0 (Fabric-only, April 2026) and the separate
  `whotevatech/skygrid-mod-neoforge` repo still holds the pre-merge NeoForge
  project. Both are superseded by this repo's two version branches and should be
  archived so nobody — human or agent — reads them as current.

## 7. Working style

Explain the architecture and confirm the approach before writing code. Propose
structure → confirm → implement. Do not produce a large diff and ask for a review
afterwards.

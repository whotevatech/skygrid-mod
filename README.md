# 🌐 WT Sky Grid

A modern Sky Grid world generation mod for **Minecraft 1.21.11**, on both **NeoForge** and **Fabric**.

The world is a 3D lattice of single blocks floating in the void — ores, wood, chests, spawners — with nothing between them. Every block counts, and falling is usually fatal.

Inspired by the classic SkyGrid map by SethBling.

---

## ✨ Features

- **3D block grid** — one block every 4 positions in X, Y and Z (configurable)
- **Per-dimension pools** — the Overworld, Nether and End each get their own block list, mobs and loot tables
- **Full mod support** — any installed mod's blocks can appear; the pool is built from the live block registry
- **Spawn weights** — make diamond ore rare and stone common, per block
- **Loot chests** — dungeon, mineshaft, stronghold, temple, bastion and End City tables, chosen per dimension
- **Mob spawners** — dimension-appropriate mobs (no blazes in the Overworld, no drowned in the Nether)
- **Ore clusters** — ores occasionally generate as 2×2×2 or 3×3×3 veins instead of single blocks
- **Smart plant placement** — saplings get dirt, cactus gets sand, nether fungi get matching nylium, Mystical Agriculture seeds get farmland
- **Starter platform** — a 5×5 platform at spawn so you don't begin the game falling
- **In-game editor** — add and remove blocks and tune weights from a GUI, no file editing
- **Commands** — full block-pool control with tab completion over every registered block

---

## 🔧 Requirements

| | |
|---|---|
| Minecraft | 1.21.11 |
| Java | 21+ |
| **NeoForge** | 21.11.44 or newer |
| **Fabric** | Loader 0.19.3+ and [Fabric API](https://modrinth.com/mod/fabric-api) |

[ModMenu](https://modrinth.com/mod/modmenu) is optional on Fabric — it provides the config button. Without it, use `/skygridgui`.

---

## 📦 Installation

1. Install NeoForge or Fabric for 1.21.11
2. On Fabric, put **Fabric API** in your `mods` folder
3. Put the Sky Grid jar in `mods`
4. Launch

---

## 🌍 Creating a Sky Grid world

1. **Singleplayer → Create New World**
2. Click **World Type** until it reads **Sky Grid**
3. **Create New World**

You spawn on a small oak platform. Bridge outward carefully — the void is directly below.

The Nether and End are Sky Grids too, with their own block pools.

---

## ⚙️ Configuration

Three files are created in your `config` folder on first launch:

| File | Dimension |
|---|---|
| `skygrid.json` | Overworld |
| `skygrid_nether.json` | The Nether |
| `skygrid_end.json` | The End |

### Format

```json
{
  "mode": "whitelist",
  "whitelist": [
    "minecraft:stone",
    { "id": "minecraft:diamond_ore", "weight": 3 },
    "biomesoplenty:redwood_log"
  ]
}
```

Each entry is either a plain block ID (weight 1) or an object with an explicit `weight`. **Higher weight = more common.** A block with weight 5 is five times as likely to appear as one with weight 1.

### Modes

| Mode | Behaviour |
|---|---|
| `whitelist` | **Only** the listed blocks appear |
| `blacklist` | All registered blocks appear **except** those listed |

> **Careful with `blacklist`.** It pulls in every block from every installed mod, including technical and unobtainable ones. `whitelist` is the default for good reason.

Entries naming blocks that aren't registered — from a mod you haven't installed — are skipped harmlessly and reported in the log at startup. That's why the pool count can be lower than your config's entry count.

---

## 🖥️ In-game editor

Open it with **`/skygridgui`**, or the config button:

- **NeoForge** — Mods screen → WT Sky Grid → config icon
- **Fabric** — ModMenu → WT Sky Grid → config icon

The editor gives you dimension tabs, a search box that matches block names *and* mod IDs, an "All blocks / In pool" filter, per-block on/off, and `−`/`+` weight controls.

Changes save immediately.

---

## 🛠️ Commands

All require permission level 2 (`LEVEL_GAMEMASTERS`), so single-player needs cheats enabled.

| Command | Description |
|---|---|
| `/skygrid blocks` | Pool summary plus the first 50 entries |
| `/skygrid blocks log` | Dump the full pool to the game log |
| `/skygrid reload` | Reload all three configs from disk |
| `/skygrid add <block> [weight] [dimension]` | Add a block, or change its weight |
| `/skygrid remove <block> [dimension]` | Remove a block |
| `/skygrid weight <block> <n> [dimension]` | Set a weight |
| `/skygridgui` | Open the editor (client-side) |

`dimension` is optional and defaults to `overworld`. Accepts `overworld`, `nether` or `end`.

`add` tab-completes over **every registered block**, so modded blocks appear automatically. `remove` and `weight` complete only over what's already in the pool.

```
/skygrid add create:andesite_alloy 3
/skygrid add minecraft:ancient_debris 1 nether
/skygrid remove minecraft:sand
```

---

## ⚠️ Changes only affect new chunks

The block pool is read when a chunk generates. Editing the config — however you do it — **never changes terrain that already exists.**

To see your changes, travel into ungenerated chunks or start a new world.

---

## 🚫 Why no water or lava?

They're deliberately absent from the default pools. As grid source blocks they cascade down through the void and flood large volumes of the world.

If you want the classic flowing-fluid Sky Grid, add them back:

```
/skygrid add minecraft:water
/skygrid add minecraft:lava
```

Sugar cane is out for the same reason — it's only placeable beside a water source.

---

## 🔍 Finding block IDs

Press **F3 + H** to enable advanced tooltips, then hover any block in the creative menu — the ID appears in grey beneath the name.

The editor's search box and the commands' tab completion both work without this, so it's mostly useful for browsing.

---

## 🏗️ Building from source

```bash
git clone https://github.com/whotevatech/skygrid-mod.git
cd skygrid-mod
./gradlew build
```

Jars land in `fabric/build/libs/` and `neoforge/build/libs/`.

Requires **JDK 21**. Gradle 9.6.1 comes via the wrapper.

### Project layout

```
common/     shared code — all game logic lives here
fabric/     Fabric loader wrapper
neoforge/   NeoForge loader wrapper
```

`common` is compiled against vanilla Minecraft with **official Mojang mappings** and must never import loader APIs. Anything loader-specific goes behind `IPlatformHelper`, resolved at runtime via `ServiceLoader`.

Run the game in a dev environment with:

```bash
./gradlew :neoforge:runClient
./gradlew :fabric:runClient
```

---

## 📈 Upgrading from 1.x

Version 1.x was Fabric-only for 1.21.1. Version 2.0 is a rewrite of the project structure — worth knowing:

- **Worlds are not compatible.** Start fresh.
- **The grid now depends on the world seed.** In 1.x every world generated an identical layout regardless of seed; that was a bug. Set `"seed_aware": false` in the world preset to restore the old behaviour.
- **NeoForge's generator ID changed** from `skygrid:skygrid_generator` to `skygrid:skygrid`, matching Fabric.
- **The NeoForge TOML config is gone**, replaced by the same JSON config Fabric uses.
- **Water, lava and sugar cane** left the default pools — see above.

---

## 🤝 Contributing

Issues and pull requests welcome.

Bug reports are most useful with the Minecraft version, the loader and its version, your `config/skygrid*.json`, and `logs/latest.log`.

---

## 📄 Licence

MIT — see [LICENSE](LICENSE).

---

## 🙏 Credits

Built with [NeoForge](https://neoforged.net/) and [Fabric](https://fabricmc.net/).

Inspired by the original SkyGrid concept by **SethBling**.

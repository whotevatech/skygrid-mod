# 🌐 WT Sky Grid

A modern Sky Grid world generation mod for **Minecraft 1.21.1**, on both **NeoForge** and **Fabric**.

The world is a 3D lattice of single blocks floating in the void — ores, wood, chests, spawners — with nothing between them. Every block counts, and falling is usually fatal.

Inspired by the classic SkyGrid map by SethBling.

> **Branches.** This repository is branch-per-Minecraft-version.
> `1.21.1` (this branch, the default) and `multiloader-1.21.11` target different
> Minecraft releases with different APIs — they are not merged into each other.
> 1.21.1 exists because Create, Mekanism, AE2 and Thermal have not moved past it.

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
- **In-game editor** — dimension tabs, search, and aligned weight controls, saved straight to the JSON configs
- **Commands** — full block-pool control with tab completion over every registered block

---

## 🔧 Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21+ |
| **NeoForge** | 21.1.244 or newer |
| **Fabric** | Loader 0.19.3+ and [Fabric API](https://modrinth.com/mod/fabric-api) |

[ModMenu](https://modrinth.com/mod/modmenu) is optional on Fabric — it provides the config button. Without it, use `/skygridgui`.

---

## 📦 Installation

1. Install NeoForge or Fabric for 1.21.1
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

### Dedicated servers

Create World is never shown on a dedicated server, so the world type has to be
set in `server.properties` **before** the world is generated:

```
level-type=skygrid:skygrid
```

If you leave this at the default (`minecraft:normal`), the overworld will be
vanilla terrain. Changing the setting later does **not** convert an existing
world — you have to delete that world folder and generate again.

On boot the mod logs whether the overworld actually came up as Sky Grid, so a
wrong `level-type` is obvious in `logs/latest.log`.

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

The editor is one centered column, the same on both loaders:

- **Overworld / Nether / End** tabs. Left and right change the tab while that bar is focused. Ctrl+F (Cmd+F) jumps to search.
- Search matches block names, ids and mod ids (`thermal`, `minecraft:stone`).
- **Listed** shows only saved entries. **All blocks** shows everything you can add.
- A checkbox or **Add** puts a block on the list. **− / +** set its weight. Shift-click changes the weight by 10. Removing a listed block asks you to click **Remove**.
- Tags and ids that are not registered stay in a section at the top, so they can still be edited.
- The line under the search box is the save state. Edits write to disk immediately and apply to **newly generated chunks only**. A blacklist is shown in amber, because listed blocks are excluded. Weight buttons are hidden in that mode — weights are not used.
- **Reload** reads the three JSON files again, after a confirmation. If one of them cannot be parsed, nothing is changed and the line turns red.

`/skygrid reload` follows the same rule and repeats it in chat.

---

## 🛠️ Commands

All require permission level 2 (`LEVEL_GAMEMASTERS`), so single-player needs cheats enabled.

| Command | Description |
|---|---|
| `/skygrid` | Status, rates, and a command list |
| `/skygrid blocks [log] [dimension]` | Pool summary (unique blocks + weights). `log` dumps the full list |
| `/skygrid reload` | Reload all three configs from disk |
| `/skygrid add <block> [weight] [dimension]` | Add a block, or change its weight |
| `/skygrid remove <block> [dimension]` | Remove a block |
| `/skygrid weight <block> <n> [dimension]` | Set a weight |
| `/skygrid tag add <tag> [weight] [dimension]` | Add every block in a tag |
| `/skygrid tag remove <tag> [dimension]` | Remove a tag |
| `/skygrid chance` | Show generation rates (optional: `spawner`/`chest`/`cluster`, a 0–1 value, and a dimension) |
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

## 📈 Upgrading from 2.3.1

2.4.0 rebuilds the in-game editor. Worlds, JSON configs, commands and world
generation are unchanged, and both loaders get the same screen.

`/skygrid reload` (and the editor's Reload button) no longer substitutes the
default pool when a file is missing or invalid. The running lists stay as
they were and the error is reported. A bad file at startup still falls back
to defaults so the game can boot.

## 📈 Upgrading from 2.3.0

2.3.1 is a polish release. Existing worlds and configs keep working. Dedicated
servers that were already generating as Sky Grid need no change. If a command
or the editor rewrites a config file, pack `_comment` / `_rates_comment` fields
are now preserved instead of being replaced.

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

# 🌐 Sky Grid Mod

A modern Sky Grid world generation mod for Minecraft 1.21.1 (Fabric).

Generates a world where blocks are placed in a 3D grid pattern, floating in the void. Every block is random — ores, wood, lava, chests, spawners — making for a unique survival challenge where every block counts.

---

## ✨ Features

- **3D block grid** — blocks placed every 4 blocks in all directions (configurable)
- **Random block variety** — large pool of blocks from stone and ores to nether and end blocks
- **Mod support** — automatically includes blocks from any installed mod
- **Loot chests** — randomly placed chests with dungeon, mineshaft, and temple loot
- **Mob spawners** — randomly placed spawners with varied mob types
- **Saplings auto-place dirt** — saplings always generate with a dirt block beneath them so they can grow
- **Fully configurable** — whitelist or blacklist blocks via a simple JSON config file
- **In-game debug command** — `/skygrid blocks` to check exactly what's in the block pool

---

## 🔧 Requirements

- Minecraft **1.21.1**
- [Fabric Loader](https://fabricmc.net/use/installer/) **0.16.7+**
- [Fabric API](https://modrinth.com/mod/fabric-api) **0.102.0+1.21.1**

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.1
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods` folder
3. Download the latest `skygrid-mod.jar` from the [Releases](../../releases) page
4. Place the jar in your `mods` folder
5. Launch Minecraft

---

## 🌍 Creating a Sky Grid World

1. Launch Minecraft with the mod installed
2. Click **Singleplayer → Create New World**
3. Click the **World Type** button until it shows **Sky Grid**
4. Click **Create New World**

You'll spawn floating in the void — be careful not to fall!

> **Tip:** Start by bridging to nearby blocks to build a safe platform before exploring.

---

## ⚙️ Configuration

On first launch, a config file is automatically created at:

```
.minecraft/config/skygrid.json
```

### Modes

The config supports two modes:

| Mode | Description |
|------|-------------|
| `whitelist` | **Only** the listed blocks can appear in the grid |
| `blacklist` | All blocks can appear **except** the listed ones |

The default mode is **whitelist**, which gives you full control over what spawns.

### Config File Format

```json
{
  "_comment": "SkyGrid Config - set mode to whitelist or blacklist",
  "mode": "whitelist",
  "whitelist": [
    "minecraft:stone",
    "minecraft:oak_log",
    "minecraft:diamond_ore",
    "mod_id:block_name"
  ]
}
```

---

## 🔍 How to Find Block IDs

The easiest way to find the correct ID for any block (including mod blocks) is to use Minecraft's **Advanced Tooltips** feature:

1. Launch Minecraft and open your inventory or creative menu
2. Press **F3 + H** to enable Advanced Tooltips
3. Hover over any block — you'll see its ID in grey below the name:

```
Oak Log
minecraft:oak_log
```

4. Copy that ID exactly into your `skygrid.json`

> Press **F3 + H** again to turn tooltips off when you're done.

### Examples

| Block | ID to use |
|-------|-----------|
| Stone | `minecraft:stone` |
| Oak Log | `minecraft:oak_log` |
| Diamond Ore | `minecraft:diamond_ore` |
| Create Andesite Alloy | `create:andesite_alloy` |
| Biomes O' Plenty Redwood Log | `biomesoplenty:redwood_log` |

---

## 🌿 Adding Mod Blocks

Sky Grid automatically scans all registered blocks when the world loads — this means any installed mod's blocks can appear in the grid. To add mod blocks to your whitelist:

1. Install the mod you want (e.g. Biomes O' Plenty, Create, Botania)
2. Launch Minecraft and go into a world
3. Use **F3 + H** + hover over the block to get its exact ID
4. Add the ID to your `skygrid.json` whitelist
5. Restart Minecraft

### Example — Adding Biomes O' Plenty blocks

```json
{
  "mode": "whitelist",
  "whitelist": [
    "minecraft:oak_log",
    "biomesoplenty:redwood_log",
    "biomesoplenty:redwood_sapling",
    "biomesoplenty:fir_log",
    "biomesoplenty:willow_log"
  ]
}
```

> **Note:** If the config fails to load (e.g. due to a JSON syntax error), the mod falls back to defaults and logs an error. Common mistake — make sure there is **no comma** after the last item in the list.

---

## 🛠️ Debug Commands

| Command | Description |
|---------|-------------|
| `/skygrid blocks` | Shows how many blocks are in the pool and lists the first 50 in chat |
| `/skygrid blocks log` | Dumps the full block list to the game log file |

The game log is found at `.minecraft/logs/latest.log` (or in the IntelliJ **Run** panel if developing).

---

## 📋 Default Whitelist

The default whitelist includes a balanced set of vanilla survival blocks:

<details>
<summary>Click to expand full default whitelist</summary>

**Terrain**
- Stone, Deepslate, Dirt, Grass Block, Coarse Dirt, Podzol, Mycelium
- Sand, Red Sand, Gravel, Clay, Ice, Packed Ice, Blue Ice, Snow Block, Obsidian

**Stone Variants**
- Cobblestone, Mossy Cobblestone, Andesite, Diorite, Granite, Tuff, Calcite

**Ores**
- Coal, Iron, Copper, Gold, Lapis, Redstone, Diamond, Emerald Ore
- All Deepslate ore variants

**Wood Types**
- Oak, Birch, Spruce, Jungle, Acacia, Dark Oak, Mangrove, Cherry logs
- Bamboo Block, Crimson Stem, Warped Stem

**Leaves**
- All vanilla leaf types including Azalea and Flowering Azalea leaves

**Saplings** *(always spawn with dirt below them)*
- All vanilla saplings, Mangrove Propagule, Bamboo, Azalea, Flowering Azalea

**Nether**
- Netherrack, Soul Sand, Soul Soil, Glowstone, Magma Block, Basalt, Blackstone
- Nether Gold Ore, Nether Quartz Ore, Crimson/Warped Nylium

**End**
- End Stone, Purpur Block

**Fluids**
- Water, Lava

**Utility**
- Melon, Pumpkin, Hay Block, Bone Block, Slime Block, Sponge
- Bookshelf, Crafting Table, Furnace, Glass, Shroomlight, Amethyst Block
- Raw Iron/Copper/Gold Block, Cactus

</details>

---

## 🏗️ Building from Source

**Requirements:** Java 21, IntelliJ IDEA

```bash
git clone https://github.com/YOUR_USERNAME/skygrid-mod.git
cd skygrid-mod
./gradlew build
```

The compiled jar will be in `build/libs/`.

To launch Minecraft with the mod for testing:

```bash
./gradlew runClient
```

---

## 🤝 Contributing

Contributions are welcome! Feel free to:
- Open an **Issue** to report bugs or suggest features
- Open a **Pull Request** with improvements

---

## 📄 Licence

This project is licensed under the MIT Licence — see the [LICENSE](LICENSE) file for details.

---

## 🙏 Credits

Built with [Fabric](https://fabricmc.net/) and [Fabric API](https://github.com/FabricMC/fabric-api).

Inspired by the classic SkyGrid concept originally created by SethBling.

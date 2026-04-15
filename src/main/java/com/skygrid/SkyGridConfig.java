package com.skygrid;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads and writes per-dimension config files:
 *   skygrid.json          — overworld
 *   skygrid_nether.json   — the nether
 *   skygrid_end.json      — the end
 */
public class SkyGridConfig {

    // -------------------------------------------------------------------------
    // Default overworld whitelist — vanilla survival blocks only
    // Nether and End blocks are in their own dimension defaults below
    // -------------------------------------------------------------------------
    private static final List<String> DEFAULT_OVERWORLD_WHITELIST = List.of(
        // --- Terrain ---
        "minecraft:stone", "minecraft:deepslate", "minecraft:dirt",
        "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:podzol",
        "minecraft:mycelium", "minecraft:sand", "minecraft:red_sand",
        "minecraft:gravel", "minecraft:clay", "minecraft:ice",
        "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:snow_block",
        "minecraft:obsidian",
        // --- Stone variants ---
        "minecraft:cobblestone", "minecraft:mossy_cobblestone",
        "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
        "minecraft:tuff", "minecraft:calcite",
        // --- Ores ---
        "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore",
        "minecraft:gold_ore", "minecraft:lapis_ore", "minecraft:redstone_ore",
        "minecraft:diamond_ore", "minecraft:emerald_ore",
        "minecraft:deepslate_coal_ore", "minecraft:deepslate_iron_ore",
        "minecraft:deepslate_copper_ore", "minecraft:deepslate_gold_ore",
        "minecraft:deepslate_lapis_ore", "minecraft:deepslate_redstone_ore",
        "minecraft:deepslate_diamond_ore", "minecraft:deepslate_emerald_ore",
        // --- Wood logs ---
        "minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
        "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
        "minecraft:mangrove_log", "minecraft:cherry_log",
        "minecraft:bamboo_block",
        // --- Leaves ---
        "minecraft:oak_leaves", "minecraft:birch_leaves", "minecraft:spruce_leaves",
        "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
        "minecraft:mangrove_leaves", "minecraft:cherry_leaves",
        "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves",
        // --- Saplings ---
        "minecraft:oak_sapling", "minecraft:birch_sapling", "minecraft:spruce_sapling",
        "minecraft:jungle_sapling", "minecraft:acacia_sapling", "minecraft:dark_oak_sapling",
        "minecraft:mangrove_propagule", "minecraft:cherry_sapling",
        "minecraft:bamboo", "minecraft:azalea", "minecraft:flowering_azalea",
        // --- Fluids ---
        "minecraft:water", "minecraft:lava",
        // --- Useful / interesting ---
        "minecraft:melon", "minecraft:pumpkin", "minecraft:hay_block",
        "minecraft:bone_block", "minecraft:slime_block", "minecraft:sponge",
        "minecraft:bookshelf", "minecraft:crafting_table", "minecraft:furnace",
        "minecraft:glass", "minecraft:amethyst_block",
        "minecraft:raw_iron_block", "minecraft:raw_copper_block", "minecraft:raw_gold_block",
        "minecraft:cactus",
        // --- Biomes O' Plenty: logs ---
        "biomesoplenty:fir_log", "biomesoplenty:redwood_log", "biomesoplenty:mahogany_log",
        "biomesoplenty:jacaranda_log", "biomesoplenty:palm_log", "biomesoplenty:willow_log",
        "biomesoplenty:dead_log", "biomesoplenty:magic_log", "biomesoplenty:umbran_log",
        // --- Biomes O' Plenty: leaves ---
        "biomesoplenty:fir_leaves", "biomesoplenty:redwood_leaves", "biomesoplenty:mahogany_leaves",
        "biomesoplenty:jacaranda_leaves", "biomesoplenty:palm_leaves", "biomesoplenty:willow_leaves",
        "biomesoplenty:magic_leaves", "biomesoplenty:umbran_leaves",
        "biomesoplenty:origin_leaves", "biomesoplenty:flowering_oak_leaves",
        "biomesoplenty:orange_autumn_leaves", "biomesoplenty:yellow_autumn_leaves",
        "biomesoplenty:maple_leaves", "biomesoplenty:snowblossom_leaves",
        // --- Biomes O' Plenty: saplings ---
        "biomesoplenty:fir_sapling", "biomesoplenty:redwood_sapling", "biomesoplenty:mahogany_sapling",
        "biomesoplenty:jacaranda_sapling", "biomesoplenty:palm_sapling", "biomesoplenty:willow_sapling",
        "biomesoplenty:magic_sapling", "biomesoplenty:umbran_sapling",
        "biomesoplenty:origin_sapling", "biomesoplenty:flowering_oak_sapling",
        "biomesoplenty:orange_autumn_sapling", "biomesoplenty:yellow_autumn_sapling",
        "biomesoplenty:maple_sapling", "biomesoplenty:snowblossom_sapling",
        // --- Thermal Expansion: ores ---
        "thermal:tin_ore", "thermal:lead_ore", "thermal:silver_ore",
        "thermal:nickel_ore", "thermal:platinum_ore", "thermal:apatite_ore",
        "thermal:cinnabar_ore", "thermal:niter_ore",
        "thermal:deepslate_tin_ore", "thermal:deepslate_lead_ore", "thermal:deepslate_silver_ore",
        "thermal:deepslate_nickel_ore", "thermal:deepslate_platinum_ore",
        "thermal:deepslate_apatite_ore", "thermal:deepslate_cinnabar_ore", "thermal:deepslate_niter_ore",
        // --- Applied Energistics 2: ores ---
        "ae2:certus_quartz_ore", "ae2:deepslate_certus_quartz_ore",
        // --- Mystical Agriculture: tier 1 seeds (placed on farmland by generator) ---
        "mysticalagriculture:inferium_seeds", "mysticalagriculture:dirt_seeds",
        "mysticalagriculture:wood_seeds", "mysticalagriculture:stone_seeds",
        "mysticalagriculture:nature_seeds", "mysticalagriculture:water_seeds",
        "mysticalagriculture:coal_seeds",
        // --- Draconic Evolution: overworld ores ---
        "draconicevolution:draconium_ore", "draconicevolution:deepslate_draconium_ore",
        // --- Extreme Reactors: ores ---
        "bigreactors:yellorite_ore", "bigreactors:deepslate_yellorite_ore",
        "bigreactors:anglesite_ore", "bigreactors:benitoite_ore"
    );

    // -------------------------------------------------------------------------
    // Default nether whitelist — nether-only blocks
    // -------------------------------------------------------------------------
    private static final List<String> DEFAULT_NETHER_WHITELIST = List.of(
        // --- Terrain ---
        "minecraft:netherrack", "minecraft:soul_sand", "minecraft:soul_soil",
        "minecraft:gravel", "minecraft:basalt", "minecraft:blackstone",
        "minecraft:magma_block", "minecraft:glowstone", "minecraft:lava",
        // --- Nylium ---
        "minecraft:crimson_nylium", "minecraft:warped_nylium",
        // --- Nether wood ---
        "minecraft:crimson_stem", "minecraft:warped_stem",
        "minecraft:crimson_hyphae", "minecraft:warped_hyphae",
        // --- Nether saplings (placed on soul soil by generator) ---
        "minecraft:crimson_fungus", "minecraft:warped_fungus",
        // --- Ores ---
        "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore", "minecraft:ancient_debris",
        // --- Decorative ---
        "minecraft:nether_bricks", "minecraft:cracked_nether_bricks",
        "minecraft:chiseled_nether_bricks", "minecraft:quartz_block",
        "minecraft:bone_block", "minecraft:shroomlight",
        // --- Biomes O' Plenty: nether wood ---
        "biomesoplenty:hellbark_log", "biomesoplenty:hellbark_leaves", "biomesoplenty:hellbark_sapling",
        // --- Thermal Expansion: nether ores ---
        "thermal:niter_ore", "thermal:sulfur_ore"
    );

    // -------------------------------------------------------------------------
    // Default end whitelist — end-only blocks
    // -------------------------------------------------------------------------
    private static final List<String> DEFAULT_END_WHITELIST = List.of(
        // --- Terrain ---
        "minecraft:end_stone", "minecraft:end_stone_bricks",
        "minecraft:obsidian",
        // --- Decorative ---
        "minecraft:purpur_block", "minecraft:purpur_pillar",
        // --- Flora ---
        "minecraft:chorus_plant", "minecraft:chorus_flower",
        // --- Draconic Evolution: end ores ---
        "draconicevolution:draconium_ore"
    );

    // -------------------------------------------------------------------------
    // Per-dimension instances
    // -------------------------------------------------------------------------
    private static SkyGridConfig overworldInstance;
    private static SkyGridConfig netherInstance;
    private static SkyGridConfig endInstance;

    /** Legacy accessor — returns overworld config. */
    public static SkyGridConfig get() {
        if (overworldInstance == null)
            throw new IllegalStateException("SkyGridConfig has not been loaded yet!");
        return overworldInstance;
    }

    /** Returns the config for the given dimension key ("overworld", "nether", "end"). */
    public static SkyGridConfig getForDimension(String dim) {
        return switch (dim.toLowerCase()) {
            case "nether" -> netherInstance != null ? netherInstance : overworldInstance;
            case "end"    -> endInstance    != null ? endInstance    : overworldInstance;
            default       -> overworldInstance;
        };
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private String mode;
    private Set<String> blockList;

    public boolean isAllowed(String blockId) {
        if ("whitelist".equalsIgnoreCase(mode)) return blockList.contains(blockId);
        return !blockList.contains(blockId);
    }

    public String getMode()           { return mode; }
    public Set<String> getBlockList() { return Collections.unmodifiableSet(blockList); }

    // -------------------------------------------------------------------------
    // Load all three dimension configs
    // -------------------------------------------------------------------------
    public static void loadAll() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        overworldInstance = loadOrCreate(configDir, "skygrid.json",        DEFAULT_OVERWORLD_WHITELIST, "overworld");
        netherInstance    = loadOrCreate(configDir, "skygrid_nether.json", DEFAULT_NETHER_WHITELIST,   "nether");
        endInstance       = loadOrCreate(configDir, "skygrid_end.json",    DEFAULT_END_WHITELIST,      "end");
    }

    /** @deprecated Use {@link #loadAll()} */
    public static void load() { loadAll(); }

    private static SkyGridConfig loadOrCreate(Path configDir, String filename,
                                               List<String> defaults, String label) {
        File file = configDir.resolve(filename).toFile();
        if (!file.exists()) {
            SkyGridMod.LOGGER.info("No {} config found — creating default at {}", label, file.getPath());
            SkyGridConfig cfg = createDefault(defaults);
            save(cfg, file);
            return cfg;
        }
        SkyGridMod.LOGGER.info("Loading {} config from {}", label, file.getPath());
        SkyGridConfig cfg = loadFrom(file);
        SkyGridMod.LOGGER.info("SkyGrid {} config: mode={}, {} entries", label, cfg.mode, cfg.blockList.size());
        return cfg;
    }

    private static SkyGridConfig createDefault(List<String> defaults) {
        SkyGridConfig cfg = new SkyGridConfig();
        cfg.mode = "whitelist";
        cfg.blockList = new LinkedHashSet<>(defaults);
        return cfg;
    }

    private static SkyGridConfig loadFrom(File file) {
        try (Reader reader = new FileReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            SkyGridConfig cfg = new SkyGridConfig();
            cfg.mode = json.has("mode") ? json.get("mode").getAsString() : "whitelist";
            cfg.blockList = new LinkedHashSet<>();
            String key = "whitelist".equalsIgnoreCase(cfg.mode) ? "whitelist" : "blacklist";
            if (json.has(key))
                for (JsonElement el : json.getAsJsonArray(key))
                    cfg.blockList.add(el.getAsString());
            return cfg;
        } catch (Exception e) {
            SkyGridMod.LOGGER.error("Failed to read config — using defaults. Error: {}", e.getMessage());
            return createDefault(DEFAULT_OVERWORLD_WHITELIST);
        }
    }

    private static void save(SkyGridConfig cfg, File file) {
        try {
            file.getParentFile().mkdirs();
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            JsonObject json = new JsonObject();
            json.addProperty("_comment",
                "SkyGrid Config — mode: whitelist (only listed blocks spawn) or blacklist (all except listed)");
            json.addProperty("mode", cfg.mode);
            JsonArray arr = new JsonArray();
            for (String id : cfg.blockList) arr.add(id);
            json.add(cfg.mode, arr);
            try (Writer writer = new FileWriter(file)) {
                gson.toJson(json, writer);
            }
            SkyGridMod.LOGGER.info("Config written to {}", file.getPath());
        } catch (Exception e) {
            SkyGridMod.LOGGER.error("Failed to write config: {}", e.getMessage());
        }
    }
}

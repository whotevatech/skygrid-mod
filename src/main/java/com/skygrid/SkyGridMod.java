package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkyGridMod implements ModInitializer {

    public static final String MOD_ID = "skygrid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("SkyGrid Mod loading!");

        // Load all dimension configs — overworld, nether, and end each get their own
        SkyGridConfig.loadAll();

        // Register our custom chunk generator so Minecraft knows how to
        // serialize/deserialize it when loading a Sky Grid world.
        Registry.register(
            Registries.CHUNK_GENERATOR,
            Identifier.of(MOD_ID, "skygrid"),
            SkyGridChunkGenerator.CODEC
        );

        LOGGER.info("SkyGrid chunk generator registered successfully.");

        // Place the starter platform the first time the overworld loads
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() == World.OVERWORLD) {
                SkyGridPlatform.placeIfNeeded(world);
            }
        });

        // Register commands (/skygrid blocks, /skygrid reload, etc.)
        SkyGridCommands.register();
    }
}

package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkyGridMod implements ModInitializer {

    public static final String MOD_ID = "skygrid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("SkyGrid Mod loading!");

        // Load config first — the chunk generator uses it when building the block pool
        SkyGridConfig.load();

        // Register our custom chunk generator so Minecraft knows how to
        // serialize/deserialize it when loading a Sky Grid world.
        Registry.register(
            Registries.CHUNK_GENERATOR,
            Identifier.of(MOD_ID, "skygrid"),
            SkyGridChunkGenerator.CODEC
        );

        LOGGER.info("SkyGrid chunk generator registered successfully.");

        // Register debug commands (/skygrid blocks, /skygrid blocks log)
        SkyGridCommands.register();
    }
}

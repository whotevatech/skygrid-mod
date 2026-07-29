package com.skygrid.fabric;

import com.skygrid.SkyGridCommands;
import com.skygrid.SkyGridMod;
import com.skygrid.SkyGridPlatform;
import com.skygrid.world.SkyGridChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

public class SkyGridFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        SkyGridMod.init();

        Registry.register(
            BuiltInRegistries.CHUNK_GENERATOR,
            Identifier.fromNamespaceAndPath(SkyGridMod.MOD_ID, "skygrid"),
            SkyGridChunkGenerator.CODEC
        );
        SkyGridMod.LOGGER.info("SkyGrid chunk generator registered (Fabric).");

        ServerWorldEvents.LOAD.register((server, level) -> {
            if (level.dimension() == Level.OVERWORLD) {
                SkyGridPlatform.placeIfNeeded(level);
            }
        });

        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> SkyGridCommands.register(dispatcher));
    }
}

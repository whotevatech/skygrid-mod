package com.skygrid.neoforge;

import com.mojang.serialization.MapCodec;
import com.skygrid.SkyGridCommands;
import com.skygrid.SkyGridMod;
import com.skygrid.SkyGridPlatform;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(SkyGridMod.MOD_ID)
public class SkyGridNeoForge {

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, SkyGridMod.MOD_ID);

    // NOTE: ID unified with Fabric. The old NeoForge build registered
    // "skygrid_generator" — existing NeoForge worlds will not load.
    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<SkyGridChunkGenerator>>
        SKYGRID_GENERATOR = CHUNK_GENERATORS.register("skygrid", () -> SkyGridChunkGenerator.CODEC);

    public SkyGridNeoForge(IEventBus modEventBus) {
        SkyGridMod.init();
        CHUNK_GENERATORS.register(modEventBus);

        // Client-only setup (config screen button, /skygridgui) lives in
        // SkyGridNeoForgeClient, which is its own @Mod class scoped to
        // Dist.CLIENT — so a dedicated server never loads it.

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
            SkyGridCommands.register(event.getDispatcher()));

        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {   // VERIFY
            if (event.getLevel() instanceof ServerLevel serverLevel
                    && serverLevel.dimension() == Level.OVERWORLD) {
                SkyGridPlatform.placeIfNeeded(serverLevel);
            }
        });

        SkyGridMod.LOGGER.info("SkyGrid chunk generator registered (NeoForge).");
    }
}

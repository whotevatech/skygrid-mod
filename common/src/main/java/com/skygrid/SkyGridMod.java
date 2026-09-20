package com.skygrid;

import com.skygrid.platform.Services;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic entry point. Each loader module calls init() once,
 * after its own registries are available.
 */
public final class SkyGridMod {

    public static final String MOD_ID = "skygrid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private SkyGridMod() {}

    public static void init() {
        LOGGER.info("SkyGrid starting on {}", Services.PLATFORM.getPlatformName());
        SkyGridConfig.loadAll();
    }

    /**
     * Dedicated servers do not go through Create World, so they will generate
     * vanilla terrain unless {@code level-type=skygrid:skygrid} is set in
     * {@code server.properties} before the world exists.
     */
    public static void onServerStarted(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        if (server.overworld().getChunkSource().getGenerator() instanceof SkyGridChunkGenerator) {
            LOGGER.info("Dedicated server overworld is using Sky Grid.");
        } else {
            LOGGER.warn("Dedicated server overworld is NOT using Sky Grid. "
                + "Set level-type=skygrid:skygrid in server.properties BEFORE the world is generated. "
                + "Changing it later does not convert an existing world.");
        }
    }
}

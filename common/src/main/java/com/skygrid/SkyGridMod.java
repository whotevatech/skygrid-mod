package com.skygrid;

import com.skygrid.platform.Services;
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
}

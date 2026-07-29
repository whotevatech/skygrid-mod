package com.skygrid.platform;

import com.skygrid.SkyGridMod;

import java.util.ServiceLoader;

/**
 * ServiceLoader-based platform lookup.
 *
 * This is the standard "MultiLoader" pattern: common code calls
 * Services.PLATFORM.something(), and at runtime the correct loader
 * implementation is discovered from the classpath.
 */
public final class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {}

    public static <T> T load(Class<T> clazz) {
        final T loaded = ServiceLoader.load(clazz)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No implementation of " + clazz.getName() + " found on the classpath. "
              + "Check that META-INF/services is present in the loader jar."));

        SkyGridMod.LOGGER.debug("Loaded platform service {}", clazz.getSimpleName());
        return loaded;
    }
}

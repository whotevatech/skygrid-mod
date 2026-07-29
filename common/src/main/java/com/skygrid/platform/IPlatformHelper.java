package com.skygrid.platform;

import java.nio.file.Path;

/**
 * The only thing the common module needs from a mod loader.
 *
 * Each loader module provides an implementation and registers it via
 * META-INF/services/com.skygrid.platform.IPlatformHelper (Java ServiceLoader).
 *
 * Keep this interface as small as possible — every method added here has to be
 * implemented twice, and every MC version bump is cheaper the smaller it stays.
 */
public interface IPlatformHelper {

    /** Human-readable loader name, used in logs. */
    String getPlatformName();

    /** The instance's config directory, e.g. .minecraft/config */
    Path getConfigDir();
}

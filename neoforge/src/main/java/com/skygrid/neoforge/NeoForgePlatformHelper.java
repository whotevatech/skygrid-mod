package com.skygrid.neoforge;

import com.skygrid.platform.IPlatformHelper;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}

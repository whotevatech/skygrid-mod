package com.skygrid.fabric;

import com.skygrid.client.SkyGridConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Adds the config button to ModMenu's mod list.
 *
 * ModMenu is an OPTIONAL runtime dependency (modCompileOnly in build.gradle).
 * This class is only ever instantiated by ModMenu itself, via the "modmenu"
 * entrypoint in fabric.mod.json — so if ModMenu is absent, the class is never
 * loaded and nothing breaks. /skygridgui works either way.
 *
 * NeoForge needs no equivalent: IConfigScreenFactory is part of the loader.
 */
public class SkyGridModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SkyGridConfigScreen::new;
    }
}

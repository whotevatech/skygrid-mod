package com.skygrid.neoforge;

import com.skygrid.SkyGridMod;
import com.skygrid.client.SkyGridClientCommands;
import com.skygrid.client.SkyGridConfigScreen;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entry point: the Mods-screen config button, and /skygridgui.
 *
 * This is a second @Mod class scoped to Dist.CLIENT, so NeoForge only constructs
 * it on the client and a dedicated server never touches client classes.
 *
 * The command is deliberately NOT "/skygrid gui": the server-side /skygrid tree
 * already exists, and a client command on that root would shadow its subcommands.
 *
 * VERIFY on 1.21.1 / NeoForge 21.1 — two things may differ from 21.11:
 *   1. @Mod's `dist` attribute may not exist. If it doesn't, drop it and guard
 *      the body with FMLEnvironment instead, or move this to an FMLClientSetup
 *      event listener on the main mod class.
 *   2. IConfigScreenFactory may live in net.neoforged.neoforge.client rather
 *      than .client.gui, and older NeoForge used ConfigScreenHandler.
 * The compiler will say which.
 */
@Mod(value = SkyGridMod.MOD_ID, dist = Dist.CLIENT)
public class SkyGridNeoForgeClient {

    public SkyGridNeoForgeClient(ModContainer container) {
        // Adds the config button next to the mod on the Mods screen.
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (mc, parent) -> SkyGridConfigScreen.create(parent));

        NeoForge.EVENT_BUS.addListener(SkyGridNeoForgeClient::onRegisterClientCommands);

        SkyGridMod.LOGGER.info("SkyGrid client hooks registered (NeoForge).");
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("skygridgui")
                .executes(ctx -> {
                    if (!SkyGridClientCommands.ready()) {
                        ctx.getSource().sendFailure(SkyGridClientCommands.notReadyMessage());
                        return 0;
                    }
                    SkyGridClientCommands.openScreen();
                    return 1;
                }));
    }
}

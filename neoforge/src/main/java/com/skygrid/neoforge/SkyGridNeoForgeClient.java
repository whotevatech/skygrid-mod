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
 * This is a second @Mod class scoped to Dist.CLIENT. NeoForge only constructs it
 * on the client, which means a dedicated server never touches IConfigScreenFactory
 * or any other client class — no environment check needed at all.
 *
 * The command is deliberately NOT "/skygrid gui": the server-side /skygrid tree
 * already exists, and a client command on that root would shadow its subcommands.
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

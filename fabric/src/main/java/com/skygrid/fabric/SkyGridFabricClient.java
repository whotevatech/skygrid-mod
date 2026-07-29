package com.skygrid.fabric;

import com.skygrid.client.SkyGridClientCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

/**
 * Registers /skygridgui — the client-side command that opens the block editor.
 *
 * Deliberately NOT "/skygrid gui": the server-side /skygrid tree already exists,
 * and a client command sharing that root would shadow its subcommands.
 */
public class SkyGridFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                ClientCommandManager.literal("skygridgui")
                    .executes(ctx -> {
                        if (!SkyGridClientCommands.ready()) {
                            ctx.getSource().sendFeedback(SkyGridClientCommands.notReadyMessage());
                            return 0;
                        }
                        SkyGridClientCommands.openScreen();
                        return 1;
                    })));
    }
}

package com.skygrid.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Shared client-side behaviour for /skygrid gui.
 *
 * The command is registered through each loader's CLIENT command event, so no
 * networking is involved — it runs on the client and opens the screen directly.
 * The command tree itself is built per loader, because Fabric and NeoForge use
 * different command source types.
 */
public final class SkyGridClientCommands {

    private SkyGridClientCommands() {}

    /**
     * Opens the block pool editor.
     *
     * Deferred to the next tick: the chat screen is still open at the moment a
     * command executes, and it would immediately replace ours on close.
     */
    public static void openScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new SkyGridConfigScreen(null)));
    }

    /** Config loads on mod init, but guard anyway so we fail with a message. */
    public static boolean ready() {
        return SkyGridConfigScreen.isReady();
    }

    public static Component notReadyMessage() {
        return Component.literal("§c[SkyGrid] Config not loaded yet.");
    }
}

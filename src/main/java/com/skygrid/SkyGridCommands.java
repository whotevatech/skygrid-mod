package com.skygrid;

import com.skygrid.world.SkyGridChunkGenerator;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.block.BlockState;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers the /skygrid command for in-game debugging.
 *
 * Usage:
 *   /skygrid blocks        — shows how many blocks are in the pool + lists them in chat
 *   /skygrid blocks log    — dumps the full block list to the game log file
 */
public class SkyGridCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                literal("skygrid")
                    .then(literal("blocks")
                        // /skygrid blocks — show summary + first 50 in chat
                        .executes(ctx -> listBlocks(ctx.getSource(), false))
                        // /skygrid blocks log — dump full list to log
                        .then(literal("log")
                            .executes(ctx -> listBlocks(ctx.getSource(), true))
                        )
                    )
                    // /skygrid reload — reload all dimension configs from disk
                    .then(literal("reload")
                        .executes(ctx -> reloadConfig(ctx.getSource()))
                    )
            );
        });
    }

    private static int reloadConfig(ServerCommandSource source) {
        SkyGridConfig.loadAll();
        SkyGridChunkGenerator.clearPools();
        source.sendFeedback(() -> Text.literal(
            "§a[SkyGrid] §fAll dimension configs reloaded. Block pools will rebuild on next chunk generation."
        ), true);
        SkyGridMod.LOGGER.info("SkyGrid configs reloaded via command.");
        return 1;
    }

    private static int listBlocks(ServerCommandSource source, boolean logOnly) {
        BlockState[] pool = SkyGridChunkGenerator.getPublicBlockPool();

        if (pool == null || pool.length == 0) {
            source.sendFeedback(() -> Text.literal("§cSkyGrid block pool is empty or not built yet!"), false);
            return 0;
        }

        // Always show the summary in chat
        source.sendFeedback(() -> Text.literal(
            "§a[SkyGrid] §f" + pool.length + " blocks in pool (mode: §e"
            + SkyGridConfig.get().getMode() + "§f)"
        ), false);

        if (logOnly) {
            // Dump full list to the game log
            SkyGridMod.LOGGER.info("=== SkyGrid Block Pool ({} blocks) ===", pool.length);
            for (BlockState state : pool) {
                SkyGridMod.LOGGER.info("  {}", net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()));
            }
            SkyGridMod.LOGGER.info("=== End of SkyGrid Block Pool ===");
            source.sendFeedback(() -> Text.literal("§a[SkyGrid] §fFull list dumped to game log."), false);
        } else {
            // Show up to 50 blocks in chat
            int limit = Math.min(pool.length, 50);
            source.sendFeedback(() -> Text.literal(
                "§7Showing first " + limit + " of " + pool.length + " — use §f/skygrid blocks log§7 for full list:"
            ), false);

            for (int i = 0; i < limit; i++) {
                String id = net.minecraft.registry.Registries.BLOCK.getId(pool[i].getBlock()).toString();
                final String display = "§7" + (i + 1) + ". §f" + id;
                source.sendFeedback(() -> Text.literal(display), false);
            }
        }

        return 1;
    }
}

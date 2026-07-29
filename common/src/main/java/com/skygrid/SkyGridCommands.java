package com.skygrid;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/**
 * /skygrid blocks [log]                       — inspect the active block pool
 * /skygrid reload                             — reload all dimension configs
 * /skygrid add <block> [weight] [dimension]   — add a block, or change its weight
 * /skygrid remove <block> [dimension]         — remove a block
 * /skygrid weight <block> <n> [dimension]     — set weight only (block must exist)
 *
 * Loader-agnostic: each loader module calls register(dispatcher) from its own
 * command-registration event.
 *
 * Edits write straight back to the JSON config and clear the cached pools, so
 * they take effect for NEWLY GENERATED chunks. Existing terrain never changes.
 */
public final class SkyGridCommands {

    private static final String[] DIMENSIONS = { "overworld", "nether", "end" };

    private SkyGridCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("skygrid")
                // 1.21.11 replaced integer permission levels with PermissionSet/
                // PermissionCheck. LEVEL_GAMEMASTERS is the old level 2.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                .then(Commands.literal("blocks")
                    .executes(ctx -> listBlocks(ctx.getSource(), false))
                    .then(Commands.literal("log")
                        .executes(ctx -> listBlocks(ctx.getSource(), true))))

                .then(Commands.literal("reload")
                    .executes(ctx -> reloadConfig(ctx.getSource())))

                .then(addCommand())
                .then(removeCommand())
                .then(weightCommand())
        );
    }

    // -------------------------------------------------------------------------
    // add
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> addCommand() {
        return Commands.literal("add")
            .then(Commands.argument("block", IdentifierArgument.id())
                .suggests(SkyGridCommands::suggestBlocks)
                .executes(ctx -> edit(ctx.getSource(),
                        IdentifierArgument.getId(ctx, "block"), 1, "overworld", Action.ADD))
                .then(Commands.argument("weight", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> edit(ctx.getSource(),
                            IdentifierArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            "overworld", Action.ADD))
                    .then(dimensionArg((src, dim, ctx) -> edit(src,
                            IdentifierArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            dim, Action.ADD)))));
    }

    // -------------------------------------------------------------------------
    // remove
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> removeCommand() {
        return Commands.literal("remove")
            .then(Commands.argument("block", IdentifierArgument.id())
                .suggests(SkyGridCommands::suggestConfiguredBlocks)
                .executes(ctx -> edit(ctx.getSource(),
                        IdentifierArgument.getId(ctx, "block"), 0, "overworld", Action.REMOVE))
                .then(dimensionArg((src, dim, ctx) -> edit(src,
                        IdentifierArgument.getId(ctx, "block"), 0, dim, Action.REMOVE))));
    }

    // -------------------------------------------------------------------------
    // weight
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> weightCommand() {
        return Commands.literal("weight")
            .then(Commands.argument("block", IdentifierArgument.id())
                .suggests(SkyGridCommands::suggestConfiguredBlocks)
                .then(Commands.argument("weight", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> edit(ctx.getSource(),
                            IdentifierArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            "overworld", Action.WEIGHT))
                    .then(dimensionArg((src, dim, ctx) -> edit(src,
                            IdentifierArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            dim, Action.WEIGHT)))));
    }

    // -------------------------------------------------------------------------
    // Shared plumbing
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface DimensionAction {
        int run(CommandSourceStack source, String dimension,
                com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
            dimensionArg(DimensionAction action) {
        return Commands.argument("dimension", com.mojang.brigadier.arguments.StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DIMENSIONS, builder))
            .executes(ctx -> {
                String dim = com.mojang.brigadier.arguments.StringArgumentType
                    .getString(ctx, "dimension").toLowerCase();
                if (!isValidDimension(dim)) {
                    ctx.getSource().sendFailure(Component.literal(
                        "§cUnknown dimension '" + dim + "' — expected overworld, nether or end."));
                    return 0;
                }
                return action.run(ctx.getSource(), dim, ctx);
            });
    }

    private static boolean isValidDimension(String dim) {
        for (String d : DIMENSIONS) if (d.equals(dim)) return true;
        return false;
    }

    private enum Action { ADD, REMOVE, WEIGHT }

    private static int edit(CommandSourceStack source, Identifier blockId,
                            int weight, String dimension, Action action) {

        String id = blockId.toString();

        if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
            source.sendFailure(Component.literal(
                "§cNo such block: §f" + id + "§c — it is not registered. Check the mod is installed."));
            return 0;
        }

        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);
        boolean changed;
        String message;

        switch (action) {
            case ADD -> {
                boolean existed = config.contains(id);
                changed = config.addOrUpdate(id, weight);
                if (!changed) {
                    source.sendSuccess(() -> Component.literal(
                        "§e[SkyGrid] §f" + id + " is already in the " + dimension
                      + " pool with weight " + weight + " — nothing to do."), false);
                    return 1;
                }
                message = existed
                    ? "§a[SkyGrid] §fUpdated §e" + id + "§f in " + dimension + " to weight §e" + weight
                    : "§a[SkyGrid] §fAdded §e" + id + "§f to " + dimension + " (weight " + weight + ")";
            }
            case REMOVE -> {
                changed = config.remove(id);
                if (!changed) {
                    source.sendFailure(Component.literal(
                        "§c" + id + " is not in the " + dimension + " pool."));
                    return 0;
                }
                message = "§a[SkyGrid] §fRemoved §e" + id + "§f from " + dimension;
            }
            case WEIGHT -> {
                if (!config.contains(id)) {
                    source.sendFailure(Component.literal(
                        "§c" + id + " is not in the " + dimension
                      + " pool. Use §f/skygrid add§c first."));
                    return 0;
                }
                changed = config.addOrUpdate(id, weight);
                message = "§a[SkyGrid] §fSet §e" + id + "§f weight to §e" + weight + "§f in " + dimension;
            }
            default -> { return 0; }
        }

        config.saveToDisk();
        SkyGridChunkGenerator.clearPools();

        final String feedback = message;
        source.sendSuccess(() -> Component.literal(feedback), true);
        source.sendSuccess(() -> Component.literal(
            "§7Applies to newly generated chunks only — existing terrain is unchanged."), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Suggestions
    // -------------------------------------------------------------------------

    /** Every registered block — vanilla and modded. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestBlocks(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                          com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet().stream(), builder);
    }

    /** Only blocks already in the overworld config — keeps remove/weight lists short. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            suggestConfiguredBlocks(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                    com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(
            SkyGridConfig.getForDimension("overworld").getBlockEntries().stream()
                .map(e -> Identifier.parse(e.id())),
            builder);
    }

    // -------------------------------------------------------------------------
    // blocks / reload
    // -------------------------------------------------------------------------

    private static int reloadConfig(CommandSourceStack source) {
        SkyGridConfig.loadAll();
        SkyGridChunkGenerator.clearPools();
        source.sendSuccess(() -> Component.literal(
            "§a[SkyGrid] §fAll dimension configs reloaded. Pools rebuild on next chunk generation."
        ), true);
        SkyGridMod.LOGGER.info("SkyGrid configs reloaded via command.");
        return 1;
    }

    private static int listBlocks(CommandSourceStack source, boolean logOnly) {
        BlockState[] pool = SkyGridChunkGenerator.getPublicBlockPool();

        if (pool == null || pool.length == 0) {
            source.sendSuccess(() -> Component.literal(
                "§cSkyGrid block pool is empty or not built yet!"), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "§a[SkyGrid] §f" + pool.length + " weighted slots (mode: §e"
          + SkyGridConfig.get().getMode() + "§f)"), false);

        if (logOnly) {
            SkyGridMod.LOGGER.info("=== SkyGrid Block Pool ({} slots) ===", pool.length);
            for (BlockState state : pool) {
                SkyGridMod.LOGGER.info("  {}", BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            }
            SkyGridMod.LOGGER.info("=== End of SkyGrid Block Pool ===");
            source.sendSuccess(() -> Component.literal(
                "§a[SkyGrid] §fFull list dumped to game log."), false);
        } else {
            int limit = Math.min(pool.length, 50);
            source.sendSuccess(() -> Component.literal(
                "§7Showing first " + limit + " of " + pool.length
              + " — use §f/skygrid blocks log§7 for the full list:"), false);
            for (int i = 0; i < limit; i++) {
                final String line = "§7" + (i + 1) + ". §f"
                    + BuiltInRegistries.BLOCK.getKey(pool[i].getBlock());
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }
}

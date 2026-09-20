package com.skygrid;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * /skygrid                                 — status + command list
 * /skygrid blocks [log] [dimension]        — inspect the active block pool
 * /skygrid reload                          — reload all dimension configs
 * /skygrid add <block> [weight] [dimension]
 * /skygrid remove <block> [dimension]
 * /skygrid weight <block> <n> [dimension]
 * /skygrid tag add|remove ...
 * /skygrid chance [spawner|chest|cluster] [value] [dimension]
 *
 * Loader-agnostic: each loader module calls register(dispatcher) from its own
 * command-registration event.
 *
 * Edits write straight back to the JSON config and clear the cached pools, so
 * they take effect for NEWLY GENERATED chunks. Existing terrain never changes.
 */
public final class SkyGridCommands {

    private static final String[] DIMENSIONS = { "overworld", "nether", "end" };
    private static final String[] CHANCE_TYPES = { "spawner", "chest", "cluster" };

    private SkyGridCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("skygrid")
                // 1.21.1 still uses integer permission levels. (1.21.11 replaced
                // these with PermissionSet/PermissionCheck.) Level 2 = gamemasters.
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> status(ctx.getSource()))

                .then(Commands.literal("help")
                    .executes(ctx -> status(ctx.getSource())))

                .then(Commands.literal("blocks")
                    .executes(ctx -> listBlocks(ctx.getSource(), false, "overworld"))
                    .then(Commands.literal("log")
                        .executes(ctx -> listBlocks(ctx.getSource(), true, "overworld"))
                        .then(dimensionArg((src, dim, ctx) -> listBlocks(src, true, dim))))
                    .then(dimensionArg((src, dim, ctx) -> listBlocks(src, false, dim))))

                .then(Commands.literal("reload")
                    .executes(ctx -> reloadConfig(ctx.getSource())))

                .then(addCommand())
                .then(removeCommand())
                .then(weightCommand())
                .then(tagCommand())
                .then(chanceCommand())
        );
    }

    // -------------------------------------------------------------------------
    // status / help
    // -------------------------------------------------------------------------
    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> prefix().append(
            Component.translatable("command.skygrid.help.header").withStyle(ChatFormatting.WHITE)), false);
        source.sendSuccess(() -> Component.translatable("command.skygrid.help.line1")
            .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("command.skygrid.help.line2")
            .withStyle(ChatFormatting.GRAY), false);

        MinecraftServer server = source.getServer();
        ServerLevel overworld = server.overworld();
        ChunkGenerator gen = overworld.getChunkSource().getGenerator();
        if (gen instanceof SkyGridChunkGenerator sky) {
            source.sendSuccess(() -> Component.translatable(
                "command.skygrid.status.world",
                yellow(sky.getDimension()),
                Component.literal(Integer.toString(sky.getGridSpacing())),
                Component.literal(Boolean.toString(sky.isSeedAware()))
            ).withStyle(ChatFormatting.GRAY), false);
        } else if (server.isDedicatedServer()) {
            source.sendSuccess(() -> Component.translatable("command.skygrid.status.not_skygrid")
                .withStyle(ChatFormatting.YELLOW), false);
        }

        for (String dim : DIMENSIONS) {
            SkyGridConfig cfg = SkyGridConfig.getForDimension(dim);
            source.sendSuccess(() -> Component.translatable(
                "command.skygrid.status.dim",
                yellow(dim),
                Component.literal(Integer.toString(cfg.getBlockEntries().size())),
                Component.literal(cfg.getMode()),
                pct(cfg.getChestChance()),
                pct(cfg.getSpawnerChance()),
                pct(cfg.getOreClusterChance())
            ).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // -------------------------------------------------------------------------
    // add
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> addCommand() {
        return Commands.literal("add")
            .then(Commands.argument("block", ResourceLocationArgument.id())
                .suggests(SkyGridCommands::suggestBlocks)
                .executes(ctx -> edit(ctx.getSource(),
                        ResourceLocationArgument.getId(ctx, "block"), 1, "overworld", Action.ADD))
                .then(Commands.argument("weight", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> edit(ctx.getSource(),
                            ResourceLocationArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            "overworld", Action.ADD))
                    .then(dimensionArg((src, dim, ctx) -> edit(src,
                            ResourceLocationArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            dim, Action.ADD)))));
    }

    // -------------------------------------------------------------------------
    // remove
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> removeCommand() {
        return Commands.literal("remove")
            .then(Commands.argument("block", ResourceLocationArgument.id())
                .suggests(SkyGridCommands::suggestConfiguredBlocks)
                .executes(ctx -> edit(ctx.getSource(),
                        ResourceLocationArgument.getId(ctx, "block"), 0, "overworld", Action.REMOVE))
                .then(dimensionArg((src, dim, ctx) -> edit(src,
                        ResourceLocationArgument.getId(ctx, "block"), 0, dim, Action.REMOVE))));
    }

    // -------------------------------------------------------------------------
    // weight
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> weightCommand() {
        return Commands.literal("weight")
            .then(Commands.argument("block", ResourceLocationArgument.id())
                .suggests(SkyGridCommands::suggestConfiguredBlocks)
                .then(Commands.argument("weight", IntegerArgumentType.integer(1, 1000))
                    .executes(ctx -> edit(ctx.getSource(),
                            ResourceLocationArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            "overworld", Action.WEIGHT))
                    .then(dimensionArg((src, dim, ctx) -> edit(src,
                            ResourceLocationArgument.getId(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "weight"),
                            dim, Action.WEIGHT)))));
    }

    // -------------------------------------------------------------------------
    // tag — add or remove a whole block tag
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> tagCommand() {
        return Commands.literal("tag")
            .then(Commands.literal("add")
                .then(Commands.argument("tag", ResourceLocationArgument.id())
                    .suggests(SkyGridCommands::suggestBlockTags)
                    .executes(ctx -> editTag(ctx.getSource(),
                            ResourceLocationArgument.getId(ctx, "tag"), 1, "overworld", true))
                    .then(Commands.argument("weight", IntegerArgumentType.integer(1, 1000))
                        .executes(ctx -> editTag(ctx.getSource(),
                                ResourceLocationArgument.getId(ctx, "tag"),
                                IntegerArgumentType.getInteger(ctx, "weight"),
                                "overworld", true))
                        .then(dimensionArg((src, dim, ctx) -> editTag(src,
                                ResourceLocationArgument.getId(ctx, "tag"),
                                IntegerArgumentType.getInteger(ctx, "weight"),
                                dim, true))))))
            .then(Commands.literal("remove")
                .then(Commands.argument("tag", ResourceLocationArgument.id())
                    .suggests(SkyGridCommands::suggestConfiguredTags)
                    .executes(ctx -> editTag(ctx.getSource(),
                            ResourceLocationArgument.getId(ctx, "tag"), 0, "overworld", false))
                    .then(dimensionArg((src, dim, ctx) -> editTag(src,
                            ResourceLocationArgument.getId(ctx, "tag"), 0, dim, false)))));
    }

    // -------------------------------------------------------------------------
    // chance — view or set generation rates
    // -------------------------------------------------------------------------
    private static LiteralArgumentBuilder<CommandSourceStack> chanceCommand() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("chance")
            .executes(ctx -> showChances(ctx.getSource(), "overworld"))
            .then(dimensionArg((src, dim, ctx) -> showChances(src, dim)));

        for (String type : CHANCE_TYPES) {
            final String t = type;
            root.then(Commands.literal(t)
                .executes(ctx -> {
                    showChances(ctx.getSource(), "overworld");
                    return 1;
                })
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
                    .executes(ctx -> setChance(ctx.getSource(), t,
                            DoubleArgumentType.getDouble(ctx, "value"), "overworld"))
                    .then(dimensionArg((src, dim, ctx) -> setChance(src, t,
                            DoubleArgumentType.getDouble(ctx, "value"), dim)))));
        }
        return root;
    }

    private static int showChances(CommandSourceStack source, String dimension) {
        SkyGridConfig cfg = SkyGridConfig.getForDimension(dimension);
        source.sendSuccess(() -> prefix().append(Component.translatable(
            "command.skygrid.chance.header", yellow(dimension)).withStyle(ChatFormatting.WHITE)), false);
        source.sendSuccess(() -> chanceLine("spawner", cfg.getSpawnerChance()), false);
        source.sendSuccess(() -> chanceLine("chest", cfg.getChestChance()), false);
        source.sendSuccess(() -> chanceLine("cluster", cfg.getOreClusterChance()), false);
        return 1;
    }

    private static Component chanceLine(String type, double value) {
        return Component.translatable("command.skygrid.chance.line",
            yellow(type),
            Component.literal(String.format(Locale.ROOT, "%.4f", value)),
            pct(value)
        ).withStyle(ChatFormatting.GRAY);
    }

    private static int setChance(CommandSourceStack source, String type, double value, String dimension) {
        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);
        boolean changed = switch (type.toLowerCase(Locale.ROOT)) {
            case "spawner" -> config.setSpawnerChance(value);
            case "chest"   -> config.setChestChance(value);
            case "cluster" -> config.setOreClusterChance(value);
            default -> {
                source.sendFailure(Component.translatable("command.skygrid.chance.unknown", type)
                    .withStyle(ChatFormatting.RED));
                yield false;
            }
        };
        if (!isChanceType(type)) return 0;

        double stored = switch (type.toLowerCase(Locale.ROOT)) {
            case "spawner" -> config.getSpawnerChance();
            case "chest"   -> config.getChestChance();
            default        -> config.getOreClusterChance();
        };

        if (!changed) {
            source.sendSuccess(() -> prefix().append(Component.translatable(
                "command.skygrid.chance.noop", yellow(type), yellow(dimension),
                Component.literal(String.format(Locale.ROOT, "%.4f", stored)), pct(stored)
            ).withStyle(ChatFormatting.YELLOW)), false);
            return 1;
        }

        config.saveToDisk();
        SkyGridChunkGenerator.clearPools();
        source.sendSuccess(() -> prefix().append(Component.translatable(
            "command.skygrid.chance.set", yellow(type), yellow(dimension),
            Component.literal(String.format(Locale.ROOT, "%.4f", stored)), pct(stored)
        ).withStyle(ChatFormatting.WHITE)), true);
        source.sendSuccess(() -> Component.translatable("command.skygrid.chunk_note")
            .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static boolean isChanceType(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        for (String c : CHANCE_TYPES) if (c.equals(t)) return true;
        return false;
    }

    private static int editTag(CommandSourceStack source, ResourceLocation tagId,
                               int weight, String dimension, boolean add) {

        String stored = "#" + tagId;   // config stores tags with a leading '#'
        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);

        if (add) {
            // Warn, but do not block: the tag may belong to a mod added later.
            TagKey<Block> key = TagKey.create(Registries.BLOCK, tagId);
            int matched = 0;
            for (Holder<Block> ignored : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) matched++;

            config.addOrUpdate(stored, weight);
            config.saveToDisk();
            SkyGridChunkGenerator.clearPools();

            final int n = matched;
            source.sendSuccess(() -> prefix().append(Component.translatable(
                "command.skygrid.tag.add.ok", yellow(stored), yellow(dimension),
                Component.literal(Integer.toString(weight)),
                Component.literal(Integer.toString(n))
            ).withStyle(ChatFormatting.WHITE)), true);
            if (matched == 0) {
                source.sendSuccess(() -> Component.translatable("command.skygrid.tag.add.empty")
                    .withStyle(ChatFormatting.YELLOW), false);
            }
        } else {
            if (!config.remove(stored)) {
                source.sendFailure(Component.translatable("command.skygrid.tag.remove.missing",
                    stored, dimension).withStyle(ChatFormatting.RED));
                return 0;
            }
            config.saveToDisk();
            SkyGridChunkGenerator.clearPools();
            source.sendSuccess(() -> prefix().append(Component.translatable(
                "command.skygrid.tag.remove.ok", yellow(stored), yellow(dimension)
            ).withStyle(ChatFormatting.WHITE)), true);
        }

        source.sendSuccess(() -> Component.translatable("command.skygrid.chunk_note")
            .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Shared plumbing
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface DimensionAction {
        int run(CommandSourceStack source, String dimension, CommandContext<CommandSourceStack> ctx);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String>
            dimensionArg(DimensionAction action) {
        return Commands.argument("dimension", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DIMENSIONS, builder))
            .executes(ctx -> {
                String dim = StringArgumentType.getString(ctx, "dimension").toLowerCase(Locale.ROOT);
                if (!isValidDimension(dim)) {
                    ctx.getSource().sendFailure(Component.translatable(
                        "command.skygrid.unknown_dimension", dim).withStyle(ChatFormatting.RED));
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

    private static int edit(CommandSourceStack source, ResourceLocation blockId,
                            int weight, String dimension, Action action) {

        String id = blockId.toString();

        if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
            source.sendFailure(Component.translatable("command.skygrid.unknown_block", id)
                .withStyle(ChatFormatting.RED));
            return 0;
        }

        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);
        boolean changed;
        Component message;

        switch (action) {
            case ADD -> {
                boolean existed = config.contains(id);
                changed = config.addOrUpdate(id, weight);
                if (!changed) {
                    source.sendSuccess(() -> prefix().append(Component.translatable(
                        "command.skygrid.add.noop", yellow(id), yellow(dimension),
                        Component.literal(Integer.toString(weight))
                    ).withStyle(ChatFormatting.YELLOW)), false);
                    return 1;
                }
                message = Component.translatable(
                    existed ? "command.skygrid.add.update" : "command.skygrid.add.ok",
                    yellow(id), yellow(dimension), Component.literal(Integer.toString(weight)));
            }
            case REMOVE -> {
                changed = config.remove(id);
                if (!changed) {
                    source.sendFailure(Component.translatable("command.skygrid.remove.missing",
                        id, dimension).withStyle(ChatFormatting.RED));
                    return 0;
                }
                message = Component.translatable("command.skygrid.remove.ok",
                    yellow(id), yellow(dimension));
            }
            case WEIGHT -> {
                if (!config.contains(id)) {
                    source.sendFailure(Component.translatable("command.skygrid.weight.missing",
                        id, dimension).withStyle(ChatFormatting.RED));
                    return 0;
                }
                changed = config.addOrUpdate(id, weight);
                if (!changed) {
                    source.sendSuccess(() -> prefix().append(Component.translatable(
                        "command.skygrid.weight.noop", yellow(id),
                        Component.literal(Integer.toString(weight)), yellow(dimension)
                    ).withStyle(ChatFormatting.YELLOW)), false);
                    return 1;
                }
                message = Component.translatable("command.skygrid.weight.ok",
                    yellow(id), Component.literal(Integer.toString(weight)), yellow(dimension));
            }
            default -> { return 0; }
        }

        config.saveToDisk();
        SkyGridChunkGenerator.clearPools();

        final Component feedback = message;
        source.sendSuccess(() -> prefix().append(feedback.copy().withStyle(ChatFormatting.WHITE)), true);
        source.sendSuccess(() -> Component.translatable("command.skygrid.chunk_note")
            .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Suggestions
    // -------------------------------------------------------------------------

    /** Every registered block — vanilla and modded. */
    private static CompletableFuture<Suggestions>
            suggestBlocks(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet().stream(), builder);
    }

    /** Every block tag the game knows about. */
    private static CompletableFuture<Suggestions>
            suggestBlockTags(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(
            BuiltInRegistries.BLOCK.getTags().map(p -> p.getFirst().location()), builder);
    }

    /** Tags already in any dimension pool. */
    private static CompletableFuture<Suggestions>
            suggestConfiguredTags(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(configuredTagIds(), builder);
    }

    /** Blocks already in any dimension pool — tags excluded so parse never throws. */
    private static CompletableFuture<Suggestions>
            suggestConfiguredBlocks(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(configuredBlockIds(), builder);
    }

    private static Stream<ResourceLocation> configuredBlockIds() {
        return Arrays.stream(DIMENSIONS)
            .flatMap(dim -> SkyGridConfig.getForDimension(dim).getBlockEntries().stream())
            .filter(e -> !e.isTag())
            .map(e -> ResourceLocation.tryParse(e.id()))
            .filter(Objects::nonNull)
            .distinct();
    }

    private static Stream<ResourceLocation> configuredTagIds() {
        return Arrays.stream(DIMENSIONS)
            .flatMap(dim -> SkyGridConfig.getForDimension(dim).getBlockEntries().stream())
            .filter(SkyGridConfig.BlockEntry::isTag)
            .map(e -> ResourceLocation.tryParse(e.tagId()))
            .filter(Objects::nonNull)
            .distinct();
    }

    // -------------------------------------------------------------------------
    // blocks / reload
    // -------------------------------------------------------------------------

    private static int reloadConfig(CommandSourceStack source) {
        SkyGridConfig.loadAll();
        SkyGridChunkGenerator.clearPools();
        source.sendSuccess(() -> prefix().append(
            Component.translatable("command.skygrid.reload.success").withStyle(ChatFormatting.WHITE)
        ), true);
        SkyGridMod.LOGGER.info("SkyGrid configs reloaded via command.");
        return 1;
    }

    private static int listBlocks(CommandSourceStack source, boolean logOnly, String dimension) {
        SkyGridConfig config = SkyGridConfig.getForDimension(dimension);
        BlockState[] pool = SkyGridChunkGenerator.getPublicBlockPool(dimension);

        int unique = 0;
        int slots = pool == null ? 0 : pool.length;
        if (pool != null) {
            unique = (int) Arrays.stream(pool).map(BlockState::getBlock).distinct().count();
        }

        if (config.getBlockEntries().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.skygrid.blocks.empty", dimension)
                .withStyle(ChatFormatting.RED), false);
            return 0;
        }

        final int uniqueF = unique;
        final int slotsF = slots;
        source.sendSuccess(() -> prefix().append(Component.translatable(
            "command.skygrid.blocks.header",
            yellow(dimension),
            Component.literal(Integer.toString(uniqueF)),
            Component.literal(Integer.toString(slotsF)),
            Component.literal(config.getMode())
        ).withStyle(ChatFormatting.WHITE)), false);

        if (logOnly) {
            SkyGridMod.LOGGER.info("=== SkyGrid {} pool ({} unique / {} slots) ===",
                dimension, unique, slots);
            for (SkyGridConfig.BlockEntry entry : config.getBlockEntries()) {
                SkyGridMod.LOGGER.info("  {}  weight {}", entry.id(), entry.weight());
            }
            SkyGridMod.LOGGER.info("=== End of SkyGrid {} pool ===", dimension);
            source.sendSuccess(() -> Component.translatable("command.skygrid.blocks.log.ok")
                .withStyle(ChatFormatting.GRAY), false);
        } else {
            int total = config.getBlockEntries().size();
            int limit = Math.min(total, 50);
            source.sendSuccess(() -> Component.translatable(
                "command.skygrid.blocks.more",
                Component.literal(Integer.toString(limit)),
                Component.literal(Integer.toString(total))
            ).withStyle(ChatFormatting.GRAY), false);
            for (int i = 0; i < limit; i++) {
                SkyGridConfig.BlockEntry entry = config.getBlockEntries().get(i);
                final String lineId = entry.id();
                final int lineW = entry.weight();
                source.sendSuccess(() -> Component.translatable("command.skygrid.blocks.entry",
                    yellow(lineId), Component.literal(Integer.toString(lineW))
                ).withStyle(ChatFormatting.GRAY), false);
            }
        }
        return 1;
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private static MutableComponent prefix() {
        return Component.translatable("command.skygrid.prefix")
            .withStyle(ChatFormatting.GREEN)
            .append(Component.literal(" "));
    }

    private static MutableComponent yellow(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static Component pct(double chance) {
        return Component.literal(String.format(Locale.ROOT, "%.2f", chance * 100.0));
    }
}

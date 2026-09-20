package com.skygrid.client;

import com.skygrid.SkyGridConfig;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Block pool editor.
 *
 * Built like a vanilla options screen: header + scrollable list + Done.
 * The list is an {@link ObjectSelectionList}, so scrolling does not rebuild
 * widgets. Click a row to add or remove; − / + change weight.
 *
 * Lives in common because Screen is vanilla API. Never referenced from
 * server-side code paths.
 */
public class SkyGridConfigScreen extends Screen {

    private static final String[] DIMENSIONS = { "overworld", "nether", "end" };
    private static final int ROW_HEIGHT = 28;
    private static final int COLUMN_WIDTH = 360;
    private static final int LIST_TOP_PAD = 4;
    private static final String DISCORD_URL = "https://discord.gg/W3JVE5A29s";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/wt-sky-grid";
    private static final String GITHUB_URL = "https://github.com/whotevatech/skygrid-mod";

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 92, 64);

    private String dimension = "overworld";
    private String filter = "";
    private boolean showOnlyEnabled = true;

    private List<BlockInfo> allBlocks = List.of();

    private EditBox search;
    private StringWidget poolSummary;
    private Button doneButton;
    private BlockList list;

    public SkyGridConfigScreen(Screen parent) {
        super(Component.translatable("screen.skygrid.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (allBlocks.isEmpty()) {
            allBlocks = collectBlocks();
        }

        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(6));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));

        int half = (columnWidth() - 8) / 2;
        LinearLayout controls = LinearLayout.horizontal().spacing(8);
        controls.addChild(
            CycleButton.builder((String dim) -> Component.translatable("screen.skygrid.config.dim." + dim))
                .withValues(DIMENSIONS)
                .withInitialValue(dimension)
                .withTooltip(dim -> Tooltip.create(Component.translatable("screen.skygrid.config.dimension.tooltip")))
                .create(0, 0, half, 20,
                    Component.translatable("screen.skygrid.config.dimension"),
                    (button, value) -> {
                        dimension = value;
                        refreshList(true);
                    })
        );
        controls.addChild(
            CycleButton.booleanBuilder(
                    Component.translatable("screen.skygrid.config.filter.pool"),
                    Component.translatable("screen.skygrid.config.filter.all"))
                .withInitialValue(showOnlyEnabled)
                .displayOnlyValue()
                .withTooltip(inPool -> Tooltip.create(Component.translatable("screen.skygrid.config.filter.tooltip")))
                .create(0, 0, half, 20,
                    Component.translatable("screen.skygrid.config.filter"),
                    (button, value) -> {
                        showOnlyEnabled = value;
                        refreshList(true);
                    })
        );
        header.addChild(controls);

        this.search = new EditBox(this.font, 0, 0, columnWidth(), 20,
            Component.translatable("screen.skygrid.config.search"));
        this.search.setHint(Component.translatable("screen.skygrid.config.search.hint"));
        this.search.setMaxLength(128);
        this.search.setValue(filter);
        this.search.setCanLoseFocus(true);
        this.search.setResponder(value -> {
            filter = value;
            refreshList(true);
        });
        header.addChild(this.search);

        this.poolSummary = new StringWidget(columnWidth(), 9, poolSummaryText(), this.font).setColor(0xFF808080);
        header.addChild(this.poolSummary);

        int width = columnWidth();
        int gap = 4;
        int curseWidth = Math.min(120, width / 3 + 8);
        int sideWidth = (width - curseWidth - gap * 2) / 2;

        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        LinearLayout links = LinearLayout.horizontal().spacing(gap);
        links.addChild(linkButton("discord", DISCORD_URL, sideWidth));
        links.addChild(linkButton("curseforge", CURSEFORGE_URL, curseWidth));
        links.addChild(linkButton("github", GITHUB_URL, sideWidth));
        footer.addChild(links);
        this.doneButton = Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build();
        footer.addChild(this.doneButton);

        this.list = this.addRenderableWidget(new BlockList(this.minecraft, columnWidth(), this.height, 0, ROW_HEIGHT));
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        refreshList(false);
        this.search.setFocused(false);
        this.setFocused(this.list);
    }

    private int columnWidth() {
        return Math.min(COLUMN_WIDTH, Math.max(280, this.width - 40));
    }

    private Button linkButton(String key, String url, int width) {
        return Button.builder(Component.translatable("screen.skygrid.config.link." + key),
                ConfirmLinkScreen.confirmLink(this, url, true))
            .width(width)
            .tooltip(Tooltip.create(Component.translatable("screen.skygrid.config.link.tooltip", url)))
            .build();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        int width = columnWidth();
        int x = (this.width - width) / 2;
        if (this.search != null) {
            this.search.setWidth(width);
            this.search.setX(x);
        }
        if (this.list != null) {
            int inner = this.layout.getContentHeight() - LIST_TOP_PAD;
            int rows = Math.max(1, inner / ROW_HEIGHT);
            int height = rows * ROW_HEIGHT + LIST_TOP_PAD;
            this.list.updateSizeAndPosition(width, height, this.layout.getHeaderHeight());
            this.list.setX(x);
        }
    }

    private void refreshList(boolean resetScroll) {
        if (this.list == null) return;
        double scroll = resetScroll ? 0.0 : this.list.getScrollAmount();
        SkyGridConfig cfg = SkyGridConfig.getForDimension(dimension);
        String query = filter.trim().toLowerCase(Locale.ROOT);

        List<BlockRow> inPool = new ArrayList<>();
        List<BlockRow> outOfPool = new ArrayList<>();
        for (BlockInfo info : allBlocks) {
            if (!query.isEmpty() && !info.matches(query)) continue;
            BlockRow row = new BlockRow(info);
            if (cfg.contains(info.id)) inPool.add(row);
            else if (!showOnlyEnabled) outOfPool.add(row);
        }
        List<BlockRow> rows = new ArrayList<>(inPool.size() + outOfPool.size());
        rows.addAll(inPool);
        rows.addAll(outOfPool);
        this.list.setRows(rows);
        this.list.setScrollAmount(scroll);
        if (this.poolSummary != null) {
            this.poolSummary.setMessage(poolSummaryText());
        }
    }

    private Component poolSummaryText() {
        return Component.translatable("screen.skygrid.config.hint",
            config().getBlockEntries().size(),
            Component.translatable("screen.skygrid.config.dim." + dimension));
    }

    private static List<BlockInfo> collectBlocks() {
        List<BlockInfo> infos = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            String id = key.toString();
            Component name = block.getName();
            ItemStack icon = new ItemStack(block.asItem());
            if (icon.isEmpty() && block != Blocks.AIR) {
                icon = ItemStack.EMPTY;
            }
            infos.add(new BlockInfo(id, key.getNamespace(), name, icon,
                name.getString().toLowerCase(Locale.ROOT)));
        }
        infos.sort(Comparator
            .comparingInt((BlockInfo b) -> b.namespace.equals("minecraft") ? 0 : 1)
            .thenComparing(b -> b.namespace)
            .thenComparing(b -> b.name.getString(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(b -> b.id));
        return List.copyOf(infos);
    }

    private SkyGridConfig config() {
        return SkyGridConfig.getForDimension(dimension);
    }

    private void commit(SkyGridConfig cfg) {
        cfg.saveToDisk();
        SkyGridChunkGenerator.clearPools();
        if (this.poolSummary != null) {
            this.poolSummary.setMessage(poolSummaryText());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (this.list != null && this.list.children().isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.translatable("screen.skygrid.config.empty"),
                this.width / 2,
                this.layout.getHeaderHeight() + this.layout.getContentHeight() / 2,
                0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    public static Screen create(Screen parent) {
        return new SkyGridConfigScreen(parent);
    }

    public static boolean isReady() {
        try {
            SkyGridConfig.get();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private record BlockInfo(String id, String namespace, Component name, ItemStack icon, String nameSearch) {
        boolean matches(String query) {
            return nameSearch.contains(query) || id.contains(query);
        }
    }

    private class BlockList extends ObjectSelectionList<BlockRow> {
        BlockList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            this.centerListVertically = false;
        }

        void setRows(List<BlockRow> rows) {
            replaceEntries(rows);
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getRight() - 6;
        }

        @Override
        protected boolean isSelectedItem(int index) {
            return false;
        }

        @Override
        protected void renderListItems(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = this.getRowLeft();
            int width = this.getRowWidth();
            int rowHeight = this.itemHeight - 4;
            int count = this.getItemCount();
            for (int i = 0; i < count; i++) {
                int top = this.getRowTop(i);
                int bottom = this.getRowBottom(i);
                if (bottom >= this.getY() && bottom <= this.getBottom()) {
                    this.renderItem(graphics, mouseX, mouseY, partialTick, i, left, top, width, rowHeight);
                }
            }
        }
    }

    private class BlockRow extends ObjectSelectionList.Entry<BlockRow> {
        private static final int ICON = 16;
        private static final int BTN = 18;
        private static final int CHECK = 17;
        private static final int NAME_COL = 196;
        private static final ResourceLocation CHECKBOX = ResourceLocation.withDefaultNamespace("widget/checkbox");
        private static final ResourceLocation CHECKBOX_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_highlighted");
        private static final ResourceLocation CHECKBOX_SELECTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected");
        private static final ResourceLocation CHECKBOX_SELECTED_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected_highlighted");

        private final BlockInfo info;
        private int left, top, width, height;

        BlockRow(BlockInfo info) {
            this.info = info;
        }

        @Override
        public Component getNarration() {
            return this.info.name;
        }

        @Override
        public void renderBack(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (hovering) {
                graphics.fill(left - 2, top - 1, left + width - 2, top + height + 1, 0x22FFFFFF);
            }
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;

            SkyGridConfig cfg = config();
            boolean inPool = cfg.contains(this.info.id);
            int textColour = inPool ? 0xFFFFFFFF : 0xFF9A9A9A;
            int idColour = inPool ? 0xFFA8A8A8 : 0xFF6A6A6A;

            int checkX = left + 2;
            int checkY = top + (height - CHECK) / 2;
            boolean overCheck = inside(mouseX, mouseY, checkX, checkY, CHECK, CHECK);
            ResourceLocation box = inPool
                ? (overCheck || hovering ? CHECKBOX_SELECTED_HIGHLIGHTED : CHECKBOX_SELECTED)
                : (overCheck || hovering ? CHECKBOX_HIGHLIGHTED : CHECKBOX);
            graphics.blitSprite(box, checkX, checkY, CHECK, CHECK);

            int iconX = checkX + CHECK + 6;
            int iconY = top + (height - ICON) / 2;
            if (!this.info.icon.isEmpty()) {
                graphics.renderItem(this.info.icon, iconX, iconY);
            } else {
                graphics.fill(iconX, iconY, iconX + ICON, iconY + ICON, 0x33000000);
            }

            int textX = iconX + ICON + 6;
            int nameWidth = NAME_COL;

            graphics.drawString(font(),
                font().plainSubstrByWidth(this.info.name.getString(), nameWidth),
                textX, top + 2, textColour, false);
            graphics.drawString(font(),
                font().plainSubstrByWidth(this.info.id, nameWidth),
                textX, top + 13, idColour, false);

            if (inPool) {
                int weight = cfg.getWeight(this.info.id);
                drawTinyButton(graphics, minusX(), buttonY(), "−", hoveringMinus(mouseX, mouseY));
                graphics.drawCenteredString(font(), Integer.toString(weight),
                    weightCentreX(), top + (height - 8) / 2, 0xFFFFFFA0);
                drawTinyButton(graphics, plusX(), buttonY(), "+", hoveringPlus(mouseX, mouseY));
            } else {
                Component add = Component.translatable("screen.skygrid.config.add")
                    .withStyle(hovering ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
                graphics.drawString(font(), add,
                    minusX(), top + (height - 8) / 2, 0xFFFFFFFF, false);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) return false;

            SkyGridConfig cfg = config();
            boolean inPool = cfg.contains(this.info.id);

            if (inPool && hoveringMinus(mouseX, mouseY)) {
                int next = Math.max(1, cfg.getWeight(this.info.id) - (Screen.hasShiftDown() ? 10 : 1));
                cfg.addOrUpdate(this.info.id, next);
                commit(cfg);
                return true;
            }
            if (inPool && hoveringPlus(mouseX, mouseY)) {
                int next = Math.min(1000, cfg.getWeight(this.info.id) + (Screen.hasShiftDown() ? 10 : 1));
                cfg.addOrUpdate(this.info.id, next);
                commit(cfg);
                return true;
            }

            if (inPool) {
                cfg.remove(this.info.id);
            } else {
                cfg.addOrUpdate(this.info.id, 1);
            }
            commit(cfg);
            refreshList(false);
            return true;
        }

        private void drawTinyButton(GuiGraphics graphics, int x, int y, String label, boolean hot) {
            ResourceLocation sprite = ResourceLocation.withDefaultNamespace(
                hot ? "widget/button_highlighted" : "widget/button");
            graphics.blitSprite(sprite, x, y, BTN, BTN);
            graphics.drawCenteredString(font(), label, x + BTN / 2, y + 5, 0xFFFFFFFF);
        }

        private int buttonY() {
            return this.top + (this.height - BTN) / 2;
        }

        private int plusX() {
            return minusX() + 40;
        }

        private int minusX() {
            return this.left + 2 + CHECK + 6 + ICON + 6 + NAME_COL + 12;
        }

        private int weightCentreX() {
            return (minusX() + BTN + plusX()) / 2;
        }

        private boolean hoveringMinus(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, minusX(), buttonY(), BTN, BTN);
        }

        private boolean hoveringPlus(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, plusX(), buttonY(), BTN, BTN);
        }

        private static boolean inside(double x, double y, int bx, int by, int w, int h) {
            return x >= bx && x < bx + w && y >= by && y < by + h;
        }

        private net.minecraft.client.gui.Font font() {
            return SkyGridConfigScreen.this.font;
        }
    }
}

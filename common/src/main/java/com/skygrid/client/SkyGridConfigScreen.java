package com.skygrid.client;

import com.skygrid.SkyGridConfig;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Block pool editor.
 *
 * One centered column, the same on Fabric and NeoForge. The header, the list
 * and the footer share that width. Row controls are measured from the right
 * edge of the row, so the weight stepper stays aligned when the window changes
 * size. Every control in the chrome is 20px tall and the gaps are 4px inside a
 * row and 6px between controls.
 *
 * Edits save immediately. The line under the search box says so, and says that
 * only newly generated chunks change. Reload reads the three JSON files and
 * refuses to apply them if any file cannot be parsed.
 */
public class SkyGridConfigScreen extends Screen {

    private static final String[] DIMENSIONS = { "overworld", "nether", "end" };
    private static final int COLUMN_MAX = 440;
    private static final int HEADER_HEIGHT = 100;
    private static final int FOOTER_HEIGHT = 56;
    private static final int ROW_HEIGHT = 32;
    private static final int CONTROL = 20;
    private static final int PAD = 4;
    private static final int GAP = 6;
    private static final int CHECK = 20;
    private static final int ICON = 16;
    private static final int NUMBER_W = 36;
    private static final int CLUSTER = CONTROL + NUMBER_W + CONTROL;
    private static final int FILTER_WIDTH = 104;
    private static final int MIN_WEIGHT = 1;
    private static final int MAX_WEIGHT = 1000;
    private static final long STATUS_MS = 4500L;

    private static final int COLOR_MUTED = 0xFFA0A0A0;
    private static final int COLOR_OK = 0xFFC8E6B0;
    private static final int COLOR_ERROR = 0xFFFF9999;
    private static final int COLOR_WARN = 0xFFFFCC88;

    private static final String DISCORD_URL = "https://discord.gg/W3JVE5A29s";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/wt-sky-grid";
    private static final String GITHUB_URL = "https://github.com/whotevatech/skygrid-mod";

    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final ResourceLocation BUTTON_DISABLED = ResourceLocation.withDefaultNamespace("widget/button_disabled");
    private static final ResourceLocation CHECKBOX = ResourceLocation.withDefaultNamespace("widget/checkbox");
    private static final ResourceLocation CHECKBOX_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_highlighted");
    private static final ResourceLocation CHECKBOX_SELECTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected");
    private static final ResourceLocation CHECKBOX_SELECTED_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/checkbox_selected_highlighted");

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);

    private String dimension = "overworld";
    private String filter = "";
    private boolean showOnlyEnabled = true;
    private String pendingRemove;

    private List<BlockInfo> allBlocks = List.of();
    private Map<String, Integer> weights = new HashMap<>();

    private Component statusText;
    private boolean statusError;
    private long statusUntil;
    private Component hoverTip;
    private EmptyKind emptyKind = EmptyKind.NONE;

    private StringWidget titleWidget;
    private StringWidget subtitleWidget;
    private StringWidget poolSummary;
    private DimensionBar tabs;
    private EditBox search;
    private CycleButton<Boolean> filterButton;
    private final Button[] linkButtons = new Button[3];
    private Button reloadButton;
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

        int width = columnWidth();
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(PAD));
        header.defaultCellSetting().alignHorizontallyCenter();

        this.titleWidget = new StringWidget(width, 9, this.title, this.font);
        header.addChild(this.titleWidget);
        this.subtitleWidget = new StringWidget(width, 9,
            Component.translatable("screen.skygrid.config.subtitle"), this.font).setColor(COLOR_MUTED);
        header.addChild(this.subtitleWidget);

        this.tabs = new DimensionBar(width);
        this.tabs.setTooltip(Tooltip.create(Component.translatable("screen.skygrid.config.dimension.tooltip")));
        header.addChild(this.tabs);

        int searchWidth = Math.max(60, width - FILTER_WIDTH - GAP);
        LinearLayout searchRow = LinearLayout.horizontal().spacing(GAP);
        this.search = new EditBox(this.font, 0, 0, searchWidth, CONTROL,
            Component.translatable("screen.skygrid.config.search"));
        this.search.setHint(Component.translatable("screen.skygrid.config.search.hint"));
        this.search.setTooltip(Tooltip.create(Component.translatable("screen.skygrid.config.search.tooltip")));
        this.search.setMaxLength(128);
        this.search.setValue(filter);
        this.search.setCanLoseFocus(true);
        this.search.setResponder(value -> {
            filter = value;
            pendingRemove = null;
            refreshList(true);
        });
        this.filterButton = CycleButton.booleanBuilder(
                Component.translatable("screen.skygrid.config.filter.pool"),
                Component.translatable("screen.skygrid.config.filter.all"))
            .withInitialValue(showOnlyEnabled)
            .displayOnlyValue()
            .withTooltip(inPool -> Tooltip.create(Component.translatable("screen.skygrid.config.filter.tooltip")))
            .create(0, 0, FILTER_WIDTH, CONTROL,
                Component.translatable("screen.skygrid.config.filter"),
                (button, value) -> {
                    showOnlyEnabled = value;
                    pendingRemove = null;
                    refreshList(true);
                });
        searchRow.addChild(this.search);
        searchRow.addChild(this.filterButton);
        header.addChild(searchRow);

        this.poolSummary = new StringWidget(width, 9, summaryText(), this.font).setColor(summaryColor());
        header.addChild(this.poolSummary);

        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(PAD));
        footer.defaultCellSetting().alignHorizontallyCenter();
        int linkWidth = linkWidth(width);
        LinearLayout links = LinearLayout.horizontal().spacing(GAP);
        this.linkButtons[0] = linkButton("discord", DISCORD_URL, linkWidth);
        this.linkButtons[1] = linkButton("curseforge", CURSEFORGE_URL, linkWidth);
        this.linkButtons[2] = linkButton("github", GITHUB_URL, linkWidth);
        for (Button link : this.linkButtons) links.addChild(link);
        footer.addChild(links);

        int actionWidth = actionWidth(width);
        LinearLayout actions = LinearLayout.horizontal().spacing(GAP);
        this.reloadButton = Button.builder(Component.translatable("screen.skygrid.config.reload"),
                button -> askReload())
            .width(actionWidth)
            .tooltip(Tooltip.create(Component.translatable("screen.skygrid.config.reload.tooltip")))
            .build();
        this.doneButton = Button.builder(CommonComponents.GUI_DONE, button -> onClose())
            .width(actionWidth)
            .build();
        actions.addChild(this.reloadButton);
        actions.addChild(this.doneButton);
        footer.addChild(actions);

        this.list = this.addRenderableWidget(new BlockList(this.minecraft, width, this.height));
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        refreshList(false);
        this.setFocused(this.list);
    }

    private int columnWidth() {
        if (this.width <= 24) return Math.max(1, this.width);
        return Math.min(COLUMN_MAX, this.width - 24);
    }

    private static int linkWidth(int column) {
        return Math.max(1, (column - GAP * 2) / 3);
    }

    private static int actionWidth(int column) {
        return Math.max(1, (column - GAP) / 2);
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
        int width = columnWidth();
        int searchWidth = Math.max(60, width - FILTER_WIDTH - GAP);
        if (this.titleWidget != null) this.titleWidget.setWidth(width);
        if (this.subtitleWidget != null) this.subtitleWidget.setWidth(width);
        if (this.tabs != null) this.tabs.setWidth(width);
        if (this.search != null) this.search.setWidth(searchWidth);
        if (this.filterButton != null) this.filterButton.setWidth(FILTER_WIDTH);
        if (this.poolSummary != null) this.poolSummary.setWidth(width);
        int linkW = linkWidth(width);
        for (Button link : this.linkButtons) {
            if (link != null) link.setWidth(linkW);
        }
        int actionW = actionWidth(width);
        if (this.reloadButton != null) this.reloadButton.setWidth(actionW);
        if (this.doneButton != null) this.doneButton.setWidth(actionW);

        this.layout.arrangeElements();
        if (this.list != null) {
            this.list.updateSizeAndPosition(width, this.layout.getContentHeight(), this.layout.getHeaderHeight());
            this.list.setX((this.width - width) / 2);
        }
    }

    private void refreshList(boolean resetScroll) {
        if (this.list == null) return;
        double scroll = resetScroll ? 0.0 : this.list.getScrollAmount();
        SkyGridConfig cfg = config();
        String query = filter.trim().toLowerCase(Locale.ROOT);

        Map<String, Integer> next = new HashMap<>();
        Set<String> known = new HashSet<>();
        for (BlockInfo info : allBlocks) known.add(info.id);
        List<BlockInfo> extras = new ArrayList<>();
        for (SkyGridConfig.BlockEntry entry : cfg.getBlockEntries()) {
            next.put(entry.id(), entry.weight());
            if (!known.contains(entry.id())) extras.add(BlockInfo.extra(entry.id()));
        }
        this.weights = next;
        extras.sort(Comparator
            .comparingInt((BlockInfo info) -> info.kind == Kind.TAG ? 0 : 1)
            .thenComparing(info -> info.id));

        List<Row> rows = new ArrayList<>();
        List<BlockInfo> extraShown = new ArrayList<>();
        for (BlockInfo info : extras) {
            if (query.isEmpty() || info.matches(query)) extraShown.add(info);
        }
        if (!extraShown.isEmpty()) {
            rows.add(new HeaderRow(Component.translatable("screen.skygrid.config.saved.header")));
            for (BlockInfo info : extraShown) rows.add(new BlockRow(info));
        }
        for (BlockInfo info : allBlocks) {
            if (!query.isEmpty() && !info.matches(query)) continue;
            if (showOnlyEnabled && !next.containsKey(info.id)) continue;
            rows.add(new BlockRow(info));
        }

        if (this.pendingRemove != null && !next.containsKey(this.pendingRemove)) {
            this.pendingRemove = null;
        }

        this.list.setRows(rows);
        this.list.setScrollAmount(scroll);
        if (rows.isEmpty()) {
            this.emptyKind = query.isEmpty() ? EmptyKind.POOL : EmptyKind.SEARCH;
        } else {
            this.emptyKind = EmptyKind.NONE;
        }
        refreshStatusLine();
    }

    private Component summaryText() {
        String mode = config().getMode();
        Component modeLabel;
        if ("whitelist".equalsIgnoreCase(mode) || "blacklist".equalsIgnoreCase(mode)) {
            modeLabel = Component.translatable("screen.skygrid.config.mode." + mode.toLowerCase(Locale.ROOT));
        } else {
            modeLabel = Component.literal(mode);
        }
        return Component.translatable("screen.skygrid.config.summary",
            config().getBlockEntries().size(),
            modeLabel,
            Component.translatable("screen.skygrid.config.summary.chunks"));
    }

    private int summaryColor() {
        if (this.statusText != null && Util.getMillis() < this.statusUntil) {
            return this.statusError ? COLOR_ERROR : COLOR_OK;
        }
        if ("blacklist".equalsIgnoreCase(config().getMode())) return COLOR_WARN;
        return COLOR_MUTED;
    }

    private void refreshStatusLine() {
        if (this.poolSummary == null) return;
        if (this.statusText != null && Util.getMillis() >= this.statusUntil) {
            this.statusText = null;
        }
        Component message = this.statusText != null ? this.statusText : summaryText();
        this.poolSummary.setMessage(fit(message));
        this.poolSummary.setColor(summaryColor());
    }

    private void showStatus(Component message, boolean error) {
        this.statusText = message;
        this.statusError = error;
        this.statusUntil = Util.getMillis() + STATUS_MS;
        refreshStatusLine();
    }

    private Component fit(Component message) {
        int max = Math.max(40, columnWidth() - 8);
        if (this.font.width(message) <= max) return message;
        String raw = message.getString();
        String ellipsis = "...";
        return Component.literal(this.font.plainSubstrByWidth(raw, max - this.font.width(ellipsis)) + ellipsis);
    }

    private String shortName(BlockInfo info) {
        String name = info.name.getString();
        int max = 180;
        if (this.font.width(name) <= max) return name;
        return this.font.plainSubstrByWidth(name, max - this.font.width("...")) + "...";
    }

    private static List<BlockInfo> collectBlocks() {
        List<BlockInfo> infos = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            infos.add(BlockInfo.ofBlock(block));
        }
        infos.sort(Comparator
            .comparingInt((BlockInfo info) -> info.namespace.equals("minecraft") ? 0 : 1)
            .thenComparing(info -> info.namespace)
            .thenComparing(info -> info.name.getString(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(info -> info.id));
        return List.copyOf(infos);
    }

    private SkyGridConfig config() {
        return SkyGridConfig.getForDimension(dimension);
    }

    private boolean listed(String id) {
        return this.weights.containsKey(id);
    }

    /** Blacklist entries are exclusions. Their weights are not used when chunks generate. */
    private boolean blacklistMode() {
        return "blacklist".equalsIgnoreCase(config().getMode());
    }

    private int weightOf(String id) {
        Integer weight = this.weights.get(id);
        return weight == null ? 0 : weight;
    }

    private boolean commit(SkyGridConfig cfg) {
        SkyGridChunkGenerator.clearPools();
        if (!cfg.saveToDisk()) {
            showStatus(Component.translatable("screen.skygrid.config.save.fail"), true);
            return false;
        }
        return true;
    }

    private void selectDimension(String next) {
        if (next.equals(this.dimension)) return;
        this.dimension = next;
        this.pendingRemove = null;
        refreshList(true);
    }

    private void addBlock(BlockInfo info) {
        this.pendingRemove = null;
        SkyGridConfig cfg = config();
        cfg.addOrUpdate(info.id, MIN_WEIGHT);
        this.weights.put(info.id, MIN_WEIGHT);
        boolean saved = commit(cfg);
        if (showOnlyEnabled) refreshList(false);
        if (saved) {
            showStatus(Component.translatable("screen.skygrid.config.saved.add", shortName(info), MIN_WEIGHT), false);
        }
    }

    private void armRemove(BlockInfo info) {
        this.pendingRemove = info.id;
        showStatus(Component.translatable("screen.skygrid.config.remove.confirm", shortName(info)), false);
    }

    private void cancelRemove(BlockInfo info) {
        this.pendingRemove = null;
        showStatus(Component.translatable("screen.skygrid.config.remove.cancelled", shortName(info)), false);
    }

    private void removeBlock(BlockInfo info) {
        this.pendingRemove = null;
        SkyGridConfig cfg = config();
        cfg.remove(info.id);
        this.weights.remove(info.id);
        boolean saved = commit(cfg);
        if (showOnlyEnabled || info.kind != Kind.BLOCK) refreshList(false);
        if (saved) {
            showStatus(Component.translatable("screen.skygrid.config.saved.remove", shortName(info)), false);
        }
    }

    private void stepWeight(BlockInfo info, int direction) {
        this.pendingRemove = null;
        int current = weightOf(info.id);
        int delta = direction * (Screen.hasShiftDown() ? 10 : 1);
        int next = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, current + delta));
        if (next == current) return;
        SkyGridConfig cfg = config();
        cfg.addOrUpdate(info.id, next);
        this.weights.put(info.id, next);
        if (commit(cfg)) {
            showStatus(Component.translatable("screen.skygrid.config.saved.weight", shortName(info), next), false);
        }
    }

    private void askReload() {
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            this.minecraft.setScreen(this);
            if (confirmed) applyReload();
        },
            Component.translatable("screen.skygrid.config.reload.title"),
            Component.translatable("screen.skygrid.config.reload.body"),
            Component.translatable("screen.skygrid.config.reload.confirm"),
            CommonComponents.GUI_CANCEL));
    }

    private void applyReload() {
        String error = SkyGridConfig.reloadFromDisk();
        if (error != null) {
            showStatus(Component.translatable("screen.skygrid.config.reload.fail", error), true);
            return;
        }
        SkyGridChunkGenerator.clearPools();
        this.pendingRemove = null;
        refreshList(false);
        showStatus(Component.translatable("screen.skygrid.config.reload.ok"), false);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int w, int h, Component label, boolean hot, boolean enabled) {
        ResourceLocation sprite = !enabled ? BUTTON_DISABLED : hot ? BUTTON_HIGHLIGHTED : BUTTON;
        graphics.blitSprite(sprite, x, y, w, h);
        graphics.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, enabled ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    private Geom geom(int left, int top, int width, int height) {
        int controlY = top + (height - CONTROL) / 2;
        int controlX = left + width - PAD - CLUSTER;
        int checkX = left + PAD;
        int checkY = top + (height - CHECK) / 2;
        int iconX = checkX + CHECK + PAD;
        int iconY = top + (height - ICON) / 2;
        int textX = iconX + ICON + PAD;
        int textW = Math.max(16, controlX - PAD - textX);
        return new Geom(checkX, checkY, iconX, iconY, textX, textW, controlX, controlY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.hoverTip = null;
        refreshStatusLine();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (this.emptyKind != EmptyKind.NONE && this.list != null) {
            drawEmpty(graphics);
        }
        if (this.hoverTip != null) {
            graphics.renderTooltip(this.font, this.hoverTip, mouseX, mouseY);
        }
    }

    private void drawEmpty(GuiGraphics graphics) {
        int y = this.list.getY() + this.list.getHeight() / 2;
        Component line1;
        Component line2;
        if (this.emptyKind == EmptyKind.SEARCH) {
            String shown = this.filter.trim();
            if (this.font.width(shown) > 180) {
                shown = this.font.plainSubstrByWidth(shown, 170) + "...";
            }
            line1 = Component.translatable("screen.skygrid.config.empty.search", shown);
            line2 = Component.translatable("screen.skygrid.config.empty.search.hint");
        } else {
            line1 = Component.translatable("screen.skygrid.config.empty.pool",
                Component.translatable("screen.skygrid.config.dim." + this.dimension));
            line2 = Component.translatable("screen.skygrid.config.empty.pool.hint");
        }
        graphics.drawCenteredString(this.font, line1, this.width / 2, y - 6, 0xFFE0E0E0);
        graphics.drawCenteredString(this.font, line2, this.width / 2, y + 8, 0xFF9A9A9A);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F
                && (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0
                && this.search != null
                && !this.search.isFocused()) {
            this.setFocused(this.search);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

    private enum EmptyKind { NONE, SEARCH, POOL }

    private enum Kind { BLOCK, TAG, MISSING }

    private record BlockInfo(String id, String namespace, Component name, Component detail,
                             ItemStack icon, String search, Kind kind) {
        boolean matches(String query) {
            return this.search.contains(query);
        }

        static BlockInfo ofBlock(Block block) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            String id = key.toString();
            Component name = block.getName();
            ItemStack icon = new ItemStack(block.asItem());
            String search = (name.getString() + " " + id).toLowerCase(Locale.ROOT);
            return new BlockInfo(id, key.getNamespace(), name, Component.literal(id), icon, search, Kind.BLOCK);
        }

        static BlockInfo extra(String id) {
            boolean tag = id.startsWith("#");
            Kind kind = tag ? Kind.TAG : Kind.MISSING;
            Component detail = Component.translatable(tag
                ? "screen.skygrid.config.tag.hint"
                : "screen.skygrid.config.missing.hint");
            ItemStack icon = tag ? new ItemStack(Items.NAME_TAG) : ItemStack.EMPTY;
            return new BlockInfo(id, tag ? "tag" : "missing", Component.literal(id), detail, icon,
                id.toLowerCase(Locale.ROOT), kind);
        }
    }

    private record Geom(int checkX, int checkY, int iconX, int iconY, int textX, int textW,
                        int controlX, int controlY) {}

    private class DimensionBar extends AbstractWidget {
        DimensionBar(int width) {
            super(0, 0, width, CONTROL, Component.translatable("screen.skygrid.config.dimension"));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int hover = this.isHovered() ? tabIndex(mouseX) : -1;
            for (int i = 0; i < DIMENSIONS.length; i++) {
                boolean selected = DIMENSIONS[i].equals(dimension);
                int x = tabX(i);
                int w = tabWidthFor(i);
                ResourceLocation sprite = (selected || hover == i) ? BUTTON_HIGHLIGHTED : BUTTON;
                graphics.blitSprite(sprite, x, this.getY(), w, CONTROL);
                Component label = Component.translatable("screen.skygrid.config.dim." + DIMENSIONS[i]);
                String text = label.getString();
                int maxText = Math.max(8, w - 8);
                if (font.width(text) > maxText) {
                    text = font.plainSubstrByWidth(text, maxText);
                }
                graphics.drawCenteredString(font, text, x + w / 2, this.getY() + (CONTROL - 8) / 2, 0xFFFFFFFF);
                if (selected) {
                    int mark = Math.min(font.width(text), w - 8);
                    int markX = x + (w - mark) / 2;
                    graphics.fill(markX, this.getY() + CONTROL - 4, markX + mark, this.getY() + CONTROL - 3, 0xFFFFFFFF);
                }
            }
            if (this.isFocused()) {
                graphics.renderOutline(this.getX() - 1, this.getY() - 1, this.getWidth() + 2, this.getHeight() + 2, 0xFFFFFFFF);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.isMouseOver(mouseX, mouseY)) return false;
            int index = tabIndex(mouseX);
            if (index < 0) return false;
            this.playDownSound(Minecraft.getInstance().getSoundManager());
            selectDimension(DIMENSIONS[index]);
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            int current = 0;
            for (int i = 0; i < DIMENSIONS.length; i++) {
                if (DIMENSIONS[i].equals(dimension)) current = i;
            }
            int next = current;
            if (keyCode == GLFW.GLFW_KEY_LEFT) next = (current + DIMENSIONS.length - 1) % DIMENSIONS.length;
            else if (keyCode == GLFW.GLFW_KEY_RIGHT) next = (current + 1) % DIMENSIONS.length;
            else return false;
            if (next != current) {
                this.playDownSound(Minecraft.getInstance().getSoundManager());
                selectDimension(DIMENSIONS[next]);
            }
            return true;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.translatable(
                "screen.skygrid.config.dimension.narration",
                Component.translatable("screen.skygrid.config.dim." + dimension)));
        }

        private int tabStride() {
            return (this.getWidth() - GAP * 2) / DIMENSIONS.length;
        }

        private int tabWidthFor(int index) {
            int base = tabStride();
            if (index < DIMENSIONS.length - 1) return base;
            return this.getWidth() - base * (DIMENSIONS.length - 1) - GAP * (DIMENSIONS.length - 1);
        }

        private int tabX(int index) {
            return this.getX() + index * (tabStride() + GAP);
        }

        private int tabIndex(double mouseX) {
            for (int i = 0; i < DIMENSIONS.length; i++) {
                int x = tabX(i);
                if (mouseX >= x && mouseX < x + tabWidthFor(i)) return i;
            }
            return -1;
        }
    }

    private class BlockList extends ObjectSelectionList<Row> {
        BlockList(Minecraft minecraft, int width, int height) {
            super(minecraft, width, height, 0, ROW_HEIGHT);
            this.centerListVertically = false;
        }

        void setRows(List<Row> rows) {
            replaceEntries(rows);
        }

        @Override
        public int getRowWidth() {
            return this.width - 16;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getRight() - 6;
        }

        @Override
        protected boolean isSelectedItem(int index) {
            return false;
        }
    }

    private abstract class Row extends ObjectSelectionList.Entry<Row> {}

    private class HeaderRow extends Row {
        private final Component label;

        HeaderRow(Component label) {
            this.label = label;
        }

        @Override
        public Component getNarration() {
            return this.label;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            int textWidth = font.width(this.label);
            int mid = top + height / 2;
            int textX = left + (width - textWidth) / 2;
            graphics.fill(left + PAD, mid, textX - GAP, mid + 1, 0x55FFFFFF);
            graphics.drawString(font, this.label, textX, top + (height - 8) / 2, COLOR_MUTED, false);
            graphics.fill(textX + textWidth + GAP, mid, left + width - PAD, mid + 1, 0x55FFFFFF);
        }
    }

    private class BlockRow extends Row {
        private final BlockInfo info;
        private int left;
        private int top;
        private int rowWidth;
        private int rowHeight;

        BlockRow(BlockInfo info) {
            this.info = info;
        }

        @Override
        public Component getNarration() {
            int weight = weightOf(this.info.id);
            if (weight > 0) {
                return Component.translatable("screen.skygrid.config.row.narration.listed",
                    this.info.name, this.info.id, weight);
            }
            return Component.translatable("screen.skygrid.config.row.narration", this.info.name, this.info.id);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            this.left = left;
            this.top = top;
            this.rowWidth = width;
            this.rowHeight = height;

            boolean inList = listed(this.info.id);
            boolean armed = this.info.id.equals(pendingRemove);
            Geom g = geom(left, top, width, height);

            if (armed) {
                graphics.fill(left, top, left + width, top + height, 0x30C45050);
            } else if (hovering) {
                graphics.fill(left, top, left + width, top + height, 0x28FFFFFF);
            } else if (inList) {
                graphics.fill(left, top, left + width, top + height, 0x14FFFFFF);
            }

            boolean overCheck = inside(mouseX, mouseY, g.checkX, g.checkY, CHECK, CHECK);
            ResourceLocation box = inList
                ? (overCheck ? CHECKBOX_SELECTED_HIGHLIGHTED : CHECKBOX_SELECTED)
                : (overCheck ? CHECKBOX_HIGHLIGHTED : CHECKBOX);
            graphics.blitSprite(box, g.checkX, g.checkY, CHECK, CHECK);

            if (!this.info.icon.isEmpty()) {
                graphics.renderItem(this.info.icon, g.iconX, g.iconY);
            } else {
                graphics.fill(g.iconX, g.iconY, g.iconX + ICON, g.iconY + ICON, 0x44000000);
                graphics.drawCenteredString(font, "?", g.iconX + ICON / 2, g.iconY + 4, 0xFFB0B0B0);
            }

            int nameColor = switch (this.info.kind) {
                case TAG -> 0xFFD6D6FF;
                case MISSING -> 0xFFFFD6A8;
                case BLOCK -> inList ? 0xFFFFFFFF : 0xFFB0B0B0;
            };
            int detailColor = inList || this.info.kind != Kind.BLOCK ? 0xFF9A9A9A : 0xFF6E6E6E;
            int textTop = top + Math.max(2, (height - 17) / 2);
            graphics.drawString(font, font.plainSubstrByWidth(this.info.name.getString(), g.textW),
                g.textX, textTop, nameColor, false);
            graphics.drawString(font, font.plainSubstrByWidth(this.info.detail.getString(), g.textW),
                g.textX, textTop + 9, detailColor, false);

            boolean blacklist = blacklistMode();
            if (!inList) {
                boolean hot = inside(mouseX, mouseY, g.controlX, g.controlY, CLUSTER, CONTROL);
                drawButton(graphics, g.controlX, g.controlY, CLUSTER, CONTROL,
                    Component.translatable("screen.skygrid.config.add"), hot, true);
            } else if (armed) {
                boolean hot = inside(mouseX, mouseY, g.controlX, g.controlY, CLUSTER, CONTROL);
                drawButton(graphics, g.controlX, g.controlY, CLUSTER, CONTROL,
                    Component.translatable("screen.skygrid.config.remove"), hot, true);
            } else if (blacklist) {
                Component excluded = Component.translatable("screen.skygrid.config.excluded");
                int labelWidth = font.width(excluded);
                graphics.drawString(font, excluded, g.controlX + CLUSTER - labelWidth,
                    g.controlY + (CONTROL - 8) / 2, COLOR_WARN, false);
            } else {
                int weight = weightOf(this.info.id);
                boolean canMinus = weight > MIN_WEIGHT;
                boolean canPlus = weight < MAX_WEIGHT;
                int plusX = g.controlX + CONTROL + NUMBER_W;
                drawButton(graphics, g.controlX, g.controlY, CONTROL, CONTROL, Component.literal("-"),
                    canMinus && inside(mouseX, mouseY, g.controlX, g.controlY, CONTROL, CONTROL), canMinus);
                graphics.drawCenteredString(font, Integer.toString(weight),
                    g.controlX + CONTROL + NUMBER_W / 2, g.controlY + (CONTROL - 8) / 2, 0xFFFFFFFF);
                drawButton(graphics, plusX, g.controlY, CONTROL, CONTROL, Component.literal("+"),
                    canPlus && inside(mouseX, mouseY, plusX, g.controlY, CONTROL, CONTROL), canPlus);
            }

            if (hovering) {
                boolean blacklistTip = blacklistMode();
                if (inList && !armed && !blacklistTip && inside(mouseX, mouseY, g.controlX, g.controlY, CLUSTER, CONTROL)) {
                    hoverTip = Component.translatable("screen.skygrid.config.weight.tooltip", weightOf(this.info.id));
                } else if (blacklistTip && inList) {
                    hoverTip = Component.translatable("screen.skygrid.config.row.excluded");
                } else if (blacklistTip) {
                    hoverTip = Component.translatable("screen.skygrid.config.row.out.blacklist");
                } else if (this.info.kind == Kind.TAG) {
                    hoverTip = Component.translatable("screen.skygrid.config.row.tag");
                } else if (this.info.kind == Kind.MISSING) {
                    hoverTip = Component.translatable("screen.skygrid.config.row.missing");
                } else if (armed) {
                    hoverTip = Component.translatable("screen.skygrid.config.remove.confirm", this.info.name);
                } else if (inList) {
                    hoverTip = Component.translatable("screen.skygrid.config.row.in");
                } else {
                    hoverTip = Component.translatable("screen.skygrid.config.row.out");
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || this.rowWidth <= 0) return false;
            Geom g = geom(this.left, this.top, this.rowWidth, this.rowHeight);
            boolean inList = listed(this.info.id);
            boolean armed = this.info.id.equals(pendingRemove);

            if (inside(mouseX, mouseY, g.checkX, g.checkY, CHECK, CHECK)) {
                if (!inList) addBlock(this.info);
                else if (armed) cancelRemove(this.info);
                else armRemove(this.info);
                return true;
            }
            if (!inList && inside(mouseX, mouseY, g.controlX, g.controlY, CLUSTER, CONTROL)) {
                addBlock(this.info);
                return true;
            }
            if (inList && armed && inside(mouseX, mouseY, g.controlX, g.controlY, CLUSTER, CONTROL)) {
                removeBlock(this.info);
                return true;
            }
            if (inList && !armed && !blacklistMode()) {
                if (inside(mouseX, mouseY, g.controlX, g.controlY, CONTROL, CONTROL)) {
                    stepWeight(this.info, -1);
                    return true;
                }
                int plusX = g.controlX + CONTROL + NUMBER_W;
                if (inside(mouseX, mouseY, plusX, g.controlY, CONTROL, CONTROL)) {
                    stepWeight(this.info, 1);
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean inside(double x, double y, int bx, int by, int w, int h) {
        return x >= bx && x < bx + w && y >= by && y < by + h;
    }
}

package com.skygrid.client;

import com.skygrid.SkyGridConfig;
import com.skygrid.world.SkyGridChunkGenerator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Block pool editor.
 *
 * Lives in common because Screen is vanilla API — only the hooks that open it
 * are loader-specific. Never referenced from server-side code paths, so a
 * dedicated server never loads this class.
 *
 * Scope, deliberately: blocks, weights, and the three dimension pools.
 * Whitelist/blacklist mode is NOT editable here — flipping it silently inverts
 * the meaning of every entry, which is a poor thing to put behind a button.
 * Edit the JSON directly if you need that.
 *
 * Edits write straight to disk and clear the cached pools, so they apply to
 * newly generated chunks. Existing terrain never changes.
 */
public class SkyGridConfigScreen extends Screen {

    private static final String[] DIMENSIONS = { "overworld", "nether", "end" };

    private static final int ROW_HEIGHT   = 22;
    // Search box occupies y=50..68, and the scroll hint is drawn at LIST_TOP-12,
    // so the list has to start at 84 or the two overlap.
    private static final int LIST_TOP     = 84;
    private static final int LIST_BOTTOM  = 40;   // distance from screen bottom

    private final Screen parent;

    private String dimension = "overworld";
    private String filter    = "";
    private boolean showOnlyEnabled = false;

    private int scroll = 0;

    /** Every block id in the registry, sorted. Built once. */
    private List<String> allBlocks = List.of();
    /** The rows currently visible after filtering. */
    private List<String> visible = new ArrayList<>();

    private EditBox search;

    public SkyGridConfigScreen(Screen parent) {
        super(Component.literal("Sky Grid — Block Pool"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (allBlocks.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                ids.add(BuiltInRegistries.BLOCK.getKey(b).toString());
            }
            ids.sort(String::compareTo);
            allBlocks = List.copyOf(ids);
        }

        int cx = this.width / 2;

        // --- dimension tabs ---
        int tabW = 80;
        int tabsX = cx - (tabW * DIMENSIONS.length) / 2;
        for (int i = 0; i < DIMENSIONS.length; i++) {
            final String dim = DIMENSIONS[i];
            String label = (dim.equals(dimension) ? "▶ " : "") + capitalise(dim);
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                dimension = dim;
                scroll = 0;
                rebuild();
            }).bounds(tabsX + i * tabW, 26, tabW - 4, 20).build());
        }

        // --- search ---
        search = new EditBox(this.font, cx - 160, 50, 220, 18, Component.literal("Search"));
        search.setHint(Component.literal("Search blocks or mod id…"));
        search.setMaxLength(128);
        search.setValue(filter);
        search.setResponder(s -> {
            filter = s.toLowerCase(Locale.ROOT);
            scroll = 0;
            recomputeVisible();
        });
        addRenderableWidget(search);

        // --- filter toggle ---
        addRenderableWidget(Button.builder(
            Component.literal(showOnlyEnabled ? "In pool" : "All blocks"), b -> {
                showOnlyEnabled = !showOnlyEnabled;
                scroll = 0;
                rebuild();
            }).bounds(cx + 68, 50, 92, 18).build());

        // --- done ---
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(cx - 50, this.height - 28, 100, 20).build());

        recomputeVisible();
        buildRowButtons();
    }

    /** Full re-init, used when the row set changes shape. */
    private void rebuild() {
        this.rebuildWidgets();
    }

    private void recomputeVisible() {
        SkyGridConfig cfg = SkyGridConfig.getForDimension(dimension);
        List<String> out = new ArrayList<>();
        for (String id : allBlocks) {
            if (!filter.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(filter)) continue;
            if (showOnlyEnabled && !cfg.contains(id)) continue;
            out.add(id);
        }
        visible = out;
        clampScroll();
    }

    private int rowsThatFit() {
        return Math.max(1, (this.height - LIST_TOP - LIST_BOTTOM) / ROW_HEIGHT);
    }

    private void clampScroll() {
        int max = Math.max(0, visible.size() - rowsThatFit());
        if (scroll > max) scroll = max;
        if (scroll < 0)   scroll = 0;
    }

    /**
     * Buttons for the visible window. Rebuilt whenever the window moves, since
     * vanilla widgets are positional rather than virtualised.
     */
    private void buildRowButtons() {
        SkyGridConfig cfg = SkyGridConfig.getForDimension(dimension);
        int cx = this.width / 2;
        int rows = Math.min(rowsThatFit(), Math.max(0, visible.size() - scroll));

        for (int i = 0; i < rows; i++) {
            final String id = visible.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            boolean in = cfg.contains(id);

            // toggle in/out of the pool
            addRenderableWidget(Button.builder(
                Component.literal(in ? "§aOn" : "§7Off"), b -> {
                    SkyGridConfig c = SkyGridConfig.getForDimension(dimension);
                    if (c.contains(id)) c.remove(id);
                    else                c.addOrUpdate(id, 1);
                    commit(c);
                    rebuild();
                }).bounds(cx - 168, y, 34, 20).build());

            if (in) {
                int w = cfg.getWeight(id);
                // weight down
                addRenderableWidget(Button.builder(Component.literal("−"), b -> {
                    SkyGridConfig c = SkyGridConfig.getForDimension(dimension);
                    int nw = Math.max(1, c.getWeight(id) - 1);
                    c.addOrUpdate(id, nw);
                    commit(c);
                    rebuild();
                }).bounds(cx + 96, y, 20, 20).build());

                // weight up
                addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                    SkyGridConfig c = SkyGridConfig.getForDimension(dimension);
                    c.addOrUpdate(id, Math.min(1000, c.getWeight(id) + 1));
                    commit(c);
                    rebuild();
                }).bounds(cx + 148, y, 20, 20).build());
            }
        }
    }

    /** Persist and invalidate the generator's cached pools. */
    private void commit(SkyGridConfig cfg) {
        cfg.saveToDisk();
        SkyGridChunkGenerator.clearPools();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 12, 0xFFFFFFFF);

        SkyGridConfig cfg = SkyGridConfig.getForDimension(dimension);
        int rows = Math.min(rowsThatFit(), Math.max(0, visible.size() - scroll));

        for (int i = 0; i < rows; i++) {
            String id = visible.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT + 6;
            boolean in = cfg.contains(id);

            // NOTE: colours must be full ARGB. A 6-digit literal like 0xFFFFFF
            // has an alpha byte of 0 and renders completely invisible.
            g.drawString(this.font, id, cx - 126, y, in ? 0xFFFFFFFF : 0xFF909090);

            if (in) {
                g.drawString(this.font, "w:" + cfg.getWeight(id), cx + 120, y, 0xFFFFD700);
            }
        }

        // status line
        String status = visible.size() + " shown  ·  "
            + cfg.getBlockEntries().size() + " in " + dimension + " pool"
            + "  ·  mode: " + cfg.getMode();
        g.drawCenteredString(this.font, status, cx, this.height - 40, 0xFFA0A0A0);

        if (visible.isEmpty()) {
            g.drawCenteredString(this.font, "No blocks match that search.",
                cx, LIST_TOP + 20, 0xFFFF7070);
        }

        // scroll hint
        int max = Math.max(0, visible.size() - rowsThatFit());
        if (max > 0) {
            g.drawCenteredString(this.font,
                "scroll  " + (scroll + 1) + "–" + (scroll + rows) + " of " + visible.size(),
                cx, LIST_TOP - 12, 0xFF707070);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, visible.size() - rowsThatFit());
        if (max > 0) {
            int before = scroll;
            scroll -= (int) Math.signum(scrollY) * 3;
            clampScroll();
            if (scroll != before) {
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        // Everything is already saved on each edit; nothing to flush here.
        this.minecraft.setScreen(parent);
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Convenience for the loader hooks and the client command. */
    public static Screen create(Screen parent) {
        return new SkyGridConfigScreen(parent);
    }

    /** Guard used by the client command — config must be loaded first. */
    public static boolean isReady() {
        try {
            SkyGridConfig.get();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}

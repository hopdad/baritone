/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.ui;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game settings screen for Baritone, accessible via the pause menu.
 *
 * <h3>Layout</h3>
 * <pre>
 * ┌─ search ──────────────────────────────────────────────────────────────────────┐
 * │ [Pathing] [Movement] [Mob Avoidance] [Mining] [Rendering] [All]   [▲] [▼]    │
 * ├───────────────────────────────────────────────────────────────────────────────┤
 * │ settingName                              [  ON  / value-text  ]  [↩]         │
 * │ ...                                                                           │
 * ├───────────────────────────────────────────────────────────────────────────────┤
 * │             [ Revert Changes ]                        [ Done ]                │
 * └───────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Interaction model</h3>
 * <ul>
 *   <li>Boolean settings toggle ON/OFF on click.</li>
 *   <li>Numeric and String settings show an {@link EditBox}; values are applied
 *       when the screen is closed or the user scrolls past the row.</li>
 *   <li>Complex / java-only settings show the current value as non-editable text.</li>
 *   <li>"Revert Changes" restores every setting to the value it held when the
 *       screen was opened.</li>
 * </ul>
 */
public class BaritoneSettingsScreen extends Screen {

    // ── Layout constants ──────────────────────────────────────────────────────

    /** General padding between elements. */
    private static final int PAD         = 4;
    private static final int SEARCH_H    = 20;
    private static final int TAB_H       = 20;
    /** Y-coordinate where the scrollable setting rows begin. */
    private static final int HEADER_H    = PAD + SEARCH_H + PAD + TAB_H + PAD;
    private static final int FOOTER_H    = 28;
    private static final int ROW_H       = 24;
    /** Width of the ↩ reset button at the end of each row. */
    private static final int RESET_W     = 22;
    /** Width of the ▲ / ▼ scroll buttons on the right edge. */
    private static final int SCROLL_W    = 20;

    // ── State ─────────────────────────────────────────────────────────────────

    private SettingCategory currentCategory = SettingCategory.PATHING;
    private String          searchFilter    = "";
    private int             scrollOffset    = 0;

    /** Values snapshot taken when the screen opened, indexed parallel to snapshotSettings. */
    @SuppressWarnings("rawtypes")
    private final List<Settings.Setting> snapshotSettings = new ArrayList<>();
    private final List<Object>           snapshotValues   = new ArrayList<>();

    // ── Persistent widgets (survive rebuildRows) ──────────────────────────────

    private EditBox  searchBox;
    private Button[] categoryButtons;
    private Button   scrollUpBtn;
    private Button   scrollDownBtn;
    private Button   revertButton;
    private Button   doneButton;

    // ── Dynamic row state ─────────────────────────────────────────────────────

    /** One RowGroup per setting currently visible in the scrollable area. */
    private final List<RowGroup> rowGroups = new ArrayList<>();

    /**
     * True once the initial snapshot has been taken. Minecraft calls {@link #init()} again
     * on every window resize; without this flag, resizing after changing settings would
     * overwrite the revert baseline with the already-modified values.
     */
    private boolean snapshotTaken = false;

    // ── Construction ──────────────────────────────────────────────────────────

    public BaritoneSettingsScreen() {
        super(Component.literal("Baritone Settings"));
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        // Only take the snapshot once — the first time the screen opens.
        // Subsequent init() calls (e.g. on window resize) must not overwrite the
        // baseline, or "Revert Changes" would restore post-modification values.
        if (!snapshotTaken) {
            takeSnapshot();
            snapshotTaken = true;
        }
        buildPersistentWidgets();
        rebuildRows();
        syncCategoryHighlights();
    }

    @Override
    public void onClose() {
        applyAllPendingEdits();
        super.onClose();
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void takeSnapshot() {
        snapshotSettings.clear();
        snapshotValues.clear();
        for (Settings.Setting<?> s : BaritoneAPI.getSettings().allSettings) {
            if (!s.isJavaOnly()) {
                snapshotSettings.add(s);
                snapshotValues.add(s.value);
            }
        }
    }

    // ── Persistent widget construction ────────────────────────────────────────

    private void buildPersistentWidgets() {
        // ── Search box ──
        searchBox = new EditBox(this.font,
                PAD, PAD,
                this.width - PAD * 2, SEARCH_H,
                Component.literal("Search"));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search settings…"));
        searchBox.setResponder(query -> {
            searchFilter = query.toLowerCase().trim();
            scrollOffset = 0;
            rebuildRows();
        });
        addRenderableWidget(searchBox);

        // ── Category tabs ──
        SettingCategory[] cats = SettingCategory.values();
        categoryButtons = new Button[cats.length];
        int tabW = (this.width - PAD * 2) / cats.length;
        int tabY = PAD + SEARCH_H + PAD;
        for (int i = 0; i < cats.length; i++) {
            final SettingCategory cat = cats[i];
            categoryButtons[i] = Button.builder(Component.literal(cat.label), btn -> {
                        currentCategory = cat;
                        scrollOffset    = 0;
                        rebuildRows();
                        syncCategoryHighlights();
                    })
                    .pos(PAD + tabW * i, tabY)
                    .size(tabW - 2, TAB_H)
                    .build();
            addRenderableWidget(categoryButtons[i]);
        }

        // ── Scroll buttons (right edge, below tabs) ──
        int scrollBtnX = this.width - SCROLL_W - PAD;
        scrollUpBtn = Button.builder(Component.literal("▲"), btn -> {
                    scrollOffset = Math.max(0, scrollOffset - 1);
                    rebuildRows();
                })
                .pos(scrollBtnX, HEADER_H)
                .size(SCROLL_W, 20)
                .build();
        addRenderableWidget(scrollUpBtn);

        scrollDownBtn = Button.builder(Component.literal("▼"), btn -> {
                    int max = Math.max(0, filteredSettings().size() - visibleRowCount());
                    scrollOffset = Math.min(scrollOffset + 1, max);
                    rebuildRows();
                })
                .pos(scrollBtnX, HEADER_H + 22)
                .size(SCROLL_W, 20)
                .build();
        addRenderableWidget(scrollDownBtn);

        // ── Footer ──
        int footerY = this.height - FOOTER_H + 4;
        revertButton = Button.builder(Component.literal("Revert Changes"), btn -> revertAll())
                .pos(this.width / 2 - 152, footerY)
                .size(148, 20)
                .build();
        addRenderableWidget(revertButton);

        doneButton = Button.builder(Component.literal("Done"), btn -> this.onClose())
                .pos(this.width / 2 + 4, footerY)
                .size(148, 20)
                .build();
        addRenderableWidget(doneButton);
    }

    // ── Dynamic row management ────────────────────────────────────────────────

    private List<Settings.Setting<?>> filteredSettings() {
        List<Settings.Setting<?>> base = currentCategory.getSettings();
        if (searchFilter.isEmpty()) {
            return base;
        }
        List<Settings.Setting<?>> result = new ArrayList<>();
        for (Settings.Setting<?> s : base) {
            if (s.getName().toLowerCase().contains(searchFilter)
                    || rawValueString(s).toLowerCase().contains(searchFilter)) {
                result.add(s);
            }
        }
        return result;
    }

    private int visibleRowCount() {
        return Math.max(1, (this.height - HEADER_H - FOOTER_H) / ROW_H);
    }

    /**
     * Tears down all current widgets, re-adds the persistent ones, then builds a new
     * set of row groups for the currently visible settings slice.
     *
     * <p>Using {@link #clearWidgets()} + re-add avoids needing {@code removeWidget},
     * whose parameter type may differ across MC versions.
     */
    private void rebuildRows() {
        // Apply any in-progress edits before discarding the widgets that hold them.
        applyAllPendingEdits();
        rowGroups.clear();

        // Full widget reset — safest across MC versions.
        this.clearWidgets();

        // Re-add persistent widgets (same objects, so EditBox preserves its text).
        addRenderableWidget(searchBox);
        for (Button btn : categoryButtons) addRenderableWidget(btn);
        addRenderableWidget(scrollUpBtn);
        addRenderableWidget(scrollDownBtn);
        addRenderableWidget(revertButton);
        addRenderableWidget(doneButton);

        // Compute which settings slice to show.
        List<Settings.Setting<?>> vis = filteredSettings();
        int maxScroll = Math.max(0, vis.size() - visibleRowCount());
        scrollOffset  = Math.min(scrollOffset, maxScroll);

        // Column geometry: leave room for the scroll buttons on the right.
        int usableW = this.width - SCROLL_W - PAD * 3;
        int nameW   = usableW * 2 / 5;                      // ~40 % for the name label
        int valueW  = usableW - nameW - PAD - RESET_W - PAD;
        int valueX  = PAD + nameW + PAD;
        int resetX  = valueX + valueW + PAD;

        int count = Math.min(visibleRowCount(), vis.size() - scrollOffset);
        for (int i = 0; i < count; i++) {
            Settings.Setting<?> setting = vis.get(scrollOffset + i);
            int rowY = HEADER_H + i * ROW_H;
            rowGroups.add(new RowGroup(setting, rowY, nameW, valueX, valueW, resetX, this));
        }

        scrollUpBtn.active   = scrollOffset > 0;
        scrollDownBtn.active = scrollOffset < maxScroll;
    }

    private void applyAllPendingEdits() {
        for (RowGroup g : rowGroups) g.applyPendingEdit();
    }

    /**
     * Marks the active category tab with a "»" prefix so the user can see which tab
     * is selected without relying on a custom visual state.
     */
    private void syncCategoryHighlights() {
        SettingCategory[] cats = SettingCategory.values();
        for (int i = 0; i < cats.length; i++) {
            boolean active = cats[i] == currentCategory;
            categoryButtons[i].setMessage(
                    Component.literal(active ? "» " + cats[i].label : cats[i].label));
        }
    }

    // ── Mouse / keyboard ──────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Positive scrollY → scroll wheel up → move list up (earlier settings).
        int delta     = scrollY > 0 ? -1 : 1;
        int maxScroll = Math.max(0, filteredSettings().size() - visibleRowCount());
        int newOffset = Math.max(0, Math.min(scrollOffset + delta, maxScroll));
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset;
            rebuildRows();
        }
        return true;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Delegates entirely to {@code super} which renders the background and all widgets
     * added via {@link #addRenderableWidget}. Custom separator lines and scroll indicators
     * could be drawn here using {@link GuiGraphicsExtractor} methods once the MC 26.x API
     * surface for that class is confirmed (see commented examples below).
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        // Example custom draws — uncomment once GuiGraphicsExtractor API is verified:
        //   guiGraphics.fill(0, HEADER_H - 1, this.width, HEADER_H, 0x44FFFFFF);      // header separator
        //   guiGraphics.fill(0, this.height - FOOTER_H, this.width, this.height - FOOTER_H + 1, 0x44FFFFFF); // footer separator
        //   if (scrollOffset > 0) guiGraphics.drawString(this.font, "▲ more above", PAD, HEADER_H - 10, 0x888888, false);
    }

    // ── Revert ────────────────────────────────────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void revertAll() {
        for (int i = 0; i < snapshotSettings.size(); i++) {
            ((Settings.Setting<Object>) snapshotSettings.get(i)).value = snapshotValues.get(i);
        }
        rebuildRows();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Returns the string representation of a setting's current value,
     * or {@code "?"} if serialisation fails.
     */
    @SuppressWarnings("rawtypes")
    static String rawValueString(Settings.Setting<?> setting) {
        try {
            return SettingsUtil.settingValueToString((Settings.Setting) setting);
        } catch (Exception e) {
            return "?";
        }
    }

    // ── RowGroup ──────────────────────────────────────────────────────────────

    /**
     * Manages the set of widgets that represent one setting row:
     * <ul>
     *   <li>A {@link StringWidget} label showing the setting name.</li>
     *   <li>Either a toggle {@link Button} (Boolean) or an {@link EditBox} (numeric / String),
     *       or a disabled display {@link Button} for complex / java-only types.</li>
     *   <li>A small reset {@link Button} ("↩") that restores the default value.</li>
     * </ul>
     *
     * <p>All widgets are added to the parent {@link Screen} in the constructor. The row is
     * discarded (and widgets implicitly removed via {@link Screen#clearWidgets()}) whenever
     * {@link BaritoneSettingsScreen#rebuildRows()} is called.
     */
    private static final class RowGroup {

        private final Settings.Setting<?> setting;
        /** Non-null only for Boolean settings and uneditable complex types. */
        private final Button  valueButton;
        /** Non-null only for editable non-Boolean settings. */
        private final EditBox valueEdit;
        /** Whether the EditBox contains a value not yet applied to the setting. */
        private boolean editDirty;

        RowGroup(Settings.Setting<?> setting,
                 int rowY, int nameW, int valueX, int valueW, int resetX,
                 BaritoneSettingsScreen screen) {
            this.setting = setting;

            // ── Name label ──
            StringWidget nameLabel = new StringWidget(
                    PAD, rowY, nameW, ROW_H,
                    Component.literal(setting.getName()),
                    screen.font);
            nameLabel.setColor(0xFFBBBBBB);
            screen.addRenderableWidget(nameLabel);

            // ── Value widget ──
            Class<?> cls = setting.getValueClass();
            boolean isBool = cls == Boolean.class || cls == boolean.class;
            boolean isSimpleEditable = !setting.isJavaOnly() && (
                    isBool
                    || cls == Integer.class  || cls == int.class
                    || cls == Long.class     || cls == long.class
                    || cls == Double.class   || cls == double.class
                    || cls == Float.class    || cls == float.class
                    || cls == String.class);

            if (isBool) {
                valueEdit   = null;
                valueButton = Button.builder(boolLabel((Boolean) setting.value), btn -> {
                            @SuppressWarnings("unchecked")
                            Settings.Setting<Boolean> bs = (Settings.Setting<Boolean>) setting;
                            bs.value = !bs.value;
                            btn.setMessage(boolLabel(bs.value));
                        })
                        .pos(valueX, rowY + 2)
                        .size(valueW, ROW_H - 4)
                        .build();
                screen.addRenderableWidget(valueButton);

            } else if (isSimpleEditable) {
                valueButton = null;
                valueEdit   = new EditBox(screen.font,
                        valueX, rowY + 2, valueW, ROW_H - 4,
                        Component.literal(setting.getName()));
                valueEdit.setMaxLength(128);
                valueEdit.setValue(rawValueString(setting));
                valueEdit.setResponder(val -> editDirty = true);
                screen.addRenderableWidget(valueEdit);

            } else {
                // Complex or java-only: show value as disabled button (non-interactive label).
                valueEdit   = null;
                valueButton = Button.builder(
                                Component.literal(rawValueString(setting))
                                        .withStyle(ChatFormatting.DARK_GRAY),
                                btn -> {})
                        .pos(valueX, rowY + 2)
                        .size(valueW, ROW_H - 4)
                        .build();
                valueButton.active = false;
                screen.addRenderableWidget(valueButton);
            }

            // ── Reset button ──
            Button resetBtn = Button.builder(Component.literal("↩"), btn -> {
                        setting.reset();
                        // Sync the value widget to the restored default.
                        if (valueEdit != null) {
                            valueEdit.setValue(rawValueString(setting));
                            editDirty = false;
                        }
                        if (valueButton != null && isBool) {
                            valueButton.setMessage(boolLabel((Boolean) setting.value));
                        }
                    })
                    .pos(resetX, rowY + 2)
                    .size(RESET_W, ROW_H - 4)
                    .build();
            screen.addRenderableWidget(resetBtn);
        }

        /**
         * If the EditBox was modified, attempt to parse and apply the typed value.
         * On parse failure the setting is left unchanged and the box is not reset
         * (so the user can see and correct the bad input).
         */
        void applyPendingEdit() {
            if (valueEdit != null && editDirty) {
                try {
                    SettingsUtil.parseAndApply(
                            BaritoneAPI.getSettings(),
                            setting.getName(),
                            valueEdit.getValue());
                    // Only clear the dirty flag on success so that invalid input is not
                    // silently discarded.  If parsing fails, editDirty stays true: the
                    // EditBox retains the bad text and the user gets another chance to
                    // correct it before closing the screen.
                    editDirty = false;
                } catch (Exception ignored) {
                    // Leave the setting unchanged; the EditBox retains the invalid text.
                }
            }
        }

        private static Component boolLabel(boolean val) {
            return val
                    ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
                    : Component.literal("OFF").withStyle(ChatFormatting.RED);
        }
    }
}

package de.texturemixer.gui;

import de.texturemixer.Category;
import de.texturemixer.MixerConfig;
import de.texturemixer.PackApplier;
import de.texturemixer.PackScanner;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Eigenes dunkles Menü (kein Minecraft-Look): Reiter pro Gegenstand, oben ein Umschalter
 * "Platziert / In der Hand" (nur bei Blöcken und Crystals), darunter ein Raster mit Vorschaubildern.
 * Klicks laufen über unsichtbare Buttons, das Aussehen wird komplett selbst darübergezeichnet.
 */
public class MixerScreen extends Screen {
    private static final int BG = 0xF2101319;
    private static final int PANEL_BORDER = 0xFF2A2F3B;
    private static final int HEADER = 0xFF0B0D11;
    private static final int ACCENT = 0xFFFFC933;
    private static final int ACCENT_DARK = 0xFF5C4712;
    private static final int CARD = 0xFF171B22;
    private static final int CARD_HOVER = 0xFF212733;
    private static final int TEXT = 0xFFE6E9EF;
    private static final int MUTED = 0xFF8A93A3;

    private static final Map<Integer, String> KEY_NAMES = buildKeyNames();
    private static final int CW = 76;
    private static final int CH = 72;
    /** Karten in der Body-Ansicht sind höher (Figur 64 px). */
    private static final int CH_BODY = 92;
    private static final int GAP = 6;
    private static final int LIST_W = 140;
    private static final int ROW_H = 13;
    private static int lastTab = 0;

    private record Card(int x, int y, PackScanner.PackInfo pack) {}

    private record Ctl(int x, int y, int w, int h, Supplier<String> label,
                       BooleanSupplier selected, BooleanSupplier enabled) {}

    private final Screen parent;
    private List<PackScanner.PackInfo> packs;
    private final Map<String, ItemStack> icons = new HashMap<>();
    private final Map<String, ItemStack[]> armorSets = new HashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Ctl> ctls = new ArrayList<>();

    private int tab = lastTab;
    private int variantIdx = 0;
    private int page = 0;
    private int pages = 1;
    private boolean listening = false;
    private boolean dirty = false;

    // Blöcke-Reiter: alphabetische Liste links
    private String selectedBlock = null;
    private int blockScroll = 0;
    private List<String> blockNames = new ArrayList<>();
    private int listX, listY, listRows;

    private int px0, py0, px1, py1;
    private int tabSize = 22;
    private int tabStartX = 0;
    private int tabY = 0;
    private int tabsPerRow = 1;
    private int gridX = 12;
    private int subY = 0;
    private int loadBudget = 2;

    public MixerScreen(Screen parent) {
        super(Text.literal("Pikachu Styles"));
        this.parent = parent;
        this.packs = PackScanner.scan();
        for (Category c : Category.ALL) {
            icons.put(c.id(), makeIcon(c));
            for (Category.Variant v : c.variants()) {
                if (v.isArmorItems()) armorSets.put(c.id(), makeArmorSet(v.material()));
            }
        }
    }

    private static ItemStack[] makeArmorSet(String material) {
        String[] parts = {"helmet", "chestplate", "leggings", "boots"};
        ItemStack[] set = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            set[i] = new ItemStack(Registries.ITEM.get(Identifier.of("minecraft", material + "_" + parts[i])));
        }
        return set;
    }

    private static ItemStack makeIcon(Category c) {
        ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.of("minecraft", c.icon())));
        if (c.potionIcon() != null) {
            Registries.POTION.getEntry(Identifier.of("minecraft", c.potionIcon()))
                    .ifPresent(e -> stack.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(e)));
        }
        return stack;
    }

    private Category cat() {
        return Category.ALL.get(tab);
    }

    private boolean isBlocks() {
        return cat().id().equals("blocks");
    }

    private Category.Variant variant() {
        Category c = cat();
        if (isBlocks()) return Category.blockVariant(selectedBlock == null ? "none" : selectedBlock);
        return c.variants().get(Math.min(variantIdx, c.variants().size() - 1));
    }

    private static String pretty(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** Kartenhöhe: in der Body-Ansicht höher als sonst. */
    private int ch() {
        return variant().isBody() ? CH_BODY : CH;
    }

    private List<PackScanner.PackInfo> entries() {
        List<PackScanner.PackInfo> l = new ArrayList<>();
        if (isBlocks() && selectedBlock == null) return l;
        String id = cat().fullId(variant());
        l.add(null); // null = nichts überschreiben
        for (PackScanner.PackInfo p : packs) {
            if (isBlocks()) {
                if (p.blocks().contains(selectedBlock)) l.add(p);
            } else if (p.categories().contains(id)) {
                l.add(p);
            }
        }
        return l;
    }

    private String standardLabel() {
        return (cat().dual() && variant().hand() != null) ? "Wie platziert" : "Standard";
    }

    // ---------- Aufbau ----------

    private ButtonWidget hit(int x, int y, int w, int h, Runnable action) {
        return this.addDrawableChild(ButtonWidget.builder(Text.empty(), btn -> action.run())
                .dimensions(x, y, w, h).build());
    }

    private void addCtl(int x, int y, int w, int h, Supplier<String> label,
                        BooleanSupplier selected, BooleanSupplier enabled, Runnable action) {
        ctls.add(new Ctl(x, y, w, h, label, selected, enabled));
        hit(x, y, w, h, action).active = enabled.getAsBoolean();
    }

    @Override
    protected void init() {
        cards.clear();
        ctls.clear();
        int n = Category.ALL.size();
        tab = Math.max(0, Math.min(tab, n - 1));
        variantIdx = Math.max(0, Math.min(variantIdx, cat().variants().size() - 1));

        px0 = 8;
        py0 = 8;
        px1 = this.width - 8;
        py1 = this.height - 8;

        // Kopfzeile: Taste zum Öffnen
        addCtl(px1 - 8 - 130, py0 + 4, 130, 16,
                () -> listening ? "Taste drücken..." : "Taste: " + keyName(MixerConfig.get().openKey),
                () -> listening, () -> true, () -> listening = true);

        // Reiter (bei Platzmangel in mehreren Reihen)
        int avail = px1 - px0 - 24;
        tabSize = 20;
        for (int s = 24; s >= 18; s--) {
            if (n * (s + 2) - 2 <= avail) {
                tabSize = s;
                break;
            }
        }
        tabsPerRow = Math.max(1, (avail + 2) / (tabSize + 2));
        int rowW = Math.min(n, tabsPerRow) * (tabSize + 2) - 2;
        tabStartX = (this.width - rowW) / 2;
        tabY = py0 + 30;
        int tabRows = (n + tabsPerRow - 1) / tabsPerRow;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            int x = tabStartX + (i % tabsPerRow) * (tabSize + 2);
            int y = tabY + (i / tabsPerRow) * (tabSize + 2);
            this.addDrawableChild(ButtonWidget.builder(Text.empty(), b -> {
                tab = idx;
                lastTab = idx;
                variantIdx = 0;
                page = 0;
                this.clearAndInit();
            }).dimensions(x, y, tabSize, tabSize).build());
        }
        subY = tabY + tabRows * (tabSize + 2) + 4;

        // Umschalter Platziert / In der Hand
        Category cat = cat();
        if (cat.dual()) {
            int segW = 92;
            int sx = px1 - 12 - 2 * segW - 2;
            for (int i = 0; i < cat.variants().size(); i++) {
                final int vi = i;
                String label = cat.variants().get(i).label();
                addCtl(sx + i * (segW + 2), subY, segW, 18, () -> label,
                        () -> variantIdx == vi, () -> true, () -> {
                            variantIdx = vi;
                            page = 0;
                            this.clearAndInit();
                        });
            }
        }

        int gridTop = subY + 26;
        boolean blocks = isBlocks();

        // Blöcke: alphabetische Liste links (nur Blöcke, deren Texturen in deinen Packs vorkommen)
        int leftW = 0;
        if (blocks) {
            TreeSet<String> set = new TreeSet<>();
            for (PackScanner.PackInfo p : packs) set.addAll(p.blocks());
            blockNames = new ArrayList<>(set);
            if (selectedBlock == null || !set.contains(selectedBlock)) {
                selectedBlock = blockNames.isEmpty() ? null : blockNames.get(0);
            }

            leftW = LIST_W + 8;
            listX = px0 + 12;
            listY = gridTop;
            int bottom = py1 - 32;
            listRows = Math.max(3, (bottom - listY - 22) / ROW_H);
            int maxScroll = Math.max(0, blockNames.size() - listRows);
            blockScroll = Math.max(0, Math.min(blockScroll, maxScroll));
            int si = selectedBlock == null ? -1 : blockNames.indexOf(selectedBlock);
            if (si >= 0 && (si < blockScroll || si >= blockScroll + listRows)) {
                blockScroll = Math.max(0, Math.min(maxScroll, si - listRows / 2));
            }
            for (int k = 0; k < listRows && blockScroll + k < blockNames.size(); k++) {
                final String name = blockNames.get(blockScroll + k);
                hit(listX, listY + k * ROW_H, LIST_W, ROW_H - 1, () -> {
                    selectedBlock = name;
                    page = 0;
                    this.clearAndInit();
                });
            }
            int by = listY + listRows * ROW_H + 3;
            addCtl(listX, by, LIST_W / 2 - 2, 16, () -> "^", () -> false, () -> blockScroll > 0, () -> {
                blockScroll = Math.max(0, blockScroll - listRows);
                this.clearAndInit();
            });
            addCtl(listX + LIST_W / 2 + 2, by, LIST_W / 2 - 2, 16, () -> "v", () -> false,
                    () -> blockScroll + listRows < blockNames.size(), () -> {
                        blockScroll = Math.min(Math.max(0, blockNames.size() - listRows), blockScroll + listRows);
                        this.clearAndInit();
                    });
        }

        // Raster
        Category.Variant v = variant();
        List<PackScanner.PackInfo> entries = entries();
        int availW = (px1 - px0 - 24) - leftW;
        int cols = Math.max(1, Math.min(8, (availW + GAP) / (CW + GAP)));
        int availH = (py1 - 32) - gridTop;
        int rows = Math.max(1, (availH + GAP) / (ch() + GAP));
        int perPage = cols * rows;
        pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        int gridW = cols * CW + (cols - 1) * GAP;
        gridX = blocks ? px0 + 12 + leftW : (this.width - gridW) / 2;
        int start = page * perPage;
        int end = Math.min(entries.size(), start + perPage);
        for (int i = start; i < end; i++) {
            int k = i - start;
            int x = gridX + (k % cols) * (CW + GAP);
            int y = gridTop + (k / cols) * (ch() + GAP);
            PackScanner.PackInfo p = entries.get(i);
            cards.add(new Card(x, y, p));
            hit(x, y, CW, ch(), () -> select(cat, v, p));
        }

        // Fußzeile
        int fy = py1 - 24;
        addCtl(px0 + 12, fy, 20, 18, () -> "<", () -> false, () -> page > 0, () -> {
            page--;
            this.clearAndInit();
        });
        addCtl(px0 + 12 + 20 + 60, fy, 20, 18, () -> ">", () -> false, () -> page < pages - 1, () -> {
            page++;
            this.clearAndInit();
        });
        int rx = px1 - 12;
        addCtl(rx - 56, fy, 56, 18, () -> "Fertig", () -> false, () -> true, this::close);
        addCtl(rx - 56 - 4 - 64, fy, 64, 18, () -> "Anwenden", () -> dirty, () -> true, () -> {
            PackApplier.apply();
            dirty = false;
        });
        addCtl(rx - 56 - 4 - 64 - 4 - 78, fy, 78, 18, () -> "Neu scannen", () -> false, () -> true, () -> {
            packs = PackScanner.scan();
            this.clearAndInit();
        });
    }

    private void select(Category cat, Category.Variant v, PackScanner.PackInfo p) {
        MixerConfig cfg = MixerConfig.get();
        String id = cat.fullId(v);
        if (p == null) cfg.selections.remove(id);
        else cfg.selections.put(id, p.name());
        cfg.save();
        dirty = true;
    }

    // ---------- Zeichnen ----------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); // zeichnet nur die unsichtbaren Klickflächen
        loadBudget = 2;

        Category cat = cat();
        Category.Variant v = variant();
        String active = MixerConfig.get().selections.get(cat.fullId(v));

        // Fenster
        context.fill(px0, py0, px1, py1, BG);
        context.fill(px0, py0, px1, py0 + 28, HEADER);
        context.fill(px0, py0 + 28, px1, py0 + 29, ACCENT);
        border(context, px0, py0, px1 - px0, py1 - py0, PANEL_BORDER);

        context.drawTextWithShadow(this.textRenderer, Text.literal("PIKACHU STYLES"), px0 + 12, py0 + 8, ACCENT);
        int tw = this.textRenderer.getWidth("PIKACHU STYLES");
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(dirty ? "|  nicht angewendet" : "|  " + packs.size() + " Packs gefunden"),
                px0 + 12 + tw + 6, py0 + 8, dirty ? 0xFFFFD34D : MUTED);

        // Reiter
        String hoverName = null;
        for (int i = 0; i < Category.ALL.size(); i++) {
            Category c = Category.ALL.get(i);
            int x = tabStartX + (i % tabsPerRow) * (tabSize + 2);
            int y = tabY + (i / tabsPerRow) * (tabSize + 2);
            boolean sel = i == tab;
            boolean hov = inside(mouseX, mouseY, x, y, tabSize, tabSize);
            if (hov) hoverName = c.name();
            context.fill(x, y, x + tabSize, y + tabSize, sel ? ACCENT_DARK : hov ? CARD_HOVER : CARD);
            border(context, x, y, tabSize, tabSize, sel ? ACCENT : PANEL_BORDER);
            context.drawItem(icons.get(c.id()), x + (tabSize - 16) / 2, y + (tabSize - 16) / 2);
        }

        // Titelzeilen
        boolean blocks = isBlocks();
        int count = Math.max(0, entries().size() - 1);
        int titleX = blocks ? px0 + 12 : gridX;
        String title = blocks
                ? "BLÖCKE: " + (selectedBlock == null ? "-" : pretty(selectedBlock).toUpperCase()) + "  (" + count + ")"
                : cat.name().toUpperCase() + "  (" + count + ")";
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(fit(title, this.width - titleX * 2 - (cat.dual() ? 200 : 0))), titleX, subY, TEXT);
        for (Card c : cards) {
            if (inside(mouseX, mouseY, c.x(), c.y(), CW, ch())) {
                hoverName = c.pack() == null ? standardLabel() : stripZip(c.pack().name());
            }
        }
        int maxW = Math.max(40, this.width - titleX * 2 - (cat.dual() ? 200 : 0));
        String line2 = hoverName != null ? hoverName
                : "Aktiv: " + (active == null ? standardLabel() : stripZip(active));
        context.drawTextWithShadow(this.textRenderer, Text.literal(fit(line2, maxW)),
                titleX, subY + 11, hoverName != null ? 0xFFFFD34D : MUTED);

        // Blockliste
        if (blocks) drawBlockList(context, mouseX, mouseY);

        // Karten
        for (Card c : cards) {
            drawCard(context, c, cat, v, active, mouseX, mouseY);
        }
        if (blocks && selectedBlock == null) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Keine Block-Texturen in deinen Packs gefunden."),
                    gridX, listY + 4, MUTED);
        }

        // Bedienelemente
        for (Ctl c : ctls) {
            drawCtl(context, c, mouseX, mouseY);
        }

        // Seitenanzeige
        int fy = py1 - 24;
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal((page + 1) + "/" + pages), px0 + 12 + 20 + 30, fy + 5, MUTED);
    }

    private void drawBlockList(DrawContext context, int mouseX, int mouseY) {
        int h = listRows * ROW_H;
        context.fill(listX - 1, listY - 1, listX + LIST_W + 1, listY + h + 1, CARD);
        border(context, listX - 1, listY - 1, LIST_W + 2, h + 2, PANEL_BORDER);
        for (int k = 0; k < listRows && blockScroll + k < blockNames.size(); k++) {
            String name = blockNames.get(blockScroll + k);
            int y = listY + k * ROW_H;
            boolean sel = name.equals(selectedBlock);
            boolean hov = inside(mouseX, mouseY, listX, y, LIST_W, ROW_H - 1);
            if (sel) {
                context.fill(listX, y, listX + LIST_W, y + ROW_H - 1, ACCENT_DARK);
            } else if (hov) {
                context.fill(listX, y, listX + LIST_W, y + ROW_H - 1, CARD_HOVER);
            }
            boolean chosen = MixerConfig.get().selections.containsKey("block:" + name);
            int color = sel ? 0xFFFFFFFF : chosen ? ACCENT : 0xFFC8CEDA;
            context.drawTextWithShadow(this.textRenderer, Text.literal(fit(pretty(name), LIST_W - 8)),
                    listX + 4, y + 2, color);
        }
    }

    private void drawCtl(DrawContext ctx, Ctl c, int mx, int my) {
        boolean en = c.enabled().getAsBoolean();
        boolean sel = c.selected().getAsBoolean();
        boolean hov = en && inside(mx, my, c.x(), c.y(), c.w(), c.h());
        int bg = !en ? 0xFF13161B : sel ? ACCENT_DARK : hov ? 0xFF262C38 : 0xFF1B1F27;
        ctx.fill(c.x(), c.y(), c.x() + c.w(), c.y() + c.h(), bg);
        border(ctx, c.x(), c.y(), c.w(), c.h(), sel ? ACCENT : hov ? 0xFF4A5366 : PANEL_BORDER);
        int tc = !en ? 0xFF555B68 : sel ? 0xFFFFFFFF : 0xFFC8CEDA;
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(fit(c.label().get(), c.w() - 6)),
                c.x() + c.w() / 2, c.y() + (c.h() - 8) / 2, tc);
    }

    private void drawCard(DrawContext context, Card c, Category cat, Category.Variant v,
                          String active, int mouseX, int mouseY) {
        boolean selected = c.pack() == null ? active == null : c.pack().name().equals(active);
        boolean hov = inside(mouseX, mouseY, c.x(), c.y(), CW, ch());
        int cardH = ch();

        context.fill(c.x(), c.y(), c.x() + CW, c.y() + cardH, hov ? CARD_HOVER : CARD);
        border(context, c.x(), c.y(), CW, cardH, selected ? ACCENT : hov ? 0xFF4A5366 : PANEL_BORDER);
        if (selected) border(context, c.x() + 1, c.y() + 1, CW - 2, cardH - 2, ACCENT);

        int size = PreviewCache.SIZE;
        boolean bodyView = v.isBody();
        int previewH = bodyView ? BodyView.H + 2 : size;
        int px = c.x() + (CW - size) / 2;
        int py = c.y() + 5;
        if (v.isBody()) {
            context.fillGradient(px, py, px + size, py + previewH, 0xFF161B24, 0xFF0A0C10);
        } else {
            context.fill(px, py, px + size, py + previewH, 0xFF0C0E12);
        }

        String name;
        if (bodyView) {
            // Body: Spieler-Skin mit der Rüstung des Packs (Standard = Vanilla-Rüstung)
            name = c.pack() == null ? standardLabel() : stripZip(c.pack().name());
            PreviewCache.Body body = PreviewCache.peekBody(c.pack(), cat, v);
            if (body == null && loadBudget > 0) {
                loadBudget--;
                body = PreviewCache.loadBody(c.pack(), cat, v);
            }
            if (body == null) {
                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("..."),
                        px + size / 2, py + previewH / 2 - 4, 0xFF888888);
            } else if (body.isEmpty()) {
                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("-"),
                        px + size / 2, py + previewH / 2 - 4, 0xFF888888);
            } else {
                BodyView.draw(context, px + (size - BodyView.W) / 2, py + 1, body);
            }
        } else if (c.pack() == null) {
            name = standardLabel();
            ItemStack[] set = v.isArmorItems() ? armorSets.get(cat.id()) : null;
            if (set != null) {
                // Item: Helm, Brustplatte, Hose, Stiefel als 2x2
                int ox = px + (size - 32) / 2;
                int oy = py + (size - 32) / 2;
                for (int i = 0; i < 4; i++) {
                    context.drawItem(set[i], ox + (i % 2) * 16, oy + (i / 2) * 16);
                }
            } else {
                context.drawItem(icons.get(cat.id()), px + (size - 16) / 2, py + (size - 16) / 2);
            }
        } else {
            name = stripZip(c.pack().name());
            PreviewCache.Preview pv = PreviewCache.peek(c.pack(), cat, v);
            if (pv == null && loadBudget > 0) {
                loadBudget--;
                pv = PreviewCache.load(c.pack(), cat, v);
            }
            if (pv == null) {
                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("..."),
                        px + size / 2, py + size / 2 - 4, 0xFF888888);
            } else if (pv.id() == null) {
                context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("-"),
                        px + size / 2, py + size / 2 - 4, 0xFF888888);
            } else {
                float vOff = pv.currentFrame() * pv.frameH();
                context.drawTexture(RenderPipelines.GUI_TEXTURED, pv.id(),
                        px + (size - pv.dw()) / 2, py + (size - pv.dh()) / 2,
                        0f, vOff, pv.dw(), pv.dh(), pv.frameW(), pv.frameH(), pv.texW(), pv.texH());
            }
        }

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(fit(name, CW - 6)),
                c.x() + CW / 2, c.y() + 5 + previewH + 6, selected ? ACCENT : TEXT);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void border(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y, x + 1, y + h, color);
        c.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String fit(String s, int maxW) {
        if (this.textRenderer.getWidth(s) <= maxW) return s;
        String ell = "..";
        while (s.length() > 1 && this.textRenderer.getWidth(s + ell) > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ell;
    }

    private static String stripZip(String name) {
        return name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    // ---------- Taste zum Öffnen ----------

    private static String keyName(int key) {
        if (key < 0) return "Nicht belegt";
        String n = KEY_NAMES.get(key);
        return n != null ? n : "Taste " + key;
    }

    private static Map<Integer, String> buildKeyNames() {
        Map<Integer, String> map = new HashMap<>();
        for (Field f : GLFW.class.getFields()) {
            String n = f.getName();
            if (!n.startsWith("GLFW_KEY_") || f.getType() != int.class) continue;
            String shortName = n.substring("GLFW_KEY_".length());
            if (shortName.equals("LAST") || shortName.equals("UNKNOWN")) continue;
            try {
                map.putIfAbsent(f.getInt(null), shortName);
            } catch (IllegalAccessException ignored) {
            }
        }
        return map;
    }

    /** Während der Tastenwahl ESC nicht das Menü schließen lassen. */
    @Override
    public boolean shouldCloseOnEsc() {
        return !listening;
    }

    @Override
    public void tick() {
        super.tick();
        if (!listening || this.client == null) return;

        long handle = this.client.getWindow().getHandle();
        for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) {
            if (GLFW.glfwGetKey(handle, k) != GLFW.GLFW_PRESS) continue;
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                // Abbruch, Belegung bleibt wie sie war
            } else if (k == GLFW.GLFW_KEY_DELETE || k == GLFW.GLFW_KEY_BACKSPACE) {
                MixerConfig.get().openKey = -1;
                MixerConfig.get().save();
            } else {
                MixerConfig.get().openKey = k;
                MixerConfig.get().save();
            }
            listening = false;
            return;
        }
    }

    @Override
    public void close() {
        if (dirty) {
            dirty = false;
            PackApplier.apply();
        }
        if (this.client != null) this.client.setScreen(parent);
    }
}

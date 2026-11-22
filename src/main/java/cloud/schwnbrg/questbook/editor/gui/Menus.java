package cloud.schwnbrg.questbook.editor.gui;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.editor.Describe;
import cloud.schwnbrg.questbook.editor.EditorService;
import cloud.schwnbrg.questbook.editor.EditorSession;
import cloud.schwnbrg.questbook.model.Cond;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Rule;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Target;
import cloud.schwnbrg.questbook.model.TextVariant;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
import cloud.schwnbrg.questbook.storage.Database;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The editor menus. Quest list (A), quest (B), step (C), trigger picker (D), requirements (E), and for branching:
 * "Then…" (where a step leads), the condition picker, the step-target picker, text variants, world choice options and
 * the branch map. Nothing in here asks the editor to type a rule: conditions are picked from what exists. All text is
 * from messages.yml.
 */
public final class Menus {

    private static final int PAGE = 45;

    private final QuestbookPlugin plugin;
    private final EditorService editor;

    public Menus(QuestbookPlugin plugin, EditorService editor) {
        this.plugin = plugin;
        this.editor = editor;
    }

    private Messages m() { return plugin.messages(); }

    /** A MiniMessage string from messages.yml, for icon names and lore. */
    private String t(String key, String... pairs) { return m().raw(key, pairs); }

    private static String esc(String s) { return Messages.esc(s); }

    private Describe d() { return editor.describe(); }

    // ---------- shared look ----------

    /** Colour of a button/option by position (0..3), 4 for every other kind of condition. */
    private static final Material[] WOOL = {Material.LIME_WOOL, Material.ORANGE_WOOL, Material.LIGHT_BLUE_WOOL, Material.MAGENTA_WOOL, Material.YELLOW_WOOL};
    private static final Material[] PANE = {Material.LIME_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE};

    private static String colour(int i) { return "<" + Describe.COLOURS.get(i) + ">"; }

    /** A label pane at the start of a row ("Text", "Then…"). */
    private ItemStack label(String key) {
        return Menu.icon(Material.BLACK_STAINED_GLASS_PANE, t(key), t(key + "-lore"));
    }

    /** Splits plain text into lore lines of about {@code width} characters, at most {@code max} lines. */
    static List<String> wrap(String text, int width, int max) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String w : para.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + w.length() > width) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(w);
            }
            out.add(line.toString());
        }
        if (out.size() > max) {
            List<String> cut = new ArrayList<>(out.subList(0, max));
            cut.set(max - 1, cut.get(max - 1) + " …");
            return cut;
        }
        return out;
    }

    private Material condIcon(Quest q, Cond c) {
        return switch (c) {
            case Cond.Picked pk -> {
                if (pk.otherQuest() && !pk.quest().equals(q.id())) yield Material.WRITABLE_BOOK;
                Step st = q.step(pk.step());
                yield st != null && st.trigger() instanceof Trigger.Answer ? Material.NAME_TAG : WOOL[Describe.colour(q, c)];
            }
            case Cond.Ending e -> Material.WRITTEN_BOOK;
            case Cond.HasItem h -> {
                Material mat = Material.matchMaterial(h.item());
                yield mat != null && mat.isItem() && !mat.isAir() ? mat : Material.CHEST;
            }
        };
    }

    private Material targetIcon(Quest q, int from, Target t) {
        if (t == null) return from + 1 < q.steps().size() ? Material.GRAY_DYE : Material.NETHER_STAR;
        if (t.isEnd()) return Material.NETHER_STAR;
        int i = q.indexOf(t.step());
        return i < 0 ? Material.BARRIER : triggerIcon(q.steps().get(i).trigger());
    }

    /** One line per rule plus the fallback, coloured, for lore. */
    private List<String> routeLore(Quest q, int i) {
        Step s = q.steps().get(i);
        List<String> out = new ArrayList<>();
        Describe d = d();
        for (Rule r : s.rules()) {
            out.add(colour(Describe.colour(q, r.cond())) + t("gui-route-if", "cond", esc(d.cond(q, r.cond())), "target", esc(d.target(q, i, r.target()))));
        }
        out.add(t(s.rules().isEmpty() ? "gui-route-then" : "gui-route-otherwise", "target", esc(d.target(q, i, s.next()))));
        return out;
    }

    // ---------- A: quest list ----------

    public void openList(Player p, int page) {
        Database db = plugin.database();
        db.playerCounts().thenAccept(counts -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            List<Quest> all = editor.editableQuests(p);
            int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
            int pg = Math.max(0, Math.min(page, pages - 1));
            Menu menu = new Menu(6, m().get("menu-list-title"));
            for (int i = 0; i < PAGE && pg * PAGE + i < all.size(); i++) {
                Quest q = all.get(pg * PAGE + i);
                int[] c = counts.getOrDefault(q.id(), new int[2]);
                menu.set(i, Menu.icon(q.published() ? Material.WRITTEN_BOOK : Material.BOOK, q.title(),
                        t("gui-quest-id", "id", q.id()),
                        t("gui-quest-meta", "status", t(q.published() ? "gui-status-published" : "gui-status-draft"), "steps", String.valueOf(q.steps().size())),
                        t("gui-quest-counts", "active", String.valueOf(c[0]), "done", String.valueOf(c[1])), "", t("gui-click-edit")),
                        click -> openQuest(p, q.id(), 0));
            }
            if (pg > 0) menu.set(45, Menu.icon(Material.ARROW, t("gui-prev-page")), click -> openList(p, pg - 1));
            menu.set(49, Menu.icon(Material.LIME_DYE, t("gui-new-quest"), t("gui-new-quest-lore")), click -> newQuest(p));
            if (pg < pages - 1) menu.set(51, Menu.icon(Material.ARROW, t("gui-next-page")), click -> openList(p, pg + 1));
            menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), click -> p.closeInventory());
            menu.open(p);
        }));
    }

    private void newQuest(Player p) {
        plugin.dialogs().textInput(p, plain("input-title-id"), "", false, id -> {
            plugin.dialogs().textInput(p, plain("input-title-quest-title"), "", false, title -> {
                Quest q = editor.create(p, id.strip(), title.isBlank() ? id : title);
                if (q != null) openQuest(p, q.id(), 0);
                else openList(p, 0);
            }, () -> openList(p, 0));
        }, () -> openList(p, 0));
    }

    /** A message as plain text, for dialog titles. */
    private String plain(String key, String... pairs) {
        return PlainTextComponentSerializer.plainText().serialize(m().get(key, pairs));
    }

    // ---------- B: quest menu ----------

    public void openQuest(Player p, String id, int page) {
        Quest q = editor.editable(p, id);
        if (q == null) return;
        EditorSession s = editor.session(p);
        s.questId = id;
        s.step = -1;
        editor.opened(p, id);
        Menu menu = new Menu(6, m().get("menu-quest-title", "title", q.title()));
        List<String> nameLore = new ArrayList<>();
        for (String l : wrap(Validation.plain(q.summary()), 40, 6)) nameLore.add("<gray>" + esc(l));
        nameLore.add("");
        nameLore.add(t("gui-edit-title"));
        nameLore.add(t("gui-edit-summary"));
        menu.set(0, Menu.icon(Material.NAME_TAG, q.title(), nameLore), c -> {
            if (c.right()) editSummary(p, q, () -> openQuest(p, id, page));
            else editTitle(p, q, () -> openQuest(p, id, page));
        });
        menu.set(1, Menu.icon(q.published() ? Material.LIME_CONCRETE : Material.ORANGE_CONCRETE,
                t(q.published() ? "gui-published" : "gui-draft"), t("gui-publish-hint")), null);
        menu.set(2, Menu.icon(q.repeatable() ? Material.CLOCK : Material.GRAY_DYE,
                t(q.repeatable() ? "gui-repeatable" : "gui-not-repeatable"),
                t("gui-cooldown", "seconds", String.valueOf(q.repeatCooldownSeconds())), "", t("gui-toggle"), t("gui-set-cooldown")), c -> {
            if (c.right()) {
                plugin.dialogs().textInput(p, plain("input-title-cooldown"), String.valueOf(q.repeatCooldownSeconds()), false, text -> {
                    editor.setRepeatable(p, id, true, parseInt(text, 0));
                    openQuest(p, id, page);
                }, () -> openQuest(p, id, page));
            } else {
                editor.setRepeatable(p, id, !q.repeatable(), q.repeatCooldownSeconds());
                openQuest(p, id, page);
            }
        });
        List<String> reqLore = new ArrayList<>();
        if (q.requires().isEmpty()) reqLore.add(t("gui-nothing"));
        for (String r : q.requires()) {
            String e = q.requireEndings().get(r);
            reqLore.add(e == null ? t("gui-requires-entry", "id", r) : t("gui-requires-entry-ending", "id", r, "ending", esc(Validation.plain(e))));
        }
        menu.set(3, Menu.icon(Material.LEAD, t("gui-requires"), reqLore), c -> openRequires(p, id));
        menu.set(5, Menu.icon(q.guide().particles() || q.guide().compass() ? Material.RECOVERY_COMPASS : Material.GRAY_DYE, t("gui-guide"),
                t("gui-guide-lore"), "",
                t("gui-guide-particles", "state", t(q.guide().particles() ? "gui-on" : "gui-off")),
                t("gui-guide-compass", "state", t(q.guide().compass() ? "gui-on" : "gui-off")), "",
                t("gui-guide-left"), t("gui-guide-right")), c -> {
            if (c.right()) editor.setGuide(p, id, null, !q.guide().compass());
            else editor.setGuide(p, id, !q.guide().particles(), null);
            openQuest(p, id, page);
        });
        menu.set(7, Menu.icon(Material.FILLED_MAP, t("gui-map"), t("gui-map-lore")), c -> openMap(p, id, 0));

        int pages = Math.max(1, (q.steps().size() + 26) / 27);
        int pg = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < 27 && pg * 27 + i < q.steps().size(); i++) {
            int idx = pg * 27 + i;
            Step st = q.steps().get(idx);
            String first = st.text().isEmpty() ? "" : Validation.plain(st.text().get(0));
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + esc(first));
            lore.add("<dark_gray>" + esc(summary(st.trigger())));
            if (st.branches() || idx == q.steps().size() - 1) lore.addAll(routeLore(q, idx));
            lore.add("");
            lore.add(t("gui-click-edit-short"));
            lore.add(t("gui-shift-move"));
            menu.set(9 + i, Menu.icon(triggerIcon(st.trigger()), t("gui-step-name", "n", String.valueOf(idx + 1), "type", st.trigger().type()), lore), c -> {
                if (c.shift()) {
                    int to = c.right() ? idx + 1 : idx - 1;
                    if (to >= 0 && to < q.steps().size()) editor.moveStep(p, id, idx, to);
                    openQuest(p, id, pg);
                } else {
                    openStep(p, id, idx);
                }
            });
        }
        if (pg > 0) menu.set(36, Menu.icon(Material.ARROW, t("gui-prev-steps")), c -> openQuest(p, id, pg - 1));
        menu.set(40, Menu.icon(Material.LIME_DYE, t("gui-add-step")), c -> openPicker(p, id, -1, -1, pg));
        if (pg < pages - 1) menu.set(44, Menu.icon(Material.ARROW, t("gui-next-steps")), c -> openQuest(p, id, pg + 1));

        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openList(p, 0));
        menu.set(46, Menu.icon(Material.OAK_SIGN, t("gui-start-sign"),
                q.startSign() == null ? t("gui-sign-none") : "<gray>" + esc(q.startSign().toString()), "", t("gui-sign-look")), c -> {
            p.closeInventory();
            editor.linkStartSign(p, id);
            Bukkit.getScheduler().runTask(plugin, () -> openQuest(p, id, pg));
        });
        menu.set(47, Menu.icon(s.preview ? Material.ENDER_EYE : Material.ENDER_PEARL,
                t("gui-preview", "state", t(s.preview ? "gui-on" : "gui-off"))), c -> {
            s.preview = !s.preview;
            openQuest(p, id, pg);
        });
        menu.set(48, Menu.icon(Material.COMPASS, t("gui-test")), c -> {
            p.closeInventory();
            plugin.quests().startTest(p, q);
        });
        menu.set(49, Menu.icon(Material.PAPER, t("gui-stats")), c -> {
            p.closeInventory();
            plugin.stats(p, id);
        });
        menu.set(50, Menu.icon(Material.SPYGLASS, t("gui-validate")), c -> {
            p.closeInventory();
            editor.validate(p, id);
        });
        menu.set(51, q.published() ? Menu.icon(Material.RED_DYE, t("gui-unpublish")) : Menu.icon(Material.EMERALD, t("gui-publish")), c -> {
            if (q.published()) editor.unpublish(p, id);
            else {
                p.closeInventory();
                editor.publish(p, id);
                Bukkit.getScheduler().runTask(plugin, () -> openQuest(p, id, pg));
                return;
            }
            openQuest(p, id, pg);
        });
        menu.set(52, Menu.icon(Material.TNT, t("gui-delete"), t("gui-delete-lore")), c -> confirm(p, t("gui-confirm-delete-quest", "id", id), () -> {
            editor.delete(p, id);
            openList(p, 0);
        }, () -> openQuest(p, id, pg)));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    /** {@code question} is a MiniMessage string (from messages.yml). */
    private void confirm(Player p, String question, Runnable yes, Runnable no) {
        Menu menu = new Menu(3, Messages.mm(question));
        menu.set(11, Menu.icon(Material.LIME_CONCRETE, t("gui-yes")), c -> yes.run());
        menu.set(15, Menu.icon(Material.RED_CONCRETE, t("gui-no")), c -> no.run());
        menu.open(p);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void editTitle(Player p, Quest q, Runnable back) {
        plugin.dialogs().textInput(p, plain("input-title-title"), q.title(), false, text -> {
            if (!text.isBlank()) editor.setTitle(p, q.id(), text);
            back.run();
        }, back);
    }

    /** The summary keeps its line breaks (since 1.2). */
    private void editSummary(Player p, Quest q, Runnable back) {
        plugin.dialogs().textInput(p, plain("input-title-summary", "max", String.valueOf(Validation.limits().summary())), q.summary(), true, text -> {
            editor.setSummary(p, q.id(), text.strip());
            back.run();
        }, back);
    }

    static Material triggerIcon(Trigger t) {
        return switch (t) {
            case Trigger.Continue c -> Material.PAPER;
            case Trigger.Reach r -> Material.COMPASS;
            case Trigger.Region r -> Material.MAP;
            case Trigger.SignClick s -> Material.OAK_SIGN;
            case Trigger.Interact i -> Material.BELL;
            case Trigger.Answer a -> Material.NAME_TAG;
            case Trigger.Dimension d -> Material.OBSIDIAN;
            case Trigger.AdvancementDone a -> Material.KNOWLEDGE_BOOK;
            case Trigger.Kill k -> Material.IRON_SWORD;
            case Trigger.HaveItem h -> Material.BUNDLE;
            case Trigger.Place pl -> Material.SCAFFOLDING;
            case Trigger.Choice c -> Material.OAK_BUTTON;
            case Trigger.FirstOf f -> Material.LIGHTNING_ROD;
        };
    }

    /** One plain-text line describing a trigger (the caller escapes it). */
    String summary(Trigger tr) {
        return switch (tr) {
            case Trigger.Continue c -> plain("gui-sum-continue");
            case Trigger.Reach r -> plain("gui-sum-reach", "r", String.valueOf(r.radius()),
                    "pos", String.format("%.1f %.1f %.1f", r.x(), r.y(), r.z()), "world", r.world());
            case Trigger.Region r -> plain("gui-sum-region", "w", String.valueOf(Math.abs(r.x2() - r.x1()) + 1),
                    "h", String.valueOf(r.height()), "d", String.valueOf(Math.abs(r.z2() - r.z1()) + 1),
                    "y", String.valueOf(r.baseY()), "world", r.world());
            case Trigger.SignClick s -> s.sign() == null ? plain("gui-sum-sign-none") : plain("gui-sum-sign", "ref", s.sign().toString());
            case Trigger.Interact i -> i.block() == null ? plain("gui-sum-block-none") : plain("gui-sum-block", "ref", i.block().toString());
            case Trigger.Answer a -> plain("gui-sum-answers", "answers", String.join(" | ", a.answers()));
            case Trigger.Dimension d -> plain("gui-sum-dimension", "env", d.env().name());
            case Trigger.AdvancementDone a -> plain("gui-sum-advancement", "key", a.key().asString());
            case Trigger.Kill k -> plain("gui-sum-kill", "amount", String.valueOf(k.amount()), "target", k.target());
            case Trigger.HaveItem h -> plain("gui-sum-have-item", "amount", String.valueOf(h.amount()), "item", h.item());
            case Trigger.Place pl -> pl.area() == null ? plain("gui-sum-place", "amount", String.valueOf(pl.amount()), "block", pl.block())
                    : plain("gui-sum-place-area", "amount", String.valueOf(pl.amount()), "block", pl.block(),
                    "w", String.valueOf(Math.abs(pl.area().x2() - pl.area().x1()) + 1), "h", String.valueOf(pl.area().height()),
                    "d", String.valueOf(Math.abs(pl.area().z2() - pl.area().z1()) + 1));
            case Trigger.Choice c -> plain("gui-sum-choice", "options", String.join(" | ", c.options().stream().map(o -> Validation.plain(o.label())).toList()));
            case Trigger.FirstOf f -> plain("gui-sum-first-of", "options", String.join(" | ", f.options().stream()
                    .map(o -> Validation.plain(o.label()) + " (" + o.trigger().type() + ")").toList()));
        };
    }

    // ---------- C: step menu ----------

    /**
     * Three labelled rows: what the player reads (text, after-text, hint, other texts), what the player does (the
     * trigger and its settings), and where they go next (Then…). Move and delete sit top right.
     */
    public void openStep(Player p, String id, int idx) {
        Quest q = editor.editable(p, id);
        if (q == null || idx < 0 || idx >= q.steps().size()) {
            openQuest(p, id, 0);
            return;
        }
        EditorSession s = editor.session(p);
        s.questId = id;
        s.step = idx;
        Step st = q.steps().get(idx);
        Menu menu = new Menu(6, m().get("menu-step-title", "n", String.valueOf(idx + 1), "type", st.trigger().type()));
        Runnable again = () -> openStep(p, id, idx);

        menu.set(0, Menu.icon(triggerIcon(st.trigger()), t("gui-step-name", "n", String.valueOf(idx + 1), "type", st.trigger().type()),
                t("gui-step-of", "n", String.valueOf(idx + 1), "total", String.valueOf(q.steps().size()))), null);
        menu.set(6, Menu.icon(Material.SPECTRAL_ARROW, t("gui-move-left")), c -> {
            if (idx > 0) {
                editor.moveStep(p, id, idx, idx - 1);
                openStep(p, id, idx - 1);
            }
        });
        menu.set(7, Menu.icon(Material.TIPPED_ARROW, t("gui-move-right")), c -> {
            if (idx < q.steps().size() - 1) {
                editor.moveStep(p, id, idx, idx + 1);
                openStep(p, id, idx + 1);
            }
        });
        menu.set(8, Menu.icon(Material.TNT, t("gui-delete-step")), c -> confirm(p, t("gui-confirm-delete-step", "n", String.valueOf(idx + 1)), () -> {
            editor.removeStep(p, id, idx);
            openQuest(p, id, 0);
        }, again));

        // row 2: what the player reads
        menu.set(9, label("gui-row-reads"), null);
        List<String> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(5, st.text().size()); i++) preview.add("<gray>" + esc(Validation.plain(st.text().get(i))));
        preview.add("");
        preview.add(t("gui-text-lore"));
        preview.add(t("gui-click-edit"));
        menu.set(10, Menu.icon(Material.WRITABLE_BOOK, t("gui-text"), preview),
                c -> plugin.dialogs().textInput(p, plain("input-title-text"), String.join("\n", st.text()), true, text -> {
                    editor.setText(p, id, idx, text);
                    again.run();
                }, again));
        List<String> afterLore = new ArrayList<>();
        for (int i = 0; i < Math.min(5, st.after().size()); i++) afterLore.add("<gray>" + esc(Validation.plain(st.after().get(i))));
        if (afterLore.isEmpty()) afterLore.add("<gray>" + t("gui-none"));
        afterLore.add("");
        afterLore.add(t("gui-after-lore"));
        afterLore.add(t("gui-click-edit-short"));
        afterLore.add(t("gui-clear"));
        menu.set(11, Menu.icon(Material.BOOK, t("gui-after"), afterLore), c -> {
            if (c.right()) {
                editor.setAfter(p, id, idx, "");
                again.run();
            } else {
                plugin.dialogs().textInput(p, plain("input-title-after"), String.join("\n", st.after()), true, text -> {
                    editor.setAfter(p, id, idx, text);
                    again.run();
                }, again);
            }
        });
        menu.set(12, Menu.icon(Material.LANTERN, t("gui-hint"), "<gray>" + (st.hint() == null ? t("gui-none") : esc(Validation.plain(st.hint()))), "",
                t("gui-click-edit-short"), t("gui-clear")), c -> {
            if (c.right()) {
                editor.setHint(p, id, idx, null);
                again.run();
            } else {
                plugin.dialogs().textInput(p, plain("input-title-hint"), st.hint() == null ? "" : st.hint(), false, text -> {
                    editor.setHint(p, id, idx, text);
                    again.run();
                }, again);
            }
        });
        List<String> varLore = new ArrayList<>();
        Describe d = d();
        for (TextVariant v : st.textIf()) varLore.add(colour(Describe.colour(q, v.cond())) + t("gui-variant-entry", "cond", esc(d.cond(q, v.cond()))));
        if (varLore.isEmpty()) varLore.add("<gray>" + t("gui-none"));
        varLore.add("");
        varLore.add(t("gui-variants-lore"));
        varLore.add(t("gui-click-edit-short"));
        menu.set(13, Menu.icon(Material.ENCHANTED_BOOK, t("gui-variants", "count", String.valueOf(st.textIf().size())), varLore),
                c -> openVariants(p, id, idx));

        // rows 3-4: what the player does
        menu.set(18, Menu.icon(triggerIcon(st.trigger()), t("gui-trigger", "type", st.trigger().type()),
                "<dark_gray>" + esc(summary(st.trigger())), "", t("gui-trigger-lore")), c -> openPicker(p, id, idx, -1, 0));
        triggerRow(menu, p, id, idx, -1, st.trigger(), again);

        // row 5: where the player goes next
        menu.set(36, label("gui-row-then"), null);
        List<String> thenLore = routeLore(q, idx);
        thenLore.add("");
        thenLore.add(t("gui-then-lore"));
        thenLore.add(t("gui-click-edit-short"));
        menu.set(37, Menu.icon(Material.HOPPER, t("gui-then"), thenLore), c -> openThen(p, id, idx));
        for (int r = 0; r < st.rules().size() && r < 4; r++) {
            Rule rule = st.rules().get(r);
            int col = Describe.colour(q, rule.cond());
            menu.set(38 + r, Menu.icon(PANE[col], colour(col) + esc(d.cond(q, rule.cond())), t("gui-goes-to", "target", esc(d.target(q, idx, rule.target())))),
                    c -> openThen(p, id, idx));
        }
        menu.set(43, Menu.icon(targetIcon(q, idx, st.next()), t(st.rules().isEmpty() ? "gui-then-short" : "gui-otherwise-short"),
                "<gray>" + esc(d.target(q, idx, st.next()))), c -> openThen(p, id, idx));

        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openQuest(p, id, idx / 27));
        menu.set(47, Menu.icon(Material.COMPASS, t("gui-test")), c -> {
            p.closeInventory();
            Quest tq = editor.editable(p, id);
            if (tq != null) plugin.quests().startTest(p, tq);
        });
        menu.set(49, Menu.icon(Material.FILLED_MAP, t("gui-map")), c -> openMap(p, id, Math.max(0, idx - 1)));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    /**
     * The settings of a trigger, in slots 19..35. {@code opt} &gt;= 0 edits a WORLD CHOICE option's trigger.
     * Layout: 19..26 = controls, 27..35 = lists (answers, buttons, options).
     */
    private void triggerRow(Menu menu, Player p, String id, int idx, int opt, Trigger trigger, Runnable again) {
        switch (trigger) {
            case Trigger.Reach r -> {
                menu.set(20, Menu.icon(Material.ENDER_PEARL, t("gui-use-position"), t("gui-use-position-lore")), c -> {
                    editor.stepHere(p, id, idx, opt, null);
                    again.run();
                });
                menu.set(22, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-radius-minus"), t("gui-shift-5")), c -> {
                    editor.setRadius(p, id, idx, opt, r.radius() - c.amount());
                    again.run();
                });
                menu.set(23, Menu.icon(Material.SLIME_BALL, t("gui-radius-value", "value", String.valueOf(r.radius()))), null);
                menu.set(24, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-radius-plus"), t("gui-shift-5")), c -> {
                    editor.setRadius(p, id, idx, opt, r.radius() + c.amount());
                    again.run();
                });
                menu.set(26, Menu.icon(Material.MAP, "<gray>" + String.format("%.1f %.1f %.1f", r.x(), r.y(), r.z()), "<dark_gray>" + esc(r.world())), null);
            }
            case Trigger.Region r -> regionRow(menu, p, id, idx, opt, r, again, 20);
            case Trigger.SignClick tr -> pickRow(menu, p, id, idx, opt, tr.sign() == null ? null : tr.sign().toString());
            case Trigger.Interact tr -> pickRow(menu, p, id, idx, opt, tr.block() == null ? null : tr.block().toString());
            case Trigger.Answer a -> {
                menu.set(20, Menu.icon(Material.LIME_DYE, t("gui-add-answer")),
                        c -> plugin.dialogs().textInput(p, plain("input-title-answer"), "", false, text -> {
                            editor.addAnswer(p, id, idx, text);
                            again.run();
                        }, again));
                int[] slots = {21, 22, 23, 24, 25, 26, 27, 28, 29, 30};
                for (int i = 0; i < Math.min(10, a.answers().size()); i++) {
                    int ai = i;
                    menu.set(slots[i], Menu.icon(Material.NAME_TAG, "<white>" + esc(a.answers().get(i)), t("gui-answer-remove")), c -> {
                        editor.removeAnswer(p, id, idx, ai);
                        again.run();
                    });
                }
            }
            case Trigger.Dimension dim -> menu.set(20, Menu.icon(Material.OBSIDIAN, t("gui-dimension", "env", dim.env().name()), t("gui-cycle")), c -> {
                World.Environment next = switch (dim.env()) {
                    case NORMAL -> World.Environment.NETHER;
                    case NETHER -> World.Environment.THE_END;
                    default -> World.Environment.NORMAL;
                };
                editor.setDimension(p, id, idx, opt, next);
                again.run();
            });
            case Trigger.AdvancementDone a -> {
                Advancement adv = Bukkit.getAdvancement(a.key());
                String title = adv == null ? t("gui-adv-unknown") : "<gray>" + esc(adv.getDisplay() == null ? a.key().asString()
                        : PlainTextComponentSerializer.plainText().serialize(adv.getDisplay().title()));
                menu.set(20, Menu.icon(Material.KNOWLEDGE_BOOK, t("gui-set-key"), "<gray>" + esc(a.key().asString()), title),
                        c -> plugin.dialogs().textInput(p, plain("input-title-advancement"), a.key().asString(), false, text -> {
                            editor.setAdvancement(p, id, idx, opt, text);
                            again.run();
                        }, again));
            }
            case Trigger.Kill k -> {
                menu.set(20, glow(Menu.icon(entityIcon(k.target()), t("gui-kill-target", "target", pretty(k.target())), t("gui-kill-choose"))),
                        c -> openKillTargets(p, id, idx, opt, 0));
                menu.set(22, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-kill-minus"), t("gui-shift-5")), c -> {
                    editor.setKill(p, id, idx, opt, null, k.amount() - c.amount());
                    again.run();
                });
                menu.set(23, Menu.icon(Material.SLIME_BALL, t("gui-kill-value", "value", String.valueOf(k.amount()))), null);
                menu.set(24, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-kill-plus"), t("gui-shift-5")), c -> {
                    editor.setKill(p, id, idx, opt, null, k.amount() + c.amount());
                    again.run();
                });
            }
            case Trigger.HaveItem h -> {
                menu.set(20, glow(Menu.icon(itemIcon(h.item()), t("gui-item", "item", pretty(h.item().toUpperCase())), t("gui-item-from-hand"))), c -> {
                    String held = editor.heldItem(p);
                    if (held != null) editor.setHaveItem(p, id, idx, opt, held, null);
                    again.run();
                });
                menu.set(22, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-amount-minus"), t("gui-shift-5")), c -> {
                    editor.setHaveItem(p, id, idx, opt, null, h.amount() - c.amount());
                    again.run();
                });
                menu.set(23, Menu.icon(Material.SLIME_BALL, t("gui-amount-value", "value", String.valueOf(h.amount()))), null);
                menu.set(24, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-amount-plus"), t("gui-shift-5")), c -> {
                    editor.setHaveItem(p, id, idx, opt, null, h.amount() + c.amount());
                    again.run();
                });
            }
            case Trigger.Place pl -> {
                menu.set(20, glow(Menu.icon(itemIcon(pl.block()), t("gui-place-block", "block", pretty(pl.block().toUpperCase())), t("gui-item-from-hand"))), c -> {
                    String held = editor.heldItem(p);
                    if (held != null) editor.setPlace(p, id, idx, opt, held, null);
                    again.run();
                });
                menu.set(22, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-amount-minus"), t("gui-shift-5")), c -> {
                    editor.setPlace(p, id, idx, opt, null, pl.amount() - c.amount());
                    again.run();
                });
                menu.set(23, Menu.icon(Material.SLIME_BALL, t("gui-amount-value", "value", String.valueOf(pl.amount()))), null);
                menu.set(24, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-amount-plus"), t("gui-shift-5")), c -> {
                    editor.setPlace(p, id, idx, opt, null, pl.amount() + c.amount());
                    again.run();
                });
                // where: anywhere, or an area set up exactly like a REGION trigger (row below)
                boolean area = pl.area() != null;
                menu.set(26, area ? glow(Menu.icon(Material.MAP, t("gui-place-in-area"), t("gui-place-in-area-lore"), "", t("gui-place-to-anywhere")))
                        : Menu.icon(Material.FILLED_MAP, t("gui-place-anywhere"), t("gui-place-anywhere-lore"), "", t("gui-place-to-area")), c -> {
                    editor.setPlaceArea(p, id, idx, opt, !area);
                    again.run();
                });
                if (area) regionRow(menu, p, id, idx, opt, pl.area(), again, 29);
            }
            case Trigger.Choice ch -> {
                if (ch.options().size() < 4) {
                    menu.set(20, Menu.icon(Material.LIME_DYE, t("gui-add-button"), t("gui-add-button-lore")),
                            c -> plugin.dialogs().textInput(p, plain("input-title-button"), "", false, text -> {
                                editor.addOption(p, id, idx, text);
                                again.run();
                            }, again));
                }
                for (int i = 0; i < ch.options().size() && i < 4; i++) {
                    int oi = i;
                    Trigger.Option o = ch.options().get(i);
                    menu.set(28 + 2 * i, Menu.icon(WOOL[i], colour(i) + "[" + o.label() + "]", t("gui-button-lore"), "",
                            t("gui-click-rename"), t("gui-shift-right-remove")), c -> {
                        if (c.shift() && c.right()) {
                            editor.removeOption(p, id, idx, oi);
                            again.run();
                        } else {
                            plugin.dialogs().textInput(p, plain("input-title-button"), o.label(), false, text -> {
                                editor.renameOption(p, id, idx, oi, text);
                                again.run();
                            }, again);
                        }
                    });
                }
            }
            case Trigger.FirstOf f -> {
                if (f.options().size() < 3) {
                    menu.set(20, Menu.icon(Material.LIME_DYE, t("gui-add-world-option"), t("gui-add-world-option-lore")),
                            c -> plugin.dialogs().textInput(p, plain("input-title-world-option"), "", false, text -> {
                                editor.addOption(p, id, idx, text);
                                again.run();
                            }, again));
                }
                for (int i = 0; i < f.options().size() && i < 3; i++) {
                    int oi = i;
                    Trigger.Branch b = f.options().get(i);
                    menu.set(29 + 2 * i, Menu.icon(triggerIcon(b.trigger()), colour(i) + esc(Validation.plain(b.label())),
                            "<gray>" + b.trigger().type() + " · " + esc(summary(b.trigger())), "", t("gui-click-edit-short"), t("gui-shift-right-remove")), c -> {
                        if (c.shift() && c.right()) {
                            editor.removeOption(p, id, idx, oi);
                            again.run();
                        } else {
                            openOption(p, id, idx, oi);
                        }
                    });
                }
            }
            case Trigger.Continue c -> { }
        }
    }

    /** The item itself as the icon, a barrier for an unknown name. */
    static Material itemIcon(String item) {
        Material m = item == null ? null : Material.matchMaterial(item);
        return m != null && m.isItem() && !m.isAir() ? m : Material.BARRIER;
    }

    /**
     * Corner 1, corner 2, height − / value / +, size: the box of a REGION trigger or the area of a PLACE trigger. The
     * buttons sit at {@code at}..{@code at}+4, the size at {@code at}+6.
     */
    private void regionRow(Menu menu, Player p, String id, int idx, int opt, Trigger.Region r, Runnable again, int at) {
        menu.set(at, Menu.icon(Material.LIME_BANNER, t("gui-pos1"), t("gui-pos1-lore")), c -> {
            editor.setCorner(p, id, idx, opt, true);
            again.run();
        });
        menu.set(at + 1, Menu.icon(Material.RED_BANNER, t("gui-pos2"), t("gui-pos2-lore")), c -> {
            editor.setCorner(p, id, idx, opt, false);
            again.run();
        });
        menu.set(at + 2, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-height-minus"), t("gui-shift-5")), c -> {
            editor.setHeight(p, id, idx, opt, r.height() - c.amount());
            again.run();
        });
        menu.set(at + 3, Menu.icon(Material.SLIME_BALL, t("gui-height-value", "value", String.valueOf(r.height()))), null);
        menu.set(at + 4, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-height-plus"), t("gui-shift-5")), c -> {
            editor.setHeight(p, id, idx, opt, r.height() + c.amount());
            again.run();
        });
        menu.set(at + 6, Menu.icon(Material.MAP,
                t("gui-size", "w", String.valueOf(Math.abs(r.x2() - r.x1()) + 1), "h", String.valueOf(r.height()), "d", String.valueOf(Math.abs(r.z2() - r.z1()) + 1)),
                t("gui-from-y", "y", String.valueOf(r.baseY())), "<dark_gray>" + esc(r.world())), null);
    }

    private void pickRow(Menu menu, Player p, String id, int idx, int opt, String target) {
        menu.set(20, Menu.icon(Material.SPYGLASS, t("gui-pick"), t("gui-pick-lore")), c -> editor.startPick(p, id, idx, opt));
        menu.set(22, Menu.icon(Material.MAP, target == null ? t("gui-nothing-picked") : "<gray>" + esc(target)), null);
    }

    // ---------- WORLD CHOICE option ----------

    /** One option of a WORLD CHOICE step: its name and its own trigger, edited like a step's trigger. */
    public void openOption(Player p, String id, int idx, int opt) {
        Quest q = editor.editable(p, id);
        if (q == null || idx < 0 || idx >= q.steps().size() || !(q.steps().get(idx).trigger() instanceof Trigger.FirstOf f)
                || opt < 0 || opt >= f.options().size()) {
            openStep(p, id, Math.max(0, idx));
            return;
        }
        Trigger.Branch b = f.options().get(opt);
        Menu menu = new Menu(6, m().get("menu-option-title", "n", String.valueOf(idx + 1), "label", esc(Validation.plain(b.label()))));
        Runnable again = () -> openOption(p, id, idx, opt);
        menu.set(4, Menu.icon(WOOL[opt], colour(opt) + esc(Validation.plain(b.label())), t("gui-world-option-lore"), "", t("gui-click-rename")),
                c -> plugin.dialogs().textInput(p, plain("input-title-world-option"), b.label(), false, text -> {
                    editor.renameOption(p, id, idx, opt, text);
                    again.run();
                }, again));
        menu.set(18, Menu.icon(triggerIcon(b.trigger()), t("gui-trigger", "type", b.trigger().type()),
                "<dark_gray>" + esc(summary(b.trigger())), "", t("gui-trigger-lore")), c -> openPicker(p, id, idx, opt, 0));
        triggerRow(menu, p, id, idx, opt, b.trigger(), again);
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openStep(p, id, idx));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- KILL target picker ----------

    /** Heads where vanilla has one, otherwise the spawn egg, otherwise a name tag. */
    static Material entityIcon(String target) {
        return switch (target) {
            case "ANY" -> Material.COMPASS;
            case "ANIMAL" -> Material.WHEAT;
            case "MONSTER" -> Material.IRON_SWORD;
            case "PLAYER" -> Material.PLAYER_HEAD;
            case "ZOMBIE" -> Material.ZOMBIE_HEAD;
            case "SKELETON" -> Material.SKELETON_SKULL;
            case "WITHER_SKELETON" -> Material.WITHER_SKELETON_SKULL;
            case "CREEPER" -> Material.CREEPER_HEAD;
            case "PIGLIN" -> Material.PIGLIN_HEAD;
            case "ENDER_DRAGON" -> Material.DRAGON_HEAD;
            default -> {
                Material egg = Material.matchMaterial(target + "_SPAWN_EGG");
                yield egg != null ? egg : Material.NAME_TAG;
            }
        };
    }

    /** ZOMBIFIED_PIGLIN becomes "Zombified Piglin". */
    static String pretty(String target) {
        StringBuilder b = new StringBuilder();
        for (String w : target.toLowerCase().split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private static ItemStack glow(ItemStack it) {
        var meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    /** The four groups first, then every living entity type in alphabetical order. */
    private static List<String> killTargets() {
        List<String> all = new ArrayList<>(Trigger.Kill.CATEGORIES);
        java.util.Arrays.stream(org.bukkit.entity.EntityType.values())
                .filter(org.bukkit.entity.EntityType::isAlive)
                .filter(t -> t != org.bukkit.entity.EntityType.PLAYER && t != org.bukkit.entity.EntityType.ARMOR_STAND
                        && t != org.bukkit.entity.EntityType.UNKNOWN)
                .map(Enum::name).sorted().forEach(all::add);
        return all;
    }

    public void openKillTargets(Player p, String id, int step, int opt, int page) {
        Quest q = editor.editable(p, id);
        if (q == null || step < 0 || step >= q.steps().size()) return;
        Runnable back = () -> {
            if (opt >= 0) openOption(p, id, step, opt);
            else openStep(p, id, step);
        };
        String current = EditorService.triggerAt(q.steps().get(step), opt) instanceof Trigger.Kill k ? k.target() : "";
        List<String> all = killTargets();
        int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, m().get("menu-kill-title", "page", String.valueOf(pg + 1), "pages", String.valueOf(pages)));
        for (int i = 0; i < PAGE && pg * PAGE + i < all.size(); i++) {
            String target = all.get(pg * PAGE + i);
            boolean group = Trigger.Kill.CATEGORIES.contains(target);
            var icon = Menu.icon(entityIcon(target), (group ? "<yellow>" : "<white>") + pretty(target),
                    group ? t("gui-kill-group-" + target.toLowerCase()) : "<dark_gray>" + target, "",
                    target.equals(current) ? t("gui-kill-selected") : t("gui-kill-pick"));
            menu.set(i, target.equals(current) ? glow(icon) : icon, c -> {
                editor.setKill(p, id, step, opt, target, null);
                back.run();
            });
        }
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> back.run());
        if (pg > 0) menu.set(48, Menu.icon(Material.ARROW, t("gui-prev-page")), c -> openKillTargets(p, id, step, opt, pg - 1));
        if (pg < pages - 1) menu.set(50, Menu.icon(Material.ARROW, t("gui-next-page")), c -> openKillTargets(p, id, step, opt, pg + 1));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- D: trigger picker ----------

    /**
     * step &lt; 0 adds a new step at the end, otherwise changes the trigger of that step ({@code opt} &gt;= 0: of that
     * WORLD CHOICE option, which offers only the world triggers).
     */
    public void openPicker(Player p, String id, int step, int opt, int returnPage) {
        Menu menu = new Menu(3, m().get("menu-picker-title"));
        List<String> types = opt >= 0 ? Trigger.WORLD_TYPES : Trigger.TYPES;
        int slot = 0;
        for (String type : types) {
            menu.set(slot++, Menu.icon(triggerIcon(Trigger.blank(type)), "<white>" + t("gui-type-" + type.toLowerCase()),
                    t("gui-desc-" + type.toLowerCase())), c -> {
                if (step < 0) {
                    int at = editor.addStep(p, id, type, null);
                    if (at >= 0) openStep(p, id, at);
                    else openQuest(p, id, returnPage);
                } else if (opt >= 0) {
                    editor.setTriggerType(p, id, step, opt, type);
                    openOption(p, id, step, opt);
                } else {
                    editor.setTriggerType(p, id, step, type);
                    openStep(p, id, step);
                }
            });
        }
        menu.set(18, Menu.icon(Material.ARROW, t("gui-back")), c -> {
            if (step < 0) openQuest(p, id, returnPage);
            else if (opt >= 0) openOption(p, id, step, opt);
            else openStep(p, id, step);
        });
        menu.set(26, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- E: requirements ----------

    /** Left click: required or not. Right click on a required quest: which ending it must have been finished with. */
    public void openRequires(Player p, String id) {
        Quest q = editor.editable(p, id);
        if (q == null) return;
        Menu menu = new Menu(6, m().get("menu-requires-title"));
        int i = 0;
        for (Quest o : editor.editableQuests(p)) {
            if (o.id().equals(id) || i >= PAGE) continue;
            boolean req = q.requires().contains(o.id());
            String ending = q.requireEndings().get(o.id());
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>" + o.id());
            if (req) lore.add(ending == null ? t("gui-requires-any-ending") : t("gui-requires-ending", "ending", esc(Validation.plain(ending))));
            lore.add("");
            lore.add(t("gui-click-toggle"));
            if (!o.endings().isEmpty()) lore.add(t("gui-requires-right"));
            menu.set(i++, Menu.icon(req ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, (req ? "<green>" : "<gray>") + esc(Validation.plain(o.title())),
                    lore), c -> {
                if (c.right() && !o.endings().isEmpty()) {
                    openRequireEnding(p, id, o.id());
                    return;
                }
                editor.toggleRequire(p, id, o.id(), null);
                openRequires(p, id);
            });
        }
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openQuest(p, id, 0));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    private void openRequireEnding(Player p, String id, String other) {
        Quest o = editor.editable(p, other);
        Quest q = editor.editable(p, id);
        if (o == null || q == null) return;
        Menu menu = new Menu(3, m().get("menu-require-ending-title", "title", Validation.plain(o.title())));
        String current = q.requireEndings().get(other);
        menu.set(0, Menu.icon(Material.GRAY_DYE, t("gui-requires-any-ending"), current == null ? t("gui-kill-selected") : ""), c -> {
            editor.setRequireEnding(p, id, other, null);
            openRequires(p, id);
        });
        int slot = 1;
        for (String e : o.endings()) {
            if (slot > 17) break;
            ItemStack icon = Menu.icon(Material.NETHER_STAR, "<gold>" + esc(Validation.plain(e)), e.equals(current) ? t("gui-kill-selected") : t("gui-kill-pick"));
            menu.set(slot++, e.equals(current) ? glow(icon) : icon, c -> {
                editor.setRequireEnding(p, id, other, e);
                openRequires(p, id);
            });
        }
        menu.set(18, Menu.icon(Material.ARROW, t("gui-back")), c -> openRequires(p, id));
        menu.set(26, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- Then…: where a step leads ----------

    /**
     * One row per condition: condition — line — target. Checked top to bottom, the first true one wins. The last row
     * is "Otherwise", which is the following step until the editor picks something else (or the end of the quest).
     */
    public void openThen(Player p, String id, int idx) {
        Quest q = editor.editable(p, id);
        if (q == null || idx < 0 || idx >= q.steps().size()) {
            openQuest(p, id, 0);
            return;
        }
        Step st = q.steps().get(idx);
        Describe d = d();
        Menu menu = new Menu(6, m().get("menu-then-title", "n", String.valueOf(idx + 1)));
        Runnable again = () -> openThen(p, id, idx);
        for (int r = 0; r < Validation.MAX_RULES; r++) {
            int base = r * 9;
            if (r >= st.rules().size()) {
                if (r == st.rules().size()) {
                    menu.set(base, Menu.icon(Material.LIME_DYE, t("gui-add-condition"), t("gui-add-condition-lore")),
                            c -> openCondPicker(p, id, idx, 0, cond -> openTargets(p, id, idx, false, 0,
                                    target -> {
                                        editor.addRule(p, id, idx, cond, target);
                                        again.run();
                                    }, again), again));
                }
                continue;
            }
            int ri = r;
            Rule rule = st.rules().get(r);
            int col = Describe.colour(q, rule.cond());
            String condText = esc(d.cond(q, rule.cond()));
            List<String> condLore = new ArrayList<>();
            condLore.add(t("gui-cond-lore"));
            condLore.add("");
            condLore.add(rule.cond() instanceof Cond.HasItem ? t("gui-cond-click-item") : t("gui-cond-click"));
            condLore.add(t("gui-cond-right"));
            menu.set(base, Menu.icon(condIcon(q, rule.cond()), colour(col) + t("gui-if", "cond", condText), condLore), c -> {
                if (c.right()) {
                    editor.setRule(p, id, idx, ri, Cond.negate(rule.cond()), null);
                    again.run();
                } else if (rule.cond() instanceof Cond.HasItem h) {
                    openItemCond(p, h, nc -> {
                        editor.setRule(p, id, idx, ri, nc, null);
                        again.run();
                    }, again);
                } else {
                    openCondPicker(p, id, idx, 0, nc -> {
                        editor.setRule(p, id, idx, ri, nc, null);
                        again.run();
                    }, again);
                }
            });
            for (int k = 1; k <= 5; k++) menu.set(base + k, Menu.icon(PANE[col], colour(col) + condText, t("gui-goes-to", "target", esc(d.target(q, idx, rule.target())))), null);
            menu.set(base + 6, Menu.icon(targetIcon(q, idx, rule.target()), t("gui-goto", "target", esc(d.target(q, idx, rule.target()))), t("gui-target-click")),
                    c -> openTargets(p, id, idx, false, 0, target -> {
                        editor.setRule(p, id, idx, ri, null, target);
                        again.run();
                    }, again));
            if (r > 0) menu.set(base + 7, Menu.icon(Material.ARROW, t("gui-rule-up"), t("gui-rule-up-lore")), c -> {
                editor.raiseRule(p, id, idx, ri);
                again.run();
            });
            menu.set(base + 8, Menu.icon(Material.BARRIER, t("gui-rule-remove"), t("gui-shift-confirm")), c -> {
                if (!c.shift()) return;
                editor.removeRule(p, id, idx, ri);
                again.run();
            });
        }
        // otherwise
        menu.set(36, Menu.icon(Material.GRAY_STAINED_GLASS_PANE, t(st.rules().isEmpty() ? "gui-then-short" : "gui-otherwise"), t("gui-otherwise-lore")), null);
        for (int k = 37; k <= 41; k++) menu.set(k, Menu.icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>" + esc(d.target(q, idx, st.next()))), null);
        List<String> nl = new ArrayList<>();
        nl.add(t("gui-target-click"));
        if (st.next() != null) nl.add(t("gui-target-reset"));
        menu.set(42, Menu.icon(targetIcon(q, idx, st.next()), t("gui-goto", "target", esc(d.target(q, idx, st.next()))), nl), c -> {
            if (c.right()) {
                editor.setNext(p, id, idx, null);
                again.run();
                return;
            }
            openTargets(p, id, idx, true, 0, target -> {
                editor.setNext(p, id, idx, target);
                again.run();
            }, again);
        });
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openStep(p, id, idx));
        menu.set(49, Menu.icon(Material.KNOWLEDGE_BOOK, t("gui-then-help"), t("gui-then-help-1"), t("gui-then-help-2"), t("gui-then-help-3")), null);
        menu.set(51, Menu.icon(Material.FILLED_MAP, t("gui-map")), c -> openMap(p, id, Math.max(0, idx - 1)));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- condition picker ----------

    private record Pickable(Material icon, String name, List<String> lore, Cond yes) {}

    /** Everything a condition can check in quest q: buttons, world choice options and answers of its steps. */
    private List<Pickable> pickables(Quest q, String questField) {
        List<Pickable> out = new ArrayList<>();
        for (int j = 0; j < q.steps().size(); j++) {
            Step s = q.steps().get(j);
            String n = String.valueOf(j + 1);
            switch (s.trigger()) {
                case Trigger.Choice c -> {
                    for (int i = 0; i < c.options().size(); i++) {
                        Trigger.Option o = c.options().get(i);
                        out.add(new Pickable(WOOL[Math.min(i, 3)], colour(Math.min(i, 3)) + t("gui-pick-button", "label", o.label(), "n", n),
                                List.of(t("gui-pick-button-lore", "n", n)), new Cond.Picked(questField, s.id(), o.id(), false)));
                    }
                }
                case Trigger.FirstOf f -> {
                    for (int i = 0; i < f.options().size(); i++) {
                        Trigger.Branch o = f.options().get(i);
                        out.add(new Pickable(WOOL[Math.min(i, 3)], colour(Math.min(i, 3)) + t("gui-pick-world", "label", esc(Validation.plain(o.label())), "n", n),
                                List.of(t("gui-pick-world-lore", "n", n, "type", o.trigger().type())), new Cond.Picked(questField, s.id(), o.id(), false)));
                    }
                }
                case Trigger.Answer a -> {
                    if (a.answers().size() < 2) break; // one accepted answer: everyone who got on gave it
                    for (String ans : a.answers()) {
                        out.add(new Pickable(Material.NAME_TAG, "<white>" + t("gui-pick-answer", "label", esc(ans), "n", n),
                                List.of(t("gui-pick-answer-lore", "n", n)), new Cond.Picked(questField, s.id(), Trigger.normalize(ans), false)));
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    /**
     * "What should be true?" Lists only things that exist: the buttons, world choice options and answers of this
     * quest's steps, then an item from the hand or something from another quest. Left click = it happened, right
     * click = it did NOT happen.
     */
    public void openCondPicker(Player p, String id, int idx, int page, Consumer<Cond> onPick, Runnable back) {
        Quest q = editor.editable(p, id);
        if (q == null) return;
        List<Pickable> all = pickables(q, "");
        int per = 36;
        int pages = Math.max(1, (all.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, m().get("menu-cond-title"));
        for (int i = 0; i < per && pg * per + i < all.size(); i++) {
            Pickable pk = all.get(pg * per + i);
            List<String> lore = new ArrayList<>(pk.lore());
            lore.add("");
            lore.add(t("gui-pick-left"));
            lore.add(t("gui-pick-right"));
            menu.set(i, Menu.icon(pk.icon(), pk.name(), lore), c -> onPick.accept(c.right() ? Cond.negate(pk.yes()) : pk.yes()));
        }
        if (all.isEmpty()) menu.set(13, Menu.icon(Material.PAPER, t("gui-cond-none"), t("gui-cond-none-lore")), null);
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> back.run());
        if (pg > 0) menu.set(46, Menu.icon(Material.ARROW, t("gui-prev-page")), c -> openCondPicker(p, id, idx, pg - 1, onPick, back));
        menu.set(48, Menu.icon(Material.CHEST, t("gui-cond-item"), t("gui-cond-item-lore")), c -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                p.sendMessage(m().get("editor-hold-item"));
                return;
            }
            openItemCond(p, new Cond.HasItem(hand.getType().getKey().getKey(), hand.getAmount(), false), onPick, back);
        });
        menu.set(50, Menu.icon(Material.WRITABLE_BOOK, t("gui-cond-quest"), t("gui-cond-quest-lore")), c -> openQuestCond(p, id, onPick, back));
        if (pg < pages - 1) menu.set(52, Menu.icon(Material.ARROW, t("gui-next-page")), c -> openCondPicker(p, id, idx, pg + 1, onPick, back));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    /** "Has an item": amount − / +. The items are only looked at, never taken. */
    private void openItemCond(Player p, Cond.HasItem h, Consumer<Cond> onPick, Runnable back) {
        Menu menu = new Menu(3, m().get("menu-item-title"));
        Material mat = Material.matchMaterial(h.item());
        menu.set(4, Menu.icon(mat == null || mat.isAir() ? Material.CHEST : mat, t("gui-item-name", "item", h.item().replace('_', ' '), "amount", String.valueOf(h.amount())),
                t("gui-item-hand")), c -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!hand.getType().isAir()) openItemCond(p, new Cond.HasItem(hand.getType().getKey().getKey(), h.amount(), h.not()), onPick, back);
        });
        menu.set(11, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-item-minus"), t("gui-shift-5")),
                c -> openItemCond(p, new Cond.HasItem(h.item(), Math.max(1, h.amount() - c.amount()), h.not()), onPick, back));
        menu.set(13, Menu.icon(Material.SLIME_BALL, t("gui-item-amount", "value", String.valueOf(h.amount()))), null);
        menu.set(15, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-item-plus"), t("gui-shift-5")),
                c -> openItemCond(p, new Cond.HasItem(h.item(), Math.min(2304, h.amount() + c.amount()), h.not()), onPick, back));
        menu.set(18, Menu.icon(Material.ARROW, t("gui-back")), c -> back.run());
        menu.set(22, Menu.icon(Material.LIME_CONCRETE, t("gui-item-done")), c -> onPick.accept(h));
        menu.set(26, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    /** Another quest: pick it, then one of its buttons/answers or one of its endings. */
    private void openQuestCond(Player p, String id, Consumer<Cond> onPick, Runnable back) {
        Menu menu = new Menu(6, m().get("menu-cond-quest-title"));
        int i = 0;
        for (Quest o : editor.editableQuests(p)) {
            if (o.id().equals(id) || i >= PAGE) continue;
            menu.set(i++, Menu.icon(o.published() ? Material.WRITTEN_BOOK : Material.BOOK, "<white>" + esc(Validation.plain(o.title())), "<dark_gray>" + o.id()),
                    c -> openQuestCondOf(p, o.id(), onPick, () -> openQuestCond(p, id, onPick, back)));
        }
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> back.run());
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    private void openQuestCondOf(Player p, String other, Consumer<Cond> onPick, Runnable back) {
        Quest o = editor.editable(p, other);
        if (o == null) return;
        Menu menu = new Menu(6, m().get("menu-cond-of-title", "title", Validation.plain(o.title())));
        int slot = 0;
        for (String e : o.endings()) {
            if (slot >= 9) break;
            Cond yes = new Cond.Ending(o.id(), e, false);
            menu.set(slot++, Menu.icon(Material.NETHER_STAR, "<gold>" + t("gui-pick-ending", "ending", esc(Validation.plain(e))), "", t("gui-pick-left"), t("gui-pick-right")),
                    c -> onPick.accept(c.right() ? Cond.negate(yes) : yes));
        }
        slot = 9;
        for (Pickable pk : pickables(o, o.id())) {
            if (slot >= 45) break;
            List<String> lore = new ArrayList<>(pk.lore());
            lore.add("");
            lore.add(t("gui-pick-left"));
            lore.add(t("gui-pick-right"));
            menu.set(slot++, Menu.icon(pk.icon(), pk.name(), lore), c -> onPick.accept(c.right() ? Cond.negate(pk.yes()) : pk.yes()));
        }
        if (slot == 9 && o.endings().isEmpty()) menu.set(22, Menu.icon(Material.PAPER, t("gui-cond-none"), t("gui-cond-none-quest")), null);
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> back.run());
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- step target picker ----------

    /**
     * "Go where?" Every step (with its first line), the end of the quest (with an optional ending name) and a new
     * step at the end. {@code allowDefault} adds "the following step" (for the Otherwise row).
     */
    public void openTargets(Player p, String id, int from, boolean allowDefault, int page, Consumer<Target> onPick, Runnable back) {
        Quest q = editor.editable(p, id);
        if (q == null) return;
        int pages = Math.max(1, (q.steps().size() + PAGE - 1) / PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, m().get("menu-target-title", "n", String.valueOf(from + 1)));
        for (int i = 0; i < PAGE && pg * PAGE + i < q.steps().size(); i++) {
            int j = pg * PAGE + i;
            Step s = q.steps().get(j);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + esc(Describe.first(s)));
            if (j == from) lore.add(t("gui-target-self"));
            else if (j == from + 1) lore.add(t("gui-target-following"));
            menu.set(i, Menu.icon(triggerIcon(s.trigger()), t("gui-step-name", "n", String.valueOf(j + 1), "type", s.trigger().type()), lore),
                    c -> onPick.accept(Target.step(s.id())));
        }
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> back.run());
        if (allowDefault) menu.set(46, Menu.icon(Material.GRAY_DYE, t("gui-target-default"), t("gui-target-default-lore")), c -> onPick.accept(null));
        menu.set(47, Menu.icon(Material.NETHER_STAR, t("gui-target-end"), t("gui-target-end-lore")),
                c -> plugin.dialogs().textInput(p, plain("input-title-ending"), "", false, name -> onPick.accept(Target.end(name)), back));
        menu.set(48, Menu.icon(Material.LIME_DYE, t("gui-target-new"), t("gui-target-new-lore")), c -> {
            int at = editor.addStep(p, id, "CONTINUE", null);
            Quest nq = editor.editable(p, id);
            if (at >= 0 && nq != null) onPick.accept(Target.step(nq.steps().get(at).id()));
        });
        if (pg > 0) menu.set(51, Menu.icon(Material.ARROW, t("gui-prev-page")), c -> openTargets(p, id, from, allowDefault, pg - 1, onPick, back));
        if (pg < pages - 1) menu.set(52, Menu.icon(Material.ARROW, t("gui-next-page")), c -> openTargets(p, id, from, allowDefault, pg + 1, onPick, back));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- text variants ----------

    /** "Other text if…": one row per variant: condition — line — text. The first true one replaces the step's text. */
    public void openVariants(Player p, String id, int idx) {
        Quest q = editor.editable(p, id);
        if (q == null || idx < 0 || idx >= q.steps().size()) return;
        Step st = q.steps().get(idx);
        Describe d = d();
        Menu menu = new Menu(6, m().get("menu-variants-title", "n", String.valueOf(idx + 1)));
        Runnable again = () -> openVariants(p, id, idx);
        for (int v = 0; v < Validation.MAX_VARIANTS; v++) {
            int base = v * 9;
            if (v >= st.textIf().size()) {
                if (v == st.textIf().size()) {
                    menu.set(base, Menu.icon(Material.LIME_DYE, t("gui-add-variant"), t("gui-add-variant-lore")),
                            c -> openCondPicker(p, id, idx, 0, cond -> plugin.dialogs().textInput(p, plain("input-title-variant"),
                                    String.join("\n", st.text()), true, text -> {
                                        editor.addVariant(p, id, idx, cond, text);
                                        again.run();
                                    }, again), again));
                }
                continue;
            }
            int vi = v;
            TextVariant tv = st.textIf().get(v);
            int col = Describe.colour(q, tv.cond());
            String condText = esc(d.cond(q, tv.cond()));
            menu.set(base, Menu.icon(condIcon(q, tv.cond()), colour(col) + t("gui-if", "cond", condText), t("gui-cond-right")), c -> {
                if (!c.right()) return;
                List<TextVariant> l = new ArrayList<>(st.textIf());
                l.set(vi, new TextVariant(Cond.negate(tv.cond()), tv.text()));
                editor.mutate(p, id, false, x -> x.withStep(idx, x.steps().get(idx).withTextIf(l)));
                again.run();
            });
            for (int k = 1; k <= 3; k++) menu.set(base + k, Menu.icon(PANE[col], colour(col) + condText), null);
            List<String> lore = new ArrayList<>();
            for (int i = 0; i < Math.min(5, tv.text().size()); i++) lore.add("<gray>" + esc(Validation.plain(tv.text().get(i))));
            lore.add("");
            lore.add(t("gui-click-edit-short"));
            menu.set(base + 4, Menu.icon(Material.WRITABLE_BOOK, t("gui-variant-text"), lore),
                    c -> plugin.dialogs().textInput(p, plain("input-title-variant"), String.join("\n", tv.text()), true, text -> {
                        editor.setVariantText(p, id, idx, vi, text);
                        again.run();
                    }, again));
            menu.set(base + 8, Menu.icon(Material.BARRIER, t("gui-variant-remove"), t("gui-shift-confirm")), c -> {
                if (!c.shift()) return;
                editor.removeVariant(p, id, idx, vi);
                again.run();
            });
        }
        menu.set(36, Menu.icon(Material.WRITABLE_BOOK, t("gui-variant-normal"), t("gui-variant-normal-lore")), null);
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openStep(p, id, idx));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- branch map ----------

    /**
     * The quest drawn with plain lines: steps left to right in their order (two columns each), the main path on the top
     * row, paths that only a condition leads to on rows below. Grey panes are "next step", coloured panes conditions
     * (the colour of the button). Jumps back and endings are told in the step's tooltip. {@code first} is the first
     * step shown; the arrows scroll.
     */
    public void openMap(Player p, String id, int first) {
        Quest q = editor.editable(p, id);
        if (q == null) return;
        int n = q.steps().size();
        int start = Math.max(0, Math.min(first, Math.max(0, n - 5)));
        int[] lane = lanes(q);
        Menu menu = new Menu(6, m().get("menu-map-title", "title", q.title()));
        Describe d = d();
        ItemStack[] grid = new ItemStack[45];
        String[] tips = new String[45];
        // lines first, steps on top
        for (int i = 0; i < n; i++) {
            Step s = q.steps().get(i);
            for (int r = -1; r < s.rules().size(); r++) {
                Target t = r < 0 ? q.fallThrough(i) : s.rules().get(r).target();
                if (t.isEnd()) continue;
                int j = q.indexOf(t.step());
                if (j <= i) continue; // back jumps: tooltip only
                Material pane = r < 0 ? Material.GRAY_STAINED_GLASS_PANE : PANE[Describe.colour(q, s.rules().get(r).cond())];
                String tip = r < 0 ? "<gray>" + esc(d.target(q, i, s.next())) : colour(Describe.colour(q, s.rules().get(r).cond()))
                        + esc(d.cond(q, s.rules().get(r).cond())) + " → " + esc(d.step(q, t.step()));
                int ci = 2 * (i - start) + 1, cj = 2 * (j - start);
                int ri = 2 * lane[i], rj = 2 * lane[j];
                // down/up in the column right of the source, then along the target's row
                for (int row = Math.min(ri, rj); row <= Math.max(ri, rj); row++) put(grid, tips, row, ci, pane, tip);
                for (int col = ci; col < cj; col++) put(grid, tips, rj, col, pane, tip);
            }
        }
        for (int i = start; i < n && 2 * (i - start) < 9; i++) {
            Step s = q.steps().get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + esc(Describe.first(s)));
            lore.addAll(routeLore(q, i));
            lore.add("");
            lore.add(t("gui-click-edit-short"));
            int slot = 2 * lane[i] * 9 + 2 * (i - start);
            int si = i;
            menu.set(slot, Menu.icon(triggerIcon(s.trigger()), t("gui-step-name", "n", String.valueOf(i + 1), "type", s.trigger().type()), lore),
                    c -> openStep(p, id, si));
            grid[slot] = null;
        }
        for (int k = 0; k < 45; k++) {
            if (grid[k] != null && menu.getInventory().getItem(k) == null) menu.set(k, grid[k], null);
        }
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openQuest(p, id, 0));
        if (start > 0) menu.set(48, Menu.icon(Material.ARROW, t("gui-map-left")), c -> openMap(p, id, start - 4));
        menu.set(49, Menu.icon(Material.KNOWLEDGE_BOOK, t("gui-map-legend"), t("gui-map-legend-1"), t("gui-map-legend-2"), t("gui-map-legend-3"),
                t("gui-map-legend-4")), null);
        if (start + 5 < n) menu.set(50, Menu.icon(Material.ARROW, t("gui-map-right")), c -> openMap(p, id, start + 4));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    private static void put(ItemStack[] grid, String[] tips, int row, int col, Material pane, String tip) {
        if (row < 0 || row > 4 || col < 0 || col > 8) return;
        int k = row * 9 + col;
        if (grid[k] == null) {
            grid[k] = Menu.icon(pane, tip);
            tips[k] = tip;
        }
    }

    /**
     * Row (0..2) per step: 0 for the path you get by always taking "otherwise" from step 1; steps that only a condition
     * leads to start a new row below the row of the step that leads there.
     */
    static int[] lanes(Quest q) {
        int n = q.steps().size();
        int[] lane = new int[n];
        java.util.Arrays.fill(lane, -1);
        // main path
        int i = 0;
        while (i >= 0 && i < n && lane[i] < 0) {
            lane[i] = 0;
            Target t = q.fallThrough(i);
            i = t.isEnd() ? -1 : q.indexOf(t.step());
        }
        int nextLane = 1;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = 0; k < n; k++) {
                if (lane[k] < 0) continue;
                for (Rule r : q.steps().get(k).rules()) {
                    if (r.target().isEnd()) continue;
                    int j = q.indexOf(r.target().step());
                    if (j < 0 || lane[j] >= 0) continue;
                    int l = Math.min(2, nextLane++);
                    changed = true;
                    // follow that path's defaults while they are unplaced
                    while (j >= 0 && j < n && lane[j] < 0) {
                        lane[j] = l;
                        Target t = q.fallThrough(j);
                        j = t.isEnd() ? -1 : q.indexOf(t.step());
                    }
                }
            }
        }
        for (int k = 0; k < n; k++) if (lane[k] < 0) lane[k] = Math.min(2, nextLane);
        return lane;
    }
}

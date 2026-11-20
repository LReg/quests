package cloud.schwnbrg.questbook.editor.gui;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.editor.EditorService;
import cloud.schwnbrg.questbook.editor.EditorSession;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
import cloud.schwnbrg.questbook.storage.Database;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

/** The editor menus: quest list (A), quest (B), step (C), trigger picker (D), requirements (E). All text is from messages.yml. */
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
        menu.set(0, Menu.icon(Material.NAME_TAG, q.title(), "<gray>" + esc(Validation.plain(q.summary())),
                "", t("gui-edit-title"), t("gui-edit-summary")), c -> {
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
        menu.set(3, Menu.icon(Material.LEAD, t("gui-requires"),
                        q.requires().isEmpty() ? List.of(t("gui-nothing")) : q.requires().stream().map(r -> t("gui-requires-entry", "id", r)).toList()),
                c -> openRequires(p, id));

        int pages = Math.max(1, (q.steps().size() + 26) / 27);
        int pg = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < 27 && pg * 27 + i < q.steps().size(); i++) {
            int idx = pg * 27 + i;
            Step st = q.steps().get(idx);
            String first = st.text().isEmpty() ? "" : Validation.plain(st.text().get(0));
            menu.set(9 + i, Menu.icon(triggerIcon(st.trigger()), t("gui-step-name", "n", String.valueOf(idx + 1), "type", st.trigger().type()),
                    "<gray>" + esc(first), "<dark_gray>" + esc(summary(st.trigger())), "", t("gui-click-edit-short"), t("gui-shift-move")), c -> {
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
        menu.set(40, Menu.icon(Material.LIME_DYE, t("gui-add-step")), c -> openPicker(p, id, -1, pg));
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

    private void editSummary(Player p, Quest q, Runnable back) {
        plugin.dialogs().textInput(p, plain("input-title-summary"), q.summary(), true, text -> {
            editor.setSummary(p, q.id(), String.join(" ", text.split("\n")).strip());
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
        };
    }

    // ---------- C: step menu ----------

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
        List<String> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(5, st.text().size()); i++) preview.add("<gray>" + esc(Validation.plain(st.text().get(i))));
        preview.add("");
        preview.add(t("gui-text-lore"));
        preview.add(t("gui-click-edit"));
        menu.set(10, Menu.icon(Material.WRITABLE_BOOK, t("gui-text"), preview),
                c -> plugin.dialogs().textInput(p, plain("input-title-text"), String.join("\n", st.text()), true, text -> {
                    editor.setText(p, id, idx, text);
                    openStep(p, id, idx);
                }, () -> openStep(p, id, idx)));
        menu.set(11, Menu.icon(Material.LANTERN, t("gui-hint"), "<gray>" + (st.hint() == null ? t("gui-none") : esc(Validation.plain(st.hint()))), "",
                t("gui-click-edit-short"), t("gui-clear")), c -> {
            if (c.right()) {
                editor.setHint(p, id, idx, null);
                openStep(p, id, idx);
            } else {
                plugin.dialogs().textInput(p, plain("input-title-hint"), st.hint() == null ? "" : st.hint(), false, text -> {
                    editor.setHint(p, id, idx, text);
                    openStep(p, id, idx);
                }, () -> openStep(p, id, idx));
            }
        });
        menu.set(12, Menu.icon(triggerIcon(st.trigger()), t("gui-trigger", "type", st.trigger().type()), t("gui-trigger-lore")),
                c -> openPicker(p, id, idx, 0));
        List<String> afterLore = new ArrayList<>();
        for (int i = 0; i < Math.min(5, st.after().size()); i++) afterLore.add("<gray>" + esc(Validation.plain(st.after().get(i))));
        if (afterLore.isEmpty()) afterLore.add("<gray>" + t("gui-none"));
        afterLore.add("");
        afterLore.add(t("gui-after-lore"));
        afterLore.add(t("gui-click-edit-short"));
        afterLore.add(t("gui-clear"));
        menu.set(13, Menu.icon(Material.BOOK, t("gui-after"), afterLore), c -> {
            if (c.right()) {
                editor.setAfter(p, id, idx, "");
                openStep(p, id, idx);
            } else {
                plugin.dialogs().textInput(p, plain("input-title-after"), String.join("\n", st.after()), true, text -> {
                    editor.setAfter(p, id, idx, text);
                    openStep(p, id, idx);
                }, () -> openStep(p, id, idx));
            }
        });
        menu.set(14, Menu.icon(Material.SPECTRAL_ARROW, t("gui-move-left")), c -> {
            if (idx > 0) {
                editor.moveStep(p, id, idx, idx - 1);
                openStep(p, id, idx - 1);
            }
        });
        menu.set(15, Menu.icon(Material.TIPPED_ARROW, t("gui-move-right")), c -> {
            if (idx < q.steps().size() - 1) {
                editor.moveStep(p, id, idx, idx + 1);
                openStep(p, id, idx + 1);
            }
        });
        menu.set(16, Menu.icon(Material.TNT, t("gui-delete-step")), c -> confirm(p, t("gui-confirm-delete-step", "n", String.valueOf(idx + 1)), () -> {
            editor.removeStep(p, id, idx);
            openQuest(p, id, 0);
        }, () -> openStep(p, id, idx)));
        triggerRow(menu, p, id, idx, st);
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openQuest(p, id, idx / 27));
        menu.set(47, Menu.icon(Material.COMPASS, t("gui-test")), c -> {
            p.closeInventory();
            { Quest tq = editor.editable(p, id); if (tq != null) plugin.quests().startTest(p, tq); }
        });
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    private void triggerRow(Menu menu, Player p, String id, int idx, Step st) {
        Runnable again = () -> openStep(p, id, idx);
        switch (st.trigger()) {
            case Trigger.Reach r -> {
                menu.set(28, Menu.icon(Material.ENDER_PEARL, t("gui-use-position"), t("gui-use-position-lore")), c -> {
                    editor.stepHere(p, id, idx, null);
                    again.run();
                });
                menu.set(30, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-radius-minus"), t("gui-shift-5")), c -> {
                    editor.setRadius(p, id, idx, r.radius() - c.amount());
                    again.run();
                });
                menu.set(31, Menu.icon(Material.SLIME_BALL, t("gui-radius-value", "value", String.valueOf(r.radius()))), null);
                menu.set(32, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-radius-plus"), t("gui-shift-5")), c -> {
                    editor.setRadius(p, id, idx, r.radius() + c.amount());
                    again.run();
                });
                menu.set(34, Menu.icon(Material.MAP, "<gray>" + String.format("%.1f %.1f %.1f", r.x(), r.y(), r.z()), "<dark_gray>" + esc(r.world())), null);
            }
            case Trigger.Region r -> {
                menu.set(28, Menu.icon(Material.LIME_BANNER, t("gui-pos1"), t("gui-pos1-lore")), c -> {
                    editor.setCorner(p, id, idx, true);
                    again.run();
                });
                menu.set(29, Menu.icon(Material.RED_BANNER, t("gui-pos2"), t("gui-pos2-lore")), c -> {
                    editor.setCorner(p, id, idx, false);
                    again.run();
                });
                menu.set(31, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-height-minus"), t("gui-shift-5")), c -> {
                    editor.setHeight(p, id, idx, r.height() - c.amount());
                    again.run();
                });
                menu.set(32, Menu.icon(Material.SLIME_BALL, t("gui-height-value", "value", String.valueOf(r.height()))), null);
                menu.set(33, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-height-plus"), t("gui-shift-5")), c -> {
                    editor.setHeight(p, id, idx, r.height() + c.amount());
                    again.run();
                });
                menu.set(34, Menu.icon(Material.MAP,
                        t("gui-size", "w", String.valueOf(Math.abs(r.x2() - r.x1()) + 1), "h", String.valueOf(r.height()), "d", String.valueOf(Math.abs(r.z2() - r.z1()) + 1)),
                        t("gui-from-y", "y", String.valueOf(r.baseY())), "<dark_gray>" + esc(r.world())), null);
            }
            case Trigger.SignClick tr -> pickRow(menu, p, id, idx, tr.sign() == null ? null : tr.sign().toString());
            case Trigger.Interact tr -> pickRow(menu, p, id, idx, tr.block() == null ? null : tr.block().toString());
            case Trigger.Answer a -> {
                for (int i = 0; i < Math.min(10, a.answers().size()); i++) {
                    int ai = i;
                    int slot = i < 9 ? 27 + i : 36;
                    menu.set(slot, Menu.icon(Material.NAME_TAG, "<white>" + esc(a.answers().get(i)), t("gui-answer-remove")), c -> {
                        editor.removeAnswer(p, id, idx, ai);
                        again.run();
                    });
                }
                menu.set(44, Menu.icon(Material.LIME_DYE, t("gui-add-answer")),
                        c -> plugin.dialogs().textInput(p, plain("input-title-answer"), "", false, text -> {
                            editor.addAnswer(p, id, idx, text);
                            again.run();
                        }, again));
            }
            case Trigger.Dimension d -> menu.set(28, Menu.icon(Material.OBSIDIAN, t("gui-dimension", "env", d.env().name()), t("gui-cycle")), c -> {
                World.Environment next = switch (d.env()) {
                    case NORMAL -> World.Environment.NETHER;
                    case NETHER -> World.Environment.THE_END;
                    default -> World.Environment.NORMAL;
                };
                editor.setDimension(p, id, idx, next);
                again.run();
            });
            case Trigger.AdvancementDone a -> {
                Advancement adv = Bukkit.getAdvancement(a.key());
                String title = adv == null ? t("gui-adv-unknown") : "<gray>" + esc(adv.getDisplay() == null ? a.key().asString()
                        : PlainTextComponentSerializer.plainText().serialize(adv.getDisplay().title()));
                menu.set(28, Menu.icon(Material.KNOWLEDGE_BOOK, t("gui-set-key"), "<gray>" + esc(a.key().asString()), title),
                        c -> plugin.dialogs().textInput(p, plain("input-title-advancement"), a.key().asString(), false, text -> {
                            editor.setAdvancement(p, id, idx, text);
                            again.run();
                        }, again));
            }
            case Trigger.Kill k -> {
                menu.set(28, glow(Menu.icon(entityIcon(k.target()), t("gui-kill-target", "target", pretty(k.target())), t("gui-kill-choose"))),
                        c -> openKillTargets(p, id, idx, 0));
                menu.set(30, Menu.icon(Material.RED_STAINED_GLASS_PANE, t("gui-kill-minus"), t("gui-shift-5")), c -> {
                    editor.setKill(p, id, idx, null, k.amount() - c.amount());
                    again.run();
                });
                menu.set(31, Menu.icon(Material.SLIME_BALL, t("gui-kill-value", "value", String.valueOf(k.amount()))), null);
                menu.set(32, Menu.icon(Material.LIME_STAINED_GLASS_PANE, t("gui-kill-plus"), t("gui-shift-5")), c -> {
                    editor.setKill(p, id, idx, null, k.amount() + c.amount());
                    again.run();
                });
            }
            case Trigger.Continue c -> { }
        }
    }

    private void pickRow(Menu menu, Player p, String id, int idx, String target) {
        menu.set(28, Menu.icon(Material.SPYGLASS, t("gui-pick"), t("gui-pick-lore")), c -> editor.startPick(p, id, idx));
        menu.set(30, Menu.icon(Material.MAP, target == null ? t("gui-nothing-picked") : "<gray>" + esc(target)), null);
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

    private static org.bukkit.inventory.ItemStack glow(org.bukkit.inventory.ItemStack it) {
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

    public void openKillTargets(Player p, String id, int step, int page) {
        Quest q = editor.editable(p, id);
        if (q == null || step < 0 || step >= q.steps().size()) return;
        String current = q.steps().get(step).trigger() instanceof Trigger.Kill k ? k.target() : "";
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
                editor.setKill(p, id, step, target, null);
                openStep(p, id, step);
            });
        }
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openStep(p, id, step));
        if (pg > 0) menu.set(48, Menu.icon(Material.ARROW, t("gui-prev-page")), c -> openKillTargets(p, id, step, pg - 1));
        if (pg < pages - 1) menu.set(50, Menu.icon(Material.ARROW, t("gui-next-page")), c -> openKillTargets(p, id, step, pg + 1));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- D: trigger picker ----------

    /** step &lt; 0 adds a new step at the end, otherwise changes the trigger of that step. */
    public void openPicker(Player p, String id, int step, int returnPage) {
        Menu menu = new Menu(3, m().get("menu-picker-title"));
        int slot = 9; // 9 trigger types fill row 2; back and close are in row 3
        for (String type : Trigger.TYPES) {
            menu.set(slot++, Menu.icon(triggerIcon(Trigger.blank(type)), "<white>" + type, t("gui-desc-" + type.toLowerCase())), c -> {
                if (step < 0) {
                    editor.addStep(p, id, type, null);
                    Quest q = editor.editable(p, id);
                    openStep(p, id, q == null ? 0 : q.steps().size() - 1);
                } else {
                    editor.setTriggerType(p, id, step, type);
                    openStep(p, id, step);
                }
            });
        }
        menu.set(18, Menu.icon(Material.ARROW, t("gui-back")), c -> {
            if (step < 0) openQuest(p, id, returnPage);
            else openStep(p, id, step);
        });
        menu.set(26, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }

    // ---------- E: requirements ----------

    public void openRequires(Player p, String id) {
        Quest q = editor.editable(p, id);
        if (q == null) return;
        Menu menu = new Menu(6, m().get("menu-requires-title"));
        int i = 0;
        for (Quest o : editor.editableQuests(p)) {
            if (o.id().equals(id) || i >= PAGE) continue;
            boolean req = q.requires().contains(o.id());
            menu.set(i++, Menu.icon(req ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, (req ? "<green>" : "<gray>") + esc(Validation.plain(o.title())),
                    "<dark_gray>" + o.id(), t("gui-click-toggle")), c -> {
                editor.toggleRequire(p, id, o.id(), null);
                openRequires(p, id);
            });
        }
        menu.set(45, Menu.icon(Material.ARROW, t("gui-back")), c -> openQuest(p, id, 0));
        menu.set(53, Menu.icon(Material.BARRIER, t("gui-close")), c -> p.closeInventory());
        menu.open(p);
    }
}

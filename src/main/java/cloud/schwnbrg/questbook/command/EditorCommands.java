package cloud.schwnbrg.questbook.command;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.editor.EditorService;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Trigger;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** /qe ... (Brigadier, with tab completion). Players only, except list, validate, reload, reset. */
public final class EditorCommands {

    private final QuestbookPlugin plugin;
    private final EditorService ed;

    public EditorCommands(QuestbookPlugin plugin, EditorService ed) {
        this.plugin = plugin;
        this.ed = ed;
    }

    private Messages m() { return plugin.messages(); }

    /** A dialog title from messages.yml, as plain text. */
    private String title(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(m().get(key));
    }

    private static boolean isEditor(CommandSourceStack s) { return s.getSender().hasPermission("questbook.editor"); }
    private static boolean isAdmin(CommandSourceStack s) { return s.getSender().hasPermission("questbook.admin"); }

    private SuggestionProvider<CommandSourceStack> questIds() { return (c, b) -> {
        for (String id : plugin.quests().quests().keySet()) b.suggest(id);
        return b.buildFuture();
    }; }

    private SuggestionProvider<CommandSourceStack> stepNumbers() { return (c, b) -> {
        Quest q = plugin.quests().quest(StringArgumentType.getString(c, "id"));
        if (q != null) for (int i = 1; i <= q.steps().size(); i++) b.suggest(i);
        return b.buildFuture();
    }; }

    private RequiredArgumentBuilder<CommandSourceStack, String> id() {
        return Commands.argument("id", StringArgumentType.word()).suggests(questIds());
    }

    private RequiredArgumentBuilder<CommandSourceStack, Integer> n() {
        return Commands.argument("n", IntegerArgumentType.integer(1)).suggests(stepNumbers());
    }

    private static String s(CommandContext<CommandSourceStack> c, String name) { return StringArgumentType.getString(c, name); }
    private static int i(CommandContext<CommandSourceStack> c, String name) { return IntegerArgumentType.getInteger(c, name); }

    private int player(CommandContext<CommandSourceStack> c, BiConsumer<Player, CommandContext<CommandSourceStack>> f) {
        if (!(c.getSource().getSender() instanceof Player p)) {
            c.getSource().getSender().sendMessage(m().get("players-only"));
            return 0;
        }
        f.accept(p, c);
        return 1;
    }

    public void register(Commands r) {
        LiteralArgumentBuilder<CommandSourceStack> qe = Commands.literal("qe").requires(EditorCommands::isEditor);
        qe.executes(c -> player(c, (p, x) -> plugin.menus().openList(p, 0)));
        qe.then(Commands.literal("list").executes(this::list));
        qe.then(Commands.literal("create").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("title", StringArgumentType.greedyString()).executes(c -> player(c, (p, x) -> {
                    Quest q = ed.create(p, s(x, "id"), s(x, "title"));
                    if (q != null) plugin.menus().openQuest(p, q.id(), 0);
                })))));
        qe.then(Commands.literal("edit").then(id().executes(c -> player(c, (p, x) -> {
            if (plugin.quests().quest(s(x, "id")) == null) p.sendMessage(m().get("editor-unknown-quest", "quest", Messages.esc(s(x, "id"))));
            else plugin.menus().openQuest(p, s(x, "id"), 0);
        }))));
        qe.then(Commands.literal("delete").then(id().executes(c -> player(c, (p, x) -> {
            String qid = s(x, "id");
            p.sendMessage(m().get("editor-delete-confirm", "quest", qid).append(net.kyori.adventure.text.Component.text(" ")).append(plugin.quests().button("btn-confirm", () -> ed.delete(p, qid))));
        }))));
        qe.then(Commands.literal("title").then(id().then(Commands.argument("title", StringArgumentType.greedyString())
                .executes(c -> player(c, (p, x) -> ed.setTitle(p, s(x, "id"), s(x, "title")))))));
        qe.then(Commands.literal("summary").then(id().executes(c -> player(c, (p, x) -> {
            Quest q = plugin.quests().quest(s(x, "id"));
            if (q == null) return;
            plugin.dialogs().textInput(p, title("input-title-summary"), q.summary(), true, t -> ed.setSummary(p, q.id(), String.join(" ", t.split("\n")).strip()), () -> { });
        }))));
        qe.then(Commands.literal("sign")
                .then(Commands.literal("start").then(id().executes(c -> player(c, (p, x) -> ed.linkStartSign(p, s(x, "id"))))))
                .then(Commands.literal("unlink").executes(c -> player(c, (p, x) -> ed.unlinkSign(p)))));
        qe.then(step());
        qe.then(Commands.literal("requires").then(id()
                .then(Commands.literal("add").then(Commands.argument("other", StringArgumentType.word()).suggests(questIds())
                        .executes(c -> player(c, (p, x) -> ed.toggleRequire(p, s(x, "id"), s(x, "other"), true)))))
                .then(Commands.literal("remove").then(Commands.argument("other", StringArgumentType.word()).suggests(questIds())
                        .executes(c -> player(c, (p, x) -> ed.toggleRequire(p, s(x, "id"), s(x, "other"), false)))))));
        qe.then(Commands.literal("repeatable").then(id().then(Commands.argument("on", BoolArgumentType.bool())
                .executes(c -> player(c, (p, x) -> ed.setRepeatable(p, s(x, "id"), BoolArgumentType.getBool(x, "on"), 0)))
                .then(Commands.argument("cooldown", IntegerArgumentType.integer(0))
                        .executes(c -> player(c, (p, x) -> ed.setRepeatable(p, s(x, "id"), BoolArgumentType.getBool(x, "on"), i(x, "cooldown"))))))));
        qe.then(Commands.literal("publish").then(id().executes(c -> player(c, (p, x) -> ed.publish(p, s(x, "id"))))));
        qe.then(Commands.literal("unpublish").then(id().executes(c -> player(c, (p, x) -> ed.unpublish(p, s(x, "id"))))));
        qe.then(Commands.literal("preview")
                .executes(c -> player(c, (p, x) -> toggle(p, null)))
                .then(Commands.literal("on").executes(c -> player(c, (p, x) -> toggle(p, true))))
                .then(Commands.literal("off").executes(c -> player(c, (p, x) -> toggle(p, false)))));
        qe.then(Commands.literal("test")
                .then(Commands.literal("stop").executes(c -> player(c, (p, x) -> plugin.quests().stopTest(p))))
                .then(id().executes(c -> player(c, (p, x) -> {
                    Quest q = plugin.quests().quest(s(x, "id"));
                    if (q == null) p.sendMessage(m().get("editor-unknown-quest", "quest", Messages.esc(s(x, "id"))));
                    else plugin.quests().startTest(p, q);
                }))));
        qe.then(Commands.literal("skip").executes(c -> player(c, (p, x) -> plugin.quests().skip(p))));
        qe.then(Commands.literal("reset").requires(EditorCommands::isAdmin).then(Commands.argument("player", StringArgumentType.word())
                .suggests((c, b) -> {
                    Bukkit.getOnlinePlayers().forEach(o -> b.suggest(o.getName()));
                    return b.buildFuture();
                })
                .then(id().executes(this::reset))));
        qe.then(Commands.literal("stats").then(id().executes(c -> player(c, (p, x) -> plugin.stats(p, s(x, "id"))))));
        qe.then(Commands.literal("validate").executes(c -> player(c, (p, x) -> ed.validate(p, null)))
                .then(id().executes(c -> player(c, (p, x) -> ed.validate(p, s(x, "id"))))));
        qe.then(Commands.literal("reload").requires(EditorCommands::isAdmin).executes(c -> {
            var report = plugin.reloadAll();
            var sender = c.getSource().getSender();
            sender.sendMessage(m().get("reloaded", "count", String.valueOf(report.quests())));
            for (String note : report.needsRestart()) sender.sendMessage(m().get("reload-restart", "what", Messages.esc(note)));
            return 1;
        }));
        r.register(qe.build(), "Quest editor", List.of());
    }

    private void toggle(Player p, Boolean on) {
        var s = ed.session(p);
        s.preview = on == null ? !s.preview : on;
        p.sendMessage(m().get(s.preview ? "preview-on" : "preview-off"));
    }

    private int list(CommandContext<CommandSourceStack> c) {
        var sender = c.getSource().getSender();
        if (plugin.quests().quests().isEmpty()) sender.sendMessage(m().get("editor-none"));
        for (Quest q : plugin.quests().quests().values()) {
            sender.sendMessage(m().get("editor-list-line", "id", q.id(), "title", Messages.esc(cloud.schwnbrg.questbook.model.Validation.plain(q.title())),
                    "status", q.status().name(), "steps", String.valueOf(q.steps().size())));
        }
        return 1;
    }

    private int reset(CommandContext<CommandSourceStack> c) {
        String name = s(c, "player");
        String qid = s(c, "id");
        UUID uuid;
        Player online = Bukkit.getPlayerExact(name);
        // getOfflinePlayer(name) can do a blocking web lookup on the main thread; only use known players
        var known = online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
        if (known == null) {
            c.getSource().getSender().sendMessage(m().get("editor-unknown-player", "player", Messages.esc(name)));
            return 0;
        }
        uuid = known.getUniqueId();
        plugin.quests().reset(uuid, qid);
        c.getSource().getSender().sendMessage(m().get("editor-reset", "player", Messages.esc(name), "quest", qid));
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> step() {
        LiteralArgumentBuilder<CommandSourceStack> st = Commands.literal("step");
        st.then(Commands.literal("add").then(id().then(Commands.argument("type", StringArgumentType.word()).suggests((c, b) -> {
            Trigger.TYPES.forEach(b::suggest);
            return b.buildFuture();
        }).executes(c -> player(c, (p, x) -> ed.addStep(p, s(x, "id"), s(x, "type"), null)))
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(c -> player(c, (p, x) -> ed.addStep(p, s(x, "id"), s(x, "type"), i(x, "index"))))))));
        st.then(Commands.literal("remove").then(id().then(n().executes(c -> player(c, (p, x) -> ed.removeStep(p, s(x, "id"), i(x, "n") - 1))))));
        st.then(Commands.literal("move").then(id().then(n().then(Commands.argument("to", IntegerArgumentType.integer(1))
                .executes(c -> player(c, (p, x) -> ed.moveStep(p, s(x, "id"), i(x, "n") - 1, i(x, "to") - 1)))))));
        st.then(Commands.literal("text").then(id().then(n().executes(c -> player(c, (p, x) -> {
            Quest q = plugin.quests().quest(s(x, "id"));
            int idx = i(x, "n") - 1;
            if (q == null || idx >= q.steps().size()) {
                p.sendMessage(m().get("editor-unknown-step", "step", String.valueOf(idx + 1)));
                return;
            }
            plugin.dialogs().textInput(p, title("input-title-text"),String.join("\n", q.steps().get(idx).text()), true, t -> ed.setText(p, q.id(), idx, t), () -> { });
        })))));
        st.then(Commands.literal("after").then(id().then(n().then(Commands.literal("clear")
                .executes(c -> player(c, (p, x) -> ed.setAfter(p, s(x, "id"), i(x, "n") - 1, ""))))
                .executes(c -> player(c, (p, x) -> {
            Quest q = plugin.quests().quest(s(x, "id"));
            int idx = i(x, "n") - 1;
            if (q == null || idx >= q.steps().size()) {
                p.sendMessage(m().get("editor-unknown-step", "step", String.valueOf(idx + 1)));
                return;
            }
            plugin.dialogs().textInput(p, title("input-title-after"), String.join("\n", q.steps().get(idx).after()), true,
                    t -> ed.setAfter(p, q.id(), idx, t), () -> { });
        })))));
        st.then(Commands.literal("hint").then(id().then(n().executes(c -> player(c, (p, x) -> {
            Quest q = plugin.quests().quest(s(x, "id"));
            int idx = i(x, "n") - 1;
            if (q == null || idx >= q.steps().size()) {
                p.sendMessage(m().get("editor-unknown-step", "step", String.valueOf(idx + 1)));
                return;
            }
            String old = q.steps().get(idx).hint();
            plugin.dialogs().textInput(p, title("input-title-hint"), old == null ? "" : old, false, t -> ed.setHint(p, q.id(), idx, t), () -> { });
        })))));
        st.then(Commands.literal("here").then(id().then(n().executes(c -> player(c, (p, x) -> ed.stepHere(p, s(x, "id"), i(x, "n") - 1, null)))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                        .executes(c -> player(c, (p, x) -> ed.stepHere(p, s(x, "id"), i(x, "n") - 1, i(x, "radius"))))))));
        st.then(Commands.literal("radius").then(id().then(n().then(Commands.argument("r", IntegerArgumentType.integer(1, 64))
                .executes(c -> player(c, (p, x) -> ed.setRadius(p, s(x, "id"), i(x, "n") - 1, i(x, "r"))))))));
        st.then(Commands.literal("pos1").then(id().then(n().executes(c -> player(c, (p, x) -> ed.setCorner(p, s(x, "id"), i(x, "n") - 1, true))))));
        st.then(Commands.literal("pos2").then(id().then(n().executes(c -> player(c, (p, x) -> ed.setCorner(p, s(x, "id"), i(x, "n") - 1, false))))));
        st.then(Commands.literal("height").then(id().then(n().then(Commands.argument("h", IntegerArgumentType.integer(1, 128))
                .executes(c -> player(c, (p, x) -> ed.setHeight(p, s(x, "id"), i(x, "n") - 1, i(x, "h"))))))));
        st.then(Commands.literal("block").then(id().then(n().executes(c -> player(c, (p, x) -> ed.stepBlockLooking(p, s(x, "id"), i(x, "n") - 1))))));
        st.then(Commands.literal("answers").then(id().then(n().then(Commands.argument("answers", StringArgumentType.greedyString())
                .executes(c -> player(c, (p, x) -> ed.setAnswers(p, s(x, "id"), i(x, "n") - 1, Arrays.asList(s(x, "answers").split("\\|")))))))));
        st.then(Commands.literal("kill").then(id().then(n().then(Commands.argument("target", StringArgumentType.word()).suggests((c, b) -> {
            Trigger.Kill.CATEGORIES.forEach(b::suggest);
            for (var t : org.bukkit.entity.EntityType.values()) if (t.isAlive() && t != org.bukkit.entity.EntityType.PLAYER && t != org.bukkit.entity.EntityType.ARMOR_STAND) b.suggest(t.name());
            return b.buildFuture();
        }).executes(c -> player(c, (p, x) -> ed.setKill(p, s(x, "id"), i(x, "n") - 1, s(x, "target"), null)))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
                        .executes(c -> player(c, (p, x) -> ed.setKill(p, s(x, "id"), i(x, "n") - 1, s(x, "target"), i(x, "amount")))))))));
        st.then(Commands.literal("dimension").then(id().then(n().then(Commands.argument("dim", StringArgumentType.word()).suggests((c, b) -> {
            b.suggest("nether");
            b.suggest("end");
            b.suggest("overworld");
            return b.buildFuture();
        }).executes(c -> player(c, (p, x) -> {
            World.Environment env = switch (s(x, "dim").toLowerCase()) {
                case "nether" -> World.Environment.NETHER;
                case "end" -> World.Environment.THE_END;
                case "overworld" -> World.Environment.NORMAL;
                default -> null;
            };
            if (env == null) p.sendMessage(m().get("editor-bad-dimension"));
            else ed.setDimension(p, s(x, "id"), i(x, "n") - 1, env);
        }))))));
        st.then(Commands.literal("advancement").then(id().then(n().then(Commands.argument("key", StringArgumentType.greedyString()).suggests((c, b) -> {
            String rem = b.getRemaining().toLowerCase();
            for (var it = Bukkit.advancementIterator(); it.hasNext();) {
                NamespacedKey k = it.next().getKey();
                if (k.asString().contains(rem)) b.suggest(k.asString());
            }
            return b.buildFuture();
        }).executes(c -> player(c, (p, x) -> ed.setAdvancement(p, s(x, "id"), i(x, "n") - 1, s(x, "key"))))))));
        return st;
    }
}

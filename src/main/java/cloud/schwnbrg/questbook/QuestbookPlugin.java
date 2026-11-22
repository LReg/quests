package cloud.schwnbrg.questbook;

import cloud.schwnbrg.questbook.command.EditorCommands;
import cloud.schwnbrg.questbook.command.PlayerCommands;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.editor.EditorService;
import cloud.schwnbrg.questbook.editor.PreviewService;
import cloud.schwnbrg.questbook.editor.gui.MenuListener;
import cloud.schwnbrg.questbook.editor.gui.Menus;
import cloud.schwnbrg.questbook.editor.input.ChatCapture;
import cloud.schwnbrg.questbook.integration.AdvancementSync;
import cloud.schwnbrg.questbook.integration.ChatFallback;
import cloud.schwnbrg.questbook.integration.DialogSupport;
import cloud.schwnbrg.questbook.integration.ExportService;
import cloud.schwnbrg.questbook.integration.PaperDialogs;
import cloud.schwnbrg.questbook.journal.JournalService;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.QuestStatus;
import cloud.schwnbrg.questbook.model.Validation;
import cloud.schwnbrg.questbook.runtime.QuestService;
import cloud.schwnbrg.questbook.sign.RumourService;
import cloud.schwnbrg.questbook.sign.SignService;
import cloud.schwnbrg.questbook.storage.Database;
import cloud.schwnbrg.questbook.storage.QuestRepository;
import cloud.schwnbrg.questbook.text.TextDelivery;
import cloud.schwnbrg.questbook.trigger.QuestListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class QuestbookPlugin extends JavaPlugin {

    private Messages messages;
    private Database database;
    private QuestRepository repo;
    private TextDelivery delivery;
    private QuestService quests;
    private SignService signs;
    private RumourService rumours;
    private JournalService journal;
    private EditorService editor;
    private Menus menus;
    private ChatCapture chatCapture;
    private DialogSupport dialogs;
    private AdvancementSync advancements;
    private ExportService export;
    private cloud.schwnbrg.questbook.runtime.GuideService guide;
    private final Map<String, String> chronicle = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();

        database = new Database(getDataFolder().toPath().resolve("questbook.db"), getLogger());
        try {
            getDataFolder().mkdirs();
            database.open();
        } catch (Exception e) {
            getLogger().severe("Cannot open the database, disabling: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        repo = new QuestRepository(getDataFolder().toPath().resolve("quests"), getLogger());
        delivery = new TextDelivery(this);
        quests = new QuestService(this, database, messages, delivery);
        signs = new SignService(this, quests, messages);
        rumours = new RumourService(this, quests, signs, database);
        journal = new JournalService(this, quests, messages);
        editor = new EditorService(this, repo, messages);
        menus = new Menus(this, editor);
        chatCapture = new ChatCapture(this, messages);
        advancements = new AdvancementSync(this);
        export = new ExportService(this);
        guide = new cloud.schwnbrg.questbook.runtime.GuideService(this, quests, messages);
        applyLimits();
        dialogs = createDialogs();

        quests.setQuests(loadQuests());
        signs.rebuildIndex();
        advancements.registerAll();
        database.chronicleNames().thenAccept(m -> Bukkit.getScheduler().runTask(this, () -> chronicle.putAll(m)));

        var pm = getServer().getPluginManager();
        pm.registerEvents(new QuestListener(this, quests, signs, rumours), this);
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(chatCapture, this);

        PlayerCommands pc = new PlayerCommands(this);
        EditorCommands ec = new EditorCommands(this, editor);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            pc.register(event.registrar());
            ec.register(event.registrar());
        });

        new PreviewService(this, editor).start();
        guide.start();
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            getLogger().warning("Players are online while the plugin enables: /reload is not supported. Restart the server instead.");
        }
        for (Player p : Bukkit.getOnlinePlayers()) quests.join(p);
        getLogger().info(quests.quests().size() + " quests loaded.");
    }

    @Override
    public void onDisable() {
        if (delivery != null) delivery.cancelAll();
        getServer().getScheduler().cancelTasks(this);
        if (database != null) database.close();
    }

    private DialogSupport createDialogs() {
        if (getConfig().getBoolean("dialogs.enabled", true)) {
            try {
                return new PaperDialogs(this);
            } catch (LinkageError e) {
                getLogger().warning("Paper dialogs unavailable, using chat: " + e);
            }
        }
        return new ChatFallback(this);
    }

    /** Loads all quest YAML. Quests with errors are loaded as drafts and logged. */
    private Map<String, Quest> loadQuests() {
        Map<String, Quest> all = repo.loadAll();
        Map<String, Quest> out = new LinkedHashMap<>();
        for (Quest q : all.values()) {
            var errors = Validation.errors(q, all, q.published());
            if (!errors.isEmpty() && q.published()) {
                getLogger().warning("Quest " + q.id() + " has errors and is loaded as DRAFT: " + String.join("; ", errors));
                q = q.withStatus(QuestStatus.DRAFT);
            }
            out.put(q.id(), q);
        }
        return out;
    }

    /** Result of /qe reload: how many quests are loaded and what only changes after a restart. */
    public record ReloadReport(int quests, java.util.List<String> needsRestart) {}

    /** /qe reload: config, messages and all quest YAML. Runs on the main thread. */
    public ReloadReport reloadAll() {
        long previewInterval = getConfig().getLong("preview.interval_ticks", 10);
        boolean advancementsOn = getConfig().getBoolean("advancements.enabled", true);
        Map<String, Quest> before = new LinkedHashMap<>(quests.quests());

        reloadConfig();
        applyLimits();
        messages.load();
        quests.setQuests(loadQuests());
        for (String id : before.keySet()) if (quests.quest(id) == null) advancements.unregister(id);
        signs.rebuildIndex();
        for (Quest q : quests.quests().values()) if (!q.published()) advancements.unregister(q.id());
        advancements.registerAll(); // parents before children
        dialogs = createDialogs();
        quests.reconcile(before); // players mid-quest: clamp steps, stop drafts, drop vanished quests, resume newly published ones
        editor.dropMissingQuests();
        for (Player p : Bukkit.getOnlinePlayers()) signs.refreshAll(p);

        java.util.List<String> notes = new java.util.ArrayList<>();
        if (getConfig().getLong("preview.interval_ticks", 10) != previewInterval) notes.add("preview.interval_ticks");
        if (getConfig().getBoolean("advancements.enabled", true) != advancementsOn) notes.add("advancements.enabled");
        var pending = advancements.pendingRestart();
        if (!pending.isEmpty()) notes.add("advancements: " + String.join(", ", pending));
        return new ReloadReport(quests.quests().size(), notes);
    }

    /** /qe stats: SQL runs async, the answer is sent on the main thread. */
    public void stats(Player p, String id) {
        Quest q = editor.editable(p, id);
        if (q == null) {
            p.sendMessage(messages.get("editor-unknown-quest", "quest", Messages.esc(id)));
            return;
        }
        database.stats(id).thenAccept(s -> Bukkit.getScheduler().runTask(this, () -> {
            p.sendMessage(messages.get("stats-head", "quest", q.title()));
            p.sendMessage(messages.get("stats-line", "started", String.valueOf(s.started()), "active", String.valueOf(s.active()),
                    "completed", String.valueOf(s.completed()), "total", String.valueOf(s.totalCompletions()), "abandoned", String.valueOf(s.abandoned())));
            s.perStep().forEach((step, n) -> p.sendMessage(messages.get("stats-step", "step", String.valueOf(step + 1), "count", String.valueOf(n))));
            s.endings().forEach((ending, n) -> p.sendMessage(messages.get("stats-ending", "ending", ending, "count", String.valueOf(n))));
        }));
    }

    public String chronicleName(String questId) { return chronicle.get(questId); }
    public void setChronicle(String questId, String name) { chronicle.put(questId, name); }

    public Messages messages() { return messages; }
    public Database database() { return database; }
    public QuestService quests() { return quests; }
    public SignService signs() { return signs; }
    public JournalService journal() { return journal; }
    public EditorService editor() { return editor; }
    public Menus menus() { return menus; }
    public ChatCapture chatCapture() { return chatCapture; }
    public DialogSupport dialogs() { return dialogs; }
    public AdvancementSync advancements() { return advancements; }
    public ExportService export() { return export; }
    public cloud.schwnbrg.questbook.runtime.GuideService guide() { return guide; }

    /** Text limits from config.yml (limits:), falling back to the defaults for missing keys. */
    private void applyLimits() {
        var d = Validation.Limits.DEFAULT;
        var c = getConfig();
        Validation.setLimits(new Validation.Limits(c.getInt("limits.summary", d.summary()), c.getInt("limits.hint", d.hint()),
                c.getInt("limits.line", d.line()), c.getInt("limits.lines_per_step", d.linesPerStep()), c.getInt("limits.steps", d.steps())));
    }
}

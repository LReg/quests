package cloud.schwnbrg.questbook.integration;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.storage.PlayerData;
import cloud.schwnbrg.questbook.storage.Progress;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Quests as vanilla advancements in the "Server Stories" tab (runtime-loaded via UnsafeValues).
 *
 * <p>Paper's {@code loadAdvancement} writes the JSON into a datapack folder and refuses to replace an advancement that
 * is already loaded; {@code removeAdvancement} only deletes that file. So a running server can add an advancement but
 * never change or drop one: changes take effect after the next restart. To make that work, the content hash of what was
 * written is kept in {@code advancements.yml}: unchanged content is left alone (no remove/load churn), changed or
 * unpublished content has its file deleted so the next start loads the fresh version.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class AdvancementSync {

    private static final String ROOT = "root";
    private static final String CRITERION = "done";

    private final QuestbookPlugin plugin;
    private boolean broken;
    private YamlConfiguration hashes;
    /** Quests whose advancement in the running game differs from their quest until the next restart. */
    private final Set<String> pendingRestart = new java.util.TreeSet<>();

    public AdvancementSync(QuestbookPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("advancements.enabled", true) && !broken;
    }

    private NamespacedKey key(String id) { return new NamespacedKey("questbook", id); }

    private NamespacedKey questKey(Quest q) { return new NamespacedKey("questbook", "quest/" + q.id()); }

    private static String plain(String mm) {
        return PlainTextComponentSerializer.plainText().serialize(Messages.mm(mm));
    }

    private static String q(String s) {
        return ExportService.js(s);
    }

    private static String component(String s) {
        return "{\"text\":" + q(s) + "}";
    }

    // ---------- content hashes ----------

    private File hashFile() { return new File(plugin.getDataFolder(), "advancements.yml"); }

    private YamlConfiguration hashes() {
        if (hashes == null) hashes = YamlConfiguration.loadConfiguration(hashFile());
        return hashes;
    }

    private void setHash(String id, String hash) {
        hashes().set(id, hash);
        try {
            hashes().save(hashFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save advancements.yml: " + e);
        }
    }

    // ---------- registration ----------

    /** Quest ids whose advancement only matches the quest after the next restart (changed, unpublished or deleted). */
    public Set<String> pendingRestart() { return Set.copyOf(pendingRestart); }

    /** Registers root and all published quests. Call at enable, before players join. */
    public void registerAll() {
        if (!enabled()) return;
        try {
            if (Bukkit.getAdvancement(key(ROOT)) == null) {
                String json = "{\"display\":{\"icon\":{\"id\":\"minecraft:written_book\"},\"title\":" + component("Server Stories")
                        + ",\"description\":" + component("Stories you finished.") + ",\"background\":\"minecraft:block/bookshelf\","
                        + "\"show_toast\":false,\"announce_to_chat\":false},\"criteria\":{\"" + CRITERION + "\":{\"trigger\":\"minecraft:impossible\"}}}";
                Bukkit.getUnsafe().loadAdvancement(key(ROOT), json);
            }
            // parents first: an advancement whose parent is not loaded yet cannot be added
            Set<String> done = new HashSet<>();
            for (Quest quest : plugin.quests().quests().values()) if (quest.published()) loadWithParent(quest, done);
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            plugin.getLogger().warning("Advancements unavailable, quests will not appear in the advancement tab: " + e);
        }
    }

    private void loadWithParent(Quest quest, Set<String> done) {
        if (!done.add(quest.id())) return; // also stops requires cycles
        Quest parent = parentOf(quest);
        if (parent != null) loadWithParent(parent, done);
        load(quest);
    }

    /** The quest whose advancement is this one's parent in the tab, or null for the root. */
    private Quest parentOf(Quest quest) {
        if (quest.requires().isEmpty()) return null;
        Quest req = plugin.quests().quest(quest.requires().get(0));
        return req != null && req.published() ? req : null;
    }

    /** Registers one quest (a newly published quest shows up at once; changes to an existing one apply after a restart). */
    public void register(Quest quest) {
        if (!enabled()) return;
        Set<String> done = new HashSet<>();
        loadWithParent(quest, done);
        // quests below it that hung at the root while it was not published belong under it again
        reloadChildren(quest.id());
    }

    /** Rewrites the published quests directly below {@code questId}: their parent in the tab may have changed. */
    private void reloadChildren(String questId) {
        for (Quest child : plugin.quests().quests().values()) {
            if (child.published() && !child.id().equals(questId) && !child.requires().isEmpty() && child.requires().getFirst().equals(questId)) load(child);
        }
    }

    private String json(Quest quest) {
        Quest parentQuest = parentOf(quest);
        return json(quest, parentQuest == null ? key(ROOT).asString() : questKey(parentQuest).asString());
    }

    /** The advancement of {@code quest} under {@code parent} ("questbook:root" or "questbook:quest/&lt;id&gt;"). */
    static String json(Quest quest, String parent) {
        String frame = quest.steps().size() >= 5 ? "goal" : "task";
        // the default icon gives exactly the JSON of 1.4, so the hash matches and nothing counts as changed
        return "{\"parent\":" + q(parent) + ",\"display\":{\"icon\":{\"id\":" + q("minecraft:" + quest.iconOrDefault()) + "},\"title\":" + component(plain(quest.title()))
                + ",\"description\":" + component(description(quest)) + ",\"frame\":\"" + frame
                + "\",\"show_toast\":true,\"announce_to_chat\":false,\"hidden\":true},\"criteria\":{\"" + CRITERION
                + "\":{\"trigger\":\"minecraft:impossible\"}}}";
    }

    /**
     * The tooltip text. Up to 200 characters (every summary from before 1.2) it is exactly what 1.1 used, whitespace and
     * line breaks included, so the hash in advancements.yml still matches and nothing counts as changed. Longer
     * summaries become one line, cut at 200 characters.
     */
    static String description(Quest quest) {
        String raw = plain(quest.summary().isBlank() ? quest.title() : quest.summary());
        if (raw.length() <= 200) return raw;
        String s = raw.replaceAll("\\s*\\n\\s*", " ").strip();
        if (s.length() <= 200) return s;
        int cut = s.lastIndexOf(' ', 199);
        return s.substring(0, cut > 120 ? cut : 199) + "…";
    }

    private void load(Quest quest) {
        if (!enabled()) return;
        try {
            NamespacedKey k = questKey(quest);
            String json = json(quest);
            String hash = Integer.toHexString(json.hashCode());
            if (Bukkit.getAdvancement(k) == null) {
                Bukkit.getUnsafe().loadAdvancement(k, json);
                setHash(quest.id(), hash);
                pendingRestart.remove(quest.id());
            } else if (!hash.equals(hashes().getString(quest.id()))) {
                // loaded already with other content: it cannot be replaced while running. Its file gets the new content,
                // so the next start loads this one. Deleting the file instead (as before 1.5) left the quests below it
                // without a parent on the next start: "Couldn't load advancements".
                if (!overwrite(Bukkit.getWorlds().getFirst().getWorldFolder(), k, json)) Bukkit.getUnsafe().removeAdvancement(k);
                setHash(quest.id(), hash);
                pendingRestart.add(quest.id());
                plugin.getLogger().info("Advancement of " + quest.id() + " changed: it is updated after the next server restart.");
            }
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Could not register advancement for " + quest.id() + ": " + e);
        }
    }

    /**
     * Writes {@code json} over the file Paper keeps a runtime advancement in ({@code <world>/datapacks/bukkit/data/
     * <namespace>/advancement/<key>.json}; "advancements" in older layouts). Only an existing file is overwritten, so a
     * layout we do not know is never guessed at. False if there was no such file or it could not be written.
     */
    static boolean overwrite(File worldFolder, NamespacedKey key, String json) {
        for (String dir : new String[] {"advancement", "advancements"}) {
            File f = new File(worldFolder, "datapacks/bukkit/data/" + key.getNamespace() + "/" + dir + "/" + key.getKey() + ".json");
            if (!f.isFile()) continue;
            try {
                File tmp = new File(f.getPath() + ".tmp");
                java.nio.file.Files.writeString(tmp.toPath(), json);
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * The advancement stays in the running game (players keep their toast) and is gone after the next restart. The
     * published quests directly below it move up to the root on that restart, so their files are rewritten now; a file
     * still naming this one as parent would not load.
     */
    public void unregister(String questId) {
        if (!enabled()) return;
        try {
            Bukkit.getUnsafe().removeAdvancement(key("quest/" + questId));
            if (hashes().contains(questId)) setHash(questId, null);
            pendingRestart.add(questId);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Could not remove advancement " + questId + ": " + e);
        }
        reloadChildren(questId);
    }

    public void grant(Player p, Quest quest) {
        if (!enabled()) return;
        Advancement a = Bukkit.getAdvancement(questKey(quest));
        if (a == null) return;
        AdvancementProgress pr = p.getAdvancementProgress(a);
        if (!pr.isDone()) pr.awardCriteria(CRITERION);
    }

    /** On join: give the root, and award every completed quest silently. */
    public void syncJoin(Player p) {
        if (!enabled()) return;
        Advancement root = Bukkit.getAdvancement(key(ROOT));
        if (root != null && !p.getAdvancementProgress(root).isDone()) p.getAdvancementProgress(root).awardCriteria(CRITERION);
        PlayerData d = plugin.quests().data(p);
        if (d == null) return;
        for (Progress pr : d.progress.values()) {
            if (pr.completions() <= 0) continue;
            Quest quest = plugin.quests().quest(pr.questId());
            if (quest != null && quest.published()) grant(p, quest);
        }
    }
}

package cloud.schwnbrg.questbook.storage;

import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.QuestStatus;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Reads and writes quests/&lt;id&gt;.yml, the source of truth. */
public final class QuestRepository {

    private static final Set<String> QUEST_KEYS = Set.of("id", "title", "summary", "status", "author", "owner", "start_sign",
            "requires", "repeatable", "repeat_cooldown_seconds", "steps");

    private final Path dir;
    private final Logger log;

    public QuestRepository(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    public Path file(String id) { return dir.resolve(id + ".yml"); }

    public long lastModified(String id) {
        try {
            return Files.getLastModifiedTime(file(id)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public Map<String, Quest> loadAll() {
        Map<String, Quest> out = new LinkedHashMap<>();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warning("Cannot create quests dir: " + e);
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                try {
                    Quest q = parse(YamlConfiguration.loadConfiguration(p.toFile()), p.getFileName().toString());
                    if (q != null) out.put(q.id(), q);
                } catch (Exception e) {
                    log.warning("Skipping " + p.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warning("Cannot list quests: " + e);
        }
        return out;
    }

    private static final Set<String> STEP_KEYS = Set.of("text", "after", "trigger", "hint");
    private static final Map<String, Set<String>> TRIGGER_KEYS = Map.of(
            "CONTINUE", Set.of("type"),
            "REACH", Set.of("type", "world", "x", "y", "z", "radius"),
            "REGION", Set.of("type", "world", "x1", "z1", "x2", "z2", "base_y", "height"),
            "SIGN", Set.of("type", "sign"),
            "INTERACT", Set.of("type", "block"),
            "ANSWER", Set.of("type", "answers"),
            "DIMENSION", Set.of("type", "env"),
            "ADVANCEMENT", Set.of("type", "key"),
            "KILL", Set.of("type", "target", "amount"));

    Quest parse(ConfigurationSection c, String fileName) {
        String id = c.getString("id", fileName.replace(".yml", ""));
        for (String k : c.getKeys(false)) {
            if (!QUEST_KEYS.contains(k)) log.warning(fileName + ": unknown key '" + k + "'");
        }
        // Real mistakes (unknown trigger type, bad value) must not silently turn into other behaviour: the quest becomes a draft.
        List<String> problems = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        int n = 0;
        for (Map<?, ?> raw : c.getMapList("steps")) {
            steps.add(parseStep(raw, fileName, ++n, problems));
        }
        QuestStatus status = "PUBLISHED".equalsIgnoreCase(c.getString("status", "DRAFT")) ? QuestStatus.PUBLISHED : QuestStatus.DRAFT;
        if (!problems.isEmpty() && status == QuestStatus.PUBLISHED) {
            log.warning(fileName + ": loaded as DRAFT because of " + problems.size() + " problem(s) above");
            status = QuestStatus.DRAFT;
        }
        return new Quest(id, c.getString("title", id), c.getString("summary", ""), status,
                block(c.getConfigurationSection("start_sign")), List.copyOf(c.getStringList("requires")),
                c.getBoolean("repeatable"), c.getInt("repeat_cooldown_seconds"), c.getString("author", ""), c.getString("owner", ""), steps);
    }

    private Step parseStep(Map<?, ?> m, String file, int n, List<String> problems) {
        String where = file + ": step " + n;
        for (Object k : m.keySet()) {
            if (!STEP_KEYS.contains(String.valueOf(k))) log.warning(where + ": unknown key '" + k + "'");
        }
        List<String> text = new ArrayList<>();
        Object t = m.get("text");
        if (t instanceof List<?> l) for (Object o : l) text.add(String.valueOf(o));
        else if (t != null) text.add(String.valueOf(t));
        Object h = m.get("hint");
        YamlConfiguration y = new YamlConfiguration();
        Object trig = m.get("trigger");
        ConfigurationSection ts = trig instanceof Map<?, ?> tm ? y.createSection("t", castMap(tm)) : y.createSection("t");
        List<String> after = new ArrayList<>();
        Object a = m.get("after");
        if (a instanceof List<?> l) for (Object o : l) after.add(String.valueOf(o));
        else if (a != null) after.add(String.valueOf(a));
        return new Step(text, parseTrigger(ts, where, problems), h == null ? null : String.valueOf(h), after);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) { return (Map<String, Object>) m; }

    private void problem(List<String> problems, String where, String msg) {
        log.warning(where + ": " + msg);
        problems.add(where + ": " + msg);
    }

    private Trigger parseTrigger(ConfigurationSection t, String where, List<String> problems) {
        String type = t.getString("type", "CONTINUE").toUpperCase();
        Set<String> allowed = TRIGGER_KEYS.get(type);
        if (allowed == null) {
            problem(problems, where, "unknown trigger type '" + type + "', treated as CONTINUE");
            return new Trigger.Continue();
        }
        for (String k : t.getKeys(false)) {
            if (!allowed.contains(k)) log.warning(where + ": unknown key '" + k + "' for trigger " + type);
        }
        return switch (type) {
            case "REACH" -> new Trigger.Reach(t.getString("world", "world"), t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getInt("radius", 5));
            case "REGION" -> new Trigger.Region(t.getString("world", "world"), t.getInt("x1"), t.getInt("z1"), t.getInt("x2"), t.getInt("z2"),
                    t.getInt("base_y"), t.getInt("height", 4));
            case "SIGN" -> new Trigger.SignClick(block(t.getConfigurationSection("sign")));
            case "INTERACT" -> new Trigger.Interact(block(t.getConfigurationSection("block")));
            case "ANSWER" -> new Trigger.Answer(List.copyOf(t.getStringList("answers")));
            case "DIMENSION" -> {
                String env = t.getString("env", "NETHER").toUpperCase();
                World.Environment e = parseEnv(env);
                if (e == null) {
                    problem(problems, where, "unknown dimension '" + env + "' (NETHER, THE_END or NORMAL)");
                    e = World.Environment.NETHER;
                }
                yield new Trigger.Dimension(e);
            }
            case "KILL" -> {
                String target = t.getString("target", "ANY").toUpperCase();
                if (!Trigger.Kill.validTarget(target)) {
                    problem(problems, where, "unknown kill target '" + target + "' (ANY, ANIMAL, MONSTER, PLAYER or an entity type)");
                    target = "ANY";
                }
                yield new Trigger.Kill(target, t.getInt("amount", 5));
            }
            case "ADVANCEMENT" -> {
                String raw = t.getString("key", "minecraft:story/root");
                NamespacedKey k = NamespacedKey.fromString(raw);
                if (k == null) {
                    problem(problems, where, "invalid advancement key '" + raw + "'");
                    k = NamespacedKey.minecraft("story/root");
                }
                yield new Trigger.AdvancementDone(k);
            }
            default -> new Trigger.Continue();
        };
    }

    private static World.Environment parseEnv(String s) {
        try {
            return World.Environment.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BlockRef block(ConfigurationSection s) {
        if (s == null) return null;
        return new BlockRef(s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
    }

    public void save(Quest q) throws IOException {
        Files.createDirectories(dir);
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", q.id());
        y.set("title", q.title());
        y.set("summary", q.summary());
        y.set("status", q.status().name());
        y.set("author", q.author());
        if (!q.owner().isBlank()) y.set("owner", q.owner());
        if (q.startSign() != null) y.set("start_sign", blockMap(q.startSign()));
        y.set("requires", q.requires());
        y.set("repeatable", q.repeatable());
        y.set("repeat_cooldown_seconds", q.repeatCooldownSeconds());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step s : q.steps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", s.text());
            if (!s.after().isEmpty()) m.put("after", s.after());
            m.put("trigger", triggerMap(s.trigger()));
            if (s.hint() != null && !s.hint().isBlank()) m.put("hint", s.hint());
            steps.add(m);
        }
        y.set("steps", steps);
        Path tmp = dir.resolve(q.id() + ".yml.tmp");
        Files.writeString(tmp, y.saveToString());
        Files.move(tmp, file(q.id()), StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    private static Map<String, Object> blockMap(BlockRef b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", b.world());
        m.put("x", b.x());
        m.put("y", b.y());
        m.put("z", b.z());
        return m;
    }

    private static Map<String, Object> triggerMap(Trigger t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", t.type());
        switch (t) {
            case Trigger.Reach r -> { m.put("world", r.world()); m.put("x", r.x()); m.put("y", r.y()); m.put("z", r.z()); m.put("radius", r.radius()); }
            case Trigger.Region r -> { m.put("world", r.world()); m.put("x1", r.x1()); m.put("z1", r.z1()); m.put("x2", r.x2());
                m.put("z2", r.z2()); m.put("base_y", r.baseY()); m.put("height", r.height()); }
            case Trigger.SignClick s -> { if (s.sign() != null) m.put("sign", blockMap(s.sign())); }
            case Trigger.Interact i -> { if (i.block() != null) m.put("block", blockMap(i.block())); }
            case Trigger.Answer a -> m.put("answers", a.answers());
            case Trigger.Dimension d -> m.put("env", d.env().name());
            case Trigger.AdvancementDone a -> m.put("key", a.key().asString());
            case Trigger.Kill k -> { m.put("target", k.target()); m.put("amount", k.amount()); }
            case Trigger.Continue c -> { }
        }
        return m;
    }
}

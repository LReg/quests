package cloud.schwnbrg.questbook.storage;

import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Cond;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.QuestStatus;
import cloud.schwnbrg.questbook.model.Rule;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Target;
import cloud.schwnbrg.questbook.model.TextVariant;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
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

/**
 * Reads and writes quests/&lt;id&gt;.yml, the source of truth. Every key added after 1.1 is optional, so files written by
 * older versions load unchanged (steps get hidden ids s1, s2, ... in order, which are written on the next save).
 */
public final class QuestRepository {

    private static final Set<String> QUEST_KEYS = Set.of("id", "title", "summary", "status", "author", "owner", "start_sign",
            "requires", "requires_endings", "repeatable", "repeat_cooldown_seconds", "guide", "steps");

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

    private static final Set<String> STEP_KEYS = Set.of("id", "text", "text_if", "after", "trigger", "hint", "conditions", "next", "ending");
    private static final Map<String, Set<String>> TRIGGER_KEYS = Map.ofEntries(
            Map.entry("CONTINUE", Set.of("type")),
            Map.entry("REACH", Set.of("type", "world", "x", "y", "z", "radius")),
            Map.entry("REGION", Set.of("type", "world", "x1", "z1", "x2", "z2", "base_y", "height")),
            Map.entry("SIGN", Set.of("type", "sign")),
            Map.entry("INTERACT", Set.of("type", "block")),
            Map.entry("ANSWER", Set.of("type", "answers")),
            Map.entry("DIMENSION", Set.of("type", "env")),
            Map.entry("ADVANCEMENT", Set.of("type", "key")),
            Map.entry("KILL", Set.of("type", "target", "amount")),
            Map.entry("HAVE_ITEM", Set.of("type", "item", "amount", "take")), // "take" (1.3 betas) is read and ignored
            Map.entry("PLACE", Set.of("type", "block", "amount", "area", "at")), // "at" (1.3 betas): one spot, read as a 1x1x1 area
            Map.entry("CHOICE", Set.of("type", "options")),
            Map.entry("FIRST_OF", Set.of("type", "options")));

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
        Map<String, String> reqEnd = new LinkedHashMap<>();
        ConfigurationSection re = c.getConfigurationSection("requires_endings");
        if (re != null) for (String k : re.getKeys(false)) reqEnd.put(k, re.getString(k, ""));
        ConfigurationSection g = c.getConfigurationSection("guide");
        // No guide key: a file from before 1.2 gets the default (on). 1.2.0 wrote the key only when the guide was on, so a
        // 1.2.0 file without it had the guide off on purpose. 1.2+ files are recognised by their step ids.
        boolean from12 = c.getMapList("steps").stream().anyMatch(st -> st.containsKey("id"));
        Quest.Guide guide = g == null ? (from12 ? Quest.Guide.OFF : Quest.Guide.DEFAULT)
                : new Quest.Guide(g.getBoolean("particles", Quest.Guide.DEFAULT.particles()), g.getBoolean("compass", Quest.Guide.DEFAULT.compass()));
        return new Quest(id, c.getString("title", id), c.getString("summary", ""), status,
                block(c.getConfigurationSection("start_sign")), List.copyOf(c.getStringList("requires")), reqEnd,
                c.getBoolean("repeatable"), c.getInt("repeat_cooldown_seconds"), c.getString("author", ""), c.getString("owner", ""), steps, guide);
    }

    private static List<String> lines(Object t) {
        List<String> out = new ArrayList<>();
        if (t instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        else if (t != null) out.add(String.valueOf(t));
        return out;
    }

    private static ConfigurationSection section(Object raw) {
        YamlConfiguration y = new YamlConfiguration();
        return raw instanceof Map<?, ?> m ? y.createSection("t", castMap(m)) : y.createSection("t");
    }

    private Step parseStep(Map<?, ?> m, String file, int n, List<String> problems) {
        String where = file + ": step " + n;
        for (Object k : m.keySet()) {
            if (!STEP_KEYS.contains(String.valueOf(k))) log.warning(where + ": unknown key '" + k + "'");
        }
        Object h = m.get("hint");
        Object uid = m.get("id");
        List<Rule> rules = new ArrayList<>();
        if (m.get("conditions") instanceof List<?> l) {
            int i = 0;
            for (Object o : l) {
                i++;
                ConfigurationSection r = section(o);
                Cond cond = parseCond(r.getConfigurationSection("if"), where + " condition " + i, problems);
                Target t = parseTarget(r.getString("goto"), r.getString("ending"), where + " condition " + i, problems);
                if (cond != null && t != null) rules.add(new Rule(cond, t));
            }
        }
        List<TextVariant> variants = new ArrayList<>();
        if (m.get("text_if") instanceof List<?> l) {
            int i = 0;
            for (Object o : l) {
                i++;
                ConfigurationSection r = section(o);
                Cond cond = parseCond(r.getConfigurationSection("if"), where + " text variant " + i, problems);
                if (cond != null) variants.add(new TextVariant(cond, lines(r.get("text"))));
            }
        }
        Target next = null;
        Object nx = m.get("next");
        Object ending = m.get("ending");
        if (nx != null) next = parseTarget(String.valueOf(nx), ending == null ? null : String.valueOf(ending), where + " next", problems);
        else if (ending != null) next = Target.end(String.valueOf(ending));
        return new Step(uid == null ? null : String.valueOf(uid), lines(m.get("text")), parseTrigger(section(m.get("trigger")), where, problems),
                h == null ? null : String.valueOf(h), lines(m.get("after")), rules, next, variants);
    }

    private Target parseTarget(String to, String ending, String where, List<String> problems) {
        if (to == null || to.isBlank()) {
            problem(problems, where, "missing goto");
            return null;
        }
        return Target.END.equalsIgnoreCase(to.strip()) ? Target.end(ending) : Target.step(to.strip());
    }

    private Cond parseCond(ConfigurationSection s, String where, List<String> problems) {
        if (s == null) {
            problem(problems, where, "missing if");
            return null;
        }
        boolean not = s.getBoolean("not");
        if (s.contains("item")) {
            if (s.getBoolean("take")) log.warning(where + ": 'take: true' is ignored: Questbook never takes items any more, so this only checks that the player has them. Check that the quest still makes sense.");
            return new Cond.HasItem(s.getString("item", "").toLowerCase(), s.getInt("amount", 1), not);
        }
        if (s.contains("ending")) return new Cond.Ending(s.getString("quest", ""), s.getString("ending", ""), not);
        if (s.contains("picked")) return new Cond.Picked(s.getString("quest", ""), s.getString("step", ""), s.getString("picked", ""), not);
        problem(problems, where, "unknown condition " + s.getKeys(false));
        return null;
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
            case "HAVE_ITEM" -> {
                String item = t.getString("item", "").toLowerCase();
                if (!Validation.validItem(item)) problem(problems, where, "unknown item '" + item + "'");
                if (t.getBoolean("take")) log.warning(where + ": 'take: true' is ignored: Questbook never takes items any more, so this only checks that the player has them. Check that the quest still makes sense.");
                yield new Trigger.HaveItem(item, t.getInt("amount", 1));
            }
            case "PLACE" -> {
                String block = t.getString("block", "").toLowerCase();
                if (!Validation.validBlock(block)) problem(problems, where, "'" + block + "' is not a block that can be placed");
                ConfigurationSection ar = t.getConfigurationSection("area");
                Trigger.Region area = ar == null ? null : new Trigger.Region(ar.getString("world", "world"), ar.getInt("x1"), ar.getInt("z1"),
                        ar.getInt("x2"), ar.getInt("z2"), ar.getInt("base_y"), ar.getInt("height", 4));
                BlockRef at = area == null ? block(t.getConfigurationSection("at")) : null;
                if (at != null) area = new Trigger.Region(at.world(), at.x(), at.z(), at.x(), at.z(), at.y(), 1);
                yield new Trigger.Place(block, t.getInt("amount", 1), area);
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
            case "CHOICE" -> {
                List<Trigger.Option> opts = new ArrayList<>();
                for (Map<?, ?> o : t.getMapList("options")) {
                    Object oid = o.get("id");
                    Object label = o.get("label");
                    opts.add(new Trigger.Option(oid == null ? null : String.valueOf(oid), label == null ? "" : String.valueOf(label)));
                }
                yield new Trigger.Choice(opts);
            }
            case "FIRST_OF" -> {
                List<Trigger.Branch> opts = new ArrayList<>();
                int i = 0;
                for (Map<?, ?> o : t.getMapList("options")) {
                    i++;
                    Object oid = o.get("id");
                    Object label = o.get("label");
                    Trigger inner = parseTrigger(section(o.get("trigger")), where + " option " + i, problems);
                    opts.add(new Trigger.Branch(oid == null ? null : String.valueOf(oid), label == null ? "" : String.valueOf(label), inner));
                }
                yield new Trigger.FirstOf(opts);
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
        if (!q.requireEndings().isEmpty()) y.set("requires_endings", new LinkedHashMap<>(q.requireEndings()));
        y.set("repeatable", q.repeatable());
        y.set("repeat_cooldown_seconds", q.repeatCooldownSeconds());
        // always written, so a quest keeps its setting if the default changes again
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("particles", q.guide().particles());
        g.put("compass", q.guide().compass());
        y.set("guide", g);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step s : q.steps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("text", s.text());
            if (!s.textIf().isEmpty()) {
                List<Map<String, Object>> vs = new ArrayList<>();
                for (TextVariant v : s.textIf()) {
                    Map<String, Object> vm = new LinkedHashMap<>();
                    vm.put("if", condMap(v.cond()));
                    vm.put("text", v.text());
                    vs.add(vm);
                }
                m.put("text_if", vs);
            }
            if (!s.after().isEmpty()) m.put("after", s.after());
            m.put("trigger", triggerMap(s.trigger()));
            if (s.hint() != null && !s.hint().isBlank()) m.put("hint", s.hint());
            if (!s.rules().isEmpty()) {
                List<Map<String, Object>> rs = new ArrayList<>();
                for (Rule r : s.rules()) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("if", condMap(r.cond()));
                    rm.put("goto", r.target().step());
                    if (r.target().ending() != null) rm.put("ending", r.target().ending());
                    rs.add(rm);
                }
                m.put("conditions", rs);
            }
            if (s.next() != null) {
                m.put("next", s.next().step());
                if (s.next().ending() != null) m.put("ending", s.next().ending());
            }
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

    private static Map<String, Object> condMap(Cond c) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (c) {
            case Cond.Picked p -> {
                if (p.otherQuest()) m.put("quest", p.quest());
                m.put("step", p.step());
                m.put("picked", p.value());
            }
            case Cond.Ending e -> {
                m.put("quest", e.quest());
                m.put("ending", e.ending());
            }
            case Cond.HasItem h -> {
                m.put("item", h.item());
                m.put("amount", h.amount());
            }
        }
        if (c.not()) m.put("not", true);
        return m;
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
            case Trigger.HaveItem h -> { m.put("item", h.item()); m.put("amount", h.amount()); }
            case Trigger.Place pl -> {
                m.put("block", pl.block());
                m.put("amount", pl.amount());
                if (pl.area() != null) {
                    Trigger.Region r = pl.area();
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("world", r.world()); a.put("x1", r.x1()); a.put("z1", r.z1()); a.put("x2", r.x2()); a.put("z2", r.z2());
                    a.put("base_y", r.baseY()); a.put("height", r.height());
                    m.put("area", a);
                }
            }
            case Trigger.Choice c -> {
                List<Map<String, Object>> os = new ArrayList<>();
                for (Trigger.Option o : c.options()) {
                    Map<String, Object> om = new LinkedHashMap<>();
                    om.put("id", o.id());
                    om.put("label", o.label());
                    os.add(om);
                }
                m.put("options", os);
            }
            case Trigger.FirstOf f -> {
                List<Map<String, Object>> os = new ArrayList<>();
                for (Trigger.Branch o : f.options()) {
                    Map<String, Object> om = new LinkedHashMap<>();
                    om.put("id", o.id());
                    om.put("label", o.label());
                    om.put("trigger", triggerMap(o.trigger()));
                    os.add(om);
                }
                m.put("options", os);
            }
            case Trigger.Continue c -> { }
        }
        return m;
    }
}

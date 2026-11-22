package cloud.schwnbrg.questbook.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record Quest(
        String id,
        String title,
        String summary,
        QuestStatus status,
        BlockRef startSign,
        List<String> requires,
        Map<String, String> requireEndings,
        boolean repeatable,
        int repeatCooldownSeconds,
        String author,
        String owner,
        List<Step> steps,
        Guide guide,
        String icon) {

    /** The advancement icon a quest has when it picked none: the book every quest had before 1.5. */
    public static final String DEFAULT_ICON = "written_book";

    /**
     * The icons the editor offers for the advancement tab, so quests can be told apart. A hand-written file may name
     * any other item.
     */
    public static final List<String> ICONS = List.of(
            "written_book", "compass", "filled_map", "iron_sword", "bow", "shield",
            "diamond", "emerald", "gold_ingot", "iron_pickaxe", "wheat", "cake",
            "oak_sapling", "poppy", "lantern", "campfire", "bell", "chest",
            "ender_eye", "skeleton_skull", "heart_of_the_sea", "nether_star", "fishing_rod", "amethyst_shard");

    /**
     * Player guidance the editor turns on per quest: {@code particles} shows where the current step wants the player
     * (sphere, box, sign or block outline), {@code compass} shows a compass bar at the top of the screen pointing there.
     */
    public record Guide(boolean particles, boolean compass) {
        public static final Guide OFF = new Guide(false, false);
        public static final Guide ON = new Guide(true, true);
        /** What a quest gets when its file says nothing (new quests, quests from before 1.2). */
        public static final Guide DEFAULT = ON;
    }

    /** Quest without ending requirements and guidance. */
    public Quest(String id, String title, String summary, QuestStatus status, BlockRef startSign, List<String> requires,
                 boolean repeatable, int repeatCooldownSeconds, String author, String owner, List<Step> steps) {
        this(id, title, summary, status, startSign, requires, Map.of(), repeatable, repeatCooldownSeconds, author, owner, steps, Guide.DEFAULT);
    }

    /** Quest without an owner (files from before ownership existed). */
    public Quest(String id, String title, String summary, QuestStatus status, BlockRef startSign, List<String> requires,
                 boolean repeatable, int repeatCooldownSeconds, String author, List<Step> steps) {
        this(id, title, summary, status, startSign, requires, repeatable, repeatCooldownSeconds, author, "", steps);
    }

    /**
     * Canonical form: owner is the UUID of the editor who may see and edit the quest, empty if nobody owns it yet.
     * Every step gets a hidden id if it has none (old files: s1, s2, ... in order), and duplicates are renamed.
     * {@code requireEndings} maps a required quest to the ending it must have been finished with.
     */
    public Quest {
        owner = owner == null ? "" : owner;
        summary = summary == null ? "" : summary;
        requires = requires == null ? List.of() : List.copyOf(requires);
        Map<String, String> re = new LinkedHashMap<>();
        if (requireEndings != null) {
            for (var e : requireEndings.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank() && requires.contains(e.getKey())) re.put(e.getKey(), e.getValue());
            }
        }
        requireEndings = Map.copyOf(re);
        steps = withIds(steps == null ? List.of() : steps);
        guide = guide == null ? Guide.DEFAULT : guide;
        icon = normalIcon(icon);
    }

    /** A quest with the default icon. */
    public Quest(String id, String title, String summary, QuestStatus status, BlockRef startSign, List<String> requires,
                 Map<String, String> requireEndings, boolean repeatable, int repeatCooldownSeconds, String author, String owner,
                 List<Step> steps, Guide guide) {
        this(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide, null);
    }

    /** "Minecraft:Compass " becomes "compass"; blank and the default become null, so a quest without a choice writes nothing. */
    static String normalIcon(String icon) {
        if (icon == null) return null;
        String s = icon.strip().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        return s.isEmpty() || s.equals(DEFAULT_ICON) ? null : s;
    }

    /** The item id shown in the advancement tab. */
    public String iconOrDefault() { return icon == null ? DEFAULT_ICON : icon; }

    public Quest withIcon(String v) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide, v); }

    private static List<Step> withIds(List<Step> steps) {
        int max = 0;
        for (Step s : steps) max = Math.max(max, number(s.id()));
        Set<String> used = new HashSet<>();
        List<Step> out = new ArrayList<>(steps.size());
        for (Step s : steps) {
            Step x = s;
            // ids end up in commands (single word) and in the saved path (comma-separated), so only plain ids are kept
            if (x.id() == null || !x.id().matches("[A-Za-z0-9_-]{1,32}") || Target.END.equals(x.id()) || !used.add(x.id())) {
                x = x.withId("s" + (++max));
                used.add(x.id());
            }
            out.add(x);
        }
        return List.copyOf(out);
    }

    /** 4 for "s4", 0 for anything else. */
    static int number(String uid) {
        if (uid == null || uid.length() < 2 || uid.charAt(0) != 's') return 0;
        try {
            return Integer.parseInt(uid.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Quest withOwner(String v) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, v, steps, guide, icon); }

    public static Quest draft(String id, String title, String author) {
        return new Quest(id, title, "", QuestStatus.DRAFT, null, List.of(), false, 0, author, "", List.of());
    }

    public boolean published() { return status == QuestStatus.PUBLISHED; }

    public Quest withTitle(String v) { return new Quest(id, v, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide, icon); }
    public Quest withSummary(String v) { return new Quest(id, title, v, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide, icon); }
    public Quest withStatus(QuestStatus v) { return new Quest(id, title, summary, v, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide, icon); }
    public Quest withStartSign(BlockRef v) { return new Quest(id, title, summary, status, v, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide, icon); }
    public Quest withRequires(List<String> v) { return new Quest(id, title, summary, status, startSign, List.copyOf(v), requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide, icon); }
    public Quest withRequireEndings(Map<String, String> v) { return new Quest(id, title, summary, status, startSign, requires, v, repeatable, repeatCooldownSeconds, author, owner, steps, guide, icon); }
    public Quest withRepeatable(boolean r, int cooldown) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, r, cooldown, author, owner, steps, guide, icon); }
    /** The guide a player gets on step {@code i}: the quest's, with that step's override on top. */
    public Guide guideAt(int i) {
        return i < 0 || i >= steps.size() ? guide : steps.get(i).guideOverride().apply(guide);
    }

    public Quest withGuide(Guide v) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, v, icon); }
    public Quest withSteps(List<Step> v) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, List.copyOf(v), guide, icon); }

    public Quest withStep(int i, Step s) {
        List<Step> l = new ArrayList<>(steps);
        l.set(i, s);
        return withSteps(l);
    }

    /** Index of the step with this hidden id, or -1. */
    public int indexOf(String uid) {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).id().equals(uid)) return i;
        return -1;
    }

    public Step step(String uid) {
        int i = indexOf(uid);
        return i < 0 ? null : steps.get(i);
    }

    /** Where step i goes when no rule matches: its next, else the following step, else the end. */
    public Target fallThrough(int i) {
        Step s = steps.get(i);
        if (s.next() != null) return s.next();
        return i + 1 < steps.size() ? Target.step(steps.get(i + 1).id()) : Target.end(null);
    }

    /** Ending names used anywhere in the quest, in order of first use. */
    public List<String> endings() {
        List<String> out = new ArrayList<>();
        for (Step s : steps) {
            for (Rule r : s.rules()) if (r.target().isEnd() && r.target().ending() != null && !out.contains(r.target().ending())) out.add(r.target().ending());
            if (s.next() != null && s.next().isEnd() && s.next().ending() != null && !out.contains(s.next().ending())) out.add(s.next().ending());
        }
        return out;
    }
}

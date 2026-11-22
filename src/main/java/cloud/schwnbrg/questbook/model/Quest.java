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
        Guide guide) {

    /**
     * Player guidance the editor turns on per quest: {@code particles} shows where the current step wants the player
     * (sphere, box, sign or block outline), {@code compass} shows a compass bar at the top of the screen pointing there.
     */
    public record Guide(boolean particles, boolean compass) {
        public static final Guide OFF = new Guide(false, false);
    }

    /** Quest without ending requirements and guidance. */
    public Quest(String id, String title, String summary, QuestStatus status, BlockRef startSign, List<String> requires,
                 boolean repeatable, int repeatCooldownSeconds, String author, String owner, List<Step> steps) {
        this(id, title, summary, status, startSign, requires, Map.of(), repeatable, repeatCooldownSeconds, author, owner, steps, Guide.OFF);
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
        guide = guide == null ? Guide.OFF : guide;
    }

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

    public Quest withOwner(String v) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, v, steps, guide); }

    public static Quest draft(String id, String title, String author) {
        return new Quest(id, title, "", QuestStatus.DRAFT, null, List.of(), false, 0, author, "", List.of());
    }

    public boolean published() { return status == QuestStatus.PUBLISHED; }

    public Quest withTitle(String v) { return new Quest(id, v, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide); }
    public Quest withSummary(String v) { return new Quest(id, title, v, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide); }
    public Quest withStatus(QuestStatus v) { return new Quest(id, title, summary, v, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide); }
    public Quest withStartSign(BlockRef v) { return new Quest(id, title, summary, status, v, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide); }
    public Quest withRequires(List<String> v) { return new Quest(id, title, summary, status, startSign, List.copyOf(v), requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, guide); }
    public Quest withRequireEndings(Map<String, String> v) { return new Quest(id, title, summary, status, startSign, requires, v, repeatable, repeatCooldownSeconds, author, owner, steps, guide); }
    public Quest withRepeatable(boolean r, int cooldown) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, r, cooldown, author, owner, steps, guide); }
    public Quest withGuide(Guide v) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, steps, v); }
    public Quest withSteps(List<Step> v) { return new Quest(id, title, summary, status, startSign, requires, requireEndings, repeatable, repeatCooldownSeconds, author, owner, List.copyOf(v), guide); }

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

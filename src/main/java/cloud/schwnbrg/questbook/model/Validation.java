package cloud.schwnbrg.questbook.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure validation of a quest; world/block checks are done by the caller. */
public final class Validation {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{2,32}");

    private Validation() {}

    public static List<String> errors(Quest q, Map<String, Quest> all, boolean forPublish) {
        List<String> e = new ArrayList<>();
        if (q.id() == null || !ID.matcher(q.id()).matches()) e.add("id must match [a-z0-9_-]{2,32}");
        if (q.title() == null || q.title().isBlank()) e.add("title is blank");
        else if (plain(q.title()).length() > 48) e.add("title longer than 48 chars");
        if (q.summary() != null && plain(q.summary()).length() > 200) e.add("summary longer than 200 chars");
        if (q.steps().size() > 50) e.add("more than 50 steps");
        if (forPublish && q.steps().isEmpty()) e.add("no steps");
        for (int i = 0; i < q.steps().size(); i++) stepErrors(q.steps().get(i), i + 1, e);
        for (String r : q.requires()) {
            if (r.equals(q.id())) e.add("requires itself");
            else if (all != null && !all.containsKey(r)) e.add("requires unknown quest '" + r + "'");
        }
        if (all != null && hasCycle(q, all)) e.add("requires cycle");
        if (q.repeatable() && q.repeatCooldownSeconds() < 0) e.add("negative cooldown");
        if (forPublish && q.startSign() == null) e.add("no start sign");
        return e;
    }

    private static void stepErrors(Step s, int n, List<String> e) {
        String p = "step " + n + ": ";
        if (s.text() == null || s.text().stream().allMatch(String::isBlank)) e.add(p + "needs at least one text line");
        else {
            if (s.text().size() > 30) e.add(p + "more than 30 lines");
            for (String l : s.text()) if (l.length() > 256) { e.add(p + "line longer than 256 chars"); break; }
        }
        if (s.after().size() > 30) e.add(p + "after text has more than 30 lines");
        for (String l : s.after()) if (l.length() > 256) { e.add(p + "after text line longer than 256 chars"); break; }
        if (s.hint() != null && plain(s.hint()).length() > 200) e.add(p + "hint longer than 200 chars");
        switch (s.trigger()) {
            case Trigger.Reach r -> { if (r.radius() < 1 || r.radius() > 64) e.add(p + "radius must be 1..64"); }
            case Trigger.Region r -> { if (r.height() < 1 || r.height() > 128) e.add(p + "height must be 1..128"); }
            case Trigger.SignClick t -> { if (t.sign() == null) e.add(p + "no sign picked"); }
            case Trigger.Interact t -> { if (t.block() == null) e.add(p + "no block picked"); }
            case Trigger.Answer a -> { if (a.answers().isEmpty() || a.answers().size() > 10) e.add(p + "needs 1..10 answers"); }
            case Trigger.Kill k -> {
                if (k.amount() < 1 || k.amount() > 1000) e.add(p + "kill amount must be 1..1000");
                if (!Trigger.Kill.validTarget(k.target())) e.add(p + "unknown kill target '" + k.target() + "'");
            }
            default -> { }
        }
    }

    private static boolean hasCycle(Quest start, Map<String, Quest> all) {
        Set<String> seen = new HashSet<>();
        Map<String, Quest> m = new HashMap<>(all);
        m.put(start.id(), start);
        return walk(start.id(), m, seen, new HashSet<>());
    }

    private static boolean walk(String id, Map<String, Quest> m, Set<String> done, Set<String> stack) {
        if (stack.contains(id)) return true;
        if (!done.add(id)) return false;
        stack.add(id);
        Quest q = m.get(id);
        if (q != null) for (String r : q.requires()) if (walk(r, m, done, stack)) return true;
        stack.remove(id);
        return false;
    }

    /** Strips MiniMessage tags for length checks. */
    public static String plain(String s) {
        return s.replaceAll("<[^>]*>", "");
    }
}

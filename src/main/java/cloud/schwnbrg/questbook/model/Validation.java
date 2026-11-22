package cloud.schwnbrg.questbook.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure validation of a quest; world/block checks are done by the caller. */
public final class Validation {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{2,32}");
    public static final int MAX_RULES = 4;
    public static final int MAX_VARIANTS = 4;
    public static final int MAX_LABEL = 48;

    /** Text limits, from config.yml {@code limits:} (defaults are the limits of 1.1). */
    public record Limits(int summary, int hint, int line, int linesPerStep, int steps) {
        public static final Limits DEFAULT = new Limits(1000, 200, 256, 30, 50);
    }

    private static volatile Limits limits = Limits.DEFAULT;

    public static Limits limits() { return limits; }

    public static void setLimits(Limits l) { limits = l == null ? Limits.DEFAULT : l; }

    private Validation() {}

    public static List<String> errors(Quest q, Map<String, Quest> all, boolean forPublish) {
        Limits lim = limits;
        List<String> e = new ArrayList<>();
        if (q.id() == null || !ID.matcher(q.id()).matches()) e.add("id must match [a-z0-9_-]{2,32}");
        if (q.title() == null || q.title().isBlank()) e.add("title is blank");
        else if (plain(q.title()).length() > 48) e.add("title longer than 48 chars");
        if (q.summary() != null && plain(q.summary()).length() > lim.summary()) e.add("summary longer than " + lim.summary() + " chars");
        if (q.steps().size() > lim.steps()) e.add("more than " + lim.steps() + " steps");
        if (forPublish && q.steps().isEmpty()) e.add("no steps");
        for (int i = 0; i < q.steps().size(); i++) stepErrors(q, q.steps().get(i), i + 1, all, forPublish, lim, e);
        for (String r : q.requires()) {
            if (r.equals(q.id())) e.add("requires itself");
            else if (all != null && !all.containsKey(r)) e.add("requires unknown quest '" + r + "'");
        }
        if (all != null && hasCycle(q, all)) e.add("requires cycle");
        if (q.repeatable() && q.repeatCooldownSeconds() < 0) e.add("negative cooldown");
        if (forPublish && q.startSign() == null) e.add("no start sign");
        return e;
    }

    private static void stepErrors(Quest q, Step s, int n, Map<String, Quest> all, boolean forPublish, Limits lim, List<String> e) {
        String p = "step " + n + ": ";
        if (s.text() == null || s.text().stream().allMatch(String::isBlank)) e.add(p + "needs at least one text line");
        else textErrors(s.text(), p, "", lim, e);
        if (s.after().size() > lim.linesPerStep()) e.add(p + "after text has more than " + lim.linesPerStep() + " lines");
        for (String l : s.after()) if (l.length() > lim.line()) { e.add(p + "after text line longer than " + lim.line() + " chars"); break; }
        if (s.hint() != null && plain(s.hint()).length() > lim.hint()) e.add(p + "hint longer than " + lim.hint() + " chars");
        triggerErrors(s.trigger(), p, forPublish, e);
        if (s.rules().size() > MAX_RULES) e.add(p + "more than " + MAX_RULES + " conditions");
        for (int i = 0; i < s.rules().size(); i++) {
            Rule r = s.rules().get(i);
            String rp = p + "condition " + (i + 1) + ": ";
            condErrors(q, r.cond(), rp, all, e);
            targetErrors(q, r.target(), rp, e);
        }
        if (s.next() != null) targetErrors(q, s.next(), p + "next step: ", e);
        if (s.textIf().size() > MAX_VARIANTS) e.add(p + "more than " + MAX_VARIANTS + " text variants");
        for (int i = 0; i < s.textIf().size(); i++) {
            TextVariant v = s.textIf().get(i);
            String vp = p + "text variant " + (i + 1) + ": ";
            condErrors(q, v.cond(), vp, all, e);
            if (v.text().stream().allMatch(String::isBlank)) e.add(vp + "needs at least one text line");
            else textErrors(v.text(), vp, "", lim, e);
        }
    }

    private static void textErrors(List<String> text, String p, String what, Limits lim, List<String> e) {
        if (text.size() > lim.linesPerStep()) e.add(p + what + "more than " + lim.linesPerStep() + " lines");
        for (String l : text) if (l.length() > lim.line()) { e.add(p + what + "line longer than " + lim.line() + " chars"); break; }
    }

    private static void triggerErrors(Trigger t, String p, boolean forPublish, List<String> e) {
        switch (t) {
            case Trigger.Reach r -> { if (r.radius() < 1 || r.radius() > 64) e.add(p + "radius must be 1..64"); }
            case Trigger.Region r -> { if (r.height() < 1 || r.height() > 128) e.add(p + "height must be 1..128"); }
            case Trigger.SignClick x -> { if (x.sign() == null) e.add(p + "no sign picked"); }
            case Trigger.Interact x -> { if (x.block() == null) e.add(p + "no block picked"); }
            case Trigger.Answer a -> { if (a.answers().isEmpty() || a.answers().size() > 10) e.add(p + "needs 1..10 answers"); }
            case Trigger.Kill k -> {
                if (k.amount() < 1 || k.amount() > 1000) e.add(p + "kill amount must be 1..1000");
                if (!Trigger.Kill.validTarget(k.target())) e.add(p + "unknown kill target '" + k.target() + "'");
            }
            case Trigger.HaveItem h -> {
                if (!validItem(h.item())) e.add(p + "unknown item '" + h.item() + "'");
                if (h.amount() < 1 || h.amount() > 2304) e.add(p + "item amount must be 1..2304");
            }
            case Trigger.Place pl -> {
                if (!validBlock(pl.block())) e.add(p + "'" + pl.block() + "' is not a block that can be placed");
                if (pl.amount() < 1 || pl.amount() > 1000) e.add(p + "place amount must be 1..1000");
                if (pl.area() != null) {
                    Trigger.Region a = pl.area();
                    if (a.height() < 1 || a.height() > 128) e.add(p + "area height must be 1..128");
                    long spots = (long) (Math.abs(a.x2() - a.x1()) + 1) * (Math.abs(a.z2() - a.z1()) + 1) * Math.max(1, a.height());
                    if (spots < pl.amount()) e.add(p + "the area has room for only " + spots + " blocks, but " + pl.amount() + " are needed");
                }
            }
            case Trigger.Choice c -> {
                if (c.options().size() > 4) e.add(p + "more than 4 buttons");
                if (forPublish && c.options().size() < 2) e.add(p + "a choice needs at least 2 buttons");
                for (Trigger.Option o : c.options()) {
                    if (o.label().isBlank()) e.add(p + "a button has no text");
                    else if (plain(o.label()).length() > MAX_LABEL) e.add(p + "button text longer than " + MAX_LABEL + " chars");
                }
            }
            case Trigger.FirstOf f -> {
                if (f.options().size() > 3) e.add(p + "more than 3 world choice options");
                if (forPublish && f.options().size() < 2) e.add(p + "a world choice needs at least 2 options");
                for (int i = 0; i < f.options().size(); i++) {
                    Trigger.Branch b = f.options().get(i);
                    String op = p + "option " + (i + 1) + ": ";
                    if (b.label().isBlank()) e.add(op + "has no name");
                    else if (plain(b.label()).length() > MAX_LABEL) e.add(op + "name longer than " + MAX_LABEL + " chars");
                    if (!Trigger.WORLD_TYPES.contains(b.trigger().type())) e.add(op + b.trigger().type() + " cannot be a world choice option");
                    else triggerErrors(b.trigger(), op, forPublish, e);
                }
            }
            default -> { }
        }
    }

    /** An item a player can hold, by lowercase key ("wheat") or any name Material.matchMaterial accepts. */
    public static boolean validItem(String item) {
        org.bukkit.Material m = item == null ? null : org.bukkit.Material.matchMaterial(item);
        return m != null && registry(() -> !m.isAir() && m.isItem(), !m.name().endsWith("AIR"));
    }

    /** An item that places a block. */
    public static boolean validBlock(String block) {
        org.bukkit.Material m = block == null ? null : org.bukkit.Material.matchMaterial(block);
        if (!validItem(block)) return false;
        return placesAs(m) || registry(m::isBlock, true);
    }

    /**
     * isItem/isBlock read Paper's registry, which only exists on a running server. Outside one (unit tests) the
     * {@code offline} answer is used, so loading and validating quest files still works there.
     */
    private static boolean registry(java.util.function.BooleanSupplier check, boolean offline) {
        try {
            return check.getAsBoolean();
        } catch (IllegalStateException | LinkageError noServer) {
            return offline;
        }
    }

    /** Items that are not blocks themselves but place one (torch → wall torch is a block anyway; redstone, string, seeds). */
    private static boolean placesAs(org.bukkit.Material m) {
        return switch (m.name()) {
            case "REDSTONE", "STRING", "WHEAT_SEEDS", "BEETROOT_SEEDS", "MELON_SEEDS", "PUMPKIN_SEEDS", "TORCHFLOWER_SEEDS",
                 "PITCHER_POD", "CARROT", "POTATO", "SWEET_BERRIES", "GLOW_BERRIES", "COCOA_BEANS" -> true;
            default -> false;
        };
    }

    private static void targetErrors(Quest q, Target t, String p, List<String> e) {
        if (t.isEnd()) {
            if (t.ending() != null && plain(t.ending()).length() > MAX_LABEL) e.add(p + "ending name longer than " + MAX_LABEL + " chars");
        } else if (q.indexOf(t.step()) < 0) {
            e.add(p + "goes to a step that does not exist (" + t.step() + ")");
        }
    }

    private static void condErrors(Quest q, Cond c, String p, Map<String, Quest> all, List<String> e) {
        switch (c) {
            case Cond.Picked pk -> {
                Quest src = q;
                if (pk.otherQuest()) {
                    if (pk.quest().equals(q.id())) src = q;
                    else if (all == null) return;
                    else src = all.get(pk.quest());
                    if (src == null) {
                        e.add(p + "checks unknown quest '" + pk.quest() + "'");
                        return;
                    }
                }
                Step s = src.step(pk.step());
                if (s == null) {
                    e.add(p + "checks a step that does not exist (" + pk.step() + ")");
                } else if (!pickable(s.trigger(), pk.value())) {
                    e.add(p + "checks a button or answer that does not exist any more");
                }
            }
            case Cond.Ending en -> {
                if (en.ending() == null || en.ending().isBlank()) e.add(p + "checks an ending without a name");
                if (all != null && !en.quest().equals(q.id()) && !all.containsKey(en.quest())) e.add(p + "checks unknown quest '" + en.quest() + "'");
            }
            case Cond.HasItem h -> {
                if (!validItem(h.item())) e.add(p + "unknown item '" + h.item() + "'");
                if (h.amount() < 1 || h.amount() > 2304) e.add(p + "item amount must be 1..2304");
            }
        }
    }

    /** True if {@code value} is something the player can pick at a step with this trigger. */
    public static boolean pickable(Trigger t, String value) {
        return switch (t) {
            case Trigger.Choice c -> c.option(value) != null;
            case Trigger.FirstOf f -> f.option(value) != null;
            case Trigger.Answer a -> a.answers().stream().anyMatch(x -> Trigger.normalize(x).equals(value));
            default -> false;
        };
    }

    // ---------- flow (warnings, not errors) ----------

    /** Indices of every step a step can lead to (rules and fall-through), -1 for the end. */
    public static List<Integer> exits(Quest q, int i) {
        List<Integer> out = new ArrayList<>();
        Step s = q.steps().get(i);
        for (Rule r : s.rules()) out.add(r.target().isEnd() ? -1 : q.indexOf(r.target().step()));
        Target t = q.fallThrough(i);
        out.add(t.isEnd() ? -1 : q.indexOf(t.step()));
        return out;
    }

    /**
     * Things that do not block publishing but are most likely mistakes: steps nothing leads to, steps from which the
     * quest can never end, and a step that runs straight into a step that is meant to be reached by a condition.
     */
    public static List<String> flowWarnings(Quest q) {
        List<String> out = new ArrayList<>();
        int n = q.steps().size();
        if (n == 0) return out;
        boolean[] reach = new boolean[n];
        Deque<Integer> todo = new ArrayDeque<>();
        reach[0] = true;
        todo.add(0);
        while (!todo.isEmpty()) {
            for (int j : exits(q, todo.poll())) {
                if (j >= 0 && !reach[j]) {
                    reach[j] = true;
                    todo.add(j);
                }
            }
        }
        // backwards: which steps can reach the end
        boolean[] ends = new boolean[n];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (ends[i]) continue;
                for (int j : exits(q, i)) {
                    if (j == -1 || (j >= 0 && ends[j])) {
                        ends[i] = true;
                        changed = true;
                        break;
                    }
                }
            }
        }
        Set<Integer> ruleTargets = new HashSet<>();
        Map<Integer, Integer> ruleFrom = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (Rule r : q.steps().get(i).rules()) {
                int j = r.target().isEnd() ? -1 : q.indexOf(r.target().step());
                if (j >= 0) {
                    ruleTargets.add(j);
                    ruleFrom.putIfAbsent(j, i);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (!reach[i]) out.add("step " + (i + 1) + " can never be reached");
            else if (!ends[i]) out.add("step " + (i + 1) + " is in a loop with no way to the end");
        }
        for (int i = 0; i + 1 < n; i++) {
            Step s = q.steps().get(i);
            if (s.next() == null && reach[i] && ruleTargets.contains(i + 1) && ruleFrom.get(i + 1) != i) {
                out.add("step " + (i + 1) + " runs on into step " + (i + 2) + ", which a condition jumps to. Set step " + (i + 1)
                        + "'s Then… if both paths should not meet there");
            }
        }
        return out;
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

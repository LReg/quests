package cloud.schwnbrg.questbook.editor;

import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.Cond;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Target;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Conditions and targets as short plain sentences for editors ("chose “Pocket the gold” at step 3", "step 5"). */
public final class Describe {

    private final Messages m;
    private final Function<String, Quest> quests;

    public Describe(Messages m, Function<String, Quest> quests) {
        this.m = m;
        this.quests = quests;
    }

    private String plain(String key, String... pairs) {
        return PlainTextComponentSerializer.plainText().serialize(m.get(key, pairs));
    }

    private static String strip(String mm) {
        return Validation.plain(mm);
    }

    /** "Step 4 · You climb the stairs…" */
    public String step(Quest q, String uid) {
        int i = q.indexOf(uid);
        if (i < 0) return plain("desc-step-missing");
        return plain("desc-step", "n", String.valueOf(i + 1), "first", first(q.steps().get(i)));
    }

    public static String first(Step s) {
        String f = s.text().isEmpty() ? "" : strip(s.text().get(0));
        return f.length() > 28 ? f.substring(0, 27) + "…" : f;
    }

    /** Null target = the step's default (the following step). */
    public String target(Quest q, int from, Target t) {
        if (t == null) {
            return from + 1 < q.steps().size() ? plain("desc-next-default", "n", String.valueOf(from + 2)) : plain("desc-end-default");
        }
        if (t.isEnd()) return t.ending() == null ? plain("desc-end") : plain("desc-end-named", "ending", strip(t.ending()));
        return step(q, t.step());
    }

    /** The label of what was picked: a button, a world choice option or an answer. */
    public static String pickLabel(Step s, String value) {
        return switch (s.trigger()) {
            case Trigger.Choice c -> c.option(value) == null ? value : strip(c.option(value).label());
            case Trigger.FirstOf f -> f.option(value) == null ? value : strip(f.option(value).label());
            default -> value;
        };
    }

    public String cond(Quest q, Cond c) {
        String s = switch (c) {
            case Cond.Picked pk -> {
                Quest src = pk.otherQuest() ? quests.apply(pk.quest()) : q;
                if (src == null) yield plain("desc-unknown-quest", "quest", pk.quest());
                Step st = src.step(pk.step());
                String label = st == null ? pk.value() : pickLabel(st, pk.value());
                String where = st == null ? "?" : String.valueOf(src.indexOf(pk.step()) + 1);
                String key = st != null && st.trigger() instanceof Trigger.Answer ? "desc-answered" : "desc-picked";
                String base = plain(key, "label", label, "n", where);
                yield pk.otherQuest() && !pk.quest().equals(q.id()) ? plain("desc-in-quest", "what", base, "quest", strip(src.title())) : base;
            }
            case Cond.Ending e -> {
                Quest src = quests.apply(e.quest());
                yield plain("desc-ending", "ending", strip(e.ending()), "quest", src == null ? e.quest() : strip(src.title()));
            }
            case Cond.HasItem h -> plain(h.take() ? "desc-item-take" : "desc-item", "amount", String.valueOf(h.amount()),
                    "item", h.item().replace('_', ' '));
        };
        return c.not() ? plain("desc-not", "what", s) : s;
    }

    /** The colour index a condition uses in the GUI and the map: the button's position, or 4 for anything else. */
    public static int colour(Quest q, Cond c) {
        if (c instanceof Cond.Picked pk && !pk.otherQuest()) {
            Step st = q.step(pk.step());
            if (st != null) {
                if (st.trigger() instanceof Trigger.Choice ch) for (int i = 0; i < ch.options().size(); i++) if (ch.options().get(i).id().equals(pk.value())) return i;
                if (st.trigger() instanceof Trigger.FirstOf f) for (int i = 0; i < f.options().size(); i++) if (f.options().get(i).id().equals(pk.value())) return i;
            }
        }
        return 4;
    }

    public static final Map<Integer, String> COLOURS = Map.of(0, "green", 1, "gold", 2, "aqua", 3, "light_purple", 4, "yellow");
}

package cloud.schwnbrg.questbook.model;

import java.util.List;

/**
 * One step: {@code text} is told when the step starts (before the trigger), {@code after} is told when the trigger
 * has fired (optional, may be empty), then the player moves on.
 *
 * <p>Where to: the {@code rules} are checked top to bottom and the first true one decides; otherwise {@code next};
 * a null {@code next} means the following step (or the end after the last step), which is how every quest from before
 * branching behaves. {@code id} is a hidden, stable id ("s4") so moving steps never breaks a reference; the quest
 * fills it in when it is missing. {@code textIf} holds alternative texts (first true one replaces {@code text}).
 */
public record Step(String id, List<String> text, Trigger trigger, String hint, List<String> after,
                   List<Rule> rules, Target next, List<TextVariant> textIf) {

    public Step {
        id = id == null || id.isBlank() ? null : id;
        text = text == null ? List.of() : List.copyOf(text);
        after = after == null ? List.of() : List.copyOf(after);
        rules = rules == null ? List.of() : List.copyOf(rules);
        textIf = textIf == null ? List.of() : List.copyOf(textIf);
    }

    public Step(List<String> text, Trigger trigger, String hint, List<String> after) {
        this(null, text, trigger, hint, after, List.of(), null, List.of());
    }

    public Step(List<String> text, Trigger trigger, String hint) {
        this(text, trigger, hint, List.of());
    }

    public Step withId(String v) { return new Step(v, text, trigger, hint, after, rules, next, textIf); }
    public Step withText(List<String> t) { return new Step(id, t, trigger, hint, after, rules, next, textIf); }
    public Step withTrigger(Trigger t) { return new Step(id, text, t, hint, after, rules, next, textIf); }
    public Step withHint(String h) { return new Step(id, text, trigger, h, after, rules, next, textIf); }
    public Step withAfter(List<String> a) { return new Step(id, text, trigger, hint, a, rules, next, textIf); }
    public Step withRules(List<Rule> r) { return new Step(id, text, trigger, hint, after, r, next, textIf); }
    public Step withNext(Target n) { return new Step(id, text, trigger, hint, after, rules, n, textIf); }
    public Step withTextIf(List<TextVariant> v) { return new Step(id, text, trigger, hint, after, rules, next, v); }

    /** True if the step does anything beyond "go on to the following step". */
    public boolean branches() { return !rules.isEmpty() || next != null; }
}

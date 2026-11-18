package cloud.schwnbrg.questbook.model;

import java.util.List;

/**
 * One step: {@code text} is told when the step starts (before the trigger), {@code after} is told when the trigger
 * has fired (optional, may be empty), then the next step starts.
 */
public record Step(List<String> text, Trigger trigger, String hint, List<String> after) {

    public Step {
        after = after == null ? List.of() : List.copyOf(after);
    }

    public Step(List<String> text, Trigger trigger, String hint) {
        this(text, trigger, hint, List.of());
    }

    public Step withText(List<String> t) { return new Step(t, trigger, hint, after); }
    public Step withTrigger(Trigger t) { return new Step(text, t, hint, after); }
    public Step withHint(String h) { return new Step(text, trigger, h, after); }
    public Step withAfter(List<String> a) { return new Step(text, trigger, hint, a); }
}

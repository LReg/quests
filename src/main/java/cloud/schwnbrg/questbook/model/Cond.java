package cloud.schwnbrg.questbook.model;

/**
 * Something a condition checks. Editors never type these: the GUI builds them from what exists (buttons of CHOICE
 * steps, accepted answers, other quests, the item in hand). {@code not} flips the result.
 */
public sealed interface Cond permits Cond.Picked, Cond.Ending, Cond.HasItem {

    boolean not();

    /**
     * The player picked {@code value} at step {@code step}: a CHOICE button id, a WORLD CHOICE option id, or (for an
     * ANSWER step) the accepted answer they typed. {@code quest} is empty for this quest, otherwise another quest's id.
     */
    record Picked(String quest, String step, String value, boolean not) implements Cond {
        public Picked {
            quest = quest == null ? "" : quest;
        }

        public Picked(String step, String value, boolean not) {
            this("", step, value, not);
        }

        public boolean otherQuest() { return !quest.isEmpty(); }
    }

    /** The player finished another quest with this ending. */
    record Ending(String quest, String ending, boolean not) implements Cond {}

    /** The player carries at least {@code amount} of {@code item} (a material key such as dried_kelp); {@code take} removes them when the rule is used. */
    record HasItem(String item, int amount, boolean take, boolean not) implements Cond {}

    static Cond negate(Cond c) {
        return switch (c) {
            case Picked p -> new Picked(p.quest(), p.step(), p.value(), !p.not());
            case Ending e -> new Ending(e.quest(), e.ending(), !e.not());
            case HasItem h -> new HasItem(h.item(), h.amount(), h.take(), !h.not());
        };
    }
}

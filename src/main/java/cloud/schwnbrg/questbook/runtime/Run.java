package cloud.schwnbrg.questbook.runtime;

/** A player's current position in one quest. {@code armed} = text delivered, triggers count. */
public final class Run {
    public final String questId;
    public final boolean test;
    public int step;
    /** Kills counted so far in the current KILL step (memory only). */
    public int count;
    public boolean armed;

    public Run(String questId, int step, boolean test) {
        this.questId = questId;
        this.step = step;
        this.test = test;
    }
}

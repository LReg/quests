package cloud.schwnbrg.questbook.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A player's current position in one quest. {@code armed} = text delivered, triggers count. */
public final class Run {
    public final String questId;
    public final boolean test;
    public int step;
    /** Kills counted so far in the current step, per WORLD CHOICE option id ("" for a plain KILL step). Memory only. */
    public final Map<String, Integer> counts = new HashMap<>();
    public boolean armed;
    /** Step id → what the player picked there (button, option or accepted answer). Saved with the progress. */
    public final Map<String, String> choices = new LinkedHashMap<>();
    /** Step ids this run went through, in order. Saved with the progress (for the journal). */
    public final List<String> path = new ArrayList<>();

    public Run(String questId, int step, boolean test) {
        this.questId = questId;
        this.step = step;
        this.test = test;
    }
}

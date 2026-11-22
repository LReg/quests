package cloud.schwnbrg.questbook.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A player's state in one quest. {@code choices} maps step id → what they picked there (button, world choice option or
 * accepted answer) in the order they were picked, {@code path} lists the step ids they went through, both for the
 * current run. {@code ending}, {@code doneChoices} and {@code donePath} belong to their last completion, so a replay
 * neither rewrites the finished story in the journal nor changes what other quests see. All five live in the table
 * progress_extra.
 */
public record Progress(String questId, State state, int step, long startedAt, long updatedAt, int completions, long lastCompletedAt,
                       Map<String, String> choices, List<String> path, String ending,
                       Map<String, String> doneChoices, List<String> donePath) {

    public enum State { ACTIVE, COMPLETED, ABANDONED }

    /** Longest remembered path; a loop that runs for hours must not grow a row forever. */
    public static final int MAX_PATH = 200;

    public Progress {
        choices = ordered(choices);
        path = capped(path);
        doneChoices = ordered(doneChoices);
        donePath = capped(donePath);
    }

    public Progress(String questId, State state, int step, long startedAt, long updatedAt, int completions, long lastCompletedAt,
                    Map<String, String> choices, List<String> path, String ending) {
        this(questId, state, step, startedAt, updatedAt, completions, lastCompletedAt, choices, path, ending, Map.of(), List.of());
    }

    public Progress(String questId, State state, int step, long startedAt, long updatedAt, int completions, long lastCompletedAt) {
        this(questId, state, step, startedAt, updatedAt, completions, lastCompletedAt, Map.of(), List.of(), null);
    }

    private static Map<String, String> ordered(Map<String, String> m) {
        return m == null || m.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    private static List<String> capped(List<String> l) {
        return l == null ? List.of() : List.copyOf(l.size() > MAX_PATH ? l.subList(l.size() - MAX_PATH, l.size()) : l);
    }

    public Progress with(State s, int step, long now) {
        return new Progress(questId, s, step, startedAt, now, completions, lastCompletedAt, choices, path, ending, doneChoices, donePath);
    }

    public Progress withRun(Map<String, String> choices, List<String> path) {
        return new Progress(questId, state, step, startedAt, updatedAt, completions, lastCompletedAt, choices, path, ending, doneChoices, donePath);
    }

    public Progress withEnding(String e) {
        return new Progress(questId, state, step, startedAt, updatedAt, completions, lastCompletedAt, choices, path, e, doneChoices, donePath);
    }

    public Progress withDone(Map<String, String> choices, List<String> path) {
        return new Progress(questId, state, step, startedAt, updatedAt, completions, lastCompletedAt, this.choices, this.path, ending, choices, path);
    }

    /** The choices of the last completed run, or of the current run if the quest was never completed. */
    public Map<String, String> finishedOrCurrentChoices() {
        return completions > 0 ? doneChoices : choices;
    }
}

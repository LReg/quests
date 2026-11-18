package cloud.schwnbrg.questbook.storage;

public record Progress(String questId, State state, int step, long startedAt, long updatedAt, int completions, long lastCompletedAt) {

    public enum State { ACTIVE, COMPLETED, ABANDONED }

    public Progress with(State s, int step, long now) {
        return new Progress(questId, s, step, startedAt, now, completions, lastCompletedAt);
    }
}

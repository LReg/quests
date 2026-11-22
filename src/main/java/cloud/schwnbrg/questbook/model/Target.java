package cloud.schwnbrg.questbook.model;

/**
 * Where a player goes: a step (by its hidden id) or the end of the quest, optionally with an ending name the player
 * reads ("The Thief"). A step whose next target is null goes on to the following step, as quests always did.
 */
public record Target(String step, String ending) {

    public static final String END = "end";

    public Target {
        if (step == null || step.isBlank()) step = END;
        ending = ending == null || ending.isBlank() ? null : ending.strip();
        if (!END.equals(step)) ending = null;
    }

    public static Target step(String uid) { return new Target(uid, null); }

    public static Target end(String ending) { return new Target(END, ending); }

    public boolean isEnd() { return END.equals(step); }
}

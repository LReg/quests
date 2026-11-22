package cloud.schwnbrg.questbook.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-player cache: progress, rumours seen, settings. Main thread only. */
public final class PlayerData {

    public final Map<String, Progress> progress = new HashMap<>();
    public final Set<String> rumoursSeen = new HashSet<>();
    public boolean rumoursMuted;
    /** Quest the compass bar follows (chosen with [Track] in the journal); null = the most recent one. Memory only. */
    public String tracked;
}

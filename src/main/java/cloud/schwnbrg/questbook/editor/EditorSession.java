package cloud.schwnbrg.questbook.editor;

/** Per-editor state: current quest and step, preview toggle, idle timeout. */
public final class EditorSession {
    public String questId;
    public int step = -1;
    public boolean preview;
    public long lastUse = System.currentTimeMillis();
    public long openedAt = System.currentTimeMillis();

    public void touch() { lastUse = System.currentTimeMillis(); }
}

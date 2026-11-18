package cloud.schwnbrg.questbook.model;

import java.util.ArrayList;
import java.util.List;

public record Quest(
        String id,
        String title,
        String summary,
        QuestStatus status,
        BlockRef startSign,
        List<String> requires,
        boolean repeatable,
        int repeatCooldownSeconds,
        String author,
        List<Step> steps) {

    public static Quest draft(String id, String title, String author) {
        return new Quest(id, title, "", QuestStatus.DRAFT, null, List.of(), false, 0, author, List.of());
    }

    public boolean published() { return status == QuestStatus.PUBLISHED; }

    public Quest withTitle(String v) { return new Quest(id, v, summary, status, startSign, requires, repeatable, repeatCooldownSeconds, author, steps); }
    public Quest withSummary(String v) { return new Quest(id, title, v, status, startSign, requires, repeatable, repeatCooldownSeconds, author, steps); }
    public Quest withStatus(QuestStatus v) { return new Quest(id, title, summary, v, startSign, requires, repeatable, repeatCooldownSeconds, author, steps); }
    public Quest withStartSign(BlockRef v) { return new Quest(id, title, summary, status, v, requires, repeatable, repeatCooldownSeconds, author, steps); }
    public Quest withRequires(List<String> v) { return new Quest(id, title, summary, status, startSign, List.copyOf(v), repeatable, repeatCooldownSeconds, author, steps); }
    public Quest withRepeatable(boolean r, int cooldown) { return new Quest(id, title, summary, status, startSign, requires, r, cooldown, author, steps); }
    public Quest withSteps(List<Step> v) { return new Quest(id, title, summary, status, startSign, requires, repeatable, repeatCooldownSeconds, author, List.copyOf(v)); }

    public Quest withStep(int i, Step s) {
        List<Step> l = new ArrayList<>(steps);
        l.set(i, s);
        return withSteps(l);
    }
}

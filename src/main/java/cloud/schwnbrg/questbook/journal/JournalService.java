package cloud.schwnbrg.questbook.journal;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.editor.Describe;
import cloud.schwnbrg.questbook.runtime.GuideService;
import cloud.schwnbrg.questbook.runtime.QuestService;
import cloud.schwnbrg.questbook.runtime.Run;
import cloud.schwnbrg.questbook.storage.PlayerData;
import cloud.schwnbrg.questbook.storage.Progress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Builds the virtual journal book. Nothing is ever placed in the inventory. */
public final class JournalService {

    private static final int FIRST_PAGE_ITEMS = 4;
    private static final int PAGE_ITEMS = 6;

    private final QuestbookPlugin plugin;
    private final QuestService quests;
    private final Messages m;

    public JournalService(QuestbookPlugin plugin, QuestService quests, Messages m) {
        this.plugin = plugin;
        this.quests = quests;
        this.m = m;
    }

    private static String plain(String mm) {
        return PlainTextComponentSerializer.plainText().serialize(Messages.mm(mm));
    }

    public void open(Player p) {
        PlayerData d = quests.data(p);
        if (d == null) {
            p.sendMessage(m.get("loading"));
            return;
        }
        List<Component> pages = new ArrayList<>();
        List<Component> detail = new ArrayList<>();
        List<Quest> active = new ArrayList<>();
        List<Quest> done = new ArrayList<>();
        for (Quest q : quests.quests().values()) {
            Progress pr = d.progress.get(q.id());
            if (pr == null) continue;
            if (pr.state() == Progress.State.ACTIVE) active.add(q);
            // a repeatable quest being replayed keeps its story readable
            if (pr.completions() > 0 && q.published()) done.add(q);
        }
        // The overview may span several pages: page 1 holds the title (2 lines), later pages are all entries.
        // Entries can wrap to two lines, so each page takes at most 6 line-items (page 1: 4 plus the title).
        int itemCount = (active.isEmpty() && done.isEmpty() ? 1 : 0) + (active.isEmpty() ? 0 : 1 + active.size())
                + (done.isEmpty() ? 0 : 1 + done.size()) + 1; // + the rumours toggle
        int overviewPages = itemCount <= FIRST_PAGE_ITEMS ? 1 : 1 + (itemCount - FIRST_PAGE_ITEMS + PAGE_ITEMS - 1) / PAGE_ITEMS;

        // Detail pages follow the overview. Compute them first so the links know their page numbers.
        Map<String, Integer> first = new java.util.HashMap<>();
        int page = overviewPages + 1;
        for (Quest q : active) {
            List<Component> qp = activePages(p, d, q);
            first.put(q.id(), page);
            detail.addAll(qp);
            page += qp.size();
        }
        for (Quest q : done) {
            List<Component> qp = storyPages(p, d, q);
            first.put(q.id(), page);
            detail.addAll(qp);
            page += qp.size();
        }
        List<Component> items = new ArrayList<>();
        if (active.isEmpty() && done.isEmpty()) items.add(m.get("journal-empty"));
        if (!active.isEmpty()) items.add(m.get("journal-active-head"));
        for (Quest q : active) {
            Progress pr = d.progress.get(q.id());
            int shown = Math.min(pr.step(), q.steps().size() - 1) + 1;
            items.add(m.get("journal-entry-active", "title", plain(q.title()), "step", String.valueOf(shown),
                    "total", String.valueOf(q.steps().size())).clickEvent(ClickEvent.changePage(first.get(q.id()))));
        }
        if (!done.isEmpty()) items.add(m.get("journal-done-head"));
        for (Quest q : done) {
            String ending = d.progress.get(q.id()).ending();
            items.add((ending == null ? m.get("journal-entry-done", "title", plain(q.title()))
                    : m.get("journal-entry-done-ending", "title", plain(q.title()), "ending", plain(ending))).clickEvent(ClickEvent.changePage(first.get(q.id()))));
        }
        items.add(d.rumoursMuted
                ? m.get("journal-rumours-off").clickEvent(ClickEvent.runCommand("/quest unmute"))
                : m.get("journal-rumours-on").clickEvent(ClickEvent.runCommand("/quest mute")));

        Component ov = Component.empty().append(m.get("journal-title")).append(Component.newline()).append(Component.newline());
        int onPage = 0;
        int cap = FIRST_PAGE_ITEMS;
        for (int i = 0; i < items.size(); i++) {
            if (onPage >= cap) {
                pages.add(ov);
                ov = Component.empty();
                onPage = 0;
                cap = PAGE_ITEMS;
            }
            ov = ov.append(items.get(i));
            if (i < items.size() - 1) ov = ov.append(Component.newline());
            onPage++;
        }
        pages.add(ov);
        pages.addAll(detail);
        p.openBook(Book.book(Component.text("Journal"), Component.text("Questbook"), pages));
    }

    private List<Component> activePages(Player p, PlayerData d, Quest q) {
        Progress pr = d.progress.get(q.id());
        int idx = Math.min(pr.step(), q.steps().size() - 1);
        Step s = q.steps().get(idx);
        List<Component> out = new ArrayList<>();
        Component head = m.get("journal-quest-head", "title", plain(q.title()), "step", String.valueOf(idx + 1), "total", String.valueOf(q.steps().size()));
        if (!q.published()) {
            out.add(head.append(Component.newline()).append(m.get("journal-rewritten")));
            return out;
        }
        Run r = quests.realRun(p, q.id());
        List<String> body = new ArrayList<>();
        for (String l : r == null ? s.text() : quests.textFor(p, r, s)) body.add(plain(l));
        // what the player chose so far in this run
        List<String> chose = choiceLines(q, r == null ? pr.choices() : r.choices);
        if (!chose.isEmpty()) {
            body.add("");
            body.addAll(chose);
        }
        // where to go, for quests whose editor turned the guide on
        if (q.guide().particles() || q.guide().compass()) {
            Location target = GuideService.nearest(p, GuideService.targets(s.trigger()));
            if (target != null) {
                body.add("");
                body.add(plain(m.raw("journal-direction", "where", plugin.guide().direction(p, target))));
            }
        }
        // the heading (title may wrap to 2 lines) plus a blank line take 3 lines of the first page
        List<List<String>> chunks = BookPaginator.paginate(body, BookPaginator.LINES - 3);

        boolean hasHint = s.hint() != null && !s.hint().isBlank();
        boolean armed = r != null && r.armed;
        boolean cont = s.trigger() instanceof Trigger.Continue && armed;
        List<Trigger.Option> buttons = armed && s.trigger() instanceof Trigger.Choice c ? c.options() : List.of();
        boolean track = q.guide().compass() && !q.id().equals(d.tracked) && !GuideService.targets(s.trigger()).isEmpty();
        // trailer: blank + hint lines + blank + [Continue] / one line per button + [Track] + [Abandon]
        int trailer = 1 + (hasHint ? BookPaginator.wrap("Hint: " + plain(s.hint())).size() + 1 : 0) + (cont ? 1 : 0) + buttons.size()
                + (track ? 1 : 0) + 1;
        boolean trailerOwnPage = chunks.get(chunks.size() - 1).size() + trailer > BookPaginator.LINES
                || (chunks.size() == 1 && chunks.get(0).size() + 3 + trailer > BookPaginator.LINES);

        for (int i = 0; i < chunks.size(); i++) {
            Component c = i == 0 ? head.append(Component.newline()).append(Component.newline()) : Component.empty();
            c = c.append(Component.text(String.join("\n", chunks.get(i))));
            if (i == chunks.size() - 1 && !trailerOwnPage) c = c.append(trailer(q, s, hasHint, cont, buttons, track));
            out.add(c);
        }
        if (trailerOwnPage) out.add(trailer(q, s, hasHint, cont, buttons, track));
        return out;
    }

    private Component trailer(Quest q, Step s, boolean hasHint, boolean cont, List<Trigger.Option> buttons, boolean track) {
        Component c = Component.empty();
        if (hasHint) c = c.append(Component.newline()).append(Component.newline()).append(m.get("journal-hint", "hint", plain(s.hint())));
        if (cont) c = c.append(Component.newline()).append(m.get("btn-continue").clickEvent(ClickEvent.runCommand("/quest continue " + q.id())));
        for (Trigger.Option o : buttons) {
            c = c.append(Component.newline()).append(m.get("journal-choice", "label", plain(o.label()))
                    // the step id makes a button on a page left open a no-op once the player is on another step
                    .clickEvent(ClickEvent.runCommand("/quest choose " + q.id() + " " + o.id() + " " + s.id())));
        }
        if (track) c = c.append(Component.newline()).append(m.get("btn-track").clickEvent(ClickEvent.runCommand("/quest track " + q.id())));
        return c.append(Component.newline()).append(m.get("btn-abandon").clickEvent(ClickEvent.runCommand("/quest abandon " + q.id())));
    }

    /** "» You chose: Pocket the gold", one per remembered pick, in the order they were made. */
    private List<String> choiceLines(Quest q, Map<String, String> choices) {
        List<String> out = new ArrayList<>();
        for (var e : choices.entrySet()) {
            Step st = q.step(e.getKey());
            if (st == null || !(st.trigger() instanceof Trigger.Choice || st.trigger() instanceof Trigger.FirstOf)) continue;
            out.add(plain(m.raw("journal-chose", "label", Describe.pickLabel(st, e.getValue()))));
        }
        return out;
    }

    /**
     * A finished story: the steps the player actually went through (the whole quest for completions from before 1.2,
     * which have no path), with their choices and the ending.
     */
    private List<Component> storyPages(Player p, PlayerData d, Quest q) {
        Progress pr = d.progress.get(q.id());
        Run memory = new Run(q.id(), 0, false); // only for picking the text variants the player saw
        // the completed run, not a replay that may be under way
        Map<String, String> chosen = pr.doneChoices();
        memory.choices.putAll(chosen);
        List<Step> steps = new ArrayList<>();
        for (String uid : pr.donePath()) {
            Step st = q.step(uid);
            if (st != null) steps.add(st);
        }
        if (steps.isEmpty()) steps = q.steps();
        List<String> body = new ArrayList<>();
        body.add(plain(q.title()));
        body.add("");
        for (Step s : steps) {
            for (String l : quests.textFor(p, memory, s)) body.add(plain(l));
            String picked = chosen.get(s.id());
            if (picked != null && (s.trigger() instanceof Trigger.Choice || s.trigger() instanceof Trigger.FirstOf)) {
                body.add(plain(m.raw("journal-chose", "label", Describe.pickLabel(s, picked))));
            }
            for (String l : s.after()) body.add(plain(l));
            body.add("");
        }
        if (pr.ending() != null) body.add(plain(m.raw("journal-ending", "ending", pr.ending())));
        body.add(plain(m.raw("journal-completed", "date", new SimpleDateFormat("yyyy-MM-dd").format(new Date(pr.lastCompletedAt())))));
        if (q.repeatable()) body.add(plain(m.raw("journal-times", "count", String.valueOf(pr.completions()))));
        String first = plugin.chronicleName(q.id());
        if (first != null) body.add(plain(m.raw("journal-first", "player", first)));
        List<Component> out = new ArrayList<>();
        for (List<String> pg : BookPaginator.paginate(body)) out.add(Component.text(String.join("\n", pg)));
        return out;
    }
}

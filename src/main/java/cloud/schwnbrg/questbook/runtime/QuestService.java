package cloud.schwnbrg.questbook.runtime;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.Cond;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Rule;
import cloud.schwnbrg.questbook.model.Target;
import cloud.schwnbrg.questbook.model.TextVariant;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.storage.Database;
import cloud.schwnbrg.questbook.storage.PlayerData;
import cloud.schwnbrg.questbook.storage.Progress;
import cloud.schwnbrg.questbook.text.TextDelivery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The quest state machine: acceptance, step delivery, arming and trigger evaluation. Main thread only. */
public final class QuestService {

    /** Why a quest cannot be accepted, as a message key plus placeholder pairs. */
    public record Denial(String key, String... pairs) {}

    private final QuestbookPlugin plugin;
    private final Database db;
    private final Messages m;
    private final TextDelivery delivery;

    private Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();
    private final Map<UUID, Map<String, Run>> runs = new HashMap<>();
    private final Map<UUID, Run> tests = new HashMap<>();
    private final Map<UUID, Long> lastInteract = new HashMap<>();
    private final Map<UUID, Map<String, Long>> abandonAsk = new HashMap<>();

    public QuestService(QuestbookPlugin plugin, Database db, Messages m, TextDelivery delivery) {
        this.plugin = plugin;
        this.db = db;
        this.m = m;
        this.delivery = delivery;
    }

    // ---------- quests ----------

    public void setQuests(Map<String, Quest> q) { this.quests = new LinkedHashMap<>(q); }
    public Map<String, Quest> quests() { return quests; }
    public Quest quest(String id) { return quests.get(id); }

    /** Replaces one quest in memory (after the editor saved it). */
    public void put(Quest q) { quests.put(q.id(), q); }
    public void remove(String id) { quests.remove(id); }

    // ---------- player lifecycle ----------

    public PlayerData data(Player p) { return data.get(p.getUniqueId()); }
    public boolean isReady(Player p) { return data.containsKey(p.getUniqueId()); }

    public void join(Player p) {
        UUID id = p.getUniqueId();
        loading.add(id);
        db.loadPlayer(id).thenAccept(d -> Bukkit.getScheduler().runTask(plugin, () -> {
            loading.remove(id);
            if (!p.isOnline()) return;
            data.put(id, d);
            Map<String, Run> mine = new HashMap<>();
            runs.put(id, mine);
            for (Progress pr : List.copyOf(d.progress.values())) {
                if (pr.state() != Progress.State.ACTIVE) continue;
                Quest q = quests.get(pr.questId());
                if (q == null || q.steps().isEmpty()) continue;
                // the quest was edited while the player was away: the step they were on is found by its id, the
                // index alone could now point at another step; a deleted step falls back to the old index, kept in range
                int at = pr.path().isEmpty() ? -1 : q.indexOf(pr.path().getLast());
                if (at < 0) at = Math.min(pr.step(), q.steps().size() - 1);
                if (at != pr.step()) {
                    pr = pr.with(Progress.State.ACTIVE, at, System.currentTimeMillis());
                    d.progress.put(q.id(), pr);
                    db.saveProgress(id, pr);
                }
                Run r = new Run(q.id(), pr.step(), false);
                r.choices.putAll(pr.choices());
                r.path.addAll(pr.path());
                // progress from before 1.2 has no path; quests were linear then, so it is steps 1..current
                if (r.path.isEmpty()) for (int i = 0; i <= pr.step(); i++) r.path.add(q.steps().get(i).id());
                mine.put(q.id(), r);
                if (q.published()) startStep(p, r);
            }
            plugin.signs().refreshAll(p);
            plugin.advancements().syncJoin(p);
        }));
    }

    public void quit(Player p) {
        UUID id = p.getUniqueId();
        delivery.cancel(id);
        forgetPlayer(id);
        plugin.signs().forget(id);
        plugin.guide().quit(p);
    }

    /** Every per-player map in one place, so a new one cannot be forgotten on quit. */
    private void forgetPlayer(UUID id) {
        data.remove(id);
        runs.remove(id);
        tests.remove(id);
        loading.remove(id);
        lastInteract.remove(id);
        abandonAsk.remove(id);
    }

    // ---------- state queries ----------

    public SignState stateFor(Player p, Quest q) {
        if (!q.published()) return SignState.DRAFT;
        PlayerData d = data(p);
        if (d == null) return SignState.DRAFT;
        Progress pr = d.progress.get(q.id());
        if (pr != null && pr.state() == Progress.State.ACTIVE) return SignState.ACTIVE;
        for (String r : q.requires()) if (!meets(d, q, r)) return SignState.LOCKED;
        if (pr != null && pr.state() == Progress.State.COMPLETED) {
            return q.repeatable() ? SignState.COMPLETED_REPEATABLE : SignState.COMPLETED;
        }
        return SignState.AVAILABLE;
    }

    private static boolean completed(PlayerData d, String id) {
        Progress pr = d.progress.get(id);
        return pr != null && pr.completions() > 0;
    }

    /** The requirement {@code req} of quest q is met: finished, and with the right ending if q asks for one. */
    private static boolean meets(PlayerData d, Quest q, String req) {
        if (!completed(d, req)) return false;
        String want = q.requireEndings().get(req);
        return want == null || want.equals(d.progress.get(req).ending());
    }

    public Denial canAccept(Player p, Quest q) {
        PlayerData d = data(p);
        if (d == null || !q.published()) return new Denial("not-available");
        for (String r : q.requires()) {
            if (!meets(d, q, r)) {
                Quest rq = quests.get(r);
                String title = rq == null ? r : rq.title();
                return completed(d, r) ? new Denial("locked-ending", "quest", title) : new Denial("locked", "quest", title);
            }
        }
        Progress pr = d.progress.get(q.id());
        if (pr != null && pr.state() == Progress.State.ACTIVE) {
            return new Denial("already-active", "step", String.valueOf(pr.step() + 1));
        }
        if (pr != null && pr.state() == Progress.State.COMPLETED) {
            if (!q.repeatable()) return new Denial("already-done");
            long left = pr.lastCompletedAt() + q.repeatCooldownSeconds() * 1000L - System.currentTimeMillis();
            if (left > 0) return new Denial("cooldown", "time", formatDuration(left));
        }
        // rows of quests that no longer exist must not use up slots
        long active = d.progress.values().stream().filter(x -> x.state() == Progress.State.ACTIVE && quests.containsKey(x.questId())).count();
        if (active >= plugin.getConfig().getInt("max_active_quests", 3)) return new Denial("too-many-active");
        return null;
    }

    public static String formatDuration(long ms) {
        Duration d = Duration.ofMillis(Math.max(0, ms));
        if (d.toDays() > 0) return d.toDays() + "d " + d.toHoursPart() + "h";
        if (d.toHours() > 0) return d.toHours() + "h " + d.toMinutesPart() + "m";
        if (d.toMinutes() > 0) return d.toMinutes() + "m " + d.toSecondsPart() + "s";
        return d.toSeconds() + "s";
    }

    // ---------- accept / abandon ----------

    /** Accepts the quest as it is now, not as it was when an intro screen was built. */
    public boolean acceptById(Player p, String questId) {
        Quest q = quests.get(questId);
        if (q == null) {
            p.sendMessage(m.get("not-available"));
            return false;
        }
        return accept(p, q);
    }

    public boolean accept(Player p, Quest q) {
        Denial den = canAccept(p, q);
        if (den != null) {
            p.sendMessage(m.get(den.key(), den.pairs()));
            return false;
        }
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        Progress old = d.progress.get(q.id());
        // a replay starts with no choices; the last completion (ending, choices, path) stays until a new one replaces it
        Progress pr = new Progress(q.id(), Progress.State.ACTIVE, 0, now, now, old == null ? 0 : old.completions(),
                old == null ? 0 : old.lastCompletedAt(), Map.of(), List.of(q.steps().get(0).id()), old == null ? null : old.ending(),
                old == null ? Map.of() : old.doneChoices(), old == null ? List.of() : old.donePath());
        d.progress.put(q.id(), pr);
        db.saveProgress(p.getUniqueId(), pr);
        db.addEvent(p.getUniqueId(), q.id(), "START", 0);
        plugin.export().send("START", q, p, 0);
        Run r = new Run(q.id(), 0, false);
        r.path.add(q.steps().get(0).id());
        runs.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).put(q.id(), r);
        p.showTitle(Title.title(m.get("quest-started-title"), m.get("quest-started-subtitle", "title", q.title())));
        plugin.signs().refresh(p, q.id());
        startStep(p, r);
        return true;
    }

    /** Asks for confirmation; the second call within 30 s abandons. */
    public void abandon(Player p, String questId, boolean confirmed) {
        PlayerData d = data(p);
        Progress pr = d == null ? null : d.progress.get(questId);
        Quest q = quests.get(questId);
        if (pr == null || pr.state() != Progress.State.ACTIVE || q == null) {
            p.sendMessage(m.get("not-active"));
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Long> asks = abandonAsk.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        if (!confirmed) {
            asks.put(questId, now);
            p.sendMessage(m.get("abandon-confirm", "quest", q.title())
                    .clickEvent(ClickEvent.runCommand("/quest abandon " + questId + " confirm")));
            return;
        }
        Long asked = asks.get(questId);
        if (asked == null || now - asked > 30_000) {
            abandon(p, questId, false);
            return;
        }
        asks.remove(questId);
        Progress np = pr.with(Progress.State.ABANDONED, 0, now);
        d.progress.put(questId, np);
        db.saveProgress(p.getUniqueId(), np);
        db.addEvent(p.getUniqueId(), questId, "ABANDON", pr.step());
        plugin.export().send("ABANDON", q, p, pr.step());
        dropRun(p.getUniqueId(), questId);
        p.sendMessage(m.get("abandoned", "quest", q.title()));
        plugin.signs().refresh(p, questId);
    }

    /** Deletes a player's progress (admin reset). */
    public void reset(UUID id, String questId) {
        db.deleteProgress(id, questId);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            PlayerData d = data(p);
            if (d != null) d.progress.remove(questId);
            dropRun(id, questId);
            plugin.signs().refresh(p, questId);
        }
    }

    /** Removes a player's run of a quest and stops its text delivery. */
    private void dropRun(UUID id, String questId) {
        Map<String, Run> mine = runs.get(id);
        Run gone = mine == null ? null : mine.remove(questId);
        if (gone != null) delivery.cancel(id, gone);
    }

    // ---------- test mode ----------

    public boolean inTest(Player p) { return tests.containsKey(p.getUniqueId()); }

    public void startTest(Player p, Quest q) {
        if (q.steps().isEmpty()) {
            p.sendMessage(m.get("editor-no-steps"));
            return;
        }
        Run old = tests.remove(p.getUniqueId());
        if (old != null) delivery.cancel(p.getUniqueId(), old);
        Run r = new Run(q.id(), 0, true);
        r.path.add(q.steps().get(0).id());
        tests.put(p.getUniqueId(), r);
        p.sendMessage(m.get("test-prefix").append(m.get("test-started", "quest", q.title())));
        startStep(p, r);
    }

    public void stopTest(Player p) {
        Run t = tests.remove(p.getUniqueId());
        if (t != null) {
            delivery.cancel(p.getUniqueId(), t);
            p.sendMessage(m.get("test-prefix").append(m.get("test-stopped")));
        }
    }

    /**
     * Test runs: finishes the current step. On a CHOICE or WORLD CHOICE step {@code option} (1-based) says which
     * button/option counts as picked; without it the first one is used.
     */
    public void skip(Player p, Integer option) {
        Run r = tests.get(p.getUniqueId());
        if (r == null) {
            p.sendMessage(m.get("test-none"));
            return;
        }
        Quest q = quests.get(r.questId);
        if (q == null || r.step >= q.steps().size()) return;
        delivery.cancel(p.getUniqueId(), r);
        List<String> ids = optionIds(q.steps().get(r.step).trigger());
        String picked = null;
        if (!ids.isEmpty()) {
            int i = option == null ? 0 : Math.max(0, Math.min(ids.size() - 1, option - 1));
            picked = ids.get(i);
            p.sendMessage(m.get("test-prefix").append(m.get("test-picked", "option", String.valueOf(i + 1))));
        }
        r.armed = true; // a step skipped while its text was still being told
        completeStep(p, r, picked);
    }

    /** Option ids of a CHOICE or WORLD CHOICE trigger, empty for any other trigger. */
    private static List<String> optionIds(Trigger t) {
        return switch (t) {
            case Trigger.Choice c -> c.options().stream().map(Trigger.Option::id).toList();
            case Trigger.FirstOf f -> f.options().stream().map(Trigger.Branch::id).toList();
            default -> List.of();
        };
    }

    // ---------- steps ----------

    public List<Run> runsOf(Player p) {
        List<Run> out = new ArrayList<>();
        Run t = tests.get(p.getUniqueId());
        if (t != null) out.add(t);
        Map<String, Run> mine = runs.get(p.getUniqueId());
        if (mine != null) {
            for (Run r : mine.values()) {
                if (t != null && t.questId.equals(r.questId)) continue;
                Quest q = quests.get(r.questId);
                if (q != null && q.published()) out.add(r);
            }
        }
        return out;
    }

    public Run realRun(Player p, String questId) {
        Map<String, Run> mine = runs.get(p.getUniqueId());
        return mine == null ? null : mine.get(questId);
    }

    private boolean live(Player p, Run r) {
        if (r.test) return tests.get(p.getUniqueId()) == r;
        Map<String, Run> mine = runs.get(p.getUniqueId());
        return mine != null && mine.get(r.questId) == r;
    }

    private Component prefixed(Run r, Component c) {
        return r.test ? m.get("test-prefix").append(c) : c;
    }

    /** MiniMessage text lines of a step as components (empty line stays empty), with the test prefix for test runs. */
    private List<Component> lines(Run r, List<String> texts) {
        List<Component> out = new ArrayList<>();
        for (String l : texts) out.add(prefixed(r, l.isEmpty() ? Component.empty() : Messages.mm(l)));
        return out;
    }

    /** The step of a run whose trigger is armed (text delivered), or null if the run is not waiting for its trigger. */
    private Step armedStep(Run r) {
        Quest q = quests.get(r.questId);
        if (q == null || !r.armed || r.step >= q.steps().size()) return null;
        return q.steps().get(r.step);
    }

    /** The text a step tells this player: the first text variant whose condition is true, else the normal text. */
    public List<String> textFor(Player p, Run r, Step s) {
        for (TextVariant v : s.textIf()) if (test(p, r, v.cond())) return v.text();
        return s.text();
    }

    private void startStep(Player p, Run r) {
        Quest q = quests.get(r.questId);
        if (q == null || r.step >= q.steps().size()) return;
        r.armed = false;
        r.counts.clear();
        r.placed.clear();
        delivery.cancel(p.getUniqueId(), r); // a re-delivery (republish) must not double up
        Step s = q.steps().get(r.step);
        List<Component> lines = lines(r, textFor(p, r, s));
        Component footer = footer(p.getUniqueId(), r, s);
        if (footer != null) lines.add(footer);
        int step = r.step;
        delivery.deliver(p, r, lines, () -> {
            if (live(p, r) && r.step == step) arm(p, r);
        });
    }

    /** Buttons hold the player's id, not the object (stale after a relog), and the step they belong to. */
    private Component footer(UUID id, Run r, Step s) {
        Component f = Component.empty();
        boolean any = false;
        int step = r.step;
        if (s.trigger() instanceof Trigger.Continue) {
            f = f.append(button("btn-continue", () -> {
                Player cur = Bukkit.getPlayer(id);
                if (cur != null) continueStep(cur, r.questId, step);
            }));
            any = true;
        } else if (s.trigger() instanceof Trigger.Answer) {
            f = f.append(m.get("btn-answer").clickEvent(ClickEvent.suggestCommand("/quest answer ")));
            any = true;
        } else if (s.trigger() instanceof Trigger.Choice c) {
            for (Trigger.Option o : c.options()) {
                if (any) f = f.append(Component.text(" "));
                f = f.append(m.get("btn-choice", "label", o.label()).clickEvent(ClickEvent.callback(a -> {
                    Player cur = Bukkit.getPlayer(id);
                    if (cur != null) choose(cur, r.questId, step, o.id());
                    // a choice can wait for a long time (AFK); the journal has the same buttons without a time limit
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofHours(24)).build())));
                any = true;
            }
        }
        if (s.hint() != null && !s.hint().isBlank()) {
            if (any) f = f.append(Component.text(" "));
            f = f.append(button("btn-hint", () -> {
                Player cur = Bukkit.getPlayer(id);
                if (cur != null) cur.sendMessage(prefixed(r, m.get("hint-line", "hint", Messages.esc(s.hint()))));
            }));
            any = true;
        }
        return any ? f : null;
    }

    /** A chat button with a single-use callback that expires after one hour. */
    public Component button(String key, Runnable action) {
        return m.get(key).clickEvent(ClickEvent.callback(a -> action.run(),
                ClickCallback.Options.builder().uses(1).lifetime(Duration.ofHours(1)).build()));
    }

    private void arm(Player p, Run r) {
        r.armed = true;
        checkImmediate(p, r);
    }

    /** A step's trigger as (option id, trigger) parts: one part with no option, or one per WORLD CHOICE option. */
    private record Part(String option, Trigger trigger) {}

    private static List<Part> parts(Trigger t) {
        if (t instanceof Trigger.FirstOf f) {
            List<Part> out = new ArrayList<>(f.options().size());
            for (Trigger.Branch b : f.options()) out.add(new Part(b.id(), b.trigger()));
            return out;
        }
        return List.of(new Part(null, t));
    }

    private void checkImmediate(Player p, Run r) {
        Quest q = quests.get(r.questId);
        if (q == null || r.step >= q.steps().size() || p.getGameMode() == GameMode.SPECTATOR) return;
        for (Part part : parts(q.steps().get(r.step).trigger())) {
            Trigger t = part.trigger();
            if (t instanceof Trigger.AdvancementDone a) {
                Advancement adv = Bukkit.getAdvancement(a.key());
                if (adv != null && p.getAdvancementProgress(adv).isDone()) {
                    completeStep(p, r, part.option());
                    return;
                }
            } else if (t instanceof Trigger.HaveItem h) {
                if (carries(p, h)) {
                    completeStep(p, r, part.option());
                    return;
                }
            } else if (matchesLocation(p.getLocation(), t)) {
                completeStep(p, r, part.option());
                return;
            }
        }
    }

    /** True if the player carries enough for a HAVE_ITEM trigger. The plugin never takes or changes items. */
    private static boolean carries(Player p, Trigger.HaveItem h) {
        Material mat = Material.matchMaterial(h.item());
        return mat != null && count(p, mat) >= h.amount();
    }

    private static final Predicate<Trigger> HAVE_ITEM = t -> t instanceof Trigger.HaveItem;

    /**
     * The inventory may have changed (pickup, click, crafting, the once-a-second check): completes the first armed
     * HAVE_ITEM trigger the player now satisfies. One per call; the next check (at most a second later) finishes the others.
     */
    public void onInventory(Player p) {
        if (!eligible(p) || !hasArmed(p, HAVE_ITEM)) return;
        for (Run r : runsOf(p)) {
            Step s = armedStep(r);
            if (s == null) continue;
            for (Part part : parts(s.trigger())) {
                if (part.trigger() instanceof Trigger.HaveItem h && carries(p, h)) {
                    completeStep(p, r, part.option());
                    return;
                }
            }
        }
    }

    /**
     * A player placed a block. {@code placedAs} is the item it was placed from (a wall torch was a torch). Counts towards
     * every armed PLACE trigger it matches; each spot counts once per step.
     */
    public void onPlace(Player p, Block b, Material placedAs) {
        if (!eligible(p) || !hasArmed(p, t -> t instanceof Trigger.Place)) return;
        for (Run r : runsOf(p)) {
            Step s = armedStep(r);
            if (s == null) continue;
            for (Part part : parts(s.trigger())) {
                if (!(part.trigger() instanceof Trigger.Place pl)) continue;
                Material want = Material.matchMaterial(pl.block());
                if (want == null || (want != placedAs && want != b.getType())) continue;
                if (pl.area() != null && !pl.area().contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) continue;
                String key = part.option() == null ? "" : part.option();
                if (!r.placed.add(key + "|" + b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ())) continue;
                int n = r.counts.merge(key, 1, Integer::sum);
                if (n >= pl.amount()) {
                    completeStep(p, r, part.option());
                    break;
                }
                p.sendActionBar(prefixed(r, m.get("place-progress", "count", String.valueOf(n), "total", String.valueOf(pl.amount()))));
            }
        }
    }

    /** Called by the Continue button or /quest continue. Only works on an armed CONTINUE step. */
    public void continueStep(Player p, String questId) {
        continueStep(p, questId, -1);
    }

    /** {@code onlyStep} &gt;= 0 makes an old chat button a no-op once the player has moved past its step. */
    public void continueStep(Player p, String questId, int onlyStep) {
        for (Run r : runsOf(p)) {
            if (!r.questId.equals(questId)) continue;
            Quest q = quests.get(r.questId);
            if (onlyStep >= 0 && r.step != onlyStep) return;
            if (r.armed && q != null && r.step < q.steps().size() && q.steps().get(r.step).trigger() instanceof Trigger.Continue) {
                completeStep(p, r, null);
            }
            return;
        }
    }

    /**
     * A CHOICE button was clicked (chat button, journal or /quest choose). Only counts on an armed CHOICE step that has
     * this option; {@code onlyStep} &gt;= 0 makes an old button a no-op once the player has moved on.
     */
    public boolean choose(Player p, String questId, int onlyStep, String option) {
        for (Run r : runsOf(p)) {
            if (!r.questId.equals(questId)) continue;
            Quest q = quests.get(r.questId);
            if (q == null || r.step >= q.steps().size() || (onlyStep >= 0 && r.step != onlyStep)) return false;
            if (r.armed && q.steps().get(r.step).trigger() instanceof Trigger.Choice c && c.option(option) != null) {
                completeStep(p, r, option);
                return true;
            }
            return false;
        }
        return false;
    }

    // ---------- conditions and routing ----------

    /** True if the condition holds for this player and run. Only looks, never changes anything. */
    public boolean test(Player p, Run r, Cond c) {
        boolean v = switch (c) {
            case Cond.Picked pk -> {
                if (!pk.otherQuest() || pk.quest().equals(r.questId)) yield pk.value().equals(r.choices.get(pk.step()));
                PlayerData d = data(p);
                Progress pr = d == null ? null : d.progress.get(pk.quest());
                // what the player picked in their finished run; a replay in progress does not change it
                yield pr != null && pk.value().equals(pr.finishedOrCurrentChoices().get(pk.step()));
            }
            case Cond.Ending en -> {
                PlayerData d = data(p);
                Progress pr = d == null ? null : d.progress.get(en.quest());
                yield pr != null && pr.completions() > 0 && en.ending().equals(pr.ending());
            }
            case Cond.HasItem h -> {
                Material mat = Material.matchMaterial(h.item());
                yield mat != null && count(p, mat) >= h.amount();
            }
        };
        return c.not() != v;
    }

    private static int count(Player p, Material mat) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType() == mat) n += it.getAmount();
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off.getType() == mat) n += off.getAmount();
        return n;
    }

    /** Where the player goes after step i: the first rule whose condition is true, otherwise the step's next. */
    private Target route(Player p, Run r, Quest q, int i) {
        Step s = q.steps().get(i);
        for (Rule rule : s.rules()) {
            if (!test(p, r, rule.cond())) continue;
            return rule.target();
        }
        return q.fallThrough(i);
    }

    /**
     * The step's trigger fired. {@code picked} is the button, option or accepted answer, remembered for conditions.
     * Tells the after-text, then goes where the step's conditions or next step say (or finishes the quest).
     */
    private void completeStep(Player p, Run r, String picked) {
        Quest q = quests.get(r.questId);
        if (q == null || !live(p, r) || r.step >= q.steps().size()) return;
        r.armed = false;
        playSound(p, "sounds.step_complete");
        int finished = r.step;
        Step done = q.steps().get(finished);
        if (picked != null) {
            // a step met again in a loop: the new pick counts and moves to the end of the order
            r.choices.remove(done.id());
            r.choices.put(done.id(), picked);
        }
        List<Component> after = lines(r, done.after());
        if (!r.test) {
            db.addEvent(p.getUniqueId(), q.id(), "STEP", r.step);
        }
        Target to = route(p, r, q, finished);
        int next = to.isEnd() ? -1 : q.indexOf(to.step());
        if (!to.isEnd() && next < 0) {
            // a target that vanished (hand-edited file): behave like a quest without branching
            next = finished + 1 < q.steps().size() ? finished + 1 : -1;
        }
        if (next < 0) {
            String ending = to.isEnd() ? to.ending() : null;
            // the closing text is told first, then the quest completes (title, sound, advancement)
            if (after.isEmpty()) completeQuest(p, r, q, ending);
            else delivery.deliver(p, r, after, () -> {
                if (live(p, r) && r.step == finished) completeQuest(p, r, q, ending);
            });
            return;
        }
        int target = next;
        r.step = target;
        r.path.add(q.steps().get(target).id());
        if (r.path.size() > Progress.MAX_PATH) r.path.removeFirst();
        if (!r.test) {
            PlayerData d = data(p);
            Progress pr = d.progress.get(q.id());
            Progress np = pr.with(Progress.State.ACTIVE, target, System.currentTimeMillis()).withRun(r.choices, r.path);
            d.progress.put(q.id(), np);
            db.saveProgress(p.getUniqueId(), np);
            plugin.signs().refresh(p, q.id());
        }
        // the "after" text of the finished step comes before the text of the next one
        if (after.isEmpty()) startStep(p, r);
        else delivery.deliver(p, r, after, () -> {
            if (live(p, r) && r.step == target && !r.armed) startStep(p, r);
        });
    }

    private void completeQuest(Player p, Run r, Quest q, String ending) {
        if (r.test) {
            tests.remove(p.getUniqueId());
            p.sendMessage(m.get("test-prefix").append(ending == null ? m.get("test-finished") : m.get("test-finished-ending", "ending", ending)));
            return;
        }
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        Progress pr = d.progress.get(q.id());
        Progress np = new Progress(q.id(), Progress.State.COMPLETED, 0, pr.startedAt(), now, pr.completions() + 1, now, r.choices, r.path, ending,
                r.choices, r.path);
        d.progress.put(q.id(), np);
        db.saveProgress(p.getUniqueId(), np);
        db.addEvent(p.getUniqueId(), q.id(), "COMPLETE", q.steps().size());
        runs.get(p.getUniqueId()).remove(q.id());
        if (q.id().equals(d.tracked)) d.tracked = null;
        Component sub = ending == null ? m.get("quest-complete-subtitle", "title", q.title())
                : m.get("quest-complete-ending-subtitle", "title", q.title(), "ending", ending);
        p.showTitle(Title.title(m.get("quest-complete-title"), sub));
        playSound(p, "sounds.quest_complete");
        plugin.advancements().grant(p, q);
        db.claimChronicle(q.id(), p.getUniqueId(), p.getName()).thenAccept(first -> {
            if (first) Bukkit.getScheduler().runTask(plugin, () -> { plugin.setChronicle(q.id(), p.getName()); Bukkit.broadcast(
                    m.get("chronicle-first", "player", Messages.esc(p.getName()), "title", q.title())); });
        });
        plugin.export().send("COMPLETE", q, p, q.steps().size(), ending);
        plugin.signs().refresh(p, q.id());
        for (Quest other : quests.values()) if (other.requires().contains(q.id())) plugin.signs().refresh(p, other.id());
    }

    private void playSound(Player p, String path) {
        String key = plugin.getConfig().getString(path);
        if (key == null || key.isBlank()) return;
        float vol = (float) plugin.getConfig().getDouble("sounds.volume", 0.6);
        try {
            p.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, vol, 1f));
        } catch (RuntimeException ignored) {
            // invalid sound key in config
        }
    }

    // ---------- triggers ----------

    private boolean eligible(Player p) {
        return p.getGameMode() != GameMode.SPECTATOR && isReady(p);
    }

    private boolean matchesLocation(Location l, Trigger t) {
        String w = l.getWorld().getName();
        return switch (t) {
            case Trigger.Reach r -> r.contains(w, l.getX(), l.getY(), l.getZ());
            case Trigger.Region r -> r.contains(w, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            case Trigger.Dimension d -> l.getWorld().getEnvironment() == d.env();
            default -> false;
        };
    }

    /** Allocation-free check, called on every block move: does the player have an armed step of this kind? */
    private boolean hasArmed(Player p, Predicate<Trigger> kind) {
        UUID id = p.getUniqueId();
        Run t = tests.get(id);
        if (t != null && armedOfKind(t, kind)) return true;
        Map<String, Run> mine = runs.get(id);
        if (mine == null) return false;
        for (Run r : mine.values()) {
            if (r.armed && armedOfKind(r, kind)) return true;
        }
        return false;
    }

    private boolean armedOfKind(Run r, Predicate<Trigger> kind) {
        if (!r.armed) return false;
        Quest q = quests.get(r.questId);
        if (q == null || r.step >= q.steps().size() || (!r.test && !q.published())) return false;
        Trigger t = q.steps().get(r.step).trigger();
        if (t instanceof Trigger.FirstOf f) {
            for (Trigger.Branch b : f.options()) if (kind.test(b.trigger())) return true;
            return false;
        }
        return kind.test(t);
    }

    /** Completes the first armed step one of whose triggers matches. */
    private boolean fire(Player p, Predicate<Trigger> match) {
        if (!eligible(p)) return false;
        for (Run r : runsOf(p)) {
            Step s = armedStep(r);
            if (s == null) continue;
            for (Part part : parts(s.trigger())) {
                if (match.test(part.trigger())) {
                    completeStep(p, r, part.option());
                    return true;
                }
            }
        }
        return false;
    }

    /** Movement, teleport, world change, respawn: location triggers. */
    public void onLocation(Player p) {
        onLocation(p, p.getLocation());
    }

    /** Evaluates against a given position, so a move event can pass its destination without waiting a tick. */
    public void onLocation(Player p, Location at) {
        if (!hasArmed(p, LOCATION_TRIGGER)) return;
        fire(p, t -> matchesLocation(at, t));
    }

    private static final Predicate<Trigger> LOCATION_TRIGGER =
            t -> t instanceof Trigger.Reach || t instanceof Trigger.Region || t instanceof Trigger.Dimension;

    /** A player killed something. Counts towards every armed KILL trigger it matches; the counts are kept per run in memory. */
    public void onKill(Player p, org.bukkit.entity.LivingEntity victim) {
        if (!eligible(p) || victim == p || victim instanceof org.bukkit.entity.ArmorStand) return;
        String type = victim.getType().name();
        boolean animal = victim instanceof org.bukkit.entity.Animals;
        boolean enemy = victim instanceof org.bukkit.entity.Enemy;
        boolean player = victim instanceof Player;
        for (Run r : runsOf(p)) {
            Step s = armedStep(r);
            if (s == null) continue;
            for (Part part : parts(s.trigger())) {
                if (!(part.trigger() instanceof Trigger.Kill k) || !k.matches(type, animal, enemy, player)) continue;
                String key = part.option() == null ? "" : part.option();
                int n = r.counts.merge(key, 1, Integer::sum);
                if (n >= k.amount()) {
                    completeStep(p, r, part.option());
                    break;
                }
                p.sendActionBar(prefixed(r, m.get("kill-progress", "count", String.valueOf(n), "total", String.valueOf(k.amount()))));
            }
        }
    }

    public void onAdvancement(Player p, NamespacedKey key) {
        if (key.getNamespace().equals("questbook")) return;
        fire(p, t -> t instanceof Trigger.AdvancementDone a && a.key().equals(key));
    }

    /** Right click on a sign: true if it was a trigger target for this player (armed or not). */
    public boolean onSignClick(Player p, Block b) {
        return click(p, b, true);
    }

    public boolean onInteract(Player p, Block b) {
        long now = System.currentTimeMillis();
        Long last = lastInteract.get(p.getUniqueId());
        if (last != null && now - last < 500) return false;
        lastInteract.put(p.getUniqueId(), now);
        return click(p, b, false);
    }

    private boolean click(Player p, Block b, boolean sign) {
        if (!eligible(p)) return false;
        boolean target = false;
        for (Run r : runsOf(p)) {
            Quest q = quests.get(r.questId);
            if (q == null || r.step >= q.steps().size()) continue;
            Step s = q.steps().get(r.step);
            for (Part part : parts(s.trigger())) {
                Trigger t = part.trigger();
                boolean hit = sign ? t instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().sameAs(b)
                        : t instanceof Trigger.Interact in && in.block() != null && in.block().sameAs(b);
                if (!hit) continue;
                target = true;
                if (r.armed) {
                    completeStep(p, r, part.option());
                    return true;
                }
                if (s.hint() != null && !s.hint().isBlank()) p.sendMessage(prefixed(r, m.get("hint-line", "hint", Messages.esc(s.hint()))));
                break;
            }
        }
        return target;
    }

    /** /quest answer. Returns false if the player had no armed ANSWER step. The accepted answer is remembered for conditions. */
    public boolean answer(Player p, String text) {
        if (!eligible(p)) return false;
        String norm = Trigger.normalize(text);
        boolean any = false;
        for (Run r : runsOf(p)) {
            Step step = armedStep(r);
            if (step == null) continue;
            if (step.trigger() instanceof Trigger.Answer a) {
                any = true;
                for (String answer : a.answers()) {
                    if (Trigger.normalize(answer).equals(norm)) {
                        completeStep(p, r, norm);
                        return true;
                    }
                }
            }
        }
        if (any) p.sendMessage(m.get("answer-wrong"));
        else p.sendMessage(m.get("answer-none"));
        return false;
    }

    /** True if any quest uses this block as a SIGN trigger (also inside a world choice), used to decide cancelling. */
    public boolean isStepSign(Block b) {
        for (Quest q : quests.values()) {
            for (Step s : q.steps()) {
                for (Trigger t : Trigger.leaves(s.trigger())) if (t instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().sameAs(b)) return true;
            }
        }
        return false;
    }

    public int activePlayers(String questId) {
        int n = 0;
        for (PlayerData d : data.values()) {
            Progress pr = d.progress.get(questId);
            if (pr != null && pr.state() == Progress.State.ACTIVE) n++;
        }
        return n;
    }

    /** After an editor changed a quest: clamp step indices of active runs. */
    public void clampRuns(Quest q) {
        for (Map.Entry<UUID, Map<String, Run>> e : runs.entrySet()) {
            Run r = e.getValue().get(q.id());
            if (r == null) continue;
            if (q.steps().isEmpty()) {
                e.getValue().remove(q.id());
                delivery.cancel(e.getKey(), r);
            } else {
                // steps were inserted, moved or deleted: follow the current step by its id; a deleted one keeps the
                // old index, kept in range
                int at = r.path.isEmpty() ? -1 : q.indexOf(r.path.getLast());
                if (at < 0) at = Math.min(r.step, q.steps().size() - 1);
                if (at == r.step) continue;
                r.step = at;
                if (r.test) continue;
                // keep the stored progress in step with the run
                PlayerData d = data.get(e.getKey());
                Progress pr = d == null ? null : d.progress.get(q.id());
                if (pr != null && pr.state() == Progress.State.ACTIVE) {
                    Progress np = pr.with(Progress.State.ACTIVE, r.step, System.currentTimeMillis());
                    d.progress.put(q.id(), np);
                    db.saveProgress(e.getKey(), np);
                }
            }
        }
    }

    /**
     * After the whole quest set was replaced (/qe reload): drop runs of vanished quests, clamp step indices,
     * stop runs whose quest is a draft now, and restart text only where it makes sense: the run waited for its quest
     * to be published or its current step changed. A run in the middle of an unchanged step is left alone.
     *
     * @param before the quests as they were before the reload
     */
    public void reconcile(Map<String, Quest> before) {
        for (Map.Entry<UUID, Map<String, Run>> e : List.copyOf(runs.entrySet())) {
            for (Run r : List.copyOf(e.getValue().values())) {
                Quest q = quests.get(r.questId);
                if (q == null || q.steps().isEmpty()) {
                    e.getValue().remove(r.questId);
                    delivery.cancel(e.getKey(), r);
                    continue;
                }
                if (!q.published()) {
                    // players must not finish steps of a draft: stop counting, resume when it is published again
                    r.armed = false;
                    delivery.cancel(e.getKey(), r);
                    continue;
                }
                int stepBefore = r.step;
                clampRuns(q);
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && needsRestart(before.get(r.questId), q, r, stepBefore)) startStep(p, r);
            }
        }
        // test runs of vanished quests or of quests without steps would point at nothing
        for (Map.Entry<UUID, Run> e : List.copyOf(tests.entrySet())) {
            Quest q = quests.get(e.getValue().questId);
            if (q == null || q.steps().isEmpty() || e.getValue().step >= q.steps().size()) {
                tests.remove(e.getKey());
                delivery.cancel(e.getKey(), e.getValue());
            }
        }
    }

    /** True if the run's current step has to be told again after a reload. */
    private static boolean needsRestart(Quest before, Quest now, Run r, int stepBefore) {
        if (r.armed) return false;
        if (before == null || !before.published()) return true; // was waiting for publication
        if (stepBefore != r.step) return true; // clamped to another step
        return stepBefore >= before.steps().size() || !before.steps().get(stepBefore).equals(now.steps().get(r.step));
    }

    /** The first required quest this player has not finished yet, or null. For the locked sign text. */
    public Quest firstMissing(Player p, Quest q) {
        PlayerData d = data(p);
        if (d == null) return null;
        for (String r : q.requires()) {
            if (!meets(d, q, r)) return quests.get(r);
        }
        return null;
    }

    /** Re-delivers the current step for players mid-quest after a republish. */
    public void resume(Quest q) {
        for (Map.Entry<UUID, Map<String, Run>> e : runs.entrySet()) {
            Run r = e.getValue().get(q.id());
            Player p = Bukkit.getPlayer(e.getKey());
            if (r != null && p != null && !r.armed && q.published() && !q.steps().isEmpty()) startStep(p, r);
        }
    }
}

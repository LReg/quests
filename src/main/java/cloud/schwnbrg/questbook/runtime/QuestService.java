package cloud.schwnbrg.questbook.runtime;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.Quest;
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
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

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
                if (pr.step() >= q.steps().size()) {
                    // the quest got shorter while the player was away: keep the stored step in range too
                    pr = pr.with(Progress.State.ACTIVE, q.steps().size() - 1, System.currentTimeMillis());
                    d.progress.put(q.id(), pr);
                    db.saveProgress(id, pr);
                }
                Run r = new Run(q.id(), pr.step(), false);
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
        data.remove(id);
        runs.remove(id);
        tests.remove(id);
        loading.remove(id);
        lastInteract.remove(id);
        abandonAsk.remove(id);
        plugin.signs().forget(id);
    }

    // ---------- state queries ----------

    public SignState stateFor(Player p, Quest q) {
        if (!q.published()) return SignState.DRAFT;
        PlayerData d = data(p);
        if (d == null) return SignState.DRAFT;
        Progress pr = d.progress.get(q.id());
        if (pr != null && pr.state() == Progress.State.ACTIVE) return SignState.ACTIVE;
        for (String r : q.requires()) if (!completed(d, r)) return SignState.LOCKED;
        if (pr != null && pr.state() == Progress.State.COMPLETED) {
            return q.repeatable() ? SignState.COMPLETED_REPEATABLE : SignState.COMPLETED;
        }
        return SignState.AVAILABLE;
    }

    private static boolean completed(PlayerData d, String id) {
        Progress pr = d.progress.get(id);
        return pr != null && pr.completions() > 0;
    }

    public Denial canAccept(Player p, Quest q) {
        PlayerData d = data(p);
        if (d == null || !q.published()) return new Denial("not-available");
        for (String r : q.requires()) {
            if (!completed(d, r)) {
                Quest rq = quests.get(r);
                return new Denial("locked", "quest", Messages.esc(rq == null ? r : rq.title()));
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
        Progress pr = new Progress(q.id(), Progress.State.ACTIVE, 0, now, now, old == null ? 0 : old.completions(),
                old == null ? 0 : old.lastCompletedAt());
        d.progress.put(q.id(), pr);
        db.saveProgress(p.getUniqueId(), pr);
        db.addEvent(p.getUniqueId(), q.id(), "START", 0);
        plugin.export().send("START", q, p, 0);
        Run r = new Run(q.id(), 0, false);
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
        Map<String, Run> mine = runs.get(p.getUniqueId());
        Run gone = mine == null ? null : mine.remove(questId);
        if (gone != null) delivery.cancel(p.getUniqueId(), gone);
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
            Map<String, Run> mine = runs.get(id);
            Run gone = mine == null ? null : mine.remove(questId);
            if (gone != null) delivery.cancel(id, gone);
            plugin.signs().refresh(p, questId);
        }
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

    public void skip(Player p) {
        Run r = tests.get(p.getUniqueId());
        if (r == null) {
            p.sendMessage(m.get("test-none"));
            return;
        }
        delivery.cancel(p.getUniqueId(), r);
        completeStep(p, r);
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

    private void startStep(Player p, Run r) {
        Quest q = quests.get(r.questId);
        if (q == null || r.step >= q.steps().size()) return;
        r.armed = false;
        r.count = 0;
        delivery.cancel(p.getUniqueId(), r); // a re-delivery (republish) must not double up
        Step s = q.steps().get(r.step);
        List<Component> lines = new ArrayList<>();
        for (String l : s.text()) lines.add(prefixed(r, l.isEmpty() ? Component.empty() : Messages.mm(l)));
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

    private void checkImmediate(Player p, Run r) {
        Quest q = quests.get(r.questId);
        if (q == null || r.step >= q.steps().size() || p.getGameMode() == GameMode.SPECTATOR) return;
        Trigger t = q.steps().get(r.step).trigger();
        if (t instanceof Trigger.AdvancementDone a) {
            Advancement adv = Bukkit.getAdvancement(a.key());
            if (adv != null && p.getAdvancementProgress(adv).isDone()) completeStep(p, r);
        } else if (matchesLocation(p.getLocation(), t)) {
            completeStep(p, r);
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
                completeStep(p, r);
            }
            return;
        }
    }

    private void completeStep(Player p, Run r) {
        Quest q = quests.get(r.questId);
        if (q == null || !live(p, r)) return;
        r.armed = false;
        playSound(p, "sounds.step_complete");
        int next = r.step + 1;
        int finished = r.step;
        List<Component> after = new ArrayList<>();
        for (String l : q.steps().get(finished).after()) after.add(prefixed(r, l.isEmpty() ? Component.empty() : Messages.mm(l)));
        if (!r.test) {
            db.addEvent(p.getUniqueId(), q.id(), "STEP", r.step);
        }
        if (next >= q.steps().size()) {
            // the closing text is told first, then the quest completes (title, sound, advancement)
            if (after.isEmpty()) completeQuest(p, r, q);
            else delivery.deliver(p, r, after, () -> {
                if (live(p, r) && r.step == finished) completeQuest(p, r, q);
            });
            return;
        }
        r.step = next;
        if (!r.test) {
            PlayerData d = data(p);
            Progress pr = d.progress.get(q.id());
            Progress np = pr.with(Progress.State.ACTIVE, next, System.currentTimeMillis());
            d.progress.put(q.id(), np);
            db.saveProgress(p.getUniqueId(), np);
            plugin.signs().refresh(p, q.id());
        }
        // the "after" text of the finished step comes before the text of the next one
        if (after.isEmpty()) startStep(p, r);
        else delivery.deliver(p, r, after, () -> {
            if (live(p, r) && r.step == next) startStep(p, r);
        });
    }

    private void completeQuest(Player p, Run r, Quest q) {
        if (r.test) {
            tests.remove(p.getUniqueId());
            p.sendMessage(m.get("test-prefix").append(m.get("test-finished")));
            return;
        }
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        Progress pr = d.progress.get(q.id());
        Progress np = new Progress(q.id(), Progress.State.COMPLETED, 0, pr.startedAt(), now, pr.completions() + 1, now);
        d.progress.put(q.id(), np);
        db.saveProgress(p.getUniqueId(), np);
        db.addEvent(p.getUniqueId(), q.id(), "COMPLETE", q.steps().size());
        runs.get(p.getUniqueId()).remove(q.id());
        p.showTitle(Title.title(m.get("quest-complete-title"), m.get("quest-complete-subtitle", "title", q.title())));
        playSound(p, "sounds.quest_complete");
        plugin.advancements().grant(p, q);
        db.claimChronicle(q.id(), p.getUniqueId(), p.getName()).thenAccept(first -> {
            if (first) Bukkit.getScheduler().runTask(plugin, () -> { plugin.setChronicle(q.id(), p.getName()); Bukkit.broadcast(
                    m.get("chronicle-first", "player", Messages.esc(p.getName()), "title", q.title())); });
        });
        plugin.export().send("COMPLETE", q, p, q.steps().size());
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
        return kind.test(q.steps().get(r.step).trigger());
    }

    /** Completes the first armed step whose trigger matches. */
    private boolean fire(Player p, Predicate<Trigger> match) {
        if (!eligible(p)) return false;
        for (Run r : runsOf(p)) {
            Quest q = quests.get(r.questId);
            if (q == null || !r.armed || r.step >= q.steps().size()) continue;
            if (match.test(q.steps().get(r.step).trigger())) {
                completeStep(p, r);
                return true;
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

    /** A player killed something. Counts towards every armed KILL step it matches; the count is kept per run in memory. */
    public void onKill(Player p, org.bukkit.entity.LivingEntity victim) {
        if (!eligible(p) || victim == p || victim instanceof org.bukkit.entity.ArmorStand) return;
        String type = victim.getType().name();
        boolean animal = victim instanceof org.bukkit.entity.Animals;
        boolean enemy = victim instanceof org.bukkit.entity.Enemy;
        boolean player = victim instanceof Player;
        for (Run r : runsOf(p)) {
            Quest q = quests.get(r.questId);
            if (q == null || !r.armed || r.step >= q.steps().size()) continue;
            if (!(q.steps().get(r.step).trigger() instanceof Trigger.Kill k) || !k.matches(type, animal, enemy, player)) continue;
            r.count++;
            if (r.count >= k.amount()) {
                completeStep(p, r);
            } else {
                p.sendActionBar(prefixed(r, m.get("kill-progress", "count", String.valueOf(r.count), "total", String.valueOf(k.amount()))));
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
            boolean hit = sign ? s.trigger() instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().sameAs(b)
                    : s.trigger() instanceof Trigger.Interact in && in.block() != null && in.block().sameAs(b);
            if (!hit) continue;
            target = true;
            if (r.armed) {
                completeStep(p, r);
                return true;
            }
            if (s.hint() != null && !s.hint().isBlank()) p.sendMessage(prefixed(r, m.get("hint-line", "hint", Messages.esc(s.hint()))));
        }
        return target;
    }

    /** /quest answer. Returns false if the player had no armed ANSWER step. */
    public boolean answer(Player p, String text) {
        if (!eligible(p)) return false;
        String norm = Trigger.normalize(text);
        boolean any = false;
        for (Run r : runsOf(p)) {
            Quest q = quests.get(r.questId);
            if (q == null || !r.armed || r.step >= q.steps().size()) continue;
            if (q.steps().get(r.step).trigger() instanceof Trigger.Answer a) {
                any = true;
                for (String s : a.answers()) {
                    if (Trigger.normalize(s).equals(norm)) {
                        completeStep(p, r);
                        return true;
                    }
                }
            }
        }
        if (any) p.sendMessage(m.get("answer-wrong"));
        else p.sendMessage(m.get("answer-none"));
        return false;
    }

    /** True if the player is on an armed SIGN step targeting this block, used to decide cancelling. */
    public boolean isStepSign(Block b) {
        for (Quest q : quests.values()) {
            for (Step s : q.steps()) if (s.trigger() instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().sameAs(b)) return true;
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
            } else if (r.step >= q.steps().size()) {
                r.step = q.steps().size() - 1;
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
     * and start delivery for runs that were waiting for the quest to be published.
     */
    public void reconcile() {
        for (Map.Entry<UUID, Map<String, Run>> e : List.copyOf(runs.entrySet())) {
            for (Run r : List.copyOf(e.getValue().values())) {
                Quest q = quests.get(r.questId);
                if (q == null || q.steps().isEmpty()) {
                    e.getValue().remove(r.questId);
                    delivery.cancel(e.getKey(), r);
                    continue;
                }
                clampRuns(q);
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && q.published() && !r.armed) startStep(p, r);
            }
        }
    }

    /** The first required quest this player has not finished yet, or null. For the locked sign text. */
    public Quest firstMissing(Player p, Quest q) {
        PlayerData d = data(p);
        if (d == null) return null;
        for (String r : q.requires()) {
            if (!completed(d, r)) return quests.get(r);
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

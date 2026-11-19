package cloud.schwnbrg.questbook.editor;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.QuestStatus;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
import cloud.schwnbrg.questbook.storage.QuestRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

/** All editor mutations. Commands and the GUI both call these methods. Main thread only. */
public final class EditorService {

    private record Pick(String questId, int step, long expires) {}

    private final QuestbookPlugin plugin;
    private final QuestRepository repo;
    private final Messages m;
    private final Map<UUID, EditorSession> sessions = new HashMap<>();
    private final Map<UUID, Pick> picks = new HashMap<>();
    /** Per editor: the quest file's modification time as that editor last saw or saved it. */
    private final Map<UUID, Map<String, Long>> seen = new HashMap<>();

    public EditorService(QuestbookPlugin plugin, QuestRepository repo, Messages m) {
        this.plugin = plugin;
        this.repo = repo;
        this.m = m;
    }

    // ---------- sessions ----------

    public EditorSession session(Player p) {
        EditorSession s = sessions.computeIfAbsent(p.getUniqueId(), k -> new EditorSession());
        s.touch();
        return s;
    }

    public EditorSession peek(UUID id) { return sessions.get(id); }
    public Map<UUID, EditorSession> sessions() { return sessions; }
    public void endSession(UUID id) { sessions.remove(id); picks.remove(id); seen.remove(id); }

    /** Called when an editor opens a quest menu: the baseline for the "file changed meanwhile" warning (kept until they save). */
    public void opened(Player p, String id) {
        seen.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).putIfAbsent(id, repo.lastModified(id));
    }

    /** After a reload: editors whose selected quest is gone select nothing, so no menu or preview points at a ghost. */
    public void dropMissingQuests() {
        for (EditorSession s : sessions.values()) {
            if (s.questId != null && plugin.quests().quest(s.questId) == null) s.questId = null;
        }
    }

    public void expireIdle() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().lastUse > 30 * 60_000L);
    }

    // ---------- core mutation ----------

    private Quest require(Player p, String id) {
        Quest q = plugin.quests().quest(id);
        if (q == null) p.sendMessage(m.get("editor-unknown-quest", "quest", Messages.esc(id)));
        return q;
    }

    private boolean stepOk(Player p, Quest q, int i) {
        if (i < 0 || i >= q.steps().size()) {
            p.sendMessage(m.get("editor-unknown-step", "step", String.valueOf(i + 1)));
            return false;
        }
        return true;
    }

    /** Applies a change, saves the YAML, and refreshes everything that depends on the quest. Returns the new quest or null. */
    public Quest mutate(Player p, String id, boolean structural, UnaryOperator<Quest> f) {
        Quest before = require(p, id);
        if (before == null) return null;
        Quest after;
        try {
            after = f.apply(before);
        } catch (IllegalArgumentException e) {
            p.sendMessage(m.get("editor-error", "reason", Messages.esc(String.valueOf(e.getMessage()))));
            return null;
        }
        if (after == null) return null;
        if (after.published()) {
            // a live quest must never be saved in a state players cannot finish: drop it back to draft instead
            List<String> errs = Validation.errors(after, plugin.quests().quests(), true);
            if (!errs.isEmpty()) {
                after = after.withStatus(QuestStatus.DRAFT);
                p.sendMessage(m.get("editor-auto-draft", "count", String.valueOf(errs.size())));
                for (String e : errs) p.sendMessage(m.get("editor-problem", "problem", Messages.esc(e)));
            }
        }
        if (structural) {
            int n = plugin.quests().activePlayers(id);
            if (n > 0) p.sendMessage(m.get("editor-active-warning", "count", String.valueOf(n)));
        }
        Map<String, Long> mineSeen = seen.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        Long mine = mineSeen.get(id);
        if (mine != null && repo.lastModified(id) != mine) p.sendMessage(m.get("editor-file-changed"));
        try {
            repo.save(after);
        } catch (IOException e) {
            p.sendMessage(m.get("editor-error", "reason", Messages.esc(e.getMessage())));
            return null;
        }
        mineSeen.put(id, repo.lastModified(id));
        apply(before, after);
        return after;
    }

    private void apply(Quest before, Quest after) {
        plugin.quests().put(after);
        plugin.signs().relinkIndex(before, after);
        plugin.quests().clampRuns(after);
        // step signs the quest no longer uses are released, so players can break them again
        for (Step s : before.steps()) {
            if (s.trigger() instanceof Trigger.SignClick sc) plugin.signs().releaseStepSign(sc.sign());
        }
        var adv = plugin.advancements();
        if (before.published() && !after.published()) adv.unregister(after.id());
        else if (after.published() && advancementChanged(before, after)) adv.register(after);
        if (before.published() || after.published()) plugin.signs().refreshForAll(after.id());
    }

    /** Title, summary, parent or frame (goal from 5 steps) changed, or the quest has just been published. */
    private static boolean advancementChanged(Quest before, Quest after) {
        return !before.published()
                || !before.title().equals(after.title())
                || !before.summary().equals(after.summary())
                || !before.requires().equals(after.requires())
                || (before.steps().size() >= 5) != (after.steps().size() >= 5);
    }

    // ---------- quest level ----------

    public Quest create(Player p, String id, String title) {
        if (!Validation.ID.matcher(id).matches()) {
            p.sendMessage(m.get("editor-bad-id"));
            return null;
        }
        if (plugin.quests().quest(id) != null) {
            p.sendMessage(m.get("editor-id-taken", "quest", id));
            return null;
        }
        Quest q = Quest.draft(id, title, p.getName());
        try {
            repo.save(q);
        } catch (IOException e) {
            p.sendMessage(m.get("editor-error", "reason", Messages.esc(e.getMessage())));
            return null;
        }
        opened(p, id);
        plugin.quests().put(q);
        p.sendMessage(m.get("editor-created", "quest", id));
        return q;
    }

    public void delete(Player p, String id) {
        Quest q = require(p, id);
        if (q == null) return;
        try {
            repo.delete(id);
        } catch (IOException e) {
            p.sendMessage(m.get("editor-error", "reason", Messages.esc(e.getMessage())));
            return;
        }
        if (q.startSign() != null && q.startSign().block() != null) {
            plugin.signs().untag(q.startSign().block());
            plugin.signs().restore(q.startSign()); // players still see the overridden text otherwise
        }
        for (Step s : q.steps()) {
            if (s.trigger() instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().block() != null && !usedElsewhere(sc.sign(), id)) {
                plugin.signs().untag(sc.sign().block());
            }
        }
        plugin.signs().relinkIndex(q, null);
        plugin.quests().remove(id);
        plugin.advancements().unregister(id);
        dropRequirement(p, id);
        for (EditorSession s : sessions.values()) if (id.equals(s.questId)) s.questId = null;
        p.sendMessage(m.get("editor-deleted", "quest", id));
    }

    /** Quests that required the deleted quest would be locked forever: remove the requirement from them. */
    private void dropRequirement(Player p, String deletedId) {
        for (Quest o : List.copyOf(plugin.quests().quests().values())) {
            if (!o.requires().contains(deletedId)) continue;
            List<String> r = new ArrayList<>(o.requires());
            r.remove(deletedId);
            Quest next = o.withRequires(r);
            try {
                repo.save(next);
            } catch (IOException e) {
                p.sendMessage(m.get("editor-error", "reason", Messages.esc(e.getMessage())));
                continue;
            }
            apply(o, next);
            p.sendMessage(m.get("editor-requires-removed", "quest", Messages.esc(o.id()), "other", Messages.esc(deletedId)));
        }
    }

    private boolean usedElsewhere(BlockRef ref, String exceptQuest) {
        for (Quest o : plugin.quests().quests().values()) {
            if (o.id().equals(exceptQuest)) continue;
            if (ref.equals(o.startSign())) return true;
            for (Step s : o.steps()) if (s.trigger() instanceof Trigger.SignClick sc && ref.equals(sc.sign())) return true;
        }
        return false;
    }

    public void setTitle(Player p, String id, String title) {
        if (mutate(p, id, false, q -> q.withTitle(title)) != null) p.sendMessage(m.get("editor-saved"));
    }

    public void setSummary(Player p, String id, String summary) {
        if (mutate(p, id, false, q -> q.withSummary(summary)) != null) p.sendMessage(m.get("editor-saved"));
    }

    public void setRepeatable(Player p, String id, boolean rep, int cooldown) {
        if (mutate(p, id, false, q -> q.withRepeatable(rep, rep ? Math.max(0, cooldown) : 0)) != null) p.sendMessage(m.get("editor-saved"));
    }

    public void toggleRequire(Player p, String id, String other, Boolean add) {
        mutate(p, id, false, q -> {
            if (other.equals(id)) throw new IllegalArgumentException("a quest cannot require itself");
            if (plugin.quests().quest(other) == null) throw new IllegalArgumentException("unknown quest " + other);
            List<String> r = new ArrayList<>(q.requires());
            boolean has = r.contains(other);
            boolean want = add == null ? !has : add;
            if (want && !has) r.add(other);
            if (!want) r.remove(other);
            Quest next = q.withRequires(r);
            if (Validation.errors(next, plugin.quests().quests(), false).stream().anyMatch(e -> e.contains("cycle"))) {
                throw new IllegalArgumentException("that would create a requires cycle");
            }
            return next;
        });
    }

    // ---------- start sign ----------

    public Block looking(Player p) {
        Block b = p.getTargetBlockExact(6);
        return b;
    }

    public void linkStartSign(Player p, String id) {
        Quest q = require(p, id);
        if (q == null) return;
        Block b = looking(p);
        if (b == null || !(b.getState() instanceof Sign)) {
            p.sendMessage(m.get("editor-look-at-sign"));
            return;
        }
        String err = plugin.signs().linkStart(q, b);
        if (err != null) {
            p.sendMessage(m.get(err));
            return;
        }
        if (q.startSign() != null && q.startSign().block() != null && !BlockRef.of(b).equals(q.startSign())) {
            plugin.signs().untag(q.startSign().block());
            plugin.signs().restore(q.startSign());
        }
        plugin.signs().tag(b, "start", id);
        if (mutate(p, id, false, x -> x.withStartSign(BlockRef.of(b))) != null) {
            p.sendMessage(m.get("editor-sign-linked"));
            plugin.signs().refreshForAll(id);
        }
    }

    public void unlinkSign(Player p) {
        Block b = looking(p);
        if (b == null || !(b.getState() instanceof Sign)) {
            p.sendMessage(m.get("editor-look-at-sign"));
            return;
        }
        Quest q = plugin.signs().questAt(b);
        plugin.signs().untag(b);
        if (q != null) {
            mutate(p, q.id(), false, x -> x.withStartSign(null).withStatus(QuestStatus.DRAFT));
        }
        plugin.signs().restore(BlockRef.of(b));
        p.sendMessage(m.get("editor-sign-unlinked"));
    }

    /** An editor broke a linked start sign: unlink it and drop the quest back to draft. */
    public void onStartSignBroken(Player p, Quest q) {
        plugin.signs().untag(q.startSign().block());
        mutate(p, q.id(), false, x -> x.withStartSign(null).withStatus(QuestStatus.DRAFT));
        p.sendMessage(m.get("editor-sign-broken", "quest", q.id()));
    }

    /** An editor broke a sign that is a SIGN trigger: those steps can no longer be finished, so say so. */
    public void onStepSignBroken(Player p, Block b) {
        List<String> ids = new ArrayList<>();
        for (Quest q : plugin.quests().quests().values()) {
            for (Step s : q.steps()) {
                if (s.trigger() instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().sameAs(b)) {
                    ids.add(q.id());
                    break;
                }
            }
        }
        if (!ids.isEmpty()) p.sendMessage(m.get("editor-step-sign-broken", "quests", Messages.esc(String.join(", ", ids))));
    }

    // ---------- steps ----------

    public void addStep(Player p, String id, String type, Integer index1) {
        String t = type.toUpperCase();
        if (!Trigger.TYPES.contains(t)) {
            p.sendMessage(m.get("editor-bad-type", "types", String.join(", ", Trigger.TYPES)));
            return;
        }
        Quest res = mutate(p, id, true, q -> {
            if (q.steps().size() >= 50) throw new IllegalArgumentException("max 50 steps");
            List<Step> l = new ArrayList<>(q.steps());
            int at = index1 == null ? l.size() : Math.max(0, Math.min(l.size(), index1 - 1));
            Trigger trig = Trigger.blank(t);
            if (trig instanceof Trigger.Reach) trig = new Trigger.Reach(p.getWorld().getName(), p.getLocation().getBlockX() + 0.5, p.getLocation().getBlockY(), p.getLocation().getBlockZ() + 0.5, 5);
            if (trig instanceof Trigger.Region) trig = new Trigger.Region(p.getWorld().getName(), p.getLocation().getBlockX(), p.getLocation().getBlockZ(), p.getLocation().getBlockX(), p.getLocation().getBlockZ(), p.getLocation().getBlockY(), 4);
            l.add(at, new Step(List.of("New step"), trig, null));
            return q.withSteps(l);
        });
        if (res != null) p.sendMessage(m.get("editor-step-added", "step", String.valueOf(res.steps().size())));
    }

    public void removeStep(Player p, String id, int i) {
        mutate(p, id, true, q -> {
            if (i < 0 || i >= q.steps().size()) throw new IllegalArgumentException("no such step");
            List<Step> l = new ArrayList<>(q.steps());
            l.remove(i);
            return q.withSteps(l);
        });
    }

    public void moveStep(Player p, String id, int from, int to) {
        mutate(p, id, true, q -> {
            int n = q.steps().size();
            if (from < 0 || from >= n || to < 0 || to >= n) throw new IllegalArgumentException("no such step");
            List<Step> l = new ArrayList<>(q.steps());
            l.add(to, l.remove(from));
            return q.withSteps(l);
        });
    }

    private void editStep(Player p, String id, int i, boolean structural, UnaryOperator<Step> f) {
        Quest q = require(p, id);
        if (q == null || !stepOk(p, q, i)) return;
        Quest res = mutate(p, id, structural, x -> x.withStep(i, f.apply(x.steps().get(i))));
        if (res != null) p.sendMessage(m.get("editor-saved"));
    }

    /** Splits typed text into lines and drops trailing blank ones. */
    private static List<String> textLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
        return lines;
    }

    public void setText(Player p, String id, int i, String text) {
        List<String> lines = textLines(text);
        if (lines.isEmpty()) {
            p.sendMessage(m.get("editor-text-empty"));
            return;
        }
        editStep(p, id, i, false, s -> s.withText(lines));
    }

    /** The optional text told after the trigger fired. An empty text clears it. */
    public void setAfter(Player p, String id, int i, String text) {
        List<String> lines = textLines(text);
        editStep(p, id, i, false, s -> s.withAfter(lines));
    }

    public void setHint(Player p, String id, int i, String hint) {
        editStep(p, id, i, false, s -> s.withHint(hint == null || hint.isBlank() ? null : hint.lines().findFirst().orElse(null)));
    }

    public void setTriggerType(Player p, String id, int i, String type) {
        if (!Trigger.TYPES.contains(type.toUpperCase())) {
            p.sendMessage(m.get("editor-bad-type", "types", String.join(", ", Trigger.TYPES)));
            return;
        }
        editStep(p, id, i, true, s -> s.withTrigger(Trigger.blank(type)));
    }

    public void stepHere(Player p, String id, int i, Integer radius) {
        Location l = p.getLocation();
        editStep(p, id, i, false, s -> {
            int r = radius != null ? radius : (s.trigger() instanceof Trigger.Reach old ? old.radius() : 5);
            return s.withTrigger(new Trigger.Reach(l.getWorld().getName(), Math.floor(l.getX()) + 0.5, l.getBlockY(), Math.floor(l.getZ()) + 0.5, clamp(r, 1, 64)));
        });
    }

    public void setRadius(Player p, String id, int i, int r) {
        editStep(p, id, i, false, s -> {
            if (!(s.trigger() instanceof Trigger.Reach t)) throw new IllegalArgumentException("step is not REACH");
            return s.withTrigger(new Trigger.Reach(t.world(), t.x(), t.y(), t.z(), clamp(r, 1, 64)));
        });
    }

    /**
     * pos1 resets the base to the editor's feet level, pos2 lowers it if its feet level is lower (spec §5:
     * baseY = min of both). The box contains blocks from baseY up, measured against the player's feet block.
     */
    public void setCorner(Player p, String id, int i, boolean first) {
        Location l = p.getLocation();
        editStep(p, id, i, false, s -> {
            int gy = l.getBlockY(); // feet level, the same coordinate Region.contains() compares
            if (!(s.trigger() instanceof Trigger.Region t)) {
                return s.withTrigger(new Trigger.Region(l.getWorld().getName(), l.getBlockX(), l.getBlockZ(), l.getBlockX(), l.getBlockZ(), gy, 4));
            }
            int x1 = first ? l.getBlockX() : t.x1(), z1 = first ? l.getBlockZ() : t.z1();
            int x2 = first ? t.x2() : l.getBlockX(), z2 = first ? t.z2() : l.getBlockZ();
            int base = first ? gy : Math.min(t.baseY(), gy);
            return s.withTrigger(new Trigger.Region(l.getWorld().getName(), x1, z1, x2, z2, base, t.height()));
        });
    }

    public void setHeight(Player p, String id, int i, int h) {
        editStep(p, id, i, false, s -> {
            if (!(s.trigger() instanceof Trigger.Region t)) throw new IllegalArgumentException("step is not REGION");
            return s.withTrigger(new Trigger.Region(t.world(), t.x1(), t.z1(), t.x2(), t.z2(), t.baseY(), clamp(h, 1, 128)));
        });
    }

    public void setBlock(Player p, String id, int i, Block b) {
        Quest q = require(p, id);
        if (q == null || !stepOk(p, q, i)) return;
        Trigger t = q.steps().get(i).trigger();
        if (t instanceof Trigger.SignClick) {
            if (!(b.getState() instanceof Sign)) {
                p.sendMessage(m.get("editor-not-a-sign"));
                return;
            }
            plugin.signs().tag(b, "step", null);
            editStep(p, id, i, false, s -> s.withTrigger(new Trigger.SignClick(BlockRef.of(b))));
        } else if (t instanceof Trigger.Interact) {
            editStep(p, id, i, false, s -> s.withTrigger(new Trigger.Interact(BlockRef.of(b))));
        } else {
            p.sendMessage(m.get("editor-not-block-step"));
        }
    }

    public void stepBlockLooking(Player p, String id, int i) {
        Block b = looking(p);
        if (b == null) {
            p.sendMessage(m.get("editor-look-at-block"));
            return;
        }
        setBlock(p, id, i, b);
    }

    public void setAnswers(Player p, String id, int i, List<String> answers) {
        List<String> clean = answers.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
        editStep(p, id, i, false, s -> {
            if (clean.isEmpty() || clean.size() > 10) throw new IllegalArgumentException("1..10 answers");
            return s.withTrigger(new Trigger.Answer(clean));
        });
    }

    public void addAnswer(Player p, String id, int i, String answer) {
        Quest q = require(p, id);
        if (q == null || !stepOk(p, q, i) || answer.isBlank()) return;
        List<String> l = new ArrayList<>(q.steps().get(i).trigger() instanceof Trigger.Answer a ? a.answers() : List.of());
        l.add(answer.strip());
        setAnswers(p, id, i, l);
    }

    public void removeAnswer(Player p, String id, int i, int idx) {
        Quest q = require(p, id);
        if (q == null || !stepOk(p, q, i)) return;
        List<String> l = new ArrayList<>(q.steps().get(i).trigger() instanceof Trigger.Answer a ? a.answers() : List.of());
        if (idx >= 0 && idx < l.size()) l.remove(idx);
        editStep(p, id, i, false, s -> s.withTrigger(new Trigger.Answer(List.copyOf(l))));
    }

    /** KILL trigger: target is a category or an entity type name; amount is clamped to 1..1000. Null keeps the old value. */
    public void setKill(Player p, String id, int i, String target, Integer amount) {
        String t = target == null ? null : target.strip().toUpperCase();
        if (t != null && !Trigger.Kill.validTarget(t)) {
            p.sendMessage(m.get("editor-bad-kill-target", "target", Messages.esc(target), "categories", String.join(", ", Trigger.Kill.CATEGORIES)));
            return;
        }
        editStep(p, id, i, false, s -> {
            Trigger.Kill old = s.trigger() instanceof Trigger.Kill k ? k : new Trigger.Kill("ANY", 5);
            return s.withTrigger(new Trigger.Kill(t == null ? old.target() : t, clamp(amount == null ? old.amount() : amount, 1, 1000)));
        });
    }

    public void setDimension(Player p, String id, int i, World.Environment env) {
        editStep(p, id, i, false, s -> s.withTrigger(new Trigger.Dimension(env)));
    }

    public void setAdvancement(Player p, String id, int i, String key) {
        NamespacedKey k = NamespacedKey.fromString(key.strip().toLowerCase());
        if (k == null || Bukkit.getAdvancement(k) == null) {
            p.sendMessage(m.get("editor-unknown-advancement", "key", Messages.esc(key)));
            return;
        }
        editStep(p, id, i, false, s -> s.withTrigger(new Trigger.AdvancementDone(k)));
    }

    // ---------- pick block (GUI) ----------

    public void startPick(Player p, String id, int step) {
        picks.put(p.getUniqueId(), new Pick(id, step, System.currentTimeMillis() + 30_000));
        p.closeInventory();
        p.sendMessage(m.get("editor-pick"));
    }

    /** Captures a right-click for a pending pick. Returns true if the click was consumed. */
    public boolean capturePick(Player p, Block b) {
        Pick pk = picks.get(p.getUniqueId());
        if (pk == null) return false;
        picks.remove(p.getUniqueId());
        if (System.currentTimeMillis() > pk.expires()) return false;
        setBlock(p, pk.questId(), pk.step(), b);
        plugin.menus().openStep(p, pk.questId(), pk.step());
        return true;
    }

    // ---------- publish ----------

    public List<String> errors(Quest q, boolean publish) {
        List<String> e = new ArrayList<>(Validation.errors(q, plugin.quests().quests(), publish));
        if (publish && q.startSign() != null) {
            Block b = q.startSign().block();
            if (b == null) e.add("start sign world is not loaded");
            else if (!(b.getState() instanceof Sign)) e.add("start sign is not a sign");
        }
        return e;
    }

    /** Things that do not block publishing but will stop a step from ever completing (spec §6, §16). */
    public List<String> warnings(Quest q) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < q.steps().size(); i++) {
            Trigger t = q.steps().get(i).trigger();
            String world = switch (t) {
                case Trigger.Reach r -> r.world();
                case Trigger.Region r -> r.world();
                case Trigger.SignClick s -> s.sign() == null ? null : s.sign().world();
                case Trigger.Interact in -> in.block() == null ? null : in.block().world();
                default -> null;
            };
            if (world != null && Bukkit.getWorld(world) == null) out.add("step " + (i + 1) + ": world '" + world + "' is not loaded");
            if (t instanceof Trigger.AdvancementDone a && Bukkit.getAdvancement(a.key()) == null) {
                out.add("step " + (i + 1) + ": unknown advancement " + a.key());
            }
        }
        return out;
    }

    public void publish(Player p, String id) {
        Quest q = require(p, id);
        if (q == null) return;
        List<String> errs = errors(q, true);
        if (!errs.isEmpty()) {
            p.sendMessage(m.get("editor-publish-failed", "count", String.valueOf(errs.size())));
            for (String e : errs) p.sendMessage(m.get("editor-problem", "problem", Messages.esc(e)));
            return;
        }
        Quest after = mutate(p, id, false, x -> x.withStatus(QuestStatus.PUBLISHED));
        if (after == null) return;
        plugin.quests().resume(after);
        p.sendMessage(m.get("editor-published", "quest", id));
    }

    public void unpublish(Player p, String id) {
        Quest after = mutate(p, id, false, x -> x.withStatus(QuestStatus.DRAFT));
        if (after == null) return;
        p.sendMessage(m.get("editor-unpublished", "quest", id));
    }

    public void validate(Player p, String id) {
        List<String> out = new ArrayList<>();
        for (Quest q : plugin.quests().quests().values()) {
            if (id != null && !q.id().equals(id)) continue;
            for (String e : errors(q, q.published())) out.add(q.id() + ": " + e);
            for (String w : warnings(q)) out.add(q.id() + ": warning: " + w);
        }
        if (id == null) out.addAll(plugin.signs().problems());
        if (out.isEmpty()) p.sendMessage(m.get("editor-valid"));
        else for (String s : out) p.sendMessage(m.get("editor-problem", "problem", Messages.esc(s)));
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

package cloud.schwnbrg.questbook.editor;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Cond;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Rule;
import cloud.schwnbrg.questbook.model.Target;
import cloud.schwnbrg.questbook.model.TextVariant;
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

    private record Pick(String questId, int step, int opt, long expires) {}

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

    /** Admins edit every quest. Everybody else only their own; quests from before ownership belong to the editor named as author. */
    public boolean canEdit(Player p, Quest q) {
        if (!p.hasPermission("questbook.editor")) return false;
        if (p.hasPermission("questbook.admin")) return true;
        if (!q.owner().isBlank()) return q.owner().equals(p.getUniqueId().toString());
        return p.getName().equalsIgnoreCase(q.author());
    }

    /** The quest if it exists and this player may see and edit it, else null. Someone else's quest looks like a missing one. */
    public Quest editable(Player p, String id) {
        Quest q = plugin.quests().quest(id);
        return q != null && canEdit(p, q) ? q : null;
    }

    public List<Quest> editableQuests(Player p) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : plugin.quests().quests().values()) if (canEdit(p, q)) out.add(q);
        return out;
    }

    /** True if every quest that uses this sign (start sign or SIGN step) is editable by the player. */
    public boolean maySignEdit(Player p, Block sign) {
        if (!p.hasPermission("questbook.editor")) return false;
        for (Quest q : plugin.quests().quests().values()) {
            boolean uses = q.startSign() != null && q.startSign().sameAs(sign);
            for (Step s : q.steps()) {
                for (Trigger lt : Trigger.leaves(s.trigger())) if (lt instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().sameAs(sign)) uses = true;
            }
            if (uses && !canEdit(p, q)) return false;
        }
        return true;
    }

    private Quest require(Player p, String id) {
        Quest q = editable(p, id);
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
        after = cleanReferences(p, after);
        if (after.owner().isBlank() && p.getName().equalsIgnoreCase(after.author())) after = after.withOwner(p.getUniqueId().toString());
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
            for (Trigger lt : Trigger.leaves(s.trigger())) if (lt instanceof Trigger.SignClick sc) plugin.signs().releaseStepSign(sc.sign());
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
        Quest q = Quest.draft(id, title, p.getName()).withOwner(p.getUniqueId().toString());
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
            for (Trigger lt : Trigger.leaves(s.trigger())) if (lt instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().block() != null && !usedElsewhere(sc.sign(), id)) {
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
            for (Step s : o.steps()) for (Trigger lt : Trigger.leaves(s.trigger())) if (lt instanceof Trigger.SignClick sc && ref.equals(sc.sign())) return true;
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
            if (editable(p, other) == null) throw new IllegalArgumentException("unknown quest " + other);
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
        if (!maySignEdit(p, b)) {
            p.sendMessage(m.get("editor-not-yours"));
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
        if (!maySignEdit(p, b)) {
            p.sendMessage(m.get("editor-not-yours"));
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
        for (Quest q : editableQuests(p)) {
            for (Step s : q.steps()) {
                for (Trigger lt : Trigger.leaves(s.trigger())) if (lt instanceof Trigger.SignClick sc && sc.sign() != null && sc.sign().sameAs(b)) {
                    if (!ids.contains(q.id())) ids.add(q.id());
                    break;
                }
            }
        }
        if (!ids.isEmpty()) p.sendMessage(m.get("editor-step-sign-broken", "quests", Messages.esc(String.join(", ", ids))));
    }

    // ---------- steps ----------

    /** Adds a step (at the end, or at 1-based {@code index1}). Returns the new step's index, or -1. */
    public int addStep(Player p, String id, String type, Integer index1) {
        String t = type.toUpperCase();
        if (!Trigger.TYPES.contains(t)) {
            p.sendMessage(m.get("editor-bad-type", "types", String.join(", ", Trigger.TYPES)));
            return -1;
        }
        int[] at = {-1};
        Quest res = mutate(p, id, true, q -> {
            int max = Validation.limits().steps();
            if (q.steps().size() >= max) throw new IllegalArgumentException("max " + max + " steps");
            List<Step> l = new ArrayList<>(q.steps());
            at[0] = index1 == null ? l.size() : Math.max(0, Math.min(l.size(), index1 - 1));
            l.add(at[0], new Step(List.of("New step"), placed(p, Trigger.blank(t)), null));
            return q.withSteps(l);
        });
        if (res == null) return -1;
        p.sendMessage(m.get("editor-step-added", "step", String.valueOf(at[0] + 1)));
        return at[0];
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
        setTriggerType(p, id, i, -1, type);
    }

    /** {@code opt} &gt;= 0 changes the trigger of that WORLD CHOICE option instead of the step's own trigger. */
    public void setTriggerType(Player p, String id, int i, int opt, String type) {
        String t = type.toUpperCase();
        List<String> allowed = opt < 0 ? Trigger.TYPES : Trigger.WORLD_TYPES;
        if (!allowed.contains(t)) {
            p.sendMessage(m.get("editor-bad-type", "types", String.join(", ", allowed)));
            return;
        }
        editTrigger(p, id, i, opt, true, old -> placed(p, Trigger.blank(t)));
    }

    /** A blank REACH/REGION starts where the editor stands, like a newly added step. */
    private static Trigger placed(Player p, Trigger trig) {
        Location l = p.getLocation();
        if (trig instanceof Trigger.Reach) return new Trigger.Reach(p.getWorld().getName(), l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5, 5);
        if (trig instanceof Trigger.Region) return new Trigger.Region(p.getWorld().getName(), l.getBlockX(), l.getBlockZ(), l.getBlockX(), l.getBlockZ(), l.getBlockY(), 4);
        return trig;
    }

    /** The trigger that {@code opt} addresses: the step's own (opt &lt; 0) or a WORLD CHOICE option's. Null if there is none. */
    public static Trigger triggerAt(Step s, int opt) {
        if (opt < 0) return s.trigger();
        return s.trigger() instanceof Trigger.FirstOf f && opt < f.options().size() ? f.options().get(opt).trigger() : null;
    }

    /** Changes the step's trigger, or the trigger of WORLD CHOICE option {@code opt}. */
    private void editTrigger(Player p, String id, int i, int opt, boolean structural, UnaryOperator<Trigger> f) {
        editStep(p, id, i, structural, s -> {
            if (opt < 0) return s.withTrigger(f.apply(s.trigger()));
            if (!(s.trigger() instanceof Trigger.FirstOf fo) || opt >= fo.options().size()) throw new IllegalArgumentException("no such option");
            List<Trigger.Branch> l = new ArrayList<>(fo.options());
            Trigger.Branch b = l.get(opt);
            l.set(opt, new Trigger.Branch(b.id(), b.label(), f.apply(b.trigger())));
            return s.withTrigger(new Trigger.FirstOf(l));
        });
    }

    public void stepHere(Player p, String id, int i, Integer radius) {
        stepHere(p, id, i, -1, radius);
    }

    public void stepHere(Player p, String id, int i, int opt, Integer radius) {
        Location l = p.getLocation();
        editTrigger(p, id, i, opt, false, t -> {
            int r = radius != null ? radius : (t instanceof Trigger.Reach old ? old.radius() : 5);
            return new Trigger.Reach(l.getWorld().getName(), Math.floor(l.getX()) + 0.5, l.getBlockY(), Math.floor(l.getZ()) + 0.5, clamp(r, 1, 64));
        });
    }

    public void setRadius(Player p, String id, int i, int r) {
        setRadius(p, id, i, -1, r);
    }

    public void setRadius(Player p, String id, int i, int opt, int r) {
        editTrigger(p, id, i, opt, false, tr -> {
            if (!(tr instanceof Trigger.Reach t)) throw new IllegalArgumentException("step is not REACH");
            return new Trigger.Reach(t.world(), t.x(), t.y(), t.z(), clamp(r, 1, 64));
        });
    }

    public void setCorner(Player p, String id, int i, boolean first) {
        setCorner(p, id, i, -1, first);
    }

    /**
     * pos1 resets the base to the editor's feet level, pos2 lowers it if its feet level is lower (spec §5:
     * baseY = min of both). The box contains blocks from baseY up, measured against the player's feet block.
     */
    public void setCorner(Player p, String id, int i, int opt, boolean first) {
        Location l = p.getLocation();
        // a REGION trigger's box, or the area of a PLACE trigger: the same tools
        editTrigger(p, id, i, opt, false, tr -> tr instanceof Trigger.Place pl ? pl.withArea(corner(pl.area(), l, first))
                : corner(tr instanceof Trigger.Region r ? r : null, l, first));
    }

    private static Trigger.Region corner(Trigger.Region t, Location l, boolean first) {
        int gy = l.getBlockY(); // feet level, the same coordinate Region.contains() compares
        if (t == null) return new Trigger.Region(l.getWorld().getName(), l.getBlockX(), l.getBlockZ(), l.getBlockX(), l.getBlockZ(), gy, 4);
        int x1 = first ? l.getBlockX() : t.x1(), z1 = first ? l.getBlockZ() : t.z1();
        int x2 = first ? t.x2() : l.getBlockX(), z2 = first ? t.z2() : l.getBlockZ();
        int base = first ? gy : Math.min(t.baseY(), gy);
        return new Trigger.Region(l.getWorld().getName(), x1, z1, x2, z2, base, t.height());
    }

    public void setHeight(Player p, String id, int i, int h) {
        setHeight(p, id, i, -1, h);
    }

    public void setHeight(Player p, String id, int i, int opt, int h) {
        editTrigger(p, id, i, opt, false, tr -> {
            if (tr instanceof Trigger.Place pl && pl.area() != null) return pl.withArea(height(pl.area(), h));
            if (!(tr instanceof Trigger.Region t)) throw new IllegalArgumentException("step is not REGION");
            return height(t, h);
        });
    }

    private static Trigger.Region height(Trigger.Region t, int h) {
        return new Trigger.Region(t.world(), t.x1(), t.z1(), t.x2(), t.z2(), t.baseY(), clamp(h, 1, 128));
    }

    /** PLACE: only blocks placed inside an area count; it starts as one column where the editor stands, like a new REGION. */
    public void setPlaceArea(Player p, String id, int i, int opt, boolean on) {
        Location l = p.getLocation();
        editTrigger(p, id, i, opt, false, tr -> tr instanceof Trigger.Place pl ? pl.withArea(on ? corner(null, l, true) : null) : tr);
    }

    public void setBlock(Player p, String id, int i, Block b) {
        setBlock(p, id, i, -1, b);
    }

    public void setBlock(Player p, String id, int i, int opt, Block b) {
        Quest q = require(p, id);
        if (q == null || !stepOk(p, q, i)) return;
        Trigger t = triggerAt(q.steps().get(i), opt);
        if (t instanceof Trigger.SignClick) {
            if (!(b.getState() instanceof Sign)) {
                p.sendMessage(m.get("editor-not-a-sign"));
                return;
            }
            if (!maySignEdit(p, b)) {
                p.sendMessage(m.get("editor-not-yours"));
                return;
            }
            if (plugin.signs().questAt(b) != null) {
                p.sendMessage(m.get("editor-sign-taken"));
                return;
            }
            plugin.signs().tag(b, "step", null);
            editTrigger(p, id, i, opt, false, x -> new Trigger.SignClick(BlockRef.of(b)));
        } else if (t instanceof Trigger.Interact) {
            editTrigger(p, id, i, opt, false, x -> new Trigger.Interact(BlockRef.of(b)));
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

    public void setKill(Player p, String id, int i, String target, Integer amount) {
        setKill(p, id, i, -1, target, amount);
    }

    /** KILL trigger: target is a category or an entity type name; amount is clamped to 1..1000. Null keeps the old value. */
    public void setKill(Player p, String id, int i, int opt, String target, Integer amount) {
        String t = target == null ? null : target.strip().toUpperCase();
        if (t != null && !Trigger.Kill.validTarget(t)) {
            p.sendMessage(m.get("editor-bad-kill-target", "target", Messages.esc(target), "categories", String.join(", ", Trigger.Kill.CATEGORIES)));
            return;
        }
        editTrigger(p, id, i, opt, false, tr -> {
            Trigger.Kill old = tr instanceof Trigger.Kill k ? k : new Trigger.Kill("ANY", 5);
            return new Trigger.Kill(t == null ? old.target() : t, clamp(amount == null ? old.amount() : amount, 1, 1000));
        });
    }

    /** HAVE_ITEM: {@code item} null keeps the item, otherwise a material key ("wheat"); amount is clamped to 1..2304. */
    public void setHaveItem(Player p, String id, int i, int opt, String item, Integer amount) {
        String it = item == null ? null : item.strip().toLowerCase();
        if (it != null && !Validation.validItem(it)) {
            p.sendMessage(m.get("editor-bad-item", "item", Messages.esc(item)));
            return;
        }
        editTrigger(p, id, i, opt, false, tr -> {
            Trigger.HaveItem old = tr instanceof Trigger.HaveItem h ? h : new Trigger.HaveItem("wheat", 16);
            return new Trigger.HaveItem(it == null ? old.item() : it, clamp(amount == null ? old.amount() : amount, 1, 2304));
        });
    }

    /** PLACE: {@code block} null keeps it; amount is clamped to 1..1000. */
    public void setPlace(Player p, String id, int i, int opt, String block, Integer amount) {
        String b = block == null ? null : block.strip().toLowerCase();
        if (b != null && !Validation.validBlock(b)) {
            p.sendMessage(m.get("editor-bad-block", "block", Messages.esc(block)));
            return;
        }
        editTrigger(p, id, i, opt, false, tr -> {
            Trigger.Place old = tr instanceof Trigger.Place pl ? pl : new Trigger.Place("torch", 1, null);
            return new Trigger.Place(b == null ? old.block() : b, clamp(amount == null ? old.amount() : amount, 1, 1000), old.area());
        });
    }

    /** The item in the editor's main hand as a material key, or null (with a message) if the hand is empty. */
    public String heldItem(Player p) {
        org.bukkit.inventory.ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) {
            p.sendMessage(m.get("editor-hold-item"));
            return null;
        }
        return it.getType().getKey().getKey();
    }

    public void setDimension(Player p, String id, int i, World.Environment env) {
        setDimension(p, id, i, -1, env);
    }

    public void setDimension(Player p, String id, int i, int opt, World.Environment env) {
        editTrigger(p, id, i, opt, false, t -> new Trigger.Dimension(env));
    }

    public void setAdvancement(Player p, String id, int i, String key) {
        setAdvancement(p, id, i, -1, key);
    }

    public void setAdvancement(Player p, String id, int i, int opt, String key) {
        NamespacedKey k = NamespacedKey.fromString(key.strip().toLowerCase());
        if (k == null || Bukkit.getAdvancement(k) == null) {
            p.sendMessage(m.get("editor-unknown-advancement", "key", Messages.esc(key)));
            return;
        }
        editTrigger(p, id, i, opt, false, t -> new Trigger.AdvancementDone(k));
    }

    // ---------- CHOICE buttons and WORLD CHOICE options ----------

    /** Adds a CHOICE button (max 4) or, on a WORLD CHOICE step, an option with a REACH trigger here (max 3). */
    public void addOption(Player p, String id, int i, String label) {
        String l = label == null ? "" : label.strip();
        editStep(p, id, i, true, s -> switch (s.trigger()) {
            case Trigger.Choice c -> {
                if (c.options().size() >= 4) throw new IllegalArgumentException("max 4 buttons");
                List<Trigger.Option> o = new ArrayList<>(c.options());
                o.add(new Trigger.Option(null, l.isEmpty() ? "Option " + (o.size() + 1) : l));
                yield s.withTrigger(new Trigger.Choice(o));
            }
            case Trigger.FirstOf f -> {
                if (f.options().size() >= 3) throw new IllegalArgumentException("max 3 options");
                List<Trigger.Branch> o = new ArrayList<>(f.options());
                o.add(new Trigger.Branch(null, l.isEmpty() ? "Option " + (o.size() + 1) : l, placed(p, Trigger.blank("REACH"))));
                yield s.withTrigger(new Trigger.FirstOf(o));
            }
            default -> throw new IllegalArgumentException("step has no options");
        });
    }

    public void renameOption(Player p, String id, int i, int opt, String label) {
        if (label == null || label.isBlank()) return;
        String l = label.strip();
        editStep(p, id, i, false, s -> switch (s.trigger()) {
            case Trigger.Choice c when opt >= 0 && opt < c.options().size() -> {
                List<Trigger.Option> o = new ArrayList<>(c.options());
                o.set(opt, new Trigger.Option(o.get(opt).id(), l));
                yield s.withTrigger(new Trigger.Choice(o));
            }
            case Trigger.FirstOf f when opt >= 0 && opt < f.options().size() -> {
                List<Trigger.Branch> o = new ArrayList<>(f.options());
                Trigger.Branch b = o.get(opt);
                o.set(opt, new Trigger.Branch(b.id(), l, b.trigger()));
                yield s.withTrigger(new Trigger.FirstOf(o));
            }
            default -> throw new IllegalArgumentException("no such option");
        });
    }

    /** Removes a button/option; conditions that checked it are removed too (and reported). */
    public void removeOption(Player p, String id, int i, int opt) {
        editStep(p, id, i, true, s -> switch (s.trigger()) {
            case Trigger.Choice c when opt >= 0 && opt < c.options().size() -> {
                List<Trigger.Option> o = new ArrayList<>(c.options());
                o.remove(opt);
                yield s.withTrigger(new Trigger.Choice(o));
            }
            case Trigger.FirstOf f when opt >= 0 && opt < f.options().size() -> {
                List<Trigger.Branch> o = new ArrayList<>(f.options());
                o.remove(opt);
                yield s.withTrigger(new Trigger.FirstOf(o));
            }
            default -> throw new IllegalArgumentException("no such option");
        });
    }

    // ---------- where next: conditions, next step, endings ----------

    /** Sets where a step goes when no condition matches; null = the following step (the default). */
    public void setNext(Player p, String id, int i, Target t) {
        editStep(p, id, i, true, s -> s.withNext(t));
    }

    public void addRule(Player p, String id, int i, Cond c, Target t) {
        editStep(p, id, i, true, s -> {
            if (s.rules().size() >= Validation.MAX_RULES) throw new IllegalArgumentException("max " + Validation.MAX_RULES + " conditions per step");
            List<Rule> l = new ArrayList<>(s.rules());
            l.add(new Rule(c, t));
            return s.withRules(l);
        });
    }

    /** Replaces the condition and/or the target of rule r (null keeps it). */
    public void setRule(Player p, String id, int i, int r, Cond c, Target t) {
        editStep(p, id, i, true, s -> {
            if (r < 0 || r >= s.rules().size()) throw new IllegalArgumentException("no such condition");
            List<Rule> l = new ArrayList<>(s.rules());
            Rule old = l.get(r);
            l.set(r, new Rule(c == null ? old.cond() : c, t == null ? old.target() : t));
            return s.withRules(l);
        });
    }

    public void removeRule(Player p, String id, int i, int r) {
        editStep(p, id, i, true, s -> {
            if (r < 0 || r >= s.rules().size()) throw new IllegalArgumentException("no such condition");
            List<Rule> l = new ArrayList<>(s.rules());
            l.remove(r);
            return s.withRules(l);
        });
    }

    /** Moves rule r one place up (it is checked earlier). */
    public void raiseRule(Player p, String id, int i, int r) {
        editStep(p, id, i, true, s -> {
            if (r <= 0 || r >= s.rules().size()) return s;
            List<Rule> l = new ArrayList<>(s.rules());
            l.add(r - 1, l.remove(r));
            return s.withRules(l);
        });
    }

    // ---------- text variants ----------

    public void addVariant(Player p, String id, int i, Cond c, String text) {
        List<String> lines = textLines(text);
        if (lines.isEmpty()) {
            p.sendMessage(m.get("editor-text-empty"));
            return;
        }
        editStep(p, id, i, false, s -> {
            if (s.textIf().size() >= Validation.MAX_VARIANTS) throw new IllegalArgumentException("max " + Validation.MAX_VARIANTS + " text variants");
            List<TextVariant> l = new ArrayList<>(s.textIf());
            l.add(new TextVariant(c, lines));
            return s.withTextIf(l);
        });
    }

    public void setVariantText(Player p, String id, int i, int v, String text) {
        List<String> lines = textLines(text);
        if (lines.isEmpty()) {
            p.sendMessage(m.get("editor-text-empty"));
            return;
        }
        editStep(p, id, i, false, s -> {
            if (v < 0 || v >= s.textIf().size()) throw new IllegalArgumentException("no such text variant");
            List<TextVariant> l = new ArrayList<>(s.textIf());
            l.set(v, new TextVariant(l.get(v).cond(), lines));
            return s.withTextIf(l);
        });
    }

    public void removeVariant(Player p, String id, int i, int v) {
        editStep(p, id, i, false, s -> {
            if (v < 0 || v >= s.textIf().size()) throw new IllegalArgumentException("no such text variant");
            List<TextVariant> l = new ArrayList<>(s.textIf());
            l.remove(v);
            return s.withTextIf(l);
        });
    }

    // ---------- guide, required endings ----------

    /** Turns the player guide on or off for a quest (null keeps that part). */
    public void setGuide(Player p, String id, Boolean particles, Boolean compass) {
        Quest res = mutate(p, id, false, q -> q.withGuide(new Quest.Guide(particles == null ? q.guide().particles() : particles,
                compass == null ? q.guide().compass() : compass)));
        if (res != null) p.sendMessage(m.get("editor-saved"));
    }

    /** Requires quest {@code other} to have been finished with this ending (null: any ending). Adds the requirement if missing. */
    public void setRequireEnding(Player p, String id, String other, String ending) {
        mutate(p, id, false, q -> {
            if (editable(p, other) == null) throw new IllegalArgumentException("unknown quest " + other);
            Quest next = q;
            if (!q.requires().contains(other)) {
                List<String> r = new ArrayList<>(q.requires());
                r.add(other);
                next = q.withRequires(r);
                if (Validation.errors(next, plugin.quests().quests(), false).stream().anyMatch(e -> e.contains("cycle"))) {
                    throw new IllegalArgumentException("that would create a requires cycle");
                }
            }
            Map<String, String> re = new java.util.LinkedHashMap<>(next.requireEndings());
            if (ending == null || ending.isBlank()) re.remove(other);
            else re.put(other, ending);
            return next.withRequireEndings(re);
        });
    }

    /**
     * Drops what a change left pointing at nothing: conditions and text variants that check a removed step, button or
     * answer of this quest, and conditions / next steps that go to a removed step. Says how many were dropped.
     */
    private Quest cleanReferences(Player p, Quest q) {
        int[] dropped = {0};
        List<Step> out = new ArrayList<>();
        for (Step s : q.steps()) {
            List<Rule> rules = new ArrayList<>();
            for (Rule r : s.rules()) {
                if (dangling(q, r.cond()) || (!r.target().isEnd() && q.indexOf(r.target().step()) < 0)) dropped[0]++;
                else rules.add(r);
            }
            List<TextVariant> vs = new ArrayList<>();
            for (TextVariant v : s.textIf()) {
                if (dangling(q, v.cond())) dropped[0]++;
                else vs.add(v);
            }
            Target next = s.next();
            if (next != null && !next.isEnd() && q.indexOf(next.step()) < 0) {
                next = null;
                dropped[0]++;
            }
            out.add(s.withRules(rules).withTextIf(vs).withNext(next));
        }
        if (dropped[0] == 0) return q;
        p.sendMessage(m.get("editor-refs-dropped", "count", String.valueOf(dropped[0])));
        return q.withSteps(out);
    }

    private static boolean dangling(Quest q, Cond c) {
        if (!(c instanceof Cond.Picked pk) || (pk.otherQuest() && !pk.quest().equals(q.id()))) return false;
        Step s = q.step(pk.step());
        return s == null || !Validation.pickable(s.trigger(), pk.value());
    }

    public Describe describe() { return new Describe(m, plugin.quests()::quest); }

    /** /qe map: the quest's flow as chat lines, one block per step. */
    public void printMap(Player p, String id) {
        Quest q = require(p, id);
        if (q == null) return;
        Describe d = describe();
        p.sendMessage(m.get("map-head", "title", q.title()));
        for (int i = 0; i < q.steps().size(); i++) {
            Step s = q.steps().get(i);
            p.sendMessage(m.get("map-step", "n", String.valueOf(i + 1), "type", s.trigger().type(), "first", Messages.esc(Describe.first(s))));
            for (Rule r : s.rules()) {
                p.sendMessage(m.get("map-rule", "colour", Describe.COLOURS.get(Describe.colour(q, r.cond())),
                        "cond", Messages.esc(d.cond(q, r.cond())), "target", Messages.esc(d.target(q, i, r.target()))));
            }
            if (!s.rules().isEmpty() || s.next() != null || i == q.steps().size() - 1) {
                p.sendMessage(m.get(s.rules().isEmpty() ? "map-then" : "map-otherwise", "target", Messages.esc(d.target(q, i, s.next()))));
            }
        }
        for (String w : Validation.flowWarnings(q)) p.sendMessage(m.get("editor-problem", "problem", Messages.esc("warning: " + w)));
    }

    // ---------- pick block (GUI) ----------

    public void startPick(Player p, String id, int step) {
        startPick(p, id, step, -1);
    }

    public void startPick(Player p, String id, int step, int opt) {
        picks.put(p.getUniqueId(), new Pick(id, step, opt, System.currentTimeMillis() + 30_000));
        p.closeInventory();
        p.sendMessage(m.get("editor-pick"));
    }

    /** Captures a right-click for a pending pick. Returns true if the click was consumed. */
    public boolean capturePick(Player p, Block b) {
        Pick pk = picks.get(p.getUniqueId());
        if (pk == null) return false;
        picks.remove(p.getUniqueId());
        if (System.currentTimeMillis() > pk.expires()) return false;
        setBlock(p, pk.questId(), pk.step(), pk.opt(), b);
        if (pk.opt() >= 0) plugin.menus().openOption(p, pk.questId(), pk.step(), pk.opt());
        else plugin.menus().openStep(p, pk.questId(), pk.step());
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
            for (Trigger t : Trigger.leaves(q.steps().get(i).trigger())) {
                String world = switch (t) {
                    case Trigger.Reach r -> r.world();
                    case Trigger.Region r -> r.world();
                    case Trigger.SignClick s -> s.sign() == null ? null : s.sign().world();
                    case Trigger.Interact in -> in.block() == null ? null : in.block().world();
                    case Trigger.Place pl -> pl.area() == null ? null : pl.area().world();
                    default -> null;
                };
                if (world != null && Bukkit.getWorld(world) == null) out.add("step " + (i + 1) + ": world '" + world + "' is not loaded");
                if (t instanceof Trigger.AdvancementDone a && Bukkit.getAdvancement(a.key()) == null) {
                    out.add("step " + (i + 1) + ": unknown advancement " + a.key());
                }
            }
        }
        out.addAll(Validation.flowWarnings(q));
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
        for (Quest q : editableQuests(p)) {
            if (id != null && !q.id().equals(id)) continue;
            for (String e : errors(q, q.published())) out.add(q.id() + ": " + e);
            for (String w : warnings(q)) out.add(q.id() + ": warning: " + w);
        }
        if (id == null) out.addAll(plugin.signs().problems(editableQuests(p)));
        if (out.isEmpty()) p.sendMessage(m.get("editor-valid"));
        else for (String s : out) p.sendMessage(m.get("editor-problem", "problem", Messages.esc(s)));
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

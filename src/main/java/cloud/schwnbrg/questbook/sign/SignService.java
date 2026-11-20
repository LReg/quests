package cloud.schwnbrg.questbook.sign;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.runtime.QuestService;
import cloud.schwnbrg.questbook.runtime.SignState;
import cloud.schwnbrg.questbook.storage.PlayerData;
import cloud.schwnbrg.questbook.storage.Progress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** Links signs to quests, keeps the sign index, and sends per-player sign text as packets. */
public final class SignService {

    private final QuestbookPlugin plugin;
    private final QuestService quests;
    private final Messages m;
    private final NamespacedKey questKey;
    private final NamespacedKey roleKey;

    /** world -> chunkKey -> quest ids with a start sign in that chunk */
    private final Map<String, Map<Long, List<String>>> index = new HashMap<>();

    public SignService(QuestbookPlugin plugin, QuestService quests, Messages m) {
        this.plugin = plugin;
        this.quests = quests;
        this.m = m;
        this.questKey = new NamespacedKey(plugin, "quest");
        this.roleKey = new NamespacedKey(plugin, "role");
    }

    // ---------- index ----------

    public void rebuildIndex() {
        index.clear();
        for (Quest q : quests.quests().values()) if (q.startSign() != null) addToIndex(q);
    }

    private void addToIndex(Quest q) {
        BlockRef b = q.startSign();
        index.computeIfAbsent(b.world(), k -> new HashMap<>()).computeIfAbsent(b.chunkKey(), k -> new ArrayList<>()).add(q.id());
    }

    public List<Quest> startSignsInChunk(String world, int cx, int cz) {
        Map<Long, List<String>> w = index.get(world);
        if (w == null) return List.of();
        List<String> ids = w.get((((long) cx) << 32) ^ (cz & 0xffffffffL));
        if (ids == null) return List.of();
        List<Quest> out = new ArrayList<>();
        for (String id : ids) {
            Quest q = quests.quest(id);
            if (q != null && q.startSign() != null) out.add(q);
        }
        return out;
    }

    /** The quest whose start sign is this block, or null. */
    public Quest questAt(Block b) {
        for (Quest q : startSignsInChunk(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4)) {
            if (q.startSign().sameAs(b)) return q;
        }
        return null;
    }

    // ---------- linking ----------

    /** True if the sign still carries our tags (whether or not a quest uses it). */
    private boolean hasTag(Sign s) {
        return s.getPersistentDataContainer().has(roleKey, PersistentDataType.STRING);
    }

    /**
     * Removes tags from a sign no quest uses any more (quest file deleted by hand, step changed).
     * Returns true if it was stale and has been cleaned.
     */
    public boolean cleanIfStale(Block b) {
        if (!(b.getState() instanceof Sign s) || !hasTag(s)) return false;
        if (questAt(b) != null || quests.isStepSign(b)) return false;
        untag(b);
        return true;
    }

    /** Sends every online player the sign's real text again (after it stopped being a start sign). */
    public void restore(BlockRef ref) {
        Block b = ref == null ? null : ref.block();
        if (b == null || !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) || !(b.getState() instanceof Sign s)) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld() == b.getWorld()) p.sendBlockUpdate(b.getLocation(), s);
    }

    /** Frees a step sign if no quest uses it as a step sign any more. */
    public void releaseStepSign(BlockRef ref) {
        if (ref == null) return;
        Block b = ref.block();
        if (b == null || !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) return;
        cleanIfStale(b);
    }

    /** Tags the sign, waxes it. Throws IllegalArgumentException if the block is not a sign. */
    public void tag(Block b, String role, String questId) {
        if (!(b.getState() instanceof Sign s)) throw new IllegalArgumentException("not a sign");
        if (questId != null) s.getPersistentDataContainer().set(questKey, PersistentDataType.STRING, questId);
        String old = s.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        if (old == null || role.equals("start")) s.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role);
        s.setWaxed(true);
        s.update();
    }

    public void untag(Block b) {
        if (!(b.getState() instanceof Sign s)) return;
        s.getPersistentDataContainer().remove(questKey);
        s.getPersistentDataContainer().remove(roleKey);
        s.update();
    }

    /**
     * Links a sign as start sign. Returns an error message key or null. The sign must be blank on both sides, so
     * nobody can turn a sign with somebody's writing into a waxed, unbreakable quest sign.
     */
    public String linkStart(Quest q, Block b) {
        if (!(b.getState() instanceof Sign s)) return "editor-not-a-sign";
        Quest other = questAt(b);
        if (other != null && !other.id().equals(q.id())) return "editor-sign-taken";
        if (quests.isStepSign(b)) return "editor-sign-taken";
        if (!isBlank(s)) return "editor-sign-not-empty";
        return null;
    }

    /** True if no line on either side of the sign has visible text. Our per-player text is sent as packets, never stored. */
    static boolean isBlank(Sign s) {
        for (Side side : Side.values()) {
            for (Component line : s.getSide(side).lines()) {
                if (!PlainTextComponentSerializer.plainText().serialize(line).isBlank()) return false;
            }
        }
        return true;
    }

    public void relinkIndex(Quest before, Quest after) {
        if (before != null && before.startSign() != null) {
            Map<Long, List<String>> w = index.get(before.startSign().world());
            if (w != null) {
                List<String> l = w.get(before.startSign().chunkKey());
                if (l != null) l.remove(before.id());
            }
        }
        if (after != null && after.startSign() != null) addToIndex(after);
    }

    // ---------- per-player text ----------

    public void refreshAll(Player p) {
        for (Quest q : quests.quests().values()) refresh(p, q.id());
    }

    /** At most 20 sign packets per player per tick; the rest is deferred to the next tick. */
    private static final int PER_TICK = 20;
    private final Map<UUID, long[]> budget = new HashMap<>();

    private boolean takeBudget(Player p) {
        long tick = Bukkit.getCurrentTick();
        long[] b = budget.computeIfAbsent(p.getUniqueId(), k -> new long[] {tick, 0});
        if (b[0] != tick) {
            b[0] = tick;
            b[1] = 0;
        }
        if (b[1] >= PER_TICK) return false;
        b[1]++;
        return true;
    }

    public void forget(UUID id) { budget.remove(id); }

    public void refresh(Player p, String questId) {
        Quest q = quests.quest(questId);
        if (q == null || q.startSign() == null || !p.isOnline()) return;
        BlockRef r = q.startSign();
        World w = Bukkit.getWorld(r.world());
        if (w == null || p.getWorld() != w || !w.isChunkLoaded(r.x() >> 4, r.z() >> 4)) return;
        double view = Bukkit.getViewDistance() * 16.0 + 16;
        if (p.getLocation().distanceSquared(new org.bukkit.Location(w, r.x(), r.y(), r.z())) > view * view) return;
        if (!takeBudget(p)) {
            Bukkit.getScheduler().runTask(plugin, () -> refresh(p, questId));
            return;
        }
        Block b = w.getBlockAt(r.x(), r.y(), r.z());
        if (!(b.getState() instanceof Sign sign)) return;
        SignState st = quests.stateFor(p, q);
        if (st == SignState.DRAFT) {
            // vanilla text: restore what the block really says
            p.sendBlockUpdate(b.getLocation(), sign);
            return;
        }
        List<String> lines = lines(p, q, st);
        var side = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) side.line(i, i < lines.size() ? Messages.mm(lines.get(i)) : Component.empty());
        side.setGlowingText(st == SignState.AVAILABLE);
        side.setColor(st == SignState.COMPLETED ? DyeColor.GRAY : DyeColor.BLACK);
        p.sendBlockUpdate(b.getLocation(), sign);
    }

    private List<String> lines(Player p, Quest q, SignState st) {
        String title = Messages.esc(plain(q.title()));
        List<String> t = wrap(title, 15);
        String t1 = t.size() > 0 ? t.get(0) : "";
        String t2 = t.size() > 1 ? t.get(1) : "";
        return switch (st) {
            case AVAILABLE -> List.of(m.raw("sign-available-1"), t1, t2, m.raw("sign-available-4"));
            case ACTIVE -> {
                PlayerData d = quests.data(p);
                Progress pr = d == null ? null : d.progress.get(q.id());
                int step = pr == null ? 1 : pr.step() + 1;
                yield List.of(t1, t2, m.raw("sign-active-3", "step", String.valueOf(step), "total", String.valueOf(q.steps().size())), "");
            }
            case COMPLETED -> List.of(t1, t2, m.raw("sign-done-3"), "");
            case COMPLETED_REPEATABLE -> List.of(t1, m.raw("sign-done-3"), m.raw("sign-done-repeat"), "");
            case LOCKED -> {
                Quest req = quests.firstMissing(p, q);
                List<String> rt = wrap(Messages.esc(req == null ? "?" : plain(req.title())), 15);
                yield List.of(m.raw("sign-locked-1"), m.raw("sign-locked-2"), rt.size() > 0 ? rt.get(0) : "", rt.size() > 1 ? rt.get(1) : "");
            }
            default -> List.of();
        };
    }

    static String plain(String s) {
        return PlainTextComponentSerializer.plainText().serialize(Messages.mm(s));
    }

    static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > width) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word.length() > width ? word.substring(0, width) : word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Refreshes the sign for every online player (after publish, title change...). */
    public void refreshForAll(String questId) {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p, questId);
    }

    /** Verifies the sign links of these quests for /qe validate. */
    public List<String> problems(java.util.Collection<Quest> which) {
        List<String> out = new ArrayList<>();
        for (Quest q : which) {
            if (q.startSign() != null) {
                Block b = q.startSign().block();
                if (b != null && b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && !(b.getState() instanceof Sign)) {
                    out.add(q.id() + ": start sign at " + q.startSign() + " is not a sign");
                }
            }
            for (int i = 0; i < q.steps().size(); i++) {
                Step s = q.steps().get(i);
                if (s.trigger() instanceof Trigger.SignClick sc && sc.sign() != null) {
                    Block b = sc.sign().block();
                    if (b != null && b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && !(b.getState() instanceof Sign)) {
                        out.add(q.id() + " step " + (i + 1) + ": sign at " + sc.sign() + " is not a sign");
                    }
                }
            }
        }
        return out;
    }
}

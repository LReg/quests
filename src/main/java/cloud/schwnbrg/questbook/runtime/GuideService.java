package cloud.schwnbrg.questbook.runtime;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.editor.PreviewService;
import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.storage.PlayerData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Player guidance, turned on per quest by its editor: particles outlining where the current step wants the player,
 * and a compass bar (boss bar at the top) whose marker points there. Main thread only.
 */
public final class GuideService {

    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(255, 215, 90), 1.1f);
    /** Characters of the compass strip; it covers 180° of view. */
    private static final int HALF = 22;
    private static final double DEG_PER_CHAR = 90.0 / HALF;

    private final QuestbookPlugin plugin;
    private final QuestService quests;
    private final Messages m;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public GuideService(QuestbookPlugin plugin, QuestService quests, Messages m) {
        this.plugin = plugin;
        this.quests = quests;
        this.m = m;
    }

    public void start() {
        long interval = Math.max(2, plugin.getConfig().getLong("guide.interval_ticks", 5));
        Bukkit.getScheduler().runTaskTimer(plugin, (Runnable) this::tick, interval, interval);
    }

    /** /quest track &lt;quest&gt; (the journal's [Track] button): the compass follows this quest. */
    public void track(Player p, String questId) {
        PlayerData d = quests.data(p);
        Quest q = quests.quest(questId);
        if (d == null || q == null || quests.realRun(p, questId) == null) {
            p.sendMessage(m.get("not-active"));
            return;
        }
        d.tracked = questId;
        p.sendMessage(m.get(q.guide().compass() ? "tracking" : "tracking-no-compass", "title", q.title()));
        tick(p);
    }

    public void quit(Player p) {
        BossBar b = bars.remove(p.getUniqueId());
        if (b != null) p.hideBossBar(b);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) tick(p);
    }

    private void tick(Player p) {
        Run compassRun = null;
        Quest compassQuest = null;
        PlayerData d = quests.data(p);
        double max = plugin.getConfig().getDouble("guide.particle_distance", 48);
        for (Run r : quests.runsOf(p)) {
            Quest q = quests.quest(r.questId);
            if (q == null || !r.armed || r.step >= q.steps().size()) continue;
            Step s = q.steps().get(r.step);
            if (q.guide().particles()) for (Trigger t : Trigger.leaves(s.trigger())) PreviewService.draw(p, t, DUST, max);
            if (!q.guide().compass()) continue;
            boolean tracked = d != null && r.questId.equals(d.tracked);
            if (compassRun == null || tracked || r.test) {
                compassRun = r;
                compassQuest = q;
            }
        }
        Location target = compassRun == null ? null : nearest(p, targets(compassQuest.steps().get(compassRun.step).trigger()));
        if (target == null) {
            BossBar b = bars.remove(p.getUniqueId());
            if (b != null) p.hideBossBar(b);
            return;
        }
        Component name = strip(p, target, compassQuest);
        BossBar b = bars.get(p.getUniqueId());
        if (b == null) {
            b = BossBar.bossBar(name, 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), b);
            p.showBossBar(b);
        } else {
            b.name(name);
        }
    }

    /** Where a step's trigger(s) are, as block-centre locations. Dimensions, kills, answers and buttons have none. */
    public static List<Location> targets(Trigger trigger) {
        List<Location> out = new ArrayList<>();
        for (Trigger t : Trigger.leaves(trigger)) {
            switch (t) {
                case Trigger.Reach r -> add(out, r.world(), r.x(), r.y(), r.z());
                case Trigger.Region r -> add(out, r.world(), (r.x1() + r.x2()) / 2.0 + 0.5, r.baseY(), (r.z1() + r.z2()) / 2.0 + 0.5);
                case Trigger.SignClick s -> block(out, s.sign());
                case Trigger.Interact i -> block(out, i.block());
                case Trigger.Place pl -> { if (pl.area() != null) out.addAll(targets(pl.area())); }
                default -> { }
            }
        }
        return out;
    }

    private static void block(List<Location> out, BlockRef b) {
        if (b != null) add(out, b.world(), b.x() + 0.5, b.y() + 0.5, b.z() + 0.5);
    }

    private static void add(List<Location> out, String world, double x, double y, double z) {
        World w = Bukkit.getWorld(world);
        if (w != null) out.add(new Location(w, x, y, z));
    }

    /** The closest target in the player's world, or (if none is here) the first one elsewhere. */
    public static Location nearest(Player p, List<Location> targets) {
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (Location l : targets) {
            if (!l.getWorld().equals(p.getWorld())) continue;
            double dd = l.distanceSquared(p.getLocation());
            if (dd < bestD) {
                bestD = dd;
                best = l;
            }
        }
        return best != null ? best : targets.isEmpty() ? null : targets.get(0);
    }

    /** Minecraft yaw from the player towards a point: 0 = south, 90 = west, 180 = north, 270 = east. */
    static double bearing(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** "north-east" etc., for the journal. */
    public String direction(Player p, Location target) {
        if (!target.getWorld().equals(p.getWorld())) return m.raw("guide-other-world", "world", target.getWorld().getName());
        double dx = target.getX() - p.getLocation().getX(), dz = target.getZ() - p.getLocation().getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        if (dist < 4) return m.raw("guide-here");
        String[] names = {"s", "sw", "w", "nw", "n", "ne", "e", "se"};
        int i = (int) Math.floorMod(Math.round(bearing(dx, dz) / 45.0), 8);
        return m.raw("guide-direction", "distance", String.valueOf(dist), "dir", m.raw("guide-dir-" + names[i]));
    }

    /** The compass strip: cardinal letters, ticks and the quest marker, as seen from where the player looks. */
    private Component strip(Player p, Location target, Quest q) {
        if (!target.getWorld().equals(p.getWorld())) {
            return m.get("guide-bar-other-world", "title", q.title(), "world", target.getWorld().getName());
        }
        double yaw = p.getLocation().getYaw();
        double dx = target.getX() - p.getLocation().getX(), dz = target.getZ() - p.getLocation().getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double rel = wrap(bearing(dx, dz) - yaw);
        int marker = (int) Math.round(rel / DEG_PER_CHAR);
        StringBuilder sb = new StringBuilder();
        for (int k = -HALF; k <= HALF; k++) {
            if (k == marker || (k == -HALF && marker < -HALF) || (k == HALF && marker > HALF)) {
                sb.append(marker < -HALF ? "<gold>«</gold>" : marker > HALF ? "<gold>»</gold>" : "<gold><bold>◆</bold></gold>");
                continue;
            }
            double a = wrap(yaw + k * DEG_PER_CHAR);
            String letter = cardinal(a);
            if (letter != null) sb.append("<white><bold>").append(letter).append("</bold></white>");
            else sb.append(Math.floorMod(Math.round(a), 45) < DEG_PER_CHAR ? "<gray>|</gray>" : "<dark_gray>·</dark_gray>");
        }
        String distance = dist < 4 ? m.raw("guide-bar-here") : m.raw("guide-bar-distance", "distance", String.valueOf(Math.round(dist)));
        return m.get("guide-bar", "strip", sb.toString(), "distance", distance, "title", q.title());
    }

    /** N/E/S/W when this angle is the closest character to one of them. */
    private static String cardinal(double a) {
        String[] letters = {"S", "W", "N", "E"};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(wrap(a - i * 90)) < DEG_PER_CHAR / 2) return letters[i];
        }
        return null;
    }

    /** Into -180..180. */
    static double wrap(double a) {
        a = a % 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}

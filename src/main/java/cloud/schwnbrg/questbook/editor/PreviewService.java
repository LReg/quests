package cloud.schwnbrg.questbook.editor;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Particle preview of a quest's triggers. Only the editor sees the particles. */
public final class PreviewService {

    private static final Particle.DustOptions OTHER = new Particle.DustOptions(Color.fromRGB(255, 170, 0), 1.0f);
    private static final Particle.DustOptions CURRENT = new Particle.DustOptions(Color.fromRGB(80, 255, 120), 1.3f);

    private final QuestbookPlugin plugin;
    private final EditorService editor;

    public PreviewService(QuestbookPlugin plugin, EditorService editor) {
        this.plugin = plugin;
        this.editor = editor;
    }

    public void start() {
        long interval = Math.max(2, plugin.getConfig().getLong("preview.interval_ticks", 10));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private void tick() {
        editor.expireIdle();
        double max = plugin.getConfig().getDouble("preview.max_distance", 64);
        for (Map.Entry<UUID, EditorSession> e : editor.sessions().entrySet()) {
            EditorSession s = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (!s.preview || p == null || s.questId == null) continue;
            Quest q = plugin.quests().quest(s.questId);
            if (q == null) continue;
            for (int i = 0; i < q.steps().size(); i++) draw(p, q.steps().get(i), i == s.step ? CURRENT : OTHER, max);
        }
    }

    private void draw(Player p, Step step, Particle.DustOptions c, double max) {
        World w = p.getWorld();
        Location pl = p.getLocation();
        switch (step.trigger()) {
            case Trigger.Reach r -> {
                if (!r.world().equals(w.getName())) return;
                Location centre = new Location(w, r.x(), r.y(), r.z());
                if (centre.distance(pl) > max + r.radius()) return;
                int pts = (int) Math.min(160, Math.max(8, 2 * Math.PI * r.radius() / 0.6));
                for (int i = 0; i < pts; i++) {
                    double a = 2 * Math.PI * i / pts;
                    dot(p, c, r.x() + Math.cos(a) * r.radius(), r.y(), r.z() + Math.sin(a) * r.radius());
                    dot(p, c, r.x() + Math.cos(a) * r.radius(), r.y() + Math.sin(a) * r.radius(), r.z());
                }
            }
            case Trigger.Region r -> {
                if (!r.world().equals(w.getName())) return;
                double x0 = Math.min(r.x1(), r.x2()), x1 = Math.max(r.x1(), r.x2()) + 1;
                double z0 = Math.min(r.z1(), r.z2()), z1 = Math.max(r.z1(), r.z2()) + 1;
                double y0 = r.baseY(), y1 = r.baseY() + r.height();
                if (new Location(w, (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2).distance(pl) > max + 32) return;
                int[] budget = {400};
                double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
                for (double y : ys) for (double z : zs) line(p, c, x0, y, z, x1, y, z, budget);
                for (double x : xs) for (double z : zs) line(p, c, x, y0, z, x, y1, z, budget);
                for (double x : xs) for (double y : ys) line(p, c, x, y, z0, x, y, z1, budget);
            }
            case Trigger.SignClick t -> cube(p, c, t.sign(), max);
            case Trigger.Interact t -> cube(p, c, t.block(), max);
            default -> { }
        }
    }

    private void cube(Player p, Particle.DustOptions c, BlockRef b, double max) {
        if (b == null || !b.world().equals(p.getWorld().getName())) return;
        if (p.getLocation().distance(new Location(p.getWorld(), b.x(), b.y(), b.z())) > max) return;
        int[] budget = {60};
        double[] xs = {b.x(), b.x() + 1}, ys = {b.y(), b.y() + 1}, zs = {b.z(), b.z() + 1};
        for (double y : ys) for (double z : zs) line(p, c, xs[0], y, z, xs[1], y, z, budget);
        for (double x : xs) for (double z : zs) line(p, c, x, ys[0], z, x, ys[1], z, budget);
        for (double x : xs) for (double y : ys) line(p, c, x, y, zs[0], x, y, zs[1], budget);
    }

    private void line(Player p, Particle.DustOptions c, double ax, double ay, double az, double bx, double by, double bz, int[] budget) {
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        int n = Math.max(1, (int) (len / 0.5));
        for (int i = 0; i <= n && budget[0] > 0; i++) {
            double t = (double) i / n;
            dot(p, c, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t);
            budget[0]--;
        }
    }

    private void dot(Player p, Particle.DustOptions c, double x, double y, double z) {
        p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, c);
    }
}

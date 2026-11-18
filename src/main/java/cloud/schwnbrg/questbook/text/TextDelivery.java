package cloud.schwnbrg.questbook.text;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sends step text line by line with typewriter pacing. One queue per player. Every job carries an owner tag
 * (the quest run), so cancelling one quest never drops the text of another.
 */
public final class TextDelivery {

    private record Job(Object owner, List<Component> lines, Runnable done) {}

    private static final class Slot {
        final Queue<Job> queue = new ArrayDeque<>();
        Job current;
        BukkitTask task;
    }

    private final Plugin plugin;
    private final Map<UUID, Slot> slots = new HashMap<>();

    public TextDelivery(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Queues a delivery. {@code done} runs after the last line, unless the job was cancelled. */
    public void deliver(Player p, Object owner, List<Component> lines, Runnable done) {
        Slot s = slots.computeIfAbsent(p.getUniqueId(), k -> new Slot());
        s.queue.add(new Job(owner, lines, done));
        if (s.current == null) next(p.getUniqueId());
    }

    private void next(UUID id) {
        Slot s = slots.get(id);
        if (s == null || s.current != null) return;
        Job job = s.queue.poll();
        if (job == null) return;
        s.current = job;
        send(id, s, job, 0);
    }

    private void send(UUID id, Slot s, Job job, int i) {
        if (slots.get(id) != s || s.current != job) return; // cancelled meanwhile
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            cancel(id);
            return;
        }
        if (i >= job.lines().size()) {
            s.current = null;
            s.task = null;
            job.done().run();
            next(id);
            return;
        }
        Component line = job.lines().get(i);
        p.sendMessage(line);
        long delay = 0;
        // The footer (last line) needs no pause after it
        if (i < job.lines().size() - 1) delay = delayFor(PlainTextComponentSerializer.plainText().serialize(line));
        if (!plugin.getConfig().getBoolean("text.enabled", true)) delay = 0;
        if (delay <= 0) {
            send(id, s, job, i + 1);
        } else {
            s.task = Bukkit.getScheduler().runTaskLater(plugin, () -> send(id, s, job, i + 1), Math.max(1, delay / 50));
        }
    }

    private long delayFor(String plain) {
        long base = plugin.getConfig().getLong("text.base_delay_ms", 600);
        long per = plugin.getConfig().getLong("text.per_char_ms", 25);
        long max = plugin.getConfig().getLong("text.max_delay_ms", 2500);
        return plain.isEmpty() ? base : Math.min(max, base + per * plain.length());
    }

    /** Drops only the jobs of one owner. If its job is the running one, the next queued job starts. */
    public void cancel(UUID id, Object owner) {
        Slot s = slots.get(id);
        if (s == null) return;
        s.queue.removeIf(j -> j.owner() == owner);
        if (s.current != null && s.current.owner() == owner) {
            if (s.task != null) s.task.cancel();
            s.task = null;
            s.current = null;
            next(id);
        }
    }

    /** Drops everything queued for the player and stops the running delivery (quit, disable). */
    public void cancel(UUID id) {
        Slot s = slots.remove(id);
        if (s != null && s.task != null) s.task.cancel();
    }

    public void cancelAll() {
        for (UUID id : List.copyOf(slots.keySet())) cancel(id);
    }
}

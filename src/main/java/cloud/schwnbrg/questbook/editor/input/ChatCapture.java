package cloud.schwnbrg.questbook.editor.input;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/** Chat capture fallback for text input: the editor types lines in chat, which are not broadcast. */
public final class ChatCapture implements Listener {

    private static final class Session {
        final List<String> lines = new ArrayList<>();
        final boolean single;
        final Consumer<String> onSave;
        final Runnable onCancel;
        BukkitTask timeout;

        Session(boolean single, Consumer<String> onSave, Runnable onCancel) {
            this.single = single;
            this.onSave = onSave;
            this.onCancel = onCancel;
        }
    }

    private final QuestbookPlugin plugin;
    private final Messages m;
    /** Read on the async chat thread, written on the main thread. */
    private final Map<UUID, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatCapture(QuestbookPlugin plugin, Messages m) {
        this.plugin = plugin;
        this.m = m;
    }

    public boolean active(Player p) { return sessions.containsKey(p.getUniqueId()); }

    public void begin(Player p, boolean single, Consumer<String> onSave, Runnable onCancel) {
        end(p);
        Session s = new Session(single, onSave, onCancel);
        s.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(p.getUniqueId()) == s) {
                sessions.remove(p.getUniqueId());
                p.sendMessage(m.get("input-timeout"));
                onCancel.run();
            }
        }, 20L * 60 * 5);
        sessions.put(p.getUniqueId(), s);
        p.closeInventory();
        p.sendMessage(m.get(single ? "input-chat-single" : "input-chat-help"));
    }

    private void end(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s != null && s.timeout != null) s.timeout.cancel();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!sessions.containsKey(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> line(p, text));
    }

    private void line(Player p, String text) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        String t = text.strip();
        if (s.single) {
            end(p);
            if (t.equalsIgnoreCase("cancel")) s.onCancel.run();
            else s.onSave.accept(t);
            return;
        }
        switch (t.toLowerCase()) {
            case "done" -> {
                end(p);
                s.onSave.accept(String.join("\n", s.lines));
            }
            case "cancel" -> {
                end(p);
                p.sendMessage(m.get("input-cancelled"));
                s.onCancel.run();
            }
            case "undo" -> {
                if (!s.lines.isEmpty()) s.lines.remove(s.lines.size() - 1);
                p.sendMessage(m.get("input-undo", "count", String.valueOf(s.lines.size())));
            }
            default -> {
                String line = t.equals("-") ? "" : text;
                s.lines.add(line);
                p.sendMessage(m.get("input-echo", "n", String.valueOf(s.lines.size())).append(Messages.mm(line)));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { end(e.getPlayer()); }
}

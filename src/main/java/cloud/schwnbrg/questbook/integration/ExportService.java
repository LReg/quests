package cloud.schwnbrg.questbook.integration;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.model.Quest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;

/** Posts START / COMPLETE / ABANDON events to a webhook, never blocking gameplay. */
public final class ExportService {

    private static final int MAX_QUEUE = 500;

    private final QuestbookPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
    private final AtomicBoolean draining = new AtomicBoolean();

    public ExportService(QuestbookPlugin plugin) {
        this.plugin = plugin;
    }

    public void send(String type, Quest q, Player p, int step) {
        String url = plugin.getConfig().getString("export.webhook_url", "");
        if (url == null || url.isBlank()) return;
        String json = "{\"type\":" + js(type) + ",\"quest\":" + js(q.id()) + ",\"title\":" + js(cloud.schwnbrg.questbook.model.Validation.plain(q.title()))
                + ",\"player\":" + js(p.getName()) + ",\"uuid\":" + js(p.getUniqueId().toString()) + ",\"step\":" + step
                + ",\"at\":" + js(Instant.now().toString()) + ",\"server\":" + js(plugin.getConfig().getString("export.server_name", "schwnbrg")) + "}";
        if (!queue.offer(json)) return; // dropped when more than 500 are queued
        if (draining.compareAndSet(false, true)) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::drain);
    }

    private void drain() {
        try {
            String body;
            while ((body = queue.poll()) != null) {
                String url = plugin.getConfig().getString("export.webhook_url", "");
                if (url == null || url.isBlank()) continue;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
                        String secret = plugin.getConfig().getString("export.secret", "");
                        if (secret != null && !secret.isBlank()) rb.header("X-Questbook-Secret", secret);
                        HttpResponse<Void> r = client.send(rb.build(), HttpResponse.BodyHandlers.discarding());
                        if (r.statusCode() < 500) break;
                    } catch (Exception e) {
                        plugin.getLogger().fine("Export failed: " + e);
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            draining.set(false);
            if (!queue.isEmpty() && draining.compareAndSet(false, true)) drain();
        }
    }

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}

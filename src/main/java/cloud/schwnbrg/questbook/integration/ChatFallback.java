package cloud.schwnbrg.questbook.integration;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.Quest;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Chat versions of the intro and the text input. */
public class ChatFallback implements DialogSupport {

    protected final QuestbookPlugin plugin;

    public ChatFallback(QuestbookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void intro(Player p, Quest q) {
        Messages m = plugin.messages();
        var quests = plugin.quests();
        p.sendMessage(m.get("intro-header", "title", q.title()));
        if (!q.summary().isBlank()) p.sendMessage(m.get("intro-summary", "summary", q.summary()));
        p.sendMessage(m.get("intro-meta", "total", String.valueOf(q.steps().size()), "author", Messages.esc(q.author())));
        p.sendMessage(Component.empty()
                .append(quests.button("btn-accept", () -> {
                    Player cur = org.bukkit.Bukkit.getPlayer(p.getUniqueId());
                    if (cur != null) quests.acceptById(cur, q.id());
                }))
                .append(Component.text("  "))
                .append(quests.button("btn-decline", () -> { })));
    }

    @Override
    public void textInput(Player p, String title, String initial, boolean multiline, Consumer<String> onSave, Runnable onCancel) {
        plugin.chatCapture().begin(p, !multiline, onSave, onCancel);
        if (multiline && initial != null && !initial.isBlank()) {
            p.sendMessage(plugin.messages().get("input-current"));
            for (String l : initial.split("\n")) p.sendMessage(Component.text("  ").append(Component.text(l)));
        }
    }
}

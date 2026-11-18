package cloud.schwnbrg.questbook.integration;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.config.Messages;
import cloud.schwnbrg.questbook.model.Quest;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

/** Paper dialog screens (quest intro and editor text input). Falls back to chat on errors. */
public final class PaperDialogs extends ChatFallback {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).build();

    public PaperDialogs(QuestbookPlugin plugin) {
        super(plugin);
    }

    /** Dialog callbacks may arrive off the main thread; game state changes belong on it. */
    private void main(Runnable r) {
        if (org.bukkit.Bukkit.isPrimaryThread()) r.run();
        else org.bukkit.Bukkit.getScheduler().runTask(plugin, r);
    }

    @Override
    public void intro(Player p, Quest q) {
        Messages m = plugin.messages();
        try {
            ActionButton accept = ActionButton.create(m.get("btn-accept"), null, 120,
                    DialogAction.customClick((view, audience) -> main(() -> plugin.quests().acceptById(p, q.id())), ONCE));
            ActionButton decline = ActionButton.create(m.get("btn-decline"), null, 120, null);
            Dialog d = Dialog.create(b -> b.empty()
                    .base(DialogBase.builder(Messages.mm(q.title()))
                            .canCloseWithEscape(true)
                            .body(List.of(
                                    DialogBody.plainMessage(Messages.mm(q.summary().isBlank() ? " " : q.summary()), 300),
                                    DialogBody.plainMessage(m.get("intro-meta", "total", String.valueOf(q.steps().size()),
                                            "author", Messages.esc(q.author())), 300)))
                            .build())
                    .type(DialogType.confirmation(accept, decline)));
            p.showDialog(d);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Dialog failed, using chat: " + e);
            super.intro(p, q);
        }
    }

    @Override
    public void textInput(Player p, String title, String initial, boolean multiline, Consumer<String> onSave, Runnable onCancel) {
        try {
            DialogInput input = multiline
                    ? DialogInput.text("text", 320, Component.text(title), true, initial == null ? "" : initial, 2000,
                            io.papermc.paper.registry.data.dialog.input.TextDialogInput.MultilineOptions.create(null, 160))
                    : DialogInput.text("text", 320, Component.text(title), true, initial == null ? "" : initial, 256, null);
            ActionButton save = ActionButton.create(plugin.messages().get("btn-save"), null, 100,
                    DialogAction.customClick((view, audience) -> {
                        String t = view.getText("text");
                        main(() -> onSave.accept(t == null ? "" : t));
                    }, ONCE));
            ActionButton cancel = ActionButton.create(plugin.messages().get("btn-cancel"), null, 100,
                    DialogAction.customClick((view, audience) -> main(onCancel), ONCE));
            Dialog d = Dialog.create(b -> b.empty()
                    .base(DialogBase.builder(Component.text(title)).canCloseWithEscape(true).inputs(List.of(input)).build())
                    .type(DialogType.confirmation(save, cancel)));
            p.showDialog(d);
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Dialog failed, using chat: " + e);
            super.textInput(p, title, initial, multiline, onSave, onCancel);
        }
    }
}

package cloud.schwnbrg.questbook.integration;

import cloud.schwnbrg.questbook.model.Quest;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** The two places that may use Paper dialogs: the quest intro and editor text input. */
public interface DialogSupport {

    void intro(Player p, Quest q);

    /** Text input. onSave gets the lines joined with \n. Single-line inputs finish on the first line. */
    void textInput(Player p, String title, String initial, boolean multiline, Consumer<String> onSave, Runnable onCancel);
}

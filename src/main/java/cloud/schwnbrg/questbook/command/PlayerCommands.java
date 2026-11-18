package cloud.schwnbrg.questbook.command;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.bukkit.entity.Player;

/** /journal and /quest ... for players. */
public final class PlayerCommands {

    private final QuestbookPlugin plugin;

    public PlayerCommands(QuestbookPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Commands r) {
        r.register(Commands.literal("journal").executes(c -> {
            if (c.getSource().getSender() instanceof Player p) plugin.journal().open(p);
            return 1;
        }).build(), "Open your quest journal");

        LiteralArgumentBuilder<CommandSourceStack> quest = Commands.literal("quest");
        quest.then(Commands.literal("answer").then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
            if (c.getSource().getSender() instanceof Player p) plugin.quests().answer(p, StringArgumentType.getString(c, "text"));
            return 1;
        })));
        quest.then(Commands.literal("abandon").then(Commands.argument("quest", StringArgumentType.word())
                .suggests((c, b) -> {
                    if (c.getSource().getSender() instanceof Player p && plugin.quests().data(p) != null) {
                        plugin.quests().data(p).progress.values().stream()
                                .filter(x -> x.state() == cloud.schwnbrg.questbook.storage.Progress.State.ACTIVE).forEach(x -> b.suggest(x.questId()));
                    }
                    return b.buildFuture();
                })
                .executes(c -> {
                    if (c.getSource().getSender() instanceof Player p) plugin.quests().abandon(p, StringArgumentType.getString(c, "quest"), false);
                    return 1;
                })
                .then(Commands.literal("confirm").executes(c -> {
                    if (c.getSource().getSender() instanceof Player p) plugin.quests().abandon(p, StringArgumentType.getString(c, "quest"), true);
                    return 1;
                }))));
        quest.then(Commands.literal("continue").then(Commands.argument("quest", StringArgumentType.word()).executes(c -> {
            if (c.getSource().getSender() instanceof Player p) plugin.quests().continueStep(p, StringArgumentType.getString(c, "quest"));
            return 1;
        })));
        quest.then(Commands.literal("mute").executes(c -> mute(c.getSource(), true)));
        quest.then(Commands.literal("unmute").executes(c -> mute(c.getSource(), false)));
        quest.executes(c -> {
            if (c.getSource().getSender() instanceof Player p) plugin.journal().open(p);
            return 1;
        });
        r.register(quest.build(), "Quest commands", List.of());
    }

    private int mute(CommandSourceStack src, boolean muted) {
        if (src.getSender() instanceof Player p) {
            var d = plugin.quests().data(p);
            if (d != null) {
                d.rumoursMuted = muted;
                plugin.database().setMuted(p.getUniqueId(), muted);
                p.sendMessage(plugin.messages().get(muted ? "muted" : "unmuted"));
            }
        }
        return 1;
    }
}

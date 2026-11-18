package cloud.schwnbrg.questbook.config;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/** All player-facing strings from messages.yml (MiniMessage, {placeholders}). */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private YamlConfiguration yaml = new YamlConfiguration();

    public Messages(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        yaml = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("messages.yml");
        if (in != null) yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    public String raw(String key, String... pairs) {
        String s = yaml.getString(key);
        if (s == null) return key;
        return fill(s, pairs);
    }

    public List<String> rawList(String key) {
        return yaml.getStringList(key);
    }

    public Component get(String key, String... pairs) {
        return MM.deserialize(raw(key, pairs));
    }

    /** Deserializes arbitrary MiniMessage (quest text), falling back to plain text on parse errors. */
    public static Component mm(String text) {
        try {
            return MM.deserialize(text);
        } catch (RuntimeException e) {
            return Component.text(text);
        }
    }

    public static String esc(String s) {
        return MM.escapeTags(s);
    }

    private static String fill(String s, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) s = s.replace("{" + pairs[i] + "}", pairs[i + 1]);
        return s;
    }
}

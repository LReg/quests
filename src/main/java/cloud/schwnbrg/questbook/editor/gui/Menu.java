package cloud.schwnbrg.questbook.editor.gui;

import cloud.schwnbrg.questbook.config.Messages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** A chest menu. Items are display-only icons; every click and drag is cancelled by {@link MenuListener}. */
public final class Menu implements InventoryHolder {

    public record Click(Player player, boolean right, boolean shift) {
        /** Step size for number buttons: 1, or 5 with shift. */
        public int amount() { return shift ? 5 : 1; }
    }

    private final Inventory inv;
    private final Map<Integer, Consumer<Click>> actions = new HashMap<>();

    public Menu(int rows, Component title) {
        this.inv = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() { return inv; }

    public Menu set(int slot, ItemStack icon, Consumer<Click> action) {
        inv.setItem(slot, icon);
        if (action != null) actions.put(slot, action);
        return this;
    }

    public void click(int slot, Click c) {
        Consumer<Click> a = actions.get(slot);
        if (a != null) a.accept(c);
    }

    public void open(Player p) { p.openInventory(inv); }

    /** Creates an icon with a MiniMessage name and lore lines. */
    public static ItemStack icon(Material mat, String name, String... lore) {
        return icon(mat, name, List.of(lore));
    }

    public static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Messages.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Messages.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }
}

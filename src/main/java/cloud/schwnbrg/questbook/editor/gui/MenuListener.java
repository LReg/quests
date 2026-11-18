package cloud.schwnbrg.questbook.editor.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

/** Cancels every interaction with plugin menus (shift-click, number keys, drag, double-click) and dispatches clicks. */
public final class MenuListener implements Listener {

    private final Plugin plugin;

    public MenuListener(Plugin plugin) {
        this.plugin = plugin;
    }

    /** HIGHEST, so no later listener can un-cancel a click in one of our menus (R5). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClick() == ClickType.DOUBLE_CLICK) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getSlot();
        Menu.Click click = new Menu.Click(p, e.getClick().isRightClick(), e.getClick().isShiftClick());
        // menus open and close inventories from their actions: do that on the next tick, not inside the click event
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().getHolder() == menu) menu.click(slot, click);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder() instanceof Menu || e.getDestination().getHolder() instanceof Menu) e.setCancelled(true);
    }
}

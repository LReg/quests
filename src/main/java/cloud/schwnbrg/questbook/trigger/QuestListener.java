package cloud.schwnbrg.questbook.trigger;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.runtime.QuestService;
import cloud.schwnbrg.questbook.sign.RumourService;
import cloud.schwnbrg.questbook.sign.SignService;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Player events: join/quit, movement triggers, signs, interaction, advancements, and sign protection. */
public final class QuestListener implements Listener {

    private final QuestbookPlugin plugin;
    private final QuestService quests;
    private final SignService signs;
    private final RumourService rumours;

    public QuestListener(QuestbookPlugin plugin, QuestService quests, SignService signs, RumourService rumours) {
        this.plugin = plugin;
        this.quests = quests;
        this.signs = signs;
        this.rumours = rumours;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { quests.join(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.editor().endSession(e.getPlayer().getUniqueId());
        quests.quit(e.getPlayer());
    }

    /** MONITOR so a move another plugin cancelled never completes a step. Returns before any lookup if nothing is armed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        Location to = e.getTo();
        quests.onLocation(p, to);
        rumours.onBlockMove(p, to);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            quests.onLocation(p);
            signs.refreshAll(p);
        });
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            quests.onLocation(p);
            signs.refreshAll(p);
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> quests.onLocation(p), 2L);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        quests.onAdvancement(e.getPlayer(), e.getAdvancement().getKey());
    }

    /** MONITOR: only kills that really happened count. getKiller() is the player who dealt the final blow, also by arrow. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null) quests.onKill(killer, e.getEntity());
    }

    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent e) {
        var chunk = e.getChunk();
        var list = signs.startSignsInChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (list.isEmpty()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && quests.isReady(p)) for (Quest q : list) signs.refresh(p, q.id());
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        boolean editor = p.hasPermission("questbook.editor");
        if (editor && plugin.editor().capturePick(p, b)) {
            e.setCancelled(true);
            return;
        }
        boolean isSign = Tag.ALL_SIGNS.isTagged(b.getType());
        if (isSign) signs.cleanIfStale(b); // tags left behind by a quest file deleted by hand
        Quest start = isSign ? signs.questAt(b) : null;
        boolean tagged = isSign && (start != null || quests.isStepSign(b));
        if (tagged && editor && p.isSneaking() && plugin.editor().maySignEdit(p, b)) return; // owners keep vanilla behaviour
        if (isSign) {
            if (quests.onSignClick(p, b)) {
                e.setCancelled(true);
                return;
            }
            if (start != null) {
                e.setCancelled(true);
                showIntro(p, start);
                return;
            }
            if (tagged) {
                e.setCancelled(true);
                return;
            }
        }
        // INTERACT never cancels: the vanilla interaction still happens. A click another plugin denied does not count.
        if (e.useInteractedBlock() == Event.Result.DENY) return;
        quests.onInteract(p, b);
    }

    /** MONITOR: only blocks that really stay placed count. The item in hand says what was placed (a wall torch is a torch). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        // not cancelled but still reverted by the server (spawn protection, build height): does not count
        if (!e.canBuild()) return;
        quests.onPlace(e.getPlayer(), e.getBlockPlaced(), e.getItemInHand().getType());
    }

    // HAVE_ITEM: checked a tick after anything that may change the inventory (the item is in it by then)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) laterInventory(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) laterInventory(p);
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) laterInventory(p);
    }

    private void laterInventory(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) quests.onInventory(p);
        });
    }

    private void showIntro(Player p, Quest q) {
        if (!quests.isReady(p)) {
            p.sendMessage(plugin.messages().get("loading"));
            return;
        }
        if (!q.published()) {
            // players see nothing of drafts; editors get a hint
            if (p.hasPermission("questbook.editor")) p.sendMessage(plugin.messages().get("editor-is-draft"));
            return;
        }
        var denial = quests.canAccept(p, q);
        if (denial != null) {
            p.sendMessage(plugin.messages().get(denial.key(), denial.pairs()));
            return;
        }
        plugin.dialogs().intro(p, q);
    }

    // ---------- protection ----------

    private boolean protectedSign(Block b) {
        // cheap type check first: explosions call this for every block in the blast
        return Tag.ALL_SIGNS.isTagged(b.getType()) && (signs.questAt(b) != null || quests.isStepSign(b));
    }

    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private boolean supportsProtected(Block b) { return !supportedSigns(b).isEmpty(); }

    /** The protected signs this block holds up: a sign drops when its support goes, so the support is protected too. */
    private java.util.List<Block> supportedSigns(Block b) {
        java.util.List<Block> out = new java.util.ArrayList<>();
        for (BlockFace f : FACES) {
            Block n = b.getRelative(f);
            if (!Tag.ALL_SIGNS.isTagged(n.getType()) || !protectedSign(n)) continue;
            if (Tag.CEILING_HANGING_SIGNS.isTagged(n.getType())) {
                if (f == BlockFace.UP) out.add(n);
            } else if (Tag.WALL_HANGING_SIGNS.isTagged(n.getType())) {
                BlockFace facing = n.getBlockData() instanceof Directional d ? d.getFacing() : BlockFace.NORTH;
                // hangs between two blocks on its sides
                if (f != BlockFace.UP && f != BlockFace.DOWN && f != facing && f != facing.getOppositeFace()) out.add(n);
            } else {
                BlockFace support = n.getBlockData() instanceof Directional d ? d.getFacing().getOppositeFace() : BlockFace.DOWN;
                if (n.getRelative(support).equals(b)) out.add(n);
            }
        }
        return out;
    }

    private boolean protectedBlock(Block b) {
        return protectedSign(b) || supportsProtected(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        boolean sign = protectedSign(b);
        java.util.List<Block> held = supportedSigns(b);
        if (!sign && held.isEmpty()) return;
        Player p = e.getPlayer();
        var ed = plugin.editor();
        // only editors may touch these blocks, and only those who own every quest that uses the sign
        if (!p.hasPermission("questbook.editor") || (sign && !ed.maySignEdit(p, b)) || !held.stream().allMatch(s -> ed.maySignEdit(p, s))) {
            e.setCancelled(true);
            return;
        }
        if (!sign) return; // editors may remove a support
        Quest q = signs.questAt(b);
        if (q != null) {
            plugin.editor().onStartSignBroken(p, q);
        } else {
            signs.untag(b);
            plugin.editor().onStepSignBroken(p, b);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (protectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (protectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Flowing water or lava would pop the sign off. */
    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (protectedSign(e.getToBlock())) e.setCancelled(true);
    }
}

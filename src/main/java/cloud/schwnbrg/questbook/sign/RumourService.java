package cloud.schwnbrg.questbook.sign;

import cloud.schwnbrg.questbook.QuestbookPlugin;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.runtime.QuestService;
import cloud.schwnbrg.questbook.runtime.SignState;
import cloud.schwnbrg.questbook.storage.Database;
import cloud.schwnbrg.questbook.storage.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** One-line action bar hint near unused quest signs. At most one per move, once per quest and player. */
public final class RumourService {

    private final QuestbookPlugin plugin;
    private final QuestService quests;
    private final SignService signs;
    private final Database db;

    public RumourService(QuestbookPlugin plugin, QuestService quests, SignService signs, Database db) {
        this.plugin = plugin;
        this.quests = quests;
        this.signs = signs;
        this.db = db;
    }

    public void onBlockMove(Player p, Location l) {
        if (!plugin.getConfig().getBoolean("rumours.enabled", true)) return;
        PlayerData d = quests.data(p);
        if (d == null || d.rumoursMuted) return;
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        double r = plugin.getConfig().getDouble("rumours.radius", 12);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Quest q : signs.startSignsInChunk(l.getWorld().getName(), cx + dx, cz + dz)) {
                    if (d.rumoursSeen.contains(q.id()) || !q.published()) continue;
                    var s = q.startSign();
                    if (l.distanceSquared(new Location(l.getWorld(), s.x() + 0.5, s.y() + 0.5, s.z() + 0.5)) > r * r) continue;
                    // only quests the player could accept right now (also respects max_active_quests)
                    if (quests.stateFor(p, q) != SignState.AVAILABLE || quests.canAccept(p, q) != null) continue;
                    d.rumoursSeen.add(q.id());
                    db.markRumour(p.getUniqueId(), q.id());
                    p.sendActionBar(plugin.messages().get("rumour"));
                    return;
                }
            }
        }
    }
}

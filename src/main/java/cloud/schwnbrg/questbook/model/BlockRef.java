package cloud.schwnbrg.questbook.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record BlockRef(String world, int x, int y, int z) {

    public static BlockRef of(Block b) {
        return new BlockRef(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public static BlockRef of(Location l) {
        return new BlockRef(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public Block block() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    public boolean sameAs(Block b) {
        return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getName().equals(world);
    }

    public long chunkKey() {
        return (((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xffffffffL);
    }

    @Override
    public String toString() {
        return world + " " + x + " " + y + " " + z;
    }
}

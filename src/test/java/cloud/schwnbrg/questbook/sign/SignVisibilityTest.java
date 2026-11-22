package cloud.schwnbrg.questbook.sign;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.model.BlockRef;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Start sign text is sent per player when the sign's chunk reaches the client. A sign whose chunk arrived must get
 * its text however far away it is, or it stays blank (the bug: away and back, some signs were empty).
 */
class SignVisibilityTest {

    private static final int VIEW = 10;

    /** Paper's chunk key: x in the low 32 bits, z in the high ones. */
    private static long key(int cx, int cz) {
        return (cx & 0xffffffffL) | ((cz & 0xffffffffL) << 32);
    }

    /** The chunks the server sends a player standing in chunk (pcx, pcz): vanilla's circle with a one-chunk margin. */
    private static Set<Long> sent(int pcx, int pcz) {
        Set<Long> out = new HashSet<>();
        for (int cx = pcx - VIEW - 2; cx <= pcx + VIEW + 2; cx++) {
            for (int cz = pcz - VIEW - 2; cz <= pcz + VIEW + 2; cz++) {
                long dx = Math.max(0, Math.abs(cx - pcx) - 1), dz = Math.max(0, Math.abs(cz - pcz) - 1);
                if (dx * dx + dz * dz < (long) VIEW * VIEW) out.add(key(cx, cz));
            }
        }
        return out;
    }

    /** A player whose client has exactly these chunks; nothing else of the interface is used. */
    private static Player player(Set<Long> chunks) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, m, args) -> {
            if (m.getName().equals("isChunkSent") && m.getParameterTypes()[0] == long.class) return chunks.contains((Long) args[0]);
            throw new UnsupportedOperationException(m.getName());
        });
    }

    @Test
    void signAtTheEdgeOfTheViewGetsItsText() {
        // player at block (56, 64, 8), chunk (3, 0); sign at block (191, 64, -112), chunk (11, -7)
        Player p = player(sent(3, 0));
        BlockRef sign = new BlockRef("world", 191, 64, -112);
        assertTrue(SignService.seesChunkOf(p, sign));
        // the old rule (view distance * 16 + 16 blocks) skipped it, and nothing sent it later
        double dx = 191 - 56, dz = -112 - 8, limit = VIEW * 16.0 + 16;
        assertTrue(dx * dx + dz * dz > limit * limit, "this sign is beyond the old limit");
    }

    @Test
    void signInAChunkNotSentYetWaitsForIt() {
        Player p = player(sent(3, 0));
        assertFalse(SignService.seesChunkOf(p, new BlockRef("world", 16 * 20, 64, 0)));
        // x and z are not mixed up: chunk (-7, 11) is not sent, (11, -7) is
        assertFalse(SignService.seesChunkOf(p, new BlockRef("world", -112, 64, 191)));
    }

    @Test
    void negativeCoordinatesFindTheirChunk() {
        Player p = player(Set.of(key(-1, -1)));
        assertTrue(SignService.seesChunkOf(p, new BlockRef("world", -1, 64, -16)));
        assertFalse(SignService.seesChunkOf(p, new BlockRef("world", 0, 64, -16)));
    }
}

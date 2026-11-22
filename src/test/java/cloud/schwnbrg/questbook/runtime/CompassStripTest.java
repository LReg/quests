package cloud.schwnbrg.questbook.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The compass bar's marker, and the up/down arrow next to it when the target is more than 5 blocks higher or lower. */
class CompassStripTest {

    private static final int WIDTH = 2 * CompassStrip.HALF + 1;
    private static final int CENTRE = CompassStrip.HALF;

    /** The strip as the player reads it, one character per position. */
    private static String plain(double yaw, double dx, double dz, double dy) {
        return CompassStrip.build(yaw, dx, dz, dy).replaceAll("<[^>]+>", "");
    }

    @Test
    void sameHeightShowsOnlyTheMarker() {
        // looking south (yaw 0), target 20 blocks south
        String s = plain(0, 0, 20, 0);
        assertEquals(WIDTH, s.length());
        assertEquals('◆', s.charAt(CENTRE));
        assertFalse(s.contains("▲") || s.contains("▼"));
    }

    @Test
    void moreThanFiveBlocksUpOrDownShowsAnArrowNextToTheMarker() {
        String up = plain(0, 0, 20, 6);
        assertEquals('◆', up.charAt(CENTRE));
        assertEquals('▲', up.charAt(CENTRE + 1));
        assertEquals(WIDTH, up.length(), "the arrow takes a strip character's place");

        String down = plain(0, 0, 20, -12.5);
        assertEquals('◆', down.charAt(CENTRE));
        assertEquals('▼', down.charAt(CENTRE + 1));
    }

    @Test
    void fiveBlocksOrLessShowsNoArrow() {
        for (double dy : new double[] {5, -5, 4.9, -0.5, 0}) {
            String s = plain(0, 0, 20, dy);
            assertFalse(s.contains("▲") || s.contains("▼"), "dy " + dy);
        }
        assertNull(CompassStrip.arrow(5));
        assertEquals("▲", CompassStrip.arrow(5.01));
        assertEquals("▼", CompassStrip.arrow(-5.01));
    }

    @Test
    void behindThePlayerTheArrowSitsInsideTheEdgeMarker() {
        // looking south; target north-west is behind on the right: » at the right edge, arrow just left of it
        String right = plain(0, -10, -10, 8);
        assertEquals('»', right.charAt(WIDTH - 1));
        assertEquals('▲', right.charAt(WIDTH - 2));
        assertEquals(WIDTH, right.length());
        // target north-east is behind on the left: « at the left edge, arrow just right of it
        String left = plain(0, 10, -10, -8);
        assertEquals('«', left.charAt(0));
        assertEquals('▼', left.charAt(1));
    }

    @Test
    void markerFollowsWhereThePlayerLooks() {
        // target south; looking west (yaw 90) puts it to the left of the centre
        String s = plain(90, 0, 20, 0);
        int at = s.indexOf('◆');
        assertTrue(at < CENTRE && at >= 0, "south is on the left when looking west: " + s);
    }
}

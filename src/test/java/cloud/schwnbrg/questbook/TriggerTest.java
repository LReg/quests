package cloud.schwnbrg.questbook;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.model.Trigger;
import org.junit.jupiter.api.Test;

class TriggerTest {

    @Test
    void reachIsASphere() {
        var r = new Trigger.Reach("world", 0.5, 70, 0.5, 6);
        assertTrue(r.contains("world", 0.5, 70, 6.5));
        assertFalse(r.contains("world", 0.5, 70, 6.6));
        assertFalse(r.contains("nether", 0.5, 70, 0.5));
        assertFalse(r.contains("world", 5, 75, 5));
    }

    @Test
    void regionUsesHeightFromBase() {
        var r = new Trigger.Region("world", 10, 10, 4, 4, 70, 6);
        assertTrue(r.contains("world", 4, 70, 10));
        assertTrue(r.contains("world", 10, 75, 4));
        assertFalse(r.contains("world", 10, 76, 4), "height is exclusive");
        assertFalse(r.contains("world", 10, 69, 4));
        assertFalse(r.contains("world", 11, 70, 4));
    }

    @Test
    void answersAreNormalized() {
        assertEquals(Trigger.normalize("A  Clock!"), Trigger.normalize("  a clock"));
        assertEquals("its a clock", Trigger.normalize("It's a \"Clock\"."));
    }
}

package cloud.schwnbrg.questbook;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.QuestStatus;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.storage.QuestRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestRepositoryTest {

    @Test
    void roundTripsEveryTriggerType(@TempDir Path dir) throws Exception {
        Quest q = new Quest("all", "All <b>types</b>", "sum", QuestStatus.PUBLISHED, new BlockRef("world", -512, 64, -1380),
                List.of(), true, 60, "Nils", List.of(
                new Step(List.of("a", "", "b"), new Trigger.Continue(), "hint", List.of("told after", "second line")),
                new Step(List.of("r"), new Trigger.Reach("world", -620.5, 70, -1410.5, 6), null),
                new Step(List.of("g"), new Trigger.Region("world", -640, -1430, -634, -1424, 70, 6), null),
                new Step(List.of("s"), new Trigger.SignClick(new BlockRef("world", 1, 2, 3)), null),
                new Step(List.of("i"), new Trigger.Interact(new BlockRef("world", 4, 5, 6)), null),
                new Step(List.of("n"), new Trigger.Answer(List.of("clock", "a clock")), null),
                new Step(List.of("d"), new Trigger.Dimension(World.Environment.NETHER), null),
                new Step(List.of("v"), new Trigger.AdvancementDone(NamespacedKey.minecraft("nether/explore_nether")), null)));
        QuestRepository repo = new QuestRepository(dir, Logger.getAnonymousLogger());
        repo.save(q);
        assertFalse(Files.exists(dir.resolve("all.yml.tmp")), "atomic write leaves no temp file");
        Map<String, Quest> loaded = repo.loadAll();
        assertEquals(q, loaded.get("all"));
    }

    @Test
    void loadsHandWrittenSpecExample(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lighthouse.yml"), """
                id: lighthouse
                title: "The Lost Lighthouse"
                summary: "<i>An old keeper's diary speaks of a light that went out.</i>"
                status: PUBLISHED
                author: Nils
                start_sign: { world: world, x: -512, y: 64, z: -1380 }
                requires: []
                repeatable: false
                repeat_cooldown_seconds: 0
                steps:
                  - text:
                      - "<gray><i>The diary is water-stained…</i></gray>"
                    trigger: { type: CONTINUE }
                  - text:
                      - "You reach the estuary."
                    trigger: { type: REACH, world: world, x: -620.5, y: 70, z: -1410.5, radius: 6 }
                    hint: "Follow the river downstream."
                  - text: ["Letters are carved here."]
                    trigger: { type: SIGN, sign: { world: world, x: -631, y: 74, z: -1422 } }
                """);
        Quest q = new QuestRepository(dir, Logger.getAnonymousLogger()).loadAll().get("lighthouse");
        assertNotNull(q);
        assertEquals(3, q.steps().size());
        assertEquals(new Trigger.Reach("world", -620.5, 70, -1410.5, 6), q.steps().get(1).trigger());
        assertEquals("Follow the river downstream.", q.steps().get(1).hint());
        assertEquals(new BlockRef("world", -631, 74, -1422), ((Trigger.SignClick) q.steps().get(2).trigger()).sign());
    }
}

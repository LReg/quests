package cloud.schwnbrg.questbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.schwnbrg.questbook.journal.BookPaginator;
import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.QuestStatus;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regression tests for the code-review fixes (pure logic only). */
class ReviewFixesTest {

    @Test
    void regionBaseYIsTheFeetBlock() {
        // The editor stands on the ground at feet level y=70, so baseY = 70 (spec §5: baseY = pos.blockY).
        Trigger.Region r = new Trigger.Region("world", 0, 0, 5, 5, 70, 1);
        assertTrue(r.contains("world", 2, 70, 2), "a player standing on the marked ground is inside even with height 1");
        assertFalse(r.contains("world", 2, 71, 2));
        assertFalse(r.contains("world", 2, 69, 2));
    }

    @Test
    void regionHeightCountsBlocksFromBase() {
        Trigger.Region r = new Trigger.Region("world", 0, 0, 0, 0, 64, 4);
        for (int y = 64; y < 68; y++) assertTrue(r.contains("world", 0, y, 0), "y=" + y);
        assertFalse(r.contains("world", 0, 68, 0));
    }

    @Test
    void firstPageHonoursItsReserve() {
        List<String> paras = new ArrayList<>();
        for (int i = 0; i < 30; i++) paras.add("line " + i);
        List<List<String>> pages = BookPaginator.paginate(paras, BookPaginator.LINES - 3);
        assertEquals(BookPaginator.LINES - 3, pages.get(0).size());
        for (int i = 1; i < pages.size(); i++) assertTrue(pages.get(i).size() <= BookPaginator.LINES);
        int total = pages.stream().mapToInt(List::size).sum();
        assertEquals(30, total);
    }

    @Test
    void publishedQuestWithAnEmptySignStepIsInvalid() {
        // EditorService drops such a quest back to draft instead of saving it as live
        Quest q = new Quest("tale", "Tale", "", QuestStatus.PUBLISHED, new BlockRef("world", 0, 64, 0), List.of(), false, 0, "x",
                List.of(new Step(List.of("hi"), new Trigger.SignClick(null), null)));
        assertFalse(Validation.errors(q, Map.of(), true).isEmpty());
        Quest ok = q.withSteps(List.of(new Step(List.of("hi"), new Trigger.Continue(), null)));
        assertTrue(Validation.errors(ok, Map.of(), true).isEmpty());
    }

    @Test
    void unknownTriggerTypeLoadsPublishedQuestAsDraft(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("typo.yml"), """
                id: typo
                title: "Typo"
                status: PUBLISHED
                start_sign: { world: world, x: 0, y: 64, z: 0 }
                steps:
                  - text: ["hello"]
                    trigger: { type: REGOIN }
                """);
        Quest q = new cloud.schwnbrg.questbook.storage.QuestRepository(dir, java.util.logging.Logger.getAnonymousLogger()).loadAll().get("typo");
        assertEquals(QuestStatus.DRAFT, q.status());
    }

    @Test
    void killTriggerMatchesCategoriesAndTypes() {
        // typeName, animal, enemy, player
        assertTrue(new Trigger.Kill("ANY", 1).matches("COW", true, false, false));
        assertTrue(new Trigger.Kill("ANIMAL", 1).matches("COW", true, false, false));
        assertFalse(new Trigger.Kill("ANIMAL", 1).matches("ZOMBIE", false, true, false));
        assertTrue(new Trigger.Kill("MONSTER", 1).matches("ZOMBIE", false, true, false));
        assertTrue(new Trigger.Kill("PLAYER", 1).matches("PLAYER", false, false, true));
        assertFalse(new Trigger.Kill("PLAYER", 1).matches("COW", true, false, false));
        assertTrue(new Trigger.Kill("ZOMBIE", 1).matches("ZOMBIE", false, true, false));
        assertFalse(new Trigger.Kill("ZOMBIE", 1).matches("HUSK", false, true, false));
        assertTrue(Trigger.Kill.validTarget("ZOMBIE"));
        assertTrue(Trigger.Kill.validTarget("MONSTER"));
        assertFalse(Trigger.Kill.validTarget("ZOMBEE"));
        assertFalse(Trigger.Kill.validTarget("ARROW"), "not a living entity");
    }

    @Test
    void killTriggerRoundTripsAndRejectsBadTargets(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var repo = new cloud.schwnbrg.questbook.storage.QuestRepository(dir, java.util.logging.Logger.getAnonymousLogger());
        Quest q = new Quest("hunt", "Hunt", "", QuestStatus.DRAFT, null, List.of(), false, 0, "x",
                List.of(new Step(List.of("kill"), new Trigger.Kill("ZOMBIE", 10), null)));
        repo.save(q);
        assertEquals(q, repo.loadAll().get("hunt"));
        java.nio.file.Files.writeString(dir.resolve("bad.yml"), """
                id: bad
                title: "Bad"
                status: PUBLISHED
                start_sign: { world: world, x: 0, y: 64, z: 0 }
                steps:
                  - text: ["go"]
                    trigger: { type: KILL, target: ZOMBEE, amount: 3 }
                """);
        assertEquals(QuestStatus.DRAFT, repo.loadAll().get("bad").status());
        Quest big = q.withSteps(List.of(new Step(List.of("kill"), new Trigger.Kill("ANY", 5000), null)));
        assertFalse(Validation.errors(big, Map.of(), false).isEmpty());
    }

    @Test
    void ownerRoundTripsAndOldFilesHaveNone(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var repo = new cloud.schwnbrg.questbook.storage.QuestRepository(dir, java.util.logging.Logger.getAnonymousLogger());
        Quest q = Quest.draft("mine", "Mine", "Nils").withOwner("0061e708-1d2f-3e64-8617-59fef9008cc0");
        repo.save(q);
        assertEquals("0061e708-1d2f-3e64-8617-59fef9008cc0", repo.loadAll().get("mine").owner());
        assertEquals(q, repo.loadAll().get("mine"));
        java.nio.file.Files.writeString(dir.resolve("old.yml"), """
                id: old
                title: "Old"
                status: DRAFT
                author: Nils
                steps: []
                """);
        assertEquals("", repo.loadAll().get("old").owner());
        assertEquals("0061e708", q.withTitle("x").owner().substring(0, 8), "other changes keep the owner");
    }

    @Test
    void answersCollapseAllWhitespace() {
        assertEquals("a clock", Trigger.normalize("  A\tClock!! "));
        assertEquals("a clock", Trigger.normalize("a   clock"));
    }

    @Test
    void answerWithoutAnswersIsInvalid() {
        Quest q = new Quest("tale", "Tale", "", QuestStatus.PUBLISHED, new BlockRef("world", 0, 64, 0), List.of(), false, 0, "x",
                List.of(new Step(List.of("hi"), new Trigger.Answer(List.of()), null)));
        assertFalse(Validation.errors(q, Map.of(), true).isEmpty());
    }
}

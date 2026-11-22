package cloud.schwnbrg.questbook;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Cond;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.QuestStatus;
import cloud.schwnbrg.questbook.model.Rule;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Target;
import cloud.schwnbrg.questbook.model.TextVariant;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
import cloud.schwnbrg.questbook.storage.QuestRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Branching (1.2): hidden step ids, conditions, endings, and that 1.1 files load unchanged. */
class BranchingTest {

    @AfterEach
    void resetLimits() {
        Validation.setLimits(null);
    }

    private static Step text(String t) {
        return new Step(List.of(t), new Trigger.Continue(), null);
    }

    /** The lighthouse example: step 3 is a choice; "Pocket the gold" goes to the harbour (5), the lamp path skips it. */
    static Quest lighthouse() {
        Trigger.Choice choice = new Trigger.Choice(List.of(new Trigger.Option(null, "Light the lamp"), new Trigger.Option(null, "Pocket the gold")));
        Quest q = Quest.draft("lighthouse", "The Lost Lighthouse", "t").withStartSign(new BlockRef("world", 0, 64, 0)).withSteps(List.of(
                text("intro"), text("river"), new Step(List.of("keeper"), choice, null), text("stairs"), text("harbour"), text("gate")));
        Step keeper = q.steps().get(2);
        Step stairs = q.steps().get(3);
        Step harbour = q.steps().get(4);
        return q.withStep(2, keeper.withRules(List.of(new Rule(new Cond.Picked(keeper.id(), "o2", false), Target.step(harbour.id())))))
                .withStep(3, stairs.withNext(Target.step(q.steps().get(5).id())))
                .withStep(4, harbour.withNext(Target.end("The Thief")));
    }

    @Test
    void stepsWithoutIdsGetStableIdsInOrder() {
        Quest q = Quest.draft("a1", "A", "t").withSteps(List.of(text("a"), text("b"), text("c")));
        assertEquals(List.of("s1", "s2", "s3"), q.steps().stream().map(Step::id).toList());
        // a new step gets a fresh id, never one that is in use
        Quest more = q.withSteps(List.of(q.steps().get(2), q.steps().get(0), text("d")));
        assertEquals(List.of("s3", "s1", "s4"), more.steps().stream().map(Step::id).toList());
        // duplicates and the reserved word "end" are renamed
        Quest dup = Quest.draft("a2", "A", "t").withSteps(List.of(text("a").withId("x"), text("b").withId("x"), text("c").withId("end")));
        assertEquals(3, dup.steps().stream().map(Step::id).distinct().count());
        assertFalse(dup.steps().stream().anyMatch(s -> s.id().equals("end")));
        // ids go into commands and the saved path, so a hand-edited id with a comma or space is replaced
        Quest odd = Quest.draft("a3", "A", "t").withSteps(List.of(text("a").withId("a,b"), text("b").withId("two words"), text("c").withId("ok_1")));
        assertTrue(odd.steps().stream().map(Step::id).allMatch(id -> id.matches("[A-Za-z0-9_-]+")));
        assertEquals("ok_1", odd.steps().get(2).id());
    }

    @Test
    void choiceOptionsGetIds() {
        Trigger.Choice c = new Trigger.Choice(List.of(new Trigger.Option(null, "a"), new Trigger.Option("o7", "b"), new Trigger.Option(null, "c")));
        assertEquals(List.of("o8", "o7", "o9"), c.options().stream().map(Trigger.Option::id).toList());
    }

    @Test
    void lighthouseIsValidAndRoutesAsDrawn() {
        Quest q = lighthouse();
        assertEquals(List.of(), Validation.errors(q, Map.of(), true));
        assertEquals(List.of(), Validation.flowWarnings(q));
        // default: the following step
        assertEquals(Target.step(q.steps().get(1).id()), q.fallThrough(0));
        // the lamp path jumps over the harbour
        assertEquals(Target.step(q.steps().get(5).id()), q.fallThrough(3));
        assertEquals(Target.end("The Thief"), q.fallThrough(4));
        // the last step ends without a name
        assertEquals(Target.end(null), q.fallThrough(5));
        assertEquals(List.of("The Thief"), q.endings());
    }

    @Test
    void fallThroughIntoAConditionTargetIsWarned() {
        Quest q = lighthouse();
        Quest noJump = q.withStep(3, q.steps().get(3).withNext(null));
        assertTrue(Validation.flowWarnings(noJump).stream().anyMatch(w -> w.contains("step 4 runs on into step 5")), Validation.flowWarnings(noJump).toString());
    }

    @Test
    void unreachableAndEndlessStepsAreWarned() {
        Quest q = Quest.draft("loop", "L", "t").withSteps(List.of(text("a"), text("b"), text("c")));
        // step 2 loops back to itself forever, so step 3 is never reached
        Quest loop = q.withStep(1, q.steps().get(1).withNext(Target.step(q.steps().get(1).id())));
        List<String> w = Validation.flowWarnings(loop);
        assertTrue(w.stream().anyMatch(s -> s.contains("step 3 can never be reached")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("step 2 is in a loop")), w.toString());
    }

    @Test
    void danglingReferencesAreErrors() {
        Quest q = lighthouse();
        Step keeper = q.steps().get(2);
        Quest bad = q.withStep(2, keeper.withRules(List.of(new Rule(new Cond.Picked(keeper.id(), "o9", false), Target.step("s99")))));
        List<String> e = Validation.errors(bad, Map.of(), false);
        assertTrue(e.stream().anyMatch(x -> x.contains("does not exist (s99)")), e.toString());
        assertTrue(e.stream().anyMatch(x -> x.contains("button or answer that does not exist")), e.toString());
    }

    @Test
    void choiceNeedsTwoButtonsToPublish() {
        Quest q = lighthouse();
        Quest one = q.withStep(2, q.steps().get(2).withRules(List.of()).withTrigger(new Trigger.Choice(List.of(new Trigger.Option(null, "only")))));
        assertTrue(Validation.errors(one, Map.of(), false).isEmpty());
        assertTrue(Validation.errors(one, Map.of(), true).stream().anyMatch(x -> x.contains("at least 2 buttons")));
    }

    @Test
    void worldChoiceOnlyTakesWorldTriggers() {
        Quest q = lighthouse();
        Trigger.FirstOf f = new Trigger.FirstOf(List.of(
                new Trigger.Branch(null, "Fled", new Trigger.Region("world", 0, 0, 3, 3, 64, 4)),
                new Trigger.Branch(null, "Talked", new Trigger.Continue())));
        Quest w = q.withStep(1, q.steps().get(1).withTrigger(f));
        assertTrue(Validation.errors(w, Map.of(), false).stream().anyMatch(x -> x.contains("CONTINUE cannot be a world choice option")));
    }

    @Test
    void summaryLimitIsLongerAndConfigurable() {
        Quest q = lighthouse().withSummary("x".repeat(500));
        assertTrue(Validation.errors(q, Map.of(), true).isEmpty(), "500 chars fit the new default of 600");
        Validation.setLimits(new Validation.Limits(200, 200, 256, 30, 50));
        assertTrue(Validation.errors(q, Map.of(), true).stream().anyMatch(x -> x.contains("summary longer than 200")));
    }

    @Test
    void everythingNewRoundTrips(@TempDir Path dir) throws Exception {
        Quest q = lighthouse();
        Step gate = q.steps().get(5);
        q = q.withStep(5, gate.withTextIf(List.of(new TextVariant(new Cond.Picked(q.steps().get(2).id(), "o2", false), List.of("He knows your face.")))))
                .withStep(1, q.steps().get(1).withRules(List.of(
                        new Rule(new Cond.HasItem("dried_kelp", 3, true, false), Target.step(q.steps().get(3).id())),
                        new Rule(new Cond.Ending("other", "The Thief", true), Target.end("Lost")),
                        new Rule(new Cond.Picked("other", "s2", "o1", false), Target.step(q.steps().get(4).id())))))
                .withStep(0, q.steps().get(0).withTrigger(new Trigger.FirstOf(List.of(
                        new Trigger.Branch(null, "Fled", new Trigger.Region("world", 0, 0, 3, 3, 64, 4)),
                        new Trigger.Branch(null, "Fought", new Trigger.Kill("DROWNED", 3))))))
                .withRequires(List.of("other")).withRequireEndings(Map.of("other", "The Thief"))
                .withGuide(new Quest.Guide(true, false))
                .withSummary("First line.\n\nSecond paragraph.");
        QuestRepository repo = new QuestRepository(dir, Logger.getAnonymousLogger());
        repo.save(q);
        assertEquals(q, repo.loadAll().get("lighthouse"));
    }

    @Test
    void oneDotOneFileLoadsUnchangedAndKeepsItsMeaning(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("old.yml"), """
                id: old
                title: "Old"
                summary: "Written by 1.1."
                status: PUBLISHED
                author: Nils
                start_sign: { world: world, x: 1, y: 2, z: 3 }
                requires: []
                repeatable: false
                repeat_cooldown_seconds: 0
                steps:
                  - text: ["one"]
                    trigger: { type: CONTINUE }
                  - text: ["two"]
                    trigger: { type: ANSWER, answers: [clock] }
                  - text: ["three"]
                    after: ["bye"]
                    trigger: { type: CONTINUE }
                """);
        Quest q = new QuestRepository(dir, Logger.getAnonymousLogger()).loadAll().get("old");
        assertEquals(QuestStatus.PUBLISHED, q.status(), "no problems, stays published");
        assertEquals(List.of("s1", "s2", "s3"), q.steps().stream().map(Step::id).toList());
        assertTrue(q.steps().stream().noneMatch(Step::branches));
        assertEquals(Quest.Guide.OFF, q.guide());
        assertEquals(Map.of(), q.requireEndings());
        assertEquals(Target.step("s2"), q.fallThrough(0));
        assertEquals(Target.end(null), q.fallThrough(2));
        assertTrue(Validation.errors(q, Map.of(), true).isEmpty());
        assertTrue(Validation.flowWarnings(q).isEmpty());
    }
}

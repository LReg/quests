package cloud.schwnbrg.questbook;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Step.GuideOverride;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.storage.QuestRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The quest sets the player guide, a step can override particles and/or the compass bar for itself. */
class StepGuideTest {

    private static Step step(String t) {
        return new Step(List.of(t), new Trigger.Reach("world", 0, 64, 0, 5), null);
    }

    private static Quest quest(Quest.Guide guide, Step... steps) {
        return Quest.draft("g", "G", "a").withSteps(List.of(steps)).withGuide(guide);
    }

    @Test
    void aStepWithoutOverrideFollowsTheQuest() {
        Quest on = quest(Quest.Guide.ON, step("a"));
        Quest off = quest(Quest.Guide.OFF, step("a"));
        assertEquals(Quest.Guide.ON, on.guideAt(0));
        assertEquals(Quest.Guide.OFF, off.guideAt(0));
        assertNull(on.steps().get(0).guide(), "no override is stored as none");
    }

    @Test
    void eachPartOverridesOnItsOwn() {
        Quest q = quest(Quest.Guide.ON,
                step("both"),
                step("no particles").withGuide(new GuideOverride(false, null)),
                step("no compass").withGuide(new GuideOverride(null, false)),
                step("nothing").withGuide(new GuideOverride(false, false)));
        assertEquals(new Quest.Guide(true, true), q.guideAt(0));
        assertEquals(new Quest.Guide(false, true), q.guideAt(1));
        assertEquals(new Quest.Guide(true, false), q.guideAt(2));
        assertEquals(new Quest.Guide(false, false), q.guideAt(3));
        // and the other way: a quest with the guide off, one step that shows the way
        Quest offButOne = quest(Quest.Guide.OFF, step("a"), step("here").withGuide(new GuideOverride(true, true)));
        assertEquals(Quest.Guide.OFF, offButOne.guideAt(0));
        assertEquals(Quest.Guide.ON, offButOne.guideAt(1));
        // a step out of range gets the quest's
        assertEquals(Quest.Guide.OFF, offButOne.guideAt(5));
    }

    @Test
    void changingTheQuestKeepsTheOverride() {
        Quest q = quest(Quest.Guide.ON, step("a").withGuide(new GuideOverride(null, false)));
        // the quest's particles go off: the step follows, its compass stays off
        assertEquals(Quest.Guide.OFF, q.withGuide(new Quest.Guide(false, true)).guideAt(0));
        // other step edits keep the override
        assertEquals(new GuideOverride(null, false), q.steps().get(0).withText(List.of("b")).withHint("h").guide());
    }

    @Test
    void theEditorCyclesAsQuestOnOff() {
        assertEquals(Boolean.TRUE, GuideOverride.next(null));
        assertEquals(Boolean.FALSE, GuideOverride.next(true));
        assertNull(GuideOverride.next(false));
        // back to "as the quest" on both parts removes the override
        assertNull(step("a").withGuide(new GuideOverride(null, null)).guide());
    }

    @Test
    void roundTripsThroughTheFile(@TempDir Path dir) throws Exception {
        Quest q = quest(Quest.Guide.ON,
                step("a"),
                step("b").withGuide(new GuideOverride(false, null)),
                step("c").withGuide(new GuideOverride(true, false)));
        QuestRepository repo = new QuestRepository(dir, Logger.getAnonymousLogger());
        repo.save(q);
        String yml = Files.readString(dir.resolve("g.yml"));
        assertEquals(3, yml.split("\\n\\s*guide:", -1).length - 1, "only overridden steps write guide:, plus the quest's own");
        Quest back = repo.loadAll().get("g");
        assertEquals(q, back);
        assertEquals(new Quest.Guide(false, true), back.guideAt(1));
        assertEquals(new Quest.Guide(true, false), back.guideAt(2));
    }

    @Test
    void handWrittenAndBrokenValues(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("h.yml"), """
                id: h
                title: "H"
                status: DRAFT
                guide: { particles: true, compass: true }
                steps:
                  - id: s1
                    text: ["one"]
                    trigger: { type: CONTINUE }
                    guide: { compass: off }
                  - id: s2
                    text: ["two"]
                    trigger: { type: CONTINUE }
                    guide: { particles: maybe }
                  - id: s3
                    text: ["three"]
                    trigger: { type: CONTINUE }
                    guide: yes please
                """);
        Quest q = new QuestRepository(dir, Logger.getAnonymousLogger()).loadAll().get("h");
        assertEquals(new Quest.Guide(true, false), q.guideAt(0));
        // a value that is not true/false and a guide that is not a section are ignored: the step follows the quest
        assertNull(q.steps().get(1).guide());
        assertNull(q.steps().get(2).guide());
        assertEquals(Quest.Guide.ON, q.guideAt(1));
        assertEquals(Quest.Guide.ON, q.guideAt(2));
    }
}

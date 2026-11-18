package cloud.schwnbrg.questbook;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.model.BlockRef;
import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.model.Validation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationTest {

    private static Quest quest(String id, List<String> requires) {
        return Quest.draft(id, "Title " + id, "t").withRequires(requires)
                .withSteps(List.of(new Step(List.of("hello"), new Trigger.Continue(), null)));
    }

    @Test
    void draftWithoutSignIsFineButCannotPublish() {
        Quest q = quest("lighthouse", List.of());
        assertTrue(Validation.errors(q, Map.of(), false).isEmpty());
        assertTrue(Validation.errors(q, Map.of(), true).stream().anyMatch(e -> e.contains("start sign")));
        assertTrue(Validation.errors(q.withStartSign(new BlockRef("world", 1, 2, 3)), Map.of(), true).isEmpty());
    }

    @Test
    void badIdAndEmptyText() {
        Quest q = Quest.draft("A", "x", "t").withSteps(List.of(new Step(List.of(" "), new Trigger.Continue(), null)));
        var errs = Validation.errors(q, Map.of(), false);
        assertTrue(errs.stream().anyMatch(e -> e.startsWith("id")));
        assertTrue(errs.stream().anyMatch(e -> e.contains("step 1")));
    }

    @Test
    void rangesAreChecked() {
        Quest q = quest("range", List.of()).withSteps(List.of(
                new Step(List.of("a"), new Trigger.Reach("w", 0, 0, 0, 65), null),
                new Step(List.of("b"), new Trigger.Region("w", 0, 0, 1, 1, 0, 129), null),
                new Step(List.of("c"), new Trigger.Answer(List.of()), null),
                new Step(List.of("d"), new Trigger.SignClick(null), null)));
        assertEquals(4, Validation.errors(q, Map.of(), false).size());
    }

    @Test
    void requiresCycleAndUnknown() {
        Map<String, Quest> all = new LinkedHashMap<>();
        all.put("a", quest("a", List.of("b")));
        all.put("b", quest("b", List.of("a")));
        assertTrue(Validation.errors(all.get("a"), all, false).stream().anyMatch(e -> e.contains("cycle")));
        assertTrue(Validation.errors(quest("c", List.of("zzz")), all, false).stream().anyMatch(e -> e.contains("unknown")));
        assertTrue(Validation.errors(quest("d", List.of("d")), all, false).stream().anyMatch(e -> e.contains("itself")));
    }
}

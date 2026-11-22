package cloud.schwnbrg.questbook.integration;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.model.Quest;
import cloud.schwnbrg.questbook.model.Step;
import cloud.schwnbrg.questbook.model.Trigger;
import cloud.schwnbrg.questbook.storage.QuestRepository;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A quest's icon in the advancement tab, and replacing an advancement's file without breaking the ones below it. */
class QuestIconTest {

    private static Quest quest() {
        return Quest.draft("q", "The Lighthouse", "x").withSteps(List.of(new Step(List.of("a"), new Trigger.Continue(), null)));
    }

    @Test
    void twentyFourDifferentItems() {
        assertEquals(24, Quest.ICONS.size());
        assertEquals(24, new HashSet<>(Quest.ICONS).size());
        for (String icon : Quest.ICONS) assertNotNull(Material.matchMaterial(icon), icon);
        assertEquals(Quest.DEFAULT_ICON, Quest.ICONS.getFirst(), "the book comes first: what every quest had");
    }

    @Test
    void noIconIsTheBookAndStoredAsNothing() {
        Quest q = quest();
        assertNull(q.icon());
        assertEquals("written_book", q.iconOrDefault());
        // picking the book again (or typing it oddly) is the same as no choice
        assertNull(q.withIcon("minecraft:Written_Book ").icon());
        assertEquals("compass", q.withIcon(" Minecraft:COMPASS").icon());
        // other edits keep it
        assertEquals("bell", q.withIcon("bell").withTitle("T").withSummary("s").withGuide(Quest.Guide.OFF).icon());
    }

    @Test
    void theDefaultIconGivesExactlyTheJsonOfOneDotFour() {
        // what 1.4 built; the same JSON means the same hash in advancements.yml, so updating changes no advancement
        String old = "{\"parent\":\"questbook:root\",\"display\":{\"icon\":{\"id\":\"minecraft:written_book\"},\"title\":{\"text\":\"The Lighthouse\"}"
                + ",\"description\":{\"text\":\"The Lighthouse\"},\"frame\":\"task\",\"show_toast\":true,\"announce_to_chat\":false,"
                + "\"hidden\":true},\"criteria\":{\"done\":{\"trigger\":\"minecraft:impossible\"}}}";
        assertEquals(old, AdvancementSync.json(quest(), "questbook:root"));
        assertTrue(AdvancementSync.json(quest().withIcon("amethyst_shard"), "questbook:root").contains("{\"id\":\"minecraft:amethyst_shard\"}"));
    }

    @Test
    void savedOnlyWhenChosen(@TempDir Path dir) throws Exception {
        QuestRepository repo = new QuestRepository(dir, Logger.getAnonymousLogger());
        repo.save(quest());
        assertFalse(Files.readString(dir.resolve("q.yml")).contains("icon:"), "a quest without a choice writes no icon");
        Quest chosen = quest().withIcon("heart_of_the_sea");
        repo.save(chosen);
        assertEquals(chosen, repo.loadAll().get("q"));
    }

    @Test
    void anUnknownItemKeepsTheBookAndTheQuestStaysPublished(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("u.yml"), """
                id: u
                title: "U"
                status: PUBLISHED
                icon: not_an_item
                steps:
                  - text: ["one"]
                    trigger: { type: CONTINUE }
                """);
        Quest q = new QuestRepository(dir, Logger.getAnonymousLogger()).loadAll().get("u");
        assertNull(q.icon());
        assertTrue(q.published());
    }

    @Test
    void aLooselyWrittenItemBecomesItsRealId(@TempDir Path dir) throws Exception {
        // Material.matchMaterial accepts this, an advancement icon id does not: only the real id may reach the JSON
        Files.writeString(dir.resolve("h.yml"), """
                id: h
                title: "H"
                status: PUBLISHED
                icon: "Heart of the Sea"
                steps:
                  - text: ["one"]
                    trigger: { type: CONTINUE }
                """);
        Quest q = new QuestRepository(dir, Logger.getAnonymousLogger()).loadAll().get("h");
        assertEquals("heart_of_the_sea", q.icon());
        assertTrue(AdvancementSync.json(q, "questbook:root").contains("{\"id\":\"minecraft:heart_of_the_sea\"}"));
        assertEquals("compass", cloud.schwnbrg.questbook.model.Validation.itemId("minecraft:COMPASS"));
        assertNull(cloud.schwnbrg.questbook.model.Validation.itemId("not an item"));
    }

    @Test
    void aChangedAdvancementOverwritesItsFileInsteadOfDeletingIt(@TempDir Path world) throws Exception {
        NamespacedKey key = new NamespacedKey("questbook", "quest/q");
        File f = world.resolve("datapacks/bukkit/data/questbook/advancement/quest/q.json").toFile();
        assertTrue(f.getParentFile().mkdirs());
        Files.writeString(f.toPath(), "{\"old\":true}");
        assertTrue(AdvancementSync.overwrite(world.toFile(), key, "{\"new\":true}"));
        assertEquals("{\"new\":true}", Files.readString(f.toPath()), "the file is still there, so quests below it keep their parent");
        assertFalse(new File(f.getPath() + ".tmp").exists());
    }

    @Test
    void anUnknownLayoutIsNotGuessedAt(@TempDir Path world) {
        // no file where Paper keeps it: nothing is written, the caller falls back to removing the advancement
        assertFalse(AdvancementSync.overwrite(world.toFile(), new NamespacedKey("questbook", "quest/q"), "{}"));
        assertFalse(world.resolve("datapacks").toFile().exists());
    }

    @Test
    void theOlderFolderNameWorksToo(@TempDir Path world) throws Exception {
        File f = world.resolve("datapacks/bukkit/data/questbook/advancements/quest/q.json").toFile();
        assertTrue(f.getParentFile().mkdirs());
        Files.writeString(f.toPath(), "{}");
        assertTrue(AdvancementSync.overwrite(world.toFile(), new NamespacedKey("questbook", "quest/q"), "{\"a\":1}"));
        assertEquals("{\"a\":1}", Files.readString(f.toPath()));
    }
}

package cloud.schwnbrg.questbook.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A database written by 1.1 opens with 1.2, and the old progress table stays exactly as 1.1 expects it. */
class DatabaseUpgradeTest {

    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void oneDotOneDatabaseUpgradesInPlace(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("questbook.db");
        // the 1.1 schema and a row written by 1.1
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE progress (player_uuid TEXT NOT NULL, quest_id TEXT NOT NULL, state TEXT NOT NULL, "
                    + "step INTEGER NOT NULL, started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completions INTEGER NOT NULL DEFAULT 0, "
                    + "last_completed_at INTEGER, PRIMARY KEY (player_uuid, quest_id))");
            s.execute("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, quest_id TEXT NOT NULL, "
                    + "type TEXT NOT NULL, step INTEGER, at INTEGER NOT NULL)");
            s.execute("CREATE TABLE rumours_seen (player_uuid TEXT, quest_id TEXT, PRIMARY KEY (player_uuid, quest_id))");
            s.execute("CREATE TABLE chronicle (quest_id TEXT PRIMARY KEY, player_uuid TEXT, player_name TEXT, at INTEGER)");
            s.execute("CREATE TABLE settings (player_uuid TEXT PRIMARY KEY, rumours_muted INTEGER NOT NULL DEFAULT 0)");
            s.execute("PRAGMA user_version = 1");
            s.execute("INSERT INTO progress VALUES ('" + P + "', 'old', 'ACTIVE', 2, 1, 2, 0, 0)");
        }

        Database db = new Database(file, Logger.getAnonymousLogger());
        db.open();
        PlayerData d = db.loadPlayer(P).get();
        Progress old = d.progress.get("old");
        assertEquals(2, old.step());
        assertEquals(Map.of(), old.choices());
        assertNull(old.ending());

        Progress np = new Progress("lighthouse", Progress.State.COMPLETED, 0, 1, 2, 1, 2,
                Map.of("s3", "o2", "s7", "a king & co=x"), List.of("s1", "s2", "s3", "s5"), "The Thief",
                Map.of("s3", "o1"), List.of("s1", "s3", "s6"));
        db.saveProgress(P, np);
        assertEquals(np, db.loadPlayer(P).get().progress.get("lighthouse"));
        // a replay keeps the finished run apart from the new one
        Progress replay = np.with(Progress.State.ACTIVE, 0, 3).withRun(Map.of(), List.of("s1"));
        db.saveProgress(P, replay);
        Progress back = db.loadPlayer(P).get().progress.get("lighthouse");
        assertEquals(Map.of("s3", "o1"), back.doneChoices());
        assertEquals(Map.of("s3", "o1"), back.finishedOrCurrentChoices());
        assertEquals(List.of("s1", "s3", "s6"), back.donePath());
        assertEquals(List.of("s1"), back.path());
        db.close();

        // 1.1's own write still works on the upgraded file: 8 values into progress
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("INSERT OR REPLACE INTO progress VALUES ('" + P + "', 'old', 'ACTIVE', 3, 1, 2, 0, 0)");
        }
    }

    @Test
    void choicesSurviveAnyCharacters() {
        Map<String, String> m = Map.of("s1", "o1", "s2", "a=b&c, d%", "s3", "ü ß ✦");
        assertEquals(m, Database.decodeChoices(Database.encodeChoices(m)));
        assertEquals(Map.of(), Database.decodeChoices(null));
        List<String> path = List.of("s1", "a,b", "ü");
        assertEquals(path, Database.decodePath(Database.encodePath(path)));
        // a path written before the entries were encoded reads the same
        assertEquals(List.of("s1", "s2"), Database.decodePath("s1,s2"));
    }

    @Test
    void runFromBeforeAOneDotOneDetourIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("questbook.db");
        Database db = new Database(file, Logger.getAnonymousLogger());
        db.open();
        db.saveProgress(P, new Progress("q", Progress.State.ACTIVE, 4, 1, 100, 0, 0, Map.of("s3", "o1"), List.of("s1", "s3", "s5"), null));
        db.close();
        // the 1.1 jar moves the player on: it writes progress only
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("INSERT OR REPLACE INTO progress VALUES ('" + P + "', 'q', 'ACTIVE', 1, 150, 200, 0, 0)");
        }
        db = new Database(file, Logger.getAnonymousLogger());
        db.open();
        Progress back = db.loadPlayer(P).get().progress.get("q");
        assertEquals(1, back.step());
        assertEquals(List.of(), back.path(), "the old path would move the player back to s5");
        assertEquals(Map.of(), back.choices());
        db.close();
    }

    @Test
    void brokenValuesAreSkipped() {
        assertEquals(Map.of("s2", "o1"), Database.decodeChoices("s1=%zz&s2=o1"));
        assertEquals(List.of("s1"), Database.decodePath("s1,%zz"));
    }

    @Test
    void choicesKeepTheirOrder() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 9; i >= 1; i--) m.put("s" + i, "o" + i);
        Progress pr = new Progress("q", Progress.State.ACTIVE, 0, 0, 0, 0, 0, m, List.of(), null);
        assertEquals(List.copyOf(m.keySet()), List.copyOf(pr.choices().keySet()));
    }

    @Test
    void earlyOneDotTwoTableGetsTheNewColumns(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("questbook.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE progress_extra (player_uuid TEXT NOT NULL, quest_id TEXT NOT NULL, choices TEXT, "
                    + "path TEXT, ending TEXT, PRIMARY KEY (player_uuid, quest_id))");
        }
        Database db = new Database(file, Logger.getAnonymousLogger());
        db.open();
        Progress np = new Progress("q", Progress.State.COMPLETED, 0, 1, 2, 1, 2, Map.of(), List.of("s1"), null, Map.of("s1", "o1"), List.of("s1"));
        db.saveProgress(P, np);
        assertEquals(np, db.loadPlayer(P).get().progress.get("q"));
        db.close();
    }
}

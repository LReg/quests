package cloud.schwnbrg.questbook.storage;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** SQLite storage. All I/O runs on one single-thread executor. */
public final class Database {

    private static final int VERSION = 1;

    private final Path file;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "questbook-io");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;

    public Database(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver missing", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        migrate();
    }

    private void migrate() throws SQLException {
        int v;
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
            v = r.next() ? r.getInt(1) : 0;
        }
        if (v < 1) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS progress (player_uuid TEXT NOT NULL, quest_id TEXT NOT NULL, state TEXT NOT NULL, "
                        + "step INTEGER NOT NULL, started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, completions INTEGER NOT NULL DEFAULT 0, "
                        + "last_completed_at INTEGER, PRIMARY KEY (player_uuid, quest_id))");
                s.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, quest_id TEXT NOT NULL, "
                        + "type TEXT NOT NULL, step INTEGER, at INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS rumours_seen (player_uuid TEXT, quest_id TEXT, PRIMARY KEY (player_uuid, quest_id))");
                s.execute("CREATE TABLE IF NOT EXISTS chronicle (quest_id TEXT PRIMARY KEY, player_uuid TEXT, player_name TEXT, at INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS settings (player_uuid TEXT PRIMARY KEY, rumours_muted INTEGER NOT NULL DEFAULT 0)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_events_quest ON events(quest_id, type)");
                s.execute("PRAGMA user_version = " + VERSION);
            }
        }
        // Added in 1.2 as its own table (not new columns), so the 1.1 jar still works on a database the 1.2 jar has used.
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS progress_extra (player_uuid TEXT NOT NULL, quest_id TEXT NOT NULL, choices TEXT, "
                    + "path TEXT, ending TEXT, done_choices TEXT, done_path TEXT, PRIMARY KEY (player_uuid, quest_id))");
        }
        // a database from an early 1.2 build has the table without the last two columns
        for (String col : List.of("done_choices", "done_path")) {
            try (Statement s = conn.createStatement()) {
                s.execute("ALTER TABLE progress_extra ADD COLUMN " + col + " TEXT");
            } catch (SQLException alreadyThere) {
                // the usual case
            }
        }
    }

    // choices are stored as key=value&key=value with both parts URL-encoded, the path as comma-separated URL-encoded step ids

    static String encodeChoices(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (var e : m.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    static Map<String, String> decodeChoices(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String part : s.split("&")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static String encodePath(List<String> path) {
        StringBuilder b = new StringBuilder();
        for (String id : path) {
            if (b.length() > 0) b.append(',');
            b.append(URLEncoder.encode(id, StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    static List<String> decodePath(String s) {
        if (s == null || s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) out.add(URLDecoder.decode(part, StandardCharsets.UTF_8));
        return out;
    }

    private <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, io);
    }

    private void run(SqlWork w) {
        io.execute(() -> {
            try {
                w.run();
            } catch (SQLException e) {
                log.warning("Database write failed: " + e.getMessage());
            }
        });
    }

    private interface SqlWork { void run() throws SQLException; }

    public CompletableFuture<PlayerData> loadPlayer(UUID id) {
        return async(() -> {
            PlayerData d = new PlayerData();
            try {
                try (PreparedStatement p = conn.prepareStatement("SELECT * FROM progress WHERE player_uuid=?")) {
                    p.setString(1, id.toString());
                    ResultSet r = p.executeQuery();
                    while (r.next()) {
                        d.progress.put(r.getString("quest_id"), new Progress(r.getString("quest_id"), Progress.State.valueOf(r.getString("state")),
                                r.getInt("step"), r.getLong("started_at"), r.getLong("updated_at"), r.getInt("completions"), r.getLong("last_completed_at")));
                    }
                }
                try (PreparedStatement p = conn.prepareStatement("SELECT quest_id, choices, path, ending, done_choices, done_path FROM progress_extra WHERE player_uuid=?")) {
                    p.setString(1, id.toString());
                    ResultSet r = p.executeQuery();
                    while (r.next()) {
                        Progress pr = d.progress.get(r.getString(1));
                        if (pr == null) continue;
                        d.progress.put(pr.questId(), pr.withRun(decodeChoices(r.getString(2)), decodePath(r.getString(3))).withEnding(r.getString(4))
                                .withDone(decodeChoices(r.getString(5)), decodePath(r.getString(6))));
                    }
                }
                try (PreparedStatement p = conn.prepareStatement("SELECT quest_id FROM rumours_seen WHERE player_uuid=?")) {
                    p.setString(1, id.toString());
                    ResultSet r = p.executeQuery();
                    while (r.next()) d.rumoursSeen.add(r.getString(1));
                }
                try (PreparedStatement p = conn.prepareStatement("SELECT rumours_muted FROM settings WHERE player_uuid=?")) {
                    p.setString(1, id.toString());
                    ResultSet r = p.executeQuery();
                    if (r.next()) d.rumoursMuted = r.getInt(1) != 0;
                }
            } catch (SQLException e) {
                log.warning("Loading player data failed: " + e.getMessage());
            }
            return d;
        });
    }

    public void saveProgress(UUID id, Progress p) {
        run(() -> {
            try (PreparedStatement s = conn.prepareStatement("INSERT OR REPLACE INTO progress VALUES (?,?,?,?,?,?,?,?)")) {
                s.setString(1, id.toString());
                s.setString(2, p.questId());
                s.setString(3, p.state().name());
                s.setInt(4, p.step());
                s.setLong(5, p.startedAt());
                s.setLong(6, p.updatedAt());
                s.setInt(7, p.completions());
                s.setLong(8, p.lastCompletedAt());
                s.executeUpdate();
            }
            boolean empty = p.choices().isEmpty() && p.path().isEmpty() && p.ending() == null
                    && p.doneChoices().isEmpty() && p.donePath().isEmpty();
            try (PreparedStatement s = conn.prepareStatement(empty ? "DELETE FROM progress_extra WHERE player_uuid=? AND quest_id=?"
                    : "INSERT OR REPLACE INTO progress_extra (player_uuid, quest_id, choices, path, ending, done_choices, done_path) VALUES (?,?,?,?,?,?,?)")) {
                s.setString(1, id.toString());
                s.setString(2, p.questId());
                if (!empty) {
                    s.setString(3, encodeChoices(p.choices()));
                    s.setString(4, encodePath(p.path()));
                    s.setString(5, p.ending());
                    s.setString(6, encodeChoices(p.doneChoices()));
                    s.setString(7, encodePath(p.donePath()));
                }
                s.executeUpdate();
            }
        });
    }

    public void deleteProgress(UUID id, String quest) {
        run(() -> {
            for (String table : List.of("progress", "progress_extra")) {
                try (PreparedStatement s = conn.prepareStatement("DELETE FROM " + table + " WHERE player_uuid=? AND quest_id=?")) {
                    s.setString(1, id.toString());
                    s.setString(2, quest);
                    s.executeUpdate();
                }
            }
        });
    }

    public void addEvent(UUID id, String quest, String type, int step) {
        run(() -> {
            try (PreparedStatement s = conn.prepareStatement("INSERT INTO events(player_uuid,quest_id,type,step,at) VALUES (?,?,?,?,?)")) {
                s.setString(1, id.toString());
                s.setString(2, quest);
                s.setString(3, type);
                s.setInt(4, step);
                s.setLong(5, System.currentTimeMillis());
                s.executeUpdate();
            }
        });
    }

    public void markRumour(UUID id, String quest) {
        run(() -> {
            try (PreparedStatement s = conn.prepareStatement("INSERT OR IGNORE INTO rumours_seen VALUES (?,?)")) {
                s.setString(1, id.toString());
                s.setString(2, quest);
                s.executeUpdate();
            }
        });
    }

    public void setMuted(UUID id, boolean muted) {
        run(() -> {
            try (PreparedStatement s = conn.prepareStatement("INSERT OR REPLACE INTO settings VALUES (?,?)")) {
                s.setString(1, id.toString());
                s.setInt(2, muted ? 1 : 0);
                s.executeUpdate();
            }
        });
    }

    /** Inserts the chronicle row if the quest has none yet. Completes true if this player is the first finisher. */
    public CompletableFuture<Boolean> claimChronicle(String quest, UUID id, String name) {
        return async(() -> {
            try (PreparedStatement s = conn.prepareStatement("INSERT OR IGNORE INTO chronicle VALUES (?,?,?,?)")) {
                s.setString(1, quest);
                s.setString(2, id.toString());
                s.setString(3, name);
                s.setLong(4, System.currentTimeMillis());
                return s.executeUpdate() > 0;
            } catch (SQLException e) {
                log.warning("Chronicle write failed: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Map<String, String>> chronicleNames() {
        return async(() -> {
            Map<String, String> m = new LinkedHashMap<>();
            try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("SELECT quest_id, player_name FROM chronicle")) {
                while (r.next()) m.put(r.getString(1), r.getString(2));
            } catch (SQLException e) {
                log.warning("Chronicle read failed: " + e.getMessage());
            }
            return m;
        });
    }

    /** {@code endings}: players per ending of their last completion. */
    public record Stats(int started, int active, Map<Integer, Integer> perStep, int completed, int totalCompletions, int abandoned,
                        Map<String, Integer> endings) {}

    public CompletableFuture<Stats> stats(String quest) {
        return async(() -> {
            try {
                int started = one("SELECT COUNT(DISTINCT player_uuid) FROM events WHERE quest_id=? AND type='START'", quest);
                int active = one("SELECT COUNT(*) FROM progress WHERE quest_id=? AND state='ACTIVE'", quest);
                int completed = one("SELECT COUNT(DISTINCT player_uuid) FROM events WHERE quest_id=? AND type='COMPLETE'", quest);
                int total = one("SELECT COUNT(*) FROM events WHERE quest_id=? AND type='COMPLETE'", quest);
                int abandoned = one("SELECT COUNT(DISTINCT player_uuid) FROM events WHERE quest_id=? AND type='ABANDON'", quest);
                Map<Integer, Integer> perStep = new LinkedHashMap<>();
                try (PreparedStatement p = conn.prepareStatement("SELECT step, COUNT(*) FROM progress WHERE quest_id=? AND state='ACTIVE' GROUP BY step ORDER BY step")) {
                    p.setString(1, quest);
                    ResultSet r = p.executeQuery();
                    while (r.next()) perStep.put(r.getInt(1), r.getInt(2));
                }
                Map<String, Integer> endings = new LinkedHashMap<>();
                try (PreparedStatement p = conn.prepareStatement("SELECT ending, COUNT(*) FROM progress_extra WHERE quest_id=? AND ending IS NOT NULL "
                        + "AND ending <> '' GROUP BY ending ORDER BY COUNT(*) DESC")) {
                    p.setString(1, quest);
                    ResultSet r = p.executeQuery();
                    while (r.next()) endings.put(r.getString(1), r.getInt(2));
                }
                return new Stats(started, active, perStep, completed, total, abandoned, endings);
            } catch (SQLException e) {
                log.warning("Stats failed: " + e.getMessage());
                return new Stats(0, 0, Map.of(), 0, 0, 0, Map.of());
            }
        });
    }

    /** Counts of active/completed players per quest, for the editor list. */
    public CompletableFuture<Map<String, int[]>> playerCounts() {
        return async(() -> {
            Map<String, int[]> m = new LinkedHashMap<>();
            try (Statement s = conn.createStatement();
                 ResultSet r = s.executeQuery("SELECT quest_id, state, COUNT(*) FROM progress GROUP BY quest_id, state")) {
                while (r.next()) {
                    int[] a = m.computeIfAbsent(r.getString(1), k -> new int[2]);
                    if (r.getString(2).equals("ACTIVE")) a[0] = r.getInt(3);
                    else if (r.getString(2).equals("COMPLETED")) a[1] = r.getInt(3);
                }
            } catch (SQLException e) {
                log.warning("Counts failed: " + e.getMessage());
            }
            return m;
        });
    }

    private int one(String sql, String arg) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(sql)) {
            p.setString(1, arg);
            ResultSet r = p.executeQuery();
            return r.next() ? r.getInt(1) : 0;
        }
    }

    /** Flushes the queue (max 5 s) and closes. */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) log.warning("Database queue not flushed in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
    }
}

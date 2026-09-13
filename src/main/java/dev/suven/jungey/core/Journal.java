package dev.suven.jungey.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every exchange, kept in a local SQLite database so it can be rated, corrected and later
 * exported as training data for a fine-tuned model.
 *
 * <p>The file sits beside the transcript and never leaves the machine. A broken database
 * must never interrupt a conversation, so failures are reported to stderr and otherwise
 * ignored - Jungey simply stops remembering.
 */
public final class Journal implements AutoCloseable {

    public static final Path FILE = Path.of(System.getProperty("user.home"), ".local", "share", "jungey", "jungey.db");

    /** One exchange: what was said, what answered it, and what the user thought of the answer. */
    public record Exchange(long id, String session, String at, String input, String skill, String reply,
                           boolean ok, String model, long latencyMs, Integer rating, String correction) {

        /** The reply as it should have been - the correction when there is one. */
        public String bestReply() {
            return correction != null ? correction : reply;
        }
    }

    public record Stats(int total, int conversation, int good, int bad, int corrected) {
    }

    /** One per launch, so conversations can be told apart when exporting. */
    private final String session = UUID.randomUUID().toString();

    private Connection db;

    public Journal() {
        try {
            Files.createDirectories(FILE.getParent());
            db = DriverManager.getConnection("jdbc:sqlite:" + FILE);
            try (Statement st = db.createStatement()) {
                // WAL with NORMAL sync flushes at checkpoints rather than on every row, which
                // keeps writes quick on a spinning disk.
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS exchange (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            session     TEXT    NOT NULL,
                            at          TEXT    NOT NULL,
                            input       TEXT    NOT NULL,
                            skill       TEXT    NOT NULL,
                            reply       TEXT    NOT NULL,
                            ok          INTEGER NOT NULL,
                            model       TEXT,
                            latency_ms  INTEGER NOT NULL,
                            rating      INTEGER,
                            correction  TEXT
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS exchange_skill ON exchange(skill, session, id)");
            }
        } catch (Exception e) {
            System.err.println("[jungey] journal unavailable, exchanges will not be saved: " + e.getMessage());
            db = null;
        }
    }

    public synchronized void record(String input, String skill, SkillResult result, String model, long latencyMs) {
        if (db == null) return;

        String sql = "INSERT INTO exchange (session, at, input, skill, reply, ok, model, latency_ms) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, session);
            ps.setString(2, LocalDateTime.now().withNano(0).toString());
            ps.setString(3, input);
            ps.setString(4, skill);
            ps.setString(5, result.speech() == null ? "" : result.speech());
            ps.setInt(6, result.ok() ? 1 : 0);
            if (model == null) ps.setNull(7, Types.VARCHAR);
            else ps.setString(7, model);
            ps.setLong(8, latencyMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[jungey] could not save exchange: " + e.getMessage());
        }
    }

    /** The most recent exchange, from this launch or an earlier one; null if there is none. */
    public synchronized Exchange last() {
        if (db == null) return null;
        List<Exchange> rows = query("SELECT * FROM exchange ORDER BY id DESC LIMIT 1");
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** +1 for a good reply, -1 for a bad one. */
    public synchronized boolean rate(long id, int rating) {
        return update("UPDATE exchange SET rating = ? WHERE id = ?", rating, id);
    }

    /** What the reply should have been. A correction also marks the original as bad. */
    public synchronized boolean correct(long id, String correction) {
        return update("UPDATE exchange SET rating = -1, correction = ? WHERE id = ?", correction, id);
    }

    /** Every successful exchange a skill produced, oldest first, grouped by launch. */
    public synchronized List<Exchange> successfulBySkill(String skill) {
        if (db == null) return List.of();
        return query("SELECT * FROM exchange WHERE skill = ? AND ok = 1 ORDER BY session, id", skill);
    }

    public synchronized Stats stats() {
        if (db == null) return new Stats(0, 0, 0, 0, 0);
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT COUNT(*),
                            SUM(skill = 'converse'),
                            SUM(rating = 1),
                            SUM(rating = -1),
                            SUM(correction IS NOT NULL)
                     FROM exchange""")) {
            return new Stats(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5));
        } catch (SQLException e) {
            return new Stats(0, 0, 0, 0, 0);
        }
    }

    public boolean available() {
        return db != null;
    }

    private boolean update(String sql, Object value, long id) {
        if (db == null) return false;
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setObject(1, value);
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            System.err.println("[jungey] could not update exchange: " + e.getMessage());
            return false;
        }
    }

    private List<Exchange> query(String sql, Object... args) {
        List<Exchange> rows = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int rating = rs.getInt("rating");
                    rows.add(new Exchange(
                            rs.getLong("id"), rs.getString("session"), rs.getString("at"),
                            rs.getString("input"), rs.getString("skill"), rs.getString("reply"),
                            rs.getInt("ok") == 1, rs.getString("model"), rs.getLong("latency_ms"),
                            rs.wasNull() ? null : rating, rs.getString("correction")));
                }
            }
        } catch (SQLException e) {
            System.err.println("[jungey] journal query failed: " + e.getMessage());
        }
        return rows;
    }

    @Override
    public synchronized void close() {
        if (db == null) return;
        try {
            db.close();
        } catch (SQLException ignored) {
            // Shutting down anyway.
        }
        db = null;
    }
}

package com.legacyaudit.engine.inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-file result cache keyed by (root, path) and validated by size/mtime, SHA-256 and the
 * analyzer version key. Linking, metrics, rules and totals are never cached. One SQLite file per
 * output folder ({@code cache/file-cache.sqlite}).
 */
public final class FileCache implements AutoCloseable {

    /**
     * @param payload the cached analysis as JSON text ({@code {"record": ..., "facts": ...}})
     */
    public record Entry(long size, long mtimeNs, String sha256, String payload) {}

    private final Connection conn;
    private final String version;
    private final Set<String> seen = new HashSet<>();
    private final PreparedStatement get;
    private final PreparedStatement put;

    public FileCache(Path path, String version) {
        this.version = version;
        try {
            Files.createDirectories(path.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS files (
                            root TEXT NOT NULL,
                            path TEXT NOT NULL,
                            size INTEGER NOT NULL,
                            mtime_ns INTEGER NOT NULL,
                            sha256 TEXT NOT NULL,
                            version TEXT NOT NULL,
                            payload TEXT NOT NULL,
                            PRIMARY KEY (root, path)
                        )""");
            }
            get = conn.prepareStatement("SELECT size, mtime_ns, sha256, version, payload FROM files WHERE root=? AND path=?");
            put = conn.prepareStatement("INSERT OR REPLACE INTO files VALUES (?, ?, ?, ?, ?, ?, ?)");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open the file cache " + path + ": " + e.getMessage(), e);
        }
    }

    public String version() {
        return version;
    }

    /** The entry for a file, or null when there is none for this version. */
    public Entry get(String root, String path) {
        seen.add(root + "\u001f" + path);
        try {
            get.setString(1, root);
            get.setString(2, path);
            try (ResultSet rs = get.executeQuery()) {
                if (!rs.next() || !version.equals(rs.getString(4))) {
                    return null;
                }
                return new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(5));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void put(String root, String path, Entry entry) {
        seen.add(root + "\u001f" + path);
        try {
            put.setString(1, root);
            put.setString(2, path);
            put.setLong(3, entry.size());
            put.setLong(4, entry.mtimeNs());
            put.setString(5, entry.sha256());
            put.setString(6, version);
            put.setString(7, entry.payload());
            put.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Drops entries for files not seen in this run. Returns how many were dropped. */
    public int prune() {
        try {
            List<String[]> gone = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT root, path FROM files")) {
                while (rs.next()) {
                    if (!seen.contains(rs.getString(1) + "\u001f" + rs.getString(2))) {
                        gone.add(new String[] {rs.getString(1), rs.getString(2)});
                    }
                }
            }
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM files WHERE root=? AND path=?")) {
                for (String[] g : gone) {
                    del.setString(1, g[0]);
                    del.setString(2, g[1]);
                    del.addBatch();
                }
                del.executeBatch();
            }
            return gone.size();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void commit() {
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            conn.commit();
            get.close();
            put.close();
            conn.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

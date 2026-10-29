package com.legacyaudit.engine.inventory;

import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Origin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Per-file inventory: hash, encoding, language, category and LOC. */
public final class FileAnalyzer {

    /** Bump when inventory logic changes so cached results are recomputed. */
    public static final String VERSION = "java-1";

    private static final int HEAD = 16 * 1024;

    /**
     * @param record null for a binary file left out (inventory.include_binaries is off)
     * @param text the decoded content, for the analyzers (not cached); null when not read
     */
    public record Result(FileRecord record, String text) {}

    private FileAnalyzer() {}

    /** Binary by extension and left out without reading it (archives are always kept). */
    public static boolean skipByExtension(String rel, boolean includeBinaries) {
        String ext = Paths.suffix(rel).toLowerCase(Locale.ROOT);
        return !includeBinaries && Languages.isBinaryExtension(ext) && !Languages.isArchiveExtension(ext);
    }

    public static Result analyze(Path file, String path, String root, Origin origin, boolean includeBinaries, long maxBytes)
            throws IOException {
        long size = Files.size(file);
        byte[] data;
        byte[] head;
        String sha;
        if (size > maxBytes) {
            sha = sha256(file);
            data = null;
            try (InputStream in = Files.newInputStream(file)) {
                head = in.readNBytes(HEAD);
            }
        } else {
            data = Files.readAllBytes(file);
            sha = sha256(data);
            head = data.length > HEAD ? Arrays.copyOf(data, HEAD) : data;
        }
        String ext = Paths.suffix(path).toLowerCase(Locale.ROOT);
        if (Encodings.isBinary(head) || Languages.isBinaryExtension(ext)) {
            // Whatever its extension says, a binary file has no lines to count: it is listed as
            // binary (or archive), and only with inventory.include_binaries.
            boolean archive = Languages.isArchiveExtension(ext);
            if (!archive && !includeBinaries) {
                return new Result(null, null);
            }
            FileRecord rec = record(path, root, origin, size, sha);
            rec.language = archive ? "archive" : "binary";
            rec.category = Category.BINARY;
            return new Result(rec, null);
        }
        String encoding = Encodings.detect(data != null && data.length > 0 ? data : head);
        String text = data != null && data.length > 0 ? Encodings.decode(data, encoding) : null;
        String headText = text != null ? Rx.head(text, HEAD) : Encodings.decode(head, encoding);
        FileRecord rec = record(path, root, origin, size, sha);
        rec.encoding = encoding;
        rec.language = Languages.detect(path, headText);
        rec.category = Languages.category(path, rec.language, headText);
        if (text != null) {
            Loc loc = Loc.count(text, rec.language);
            rec.locCode = loc.code();
            rec.locComment = loc.comment();
            rec.locBlank = loc.blank();
        }
        return new Result(rec, text);
    }

    private static FileRecord record(String path, String root, Origin origin, long size, String sha) {
        FileRecord rec = new FileRecord();
        rec.id = Fact.makeId("file", root, path);
        rec.path = path;
        rec.root = root;
        rec.origin = origin;
        rec.size = size;
        rec.sha256 = sha;
        rec.evidence = Evidence.list(path);
        return rec;
    }

    static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

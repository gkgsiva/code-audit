package com.legacyaudit.engine.inventory;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.mozilla.universalchardet.UniversalDetector;

/**
 * Encoding detection: UTF-8 (with or without a BOM), UTF-16 by its BOM, EBCDIC (cp037) by its
 * letters, then a statistical guess (juniversalchardet), and Windows-1252 or Latin-1 for western
 * text. Names are the Python codec names the Python tool wrote ({@code utf-8}, {@code cp037},
 * {@code cp1252}, {@code latin-1}, ...), so inventories of both tools compare.
 */
public final class Encodings {

    private static final int SAMPLE = 64 * 1024;

    /** EBCDIC (cp037) space, punctuation, letters and digits. */
    private static final boolean[] EBCDIC_TEXT = new boolean[256];

    /** Control bytes that never appear in text (tab, newlines, form feed, ESC, ^Z and EBCDIC NL are allowed). */
    private static final boolean[] CONTROL = new boolean[256];

    private static final double CONTROL_SHARE = 0.10;

    static {
        for (int b : new int[] {0x40, 0x4B, 0x4D, 0x5D, 0x6B, 0x7D, 0x7E, 0x5E, 0x60, 0x61, 0x25, 0x15}) {
            EBCDIC_TEXT[b] = true;
        }
        int[][] ranges = {{0xC1, 0xCA}, {0xD1, 0xDA}, {0xE2, 0xEA}, {0x81, 0x8A}, {0x91, 0x9A}, {0xA2, 0xAA}, {0xF0, 0xFA}};
        for (int[] r : ranges) {
            for (int b = r[0]; b < r[1]; b++) {
                EBCDIC_TEXT[b] = true;
            }
        }
        Set<Integer> allowed = Set.of(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x15, 0x1A, 0x1B);
        for (int b = 0; b < 0x20; b++) {
            CONTROL[b] = !allowed.contains(b);
        }
    }

    private static final Set<String> LATIN =
            Set.of("latin_1", "iso8859_1", "iso8859_15", "cp1250", "cp1252", "windows-1252", "cp850");

    /** juniversalchardet names as Python codec names. */
    private static final Map<String, String> PYTHON_NAMES = Map.ofEntries(
            Map.entry("WINDOWS-1252", "cp1252"),
            Map.entry("ISO-8859-1", "latin_1"),
            Map.entry("ISO-8859-15", "iso8859_15"),
            Map.entry("WINDOWS-1250", "cp1250"),
            Map.entry("WINDOWS-1251", "cp1251"),
            Map.entry("WINDOWS-1253", "cp1253"),
            Map.entry("WINDOWS-1255", "cp1255"),
            Map.entry("ISO-8859-2", "iso8859_2"),
            Map.entry("ISO-8859-5", "iso8859_5"),
            Map.entry("ISO-8859-7", "iso8859_7"),
            Map.entry("ISO-8859-8", "iso8859_8"),
            Map.entry("KOI8-R", "koi8_r"),
            Map.entry("IBM855", "cp855"),
            Map.entry("IBM866", "cp866"),
            Map.entry("X-MAC-CYRILLIC", "mac_cyrillic"),
            Map.entry("SHIFT_JIS", "shift_jis"),
            Map.entry("EUC-JP", "euc_jp"),
            Map.entry("ISO-2022-JP", "iso2022_jp"),
            Map.entry("EUC-KR", "euc_kr"),
            Map.entry("ISO-2022-KR", "iso2022_kr"),
            Map.entry("BIG5", "big5"),
            Map.entry("GB18030", "gb18030"),
            Map.entry("EUC-TW", "euc_tw"),
            Map.entry("HZ-GB-2312", "hz"),
            Map.entry("ISO-2022-CN", "iso2022_cn"),
            Map.entry("TIS620", "tis_620"),
            Map.entry("UTF-16BE", "utf_16_be"),
            Map.entry("UTF-16LE", "utf_16_le"),
            Map.entry("UTF-32BE", "utf_32_be"),
            Map.entry("UTF-32LE", "utf_32_le"));

    private Encodings() {}

    /** A NUL byte, or mostly control bytes, in the first 8 KB (UTF-16 with a BOM is text). */
    public static boolean isBinary(byte[] data) {
        int n = Math.min(data.length, 8192);
        if (n >= 2 && ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE || (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF)) {
            return false;
        }
        int controls = 0;
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) {
                return true;
            }
            if (CONTROL[b]) {
                controls++;
            }
        }
        return n > 0 && (n - controls) < n * (1 - CONTROL_SHARE);
    }

    static boolean looksEbcdic(byte[] sample) {
        if (sample.length < 16) {
            return false;
        }
        int ebcdic = 0;
        int ascii = 0;
        for (byte x : sample) {
            int b = x & 0xFF;
            if (EBCDIC_TEXT[b]) {
                ebcdic++;
            }
            if ((b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A)) {
                ascii++;
            }
        }
        return (double) ebcdic / sample.length > 0.7 && (double) ascii / sample.length < 0.15;
    }

    /** The Python codec name of text {@code data}. */
    public static String detect(byte[] data) {
        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            return "utf-8-sig";
        }
        if (startsWith(data, 0xFF, 0xFE) || startsWith(data, 0xFE, 0xFF)) {
            return "utf-16";
        }
        byte[] sample = data.length > SAMPLE ? Arrays.copyOf(data, SAMPLE) : data;
        int bad = firstUtf8Error(sample);
        if (bad < 0) {
            return "utf-8";
        }
        // A multi-byte character cut by the sample boundary is still UTF-8.
        if (bad >= sample.length - 3 && data.length > sample.length) {
            return "utf-8";
        }
        if (looksEbcdic(sample)) {
            return "cp037";
        }
        UniversalDetector detector = new UniversalDetector();
        detector.handleData(sample, 0, sample.length);
        detector.dataEnd();
        String found = detector.getDetectedCharset();
        String name = found == null ? "" : PYTHON_NAMES.getOrDefault(found.toUpperCase(Locale.ROOT), found.toLowerCase(Locale.ROOT));
        if (name.equals("utf-8")) {
            name = "";
        }
        if (name.isEmpty() || LATIN.contains(name)) {
            return isCp1252(sample) ? "cp1252" : "latin-1";
        }
        return charset(name) == null ? "latin-1" : name;
    }

    /** Text of {@code data} with every line break as {@code \n}; undecodable bytes become U+FFFD. */
    public static String decode(byte[] data, String encoding) {
        int skip = encoding.equals("utf-8-sig") && startsWith(data, 0xEF, 0xBB, 0xBF) ? 3 : 0;
        Charset cs = charset(encoding);
        String text = new String(data, skip, data.length - skip, cs == null ? StandardCharsets.ISO_8859_1 : cs);
        return newlines(text);
    }

    public static String newlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** The Java charset of a Python codec name, or null. */
    static Charset charset(String python) {
        String n = python.toLowerCase(Locale.ROOT).replace('_', '-');
        String java = switch (n) {
            case "utf-8", "utf-8-sig", "utf8" -> "UTF-8";
            case "utf-16" -> "UTF-16";
            case "cp037" -> "IBM037";
            case "cp1252", "windows-1252" -> "windows-1252";
            case "latin-1", "latin1", "iso8859-1" -> "ISO-8859-1";
            case "shift-jis" -> "Shift_JIS";
            case "koi8-r" -> "KOI8-R";
            case "mac-cyrillic" -> "x-MacCyrillic";
            case "tis-620" -> "TIS-620";
            case "hz" -> "x-HZ";
            default -> n.startsWith("cp") ? (n.length() == 6 ? "windows-" + n.substring(2) : "IBM" + n.substring(2))
                    : n.startsWith("iso8859-") ? "ISO-8859-" + n.substring(8)
                    : n;
        };
        try {
            return Charset.forName(java);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Index of the first byte that is not valid UTF-8, or -1. */
    static int firstUtf8Error(byte[] data) {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(8192);
        while (true) {
            CoderResult r = dec.decode(in, out, true);
            if (r.isError()) {
                return in.position();
            }
            if (r.isUnderflow()) {
                CoderResult f = dec.flush(out);
                return f.isError() ? in.position() : -1;
            }
            out.clear();
        }
    }

    /** Python's strict cp1252 leaves five bytes undefined. */
    private static boolean isCp1252(byte[] data) {
        for (byte x : data) {
            int b = x & 0xFF;
            if (b == 0x81 || b == 0x8D || b == 0x8F || b == 0x90 || b == 0x9D) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

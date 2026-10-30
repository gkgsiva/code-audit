package com.legacyaudit.reports;

import com.legacyaudit.engine.rules.Py;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Number formatting as the Python reports print them, and small file helpers. */
public final class Fmt {

    private Fmt() {}

    /** {@code f"{v:,}"} for whole numbers, {@code f"{v:,.1f}"} for floats, else {@code str(v)}. */
    public static String num(Object v) {
        if (v instanceof Boolean) {
            return Py.str(v);
        }
        if (v instanceof Double || v instanceof Float) {
            return fixed(((Number) v).doubleValue(), 1, true);
        }
        if (v instanceof Number n) {
            return String.format(Locale.ROOT, "%,d", n.longValue());
        }
        return Py.str(v);
    }

    /** {@code f"{x:.Nf}"} (with {@code ,} grouping when asked), rounded as Python does. */
    public static String fixed(double x, int digits, boolean group) {
        if (!Double.isFinite(x)) {
            return Double.isNaN(x) ? "nan" : x > 0 ? "inf" : "-inf";
        }
        BigDecimal d = new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN);
        String plain = d.abs().toPlainString();
        String sign = d.signum() < 0 || (d.signum() == 0 && 1 / x < 0) ? "-" : "";
        if (!group) {
            return sign + plain;
        }
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String frac = dot < 0 ? "" : plain.substring(dot);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                sb.append(',');
            }
            sb.append(whole.charAt(i));
        }
        return sign + sb + frac;
    }

    /** The value at a dotted path in nested maps, or {@code dflt}. */
    public static Object at(Object d, String path, Object dflt) {
        for (String part : path.split("\\.")) {
            if (!(d instanceof java.util.Map<?, ?> m) || !m.containsKey(part)) {
                return dflt;
            }
            d = m.get(part);
        }
        return d;
    }

    public static Object at(Object d, String path) {
        return at(d, path, 0L);
    }

    /** A number at a dotted path (0 when missing). */
    public static long n(Object d, String path) {
        Object v = at(d, path);
        return v instanceof Number x ? x.longValue() : 0;
    }

    /** A file's text as Python's {@code read_text(errors="replace")} reads it (universal newlines). */
    public static String readText(Path p) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString();
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Copy class-path resources ({@code folder/name}) to a folder. */
    public static void copyResources(String folder, List<String> names, Path dest) {
        try {
            for (String name : names) {
                Path target = dest.resolve(name);
                Files.createDirectories(target.getParent());
                try (InputStream in = Fmt.class.getClassLoader().getResourceAsStream(folder + "/" + name)) {
                    if (in == null) {
                        throw new IllegalStateException("missing resource " + folder + "/" + name);
                    }
                    Files.write(target, in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Delete a folder and everything in it ({@code shutil.rmtree}). */
    public static void rmtree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

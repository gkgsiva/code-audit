package com.legacyaudit.reports.jinja;

import com.legacyaudit.engine.rules.Py;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Python's {@code "..." % args} formatting: {@code %s %r %d %i %f %e %g %x %%} with flags, width and precision. */
final class Printf {

    private static final Pattern SPEC = Pattern.compile("%([-+ 0#]*)(\\d+|\\*)?(?:\\.(\\d+))?([sdirfFeEgGxXoc%])");

    private Printf() {}

    static String format(String fmt, List<?> args) {
        StringBuilder out = new StringBuilder();
        Matcher m = SPEC.matcher(fmt);
        int pos = 0;
        int k = 0;
        while (m.find()) {
            out.append(fmt, pos, m.start());
            pos = m.end();
            char conv = m.group(4).charAt(0);
            if (conv == '%') {
                out.append('%');
                continue;
            }
            if (k >= args.size()) {
                throw new TemplateError("not enough arguments for format string");
            }
            Object v = args.get(k++);
            String flags = m.group(1);
            Integer prec = m.group(3) == null ? null : Integer.parseInt(m.group(3));
            String body = switch (conv) {
                case 's' -> Values.str(v);
                case 'r' -> Py.repr(v, false);
                case 'd', 'i' -> sign(flags, BigDecimal.valueOf(Values.asDouble(v)).setScale(0, RoundingMode.DOWN).toPlainString(), v);
                case 'f', 'F' -> sign(flags, fixed(Values.asDouble(v), prec == null ? 6 : prec), v);
                case 'e', 'E' -> {
                    String s = String.format(java.util.Locale.ROOT, "%." + (prec == null ? 6 : prec) + "e", Values.asDouble(v));
                    yield sign(flags, conv == 'E' ? s.toUpperCase(java.util.Locale.ROOT) : s, v);
                }
                case 'g', 'G' -> sign(flags, Py.str(Values.asDouble(v)), v);
                case 'x' -> Long.toHexString(Values.asLong(v));
                case 'X' -> Long.toHexString(Values.asLong(v)).toUpperCase(java.util.Locale.ROOT);
                case 'o' -> Long.toOctalString(Values.asLong(v));
                case 'c' -> v instanceof String s ? s : new String(Character.toChars((int) Values.asLong(v)));
                default -> throw new TemplateError("unsupported format character '" + conv + "'");
            };
            if (m.group(2) != null && !m.group(2).equals("*")) {
                int width = Integer.parseInt(m.group(2));
                int n = body.codePointCount(0, body.length());
                if (n < width) {
                    String pad = " ".repeat(width - n);
                    if (flags.contains("-")) {
                        body = body + pad;
                    } else if (flags.contains("0") && conv != 's' && conv != 'r') {
                        boolean neg = body.startsWith("-") || body.startsWith("+");
                        body = neg ? body.charAt(0) + "0".repeat(width - n) + body.substring(1) : "0".repeat(width - n) + body;
                    } else {
                        body = pad + body;
                    }
                }
            }
            out.append(body);
        }
        if (k < args.size() && !(args.size() == 1 && args.get(0) instanceof java.util.Map)) {
            throw new TemplateError("not all arguments converted during string formatting");
        }
        out.append(fmt.substring(pos));
        return out.toString();
    }

    private static String sign(String flags, String s, Object v) {
        if (!s.startsWith("-")) {
            if (flags.contains("+")) {
                return "+" + s;
            }
            if (flags.contains(" ")) {
                return " " + s;
            }
        }
        return s;
    }

    /** {@code %.Nf}: the exact binary value rounded half-even, as C's printf does. */
    static String fixed(double d, int prec) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        String s = new BigDecimal(d).setScale(prec, RoundingMode.HALF_EVEN).toPlainString();
        if (s.startsWith("-") && new BigDecimal(s).signum() == 0) {
            return s; // Python keeps -0.0's sign
        }
        return s;
    }
}

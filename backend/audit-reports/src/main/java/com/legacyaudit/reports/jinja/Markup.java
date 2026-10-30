package com.legacyaudit.reports.jinja;

/** Text that is already HTML (markupsafe's {@code Markup}): printed as it is, never escaped again. */
public record Markup(String html) {

    @Override
    public String toString() {
        return html;
    }

    /** markupsafe's {@code escape}: {@code & < > " '} as entities. */
    public static String escape(String s) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&#34;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (rep != null && out == null) {
                out = new StringBuilder(s.length() + 16).append(s, 0, i);
            }
            if (out != null) {
                if (rep != null) {
                    out.append(rep);
                } else {
                    out.append(c);
                }
            }
        }
        return out == null ? s : out.toString();
    }
}

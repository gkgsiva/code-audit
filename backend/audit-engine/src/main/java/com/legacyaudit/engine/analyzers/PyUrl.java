package com.legacyaudit.engine.analyzers;

/**
 * Python's {@code urllib.parse.urlsplit}: scheme, netloc, path, query and fragment, with
 * {@code hostname} and {@code port} as Python computes them ({@code port} raises ValueError on a
 * port that is not a number or out of range).
 */
public record PyUrl(String scheme, String netloc, String path, String query, String fragment) {

    private static final String SCHEME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-.";

    public static PyUrl split(String url) {
        int start = 0;
        while (start < url.length() && url.charAt(start) <= ' ') {
            start++;
        }
        url = url.substring(start).replace("\t", "").replace("\r", "").replace("\n", "");
        String scheme = "";
        String netloc = "";
        String query = "";
        String fragment = "";
        int i = url.indexOf(':');
        if (i > 0 && url.charAt(0) < 128 && Character.isLetter(url.charAt(0))) {
            boolean ok = true;
            for (int k = 0; k < i; k++) {
                if (SCHEME_CHARS.indexOf(url.charAt(k)) < 0) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                scheme = url.substring(0, i).toLowerCase(java.util.Locale.ROOT);
                url = url.substring(i + 1);
            }
        }
        if (url.startsWith("//")) {
            int end = url.length();
            for (char c : new char[] {'/', '?', '#'}) {
                int p = url.indexOf(c, 2);
                if (p >= 0) {
                    end = Math.min(end, p);
                }
            }
            netloc = url.substring(2, end);
            url = url.substring(end);
            if ((netloc.contains("[") && !netloc.contains("]")) || (netloc.contains("]") && !netloc.contains("["))) {
                throw new PyError("ValueError", "Invalid IPv6 URL");
            }
        }
        int h = url.indexOf('#');
        if (h >= 0) {
            fragment = url.substring(h + 1);
            url = url.substring(0, h);
        }
        int q = url.indexOf('?');
        if (q >= 0) {
            query = url.substring(q + 1);
            url = url.substring(0, q);
        }
        return new PyUrl(scheme, netloc, url, query, fragment);
    }

    private String[] hostinfo() {
        int at = netloc.lastIndexOf('@');
        String info = at >= 0 ? netloc.substring(at + 1) : netloc;
        String hostname;
        String port;
        int br = info.indexOf('[');
        if (br >= 0) {
            String bracketed = info.substring(br + 1);
            int close = bracketed.indexOf(']');
            hostname = close >= 0 ? bracketed.substring(0, close) : bracketed;
            String rest = close >= 0 ? bracketed.substring(close + 1) : "";
            int c = rest.indexOf(':');
            port = c >= 0 ? rest.substring(c + 1) : "";
        } else {
            int c = info.indexOf(':');
            hostname = c >= 0 ? info.substring(0, c) : info;
            port = c >= 0 ? info.substring(c + 1) : "";
        }
        return new String[] {hostname, port.isEmpty() ? null : port};
    }

    /** The host in lower case, or null. */
    public String hostname() {
        String host = hostinfo()[0];
        if (host.isEmpty()) {
            return null;
        }
        int pct = host.indexOf('%');
        return pct >= 0 ? host.substring(0, pct).toLowerCase(java.util.Locale.ROOT) + host.substring(pct) : host.toLowerCase(java.util.Locale.ROOT);
    }

    /** The port, or null; a port that is not a number in 0-65535 raises ValueError. */
    public Integer port() {
        String port = hostinfo()[1];
        if (port == null) {
            return null;
        }
        for (int k = 0; k < port.length(); k++) {
            char c = port.charAt(k);
            if (c < '0' || c > '9') {
                throw new PyError("ValueError", "Port could not be cast to integer value as '" + port + "'");
            }
        }
        String digits = port.replaceFirst("^0+(?=.)", "");
        if (digits.length() > 5 || Integer.parseInt(digits) > 65535) {
            throw new PyError("ValueError", "Port out of range 0-65535");
        }
        return Integer.parseInt(digits);
    }
}

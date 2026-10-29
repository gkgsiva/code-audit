package com.legacyaudit.engine.components;

import com.legacyaudit.engine.inventory.Paths;
import com.legacyaudit.engine.inventory.Rx;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * What a folder's {@code pom.xml}, {@code settings.gradle} or {@code build.gradle} says about its
 * modules.
 *
 * @param modules folders, relative to the source root
 * @param deps artifactIds / project folders it uses
 * @param runnable Spring Boot, a main class in the manifest, the application plugin
 */
record BuildInfo(String artifact, String packaging, List<String> modules, boolean gradleRoot, List<String> deps, boolean runnable) {

    /** A build file that is not well-formed XML. */
    static final class XmlError extends Exception {
        XmlError(String message) {
            super(message);
        }
    }

    BuildInfo withModules(List<String> m) {
        return new BuildInfo(artifact, packaging, m, gradleRoot, deps, runnable);
    }

    private static final Pattern GRADLE_INCLUDE = Rx.of("^\\s*include(?:Flat)?\\b\\s*\\(?(.*)$", Rx.M);
    private static final Pattern GRADLE_PROJECT_DIR = Rx.of(
            "project\\(\\s*['\"]:?([^'\"]+)['\"]\\s*\\)\\.projectDir\\s*=\\s*"
                    + "(?:new\\s+File\\(\\s*(?:settingsDir|rootDir)\\s*,\\s*|file\\(\\s*)['\"]([^'\"]+)['\"]");
    private static final Pattern QUOTED = Rx.of("['\"]([^'\"]+)['\"]");
    private static final Pattern ROOT_NAME = Rx.of("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern RUNNABLE = Rx.of(
            "spring-boot-maven-plugin|<mainClass>|org\\.springframework\\.boot['\"]|"
                    + "\\bid\\s*\\(?\\s*['\"]application['\"]|apply\\s+plugin\\s*:\\s*['\"]application['\"]|"
                    + "^\\s*application\\s*$|mainClass(?:Name)?\\s*[=.]",
            Rx.M);
    private static final Pattern GRADLE_PROJECT_DEP = Rx.of("project\\(\\s*(?:path\\s*:\\s*)?['\"]:?([^'\"]+)['\"]");
    private static final Pattern UNKNOWN_ENTITY = Pattern.compile("&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");
    private static final Pattern XML_DECL = Pattern.compile("^\\s*<\\?xml[^>]*\\?>");

    /** The build makes something that runs on its own (not a library jar). */
    static boolean runnable(String text) {
        return RUNNABLE.matcher(text).find();
    }

    /**
     * A module path as written in a pom ({@code core}, {@code ../shared}, {@code core/pom.xml}) as a
     * folder relative to the source root, or null when it points outside the root.
     */
    static String moduleDir(String base, String module) {
        String m = module.strip().replace('\\', '/');
        if (m.isEmpty()) {
            return null;
        }
        if (m.toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
            int i = m.lastIndexOf('/');
            m = i < 0 ? "" : m.substring(0, i);
        }
        String joined = normpath((base.isEmpty() ? "." : base) + "/" + (m.isEmpty() ? "." : m));
        if (joined.equals(".")) {
            return "";
        }
        if (joined.startsWith("../") || joined.equals("..") || joined.startsWith("/")) {
            return null;
        }
        return joined;
    }

    /** Python's {@code posixpath.normpath} for relative paths. */
    static String normpath(String path) {
        boolean abs = path.startsWith("/");
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") && !out.isEmpty() && !out.getLast().equals("..")) {
                out.removeLast();
            } else if (!(part.equals("..") && abs)) {
                out.add(part);
            }
        }
        String joined = String.join("/", out);
        return abs ? "/" + joined : joined.isEmpty() ? "." : joined;
    }

    /** artifactId, packaging and modules ({@code <modules>} and those of every profile). */
    static BuildInfo readPom(String base, String text) throws XmlError {
        Element root = parseXml(text);
        List<String> modules = new ArrayList<>();
        List<Element> moduleEls = new ArrayList<>();
        for (Element ms : children(root, "modules")) {
            moduleEls.addAll(children(ms, "module"));
        }
        for (Element profiles : children(root, "profiles")) {
            for (Element profile : children(profiles, "profile")) {
                for (Element ms : children(profile, "modules")) {
                    moduleEls.addAll(children(ms, "module"));
                }
            }
        }
        for (Element el : moduleEls) {
            String d = moduleDir(base, text(el));
            if (d != null && !d.equals(base) && !modules.contains(d)) {
                modules.add(d);
            }
        }
        String packaging = childText(root, "packaging");
        packaging = packaging == null ? "jar" : packaging.strip().toLowerCase(java.util.Locale.ROOT);
        if (packaging.isEmpty()) {
            packaging = "jar";
        }
        List<String> deps = new ArrayList<>();
        for (Element ds : children(root, "dependencies")) {
            for (Element d : children(ds, "dependency")) {
                String a = childText(d, "artifactId");
                if (a != null && !a.isEmpty()) {
                    deps.add(a.strip());
                }
            }
        }
        return new BuildInfo(childText(root, "artifactId"), packaging, modules, false, deps, runnable(text));
    }

    /**
     * Projects a module's {@code build.gradle} depends on ({@code implementation project(':core')}),
     * as folders relative to the source root, and whether it builds an application.
     */
    static BuildInfo readGradleBuild(String base, String text) {
        List<String> deps = new ArrayList<>();
        Matcher m = GRADLE_PROJECT_DEP.matcher(text);
        while (m.find()) {
            String d = strip(m.group(1), ':').replace(':', '/'); // matched against module folders later
            if (!d.isEmpty() && !deps.contains(d)) {
                deps.add(d);
            }
        }
        return new BuildInfo(null, null, List.of(), false, deps, runnable(text));
    }

    /** Projects a {@code settings.gradle} includes ({@code include 'core', ':batch:billing'}). */
    static BuildInfo readGradleSettings(String base, String text) {
        Map<String, String> dirs = new LinkedHashMap<>();
        Matcher pd = GRADLE_PROJECT_DIR.matcher(text);
        while (pd.find()) {
            dirs.put(pd.group(1).replace(':', '/'), pd.group(2));
        }
        List<String> modules = new ArrayList<>();
        Matcher inc = GRADLE_INCLUDE.matcher(text);
        while (inc.find()) {
            Matcher q = QUOTED.matcher(inc.group(1));
            while (q.find()) {
                String key = strip(q.group(1), ':').replace(':', '/');
                String d = moduleDir(base, dirs.getOrDefault(key, key));
                if (d != null && !d.equals(base) && !modules.contains(d)) {
                    modules.add(d);
                }
            }
        }
        Matcher name = ROOT_NAME.matcher(text);
        return new BuildInfo(name.find() ? name.group(1) : null, null, modules, true, List.of(), false);
    }

    /** Parse XML without fetching DTDs; element names lose their namespace. */
    static Element parseXml(String text) throws XmlError {
        // Unknown HTML entities (&nbsp;) are common in legacy XML; neutralise them.
        String t = UNKNOWN_ENTITY.matcher(text).replaceAll("&amp;");
        int i = 0;
        while (i < t.length() && t.charAt(i) == '\ufeff') {
            i++;
        }
        t = t.substring(i);
        Matcher decl = XML_DECL.matcher(t);
        if (decl.find()) {
            t = "\n".repeat((int) decl.group().chars().filter(c -> c == '\n').count()) + t.substring(decl.end());
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            Document doc = b.parse(new InputSource(new StringReader(t)));
            return doc.getDocumentElement();
        } catch (Exception e) {
            throw new XmlError(e.getMessage());
        }
    }

    static String local(Node n) {
        String name = n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
        int c = name.indexOf(':');
        return c < 0 ? name : name.substring(c + 1);
    }

    static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local(e).equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }

    static String text(Element el) {
        return el.getTextContent().strip();
    }

    static String childText(Element el, String tag) {
        List<Element> c = children(el, tag);
        return c.isEmpty() ? null : text(c.getFirst());
    }

    private static String strip(String s, char c) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == c) {
            a++;
        }
        while (b > a && s.charAt(b - 1) == c) {
            b--;
        }
        return s.substring(a, b);
    }

    static String parentOf(String path) {
        return Paths.parent(path);
    }
}

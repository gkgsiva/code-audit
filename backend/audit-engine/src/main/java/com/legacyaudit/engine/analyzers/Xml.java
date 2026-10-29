package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * XML as the Python analyzers read it: no DTD is fetched, tags lose their namespace (kept on
 * {@link El#ns}), every element carries the line its start tag begins on, and unknown HTML
 * entities ({@code &nbsp;}) are read as text.
 */
public final class Xml {

    private Xml() {}

    /** The document is not well-formed. */
    public static final class ParseError extends Exception {
        public ParseError(String message) {
            super(message);
        }
    }

    /** An element, shaped like Python's ElementTree element. */
    public static final class El implements Iterable<El> {
        public final String tag;
        public final String ns;
        public final Integer line;
        private final Map<String, String> attrib;
        public final List<El> children = new ArrayList<>();
        public String text;
        public String tail;

        El(String tag, String ns, Integer line, Map<String, String> attrib) {
            this.tag = tag;
            this.ns = ns;
            this.line = line;
            this.attrib = attrib;
        }

        /** The attributes, without namespaces. */
        public Map<String, String> attrs() {
            return attrib;
        }

        public String get(String name) {
            return attrib.get(name);
        }

        public String get(String name, String otherwise) {
            return attrib.getOrDefault(name, otherwise);
        }

        @Override
        public Iterator<El> iterator() {
            return children.iterator();
        }

        /** This element and every descendant, in document order. */
        public List<El> iter() {
            List<El> out = new ArrayList<>();
            Deque<El> stack = new ArrayDeque<>();
            stack.push(this);
            while (!stack.isEmpty()) {
                El e = stack.pop();
                out.add(e);
                for (int i = e.children.size() - 1; i >= 0; i--) {
                    stack.push(e.children.get(i));
                }
            }
            return out;
        }

        public List<El> iter(String... tags) {
            List<String> wanted = List.of(tags);
            return iter().stream().filter(e -> wanted.contains(e.tag)).toList();
        }

        /** ElementTree's {@code find} for a path of child tags ({@code a/b}). */
        public El find(String path) {
            List<El> all = findall(path);
            return all.isEmpty() ? null : all.getFirst();
        }

        public List<El> findall(String path) {
            List<El> current = List.of(this);
            for (String step : path.split("/")) {
                List<El> next = new ArrayList<>();
                for (El e : current) {
                    for (El c : e.children) {
                        if (step.equals("*") || c.tag.equals(step)) {
                            next.add(c);
                        }
                    }
                }
                current = next;
            }
            return current;
        }

        public List<El> children(Predicate<El> test) {
            return children.stream().filter(test).toList();
        }

        /** {@code "".join(el.itertext())}, stripped. */
        public String textOf() {
            StringBuilder sb = new StringBuilder();
            itertext(sb);
            return TextUtil.strip(sb.toString());
        }

        /** {@code "".join(el.itertext())}, not stripped. */
        public String allText() {
            StringBuilder sb = new StringBuilder();
            itertext(sb);
            return sb.toString();
        }

        /** The non-empty pieces of {@code el.itertext()}, in document order. */
        public List<String> texts() {
            List<String> out = new ArrayList<>();
            texts(out);
            return out;
        }

        private void texts(List<String> out) {
            if (text != null && !text.isEmpty()) {
                out.add(text);
            }
            for (El c : children) {
                c.texts(out);
                if (c.tail != null && !c.tail.isEmpty()) {
                    out.add(c.tail);
                }
            }
        }

        /** {@code " ".join(t.strip() for t in el.itertext() if t.strip())}. */
        public String joinedText() {
            List<String> parts = new ArrayList<>();
            for (String t : texts()) {
                String st = TextUtil.strip(t);
                if (!st.isEmpty()) {
                    parts.add(st);
                }
            }
            return String.join(" ", parts);
        }

        private void itertext(StringBuilder sb) {
            if (text != null) {
                sb.append(text);
            }
            for (El c : children) {
                c.itertext(sb);
                if (c.tail != null) {
                    sb.append(c.tail);
                }
            }
        }

        /** {@code child_text}: the stripped text of the first child with that tag, or null. */
        public String childText(String tag) {
            El c = find(tag);
            return c == null ? null : c.textOf();
        }

        public String textOrEmpty() {
            return text == null ? "" : text;
        }

        @Override
        public String toString() {
            return "<" + tag + " " + attrib + ">";
        }
    }

    private static final Pattern UNKNOWN_ENTITY = Rx.of("&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");
    private static final Pattern DECLARATION = Rx.of("^\\s*<\\?xml[^>]*\\?>");

    public static El parse(String text) throws ParseError {
        String t = UNKNOWN_ENTITY.matcher(text).replaceAll("&amp;");
        t = TextUtil.lstrip(t, "﻿");
        t = TextUtil.sub(DECLARATION, t, m -> "\n".repeat(TextUtil.count(m.group(), '\n')));
        final String doc = t;
        TextUtil.LineIndex index = new TextUtil.LineIndex(doc);
        int[] lineStarts = lineStarts(doc);
        Handler handler = new Handler(doc, index, lineStarts);
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(true);
            f.setValidating(false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = f.newSAXParser();
            var reader = parser.getXMLReader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            reader.parse(new InputSource(new StringReader(doc)));
        } catch (SAXParseException e) {
            throw new ParseError(e.getMessage() + ": line " + e.getLineNumber() + ", column " + e.getColumnNumber());
        } catch (Exception e) {
            throw new ParseError(String.valueOf(e.getMessage()));
        }
        if (handler.root == null) {
            throw new ParseError("no element found: line 1, column 0");
        }
        return handler.root;
    }

    private static int[] lineStarts(String text) {
        List<Integer> s = new ArrayList<>();
        s.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                s.add(i + 1);
            }
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final class Handler extends DefaultHandler {
        private final String doc;
        private final TextUtil.LineIndex index;
        private final int[] lineStarts;
        private Locator locator;
        private final Deque<El> stack = new ArrayDeque<>();
        private El root;
        private El last;
        private StringBuilder data = new StringBuilder();

        Handler(String doc, TextUtil.LineIndex index, int[] lineStarts) {
            this.doc = doc;
            this.index = index;
            this.lineStarts = lineStarts;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        /** The line the start tag begins on: the last '<' before where the parser stands. */
        private int startLine() {
            int line = locator.getLineNumber();
            int col = locator.getColumnNumber();
            if (line < 1 || line > lineStarts.length) {
                return Math.max(line, 1);
            }
            int offset = Math.min(lineStarts[line - 1] + Math.max(col - 1, 0), doc.length());
            int lt = doc.lastIndexOf('<', Math.max(offset - 1, 0));
            return lt < 0 ? line : index.line(lt);
        }

        private void flush() {
            if (data.isEmpty()) {
                return;
            }
            String s = data.toString();
            data = new StringBuilder();
            if (last != null) {
                last.tail = last.tail == null ? s : last.tail + s;
            } else if (!stack.isEmpty()) {
                El e = stack.peek();
                e.text = e.text == null ? s : e.text + s;
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            flush();
            Map<String, String> a = new LinkedHashMap<>();
            for (int i = 0; i < atts.getLength(); i++) {
                String name = atts.getLocalName(i);
                a.put(name == null || name.isEmpty() ? atts.getQName(i) : name, atts.getValue(i));
            }
            String tag = localName == null || localName.isEmpty() ? qName : localName;
            El e = new El(tag, uri == null ? "" : uri, startLine(), a);
            if (stack.isEmpty()) {
                root = e;
            } else {
                stack.peek().children.add(e);
            }
            stack.push(e);
            last = null;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flush();
            last = stack.pop();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            data.append(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) {
            data.append(ch, start, length);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            throw e;
        }
    }

    /** Thrown when an element is asked for that is not there. */
    public static NoSuchElementException missing(String what) {
        return new NoSuchElementException(what);
    }
}

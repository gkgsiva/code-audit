package com.legacyaudit.engine.analyzers.ts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSReader;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;

/** Parses text with a tree-sitter grammar and copies the tree into {@link TsNode}s. */
public final class TsTree {

    private TsTree() {}

    /** One parser per thread and grammar (parsers are not thread-safe). */
    public static ThreadLocal<TSParser> parsers(Supplier<TSLanguage> language) {
        return ThreadLocal.withInitial(() -> {
            TSParser p = new TSParser();
            p.setLanguage(language.get());
            return p;
        });
    }

    /** Parses the text and returns a copy of the syntax tree (byte offsets are UTF-8). */
    public static TsNode parse(TSParser parser, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        List<TsNode> order = new ArrayList<>();
        TsNode fast = TsNative.available() ? TsNative.parse(parser, bytes, order) : null;
        if (fast != null) {
            finish(order, bytes);
            return fast;
        }
        // A reader hands over exact UTF-8 bytes (a Java string would cross JNI as modified UTF-8).
        TSReader reader = (buf, offset, point) -> {
            int n = Math.max(0, Math.min(buf.length, bytes.length - offset));
            System.arraycopy(bytes, offset, buf, 0, n);
            return n;
        };
        try (TSTree tree = parser.parse(new byte[4096], null, reader, TSInputEncoding.TSInputEncodingUTF8)) {
            TsNode root = copy(tree.getRootNode(), bytes, order);
            finish(order, bytes);
            return root;
        }
    }

    /** What a grammar symbol is: its type name and whether it is a named node. */
    private record Kind(String type, boolean named) {}

    /**
     * One node, with as few calls into the native tree as possible: type and named-ness are per
     * grammar symbol, rows come from the byte offsets and error flags are worked out afterwards.
     */
    private static TsNode node(TSNode n, byte[] bytes, Map<Integer, Kind> kinds) {
        Kind kind = kinds.computeIfAbsent(n.getSymbol(), k -> new Kind(n.getType(), n.isNamed()));
        int start = n.getStartByte();
        int end = n.getEndByte();
        boolean missing = start == end && n.isMissing(); // a missing node is always empty
        return new TsNode(kind.type(), kind.named(), missing, false, start, end, 0, 0, bytes);
    }

    /**
     * Copies the tree through the binding with a cursor: walking siblings this way is linear,
     * where asking a node for its i-th child (or its field name) walks the siblings every time.
     */
    private static TsNode copy(TSNode root, byte[] bytes, List<TsNode> order) {
        Map<Integer, Kind> kinds = new HashMap<>();
        Map<Integer, String> fieldNames = new HashMap<>();
        TsNode out = node(root, bytes, kinds);
        order.add(out);
        try (TSTreeCursor cursor = new TSTreeCursor(root)) {
            TsNode parent = out;
            boolean more = cursor.gotoFirstChild();
            while (more) {
                TSNode c = cursor.currentNode();
                TsNode d = node(c, bytes, kinds);
                order.add(d);
                d.parent = parent;
                parent.children.add(d);
                int fieldId = cursor.currentFieldId();
                parent.fields.add(fieldId == 0 ? null : fieldNames.computeIfAbsent(fieldId, k -> cursor.currentFieldName()));
                if (cursor.gotoFirstChild()) {
                    parent = d;
                    continue;
                }
                while (!cursor.gotoNextSibling()) {
                    if (parent == out || !cursor.gotoParent()) {
                        more = false;
                        break;
                    }
                    parent = parent.parent;
                }
            }
        }
        return out;
    }

    /**
     * Rows from a table of line starts; error flags bottom up (an ERROR or missing node, or a
     * node with one inside it), as tree-sitter reports them. {@code order} has parents first.
     */
    private static void finish(List<TsNode> order, byte[] bytes) {
        int[] lineStarts = lineStarts(bytes);
        for (int i = order.size() - 1; i >= 0; i--) {
            TsNode n = order.get(i);
            n.startRow = row(lineStarts, n.startByte);
            n.endRow = row(lineStarts, n.endByte);
            if (n.type.equals("ERROR") || n.missing) {
                n.hasError = true;
            }
            if (n.hasError && n.parent != null) {
                n.parent.hasError = true;
            }
        }
    }

    private static int[] lineStarts(byte[] bytes) {
        int count = 1;
        for (byte b : bytes) {
            if (b == '\n') {
                count++;
            }
        }
        int[] out = new int[count];
        int k = 1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                out[k++] = i + 1;
            }
        }
        return out;
    }

    private static int row(int[] lineStarts, int offset) {
        int i = Arrays.binarySearch(lineStarts, offset);
        return i >= 0 ? i : -i - 2;
    }
}

package com.legacyaudit.engine.analyzers.ts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A tree-sitter syntax node copied out of the native tree, with the members the analyzers use
 * (the Python binding's {@code Node}): type, children and named children, fields, byte span,
 * rows, parent and error flags. Byte offsets are UTF-8, as in the Python tool.
 */
public final class TsNode {

    public final String type;
    public final boolean named;
    public final boolean missing;
    public boolean hasError;
    public final int startByte;
    public final int endByte;
    public int startRow;
    public int endRow;
    public TsNode parent;
    public final List<TsNode> children = new ArrayList<>();
    final List<String> fields = new ArrayList<>();
    private final byte[] source;
    private List<TsNode> namedChildren;

    TsNode(String type, boolean named, boolean missing, boolean hasError, int startByte, int endByte, int startRow, int endRow,
            byte[] source) {
        this.type = type;
        this.named = named;
        this.missing = missing;
        this.hasError = hasError;
        this.startByte = startByte;
        this.endByte = endByte;
        this.startRow = startRow;
        this.endRow = endRow;
        this.source = source;
    }

    /** The children that are named nodes (not punctuation or keywords). */
    public List<TsNode> namedChildren() {
        if (namedChildren == null) {
            List<TsNode> out = new ArrayList<>();
            for (TsNode c : children) {
                if (c.named) {
                    out.add(c);
                }
            }
            namedChildren = out;
        }
        return namedChildren;
    }

    /** The first child in that field, or null. */
    public TsNode field(String name) {
        for (int i = 0; i < children.size(); i++) {
            if (name.equals(fields.get(i))) {
                return children.get(i);
            }
        }
        return null;
    }

    /** The source text of the node. */
    public String text() {
        return new String(source, startByte, endByte - startByte, StandardCharsets.UTF_8);
    }

    /** The source text of a node, or "" for none. */
    public static String txt(TsNode node) {
        return node == null ? "" : node.text();
    }

    /** 1-based line the node starts on. */
    public int line() {
        return startRow + 1;
    }

    @Override
    public String toString() {
        return type + "[" + startByte + "," + endByte + "]";
    }
}

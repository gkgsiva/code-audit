package com.legacyaudit.engine.analyzers.ts;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.treesitter.TSParser;

/**
 * Fast tree copy: calls the tree-sitter C library directly (Java's foreign function API) instead
 * of through the binding's JNI methods, which cost about a microsecond per call.
 *
 * <p>The C library is the one the binding already loaded. Its {@code ts_*} functions are
 * exported on Linux and macOS but not in the Windows DLL; there {@link #available()} is false
 * and {@link TsTree} copies the tree through the binding instead (same result, slower).
 */
final class TsNative {

    private TsNative() {}

    // struct TSNode { uint32_t context[4]; const void *id; const TSTree *tree; }
    private static final MemoryLayout NODE = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT), ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    // struct TSTreeCursor { const void *tree; const void *id; uint32_t context[3]; } (padded to 32)
    private static final MemoryLayout CURSOR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT), MemoryLayout.paddingLayout(4));

    private static final MethodHandle ROOT_NODE;
    private static final MethodHandle CURSOR_NEW;
    private static final MethodHandle CURSOR_DELETE;
    private static final MethodHandle FIRST_CHILD;
    private static final MethodHandle NEXT_SIBLING;
    private static final MethodHandle PARENT;
    private static final MethodHandle CURRENT_NODE;
    private static final MethodHandle FIELD_ID;
    private static final MethodHandle FIELD_NAME;
    private static final MethodHandle START_BYTE;
    private static final MethodHandle END_BYTE;
    private static final MethodHandle SYMBOL;
    private static final MethodHandle IS_MISSING;
    private static final MethodHandle IS_NAMED;
    private static final MethodHandle TYPE;
    private static final MethodHandle PARSE;
    private static final MethodHandle TREE_DELETE;
    private static final Field PARSER_PTR;
    private static final boolean AVAILABLE;

    static {
        MethodHandle[] h = new MethodHandle[17];
        Field ptr = null;
        boolean ok = false;
        try {
            if (Boolean.getBoolean("legacyaudit.treesitter.binding")) { // tests the Windows path
                throw new UnsupportedOperationException("binding only");
            }
            new TSParser().close(); // makes the binding load its native library
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.loaderLookup();
            ValueLayout ptrL = ValueLayout.ADDRESS;
            ValueLayout u32 = ValueLayout.JAVA_INT;
            ValueLayout u16 = ValueLayout.JAVA_SHORT;
            ValueLayout bool = ValueLayout.JAVA_BOOLEAN;
            Object[][] specs = {
                {"ts_tree_root_node", FunctionDescriptor.of(NODE, ptrL)},
                {"ts_tree_cursor_new", FunctionDescriptor.of(CURSOR, NODE)},
                {"ts_tree_cursor_delete", FunctionDescriptor.ofVoid(ptrL)},
                {"ts_tree_cursor_goto_first_child", FunctionDescriptor.of(bool, ptrL)},
                {"ts_tree_cursor_goto_next_sibling", FunctionDescriptor.of(bool, ptrL)},
                {"ts_tree_cursor_goto_parent", FunctionDescriptor.of(bool, ptrL)},
                {"ts_tree_cursor_current_node", FunctionDescriptor.of(NODE, ptrL)},
                {"ts_tree_cursor_current_field_id", FunctionDescriptor.of(u16, ptrL)},
                {"ts_tree_cursor_current_field_name", FunctionDescriptor.of(ptrL, ptrL)},
                {"ts_node_start_byte", FunctionDescriptor.of(u32, NODE)},
                {"ts_node_end_byte", FunctionDescriptor.of(u32, NODE)},
                {"ts_node_symbol", FunctionDescriptor.of(u16, NODE)},
                {"ts_node_is_missing", FunctionDescriptor.of(bool, NODE)},
                {"ts_node_is_named", FunctionDescriptor.of(bool, NODE)},
                {"ts_node_type", FunctionDescriptor.of(ptrL, NODE)},
                {"ts_parser_parse_string_encoding", FunctionDescriptor.of(ptrL, ptrL, ptrL, ptrL, u32, u32)},
                {"ts_tree_delete", FunctionDescriptor.ofVoid(ptrL)},
            };
            for (int i = 0; i < specs.length; i++) {
                MemorySegment fn = lookup.find((String) specs[i][0]).orElseThrow();
                h[i] = linker.downcallHandle(fn, (FunctionDescriptor) specs[i][1]);
            }
            ptr = TSParser.class.getDeclaredField("ptr");
            ptr.setAccessible(true);
            ok = true;
        } catch (Throwable e) { // no exported symbols (Windows) or no native access: use the binding
            ok = false;
        }
        ROOT_NODE = h[0];
        CURSOR_NEW = h[1];
        CURSOR_DELETE = h[2];
        FIRST_CHILD = h[3];
        NEXT_SIBLING = h[4];
        PARENT = h[5];
        CURRENT_NODE = h[6];
        FIELD_ID = h[7];
        FIELD_NAME = h[8];
        START_BYTE = h[9];
        END_BYTE = h[10];
        SYMBOL = h[11];
        IS_MISSING = h[12];
        IS_NAMED = h[13];
        TYPE = h[14];
        PARSE = h[15];
        TREE_DELETE = h[16];
        PARSER_PTR = ptr;
        AVAILABLE = ok;
    }

    static boolean available() {
        return AVAILABLE;
    }

    private record Kind(String type, boolean named) {}

    /**
     * Parses UTF-8 text with the binding's parser (its grammar is already set) and copies the
     * whole tree; null if a native call failed (the caller then uses the binding).
     */
    static TsNode parse(TSParser parser, byte[] bytes, List<TsNode> order) {
        MemorySegment tree = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(Math.max(1, bytes.length));
            MemorySegment.copy(bytes, 0, text, ValueLayout.JAVA_BYTE, 0, bytes.length);
            MemorySegment p = MemorySegment.ofAddress(PARSER_PTR.getLong(parser));
            tree = (MemorySegment) PARSE.invokeExact(p, MemorySegment.NULL, text, bytes.length, 0); // 0 = UTF-8
            if (tree.equals(MemorySegment.NULL)) {
                return null;
            }
            return copy(arena, tree, bytes, order);
        } catch (Throwable e) {
            order.clear();
            return null;
        } finally {
            if (!tree.equals(MemorySegment.NULL)) {
                try {
                    TREE_DELETE.invokeExact(tree);
                } catch (Throwable ignored) {
                    // nothing more to free
                }
            }
        }
    }

    private static TsNode copy(Arena arena, MemorySegment tree, byte[] bytes, List<TsNode> order) throws Throwable {
        MemorySegment root = (MemorySegment) ROOT_NODE.invokeExact((SegmentAllocator) arena, tree);
        MemorySegment cursor = (MemorySegment) CURSOR_NEW.invokeExact((SegmentAllocator) arena, root);
        MemorySegment node = arena.allocate(NODE);
        Map<Integer, Kind> kinds = new HashMap<>();
        Map<Integer, String> fields = new HashMap<>();
        try {
            TsNode out = node(root, bytes, kinds);
            order.add(out);
            TsNode parent = out;
            boolean more = (boolean) FIRST_CHILD.invokeExact(cursor);
            while (more) {
                MemorySegment c = (MemorySegment) CURRENT_NODE.invokeExact((SegmentAllocator) (size, align) -> node, cursor);
                TsNode d = node(c, bytes, kinds);
                order.add(d);
                d.parent = parent;
                parent.children.add(d);
                int fieldId = Short.toUnsignedInt((short) FIELD_ID.invokeExact(cursor));
                String field = null;
                if (fieldId != 0) {
                    field = fields.get(fieldId);
                    if (field == null) {
                        field = cString((MemorySegment) FIELD_NAME.invokeExact(cursor));
                        fields.put(fieldId, field);
                    }
                }
                parent.fields.add(field);
                if ((boolean) FIRST_CHILD.invokeExact(cursor)) {
                    parent = d;
                    continue;
                }
                while (!(boolean) NEXT_SIBLING.invokeExact(cursor)) {
                    if (parent == out || !(boolean) PARENT.invokeExact(cursor)) {
                        more = false;
                        break;
                    }
                    parent = parent.parent;
                }
            }
            return out;
        } finally {
            CURSOR_DELETE.invokeExact(cursor);
        }
    }

    private static TsNode node(MemorySegment n, byte[] bytes, Map<Integer, Kind> kinds) throws Throwable {
        int symbol = Short.toUnsignedInt((short) SYMBOL.invokeExact(n));
        Kind kind = kinds.get(symbol);
        if (kind == null) {
            kind = new Kind(cString((MemorySegment) TYPE.invokeExact(n)), (boolean) IS_NAMED.invokeExact(n));
            kinds.put(symbol, kind);
        }
        int start = (int) START_BYTE.invokeExact(n);
        int end = (int) END_BYTE.invokeExact(n);
        boolean missing = start == end && (boolean) IS_MISSING.invokeExact(n); // a missing node is always empty
        return new TsNode(kind.type(), kind.named(), missing, false, start, end, 0, 0, bytes);
    }

    private static String cString(MemorySegment p) {
        return p.equals(MemorySegment.NULL) ? null : p.reinterpret(Long.MAX_VALUE).getString(0);
    }
}

package com.legacyaudit.ai.mcp;

import com.legacyaudit.engine.store.FactsQuery.FactsError;
import com.legacyaudit.engine.store.Summary;
import com.legacyaudit.model.AppInfo;
import com.legacyaudit.model.json.PyJson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code legacy-audit mcp}: a stdio MCP server with read-only tools over the latest run, for
 * Copilot ({@code .vscode/mcp.json}, written by {@code init}). It speaks JSON-RPC 2.0, one message
 * per line, and implements what Copilot needs: {@code initialize}, {@code ping},
 * {@code tools/list} and {@code tools/call}. The tool names and answers are the Python tool's.
 */
public final class McpServer {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, Function<Map<String, Object>, Object>> calls = new LinkedHashMap<>();

    public McpServer(Facts facts) {
        calls.put("query_facts", a -> facts.queryFacts(str(a, "kind"), map(a.get("filters")),
                a.containsKey("limit") ? integer(a.get("limit")) : 50, strings(a.get("fields"))));
        calls.put("get_component", a -> facts.getComponent(str(a, "id")));
        calls.put("get_sql", a -> facts.getSql(str(a, "id")));
        calls.put("read_evidence", a -> facts.readEvidence(str(a, "file"),
                a.get("line") == null ? null : integer(a.get("line")), a.containsKey("context") ? integer(a.get("context")) : 20));
        calls.put("coverage", a -> facts.coverage());
    }

    /** A missing argument, as Python's {@code KeyError} names it. */
    static final class MissingArgument extends RuntimeException {
        MissingArgument(String key) {
            super("'" + key + "'");
        }
    }

    private static String str(Map<String, Object> a, String key) {
        if (!a.containsKey(key)) {
            throw new MissingArgument(key);
        }
        Object v = a.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("filters must be an object");
    }

    private static List<String> strings(Object v) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof List<?> l)) {
            throw new IllegalArgumentException("fields must be a list of names");
        }
        return l.stream().map(String::valueOf).toList();
    }

    private static int integer(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(v).strip());
    }

    /** The tools and their input schemas ({@code tools/list}). */
    static List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("query_facts", "Rows of one fact kind from the latest scan, with exact-match filters "
                + "('~text' = contains). Returns total and rows (at most 'limit').",
                obj(props(
                        "kind", obj("type", "string", "enum", Summary.ENTITIES),
                        "filters", obj("type", "object", "additionalProperties", true),
                        "limit", obj("type", "integer", "minimum", 1, "maximum", 500, "default", 50),
                        "fields", obj("type", "array", "items", obj("type", "string"))), "kind")));
        out.add(tool("get_component", "One component (by id or name) with its services, screens, SQL, batch "
                + "jobs and integrations.",
                obj(props("id", obj("type", "string", "description", "Component id or name")), "id")));
        out.add(tool("get_sql", "One SQL statement by id: text, how it was built, PostgreSQL grade.",
                obj(props("id", obj("type", "string")), "id")));
        out.add(tool("read_evidence", "Source lines around an evidence location (file as in the facts). "
                + "Credentials are masked.",
                obj(props(
                        "file", obj("type", "string"),
                        "line", obj("type", "integer", "minimum", 1),
                        "context", obj("type", "integer", "minimum", 0, "maximum", 200, "default", 20)), "file")));
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("type", "object");
        empty.put("properties", new LinkedHashMap<>());
        out.add(tool("coverage", "Input coverage, analyzer coverage and unresolved references of the scan.", empty));
        return out;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        return obj("name", name, "description", description, "inputSchema", schema);
    }

    private static Map<String, Object> props(Object... kv) {
        return obj(kv);
    }

    /** {@code {"type": "object", "properties": ..., "required": [...]}}. */
    private static Map<String, Object> obj(Map<String, Object> properties, String... required) {
        return obj("type", "object", "properties", properties, "required", List.of(required));
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** One JSON-RPC message in; the response out (null for a notification). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> msg) {
        Object method = msg.get("method");
        Object id = msg.get("id");
        if (id == null) { // notification (e.g. notifications/initialized)
            return null;
        }
        Map<String, Object> params = msg.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        Object result;
        if ("initialize".equals(method)) {
            result = obj("protocolVersion", params.getOrDefault("protocolVersion", PROTOCOL_VERSION),
                    "capabilities", obj("tools", new LinkedHashMap<>()),
                    "serverInfo", obj("name", "legacy-audit", "version", AppInfo.version()),
                    "instructions", "Read-only facts from the latest legacy-audit scan. Numbers in "
                            + "these results are the scanner's; cite evidence as file:line.");
        } else if ("ping".equals(method)) {
            result = new LinkedHashMap<>();
        } else if ("tools/list".equals(method)) {
            result = obj("tools", tools());
        } else if ("tools/call".equals(method)) {
            Object args = params.get("arguments");
            result = call(String.valueOf(params.getOrDefault("name", "")),
                    args instanceof Map<?, ?> a && !a.isEmpty() ? (Map<String, Object>) a : new LinkedHashMap<>());
        } else {
            return obj("jsonrpc", "2.0", "id", id, "error", obj("code", -32601, "message", "Method not found: " + method));
        }
        return obj("jsonrpc", "2.0", "id", id, "result", result);
    }

    private Map<String, Object> call(String name, Map<String, Object> args) {
        Function<Map<String, Object>, Object> fn = calls.get(name);
        if (fn == null) {
            return text("Unknown tool '" + name + "'", true);
        }
        try {
            return text(PyJson.dumps(fn.apply(args), false, false), false);
        } catch (FactsError e) {
            return text("FactsError: " + e.getMessage(), true);
        } catch (MissingArgument e) {
            return text("KeyError: " + e.getMessage(), true);
        } catch (IllegalArgumentException e) {
            return text("ValueError: " + e.getMessage(), true);
        } catch (UncheckedIOException e) {
            return text("OSError: " + e.getCause().getMessage(), true);
        }
    }

    private static Map<String, Object> text(String text, boolean error) {
        return obj("content", List.of(obj("type", "text", "text", text)), "isError", error);
    }

    /** Answer each line of {@code in} on {@code out} until the input ends. */
    @SuppressWarnings("unchecked")
    public void serve(BufferedReader in, PrintStream out) {
        try {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> reply;
                try {
                    Object msg = JSON.readValue(line, Object.class);
                    reply = msg instanceof Map<?, ?> m ? handle((Map<String, Object>) m) : null;
                } catch (JacksonException e) {
                    reply = obj("jsonrpc", "2.0", "id", null, "error", obj("code", -32700, "message", "Parse error"));
                }
                if (reply != null) {
                    out.print(PyJson.dumps(reply, false, false) + "\n");
                    out.flush();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

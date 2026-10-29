package com.legacyaudit.engine.analyzers;

import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads JSON text as Python's {@code json.loads} does (object keys in order, NaN allowed). */
public final class PyJsonRead {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();

    private PyJsonRead() {}

    /** The parsed value; throws {@link IllegalArgumentException} with the reason. */
    public static JsonNode parse(String text) {
        try {
            JsonNode n = MAPPER.readTree(text);
            if (n == null || n.isMissingNode()) {
                throw new IllegalArgumentException("Expecting value: line 1 column 1 (char 0)");
            }
            return n;
        } catch (RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) {
                throw iae;
            }
            String msg = e.getMessage() == null ? e.toString() : e.getMessage().split("\n")[0];
            throw new IllegalArgumentException(msg, e);
        }
    }

    /** Python's {@code str(value)} for a JSON scalar (strings as they are). */
    public static String str(JsonNode n) {
        if (n == null || n.isNull()) {
            return "None";
        }
        if (n.isString()) {
            return n.asString();
        }
        if (n.isBoolean()) {
            return n.asBoolean() ? "True" : "False";
        }
        if (n.isIntegralNumber()) {
            return n.bigIntegerValue().toString();
        }
        if (n.isNumber()) {
            return com.legacyaudit.model.json.PyJson.repr(n.doubleValue());
        }
        return n.toString();
    }
}

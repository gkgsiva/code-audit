package com.legacyaudit.engine.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.legacyaudit.engine.rules.Packs;
import com.legacyaudit.engine.rules.PyRegex;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/** Hashes and regular expressions give what the Python tool gave (values taken from office-main). */
class HashesTest {

    private static String blake(String text) {
        return HexFormat.of().formatHex(Blake2b.digest(text.getBytes(StandardCharsets.UTF_8), 8));
    }

    @Test
    void blake2bMatchesHashlib() {
        assertEquals("e4a6a0577479b2b4", blake(""));
        assertEquals("d8bb14d833d59559", blake("abc"));
        assertEquals("901ce374e8e12402", blake("x".repeat(128)));
        assertEquals("7c617a149f5301e6", blake("y".repeat(129)));
        assertEquals("92920681f95ec7f0", blake("z".repeat(300)));
        assertEquals("1ab64ffddd1d71ba", blake("a\u001fb\u001fé"));
    }

    @Test
    void rulesKeyMatchesPython() {
        assertEquals(17, Packs.textRules(List.of("risk", "postgresql", "dependency"), null).size());
        assertEquals("eed7e1683bb4", Packs.rulesKey(Packs.textRules(List.of("risk", "postgresql", "dependency"), null)));
    }

    @Test
    void pythonNamedGroupsAreTranslated() {
        PyRegex rx = PyRegex.compile("(?P<secret_1>a+)(?P<x>b)(?P=x)\\Z");
        Matcher m = rx.pattern.matcher("aabb");
        assertEquals(true, m.find());
        assertEquals("secret_1", rx.pyName("secret1"));
        assertEquals("aa", m.group("secret1"));
    }
}

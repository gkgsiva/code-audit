package com.legacyaudit.ai.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.legacyaudit.ai.enrich.ValidateParityTest;
import com.legacyaudit.model.AppInfo;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP server answers {@code mcp-parity/requests.jsonl} line for line as the Python server did
 * ({@code mcp-parity/expected.json}) on two fixture runs: every tool, errors, notifications and a
 * line that is not JSON. Only the version in {@code serverInfo} differs.
 */
class McpParityTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "mcp-parity");

    @ParameterizedTest
    @ValueSource(strings = {"enriched/struts-oracle-app", "ref/c-proc-batch"})
    void answersAsThePythonServer(String set) throws Exception {
        Path runDir = ValidateParityTest.unpack(set, ValidateParityTest.fresh(Path.of("target", "mcp-parity-runs", set.replace('/', '-'))));
        McpServer server = new McpServer(new Facts(() -> runDir));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        String requests = Files.readString(FIXTURES.resolve("requests.jsonl"));
        server.serve(new BufferedReader(new StringReader(requests)), new PrintStream(buf, true, StandardCharsets.UTF_8));
        Map<?, ?> expected = JsonMapper.builder().build().readValue(Files.readString(FIXTURES.resolve("expected.json")), Map.class);
        String want = ((String) expected.get(set)).replace("\"version\": \"0.1.0\"", "\"version\": \"" + AppInfo.version() + "\"");
        assertEquals(want, buf.toString(StandardCharsets.UTF_8));
    }
}

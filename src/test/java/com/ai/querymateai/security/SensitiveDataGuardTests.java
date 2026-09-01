package com.ai.querymateai.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.mock.env.MockEnvironment;

import com.ai.querymateai.config.AppProperties;
import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.json.JsonMapper;

class SensitiveDataGuardTests {

    private static final String REAL_NAME = "Jane Doe";

    private static final String REAL_EMAIL = "jane.doe@example.com";

    private static final String REAL_PHONE = "415-555-0101";

    private static final String TOOL_RESULT = """
            {"value":[
              {"CustomerId":1,"FullName":"Jane Doe","Email":"jane.doe@example.com","Phone":"415-555-0101","City":"Austin"},
              {"CustomerId":2,"FullName":"Sam Patel","Email":"sam.patel@example.com","Phone":"212-555-0199","City":"Austin"}
            ]}
            """;

    private final AppProperties properties = properties();

    private final SensitiveDataGuard guard =
            new SensitiveDataGuard(this.properties, JsonMapper.builder().build(), traceLogger(this.properties));

    private static AppProperties properties() {
        return new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, java.time.Duration.ofSeconds(120),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(20),
                new AppProperties.Security("unit-test-secret"),
                new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(false, false, 20_000)),
                List.of(new AppProperties.SensitiveField("Customer", "FullName", "CU"),
                        new AppProperties.SensitiveField("Customer", "Email", "EM"),
                        new AppProperties.SensitiveField("Customer", "Phone", "PH")));
    }

    private static LocalAiTraceLogger traceLogger(AppProperties properties) {
        return new LocalAiTraceLogger(properties, new MockEnvironment());
    }

    private static ToolCallback stubCallback(String payload) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_records")
                        .description("read records").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return payload;
            }
        };
    }

    @Test
    void sensitiveValuesNeverReachTheModelPayload() {
        SensitiveRequestContext session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload)
                .as("raw sensitive values must never appear in what is sent to the LLM")
                .doesNotContain(REAL_NAME)
                .doesNotContain(REAL_EMAIL)
                .doesNotContain(REAL_PHONE)
                .doesNotContain("Sam Patel")
                .doesNotContain("sam.patel@example.com", "212-555-0199");
        assertThat(modelBoundPayload).containsPattern("CU_[0-9a-f]{6}")
                .containsPattern("EM_[0-9a-f]{6}").containsPattern("PH_[0-9a-f]{6}");
        // Non-sensitive columns must survive, or aggregation questions break.
        assertThat(modelBoundPayload).contains("Austin").contains("\"CustomerId\":1");
    }

    @Test
    void tokensAreDeterministicAcrossCalls() {
        SensitiveRequestContext session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0];

        String first = guarded.call("{\"entity\":\"Customer\"}");
        String second = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(first).isEqualTo(second);
        assertThat(session.tokenCount()).isEqualTo(6);
    }

    @Test
    void finalAnswerRemainsPseudonymizedForTheUser() {
        SensitiveRequestContext session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0];
        String payload = guarded.call("{\"entity\":\"Customer\"}");

        String token = payload.replaceAll("(?s).*?(CU_[0-9a-f]{6}).*", "$1");
        String protectedOutput = session.protectOutput("The top customer is " + token + ".");

        assertThat(protectedOutput).isEqualTo("The top customer is " + token + ".");
    }

    @Test
    void unparseableToolResultIsWithheldRatherThanForwarded() {
        SensitiveRequestContext session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback("<html>Jane Doe</html>") })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void sensitiveValuesAreTokenizedInsideTheMcpEnvelope() {
        // The real DAB/MCP shape: rows arrive as JSON encoded *inside* content[0].text.
        String envelope = """
                {"content":[{"type":"text","text":"{\\"entity\\":\\"Customer\\",\\"result\\":{\\"value\\":[\
                {\\"CustomerId\\":1,\\"FullName\\":\\"Jane Doe\\",\\"Email\\":\\"jane.doe@example.com\\"}]}}"}],\
                "isError":false}
                """;
        SensitiveRequestContext session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload)
                .as("values nested inside the MCP text envelope must still be tokenized")
                .doesNotContain(REAL_NAME)
                .doesNotContain(REAL_EMAIL);
        assertThat(modelBoundPayload).containsPattern("CU_[0-9a-f]{6}");
        assertThat(session.tokenCount()).isEqualTo(2);
    }

    @Test
    void inboundPiiIsTokenizedBeforeItCanReachMemoryOrTheModel() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedInput = session.protectInput(
                "Find customer named Jane Doe with jane.doe@example.com or phone 415-555-0101 on 2025-01-08.");

        assertThat(protectedInput)
                .doesNotContain("Jane Doe")
                .doesNotContain("jane.doe@example.com")
                .doesNotContain("415-555-0101")
                .contains("2025-01-08")
                .containsPattern("CU_[0-9a-f]{6}")
                .containsPattern("EM_[0-9a-f]{6}")
                .containsPattern("PH_[0-9a-f]{6}");
    }

    @Test
    void bareCustomerNameIsTokenizedBeforeItCanReachModel() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedInput = session.protectInput("give me the details of customer Ethan Thomas");

        assertThat(protectedInput)
                .doesNotContain("Ethan Thomas")
                .containsPattern("CU_[0-9a-f]{6}");
    }

    @Test
    void knownSensitiveValuesEchoedByModelAreRetokenizedForFinalOutput() {
        SensitiveRequestContext session = this.guard.newSession();
        String protectedInput = session.protectInput("give me the details of customer Ethan Thomas");
        String token = protectedInput.replaceAll(".*(CU_[0-9a-f]{6}).*", "$1");

        String protectedOutput = session.protectOutput("Customer Ethan Thomas is a Gold-tier customer.");

        assertThat(protectedOutput).isEqualTo("Customer " + token + " is a Gold-tier customer.");
    }

    @Test
    void protectedToolArgumentsAreRestoredOnlyForDab() {
        SensitiveRequestContext session = this.guard.newSession();
        String protectedInput = session.protectInput("FullName eq 'Jane Doe'");
        String token = protectedInput.replaceAll(".*'(CU_[0-9a-f]{6})'.*", "$1");
        AtomicReference<String> dabInput = new AtomicReference<>();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_records").description("read records")
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                dabInput.set(toolInput);
                return "{\"value\":[]}";
            }
        };

        session.wrap(new ToolCallback[] { delegate })[0]
                .call("{\"entity\":\"Customer\",\"filter\":\"FullName eq '" + token + "'\"}");

        assertThat(dabInput.get()).contains("Jane Doe").doesNotContain(token);
    }

    @Test
    void toolIntentLogsNeverIncludeFilterValues() {
        String rendered = ToolCallIntent.render(JsonMapper.builder().build(),
                "{\"entity\":\"Customer\",\"filter\":\"FullName eq 'Jane Doe'\"}");

        assertThat(rendered).contains("Customer", "<redacted>").doesNotContain("Jane Doe");
    }

    @Test
    void secureBoundaryLogsCanCountResolvedTokensWithoutValues() {
        int resolved = ToolCallIntent.resolvedTokenCount(
                "{\"filter\":\"Email eq 'EM_ab12cd' and Phone eq 'PH_ef3456'\"}",
                "{\"filter\":\"Email eq 'x@example.test' and Phone eq '555-0101'\"}");

        assertThat(resolved).isEqualTo(2);
    }

    @Test
    void finalOutputRedactsProviderGeneratedContactDetails() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedOutput = session.protectOutput("Contact invented@example.com or 415-555-0101.");

        assertThat(protectedOutput).isEqualTo("Contact [REDACTED_EMAIL] or [REDACTED_PHONE].");
    }

    @Test
    void unparseableTextInsideMcpEnvelopeIsWithheld() {
        String envelope = "{\"content\":[{\"type\":\"text\",\"text\":\"{Jane Doe\"}],\"isError\":false}";
        SensitiveRequestContext session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0].call("{}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void unknownTokenInModelOutputIsLeftAlone() {
        SensitiveRequestContext session = this.guard.newSession();
        session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0].call("{}");

        assertThat(session.protectOutput("Unknown CU_ffffff here")).isEqualTo("Unknown CU_ffffff here");
    }
}

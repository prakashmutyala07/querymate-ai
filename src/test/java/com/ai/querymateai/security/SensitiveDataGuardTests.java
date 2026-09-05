package com.ai.querymateai.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.mock.env.MockEnvironment;

import com.ai.querymateai.chat.ChatResponse;
import com.ai.querymateai.config.AppProperties;
import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.json.JsonMapper;

class SensitiveDataGuardTests {

    private static final String REAL_NAME = "John Smith";

    private static final String REAL_EMAIL = "john.smith@example.com";

    private static final String REAL_PHONE = "415-555-0101";

    private static final String PROTECTED_PATTERN =
            "\\b(?:CustomerName|Email|Phone|SensitiveValue)Protected#\\d+\\b";

    private static final String TOOL_RESULT = """
            {"value":[
              {"CustomerId":1,"FullName":"John Smith","Email":"john.smith@example.com","Phone":"415-555-0101","City":"Austin"},
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
                new AppProperties.Security(),
                new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(false, false, 20_000)),
                List.of(new AppProperties.SensitiveField("Customer", "FullName", null),
                        new AppProperties.SensitiveField("Customer", "Email", null),
                        new AppProperties.SensitiveField("Customer", "Phone", null)));
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
    void inputEmailPhoneAndFullNameAreProtectedBeforeModelCall() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedInput = session.protectInput(
                "Find customer John Smith with john.smith@example.com or phone 415-555-0101.");

        assertThat(protectedInput)
                .doesNotContain(REAL_NAME)
                .doesNotContain(REAL_EMAIL)
                .doesNotContain(REAL_PHONE)
                .containsPattern(PROTECTED_PATTERN);
        assertThat(session.restoreProtectedValues(protectedInput))
                .contains(REAL_NAME, REAL_EMAIL, REAL_PHONE);
    }

    @Test
    void adjacentProtectedNamePartsAreCollapsedIntoOneSearchableFullName() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedInput = session.protectInput("Find customer Ethan Thomas");

        assertThat(protectedInput)
                .doesNotContain("Ethan")
                .doesNotContain("Thomas")
                .containsPattern(PROTECTED_PATTERN);
        assertThat(session.protectedValueCount(protectedInput)).isEqualTo(1);
        assertThat(session.restoreProtectedValues(protectedInput)).isEqualTo("Find customer Ethan Thomas");
    }

    @Test
    void multipleAndRepeatedPiiValuesAreProtectedWithShortTokens() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedInput = session.protectInput(
                "Email john.smith@example.com, backup john.smith@example.com, phone 415-555-0101.");

        assertThat(protectedInput).doesNotContain(REAL_EMAIL, REAL_PHONE);
        assertThat(session.protectedValueCount(protectedInput)).isGreaterThanOrEqualTo(3);
        assertThat(session.restoreProtectedValues(protectedInput)).contains(REAL_EMAIL, REAL_PHONE);
    }

    @Test
    void sameSensitiveValueReusesSameTokenAcrossOneRequest() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedInput = session.protectInput("Find customer Ethan Thomas");
        String protectedResult = session.wrap(new ToolCallback[] {
                stubCallback("{\"value\":[{\"CustomerId\":11,\"FullName\":\"Ethan Thomas\"}]}")
        })[0].call("{\"entity\":\"Customer\"}");

        assertThat(protectedInput).contains("CustomerNameProtected#1");
        assertThat(protectedResult).contains("CustomerNameProtected#1")
                .doesNotContain("CustomerNameProtected#2")
                .doesNotContain("Ethan Thomas");
    }

    @Test
    void alreadyProtectedTokensAreNotProtectedAgain() {
        SensitiveRequestContext session = this.guard.newSession();
        String protectedInput = session.protectInput("Find customer Ethan Thomas");

        String protectedOutput = session.protectOutput("Found customer CustomerNameProtected#1.");

        assertThat(protectedInput).contains("CustomerNameProtected#1");
        assertThat(protectedOutput).isEqualTo("Found customer CustomerNameProtected#1.");
    }

    @Test
    void uiResponseShowsNamesAndMasksContactDetails() {
        SensitiveRequestContext session = this.guard.newSession();
        String protectedName = session.protectStructuredCell("FullName", "Ethan Thomas");
        String protectedEmail = session.protectStructuredCell("Email", "ethan.thomas@example.com");
        String protectedPhone = session.protectStructuredCell("Phone", "415-555-0101");
        ChatResponse protectedResponse = new ChatResponse("conversation", "model", false,
                ChatResponse.Status.ANSWER, "Customer CustomerNameProtected#999 was found.",
                List.of("CustomerId", "CustomerNameProtected", "EmailProtected", "PhoneProtected"),
                List.of(List.of("11", protectedName, protectedEmail, protectedPhone)),
                true, false, "Contact " + protectedEmail + " or " + protectedPhone, "");

        ChatResponse uiResponse = session.toUiResponse(protectedResponse);

        assertThat(uiResponse.message()).isEqualTo("Customer Ethan Thomas was found.");
        assertThat(uiResponse.columns()).containsExactly("CustomerId", "FullName", "Email", "Phone");
        assertThat(uiResponse.rows().getFirst()).containsExactly("11", "Ethan Thomas",
                "eth***@example.com", "415***01");
        assertThat(uiResponse.dataNotes()).isEqualTo("Contact eth***@example.com or 415***01");
    }

    @Test
    void normalQueryWithoutPiiPassesThrough() {
        SensitiveRequestContext session = this.guard.newSession();

        assertThat(session.protectInput("show total orders by city")).isEqualTo("show total orders by city");
    }

    @Test
    void protectedToolArgumentsAreDecryptedOnlyForDab() {
        SensitiveRequestContext session = this.guard.newSession();
        String protectedFilter = session.protectInput("FullName eq 'John Smith'");
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
                .call("{\"entity\":\"Customer\",\"filter\":\"" + protectedFilter + "\"}");

        assertThat(dabInput.get()).contains("John Smith").doesNotContain("Protected#");
    }

    @Test
    void customerFullNameDetailsRequestRestoresOnlyTheActualNameForExactFilter() {
        SensitiveRequestContext session = this.guard.newSession();
        String protectedMessage = session.protectInput("customer details of Mason Taylor");
        java.util.ArrayList<String> dabInputs = new java.util.ArrayList<>();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_records").description("read records")
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                dabInputs.add(toolInput);
                return """
                        [{"text":"{\\"entity\\":\\"Customer\\",\\"result\\":{\\"value\\":[\
                        {\\"CustomerId\\":11,\\"FullName\\":\\"Mason Taylor\\",\\"City\\":\\"Portland\\"}]},\
                        \\"message\\":\\"Successfully read records for entity 'Customer'\\",\\"status\\":\\"success\\"}"}]
                        """;
            }
        };

        String result = session.wrap(new ToolCallback[] { delegate })[0]
                .call("{\"entity\":\"Customer\",\"select\":\"CustomerId,FullName,City\","
                        + "\"filter\":\"FullName eq 'CustomerNameProtected#1'\",\"first\":1}");

        assertThat(protectedMessage).contains("CustomerNameProtected#1");
        assertThat(dabInputs).hasSize(1);
        assertThat(dabInputs.getFirst()).contains("FullName eq 'Mason Taylor'");
        assertThat(result).contains("CustomerNameProtected#1", "Portland").doesNotContain("Mason Taylor");
    }

    @Test
    void decryptionFailureIsWithheldBeforeDab() {
        SensitiveRequestContext session = this.guard.newSession();
        AtomicBoolean called = new AtomicBoolean(false);
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_records").description("read records")
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                called.set(true);
                return "{\"value\":[]}";
            }
        };

        String result = session.wrap(new ToolCallback[] { delegate })[0]
                .call("{\"filter\":\"Email eq 'EmailProtected#999'\"}");

        assertThat(called).isFalse();
        assertThat(result).contains("invalid protected value").doesNotContain("john.smith");
    }

    @Test
    void rawSensitiveValuesNeverReachTheModelPayload() {
        SensitiveRequestContext session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback(TOOL_RESULT) })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload)
                .doesNotContain(REAL_NAME)
                .doesNotContain(REAL_EMAIL)
                .doesNotContain(REAL_PHONE)
                .doesNotContain("Sam Patel", "sam.patel@example.com", "212-555-0199")
                .containsPattern(PROTECTED_PATTERN)
                .contains("Austin")
                .contains("\"CustomerId\":1");
        assertThat(session.restoreProtectedValues(modelBoundPayload))
                .contains(REAL_NAME, REAL_EMAIL, REAL_PHONE, "Sam Patel");
    }

    @Test
    void sensitiveValuesAreProtectedInsideTheMcpEnvelope() {
        String envelope = """
                {"content":[{"type":"text","text":"{\\"entity\\":\\"Customer\\",\\"result\\":{\\"value\\":[\
                {\\"CustomerId\\":1,\\"FullName\\":\\"John Smith\\",\\"Email\\":\\"john.smith@example.com\\",\
                \\"Phone\\":\\"415-555-0101\\",\\"City\\":\\"Austin\\"}]}}"}],"isError":false}
                """;
        SensitiveRequestContext session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0]
                .call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME, REAL_EMAIL, REAL_PHONE)
                .containsPattern(PROTECTED_PATTERN)
                .contains("Austin");
    }

    @Test
    void unparseableToolResultIsWithheldRatherThanForwarded() {
        SensitiveRequestContext session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback("<html>John Smith</html>") })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void unparseableTextInsideMcpEnvelopeIsWithheld() {
        String envelope = "{\"content\":[{\"type\":\"text\",\"text\":\"{John Smith\"}],\"isError\":false}";
        SensitiveRequestContext session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0].call("{}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void finalOutputProtectionCatchesProviderEmittedPii() {
        SensitiveRequestContext session = this.guard.newSession();

        String protectedOutput = session.protectOutput(
                "Contact John Smith at john.smith@example.com or 415-555-0101.");

        assertThat(protectedOutput)
                .doesNotContain(REAL_NAME, REAL_EMAIL, REAL_PHONE)
                .containsPattern(PROTECTED_PATTERN);
        assertThat(session.restoreProtectedValues(protectedOutput)).contains(REAL_NAME, REAL_EMAIL, REAL_PHONE);
    }

    @Test
    void toolIntentLogsNeverIncludeFilterValues() {
        String rendered = ToolCallIntent.render(JsonMapper.builder().build(),
                "{\"entity\":\"Customer\",\"filter\":\"FullName eq 'John Smith'\"}");

        assertThat(rendered).contains("Customer", "<redacted>").doesNotContain("John Smith");
    }

    @Test
    void secureBoundaryLogsCanCountResolvedProtectedValuesWithoutValues() {
        int resolved = ToolCallIntent.resolvedTokenCount(
                "{\"filter\":\"Email eq 'EmailProtected#1' and Phone eq 'PhoneProtected#1'\"}",
                "{\"filter\":\"Email eq 'x@example.test' and Phone eq '555-0101'\"}");

        assertThat(resolved).isEqualTo(2);
    }
}

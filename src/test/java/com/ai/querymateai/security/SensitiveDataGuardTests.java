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
import com.ai.querymateai.mcp.ToolCallIntent;
import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.json.JsonMapper;

class SensitiveDataGuardTests {

    private static final String REAL_NAME = "John Smith";

    private static final String REAL_EMAIL = "john.smith@example.com";

    private static final String REAL_PHONE = "415-555-0101";

    private static final String PROTECTED_PATTERN =
            "\\[PII:(?:NAME|EMAIL|PHONE|VALUE):[A-Za-z0-9_-]{16}:\\d+]";

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
                new AppProperties.Security(new AppProperties.DataPolicy(
                        List.of("CustomerId", "OrderId", "count", "City", "StateProvince", "Country",
                                "LoyaltyTier", "Status"),
                        null, null, null, null), null, null),
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
        return stubCallback(payload, "read_records");
    }

    private static ToolCallback stubCallback(String payload, String toolName) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(toolName)
                        .description("read records").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return payload;
            }
        };
    }

    private static String firstProtectedToken(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(PROTECTED_PATTERN).matcher(text);
        assertThat(matcher.find()).as("expected a protected token in %s", text).isTrue();
        return matcher.group();
    }

    private static java.util.List<String> protectedTokens(String text) {
        java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(PROTECTED_PATTERN).matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    @Test
    void inputEmailPhoneAndFullNameAreProtectedBeforeModelCall() {
        PrivacySession session = this.guard.newSession();

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
        PrivacySession session = this.guard.newSession();

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
        PrivacySession session = this.guard.newSession();

        String protectedInput = session.protectInput(
                "Email john.smith@example.com, backup john.smith@example.com, phone 415-555-0101.");

        assertThat(protectedInput).doesNotContain(REAL_EMAIL, REAL_PHONE);
        assertThat(session.protectedValueCount(protectedInput)).isGreaterThanOrEqualTo(3);
        assertThat(session.restoreProtectedValues(protectedInput)).contains(REAL_EMAIL, REAL_PHONE);
    }

    @Test
    void sameSensitiveValueReusesSameTokenAcrossOneRequest() {
        PrivacySession session = this.guard.newSession();

        String protectedInput = session.protectInput("Find customer Ethan Thomas");
        String protectedResult = session.wrap(new ToolCallback[] {
                stubCallback("{\"value\":[{\"CustomerId\":11,\"FullName\":\"Ethan Thomas\"}]}")
        })[0].call("{\"entity\":\"Customer\"}");
        String token = firstProtectedToken(protectedInput);

        assertThat(protectedInput).contains(token);
        assertThat(protectedResult).contains(token)
                .doesNotContain("Ethan Thomas");
    }

    @Test
    void alreadyProtectedTokensAreNotProtectedAgain() {
        PrivacySession session = this.guard.newSession();
        String protectedInput = session.protectInput("Find customer Ethan Thomas");
        String token = firstProtectedToken(protectedInput);

        String protectedOutput = session.protectOutput("Found customer " + token + ".");

        assertThat(protectedInput).contains(token);
        assertThat(protectedOutput).isEqualTo("Found customer " + token + ".");
    }

    @Test
    void uiResponseShowsNamesAndMasksContactDetails() {
        PrivacySession session = this.guard.newSession();
        String protectedName = session.protectStructuredCell("FullName", "Ethan Thomas");
        String protectedEmail = session.protectStructuredCell("Email", "ethan.thomas@example.com");
        String protectedPhone = session.protectStructuredCell("Phone", "415-555-0101");
        ChatResponse protectedResponse = new ChatResponse("conversation", "model", false,
                ChatResponse.Status.ANSWER, "Customer " + protectedName + " was found.",
                List.of("CustomerId", "FullName", "Email", "Phone"),
                List.of(List.of("11", protectedName, protectedEmail, protectedPhone)),
                true, false, ChatResponse.UNKNOWN_TOTAL, "Contact " + protectedEmail + " or " + protectedPhone, "");

        ChatResponse uiResponse = session.toUiResponse(protectedResponse);

        assertThat(uiResponse.message()).isEqualTo("Customer E*** T*** was found.");
        assertThat(uiResponse.columns()).containsExactly("CustomerId", "FullName", "Email", "Phone");
        assertThat(uiResponse.rows().getFirst()).containsExactly("11", "E*** T***",
                "eth***@example.com", "415***01");
        assertThat(uiResponse.dataNotes()).isEqualTo("Contact eth***@example.com or 415***01");
    }

    @Test
    void normalQueryWithoutPiiPassesThrough() {
        PrivacySession session = this.guard.newSession();

        assertThat(session.protectInput("show total orders by city")).isEqualTo("show total orders by city");
    }

    @Test
    void protectedToolArgumentsAreDecryptedOnlyForDab() {
        PrivacySession session = this.guard.newSession();
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
        PrivacySession session = this.guard.newSession();
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
        String token = firstProtectedToken(protectedMessage);

        String result = session.wrap(new ToolCallback[] { delegate })[0]
                .call("{\"entity\":\"Customer\",\"select\":\"CustomerId,FullName,City\","
                        + "\"filter\":\"FullName eq '" + token + "'\",\"first\":1}");

        assertThat(protectedMessage).contains(token);
        assertThat(dabInputs).hasSize(1);
        assertThat(dabInputs.getFirst()).contains("FullName eq 'Mason Taylor'");
        assertThat(result).contains(token, "Portland").doesNotContain("Mason Taylor");
    }

    @Test
    void decryptionFailureIsWithheldBeforeDab() {
        PrivacySession session = this.guard.newSession();
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
                .call("{\"filter\":\"Email eq '[PII:EMAIL:ABCDEFGHIJKLMNOP:999]'\"}");

        assertThat(called).isFalse();
        assertThat(result).contains("invalid protected value").doesNotContain("john.smith");
    }

    @Test
    void rawSensitiveValuesNeverReachTheModelPayload() {
        PrivacySession session = this.guard.newSession();
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
    }

    @Test
    void sensitiveValuesAreProtectedInsideTheMcpEnvelope() {
        String envelope = """
                {"content":[{"type":"text","text":"{\\"entity\\":\\"Customer\\",\\"result\\":{\\"value\\":[\
                {\\"CustomerId\\":1,\\"FullName\\":\\"John Smith\\",\\"Email\\":\\"john.smith@example.com\\",\
                \\"Phone\\":\\"415-555-0101\\",\\"City\\":\\"Austin\\"}]}}"}],"isError":false}
                """;
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0]
                .call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME, REAL_EMAIL, REAL_PHONE)
                .containsPattern(PROTECTED_PATTERN)
                .contains("Austin");
    }

    @Test
    void modelProseIsNotMangledByThePersonNameHeuristic() {
        // Regression: the name heuristic used to fire on ordinary words following "customer",
        // rewriting the model's own sentence into tokens. Output protection must leave prose alone.
        PrivacySession session = this.guard.newSession();
        String protectedInput = session.protectInput("lookup customer record of " + REAL_NAME);
        String token = firstProtectedToken(protectedInput);

        String answer = session.protectOutput(
                "No customer record was found matching the name " + token + " "
                        + "in the currently approved data access configuration.");
        String dataNotes = session.protectOutput(
                "The search was performed on the Customer FullName field as the filter.");

        assertThat(answer).isEqualTo("No customer record was found matching the name "
                + token + " in the currently approved data access configuration.");
        assertThat(dataNotes).isEqualTo("The search was performed on the Customer FullName field as the filter.");
    }

    @Test
    void outputStillProtectsContactDetailsTheModelEmits() {
        PrivacySession session = this.guard.newSession();

        String answer = session.protectOutput("Reach them at " + REAL_EMAIL + " or " + REAL_PHONE + ".");

        assertThat(answer).doesNotContain(REAL_EMAIL).doesNotContain(REAL_PHONE)
                .containsPattern(PROTECTED_PATTERN);
    }

    @Test
    void outputProtectionDoesNotVaultOrdinaryWordsThatWouldBlockEgress() {
        // Junk vault entries are not cosmetic: the egress firewall blocks any outbound payload
        // containing a vaulted value, and "FullName" appears throughout the system prompt.
        PrivacySession session = this.guard.newSession();

        session.protectOutput("The search was performed on the Customer FullName field as the filter.");

        assertThat(session.vaultedValuesPresentIn("... use the exact FullName field ...")).isEmpty();
    }

    @Test
    void genericProtectedValuesRenderDistinctlyInTheUi() {
        // Deny-by-default protects many columns now. A fixed placeholder would make every
        // protected cell in a row look identical and the table unreadable.
        String toolResult = "{\"value\":[{\"TaxId\":\"AB-123-XYZ\",\"Notes\":\"Prefers morning delivery\"}]}";
        PrivacySession session = this.guard.newSession();
        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(toolResult) })[0]
                .call("{\"entity\":\"Customer\"}");
        java.util.List<String> tokens = protectedTokens(modelBoundPayload);

        ChatResponse response = new ChatResponse("c", "m", false, ChatResponse.Status.ANSWER, "ok",
                List.of("TaxId", "Notes"),
                List.of(List.of(tokens.get(0), tokens.get(1))),
                true, false, ChatResponse.UNKNOWN_TOTAL, "", "");
        ChatResponse ui = session.toUiResponse(response);

        assertThat(ui.rows().getFirst()).containsExactly("AB***", "Pr***");
    }

    @Test
    void columnMissingFromBothTheSensitiveListAndTheSafeListIsProtected() {
        // TaxId is configured nowhere. Deny-by-default means it is protected anyway, which is
        // the whole point: a column added by a later migration must fail closed.
        String toolResult = "{\"value\":[{\"CustomerId\":1,\"TaxId\":\"AB-123-XYZ\",\"City\":\"Austin\"}]}";
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(toolResult) })[0]
                .call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).doesNotContain("AB-123-XYZ")
                .containsPattern(PROTECTED_PATTERN)
                .contains("Austin")
                .contains("\"CustomerId\":1");
    }

    @Test
    void unclassifiedNumericColumnIsProtectedUnlessExplicitlySafe() {
        String toolResult = "{\"value\":[{\"CustomerId\":1,\"NumericTaxId\":123456789}]}";
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(toolResult) })[0]
                .call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).contains("\"CustomerId\":1")
                .containsPattern(PROTECTED_PATTERN)
                .doesNotContain("123456789");
    }

    @Test
    void aggregateResultArrayUsesDenyByDefaultProtection() {
        String toolResult = "{\"result\":[{\"ConfidentialGroup\":\"North-Secret\",\"count\":2}]}";
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(
                new ToolCallback[] { stubCallback(toolResult, "aggregate_records") })[0]
                .call("{\"entity\":\"Customer\",\"function\":\"count\",\"field\":\"*\","
                        + "\"groupby\":[\"ConfidentialGroup\"]}");

        assertThat(modelBoundPayload).contains("\"count\":2")
                .containsPattern(PROTECTED_PATTERN)
                .doesNotContain("North-Secret");
    }

    @Test
    void unrecognisedRowDataShapeIsWithheld() {
        String toolResult = "{\"rows\":[{\"UnknownSecret\":\"raw-secret\"}]}";
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(toolResult) })[0]
                .call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).contains("could not be verified", "withheld")
                .doesNotContain("raw-secret");
    }

    @Test
    void envelopeFieldsOutsideTheRowArrayAreLeftIntact() {
        String toolResult = "{\"entity\":\"Customer\",\"message\":\"Query completed\","
                + "\"status\":\"success\",\"result\":{\"value\":[{\"FullName\":\"John Smith\"}]}}";
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(toolResult) })[0]
                .call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).contains("Query completed", "success", "Customer")
                .doesNotContain(REAL_NAME)
                .containsPattern(PROTECTED_PATTERN);
    }

    @Test
    void schemaDiscoveryResultReachesTheModelIntact() {
        // describe_entities returns field metadata, not rows. Protecting it would leave the
        // model unable to learn which fields exist.
        String schema = "{\"value\":[{\"name\":\"FullName\",\"type\":\"string\","
                + "\"description\":\"Customer full name\"}]}";
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(
                new ToolCallback[] { stubCallback(schema, "describe_entities") })[0]
                .call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).contains("FullName", "string", "Customer full name")
                .doesNotContain("SensitiveValueProtected");
    }

    @Test
    void unparseableToolResultIsWithheldRatherThanForwarded() {
        PrivacySession session = this.guard.newSession();
        ToolCallback guarded = session.wrap(new ToolCallback[] { stubCallback("<html>John Smith</html>") })[0];

        String modelBoundPayload = guarded.call("{\"entity\":\"Customer\"}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void unparseableTextInsideMcpEnvelopeIsWithheld() {
        String envelope = "{\"content\":[{\"type\":\"text\",\"text\":\"{John Smith\"}],\"isError\":false}";
        PrivacySession session = this.guard.newSession();

        String modelBoundPayload = session.wrap(new ToolCallback[] { stubCallback(envelope) })[0].call("{}");

        assertThat(modelBoundPayload).doesNotContain(REAL_NAME).contains("withheld");
    }

    @Test
    void finalOutputProtectionCatchesProviderEmittedPii() {
        PrivacySession session = this.guard.newSession();

        String protectedOutput = session.protectOutput(
                "Contact John Smith at john.smith@example.com or 415-555-0101.");

        assertThat(protectedOutput)
                .doesNotContain(REAL_NAME, REAL_EMAIL, REAL_PHONE)
                .containsPattern(PROTECTED_PATTERN);
        assertThat(protectedOutput).doesNotContain(REAL_NAME, REAL_EMAIL, REAL_PHONE);
    }

    @Test
    void toolIntentLogsNeverIncludeFilterValues() {
        String rendered = ToolCallIntent.parse(JsonMapper.builder().build(), "read_records",
                "{\"entity\":\"Customer\",\"filter\":\"FullName eq 'John Smith'\"}").renderSafe();

        assertThat(rendered).contains("Customer", "<redacted>").doesNotContain("John Smith");
    }

    @Test
    void secureBoundaryLogsCanCountResolvedProtectedValuesWithoutValues() {
        PrivacySession session = this.guard.newSession();
        String protectedInput = session.protectInput("Find john.smith@example.com or 415-555-0101");
        int resolved = session.resolvedProtectedValueCount(protectedInput,
                session.restoreProtectedValues(protectedInput));

        assertThat(resolved).isEqualTo(2);
    }

    private static final String ORDER_ROWS =
            "{\"value\":[{\"OrderId\":1,\"OrderStatus\":\"Delivered\"},{\"OrderId\":2,\"OrderStatus\":\"Shipped\"}]}";

    private static final String ORDER_COUNT =
            "{\"entity\":\"Order\",\"result\":[{\"count\":120}],\"status\":\"success\"}";

    private static final String ORDER_COUNT_AS_STRING =
            "{\"entity\":\"Order\",\"result\":[{\"count\":\"120\"}],\"message\":\"Successfully aggregated records\","
                    + "\"status\":\"success\"}";

    private static final String CUSTOMER_COUNT =
            "{\"entity\":\"Customer\",\"result\":[{\"count\":40}],\"status\":\"success\"}";

    @Test
    void totalCountIsReadFromTheCountingToolResult() {
        PrivacySession session = this.guard.newSession();
        ToolCallback[] tools = session.wrap(new ToolCallback[] {
                stubCallback(ORDER_ROWS, "read_records"), stubCallback(ORDER_COUNT, "aggregate_records") });

        tools[0].call("{\"entity\":\"Order\",\"first\":2}");
        tools[1].call("{\"entity\":\"Order\",\"function\":\"count\",\"field\":\"*\"}");

        assertThat(session.resolvedTotalCount()).isEqualTo(120L);
    }

    @Test
    void totalCountIsReadThroughTheMcpContentEnvelope() {
        PrivacySession session = this.guard.newSession();
        String enveloped = "{\"content\":[{\"type\":\"text\",\"text\":"
                + JsonMapper.builder().build().writeValueAsString(ORDER_COUNT) + "}]}";
        ToolCallback[] tools = session.wrap(new ToolCallback[] { stubCallback(enveloped, "aggregate_records") });

        tools[0].call("{\"entity\":\"Order\",\"function\":\"count\",\"field\":\"*\"}");

        assertThat(session.resolvedTotalCount()).isEqualTo(120L);
    }

    @Test
    void stringCountFromDabEnvelopeStaysVisibleAndRecordsTotal() {
        PrivacySession session = this.guard.newSession();
        String enveloped = "{\"content\":[{\"type\":\"text\",\"text\":"
                + JsonMapper.builder().build().writeValueAsString(ORDER_COUNT_AS_STRING) + "}]}";
        ToolCallback[] tools = session.wrap(new ToolCallback[] { stubCallback(enveloped, "aggregate_records") });

        String result = tools[0].call("{\"entity\":\"Order\",\"function\":\"count\",\"field\":\"*\"}");

        assertThat(result).contains("\\\"count\\\":\\\"120\\\"").doesNotContain("[PII:VALUE:");
        assertThat(session.resolvedTotalCount()).isEqualTo(120L);
    }

    @Test
    void totalCountIsUnknownWhenTwoEntitiesWereCounted() {
        PrivacySession session = this.guard.newSession();
        ToolCallback[] tools = session.wrap(new ToolCallback[] {
                stubCallback(ORDER_COUNT, "aggregate_records"), stubCallback(CUSTOMER_COUNT, "aggregate_records") });

        tools[0].call("{\"entity\":\"Order\",\"function\":\"count\",\"field\":\"*\"}");
        tools[1].call("{\"entity\":\"Customer\",\"function\":\"count\",\"field\":\"*\"}");

        assertThat(session.resolvedTotalCount()).isEqualTo(ChatResponse.UNKNOWN_TOTAL);
    }

    @Test
    void totalCountIsUnknownWhenTheCountedEntityIsNotTheOneRead() {
        PrivacySession session = this.guard.newSession();
        ToolCallback[] tools = session.wrap(new ToolCallback[] {
                stubCallback(ORDER_ROWS, "read_records"), stubCallback(CUSTOMER_COUNT, "aggregate_records") });

        tools[0].call("{\"entity\":\"Order\",\"first\":2}");
        tools[1].call("{\"entity\":\"Customer\",\"function\":\"count\",\"field\":\"*\"}");

        assertThat(session.resolvedTotalCount()).isEqualTo(ChatResponse.UNKNOWN_TOTAL);
    }

    @Test
    void totalCountIsUnknownWhenTheReadFilterDoesNotMatchTheCountFilter() {
        PrivacySession session = this.guard.newSession();
        ToolCallback[] tools = session.wrap(new ToolCallback[] {
                stubCallback(ORDER_ROWS, "read_records"), stubCallback(ORDER_COUNT, "aggregate_records") });

        tools[0].call("{\"entity\":\"Order\",\"filter\":\"OrderStatus eq 'Delivered'\",\"first\":2}");
        tools[1].call("{\"entity\":\"Order\",\"function\":\"count\",\"field\":\"*\"}");

        assertThat(session.resolvedTotalCount()).isEqualTo(ChatResponse.UNKNOWN_TOTAL);
    }

    @Test
    void groupedCountIsNotTreatedAsTheMatchingRecordTotal() {
        PrivacySession session = this.guard.newSession();
        ToolCallback[] tools = session.wrap(new ToolCallback[] {
                stubCallback(ORDER_COUNT, "aggregate_records") });

        tools[0].call("{\"entity\":\"Order\",\"function\":\"count\",\"field\":\"*\","
                + "\"groupby\":[\"OrderStatus\"]}");

        assertThat(session.resolvedTotalCount()).isEqualTo(ChatResponse.UNKNOWN_TOTAL);
    }
}

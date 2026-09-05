package com.ai.querymateai.security;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.mock.env.MockEnvironment;

import com.ai.querymateai.config.AppProperties;
import com.ai.querymateai.trace.LocalAiTraceLogger;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveEgressFirewallTests {

    private static final String REAL_NAME = "John Smith";

    private static final String REAL_EMAIL = "john.smith@example.com";

    private final SensitiveDataGuard guard = newGuard();

    private final SensitiveEgressFirewall firewall = new SensitiveEgressFirewall();

    @AfterEach
    void unbind() {
        SensitiveEgressFirewall.unbind();
    }

    @Test
    void payloadCarryingOnlyProtectedTokensIsSentOn() throws IOException {
        SensitiveRequestContext session = this.guard.newSession();
        String protectedMessage = session.protectInput("Find customer " + REAL_NAME);
        session.bindToEgress();

        RecordingChain chain = new RecordingChain(body(protectedMessage));

        assertThatCode(() -> this.firewall.intercept(chain)).doesNotThrowAnyException();
        assertThat(chain.forwarded).isNotNull();
        assertThat(chain.forwardedBody()).contains("CustomerNameProtected#1").doesNotContain(REAL_NAME);
    }

    @Test
    void payloadCarryingARawVaultedValueIsBlocked() {
        SensitiveRequestContext session = this.guard.newSession();
        session.protectInput("Find customer " + REAL_NAME);
        session.bindToEgress();

        // Simulates protection failing upstream: the raw name reaches the outbound payload.
        RecordingChain chain = new RecordingChain(body("Find customer " + REAL_NAME));

        assertThatThrownBy(() -> this.firewall.intercept(chain))
                .isInstanceOf(SensitiveEgressFirewall.SensitiveEgressBlockedException.class);
        assertThat(chain.forwarded).as("blocked request must never reach the provider").isNull();
    }

    @Test
    void valueVaultedFromAToolResultIsBlockedOnALaterTurn() {
        SensitiveRequestContext session = this.guard.newSession();
        session.protectStructuredCell("Email", REAL_EMAIL);
        session.bindToEgress();

        RecordingChain chain = new RecordingChain(body("contact is " + REAL_EMAIL));

        assertThatThrownBy(() -> this.firewall.intercept(chain))
                .isInstanceOf(SensitiveEgressFirewall.SensitiveEgressBlockedException.class);
        assertThat(chain.forwarded).isNull();
    }

    @Test
    void jsonEscapingCannotHideAVaultedValue() {
        String quotedSecret = "Jane \"JJ\" Doe";
        SensitiveRequestContext session = this.guard.newSession();
        session.protectStructuredCell("FullName", quotedSecret);
        session.bindToEgress();
        String json = JsonMapper.builder().build()
                .writeValueAsString(java.util.Map.of("message", quotedSecret));

        RecordingChain chain = new RecordingChain(body(json));

        assertThatThrownBy(() -> this.firewall.intercept(chain))
                .isInstanceOf(SensitiveEgressFirewall.SensitiveEgressBlockedException.class);
        assertThat(chain.forwarded).isNull();
    }

    @Test
    void shortCommonWordFromAnUnclassifiedColumnDoesNotBlockTheRequest() {
        // Regression: a LoyaltyTier of "Gold" was vaulted, then matched the schema's own
        // description ("Bronze Silver Gold or Platinum") already in the conversation, and the
        // firewall failed a request whose payload was correctly protected throughout.
        SensitiveRequestContext session = this.guard.newSession();
        session.protectStructuredCell("LoyaltyTier", "Gold");
        session.bindToEgress();

        RecordingChain chain = new RecordingChain(
                body("Loyalty tier such as Bronze Silver Gold or Platinum"));

        assertThatCode(() -> this.firewall.intercept(chain)).doesNotThrowAnyException();
        assertThat(chain.forwarded).isNotNull();
    }

    @Test
    void distinctiveValuesAreStillBlockedAfterTheCollisionFix() {
        SensitiveRequestContext session = this.guard.newSession();
        session.protectInput("Find customer " + REAL_NAME);
        session.protectStructuredCell("Email", REAL_EMAIL);
        // TaxId is vaulted by the deny-by-default tool-result path, not by field name.
        session.wrap(new ToolCallback[] { taxIdTool() })[0].call("{\"entity\":\"Customer\"}");
        session.bindToEgress();

        for (String leaked : List.of(REAL_NAME, REAL_EMAIL, "AB-123-XYZ")) {
            RecordingChain chain = new RecordingChain(body("value is " + leaked));
            assertThatThrownBy(() -> this.firewall.intercept(chain))
                    .as("must still block %s", leaked)
                    .isInstanceOf(SensitiveEgressFirewall.SensitiveEgressBlockedException.class);
        }
    }

    @Test
    void callWithoutABoundSessionIsBlocked() {
        RecordingChain chain = new RecordingChain(body("anything at all"));

        assertThatThrownBy(() -> this.firewall.intercept(chain))
                .isInstanceOf(SensitiveEgressFirewall.SensitiveEgressBlockedException.class);
        assertThat(chain.forwarded).isNull();
    }

    @Test
    void oneSessionsVaultDoesNotLeakIntoAnother() {
        SensitiveRequestContext first = this.guard.newSession();
        first.protectInput("Find customer " + REAL_NAME);

        SensitiveRequestContext second = this.guard.newSession();
        String secondProtected = second.protectInput("Find customer Ethan Thomas");
        second.bindToEgress();

        // Counters restart per session, so the second turn mints #1 for its own value...
        assertThat(secondProtected).contains("CustomerNameProtected#1");
        // ...and resolving that token must not reach into the first session's vault.
        assertThat(second.restoreProtectedValues(secondProtected))
                .contains("Ethan Thomas")
                .doesNotContain(REAL_NAME);

        RecordingChain chain = new RecordingChain(body(secondProtected));
        assertThatCode(() -> this.firewall.intercept(chain)).doesNotThrowAnyException();
    }

    private static ToolCallback taxIdTool() {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("read_records").description("read").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "{\"value\":[{\"TaxId\":\"AB-123-XYZ\"}]}";
            }
        };
    }

    private static SensitiveDataGuard newGuard() {
        AppProperties properties = new AppProperties(
                new AppProperties.Models("primary", "fallback"),
                new AppProperties.Execution(true, false, 1200, 0.1, java.time.Duration.ofSeconds(120),
                        AppProperties.ResponseFormat.JSON_SCHEMA),
                new AppProperties.Memory(20),
                new AppProperties.Security(new AppProperties.DataPolicy(
                        List.of("City", "StateProvince", "Country", "LoyaltyTier", "Status"),
                        null, null, null)),
                new AppProperties.Logging(false),
                new AppProperties.Ai(new AppProperties.Trace(false, false, 20_000)),
                List.of(new AppProperties.SensitiveField("Customer", "FullName", null),
                        new AppProperties.SensitiveField("Customer", "Email", null),
                        new AppProperties.SensitiveField("Customer", "Phone", null)));
        return new SensitiveDataGuard(properties, JsonMapper.builder().build(),
                new LocalAiTraceLogger(properties, new MockEnvironment()));
    }

    private static Request body(String payload) {
        String trimmed = payload.strip();
        String json = trimmed.startsWith("{") || trimmed.startsWith("[") ? payload
                : JsonMapper.builder().build().writeValueAsString(java.util.Map.of("message", payload));
        return new Request.Builder().url("https://api.openai.com/v1/chat/completions")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
    }

    /** Records what the firewall forwarded, so a blocked call is provably never sent. */
    private static final class RecordingChain implements Interceptor.Chain {

        private final Request request;

        private Request forwarded;

        private RecordingChain(Request request) {
            this.request = request;
        }

        private String forwardedBody() throws IOException {
            okio.Buffer buffer = new okio.Buffer();
            this.forwarded.body().writeTo(buffer);
            return buffer.readUtf8();
        }

        @Override
        public Request request() {
            return this.request;
        }

        @Override
        public Response proceed(Request request) {
            this.forwarded = request;
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create("{}", MediaType.parse("application/json")))
                    .build();
        }

        @Override
        public Connection connection() {
            return null;
        }

        @Override
        public Call call() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int connectTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withConnectTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int readTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withReadTimeout(int timeout, TimeUnit unit) {
            return this;
        }

        @Override
        public int writeTimeoutMillis() {
            return 0;
        }

        @Override
        public Interceptor.Chain withWriteTimeout(int timeout, TimeUnit unit) {
            return this;
        }
    }
}

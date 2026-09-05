package com.ai.querymateai.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Last check before any byte reaches the model provider.
 *
 * <p>Registered as an OkHttp interceptor on the OpenAI client, so every outbound call
 * passes through here: the first turn, each turn of the tool-calling loop, SDK retries,
 * and the fallback model. Asserting at individual call sites would miss most of those,
 * because the whole tool loop runs inside a single {@code ChatClient.call()}.
 *
 * <p>The check is exact rather than heuristic. {@link RequestTokenVault} vaults every raw
 * value it protects, so those values are ground truth: not one of them may appear in an
 * outbound payload. A hit means protection failed somewhere upstream, and the request is
 * refused rather than sent.
 */
public final class SensitiveEgressFirewall implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveEgressFirewall.class);

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private static final ThreadLocal<PrivacySession> ACTIVE = new ThreadLocal<>();

    /**
     * Binds the turn's protection state to the calling thread. The model call is synchronous
     * all the way down to {@code okhttp3.Call#execute()}, so the interceptor runs on this
     * same thread.
     */
    static void bind(PrivacySession session) {
        ACTIVE.set(session);
    }

    static void unbind() {
        ACTIVE.remove();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        PrivacySession session = ACTIVE.get();
        if (session == null) {
            // No protection state means nothing can be verified, so nothing may be sent.
            logger.error("Egress blocked: model call attempted outside a protected request scope url={}",
                    request.url().encodedPath());
            throw new SensitiveEgressBlockedException(
                    "Model call attempted outside a protected request scope.");
        }
        RequestBody body = request.body();
        if (body == null) {
            return chain.proceed(request);
        }
        byte[] payload = readBody(body);
        String serializedPayload = new String(payload, StandardCharsets.UTF_8);
        try {
            DataDisclosurePolicy.ensurePayloadWithinLimit(serializedPayload,
                    session.policy().limits().maxEgressBytes(), "Model egress payload");
        }
        catch (DataDisclosurePolicy.PolicyViolationException ex) {
            logger.error("Egress blocked: payload exceeded configured limit requestId={} path={}",
                    session.requestId(), request.url().encodedPath());
            throw new SensitiveEgressBlockedException(ex.getMessage());
        }
        List<String> leakedTokens = session.vaultedValuesPresentIn(serializedPayload);
        try {
            collectDecodedLeaks(OBJECT_MAPPER.readTree(serializedPayload), session, leakedTokens);
        }
        catch (RuntimeException ex) {
            logger.error("Egress blocked: outbound model payload could not be inspected requestId={} errorType={}",
                    session.requestId(), ex.getClass().getSimpleName());
            throw new SensitiveEgressBlockedException(
                    "Outbound model payload could not be inspected and was blocked.");
        }
        if (!leakedTokens.isEmpty()) {
            // Never log the values themselves, only which protected slots they belong to.
            logger.error("Egress blocked: outbound payload contained {} unprotected sensitive value(s) "
                    + "requestId={} tokens={}", leakedTokens.size(), session.requestId(), leakedTokens);
            throw new SensitiveEgressBlockedException(
                    "Outbound payload contained an unprotected sensitive value and was blocked.");
        }
        // The body was consumed to read it, so forward the buffered copy.
        return chain.proceed(request.newBuilder()
                .method(request.method(), RequestBody.create(payload, body.contentType()))
                .build());
    }

    private static byte[] readBody(RequestBody body) throws IOException {
        try (Buffer buffer = new Buffer()) {
            body.writeTo(buffer);
            return buffer.readByteArray();
        }
    }

    /** Checks decoded JSON strings as well as raw bytes, which may contain JSON escapes. */
    private static void collectDecodedLeaks(JsonNode node, PrivacySession session,
            List<String> leakedTokens) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            for (String token : session.vaultedValuesPresentIn(node.stringValue())) {
                if (!leakedTokens.contains(token)) {
                    leakedTokens.add(token);
                }
            }
            return;
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                collectDecodedLeaks(child, session, leakedTokens);
            }
        }
    }

    /** Thrown instead of completing a request that would have leaked a protected value. */
    public static final class SensitiveEgressBlockedException extends RuntimeException {

        SensitiveEgressBlockedException(String message) {
            super(message);
        }
    }
}

package com.example.querymateai.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.util.StringUtils;

public final class SensitiveTokenStore {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b([A-Z]{2,8})_([0-9a-f]{6,12})\\b");

    private final Map<String, String> tokenToValue = new ConcurrentHashMap<>();

    private final byte[] secretKey;

    SensitiveTokenStore(byte[] secretKey) {
        this.secretKey = secretKey;
    }

    public int size() {
        return this.tokenToValue.size();
    }

    public Set<String> prefixes() {
        return this.tokenToValue.keySet().stream()
                .map(token -> token.substring(0, token.indexOf('_')))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(this.tokenToValue);
    }

    String detokenize(String text) {
        if (!StringUtils.hasText(text) || this.tokenToValue.isEmpty()) {
            return text;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        StringBuilder restored = new StringBuilder();
        while (matcher.find()) {
            String replacement = this.tokenToValue.get(matcher.group());
            matcher.appendReplacement(restored,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(restored);
        return restored.toString();
    }

    static int resolvedTokenCount(String protectedInput, String detokenizedInput) {
        if (!StringUtils.hasText(protectedInput) || !StringUtils.hasText(detokenizedInput)
                || protectedInput.equals(detokenizedInput)) {
            return 0;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(protectedInput);
        int count = 0;
        while (matcher.find()) {
            if (!detokenizedInput.contains(matcher.group())) {
                count++;
            }
        }
        return count;
    }

    String protectKnownValues(String text) {
        if (!StringUtils.hasText(text) || this.tokenToValue.isEmpty()) {
            return text;
        }
        String protectedText = text;
        for (Map.Entry<String, String> entry : this.tokenToValue.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparingInt(String::length).reversed()))
                .toList()) {
            if (StringUtils.hasText(entry.getValue())) {
                protectedText = protectedText.replace(entry.getValue(), entry.getKey());
            }
        }
        return protectedText;
    }

    String tokenFor(String field, String prefix, String value) {
        String digest = hmacHex(field.toLowerCase() + '|' + value);
        for (int length = 6; length <= 12; length += 2) {
            String token = prefix + '_' + digest.substring(0, length);
            String existing = this.tokenToValue.putIfAbsent(token, value);
            if (existing == null || existing.equals(value)) {
                return token;
            }
        }
        return prefix + '_' + digest.substring(0, 12);
    }

    private String hmacHex(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.secretKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to derive sensitive-field token", ex);
        }
    }
}

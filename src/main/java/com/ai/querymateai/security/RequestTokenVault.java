package com.ai.querymateai.security;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import com.ai.querymateai.security.PiiDetector.PiiFinding;
import com.ai.querymateai.security.PiiDetector.PiiType;

/** Request-local pseudonym vault. Raw values are never persisted and are cleared on close. */
final class RequestTokenVault implements AutoCloseable {

    private static final int NAMESPACE_BYTES = 12;

    private static final int MIN_LEAK_CHECK_LENGTH = 3;

    private static final int MIN_PLAIN_WORD_LEAK_CHECK_LENGTH = 9;

    private static final SecureRandom RANDOM = new SecureRandom();

    static final Pattern TOKEN = Pattern.compile(
            "\\[PII:(NAME|EMAIL|PHONE|VALUE):([A-Za-z0-9_-]{16}):(\\d+)]");

    private final String namespace = newNamespace();

    private final Map<String, MutableTokenEntry> entryByToken = new LinkedHashMap<>();

    private final Map<ValueKey, String> tokenByValue = new HashMap<>();

    private final Map<PiiType, Integer> counters = new HashMap<>();

    String protectText(String text, List<PiiFinding> findings, PiiOrigin origin) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        List<Replacement> replacements = new ArrayList<>();
        Matcher tokenMatcher = TOKEN.matcher(text);
        while (tokenMatcher.find()) {
            replacements.add(new Replacement(tokenMatcher.start(), tokenMatcher.end(), null));
        }
        for (PiiFinding finding : findings) {
            replacements.add(new Replacement(finding.start(), finding.end(), finding.type()));
        }
        replacements.sort(java.util.Comparator.comparingInt(Replacement::start)
                .thenComparing(java.util.Comparator.comparingInt(Replacement::length).reversed()));

        StringBuilder result = new StringBuilder(text.length());
        int index = 0;
        for (Replacement replacement : replacements) {
            if (replacement.start() < index) {
                continue;
            }
            result.append(text, index, replacement.start());
            String raw = text.substring(replacement.start(), replacement.end());
            result.append(replacement.type() == null ? raw : protectKnown(raw, replacement.type(), origin));
            index = replacement.end();
        }
        result.append(text.substring(index));
        return result.toString();
    }

    String protectKnown(String raw, PiiType type, PiiOrigin origin) {
        if (!StringUtils.hasText(raw) || TOKEN.matcher(raw).matches()) {
            return raw;
        }
        ValueKey key = new ValueKey(type, raw);
        String existing = this.tokenByValue.get(key);
        if (existing != null) {
            this.entryByToken.get(existing).origins.add(origin);
            return existing;
        }
        int counter = this.counters.merge(type, 1, Integer::sum);
        String token = "[PII:" + type.name() + ':' + this.namespace + ':' + counter + ']';
        this.tokenByValue.put(key, token);
        this.entryByToken.put(token, new MutableTokenEntry(raw, type, EnumSet.of(origin)));
        return token;
    }

    /** Resolves only values supplied by the user in this request; tool/model-origin values cannot expand access. */
    String resolveUserInputTokensForTool(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder restored = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            MutableTokenEntry entry = this.entryByToken.get(token);
            if (entry == null || !entry.origins.contains(PiiOrigin.USER_INPUT)) {
                throw new IllegalArgumentException("Unknown, expired, or disallowed protected value.");
            }
            matcher.appendReplacement(restored, Matcher.quoteReplacement(entry.rawValue));
        }
        matcher.appendTail(restored);
        return restored.toString();
    }

    String displayProtectedValues(String text, boolean mayRevealNames) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder displayed = new StringBuilder();
        while (matcher.find()) {
            MutableTokenEntry entry = this.entryByToken.get(matcher.group());
            String replacement = entry == null ? matcher.group() : displayValue(entry, mayRevealNames);
            matcher.appendReplacement(displayed, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(displayed);
        return displayed.toString();
    }

    List<String> tokensLeakedIn(String payload) {
        if (!StringUtils.hasText(payload) || this.entryByToken.isEmpty()) {
            return List.of();
        }
        String haystack = payload.toLowerCase(Locale.ROOT);
        List<String> leaked = new ArrayList<>();
        this.entryByToken.forEach((token, entry) -> {
            if (distinctiveEnoughToAccuse(entry.rawValue)
                    && haystack.contains(entry.rawValue.toLowerCase(Locale.ROOT))) {
                leaked.add(token);
            }
        });
        return leaked;
    }

    int protectedValueCount(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int count = 0;
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    int resolvedProtectedValueCount(String before, String after) {
        return !StringUtils.hasText(before) || before.equals(after) ? 0 : protectedValueCount(before);
    }

    Set<PiiOrigin> origins(String token) {
        MutableTokenEntry entry = this.entryByToken.get(token);
        return entry == null ? Set.of() : Set.copyOf(entry.origins);
    }

    String namespace() {
        return this.namespace;
    }

    @Override
    public void close() {
        this.entryByToken.clear();
        this.tokenByValue.clear();
        this.counters.clear();
    }

    private static String displayValue(MutableTokenEntry entry, boolean mayRevealNames) {
        return switch (entry.type) {
            case NAME -> mayRevealNames && entry.origins.contains(PiiOrigin.AUTHORIZED_TOOL_RESULT)
                    ? entry.rawValue : maskName(entry.rawValue);
            case EMAIL -> maskEmail(entry.rawValue);
            case PHONE -> maskPhone(entry.rawValue);
            case VALUE -> maskGeneric(entry.rawValue);
        };
    }

    private static boolean distinctiveEnoughToAccuse(String raw) {
        if (raw.length() < MIN_LEAK_CHECK_LENGTH) {
            return false;
        }
        for (int index = 0; index < raw.length(); index++) {
            if (!Character.isLetter(raw.charAt(index))) {
                return true;
            }
        }
        return raw.length() >= MIN_PLAIN_WORD_LEAK_CHECK_LENGTH;
    }

    private static String maskName(String value) {
        return java.util.Arrays.stream(value.strip().split("\\s+"))
                .map(part -> part.isEmpty() ? "***" : part.substring(0, 1) + "***")
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String maskGeneric(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= 2 ? "***" : trimmed.substring(0, 2) + "***";
    }

    private static String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return "email***";
        }
        String local = value.substring(0, at);
        return local.substring(0, Math.min(3, local.length())) + "***@" + value.substring(at + 1);
    }

    private static String maskPhone(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() <= 3) {
            return digits + "***";
        }
        String prefix = digits.substring(0, Math.min(3, digits.length()));
        String suffix = digits.length() > 5 ? digits.substring(digits.length() - 2) : "";
        return prefix + "***" + suffix;
    }

    private static String newNamespace() {
        byte[] bytes = new byte[NAMESPACE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    enum PiiOrigin {
        USER_INPUT,
        AUTHORIZED_TOOL_RESULT,
        MODEL_OUTPUT
    }

    private static final class MutableTokenEntry {

        private final String rawValue;

        private final PiiType type;

        private final EnumSet<PiiOrigin> origins;

        private MutableTokenEntry(String rawValue, PiiType type, EnumSet<PiiOrigin> origins) {
            this.rawValue = rawValue;
            this.type = type;
            this.origins = origins;
        }
    }

    private record ValueKey(PiiType type, String rawValue) {
    }

    private record Replacement(int start, int end, PiiType type) {

        int length() {
            return this.end - this.start;
        }
    }
}

package com.ai.querymateai.security;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

final class PiiDetector {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w.-])");

    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\w)(?:\\+?\\d[\\d(). -]{7,}\\d)(?!\\w)");

    private static final Pattern EXPLICIT_NAME = Pattern.compile(
            "(?i:\\b(?:customer(?:\\s+(?:named|called))?|full\\s+name\\s*(?:is|=|:)|name\\s*(?:is|=|:))\\s*['\"]?)"
                    + "([\\p{Lu}][\\p{L}'-]+(?:\\s+[\\p{Lu}][\\p{L}'-]+){1,3})");

    private static final Pattern FULL_NAME_FILTER = Pattern.compile(
            "(?i)(FullName\\s+eq\\s+['\"])([^'\"]+)(['\"])");

    String protect(String text, SensitiveTokenStore tokens, Map<String, String> prefixByField) {
        String protectedText = replaceMatches(text, EMAIL, "Email", prefix(prefixByField, "email", "EM"), tokens);
        protectedText = replacePhoneMatches(protectedText, "Phone", prefix(prefixByField, "phone", "PH"), tokens);
        protectedText = replaceGroup(protectedText, FULL_NAME_FILTER, 2, "FullName",
                prefix(prefixByField, "fullname", "CU"), tokens);
        return replaceGroup(protectedText, EXPLICIT_NAME, 1, "FullName",
                prefix(prefixByField, "fullname", "CU"), tokens);
    }

    String redactProviderContactDetails(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return redactPhoneMatches(EMAIL.matcher(text).replaceAll("[REDACTED_EMAIL]"));
    }

    private String replaceMatches(String text, Pattern pattern, String field, String prefix,
            SensitiveTokenStore tokens) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(safe,
                    Matcher.quoteReplacement(tokens.tokenFor(field, prefix, matcher.group())));
        }
        matcher.appendTail(safe);
        return safe.toString();
    }

    private String replacePhoneMatches(String text, String field, String prefix, SensitiveTokenStore tokens) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher matcher = PHONE.matcher(text);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) {
            String candidate = matcher.group();
            String replacement = hasEnoughPhoneDigits(candidate) ? tokens.tokenFor(field, prefix, candidate) : candidate;
            matcher.appendReplacement(safe, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(safe);
        return safe.toString();
    }

    private String redactPhoneMatches(String text) {
        Matcher matcher = PHONE.matcher(text);
        StringBuilder safe = new StringBuilder();
        while (matcher.find()) {
            String candidate = matcher.group();
            String replacement = hasEnoughPhoneDigits(candidate) ? "[REDACTED_PHONE]" : candidate;
            matcher.appendReplacement(safe, replacement);
        }
        matcher.appendTail(safe);
        return safe.toString();
    }

    private String replaceGroup(String text, Pattern pattern, int group, String field, String prefix,
            SensitiveTokenStore tokens) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder safe = new StringBuilder();
        int copiedUntil = 0;
        while (matcher.find()) {
            safe.append(text, copiedUntil, matcher.start(group));
            safe.append(tokens.tokenFor(field, prefix, matcher.group(group)));
            copiedUntil = matcher.end(group);
        }
        return copiedUntil == 0 ? text : safe.append(text, copiedUntil, text.length()).toString();
    }

    private static boolean hasEnoughPhoneDigits(String candidate) {
        return candidate.chars().filter(Character::isDigit).count() >= 10;
    }

    private static String prefix(Map<String, String> prefixByField, String field, String fallback) {
        return prefixByField.getOrDefault(field, fallback);
    }
}

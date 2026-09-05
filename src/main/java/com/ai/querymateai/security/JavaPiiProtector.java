package com.ai.querymateai.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.SimpleTokenizer;

import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.util.StringUtils;

import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

final class JavaPiiProtector {

    private static final Pattern PROTECTED_VALUE =
            Pattern.compile("\\b(?:CustomerName|Email|Phone|SensitiveValue)Protected#\\d+\\b");

    private static final Pattern EMAIL_CANDIDATE =
            Pattern.compile("(?<!\\S)[^\\s@]+@[^\\s@]+(?!\\S)");

    private static final Pattern CUSTOMER_NAME =
            Pattern.compile("(?i)\\b(?:find|show|get|lookup|locate)?\\s*(?:customer|contact|person|customer\\s+named|customer\\s+name|fullname|full\\s+name|name)\\s+(?:(?:details|profile|record|information)\\s+(?:of|for)\\s+|named|called|with\\s+name|is|=|eq\\s+)?([A-Z][A-Za-z'’-]+(?:\\s+[A-Z][A-Za-z'’-]+){1,3})");

    private final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

    private final EmailValidator emailValidator = EmailValidator.getInstance(false, true);

    private final NameFinderME personNameFinder = personNameFinder();

    private final Map<String, String> rawByToken = new LinkedHashMap<>();

    private final Map<String, String> tokenByProtectedValue = new HashMap<>();

    private final Map<String, Integer> counters = new HashMap<>();

    String protect(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return replaceSpans(text, detectedSpans(text));
    }

    String protectKnownSensitiveValue(String value, String fieldName) {
        if (!StringUtils.hasText(value) || protectedValueCount(value) > 0) {
            return value;
        }
        return newToken(labelForField(fieldName), value);
    }

    String protectContactDetails(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        List<Span> spans = new ArrayList<>();
        addEmailSpans(text, spans);
        addPhoneSpans(text, spans);
        return replaceSpans(text, spans);
    }

    String restoreProtectedValues(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher matcher = PROTECTED_VALUE.matcher(text);
        StringBuilder restored = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String raw = this.rawByToken.get(token);
            if (raw == null) {
                throw new IllegalArgumentException("Unknown protected value.");
            }
            matcher.appendReplacement(restored, Matcher.quoteReplacement(raw));
        }
        matcher.appendTail(restored);
        return restored.toString();
    }

    String displayProtectedValues(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        Matcher matcher = PROTECTED_VALUE.matcher(text);
        StringBuilder displayed = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            matcher.appendReplacement(displayed, Matcher.quoteReplacement(displayValue(token)));
        }
        matcher.appendTail(displayed);
        return displayed.toString();
    }

    String displayColumnName(String column) {
        if (!StringUtils.hasText(column)) {
            return column;
        }
        String normalized = column.toLowerCase(Locale.ROOT);
        if (normalized.contains("customernameprotected") || normalized.contains("fullnameprotected")) {
            return "FullName";
        }
        if (normalized.contains("emailprotected")) {
            return "Email";
        }
        if (normalized.contains("phoneprotected")) {
            return "Phone";
        }
        return column;
    }

    int protectedValueCount(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        Matcher matcher = PROTECTED_VALUE.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    int resolvedProtectedValueCount(String before, String after) {
        if (!StringUtils.hasText(before) || before.equals(after)) {
            return 0;
        }
        return protectedValueCount(before);
    }

    private List<Span> detectedSpans(String text) {
        List<Span> spans = new ArrayList<>();
        addExistingProtectedTokenSpans(text, spans);
        addEmailSpans(text, spans);
        addPhoneSpans(text, spans);
        addOpenNlpPersonNameSpans(text, spans);
        addCustomerNameSpans(text, spans);
        return spans;
    }

    private void addExistingProtectedTokenSpans(String text, List<Span> spans) {
        Matcher matcher = PROTECTED_VALUE.matcher(text);
        while (matcher.find()) {
            spans.add(new Span(matcher.start(), matcher.end(), null, matcher.group()));
        }
    }

    private void addEmailSpans(String text, List<Span> spans) {
        Matcher matcher = EMAIL_CANDIDATE.matcher(text);
        while (matcher.find()) {
            Candidate candidate = trimCandidate(text, matcher.start(), matcher.end());
            if (this.emailValidator.isValid(candidate.value())) {
                spans.add(new Span(candidate.start(), candidate.end(), "EmailProtected", candidate.value()));
            }
        }
    }

    private void addPhoneSpans(String text, List<Span> spans) {
        for (PhoneNumberMatch match : this.phoneNumberUtil.findNumbers(text, "US",
                PhoneNumberUtil.Leniency.POSSIBLE, Long.MAX_VALUE)) {
            spans.add(new Span(match.start(), match.end(), "PhoneProtected", text.substring(match.start(), match.end())));
        }
    }

    private void addCustomerNameSpans(String text, List<Span> spans) {
        Matcher matcher = CUSTOMER_NAME.matcher(text);
        while (matcher.find()) {
            Candidate name = trimmedNameCandidate(text, matcher.start(1), matcher.end(1));
            if (!looksLikeNonName(name.value())) {
                spans.add(new Span(name.start(), name.end(), "CustomerNameProtected", name.value()));
            }
        }
    }

    private void addOpenNlpPersonNameSpans(String text, List<Span> spans) {
        if (this.personNameFinder == null) {
            return;
        }
        opennlp.tools.util.Span[] tokenSpans = SimpleTokenizer.INSTANCE.tokenizePos(text);
        String[] tokens = opennlp.tools.util.Span.spansToStrings(tokenSpans, text);
        opennlp.tools.util.Span[] names;
        synchronized (this.personNameFinder) {
            names = this.personNameFinder.find(tokens);
            this.personNameFinder.clearAdaptiveData();
        }
        for (opennlp.tools.util.Span name : names) {
            if (name.length() < 2) {
                continue;
            }
            int start = tokenSpans[name.getStart()].getStart();
            int end = tokenSpans[name.getEnd() - 1].getEnd();
            Candidate candidate = trimmedNameCandidate(text, start, end);
            if (!looksLikeNonName(candidate.value())) {
                spans.add(new Span(candidate.start(), candidate.end(), "CustomerNameProtected", candidate.value()));
            }
        }
    }

    private String replaceSpans(String text, List<Span> spans) {
        List<Span> accepted = nonOverlapping(spans);
        if (accepted.isEmpty()) {
            return text;
        }
        StringBuilder protectedText = new StringBuilder();
        int index = 0;
        for (Span span : accepted) {
            protectedText.append(text, index, span.start());
            if (span.label() == null) {
                protectedText.append(span.raw());
            }
            else {
                protectedText.append(newToken(span.label(), span.raw()));
            }
            index = span.end();
        }
        protectedText.append(text.substring(index));
        return protectedText.toString();
    }

    private List<Span> nonOverlapping(List<Span> spans) {
        List<Span> sorted = spans.stream()
                .sorted(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::length).reversed()))
                .toList();
        List<Span> accepted = new ArrayList<>();
        int coveredUntil = -1;
        for (Span span : sorted) {
            if (span.start() >= coveredUntil) {
                accepted.add(span);
                coveredUntil = span.end();
            }
        }
        return accepted;
    }

    private String newToken(String label, String raw) {
        String key = label + "\u0000" + raw;
        String existing = this.tokenByProtectedValue.get(key);
        if (existing != null) {
            return existing;
        }
        int next = this.counters.merge(label, 1, Integer::sum);
        String token = label + "#" + next;
        this.rawByToken.put(token, raw);
        this.tokenByProtectedValue.put(key, token);
        return token;
    }

    private String displayValue(String token) {
        String raw = this.rawByToken.get(token);
        if (raw == null) {
            return token;
        }
        if (token.startsWith("CustomerNameProtected#")) {
            return raw;
        }
        if (token.startsWith("EmailProtected#")) {
            return maskEmail(raw);
        }
        if (token.startsWith("PhoneProtected#")) {
            return maskPhone(raw);
        }
        return "ProtectedValue";
    }

    private static String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return "email***";
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        String prefix = local.substring(0, Math.min(3, local.length()));
        return prefix + "***@" + domain;
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

    private static Candidate trimCandidate(String text, int start, int end) {
        while (start < end && isBoundaryPunctuation(text.charAt(start))) {
            start++;
        }
        while (end > start && isBoundaryPunctuation(text.charAt(end - 1))) {
            end--;
        }
        return new Candidate(start, end, text.substring(start, end));
    }

    private static Candidate trimmedNameCandidate(String text, int start, int end) {
        Candidate candidate = trimCandidate(text, start, end);
        String lower = candidate.value().toLowerCase(Locale.ROOT);
        for (String prefix : List.of("details of ", "details for ", "profile of ", "profile for ",
                "record of ", "record for ", "information of ", "information for ")) {
            if (lower.startsWith(prefix)) {
                int adjustedStart = candidate.start() + prefix.length();
                return trimCandidate(text, adjustedStart, candidate.end());
            }
        }
        return candidate;
    }

    private static boolean isBoundaryPunctuation(char c) {
        return c == '\'' || c == '"' || c == '<' || c == '>' || c == '(' || c == ')'
                || c == '[' || c == ']' || c == ',' || c == '.' || c == ';' || c == ':';
    }

    private static boolean looksLikeNonName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("createddate") || normalized.contains("loyaltytier")
                || normalized.contains("customerid") || normalized.contains("email")
                || normalized.contains("phone");
    }

    private static String labelForField(String fieldName) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (normalized.contains("fullname") || normalized.contains("customername")
                || normalized.contains("nameprotected") || normalized.endsWith("name")) {
            return "CustomerNameProtected";
        }
        if (normalized.contains("email")) {
            return "EmailProtected";
        }
        if (normalized.contains("phone")) {
            return "PhoneProtected";
        }
        return "SensitiveValueProtected";
    }

    private static NameFinderME personNameFinder() {
        try {
            ClassLoader loader = JavaPiiProtector.class.getClassLoader();
            java.io.InputStream model = loader.getResourceAsStream("en-ner-person.bin");
            if (model == null) {
                model = loader.getResourceAsStream("opennlp/en-ner-person.bin");
            }
            if (model == null) {
                return null;
            }
            try (java.io.InputStream modelStream = model) {
                return new NameFinderME(new TokenNameFinderModel(modelStream));
            }
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException("Unable to load Apache OpenNLP person-name model.", ex);
        }
    }

    private record Span(int start, int end, String label, String raw) {

        int length() {
            return this.end - this.start;
        }
    }

    private record Candidate(int start, int end, String value) {
    }
}

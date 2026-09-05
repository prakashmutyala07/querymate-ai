package com.ai.querymateai.security;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.SimpleTokenizer;

import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.util.StringUtils;

import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

/** Detects PII spans in free-form text. It does not tokenize, store, mask, or restore values. */
final class PiiDetector {

    private static final Pattern EMAIL_CANDIDATE = Pattern.compile("(?<!\\S)[^\\s@]+@[^\\s@]+(?!\\S)");

    private static final Pattern CUSTOMER_NAME = Pattern.compile(
            "\\b(?i:find|show|get|lookup|locate)?\\s*(?i:customer|contact|person|customer\\s+named|customer\\s+name|fullname|full\\s+name|name)\\s+"
                    + "(?i:(?:details|profile|record|information)\\s+(?:of|for)\\s+|named|called|with\\s+name|is|=|eq\\s+)?"
                    + "([A-Z][A-Za-z'’-]+(?:\\s+[A-Z][A-Za-z'’-]+){1,3})");

    private static final TokenNameFinderModel PERSON_NAME_MODEL = loadPersonNameModel();

    private final PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

    private final EmailValidator emailValidator = EmailValidator.getInstance(false, true);

    private final String phoneRegion;

    PiiDetector(String phoneRegion, boolean requirePersonNameModel) {
        this.phoneRegion = phoneRegion;
        if (requirePersonNameModel && PERSON_NAME_MODEL == null) {
            throw new IllegalStateException(
                    "Person-name detection is required, but en-ner-person.bin is not on the classpath.");
        }
    }

    List<PiiFinding> detect(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<PiiFinding> findings = new ArrayList<>();
        addEmails(text, findings);
        addPhones(text, findings);
        addModelNames(text, findings);
        addCueNames(text, findings);
        return nonOverlapping(findings);
    }

    private void addEmails(String text, List<PiiFinding> findings) {
        Matcher matcher = EMAIL_CANDIDATE.matcher(text);
        while (matcher.find()) {
            Candidate candidate = trim(text, matcher.start(), matcher.end());
            if (this.emailValidator.isValid(candidate.value())) {
                findings.add(new PiiFinding(PiiType.EMAIL, candidate.start(), candidate.end(), 1.0, "email"));
            }
        }
    }

    private void addPhones(String text, List<PiiFinding> findings) {
        for (PhoneNumberMatch match : this.phoneNumberUtil.findNumbers(text, this.phoneRegion,
                PhoneNumberUtil.Leniency.POSSIBLE, Long.MAX_VALUE)) {
            findings.add(new PiiFinding(PiiType.PHONE, match.start(), match.end(), 1.0, "libphonenumber"));
        }
    }

    private static void addModelNames(String text, List<PiiFinding> findings) {
        if (PERSON_NAME_MODEL == null) {
            return;
        }
        NameFinderME finder = new NameFinderME(PERSON_NAME_MODEL);
        opennlp.tools.util.Span[] tokenSpans = SimpleTokenizer.INSTANCE.tokenizePos(text);
        String[] tokens = opennlp.tools.util.Span.spansToStrings(tokenSpans, text);
        for (opennlp.tools.util.Span name : finder.find(tokens)) {
            if (name.length() < 2) {
                continue;
            }
            int start = tokenSpans[name.getStart()].getStart();
            int end = tokenSpans[name.getEnd() - 1].getEnd();
            Candidate candidate = trimName(text, start, end);
            if (!looksLikeNonName(candidate.value())) {
                findings.add(new PiiFinding(PiiType.NAME, candidate.start(), candidate.end(),
                        name.getProb(), "opennlp"));
            }
        }
    }

    private static void addCueNames(String text, List<PiiFinding> findings) {
        Matcher matcher = CUSTOMER_NAME.matcher(text);
        while (matcher.find()) {
            Candidate candidate = trimName(text, matcher.start(1), matcher.end(1));
            if (!looksLikeNonName(candidate.value())) {
                findings.add(new PiiFinding(PiiType.NAME, candidate.start(), candidate.end(), 0.85, "name-cue"));
            }
        }
    }

    private static List<PiiFinding> nonOverlapping(List<PiiFinding> findings) {
        List<PiiFinding> sorted = findings.stream()
                .sorted(Comparator.comparingInt(PiiFinding::start)
                        .thenComparing(Comparator.comparingInt(PiiFinding::length).reversed()))
                .toList();
        List<PiiFinding> accepted = new ArrayList<>();
        int coveredUntil = -1;
        for (PiiFinding finding : sorted) {
            if (finding.start() >= coveredUntil) {
                accepted.add(finding);
                coveredUntil = finding.end();
            }
        }
        return List.copyOf(accepted);
    }

    private static Candidate trimName(String text, int start, int end) {
        Candidate candidate = trim(text, start, end);
        String lower = candidate.value().toLowerCase(Locale.ROOT);
        for (String prefix : List.of("details of ", "details for ", "profile of ", "profile for ",
                "record of ", "record for ", "information of ", "information for ")) {
            if (lower.startsWith(prefix)) {
                return trim(text, candidate.start() + prefix.length(), candidate.end());
            }
        }
        return candidate;
    }

    private static Candidate trim(String text, int start, int end) {
        while (start < end && isBoundaryPunctuation(text.charAt(start))) {
            start++;
        }
        while (end > start && isBoundaryPunctuation(text.charAt(end - 1))) {
            end--;
        }
        return new Candidate(start, end, text.substring(start, end));
    }

    private static boolean isBoundaryPunctuation(char value) {
        return value == '\'' || value == '"' || value == '<' || value == '>' || value == '('
                || value == ')' || value == '[' || value == ']' || value == ',' || value == '.'
                || value == ';' || value == ':';
    }

    private static boolean looksLikeNonName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("createddate") || normalized.contains("loyaltytier")
                || normalized.contains("customerid") || normalized.contains("email")
                || normalized.contains("phone");
    }

    static boolean personNameModelAvailable() {
        return PERSON_NAME_MODEL != null;
    }

    private static TokenNameFinderModel loadPersonNameModel() {
        ClassLoader loader = PiiDetector.class.getClassLoader();
        InputStream stream = loader.getResourceAsStream("en-ner-person.bin");
        if (stream == null) {
            stream = loader.getResourceAsStream("opennlp/en-ner-person.bin");
        }
        if (stream == null) {
            return null;
        }
        try (InputStream model = stream) {
            return new TokenNameFinderModel(model);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Unable to load Apache OpenNLP person-name model.", ex);
        }
    }

    enum PiiType {
        NAME,
        EMAIL,
        PHONE,
        VALUE
    }

    record PiiFinding(PiiType type, int start, int end, double confidence, String detector) {

        PiiFinding {
            if (type == null || start < 0 || end <= start) {
                throw new IllegalArgumentException("Invalid PII finding.");
            }
        }

        int length() {
            return this.end - this.start;
        }
    }

    private record Candidate(int start, int end, String value) {
    }
}

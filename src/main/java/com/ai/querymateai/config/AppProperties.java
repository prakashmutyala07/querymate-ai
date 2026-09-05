package com.ai.querymateai.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(Models models, Execution execution, Memory memory,
        Security security, Logging logging, Ai ai, List<SensitiveField> sensitiveFields) {

    public AppProperties {
        security = security == null ? new Security() : security;
        logging = logging == null ? new Logging(false) : logging;
        ai = ai == null ? new Ai(null) : ai;
        sensitiveFields = sensitiveFields == null ? List.of() : List.copyOf(sensitiveFields);
    }

    public record Models(String primary, String fallback) {
    }

    public record Execution(boolean fallbackEnabled, boolean primaryRetryEnabled, Integer maxCompletionTokens,
            Double temperature, java.time.Duration requestTimeout, ResponseFormat responseFormat) {

        public Execution {
            requestTimeout = requestTimeout == null ? java.time.Duration.ofSeconds(30) : requestTimeout;
            responseFormat = responseFormat == null ? ResponseFormat.PROMPT_JSON : responseFormat;
        }
    }

    public enum ResponseFormat {

        JSON_SCHEMA,

        PROMPT_JSON
    }

    public record Memory(int maxMessages) {
    }

    public record Security() {
    }

    public record Logging(boolean logSensitiveData) {
    }

    public record Ai(Trace trace) {

        public Ai {
            trace = trace == null ? new Trace(false, false, 20_000) : trace;
        }
    }

    public record Trace(boolean enabled, boolean includeSensitiveValues, Integer maxPayloadChars) {

        public Trace {
            maxPayloadChars = maxPayloadChars == null || maxPayloadChars < 1 ? 20_000 : maxPayloadChars;
        }
    }

    /**
     * One sensitive {@code entity.field} pair that must be encrypted when returned
     * by database tools.
     */
    public record SensitiveField(String entity, String field, String prefix) {

        public String prefixOrDefault() {
            return (this.prefix == null || this.prefix.isBlank())
                    ? this.entity.substring(0, Math.min(2, this.entity.length())).toUpperCase()
                    : this.prefix;
        }
    }
}

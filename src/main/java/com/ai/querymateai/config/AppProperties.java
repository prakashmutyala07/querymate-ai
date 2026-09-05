package com.ai.querymateai.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(Models models, Execution execution, Memory memory,
        Security security, Logging logging, Ai ai, List<SensitiveField> sensitiveFields) {

    public AppProperties {
        security = security == null ? new Security(null) : security;
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

    /**
     * Must keep exactly one constructor: Spring Boot's value-object binder silently binds
     * nothing when a record offers it a choice, which would leave the data policy empty and
     * deny-by-default protecting every column.
     */
    public record Security(DataPolicy dataPolicy) {

        public Security {
            dataPolicy = dataPolicy == null ? new DataPolicy(null, null, null, null) : dataPolicy;
        }
    }

    /**
     * Deny-by-default policy for tool results: every string in a returned row is protected
     * unless its column is declared safe here. A column nobody remembered to configure is
     * therefore protected rather than forwarded, which is the opposite of listing sensitive
     * fields one by one and hoping the list stays complete.
     *
     * <p>The policy applies only inside a row array, so the surrounding MCP and DAB envelope
     * ({@code content}, {@code text}, {@code entity}, {@code message}, {@code status}) is left
     * alone without having to enumerate transport keys, and no envelope key can collide with a
     * real column name.
     *
     * @param safeColumns string or numeric columns whose values may reach the model as-is
     * @param rowArrayKeys keys whose array value holds database rows
     * @param sensitiveNumericColumns numeric columns that override the safe list and must be protected
     * @param rowDataTools tools returning database rows; others (schema discovery) are left alone
     */
    public record DataPolicy(List<String> safeColumns, List<String> rowArrayKeys,
            List<String> sensitiveNumericColumns, List<String> rowDataTools) {

        private static final List<String> DEFAULT_ROW_ARRAY_KEYS = List.of("value", "result");

        private static final List<String> DEFAULT_ROW_DATA_TOOLS =
                List.of("read_records", "aggregate_records");

        public DataPolicy {
            safeColumns = lowerCased(safeColumns, List.of());
            rowArrayKeys = lowerCased(rowArrayKeys, DEFAULT_ROW_ARRAY_KEYS);
            sensitiveNumericColumns = lowerCased(sensitiveNumericColumns, List.of());
            rowDataTools = lowerCased(rowDataTools, DEFAULT_ROW_DATA_TOOLS);
        }

        /** True when a column's value may be forwarded to the model without protection. */
        public boolean isSafeColumn(String key) {
            return key != null && this.safeColumns.contains(key.toLowerCase(java.util.Locale.ROOT));
        }

        public boolean isRowArrayKey(String key) {
            return key != null && this.rowArrayKeys.contains(key.toLowerCase(java.util.Locale.ROOT));
        }

        public boolean isSensitiveNumericColumn(String key) {
            return key != null && this.sensitiveNumericColumns.contains(key.toLowerCase(java.util.Locale.ROOT));
        }

        public boolean returnsRowData(String toolName) {
            return toolName != null && this.rowDataTools.contains(toolName.toLowerCase(java.util.Locale.ROOT));
        }

        private static List<String> lowerCased(List<String> values, List<String> fallback) {
            List<String> source = (values == null || values.isEmpty()) ? fallback : values;
            return source.stream().map(value -> value.toLowerCase(java.util.Locale.ROOT)).toList();
        }
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

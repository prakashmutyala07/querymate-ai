package com.example.querymateai.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Backward-compatible facade for the local AI trace sensitive-value gate. */
@Component
public class SensitiveLoggingPolicy {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveLoggingPolicy.class);

    private static final Set<String> ALLOWED_PROFILES = Set.of("local", "dev");

    private final AppProperties properties;

    private final Set<String> activeProfiles;

    public SensitiveLoggingPolicy(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.activeProfiles = Arrays.stream(environment.getActiveProfiles()).collect(Collectors.toUnmodifiableSet());
    }

    @PostConstruct
    void warnIfSensitiveLoggingWasRejected() {
        if (requestedSensitiveLogging()) {
            logger.warn("app.logging.log-sensitive-data is deprecated and ignored. Use "
                    + "app.ai.trace.enabled=true plus app.ai.trace.include-sensitive-values=true in a local/dev profile.");
        }
    }

    public boolean sensitiveLoggingEnabled() {
        return this.properties.ai().trace().enabled()
                && this.properties.ai().trace().includeSensitiveValues()
                && isLocalOrDevProfile();
    }

    private boolean requestedSensitiveLogging() {
        return this.properties.logging() != null && this.properties.logging().logSensitiveData();
    }

    private boolean isLocalOrDevProfile() {
        return this.activeProfiles.stream().anyMatch(ALLOWED_PROFILES::contains);
    }
}

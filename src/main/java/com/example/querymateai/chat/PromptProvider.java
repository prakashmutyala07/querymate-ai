package com.example.querymateai.chat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

/** Loads the system prompt once and renders its request-time values. */
@Component
public class PromptProvider {

    private final String template;

    public PromptProvider(@Value("classpath:/prompts/sql-assistant-system.st") Resource resource) {
        this.template = read(resource);
    }

    public String systemPrompt() {
        return this.template
                .replace("__CURRENT_DATE__", LocalDate.now().toString())
                .replace("__TIME_ZONE__", ZoneId.systemDefault().getId());
    }

    private static String read(Resource resource) {
        try {
            return FileCopyUtils.copyToString(new java.io.InputStreamReader(resource.getInputStream(),
                    StandardCharsets.UTF_8));
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException("Unable to read system prompt", ex);
        }
    }
}

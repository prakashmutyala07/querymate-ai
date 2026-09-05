package com.ai.querymateai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

import com.ai.querymateai.security.SensitiveEgressFirewall;

@Configuration
public class AiConfiguration {

    @Bean
    ChatMemory chatMemory(AppProperties properties) {
        return MessageWindowChatMemory.builder()
                .maxMessages(properties.memory().maxMessages())
                .build();
    }

    /**
     * Puts {@link SensitiveEgressFirewall} on the OpenAI HTTP client so every outbound call is
     * inspected, including the tool-calling loop's intermediate turns, SDK retries and the
     * fallback model, none of which are visible at the {@code ChatClient} call site.
     */
    @Bean
    OpenAiHttpClientBuilderCustomizer sensitiveEgressFirewallCustomizer(SensitiveEgressFirewall firewall) {
        return builder -> builder.interceptor(firewall);
    }

    @Bean
    SensitiveEgressFirewall sensitiveEgressFirewall() {
        return new SensitiveEgressFirewall();
    }

    @Bean
    AsyncTaskExecutor chatTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.threadNamePrefix("chat-sse-")
                .corePoolSize(2)
                .maxPoolSize(8)
                .queueCapacity(32)
                .build();
    }
}

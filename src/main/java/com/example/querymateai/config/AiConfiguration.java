package com.example.querymateai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

@Configuration
public class AiConfiguration {

    @Bean
    ChatMemory chatMemory(AppProperties properties) {
        return MessageWindowChatMemory.builder()
                .maxMessages(properties.memory().maxMessages())
                .build();
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

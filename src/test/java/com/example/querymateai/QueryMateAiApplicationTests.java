package com.example.querymateai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "app.security.token-secret-key=test-token-secret",
        "spring.ai.mcp.client.enabled=false"
})
class QueryMateAiApplicationTests {

    @Test
    void contextLoads() {
    }
}

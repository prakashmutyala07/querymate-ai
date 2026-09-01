package com.example.sqlmcpchatopenrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SqlMcpChatOpenrouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlMcpChatOpenrouterApplication.class, args);
    }
}

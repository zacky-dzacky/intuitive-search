package com.bank.intuitivesearch;

import com.bank.intuitivesearch.config.SearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(SearchProperties.class)
@EnableAsync // SearchAuditWriter.record runs off the request thread
public class IntuitiveSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntuitiveSearchApplication.class, args);
    }
}

package com.bosch.demo.docgrounding.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableScheduling
public class WebClientConfig {
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /** Bare WebClient bean used by Jira/ServiceNow/AzureOpenAI pipeline services. */
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}

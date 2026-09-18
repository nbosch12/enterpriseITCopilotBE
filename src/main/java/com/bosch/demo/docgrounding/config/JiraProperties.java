package com.bosch.demo.docgrounding.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "jira")
public class JiraProperties {

    private String baseUrl;
    private String pat;
    private String projectKey;
    private String username;

}

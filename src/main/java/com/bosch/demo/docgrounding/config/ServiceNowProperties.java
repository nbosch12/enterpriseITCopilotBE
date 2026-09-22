package com.bosch.demo.docgrounding.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "servicenow")
public class ServiceNowProperties {

    private String baseUrl;
    private String username;
    private String password;
    private int    fetchLimit = 50;

}

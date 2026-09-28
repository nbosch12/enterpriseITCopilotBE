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

    /**
     * Zone used to decide which calendar day a ticket was created on.
     *
     * <p>Ticket timestamps have no zone of their own, so "created today" is only meaningful against
     * a stated zone. Defaults to Asia/Kolkata.</p>
     */
    private String timezone = "Asia/Kolkata";

}

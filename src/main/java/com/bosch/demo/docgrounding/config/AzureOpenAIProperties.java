package com.bosch.demo.docgrounding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "azure.openai")
public class AzureOpenAIProperties {

    private String endpoint;
    private String apiKey;
    private String deployment;

    public String getEndpoint()                      { return endpoint; }
    public void   setEndpoint(String endpoint)       { this.endpoint = endpoint; }

    public String getApiKey()                        { return apiKey; }
    public void   setApiKey(String apiKey)           { this.apiKey = apiKey; }

    public String getDeployment()                    { return deployment; }
    public void   setDeployment(String deployment)   { this.deployment = deployment; }
}

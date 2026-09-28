package com.bosch.demo.docgrounding.jira;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class JiraStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JiraStartupValidator.class);

    private final JiraClient jiraClient;

    public JiraStartupValidator(JiraClient jiraClient) {
        this.jiraClient = jiraClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Validating Jira connection...");
        boolean valid = jiraClient.validateToken();
        if (valid) {
            log.info("Jira connection validated successfully.");
        } else {
            log.warn("Jira connection validation FAILED. Check JIRA_PAT and JIRA_BASE_URL.");
        }
    }
}

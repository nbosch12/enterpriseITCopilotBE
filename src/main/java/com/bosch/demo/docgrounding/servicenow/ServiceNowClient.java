package com.bosch.demo.docgrounding.servicenow;

import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;

import java.util.List;

public interface ServiceNowClient {
    List<ServiceNowTicket> fetchNewTickets();
    void writeBackJiraKey(String sysId, String jiraKey);
}

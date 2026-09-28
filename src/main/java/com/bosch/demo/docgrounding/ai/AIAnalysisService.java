package com.bosch.demo.docgrounding.ai;

import com.bosch.demo.docgrounding.ai.model.JiraTicketPayload;
import com.bosch.demo.docgrounding.servicenow.model.ServiceNowTicket;

public interface AIAnalysisService {
    JiraTicketPayload analyze(ServiceNowTicket ticket);
}

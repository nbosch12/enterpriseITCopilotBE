package com.bosch.demo.docgrounding.servicenow.model;

import java.time.LocalDateTime;

public record ServiceNowTicket(
        String        id,
        String        sysId,
        String        title,
        String        description,
        String        category,
        String        priority,
        String        requestedBy,
        LocalDateTime createdAt
) {}

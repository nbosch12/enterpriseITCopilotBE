package com.bosch.demo.docgrounding.service;

import reactor.core.publisher.Mono;

public interface LogIncidentLlmClient {
    Mono<String> summarizeIncident(String prompt);
}


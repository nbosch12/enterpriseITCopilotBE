package com.bosch.demo.docgrounding.model;

import java.util.List;

public record VectorAskResponse(
        String answer,
        List<VectorMatch> matches,
        String rawModelResponse,
        String sessionId
) { }


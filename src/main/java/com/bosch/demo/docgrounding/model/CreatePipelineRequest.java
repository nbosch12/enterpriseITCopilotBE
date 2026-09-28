package com.bosch.demo.docgrounding.model;

import jakarta.validation.constraints.NotBlank;

public record CreatePipelineRequest(
        @NotBlank String includePath
) { }

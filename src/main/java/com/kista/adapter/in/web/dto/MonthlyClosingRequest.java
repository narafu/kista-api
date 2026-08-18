package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MonthlyClosingRequest(
        @Schema(description = "완료 여부")
        boolean completed
) {}

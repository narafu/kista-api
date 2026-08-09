package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AssetMonthlyCheckRequest(
        @Schema(description = "기록 완료 여부", example = "true")
        boolean completed
) {}

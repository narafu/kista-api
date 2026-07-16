package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EnumMeta(
        @Schema(description = "enum name() 값", example = "KIS")
        String code,        // enum name() 값
        @Schema(description = "한국어 표시 이름")
        String label,       // 한국어 표시 이름
        @Schema(description = "설명 (null 가능)")
        String description  // 설명 (null 가능)
) {}

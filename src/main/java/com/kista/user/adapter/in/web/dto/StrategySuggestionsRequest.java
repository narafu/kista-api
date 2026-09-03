package com.kista.user.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StrategySuggestionsRequest(
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 30) String> suggestions // 운용전략 추천 목록 (빈 배열 허용)
) {
}

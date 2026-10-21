package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Objects;

// DCA 등록·수정 요청 본문 — 규칙 1~10개. 구 단일 규칙 필드(direction·amount 등 최상위)는 제거됨(보내면 rules 누락으로 400)
public record DcaConfigRequest(
        @Schema(description = "DCA 규칙 목록 (1~10개, 입력 순서 보존)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<@Valid DcaRuleRequest> rules // 규칙 목록
) {
    public DcaStrategyConfig toDomain() {
        // @NotNull 대신 여기서 확인 — 검증 실패 형식([field: message]) 대신 사용자 문구 그대로 400
        if (rules == null) throw new IllegalArgumentException("rules는 필수입니다.");
        if (rules.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("규칙 항목이 비어 있습니다.");
        return new DcaStrategyConfig(rules.stream().map(DcaRuleRequest::toDomain).toList());
    }
}

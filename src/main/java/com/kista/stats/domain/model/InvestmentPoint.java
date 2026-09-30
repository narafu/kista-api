package com.kista.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 투자 누적지수 시점 — root stats 도메인 값(벤치마크 계산 입력). 내부 API 응답(contract.stats.InvestmentPointsResponse.InvestmentPointDto)은
// InvestmentPointsHttpAdapter가 이 타입으로 명시 매핑한다
public record InvestmentPoint(
        LocalDate baseDate,
        BigDecimal investmentIndexUsd,
        BigDecimal periodReturn
) {
}

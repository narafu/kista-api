package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

// 수입·소비·저축 한 유형의 기간 요약
public record FlowSummary(
        long total,                       // 기간 내 합계
        int count,                        // 기간 내 거래 건수
        @Nullable Long previousTotal,     // 월간만: 전월 전체 합
        @Nullable Long previousYearTotal, // 연간만: 전년 동기간 합
        long yearlyAverage,               // 선택 연도의 월평균(HALF_UP)
        @Nullable Long remaining,         // 수입 유형만: 수입 - 소비 - 저축
        @Nullable BigDecimal incomeRatio  // 수입 외 유형만: 같은 기간 수입 대비 비율(scale 4), 수입 0이면 null
) {}

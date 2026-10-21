package com.kista.tradingcore.trading.domain.model;

import java.time.LocalDate;
import java.util.List;

// DCA 회차 이력 한 페이지 — 주문·US 세션일을 합쳐 조립
public record DcaExecutionPage(List<Row> content, int page, int size, long totalElements) {

    public record Row(
            DcaExecution execution,  // 회차 판정 결과
            LocalDate usSessionDate, // 대응하는 US 세션일
            Order order              // ORDERED 회차의 주문 (없으면 null)
    ) {}
}

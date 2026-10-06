package com.kista.trading.application.service;

import com.kista.privacy.application.usecase.PrivacyTradeValidationUseCase;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.privacy.domain.model.PrivacyTradeValidationReport;
import com.kista.trading.application.port.output.TradingErrorReportPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// 개장·마감 배치 공용 PRIVACY 기준표 장전 점검 — "저장 시 경고, 장전 시 차단".
// 저장 때 경고로 통과한 기준표(예: 보유 중인데 매도 없음)도 배치 계획 직전 재점검에서 이슈가 있으면 기준표를 쓰지 않는다(null).
// 전략을 배치에서 빼지 않고 기준표만 비우는 이유: "기준표 미수신"과 같은 경로를 타 신규 PRIVACY 주문은 만들지 않되,
// 그날 이미 PLANNED·PLACED된 주문(바로 주문·재개 등)은 그대로 접수·체결 기록·리포트까지 이어지게 하기 위해서다.
@Component
@RequiredArgsConstructor
class PrivacyBaseGuard {

    private final PrivacyTradeValidationUseCase validationService;
    private final TradingErrorReportPort errorReportPort; // 가드 발동 관리자 알림 (출력 포트 경유)

    // 이슈가 없으면 base 그대로, 있으면 관리자 알림 후 null. batchLabel은 알림 문구용 배치 이름
    PrivacyTradeBase screen(PrivacyTradeBase base, LocalDate tradeDate, String batchLabel) {
        if (base == null) return null;
        try {
            PrivacyTradeValidationReport report = validationService.inspect(base);
            if (!report.hasIssues()) return base;
            errorReportPort.reportError(new IllegalStateException(
                    "[PRIVACY] " + batchLabel + " 장전 가드 발동 — 기준 매매표 이상으로 신규 PRIVACY 주문 생성 skip (거래일 " + tradeDate + "): "
                            + report.summary()));
        } catch (RuntimeException e) {
            // 점검 자체가 실패하면 안전하게 차단(fail-closed)
            errorReportPort.reportError(new IllegalStateException(
                    "[PRIVACY] " + batchLabel + " 기준 매매표 점검 실패 — 신규 PRIVACY 주문 생성 skip (거래일 " + tradeDate + ")", e));
        }
        return null;
    }

    // 알림 없는 조용한 판정 — 미리보기·수동 실행처럼 반복 호출되는 경로용. base 없음·이슈·점검 실패는 모두 false
    boolean usable(PrivacyTradeBase base) {
        if (base == null) return false;
        try {
            return !validationService.inspect(base).hasIssues();
        } catch (RuntimeException e) {
            return false;
        }
    }
}

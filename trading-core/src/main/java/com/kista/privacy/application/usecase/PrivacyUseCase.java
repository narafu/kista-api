package com.kista.privacy.application.usecase;

import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import com.kista.privacy.domain.model.PrivacyTradeSaveResult;

import java.time.LocalDate;
import java.util.List;

// PRIVACY 전략 FIDA 주문 처리 인터페이스
public interface PrivacyUseCase {
    // FIDA 주문 수신 처리 — 멱등 (같은 날짜+종목 동일 내용이면 200, 다른 내용이면 PrivacyTradeConflictException→409)
    PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command);

    // 관리자 조회 — release_date(KST 발행일 원본) >= fromReleaseDate 인 기준 매매표를 주문 명세 포함, 발행일 내림차순 반환
    List<PrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromReleaseDate);
}

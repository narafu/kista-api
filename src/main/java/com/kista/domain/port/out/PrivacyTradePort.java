package com.kista.domain.port.out;

import com.kista.domain.model.privacy.*;
import com.kista.domain.model.strategy.Strategy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PrivacyTradePort {
    // FIDA 수신 데이터를 기준 매매표(base) + 주문 명세(orders)로 저장
    // 동일 (tradeDate, ticker)가 이미 존재하면 비교 후 일치 시 created=false, 불일치 시 PrivacyTradeConflictException
    PrivacyTradeSaveResult saveBaseWithOrders(FidaOrderCommand command);

    // 전략 등록/수정 미리보기용 기준가 조회 — 현재 KST 일자 이후의 기준표만 사용
    Optional<PrivacyCurrentBase> findSeedPreviewBase();

    // 기존 호출부 호환용. 신규 코드는 용도에 맞는 findSeedPreviewBase/findTodayTrade를 직접 사용한다.
    default Optional<PrivacyCurrentBase> findCurrentBase() {
        return findSeedPreviewBase();
    }

    // 당일 기준 매매표 조회 — 미수신 일자면 empty
    Optional<PrivacyTradeBase> findTodayTrade(LocalDate today);

    // 관리자 조회 — trade_date(UTC) >= fromUtc 인 기준 매매표를 주문 명세 포함, 거래일 내림차순 반환
    List<PrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromUtc);

    // PRIVACY 전략이면 당일 기준 매매표 조회, 아니면 null — 단건 전략 전용 (배치는 hasPrivacy 분기 사용)
    default PrivacyTradeBase findBaseIfPrivacy(Strategy strategy, LocalDate today) {
        return strategy.isPrivacy() ? findTodayTrade(today).orElse(null) : null;
    }
}

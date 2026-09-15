package com.kista.privacy.application.port.output;

import com.kista.privacy.domain.model.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrivacyTradePort {
    // FIDA 수신 데이터를 기준 매매표(base) + 주문 명세(orders)로 저장
    // 동일 (tradeDate, ticker)가 이미 존재하면 비교 후 일치 시 created=false, 불일치 시 PrivacyTradeConflictException
    PrivacyTradeSaveResult saveBaseWithOrders(FidaOrderCommand command);

    // 전략 등록/수정 미리보기용 기준가 조회 — 현재 KST 일자 이후의 기준표만 사용
    Optional<PrivacyCurrentBase> findSeedPreviewBase();

    // 당일 기준 매매표 조회 — 미수신 일자면 empty
    Optional<PrivacyTradeBase> findTodayTrade(LocalDate today);

    // 관리자 조회 — release_date(KST 발행일 원본) >= fromReleaseDate 인 기준 매매표를 주문 명세 포함, 발행일 내림차순 반환
    List<PrivacyTradeBaseView> findBasesFromTradeDate(LocalDate fromReleaseDate);

    // 관리자 단건 조회 — 없으면 NoSuchElementException(→404)
    PrivacyTradeBaseView findByIdOrThrow(UUID id);

    // 관리자 수동 보정 — 마스터 필드(기준가·실현손익·평단가·보유수량) 전체 교체
    PrivacyTradeBaseView updateBase(UUID id, PrivacyBaseUpdateCommand command);

    // 관리자 수동 보정 — 개별 주문 가격·수량 교체 (BUY 주문의 quantity=null은 IllegalArgumentException)
    PrivacyTradeBaseView updateOrder(UUID baseId, UUID orderId, PrivacyOrderUpdateCommand command);
}

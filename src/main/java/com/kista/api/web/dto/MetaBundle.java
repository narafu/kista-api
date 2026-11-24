package com.kista.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

public record MetaBundle(
        @Schema(description = "전략 타입 목록")
        List<StrategyTypeMeta> strategyTypes,  // 전략 타입 목록
        @Schema(description = "티커 목록")
        List<TickerMeta> tickers,              // 티커 목록
        @Schema(description = "증권사 목록")
        List<BrokerMeta> brokers,               // 증권사 목록
        @Schema(description = "전략 상태 목록")
        List<EnumMeta> strategyStatuses,       // 전략 상태 목록
        @Schema(description = "연속 사이클 정책 목록")
        List<EnumMeta> cycleSeedTypes,         // 연속 사이클 정책 목록
        @Schema(description = "자산군 목록")
        List<EnumMeta> assetClasses,           // 자산군 목록
        @Schema(description = "시장 목록")
        List<EnumMeta> markets,                // 시장 목록
        @Schema(description = "재무 계좌 유형 목록")
        List<EnumMeta> financeAccountTypes,    // 재무 계좌 유형 목록
        @Schema(description = "재무 카테고리 유형 목록")
        List<EnumMeta> financeCategoryTypes,   // 재무 카테고리 유형 목록
        @Schema(description = "주문 유형 목록")
        List<EnumMeta> orderTypes,             // 주문 유형 목록 (상수 순서 = UI 필터 순서)
        @Schema(description = "주문 방향 목록")
        List<EnumMeta> orderDirections,        // 주문 방향 목록
        @Schema(description = "주문 상태 목록")
        List<EnumMeta> orderStatuses,          // 주문 상태 목록
        @Schema(description = "주문 접수 시점 목록")
        List<EnumMeta> orderTimings,           // 주문 접수 시점 목록
        @Schema(description = "DCA 실행 주기 목록")
        List<EnumMeta> dcaFrequencies,         // DCA 실행 주기 목록
        @Schema(description = "DCA 조건 연산자 목록")
        List<EnumMeta> dcaOperators,           // DCA 조건 연산자 목록
        @Schema(description = "DCA 회차 미주문 사유 목록")
        List<EnumMeta> dcaSkipReasons,         // DCA 미주문 사유 목록
        @Schema(description = "DCA 회차 판정 상태 목록")
        List<EnumMeta> dcaExecutionStatuses,   // DCA 회차 판정 상태 목록 (주문됨·건너뜀)
        @Schema(description = "DCA 규칙 규모 유형 목록")
        List<EnumMeta> dcaSizingTypes,         // DCA 규모 유형 목록 (고정 금액·보유 비율)
        @Schema(description = "DCA 규칙 부족 정책 목록")
        List<EnumMeta> dcaShortfallPolicies,   // DCA 가용 부족 정책 목록
        @Schema(description = "DCA 조건 지표 목록")
        List<DcaIndicatorMeta> dcaIndicators,  // DCA 조건 지표 목록 (SMA 기간 옵션 포함)
        @Schema(description = "계정 상태 목록")
        List<EnumMeta> userStatuses,           // 계정 상태 목록
        @Schema(description = "사용자 권한 목록")
        List<EnumMeta> userRoles,              // 사용자 권한 목록
        @Schema(description = "VR 정기 입출금 방향 목록")
        List<EnumMeta> recurringModes,         // VR 정기 입출금 방향 목록
        @Schema(description = "통화 목록")
        List<EnumMeta> currencies,             // 통화 목록
        @Schema(description = "API 키 만료일 출처 목록")
        List<EnumMeta> apiKeyExpirySources,    // API 키 만료일 출처 목록
        @Schema(description = "알림 채널 목록")
        List<EnumMeta> notificationChannels,   // 알림 채널 목록
        @Schema(description = "알림 종류 목록")
        List<EnumMeta> notificationTypes,      // 알림 종류 목록
        @Schema(description = "시장 주문 접수 세션 목록")
        List<EnumMeta> marketSessions,         // 시장 주문 접수 세션 목록
        @Schema(description = "벤치마크 자산 종류 목록")
        List<EnumMeta> benchmarkAssetTypes,    // 벤치마크 자산 종류 목록
        @Schema(description = "ETF 벤치마크 심볼 목록 (description=ETF 정식 명칭)")
        List<EnumMeta> etfBenchmarkSymbols,    // ETF 벤치마크 심볼 목록
        @Schema(description = "가계부 그룹 초대 상태 목록")
        List<EnumMeta> financeInvitationStatuses, // 가계부 그룹 초대 상태 목록
        @Schema(description = "다음 주문 미리보기 skip 사유 목록")
        List<EnumMeta> nextOrdersSkipReasons,  // 다음 주문 미리보기 skip 사유 목록
        @Schema(description = "가입 승인 필수 여부 (/api/runtime-config auth.approvalRequired와 같은 값)")
        boolean approvalRequired,              // 가입 승인 필수 여부
        @Schema(description = "ETF 벤치마크 비교 자산 허용값·기본값")
        BenchmarkEtfMeta benchmarkEtf          // ETF 벤치마크 설정
) {
    // 컴포넌트 이름 → 값 맵으로 조립한다 — 같은 타입 List<EnumMeta> 위치 인자가 늘어도 자리 바뀜이 없고,
    // 값이 빠지거나 모르는 이름이 있으면 즉시 실패해 MetaEnumRegistry 필드 이름과 이 레코드가 어긋나는 것을 막는다.
    public static MetaBundle assemble(Map<String, Object> values) {
        var components = MetaBundle.class.getRecordComponents();
        if (values.size() != components.length) {
            throw new IllegalStateException("메타 번들 필드가 맞지 않습니다: " + values.keySet());
        }
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            args[i] = values.get(components[i].getName());
            types[i] = components[i].getType();
            if (args[i] == null) {
                throw new IllegalStateException("메타 번들 값이 없습니다: " + components[i].getName());
            }
        }
        try {
            return MetaBundle.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("메타 번들 조립 실패", e);
        }
    }
}

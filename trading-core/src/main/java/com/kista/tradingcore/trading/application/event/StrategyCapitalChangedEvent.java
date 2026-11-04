package com.kista.tradingcore.trading.application.event;

import java.util.UUID;

// 전략 자본 변경 이벤트(발행: 투자금 추가·인출, 시드 수정, VR 재설정, 수동 체결 보정, 재개, 삭제) — stats가 사용자별 summary/equity-curve 캐시 무효화용으로 구독한다
public record StrategyCapitalChangedEvent(UUID userId) {}

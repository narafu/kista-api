package com.kista.sharedkernel;

// 사용자별 알림 on/off 단위 — user(설정 저장)·trading/finance(발송 전 활성 여부 확인)가 공유.
// 상수명 byte-identical 유지 필수 — user_notification_prefs.type 컬럼값과 직결.
public enum NotificationType {
    TRADING_ALERT,   // 매매 리포트·오류 알림
    MARKET_ALERT,    // 개장/마감 등 시장 이벤트 알림
    FINANCE_REMINDER // 가계부 등록 리마인더
}

package com.kista.stats.application.event;

// KB Land 벤치마크(주택 5분위 매매가·매매가격지수) 수집 실패 알림 — 관리자 전용(NotifyPort.notifyError), userId 없음.
// stats→notify 직접 호출을 끊기 위한 이벤트(market FearGreedFetchFailedEvent / privacy PrivacyAlertRaisedEvent와 동일 패턴).
// Exception 자체는 EPR 직렬화 부적합(스택트레이스·cause 체인)이라 message(String)만 담는다 —
// 소비처(notify)가 e.getMessage() 문자열만 쓴다는 걸 두 서비스의 기존 notifyError(e) 호출로 확인했다.
public record StatsAlertRaisedEvent(String message) {
}

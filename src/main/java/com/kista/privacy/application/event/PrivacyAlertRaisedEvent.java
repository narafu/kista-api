package com.kista.privacy.application.event;

// FIDA 기준 매매표 검증 경보 — privacy→notify 직접 호출을 끊기 위한 이벤트(market FearGreedFetchFailedEvent와 동일 패턴).
// severity로 관리자 알림 채널 구분: BLOCKING=저장 차단(NotifyPort.notifyError), WARNING=경고 후 저장 진행(notifyInfo).
// Exception 자체는 EPR 직렬화 부적합이라 message(String)만 담는다 — 소비처(notify)가 문자열만 쓴다.
public record PrivacyAlertRaisedEvent(Severity severity, String message) {

    // 경보 심각도 — 발행 측(PrivacyService)의 분기와 소비 측(PrivacyAlertNotifier)의 라우팅에 함께 쓰인다
    public enum Severity { BLOCKING, WARNING }
}

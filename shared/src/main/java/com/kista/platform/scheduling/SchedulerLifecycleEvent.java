package com.kista.platform.scheduling;

// 스케쥴러 공통 골격의 생명주기 알림 — SchedulerJobRunner→notify 직접 호출을 끊기 위한 이벤트
// (market FearGreedFetchFailedEvent / privacy PrivacyAlertRaisedEvent와 동일 패턴, EPR 추적).
// Exception 자체는 EPR 직렬화 부적합(스택트레이스·cause 체인)이라 errorMessage(String)만 담는다 —
// 전체 스택은 SchedulerJobRunner의 log.error가 이미 남긴다.
public record SchedulerLifecycleEvent(String jobName, Phase phase, String errorMessage) {

    // 스케쥴러 실행 단계 — 발행 측(SchedulerJobRunner) 분기와 소비 측(SchedulerNotifier) 라우팅에 함께 쓰인다
    public enum Phase { STARTED, COMPLETED, FAILED }

    public static SchedulerLifecycleEvent started(String jobName) {
        return new SchedulerLifecycleEvent(jobName, Phase.STARTED, null);
    }

    public static SchedulerLifecycleEvent completed(String jobName) {
        return new SchedulerLifecycleEvent(jobName, Phase.COMPLETED, null);
    }

    public static SchedulerLifecycleEvent failed(String jobName, Throwable error) {
        return new SchedulerLifecycleEvent(jobName, Phase.FAILED, error.getMessage());
    }
}

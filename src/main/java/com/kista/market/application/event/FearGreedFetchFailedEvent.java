package com.kista.market.application.event;

// 공포탐욕지수(CNN/CRYPTO) 수집 실패 알림 — 관리자 전용(NotifyPort.notifyError), userId 없음.
// Exception 자체는 EPR 직렬화 부적합(스택트레이스·cause 체인)해 message만 담는다 — 소비처(notify)가
// e.getMessage()만 쓴다는 걸 FearGreedService의 기존 log.error 호출로 확인했다.
public record FearGreedFetchFailedEvent(String message) {}

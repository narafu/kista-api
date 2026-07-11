package com.kista.domain.port.out;

// 스케쥴러 정상 실행 신호 — 외부 감시(healthchecks.io)가 시간 내 신호 없으면 알림 (dead-man's switch)
public interface HeartbeatPort {
    void pingOpen();  // 개장 스케쥴러 실행 완료 신호
    void pingClose(); // 마감 스케쥴러 실행 완료 신호
}

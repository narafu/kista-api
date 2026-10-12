package com.kista.tradingstats.domain.model;

// 사이클 성과 목록의 진행 상태 필터 (미지정이면 전체)
public enum CycleStatusFilter {
    ACTIVE, // 미종료 사이클
    CLOSED; // 종료된 사이클

    public boolean matches(boolean closed) {
        return (this == CLOSED) == closed;
    }
}

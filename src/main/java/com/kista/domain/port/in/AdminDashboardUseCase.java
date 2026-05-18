package com.kista.domain.port.in;

import com.kista.domain.model.AdminStats;

public interface AdminDashboardUseCase {
    AdminStats getStats(); // 사용자 현황 + 계좌 수 통계
}

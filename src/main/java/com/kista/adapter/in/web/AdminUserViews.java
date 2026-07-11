package com.kista.adapter.in.web;

import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.port.in.AdminUserUseCase;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 관리자 응답 조립용 사용자 뷰 맵 빌드 헬퍼 — 컨트롤러 3곳 중복 제거
final class AdminUserViews {

    private AdminUserViews() {}

    // 전체 사용자를 id 기준 Map으로 변환 (닉네임 등 표시 정보 결합용)
    static Map<UUID, AdminUserView> mapById(AdminUserUseCase adminUser) {
        return adminUser.listAll(null, null).stream()
                .collect(Collectors.toMap(AdminUserView::id, Function.identity()));
    }
}

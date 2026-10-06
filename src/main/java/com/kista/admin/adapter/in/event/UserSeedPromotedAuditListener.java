package com.kista.admin.adapter.in.event;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.sharedkernel.UserRole;
import com.kista.user.application.event.UserSeedPromotedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

// ADMIN_KAKAO_IDS seed 자동 승격을 관리자 화면 역할 변경과 같은 감사 로그(USER_ROLE_CHANGE)로 남긴다 — 행위자는 사람이 아니라 seed
@Component
@RequiredArgsConstructor
class UserSeedPromotedAuditListener {

    private static final String SEED_ACTOR = "ADMIN_SEED"; // 환경변수 seed 경로 행위자 표기 (admin_id는 null)

    private final AuditLogPort auditLogPort; // 감사 로그 기록

    @TransactionalEventListener
    void on(UserSeedPromotedEvent event) {
        auditLogPort.log(null, "USER_ROLE_CHANGE", "USER", event.userId(),
                Map.of("newRole", UserRole.ADMIN.name(), "actor", SEED_ACTOR));
    }
}

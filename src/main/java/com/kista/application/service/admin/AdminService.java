package com.kista.application.service.admin;

import com.kista.application.service.user.UserCascadeDeleter;
import com.kista.common.TimeZones;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.auth.TokenConstants;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminUserUseCase;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.AdminUserViewPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.BlacklistPort;
import com.kista.domain.port.out.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AdminService implements AdminUserUseCase {

    private final UserPort userPort;
    private final AdminUserViewPort adminUserViewPort;   // 관리자 화면 전용 read-model
    private final UserCascadeDeleter userCascadeDeleter;
    private final UserUseCase userUseCase; // 승인/거절 위임 (텔레그램 알림 + SSE 포함)
    private final AuditLogPort auditLogPort;             // 감사 로그 기록
    private final BlacklistPort blacklistPort;           // role 변경 시 stale AT 무효화 기록

    @Override
    @Transactional(readOnly = true)
    public List<AdminUserView> listAll(LocalDate from, LocalDate to) {
        return filterByDate(adminUserViewPort.findAll(), from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminUserView> listByStatus(User.UserStatus status, LocalDate from, LocalDate to) {
        return filterByDate(adminUserViewPort.findAllByStatus(status), from, to);
    }

    private List<AdminUserView> filterByDate(List<AdminUserView> views, LocalDate from, LocalDate to) {
        if (from == null && to == null) return views;
        return views.stream()
                .filter(v -> {
                    if (v.createdAt() == null) return true;
                    LocalDate d = v.createdAt().atZone(TimeZones.KST).toLocalDate();
                    return (from == null || !d.isBefore(from))
                        && (to   == null || !d.isAfter(to));
                })
                .toList();
    }

    @Override
    public void approveUser(UUID adminId, UUID targetUserId) {
        // UserUseCase 위임 (텔레그램 알림 + SSE 포함)
        userUseCase.approve(targetUserId);
        log.info("관리자 사용자 승인: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_APPROVE", "USER", targetUserId, null);
    }

    @Override
    public void rejectUser(UUID adminId, UUID targetUserId) {
        // UserUseCase 위임 (텔레그램 알림 + SSE 포함)
        userUseCase.reject(targetUserId);
        log.info("관리자 사용자 거절: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_REJECT", "USER", targetUserId, null);
    }

    @Override
    public void changeRole(UUID adminId, UUID targetUserId, User.UserRole role) {
        if (role == User.UserRole.USER) {
            // 자기 자신 강등 방지
            if (adminId.equals(targetUserId)) {
                throw new IllegalArgumentException("자기 자신의 역할을 강등할 수 없습니다");
            }
            // 마지막 ADMIN 강등 방지
            if (userPort.countByRole(User.UserRole.ADMIN) <= 1) {
                throw new IllegalStateException("최소 1명의 관리자가 존재해야 합니다");
            }
        }
        User user = userPort.findByIdOrThrow(targetUserId);
        userPort.save(user.withRole(role));
        // 기존 AT 무효화 — 변경 시각 이전 발급 토큰은 JwtAuthFilter가 401 처리 (refresh로 새 role AT 발급)
        blacklistPort.markRoleChanged(targetUserId, Instant.now(), TokenConstants.AT_TTL);
        log.info("관리자 역할 변경: adminId={}, targetUserId={}, role={}", adminId, targetUserId, role);
        auditLogPort.log(adminId, "USER_ROLE_CHANGE", "USER", targetUserId,
                Map.of("newRole", role.name()));
    }

    @Override
    public void deleteUser(UUID adminId, UUID targetUserId) {
        userPort.findByIdOrThrow(targetUserId); // 존재 확인
        userCascadeDeleter.deleteCascade(targetUserId);
        log.info("관리자 사용자 삭제: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_DELETE", "USER", targetUserId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminUserView> findUser(UUID userId) {
        // 전체 조회 후 ID 필터 — AdminUserViewPort에 단건 조회 미지원
        return adminUserViewPort.findAll().stream()
                .filter(v -> userId.equals(v.id()))
                .findFirst();
    }
}

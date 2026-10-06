package com.kista.admin.application.service;

import com.kista.sharedkernel.TimeZones;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSummary;
import com.kista.admin.application.usecase.AdminUserUseCase;
import com.kista.user.application.usecase.UserUseCase;
import com.kista.user.application.port.output.UserSummaryPort;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.user.application.port.output.UserPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AdminService implements AdminUserUseCase {

    private final UserPort userPort;
    private final UserSummaryPort userSummaryPort;   // 관리자 화면 전용 read-model
    private final UserUseCase userUseCase; // 승인/거절/탈퇴 위임 (텔레그램 알림 + SSE 포함)
    private final AuditLogPort auditLogPort;             // 감사 로그 기록

    private static final String TELEGRAM_ACTOR = "TELEGRAM_BOT"; // 텔레그램 인라인 버튼 경로 행위자 표기

    @Override
    @Transactional(readOnly = true)
    public List<UserSummary> listAll(LocalDate from, LocalDate to) {
        return filterByDate(userSummaryPort.findAll(), from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummary> listByStatus(UserStatus status, LocalDate from, LocalDate to) {
        return filterByDate(userSummaryPort.findAllByStatus(status), from, to);
    }

    private List<UserSummary> filterByDate(List<UserSummary> views, LocalDate from, LocalDate to) {
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
    public void rejectUser(UUID adminId, UUID targetUserId, String reason) {
        // UserUseCase 위임 (텔레그램 알림 + SSE 포함, reason 정규화는 UserService 책임)
        userUseCase.reject(targetUserId, reason);
        log.info("관리자 사용자 거절: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_REJECT", "USER", targetUserId, Map.of("reason", reason == null ? "" : reason));
    }

    @Override
    public void approveUserByTelegram(String chatId, UUID targetUserId) {
        userUseCase.approve(targetUserId);
        log.info("텔레그램 관리자 승인: chatId={}, targetUserId={}", chatId, targetUserId);
        auditLogPort.log(resolveTelegramAdmin(chatId), "USER_APPROVE", "USER", targetUserId, telegramPayload(chatId));
    }

    @Override
    public void rejectUserByTelegram(String chatId, UUID targetUserId) {
        userUseCase.reject(targetUserId, null); // 텔레그램 인라인 버튼은 사유 입력 UI 없음
        log.info("텔레그램 관리자 거절: chatId={}, targetUserId={}", chatId, targetUserId);
        Map<String, Object> payload = new HashMap<>(telegramPayload(chatId));
        payload.put("reason", "");
        auditLogPort.log(resolveTelegramAdmin(chatId), "USER_REJECT", "USER", targetUserId, payload);
    }

    // chatId에 연결된 ADMIN 사용자 — 없거나 ADMIN이 아니면 null(admin_id nullable, 행위자는 payload.actor로 식별)
    private UUID resolveTelegramAdmin(String chatId) {
        return userUseCase.findUserIdByTelegramChatId(chatId)
                .flatMap(userPort::findById)
                .filter(u -> u.role() == UserRole.ADMIN)
                .map(User::id)
                .orElse(null);
    }

    // 텔레그램 경로 감사 payload — 채널·chatId 고정 기록
    private Map<String, Object> telegramPayload(String chatId) {
        return Map.of("actor", TELEGRAM_ACTOR, "chatId", chatId);
    }

    @Override
    public void changeRole(UUID adminId, UUID targetUserId, UserRole role) {
        if (role == UserRole.USER) {
            // 자기 자신 강등 방지
            if (adminId.equals(targetUserId)) {
                throw new IllegalArgumentException("자기 자신의 역할을 강등할 수 없습니다");
            }
            // 마지막 ADMIN 강등 방지 — 대상이 지금 ADMIN일 때만(이미 USER인 대상의 USER 지정은 관리자 수를 줄이지 않는다)
            if (userPort.findByIdOrThrow(targetUserId).role() == UserRole.ADMIN && userPort.countByRole(UserRole.ADMIN) <= 1) {
                throw new IllegalStateException("최소 1명의 관리자가 존재해야 합니다");
            }
        }
        // 역할 저장 + 기존 AT 무효화는 user 모듈이 캡슐화한다
        userUseCase.changeRole(targetUserId, role);
        log.info("관리자 역할 변경: adminId={}, targetUserId={}, role={}", adminId, targetUserId, role);
        auditLogPort.log(adminId, "USER_ROLE_CHANGE", "USER", targetUserId,
                Map.of("newRole", role.name()));
    }

    @Override
    public void deleteUser(UUID adminId, UUID targetUserId) {
        userPort.findByIdOrThrow(targetUserId); // 존재 확인
        userUseCase.deleteMe(targetUserId);
        log.info("관리자 사용자 삭제: adminId={}, targetUserId={}", adminId, targetUserId);
        auditLogPort.log(adminId, "USER_DELETE", "USER", targetUserId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findUser(UUID userId) {
        // 단건 조회 — 전체 풀스캔 대신 ID 기반 직접 조회
        return userSummaryPort.findById(userId);
    }
}

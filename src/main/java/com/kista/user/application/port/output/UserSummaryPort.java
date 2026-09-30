package com.kista.user.application.port.output;

import com.kista.user.domain.model.UserSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kista.sharedkernel.UserStatus;

public interface UserSummaryPort {
    // 전체 사용자 요약 목록(관리자 화면용 읽기 투영)
    List<UserSummary> findAll();
    // 상태별 사용자 요약 목록
    List<UserSummary> findAllByStatus(UserStatus status);
    // 단건 사용자 요약 — 없으면 empty
    Optional<UserSummary> findById(UUID userId);
}

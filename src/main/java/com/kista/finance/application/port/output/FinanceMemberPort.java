package com.kista.finance.application.port.output;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// finance가 필요로 하는 사용자 정보의 전부 — user 모듈 의존은 이 포트의 어댑터 1곳으로 한정
public interface FinanceMemberPort {

    // ACTIVE 상태 사용자 id 목록 — 리마인더 순회용
    List<UUID> activeMemberIds();

    // 사용자 닉네임 — 탈퇴 등으로 사용자가 없으면 empty
    Optional<String> nicknameOf(UUID userId);
}

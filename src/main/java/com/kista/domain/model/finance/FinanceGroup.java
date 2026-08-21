package com.kista.domain.model.finance;

import java.time.Instant;
import java.util.UUID;

// 그룹은 오직 초대로만 생성된다(1인 1그룹) — personal 자동생성 그룹 개념은 폐기됨.
// 데이터 소유는 개별 리소스의 (userId, groupId) 이중축이 담당하고, 이 record는 순수 멤버십 단위다.
public record FinanceGroup(
        UUID id,           // PK
        UUID ownerUserId,  // FK → users.id, 그룹 생성자
        String name,       // 표시명
        Instant createdAt  // DB created_at, 신규 등록 시 null
) {
    public enum MemberRole {
        OWNER, MEMBER
    }
}

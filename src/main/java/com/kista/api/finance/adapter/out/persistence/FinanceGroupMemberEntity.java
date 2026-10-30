package com.kista.api.finance.adapter.out.persistence;

import com.kista.shared.platform.persistence.BaseAuditEntity;
import com.kista.api.finance.domain.model.FinanceGroup;
import com.kista.api.finance.domain.model.FinanceGroupMember;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_group_members", schema = "finance")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceGroupMemberEntity extends BaseAuditEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", nullable = false, columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FinanceGroup.MemberRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 그룹 탈퇴로 소프트 삭제됨

    static FinanceGroupMember toDomain(FinanceGroupMemberEntity e) {
        return new FinanceGroupMember(e.id, e.groupId, e.userId, e.role, e.joinedAt, e.getCreatedAt());
    }
}

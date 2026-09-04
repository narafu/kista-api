package com.kista.finance.adapter.out.persistence;

import com.kista.platform.persistence.BaseAuditEntity;
import com.kista.finance.domain.model.FinanceGroupInvitation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_group_invitations", schema = "finance")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceGroupInvitationEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", nullable = false, columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id

    @Column(name = "invited_by", nullable = false, columnDefinition = "UUID")
    private UUID invitedBy;               // FK → users.id, 초대한 사람

    @Column(name = "invitee_user_id", columnDefinition = "UUID")
    private UUID inviteeUserId;           // FK → users.id, 코드 수락 전에는 NULL

    @Column(nullable = false, length = 16)
    private String code;                  // 공유용 초대 코드 (URL-safe)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FinanceGroupInvitation.Status status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    static FinanceGroupInvitationEntity fromModel(FinanceGroupInvitation i) {
        FinanceGroupInvitationEntity e = new FinanceGroupInvitationEntity();
        e.id = i.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = i.groupId();
        e.invitedBy = i.invitedBy();
        e.inviteeUserId = i.inviteeUserId();
        e.code = i.code();
        e.status = i.status();
        e.expiresAt = i.expiresAt();
        return e;
    }

    static FinanceGroupInvitation toDomain(FinanceGroupInvitationEntity e) {
        return new FinanceGroupInvitation(
                e.id, e.groupId, e.invitedBy, e.inviteeUserId, e.code, e.status, e.expiresAt, e.getCreatedAt());
    }
}

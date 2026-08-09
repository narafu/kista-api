package com.kista.adapter.out.persistence.asset;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

// deleted_at 없음 — 소프트 삭제 대상이 아닌 단순 상태 레코드. 탈퇴 시 하드 삭제(deleteByUserId)로 정리된다.
@Entity
@Table(name = "asset_monthly_checks")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AssetMonthlyCheckEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(nullable = false, length = 7)
    private String month;                 // 'YYYY-MM'

    @Column(nullable = false)
    private boolean completed;
}

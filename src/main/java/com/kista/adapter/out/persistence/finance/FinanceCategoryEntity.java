package com.kista.adapter.out.persistence.finance;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.finance.FinanceCategory;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// @SQLRestriction("deleted_at IS NULL")를 의도적으로 붙이지 않는다 (§4.2).
// 카테고리는 읽기 모드가 둘: (A) 폼 선택지 = 활성 행만 → findSelectableByGroup이 명시적으로 필터링.
// (B) 과거 거래·자산 기록의 카테고리 이름 렌더링 = 삭제된 행도 조회돼야 함 → findById는 무필터.
// 클래스 레벨 @SQLRestriction을 걸면 (B)가 죽어 소프트 삭제된 카테고리를 참조하는 거래 목록 조회가 깨진다.
@Entity
@Table(name = "finance_categories")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FinanceCategoryEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "group_id", columnDefinition = "UUID")
    private UUID groupId;                 // FK → finance_groups.id, NULL이면 시스템 전역 카테고리

    @Column(name = "parent_id", columnDefinition = "UUID")
    private UUID parentId;                // FK → 자기참조, L1이면 NULL

    @Column(name = "created_by", columnDefinition = "UUID")
    private UUID createdBy;               // FK → users.id, 시스템 카테고리는 NULL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FinanceCategory.Type type;    // 생성 후 불변

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨 (자식 동반 삭제)

    static FinanceCategoryEntity fromModel(FinanceCategory c) {
        FinanceCategoryEntity e = new FinanceCategoryEntity();
        e.id = c.id(); // null이면 @GeneratedValue가 UUID 생성
        e.groupId = c.groupId();
        e.parentId = c.parentId();
        e.createdBy = c.createdBy();
        e.type = c.type();
        e.name = c.name();
        e.sortOrder = c.sortOrder();
        return e;
    }

    static FinanceCategory toDomain(FinanceCategoryEntity e) {
        return new FinanceCategory(
                e.id, e.groupId, e.parentId, e.createdBy, e.type, e.name, e.sortOrder, e.getCreatedAt());
    }
}

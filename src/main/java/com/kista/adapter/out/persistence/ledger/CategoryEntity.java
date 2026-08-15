package com.kista.adapter.out.persistence.ledger;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.ledger.Category;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_categories")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CategoryEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(name = "parent_id", columnDefinition = "UUID")
    private UUID parentId;                // FK → ledger_categories.id 자기참조, 최상위면 null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category.Type type;           // 생성 후 불변

    @Column(nullable = false, length = 50)
    private String name;                  // 자유 입력

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}

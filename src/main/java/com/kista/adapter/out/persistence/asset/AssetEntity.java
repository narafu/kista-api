package com.kista.adapter.out.persistence.asset;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.asset.AssetCategory;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "assets")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AssetEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetCategory category;

    @Column(nullable = false, length = 100)
    private String subcategory;           // 자유 입력

    @Column(length = 100)
    private String institution;           // 자유 입력, null 허용

    @Column(name = "asset_class", nullable = false, length = 50)
    private String assetClass;            // 자유 입력

    @Column(length = 50)
    private String strategy;              // 자유 입력, null 허용

    @Column(nullable = false)
    private long amount;                  // 원화 정수

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}

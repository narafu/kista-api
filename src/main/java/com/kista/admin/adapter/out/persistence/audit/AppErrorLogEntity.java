package com.kista.admin.adapter.out.persistence.audit;

import com.kista.platform.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "app_error_logs", schema = "public")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor
@AllArgsConstructor
class AppErrorLogEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false)
    private UUID id;

    @Column(name = "error_type", nullable = false, length = 255)
    private String errorType; // 예외 클래스 단순명

    @Column(columnDefinition = "TEXT")
    private String message; // e.getMessage()

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace; // 전체 스택트레이스

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> context; // 발생 위치 메타 (Hibernate가 JSON 컬럼에 직접 매핑 — Jackson2가 classpath에 있어 그쪽을 사용, 값은 항상 문자열이라 영향 없음)

    @Column(name = "deleted_at")
    private Instant deletedAt; // 소프트 삭제 일시 (null = 활성)
}

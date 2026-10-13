package com.kista.admin.adapter.out.persistence.audit;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_logs", schema = "public")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(updatable = false)
    private UUID id;

    @Column(name = "admin_id", nullable = true)
    private UUID adminId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id")
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload; // 관리자 액션 상세 (Hibernate가 JSON 컬럼에 직접 매핑 — Jackson2가 classpath에 있어 그쪽을 사용, 값은 항상 String/Boolean/Integer/Map이라 영향 없음)
}

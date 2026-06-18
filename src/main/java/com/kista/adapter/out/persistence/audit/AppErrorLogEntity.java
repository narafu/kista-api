package com.kista.adapter.out.persistence.audit;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "app_error_logs")
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

    @Column(columnDefinition = "jsonb")
    private String context; // 발생 위치 메타 JSON
}

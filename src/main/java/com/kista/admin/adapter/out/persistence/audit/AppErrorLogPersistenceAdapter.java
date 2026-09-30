package com.kista.admin.adapter.out.persistence.audit;

import com.kista.admin.domain.model.AppErrorLog;
import com.kista.admin.application.port.output.AppErrorLogPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Component
class AppErrorLogPersistenceAdapter implements AppErrorLogPort {

    private final AppErrorLogJpaRepository repo; // app_error_logs 테이블 JPA 저장소
    private final TransactionTemplate requiresNewTx; // 저장 전용 독립 트랜잭션(REQUIRES_NEW)

    private static final int MAX_STACK_LINES = 30;

    // AppErrorLogJpaRepository가 package-private이라 생성자도 package-private
    AppErrorLogPersistenceAdapter(AppErrorLogJpaRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 항상 자기 트랜잭션에서 커밋 — AlertNotifier 등 @TransactionalEventListener(AFTER_COMMIT) 리스너 안에서 호출되면
    // 호출 시점 트랜잭션은 이미 커밋된 뒤라 repo.save()가 flush되지 않고 조용히 유실되므로 REQUIRES_NEW로 분리.
    // @Transactional 대신 TransactionTemplate을 try 안에서 쓰는 이유: 커밋 시점 실패까지 이 메서드가 삼켜야
    // "저장 실패를 던지지 않는다"는 포트 계약이 프록시 커밋 단계에서도 지켜진다
    @Override
    public void save(String errorType, String message, String stackTrace, Map<String, String> context) {
        // 저장 실패가 호출부(클라이언트/내부 API 컨트롤러·이벤트 리스너)로 전파되지 않도록 이 메서드 계약 자체가 격리를 보장
        try {
            requiresNewTx.executeWithoutResult(status ->
                    repo.save(buildEntity(errorType, message, truncateStackTrace(stackTrace), context)));
        } catch (Exception saveEx) {
            log.warn("오류 로그 저장 실패: {}", saveEx.getMessage());
        }
    }

    // 스택트레이스 첫 30줄만 저장 (프레임워크 내부 라인 제외)
    private static String truncateStackTrace(String fullTrace) {
        if (fullTrace == null) return null;
        return fullTrace.lines()
                .limit(MAX_STACK_LINES)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private AppErrorLogEntity buildEntity(String errorType, String message, String stackTrace, Map<String, String> context) {
        return new AppErrorLogEntity(
                null,
                errorType,
                message,
                stackTrace,
                context != null ? context : Map.of(), // Hibernate가 Map을 jsonb로 직접 매핑
                null // deletedAt
        );
    }

    @Override
    public List<AppErrorLog> findRecent(int limit, Instant from, Instant to) {
        return repo.findTopNByCreatedAtBetween(from, to, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void softDelete(UUID id) {
        AppErrorLogEntity entity = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("앱 오류 로그를 찾을 수 없습니다: " + id));
        entity.setDeletedAt(Instant.now());
        repo.save(entity);
    }

    // 엔티티 → 도메인 record 변환 (context는 Hibernate가 이미 Map으로 매핑)
    private AppErrorLog toDomain(AppErrorLogEntity entity) {
        return new AppErrorLog(
                entity.getId(),
                entity.getErrorType(),
                entity.getMessage(),
                entity.getStackTrace(),
                entity.getContext(),
                entity.getCreatedAt()
        );
    }
}

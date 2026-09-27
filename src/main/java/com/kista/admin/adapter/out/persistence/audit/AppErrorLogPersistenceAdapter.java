package com.kista.admin.adapter.out.persistence.audit;

import com.kista.admin.domain.model.AppErrorLog;
import com.kista.admin.application.port.output.AppErrorLogPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // AppErrorLogJpaRepository가 package-private
class AppErrorLogPersistenceAdapter implements AppErrorLogPort {

    private final AppErrorLogJpaRepository repo; // app_error_logs 테이블 JPA 저장소

    private static final int MAX_STACK_LINES = 30;

    @Override
    public void save(Exception e, String caller) {
        // 저장 실패가 호출부(예외 핸들러·AOP 인터셉터)로 전파되지 않도록 이 메서드 계약 자체가 격리를 보장
        try {
            // 스택트레이스 첫 30줄만 저장 (프레임워크 내부 라인 제외)
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = truncateStackTrace(sw.toString());

            repo.save(buildEntity(e.getClass().getSimpleName(), e.getMessage(), stackTrace, Map.of("caller", caller)));
        } catch (Exception saveEx) {
            log.warn("오류 로그 저장 실패: {}", saveEx.getMessage());
        }
    }

    @Override
    public void save(String errorType, String message, String stackTrace, Map<String, String> context) {
        // 저장 실패가 호출부(클라이언트/내부 API 컨트롤러)로 전파되지 않도록 이 메서드 계약 자체가 격리를 보장
        try {
            repo.save(buildEntity(errorType, message, truncateStackTrace(stackTrace), context));
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

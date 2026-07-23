package com.kista.adapter.out.persistence.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.admin.AppErrorLog;
import com.kista.domain.port.out.AppErrorLogPort;
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
    private final ObjectMapper objectMapper; // Spring Boot 자동 구성 빈

    private static final int MAX_STACK_LINES = 30;

    @Override
    public void save(Exception e, String caller) {
        // 스택트레이스 첫 30줄만 저장 (프레임워크 내부 라인 제외)
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stackTrace = truncateStackTrace(sw.toString());

        repo.save(buildEntity(e.getClass().getSimpleName(), e.getMessage(), stackTrace, Map.of("caller", caller)));
    }

    @Override
    public void save(String errorType, String message, String stackTrace, Map<String, String> context) {
        repo.save(buildEntity(errorType, message, truncateStackTrace(stackTrace), context));
    }

    // 스택트레이스 첫 30줄만 저장 (프레임워크 내부 라인 제외)
    private static String truncateStackTrace(String fullTrace) {
        if (fullTrace == null) return null;
        return fullTrace.lines()
                .limit(MAX_STACK_LINES)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private AppErrorLogEntity buildEntity(String errorType, String message, String stackTrace, Map<String, String> context) {
        // context JSON 직렬화
        String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(context != null ? context : Map.of());
        } catch (JsonProcessingException ex) {
            contextJson = "{}";
        }
        return new AppErrorLogEntity(
                null,
                errorType,
                message,
                stackTrace,
                contextJson,
                null // deletedAt
        );
    }

    @Override
    public List<AppErrorLog> findRecent(int limit) {
        return repo.findTopNByOrderByCreatedAtDesc(limit).stream()
                .map(this::toDomain)
                .toList();
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

    // 엔티티 → 도메인 record 변환
    private AppErrorLog toDomain(AppErrorLogEntity entity) {
        Map<String, String> ctx = null;
        if (entity.getContext() != null) {
            try {
                ctx = objectMapper.readValue(entity.getContext(), new TypeReference<>() {});
            } catch (JsonProcessingException ex) {
                log.warn("context 역직렬화 실패: {}", ex.getMessage());
            }
        }
        return new AppErrorLog(
                entity.getId(),
                entity.getErrorType(),
                entity.getMessage(),
                entity.getStackTrace(),
                ctx,
                entity.getCreatedAt()
        );
    }
}

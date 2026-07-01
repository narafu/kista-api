package com.kista.domain.model.privacy;

import java.util.List;
import java.util.stream.Collectors;

// PRIVACY 기준 매매표 검증 결과 — 저장 시 경고/차단, 장전 가드 재사용
public record PrivacyTradeValidationReport(
        List<Issue> issues
) {
    public PrivacyTradeValidationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static PrivacyTradeValidationReport empty() {
        return new PrivacyTradeValidationReport(List.of());
    }

    public static PrivacyTradeValidationReport warning(String code, String message) {
        return new PrivacyTradeValidationReport(List.of(new Issue(Severity.WARNING, code, message)));
    }

    public static PrivacyTradeValidationReport blocking(String code, String message) {
        return new PrivacyTradeValidationReport(List.of(new Issue(Severity.BLOCKING, code, message)));
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    public boolean hasBlockingIssues() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.BLOCKING);
    }

    public String summary() {
        return issues.stream()
                .map(i -> "[" + i.code() + "] " + i.message())
                .collect(Collectors.joining(" | "));
    }

    public enum Severity {
        WARNING,
        BLOCKING
    }

    public record Issue(
            Severity severity,
            String code,
            String message
    ) {
    }
}

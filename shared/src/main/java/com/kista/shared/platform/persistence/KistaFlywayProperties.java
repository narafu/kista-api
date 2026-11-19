package com.kista.shared.platform.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// kista.flyway.* — 소유 스키마별 마이그레이션 목록(순서대로 실행)
@ConfigurationProperties("kista.flyway")
public record KistaFlywayProperties(List<Migration> migrations) {

    public KistaFlywayProperties {
        // 목록 자체가 비면 아무것도 migrate하지 않는 기동을 막는다
        if (migrations == null || migrations.isEmpty()) {
            throw new IllegalStateException("Flyway 마이그레이션 목록이 비어 있습니다.");
        }
        migrations = List.copyOf(migrations);
    }

    // 마이그레이션 한 벌: 스크립트 위치·이력 테이블·소유 스키마
    public record Migration(String locations, String table, String schema) {

        public Migration {
            // 세 값 모두 필수 — 누락 시 기본 이력 테이블·스키마로 조용히 떨어지는 것을 막는다
            if (isBlank(locations) || isBlank(table) || isBlank(schema)) {
                throw new IllegalStateException("Flyway 마이그레이션의 locations·table·schema는 모두 필요합니다.");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}

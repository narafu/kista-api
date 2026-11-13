package com.kista.tradingcore;

import com.kista.shared.support.NullMarkedRecordSpecGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// trading-core /api-docs가 @NullMarked record의 null성(required·nullable)을 와이어와 일치하게 싣는지 가드 — 받은 스펙은 build/openapi에 남긴다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.redis.url=redis://localhost:6379" // trading-core application.yml은 REDIS_URL 기본값이 없다(필수)
})
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 연결 컨텍스트 로드
class OpenApiNullMarkedRecordContractTest {

    @LocalServerPort
    private int port; // RANDOM_PORT로 뜬 서버 포트

    @Test
    void NullMarked_record_스키마의_required와_nullable은_컴포넌트_null성과_일치한다() throws Exception {
        // test workingDir가 루트 프로젝트라 루트 build/openapi에 저장된다
        String spec = NullMarkedRecordSpecGuard.fetchSpec(port, Path.of("build/openapi/spec-tc.json"));

        NullMarkedRecordSpecGuard.Report report = NullMarkedRecordSpecGuard.verify(spec, "com.kista.tradingcore", "com.kista.shared");

        assertThat(report.checked()).as("검증 대상 @NullMarked record 수(0이면 가드가 비어 있음)").isPositive();
        assertThat(report.violations()).isEmpty();
    }
}

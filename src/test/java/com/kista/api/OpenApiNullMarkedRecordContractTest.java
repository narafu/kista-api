package com.kista.api;

import com.kista.shared.support.NullMarkedRecordSpecGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// root /api-docs가 @NullMarked record의 null성(required·nullable)을 와이어와 일치하게 싣는지 가드 — 받은 스펙은 build/openapi에 남긴다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 연결 컨텍스트 로드
class OpenApiNullMarkedRecordContractTest {

    @LocalServerPort
    private int port; // RANDOM_PORT로 뜬 서버 포트

    @Test
    void NullMarked_record_스키마의_required와_nullable은_컴포넌트_null성과_일치한다() throws Exception {
        String spec = NullMarkedRecordSpecGuard.fetchSpec(port, Path.of("build/openapi/spec-root.json"));

        // root classpath에는 trading-core도 있으므로 root·shared 패키지만 본다
        NullMarkedRecordSpecGuard.Report report = NullMarkedRecordSpecGuard.verify(spec, "com.kista.api", "com.kista.shared");

        assertThat(report.checked()).as("검증 대상 @NullMarked record 수(0이면 가드가 비어 있음)").isPositive();
        assertThat(report.violations()).isEmpty();
    }
}

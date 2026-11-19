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

// 한 애플리케이션 /api-docs가 @NullMarked record의 null성(required·nullable)을 와이어와 일치하게 싣는지 가드 — 받은 스펙은 build/openapi에 남긴다
// kista.role=web — worker 기동 잡(캘린더 적재·배치 재개)이 테스트에서 Alpaca·텔레그램을 실제로 부르지 않게 한다(공개 스펙은 web role이 서빙)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "kista.role=web")
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD) // 실 DB 연결 컨텍스트 로드
class OpenApiNullMarkedRecordContractTest {

    @LocalServerPort
    private int port; // RANDOM_PORT로 뜬 서버 포트

    @Test
    void NullMarked_record_스키마의_required와_nullable은_컴포넌트_null성과_일치한다() throws Exception {
        String spec = NullMarkedRecordSpecGuard.fetchSpec(port, Path.of("build/openapi/spec-root.json"));

        // root·매매 엔진·shared 패키지를 모두 본다
        NullMarkedRecordSpecGuard.Report report = NullMarkedRecordSpecGuard.verify(spec, "com.kista.api", "com.kista.tradingcore", "com.kista.shared");

        assertThat(report.checked()).as("검증 대상 @NullMarked record 수(0이면 가드가 비어 있음)").isPositive();
        assertThat(report.violations()).isEmpty();
    }
}

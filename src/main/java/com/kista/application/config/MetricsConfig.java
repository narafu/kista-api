package com.kista.application.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// Micrometer core에 프로세스 RSS 바인더가 없어 /proc/self/status를 직접 파싱해 노출 (Fly.io 컨테이너 OOMKill 위험 감지용)
@Configuration
class MetricsConfig {

    // Linux 전용 — 로컬 macOS 등에서는 파일이 없어 NaN 반환(메트릭 미노출)
    private static final Path PROC_STATUS = Path.of("/proc/self/status");

    @Bean
    Gauge processResidentMemoryGauge(MeterRegistry registry) {
        return Gauge.builder("process.resident.memory", MetricsConfig::readRssBytes)
                .baseUnit(BaseUnits.BYTES)
                .description("프로세스 RSS (Resident Set Size, /proc/self/status VmRSS)")
                .register(registry);
    }

    private static double readRssBytes() {
        try {
            List<String> lines = Files.readAllLines(PROC_STATUS);
            for (String line : lines) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024.0; // kB -> bytes
                }
            }
        } catch (IOException e) {
            // /proc 미지원 환경 — 정상 케이스로 취급
        }
        return Double.NaN;
    }
}

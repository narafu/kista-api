package com.kista.shared.platform;

import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.shared.platform.crypto.AesCryptoService;
import com.kista.shared.platform.internalapi.InternalApiClientConfig;
import com.kista.shared.platform.internalapi.InternalApiProperties;
import com.kista.shared.platform.metrics.MetricsConfig;
import com.kista.shared.platform.persistence.JpaAuditingConfig;
import com.kista.shared.platform.redis.RedisPubSubConfig;
import com.kista.shared.platform.scheduling.SchedulerJobRunner;
import com.kista.shared.platform.scheduling.SchedulerLockService;
import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.JwtDecoderConfig;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.platform.telegram.TelegramConfig;
import com.kista.shared.platform.telegram.TelegramProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

// :shared 인프라 빈 등록 — 앱(root·trading-core)은 com.kista.shared를 컴포넌트 스캔하지 않는다.
// HIGHEST_PRECEDENCE: Boot 자동구성의 @ConditionalOnMissingBean(기본 SecurityFilterChain·JwtDecoder 등)보다
// 먼저 평가돼야 기본 빈이 함께 등록되지 않는다(컨텍스트 로드 테스트가 SecurityFilterChain 1개를 단언)
// InternalApiProperties는 무조건 등록 — InternalApiClientConfig의 등록은 internal.api.base-url 조건부라 trading-core에선
// 빠지고, 그러면 token @NotBlank 기동 검증이 사라진다(이미 등록된 이름은 registrar가 건너뛰어 중복 없음)
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties({TelegramProperties.class, InternalApiProperties.class})
@Import({
        SecurityConfig.class, JwtDecoderConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class,
        RedisPubSubConfig.class, TelegramConfig.class, InternalApiClientConfig.class, MetricsConfig.class,
        AesCryptoService.class, AccountNoHasher.class, SchedulerJobRunner.class, SchedulerLockService.class,
        JpaAuditingConfig.class,
})
public class PlatformAutoConfiguration {
}

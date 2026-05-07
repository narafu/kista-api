package com.kista.adapter.out.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// @WebMvcTest 슬라이스 테스트는 persistence 설정을 로드하지 않아 충돌 없음
@Configuration
@EnableJpaAuditing
class JpaAuditingConfig {
}

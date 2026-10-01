package com.kista.web;

import com.kista.support.DataJpaTestBase;
import org.springframework.context.annotation.Import;

// root 소유 persistence 테스트 공통 베이스 — @DataJpaTest 슬라이스는 RootJpaScanConfig(@Configuration)를 스캔하지 않아
// 엔티티 스캔이 com.kista 전체로 넓어지고, 테스트 classpath의 :trading-core 엔티티까지 검증해 trading 마이그레이션이
// 안 돌아간 DB(예: CI에서 :trading-core:test가 빌드 캐시로 생략된 경우)에서 컨텍스트 로드가 실패한다. 명시 import로 root 범위에 고정
@Import(RootJpaScanConfig.class)
public abstract class RootDataJpaTestBase extends DataJpaTestBase {
}

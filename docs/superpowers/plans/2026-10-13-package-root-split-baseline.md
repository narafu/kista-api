# Package root split — 기준선 (Task 0)
- 아키텍처 테스트 수: root 39, trading-core 1  (Step 1)
- Task 0 이후 아키텍처 테스트 수: root 39, trading-core 1 (새 컨텍스트 로드 테스트는 com.kista·com.kista.tradingweb 소속이라 집계 밖)
- Modulith 모듈 ID(전략 전환 전): account, admin, benchmark, broker, contract, finance, market, marketcalendar, matching, notify, platform, privacy, sharedkernel, support, trading, tradingnotify, tradingstats, tradingweb, user, web — 기대 19개 + `support`(testFixtures), explicitly-annotated 전환 후 19개
- failOnEmptyShould 판정: 실패 규칙 없음(수정·allowEmptyShould 0건) — 일회용 빈 규칙 테스트로 설정이 실제 적용됨을 확인(FAILED) 후 삭제
- JPA 리포지토리: root 21, trading 15 (grep·테스트 일치 — 계획의 16은 오기, 사용자 확인)
- TradingApplicationContextLoadTest: trading-core application.yml의 `spring.data.redis.url`은 `${REDIS_URL}` 기본값 없음 → `@SpringBootTest(properties = "spring.data.redis.url=redis://localhost:6379")`로만 보충(부팅 중 외부 호출 실패 없음)
- integration 기대 수: root 14, trading-core 5

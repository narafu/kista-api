# 09. 프로세스 로컬 상태 전수 조사 (web N + worker 1 합치기 관문)

조사 기준: 2026-11-12 main. 읽기 전용 조사(코드 변경 없음). 경로는 `trading-core/src/main/java/com/kista/tradingcore/` 를 `TC/`, `shared/src/main/java/com/kista/shared/` 를 `SH/`, `src/main/java/com/kista/api/` 를 `API/` 로 줄여 쓴다.
전제: 합친 뒤 같은 trading 코드가 web 여러 개(HTTP 요청 경로) + worker 1개(@Scheduled·배치·재개)에서 동시에 돈다.

## A. JVM 안 상태를 가진 빈

### A-1. 브로커 호출·토큰 계통

| # | 클래스 file:line | 담는 것 | 쓰는 쪽 / 읽는 쪽 | web N + worker 1에서 깨지는 것 | 심각도 |
|---|---|---|---|---|---|
| A1 | `TC/broker/adapter/out/internal/CallIntervalGate.java:16` (`nextSlotByKey`, `System.nanoTime` 슬롯). 인스턴스: `kis/KisHttpClient.java:123`(350ms, 대기 20s 상한, 키=appKey), `meritz/MeritzHttpClient.java:44`(110ms, 20s, 키=appKey) | 키별 다음 호출 가능 시각(JVM 단조 시계) | 두 곳 모두 HTTP 요청 경로(잔고·포트폴리오·미리보기·수동 매매·주문취소)와 배치 경로가 같은 `executeWithRetry`를 탄다 | 간격 제한이 **프로세스별로 따로** 적용된다. 같은 appKey를 worker 배치(병렬 2/계좌)와 web 사용자 조회가 동시에 쏘면 KIS EGW00201(초당 거래건수)·메리츠 한도를 넘긴다. 주문 POST는 `retryOnRateLimit=false`라 재시도 없이 BrokerRateLimitException으로 거절 → **배치 주문 접수 실패**(KisHttpClient.java:112-123, 140-177). `System.nanoTime` 슬롯이라 Redis 공유 시 시계 기준도 바꿔야 한다 | 벤더 차단·주문 안전(접수 실패) |
| A2 | `TC/broker/adapter/out/internal/DbTokenCoordinator.java:21` (`locks` ConcurrentMap<UUID,ReentrantLock>), KIS·메리츠 계좌 토큰 | 계좌별 JVM 락 + DB `broker_tokens` 캐시 | `KisAuthApi.getToken/recoverToken`(KisAuthApi.java:62-72) — 요청·배치 양쪽 | 토큰 만료 시점에 두 프로세스가 동시에 miss → 각자 `/oauth2/tokenP` 발급. KIS는 **1분당 1회 제한(EGW00133)** 이라 한쪽이 `BrokerRateLimitException`/`KisApiException`으로 실패(송신 전 실패로 확정 거절 처리됨, KisHttpClient.java:135-139). 클래스 주석은 "재발급이 비파괴적이라 인스턴스 간 조정 불필요"(DbTokenCoordinator.java:13-16)라 했지만 1분 제한은 고려하지 않았다. 401 복구(`recover`)도 `invalidateToken` 후 재발급이라 서로 상대 토큰을 지울 수 있다 | 주문 안전(낮음~중간, 일시 실패) |
| A3 | `TC/broker/adapter/out/kis/KisAdminTokenCoordinator.java:31,33` (`lock`, `volatile memory`) | KIS 관리자 시세 토큰: Redis `kis:admin:token:{sha8}` + JVM 메모리 폴백 | 시세 조회(요청·배치 공통) | Redis가 주 저장이라 프로세스 간 공유됨. 단 `lock`이 JVM 락이라 Redis miss 시점에 두 프로세스가 동시 발급 가능(EGW00133). 메모리 폴백은 Redis 장애 때만 | 벤더 차단(낮음) |
| A4 | `TC/broker/adapter/out/kis/KisAuthApi.java:46` (`tempTokenCache`, 90초) | 연결 테스트 직후 계좌번호 검증용 임시 토큰(appKey 키) | 계좌 등록 `verifyCredentials`→`verifyAccount`(HTTP 요청 경로) | 두 호출이 서로 다른 web 인스턴스로 가면 캐시 미스 → 재발급 → EGW00133으로 계좌 등록 실패. blue/green·LB 환경에서 실제로 일어남 | 화면 지연(등록 재시도로 해소) |
| A5 | `TC/broker/adapter/out/toss/TossDistributedTokenCoordinator.java` + `TossRedisTokenStore` | Redis lease + fencing, 계좌·admin 토큰 | 요청·배치 공통 | **이미 분산 대응됨**(Redis canonical). JVM 상태 없음 | 무해 |
| A6 | `TC/broker/adapter/out/toss/UsdKrwRateCache.java:19` (volatile Entry, 60s) | USD/KRW 환율 | Toss 잔고·평가 경로 | 프로세스마다 별도 캐시 → 호출 수가 프로세스 수만큼 늘 뿐. 값 불일치는 60초 이내 | 무해 |
| A7 | `TC/broker/adapter/out/toss/TossPriceApi.java:48` (`prevCloseCache`, 키=종목+거래일+버킷, 만료 없음) | 전일종가 | 시세 조회(요청·배치) | 프로세스마다 따로 채움. 버킷(정규장 진행 여부)이 키라 값은 같아진다 → 호출 수 증가뿐 | 무해 |
| A8 | `TC/broker/adapter/out/toss/TossCandleApi.java:43` (`TtlResultCache latestDailyCache`, 2분) | 종목별 최신 일봉 600봉 | 시세·통계(요청) · DCA 입력(배치) | 호출 수 증가, 값은 2분 내 차이 | 무해 |

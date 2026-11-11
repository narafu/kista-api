# 지침서 04. 브로커 축 — 비주력 삭제, 시세 분리, 공통 골격

작성 2026-11-11 (Fable). 근거 측정: `findings/03-parallel-structures.md` B절, 운영 DB 조회(2026-11-11).

## 0. 사용자 결정 (2026-11-11)

- **KIS 삭제.** 활성 전략 1건의 이전이 선행 조건이다.
- **업비트 매매 어댑터 삭제.** 김프 지표용 공개 시세(`stats`의 `UpbitMarketDataPort`)는 남긴다.
- 토스는 시세·환율·캔들·대시보드(보유 조회)를 남긴다. **토스 주문 기능 삭제는 Fable 권고이고 아직 확정이 아니다**(단계 C 시작 전에 확인).

운영 현황: MERITZ 계좌 3·활성 전략 2, BINANCE 1·1, KIS 1·1, MOCK 1·4, TOSS 1·0, UPBIT 1·0.

## 1. 현재 구조와 문제

`trading-core/src/main/java/com/kista/tradingcore/broker` 7,632줄.

| 브로커 | 전용 줄 수 | 비고 |
|---|---|---|
| 토스 | 2,059 (+model 70) | 토큰만 약 690줄(`TossDistributedTokenCoordinator` 207, `TossRedisTokenStore` 229 등) |
| KIS | 1,634 (+예외 24, 토큰 영속 106) | |
| 메리츠 | 1,081 (+예외 32) | 주력 |
| 바이낸스 | 505 (+24) | 주력 |
| 업비트 | 471 (+24) | |
| Mock | 243 | |

문제는 네 가지다.

1. **주력이 21.5%뿐이다.** 메리츠와 바이낸스를 뺀 나머지가 모듈의 60.7%다.
2. **공통 골격이 없다.** HTTP 클라이언트 5개(142~220줄)가 같은 책임(요청 실행, 오류 본문 해석, 429 변환, 간격 제한, 재시도)을 각자 구현한다. rate limit 처리가 다섯 갈래, 토큰 관리가 세 계통이다.
3. **facade가 위임만 한다.** `KisBrokerAdapter`·`MeritzBrokerAdapter`·`TossBrokerAdapter` 359줄의 대부분이 `return xApi.m(...)`이다. 호출은 `BrokerRouter` → `*BrokerAdapter` → `*Api` 3단인데 앞 2단이 위임이다.
4. **시세 공급자와 매매 브로커가 섞여 있다.** 토스는 사용자 계좌 브로커이면서 관리자 키로 전체 시세·환율·캔들을 공급한다. 그래서 벤더 이름이 공용 모델에 샜다: `TossHolding`을 `PresentBalanceResult`·`MeritzTradingApi`·`MockBrokerAdapter`가 쓰고, `MockBrokerAdapter`가 `TossPriceApi`를 직접 쓰며, root `api.market`에 `TossCandleResponse`가 있다.

## 2. 목표 구조

```
broker/
  domain/model/            공용 모델만. 벤더 이름 없음 (BrokerHolding, BrokerCandle, ExchangeRate ...)
  application/port/output/ 기능 포트 (ExecutionPort, PortfolioPort, MarginPort, CryptoExchangePort ...)
  application/service/     BrokerRouter, BrokerCallGuard
  adapter/out/
    http/                  BrokerHttpClient(공통 골격), TokenCoordinator, CallIntervalGate
    meritz/                주식 매매
    binance/               코인 매매
    mock/                  모의 매매 (시세는 marketdata 포트로)
    toss/                  대시보드용 보유 조회 + 시세 공급 구현
    marketdata/            시세·캔들·환율 포트의 구현 배선 (현재 공급자: 토스 관리자 키)
```

### 2-1. 삭제

- `adapter/out/kis/` 전체, `adapter/out/persistence/KisToken*`, `KisExchangeRegistry`, `KisAdminPriceSource`·`KisAdminTokenCoordinator`, `domain/model/kis`, `KisRateLimitException`.
- `adapter/out/upbit/` 전체, `domain/model/upbit`. `stats`의 `UpbitMarketDataPort`와 그 구현은 broker 어댑터와 무관한지 확인하고 남긴다.
- `Broker.KIS`, `Broker.UPBIT` enum 상수. `Broker.supportsOpenAuctionOrders()`는 KIS만 true라 함께 사라진다. 이 메서드를 분기하는 개장 경매(MOO/LOO 사전 접수) 경로도 죽은 코드가 되므로 지운다. **이 부분은 동작 경로 삭제라 단계 B에서 따로 다룬다.**
- 문서: `docs/agents/kis-api.md`, `docs/agents/upbit-api.md`, 어댑터 디렉토리 `CLAUDE.md`, 루트 `CLAUDE.md`·`README.md`·`flows.yml`의 KIS·업비트 서술.

### 2-2. 시세를 매매에서 분리

시세·캔들·환율은 "어느 사용자 계좌의 브로커인가"와 무관하다. `BrokerPricePort`·`CandlePort`·`ExchangeRatePort`·`AdminMarketPricePort`의 구현 배선을 `adapter/out/marketdata`로 모으고, 공급자는 설정 한 곳에서 정한다. 그 결과:

- `MockBrokerAdapter`는 `TossPriceApi`가 아니라 시세 포트를 주입받는다.
- 공용 모델에서 벤더 이름을 뗀다: `TossHolding` → `BrokerHolding`, `TossCandle` → `BrokerCandle`로 흡수, `TossExchangeRate` → `ExchangeRate`. root의 `TossCandleResponse` → `CandleResponse`(OpenAPI 스키마 이름이 바뀌므로 `cross-repo.md`에 적고 fable-ui에 알린다. JSON 필드는 그대로다).
- 관리자 시세 공급자가 KIS·토스 둘이던 것이 하나가 된다. `AdminMarketPricePort`가 구현 1개가 되면 지침서 01의 기준에 따라 인터페이스를 유지할지 판정한다.

### 2-3. HTTP 공통 골격

`BrokerHttpClient` 하나가 요청 실행, 오류 본문 해석, 429·벤더 한도 코드의 `BrokerRateLimitException` 변환, 호출 간격 제한, 멱등 요청 재시도를 맡는다. 벤더는 다음만 공급한다.

```java
// 벤더가 공급하는 정책 (예시 형태 — 실제 시그니처는 메리츠·바이낸스·토스 세 클라이언트를 읽고 정한다)
record BrokerHttpPolicy(
        String baseUrl,
        Duration minInterval,                 // 0이면 간격 제한 없음
        Predicate<HttpRequest> retryable,     // 멱등 요청만 재시도
        Function<ErrorBody, BrokerApiException> errorMapper,
        RequestSigner signer) {}              // 토큰 헤더 또는 요청별 서명
```

- 인증은 두 종류다. 토큰형(메리츠, 토스)은 `TokenCoordinator` 계약 하나에 저장소만 DB·Redis로 갈아 끼운다. 서명형(바이낸스)은 `RequestSigner`만 구현한다.
- KIS가 빠지면 `DbTokenCoordinator`의 소비자는 메리츠 하나다. 토스의 Redis 토큰 계통(약 690줄)이 같은 `TokenCoordinator` 계약으로 줄어드는지가 이 지침서에서 가장 큰 감량 지점이다. 토스가 분산 락을 쓰는 이유(발급 횟수 제한 등)를 `docs/agents/toss-api.md`에서 확인하고 유지 범위를 정한다.

### 2-4. facade 제거

`*BrokerAdapter`가 `BrokerCapabilitiesPort`를 구현해 메서드마다 위임하는 대신, 벤더 `Config`가 기능 포트 구현을 묶음 record로 등록한다.

```java
// 브로커별 기능 묶음 — BrokerRouter가 broker로 골라 쓴다
record BrokerCapabilities(Broker broker, ExecutionPort execution, PortfolioPort portfolio,
                          MarginPort margin, SellableQuantityPort sellable,
                          BrokerOrderCorrectionPort correction) {}
```

`MeritzOrderApi`가 `ExecutionPort`·`BrokerOrderCorrectionPort`를, `MeritzTradingApi`가 `PortfolioPort`·`MarginPort`·`SellableQuantityPort`를 직접 구현한다. 토스가 조회 전용이 되면 `execution`·`correction`은 없다. 이 경우를 nullable로 두지 않는다. `Broker` enum에 `tradable` 속성을 두고, 매매 묶음(`BrokerCapabilities`)과 조회 묶음을 타입으로 나눈다.

### 기각한 대안

- **전 브로커 유지 + 공통 골격만 도입**: 골격이 다섯 벤더의 예외를 전부 수용해야 해서 정책 객체가 비대해진다. 사용자가 삭제로 결정했다.
- **주식 포트와 `CryptoExchangePort`를 하나로 통합**: 코인 현물은 LOC·MOC·증거금 개념이 없고 24시간 시장이라 포트 모양이 실제로 다르다. 실행 파이프라인 이원화(코인 DCA 러너 vs `TradingService`)는 지침서 06에서 다룬다. 여기서는 포트 두 계열을 유지한다.
- **토스 전체 삭제**: 시세·환율·캔들·Mock 시세의 유일 공급자라 대체 공급자 도입이 먼저 필요하다. 범위 밖이다.

## 3. 재심한 과거 결정

| 결정 | 재심 결과 |
|---|---|
| S-11 "브로커 capability 포트 14개 유지"(2026-07-11) | 포트 자체는 유지(다형성이 실제로 있다). 묶음 인터페이스 `BrokerCapabilitiesPort`와 위임 facade는 record 묶음으로 바꾼다 |
| S-48 broker 밖 기능은 `*Port`로 노출, `adapter/out`은 비공개 | 유지 |
| memory `project_broker_strategy` "업비트·KIS 제거가 싸게 유지" | 이번에 실행한다. 메모는 결정 내용으로 갱신한다 |
| broker.md "더블체크락·전일종가 캐시·확정종가 폴백은 공용 유틸 없이 인라인" | KIS 삭제 뒤 남는 소비자 수로 다시 판정한다(3곳 미만이면 유지) |

## 4. 예상 감량과 위험

- main: KIS 약 1,764 + 업비트 약 495 + facade 약 250 + (권고 채택 시) 토스 주문 약 250 + HTTP·토큰 골격 통일 약 300~500 = **약 3,000줄(추정), broker 모듈의 약 40%**.
- test: KIS·업비트 어댑터 테스트 전부. 줄 수는 측정하지 않았다(단계 A의 첫 태스크에서 센다).
- 위험 1 (데이터): `accounts.broker`는 `@Enumerated(STRING)`이다. `KIS`·`UPBIT` 값을 가진 행이 남은 채 enum 상수를 지우면 그 행을 읽는 순간 예외가 난다. `@SQLRestriction`은 소프트 삭제 행을 걸러주지만 native query와 관리자 조회는 걸러주지 않는다.
- 위험 2 (동작): 개장 경매 경로 삭제는 구조 변경이 아니라 동작 경로 삭제다. KIS 계좌가 없어진 뒤에는 도달 불가이지만, 분기를 지울 때 메리츠·Mock 경로의 동작이 바뀌지 않는지 테스트로 확인한다.
- 위험 3 (계약): `Broker` enum은 `/api/meta`로 나가고 UI가 쓴다. UI에 KIS 전용 화면 코드가 있으면 함께 지워야 한다.

## 5. 선행 조건 (사용자·운영 작업)

1. KIS 활성 전략 1건을 종료하거나 메리츠 계좌로 옮긴다(새 전략 등록 + 기존 전략 종료. 원장 이전 기능은 만들지 않는다).
2. KIS·업비트 계좌를 삭제한다(소프트 삭제).
3. 삭제 직전 커밋에 태그를 남긴다: `git tag archive/broker-kis-upbit`. 되살릴 때는 이 태그에서 어댑터 패키지를 꺼내 새 골격에 맞춘다.
4. 운영 DB에서 확인한다(읽기 전용):
   `select broker, count(*) filter (where deleted_at is null) live, count(*) total from trading.accounts group by 1;` — KIS·UPBIT의 live가 0이어야 한다.

## 6. 태스크

공통 완료 판정: `./gradlew compileJava compileTestJava 2>&1 | grep -E "error:|BUILD"` 성공, `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.*' 2>&1 | grep -E "FAILED|BUILD"` 통과, 아키텍처 테스트(root와 trading-core 둘 다) 통과. 단계 끝에 전체 스위트 1회.

### 단계 A. KIS·업비트 삭제 (Sonnet, 판정은 Opus)

1. (Sonnet) 삭제 범위 전수표를 만든다: `grep -rn "Kis\|KIS\|Upbit\|UPBIT"`를 main·test·resources·docs·`flows.yml`·`.github`·`deploy`·`scripts`에 돌려 파일별로 "삭제 / 수정 / 유지(김프 지표)"를 분류한다. 테스트 줄 수도 센다.
2. (Opus) 전수표를 검토한다. 특히 `TradingOrderExecutor`의 KIS 언급, `shared`의 `InternalApiClientConfig`·`UsTradeDates`의 KIS 참조, `MeritzAuthApi`의 `KisAuthApi` 언급이 주석인지 의존인지 확인한다.
3. (Sonnet) Flyway(trading-core 소유): KIS 토큰 테이블 drop, `accounts`에 KIS·UPBIT live 행이 있으면 실패하는 가드(`DO $$ ... RAISE EXCEPTION`)를 같은 마이그레이션 앞에 둔다. 소프트 삭제된 KIS·UPBIT 계좌 행과 그 하위 행의 처리(하드 삭제 또는 `broker` 값 보존 방식)는 Opus가 정한다. `flyway-migration` 스킬을 쓴다.
4. (Sonnet) 코드·테스트·문서를 삭제하고 `Broker` 상수 2개를 지운다. 2-1절 목록을 따른다.
5. (Sonnet) `.env.example`·`application*.yml`의 KIS·업비트 키, 운영 secret 목록에서 제거할 키를 `cross-repo.md`에 적는다(infra 실행 세션이 처리).
6. memory `project_broker_strategy.md`를 결정 내용으로 갱신한다.

커밋: `refactor(broker): KIS·업비트 매매 어댑터를 삭제한다`. 마이그레이션은 별도 커밋.

### 단계 B. 개장 경매 경로 정리 (Opus)

1. `supportsOpenAuctionOrders()` 호출처를 전부 읽고, KIS 전용 분기와 그 분기만 쓰던 주문 유형·스케줄 단계를 식별한다.
2. 메리츠·Mock 경로의 현재 동작을 고정하는 테스트가 있는지 확인하고 없으면 먼저 추가한다.
3. 분기를 지우고 `flows.yml`·`docs/agents/workflow-batch.md`·`scheduler-time-table.md`를 맞춘다.

### 단계 C. 시세 분리와 벤더 이름 제거 (Sonnet, 설계 확인은 Opus)

0. **시작 전에 사용자에게 확인**: 토스 주문 기능(`TossOrderApi`와 정정·취소)을 삭제하고 토스를 조회 전용으로 둘지.
1. `TossHolding`·`TossCandle`·`TossExchangeRate`·`TossCandleResponse`를 2-2절대로 개명한다(IDE rename).
2. 시세 포트 구현 배선을 `adapter/out/marketdata`로 모으고 `MockBrokerAdapter`가 포트를 주입받게 한다.
3. (확인이 "삭제"면) 토스 주문 클래스를 지우고 `Broker`에 `tradable`을 추가한다. 전략 등록 검증이 `tradable`을 보게 하고, 토스 계좌에 전략을 등록하려 하면 422가 나는 테스트를 추가한다. `/api/meta`의 broker 항목에 `tradable`을 싣는다(`cross-repo.md` 기록).

### 단계 D. HTTP·토큰 공통 골격 (Opus 설계, Sonnet 적용)

1. (Opus) `MeritzHttpClient`·`BinanceHttpClient`·`TossHttpClient`·`TossAuthApi`·`MeritzAuthApi`·토스 토큰 4개 클래스를 읽고 2-3절의 `BrokerHttpPolicy`·`TokenCoordinator` 실제 시그니처를 정한다. 세 벤더가 공유하지 않는 동작은 골격에 넣지 않는다.
2. (Sonnet) 메리츠부터 골격으로 옮긴다. 기존 어댑터 테스트(MockRestServiceServer 등)가 그대로 통과해야 한다.
3. (Sonnet) 바이낸스, 토스 순으로 옮긴다.
4. (Opus) 옮긴 뒤 줄 수를 다시 재고, 골격이 벤더 3개의 합보다 실제로 작아졌는지 확인한다. 작아지지 않았으면 골격을 버리고 원복한다(추출 기준: 같은 이유로 함께 바뀌는 3곳).

### 단계 E. facade 제거 (Sonnet)

2-4절대로 `BrokerCapabilities` record 묶음으로 바꾸고 `*BrokerAdapter`와 `BrokerCapabilitiesPort`를 지운다. `BrokerRouter`의 공개 메서드는 그대로 둔다(호출 측 변경 없음).

## 7. 하지 말 것

- 선행 조건 4번 확인 없이 enum 상수를 지우지 않는다.
- 메리츠·바이낸스의 주문·재시도 동작을 바꾸지 않는다. 특히 멱등 재시도 조건과 호출 간격 값(메리츠 110ms).
- `stats`의 김프 지표용 업비트 공개 시세를 지우지 않는다.
- 주식 포트와 코인 포트를 합치지 않는다(지침서 06의 판단 사항).
- 실계좌 통합 테스트(`*IT`)를 이 작업 중에 돌리지 않는다. 실제 주문이 나간다.

## 8. 다른 지침서와의 관계

- 01: `BrokerRouter`·포트는 다형성이 있어 01의 인터페이스 제거 대상이 아니다. 시세 포트는 단계 C 뒤에 구현 수를 다시 세어 01 기준으로 판정한다.
- 03·06: 브로커가 줄어든 뒤에 진행하면 전략·배치 쪽 분기가 줄어든다. **04를 가장 먼저 실행한다.**
- 09: KIS·업비트 문서 삭제.
- `cross-repo.md`: `Broker` 메타 변경, `CandleResponse` 스키마 이름, 운영 secret 키 제거.

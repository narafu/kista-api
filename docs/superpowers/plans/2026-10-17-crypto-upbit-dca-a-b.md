# 업비트·코인 DCA 1차 (A 계약 + B 어댑터·계좌) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 업비트(KRW 마켓) 계좌를 연결하고 BTC/ETH DCA 전략을 만들 수 있는 공유 어휘·API 계약(묶음 A)과 업비트 어댑터·계좌 기능(묶음 B)을 구현한다. 코인 DCA 실행(묶음 C)은 범위 밖이다.

**Architecture:** 공유 어휘는 `:shared` `com.kista.shared.kernel`에 둔다(`Market`, 승격한 `Currency`, `Broker.market/currency`, `StrategyTicker.market`). 주문 수량은 trading-core `Order`/`DcaExecution`/DB에서만 `BigDecimal`(numeric(20,8))로 바꾸고, 매칭 커널·브로커 wire(정수)는 경계에서 `intValueExact()`로 변환한다. 업비트는 `BrokerCapabilitiesPort`를 구현하지 않고 코인 전용 `CryptoExchangePort`(+라우터 `CryptoExchanges`)와 `BrokerConnectionTestPort`(`UpbitAuthApi`)만 구현한다.

**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith, JPA/Hibernate(`ddl-auto: validate`), Flyway(trading-core `db/migration-trading`), RestClient + `MockRestServiceServer`, Jackson 3(`tools.jackson`), JDK `javax.crypto.Mac`(HS512 JWT — 신규 의존성 없음), JUnit 5/Mockito/AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-17-crypto-upbit-dca-design.md` (섹션 1 = 묶음 A, 섹션 2 = 묶음 B, 섹션 4 테스트 중 A/B 해당분)

## Global Constraints

- 커밋 메시지는 한글, Conventional Commit 접두사(`feat(scope):`, `fix:`, `refactor:`, `docs:`) + 명령형 제목. 모든 커밋 끝에 아래 두 줄:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP`
- 커밋 전 author 확인: `git config user.name` = `narafu`, `git config user.email` = `narafu@kakao.com`. `git push`는 사용자 요청 시에만.
- 작업 위치: worktree `/Users/phs/workspace/kista/kista-api-worktrees/crypto-upbit-dca` (브랜치 `feat/crypto-upbit-dca`). `main` 체크아웃은 건드리지 않는다.
- 주석: 신규 코드에 `//` 인라인 주석만(필드 역할 한 줄, 로직 블록 직전 한 줄). Javadoc·블록 주석 금지.
- Enum DB 컬럼: PostgreSQL `CREATE TYPE` 금지, `VARCHAR(20)` + `@Enumerated(EnumType.STRING)`만. 기존 enum 상수명 변경 금지(DB 문자열).
- 암호화 저장 컬럼은 `VARCHAR(512)` 이상(`accounts.app_key`/`secret_key`/`account_no`는 이미 512).
- Flyway: 이미 적용된 마이그레이션 파일은 절대 수정 금지. trading 신규 마이그레이션은 `trading-core/src/main/resources/db/migration-trading`의 다음 번호(현재 최신 `V7__dca_condition.sql` → 이 계획은 V8·V9·V10). trading 스키마는 root expand/contract 제약 밖이지만 `ddl-auto: validate`라 Entity ↔ SQL(타입·precision·scale)을 반드시 대조한다. root(`src/main/resources/db/migration`)는 이 계획에서 마이그레이션을 추가하지 않는다.
- **V8(수량 numeric) 적용 후에는 구 trading-core 이미지로 롤백할 수 없다** — 구 엔티티(`int quantity`)가 `numeric(20,8)`에 대해 validate 실패한다. 배포 노트에 적는다.
- **root(kista-api·kista-scheduler)와 kista-trading은 같은 커밋으로 함께 배포한다** — `Broker.UPBIT`가 한쪽에만 있으면 런타임 정책 내부 API(`TradingPolicySettings` JSON) 역직렬화가 실패한다.
- UPBIT는 `TradingPolicySettings.defaults()`에서 `enabled=false`(배포 직후 노출 금지). 기본 `allowedIp` = `168.107.63.152`.
- Gradle `--tests`는 반드시 서브프로젝트 접두사: `:test --tests ...`(root), `:trading-core:test --tests ...`, `:shared:test --tests ...`. 접두사 없는 `test --tests`는 `:shared`에서 "No tests found"로 실패한다.
- verbose 출력은 명령에서 걸러 받는다: `2>&1 | grep -E "FAILED|BUILD|tests completed|error:"`. 실패 진단은 XML: `grep -l 'failures="[1-9]' trading-core/build/test-results/test/TEST-*.xml`.
- 수정 반복 중엔 관련 테스트만 좁혀 돌리고 전체 스위트(`./gradlew test`)는 A 마지막(Task 5)·B 마지막(Task 12)에서만 돌린다.
- DB 테스트(`*DbTest`/`DataJpaTestBase`)는 `docker compose up -d postgres`가 떠 있어야 한다(`localhost:5432/kistadb_test`).
- 업비트 원문 에러 메시지·응답 바디는 로그에만 남기고 ProblemDetail `detail`에는 한국어 고정 문구만 싣는다.
- 신규 `ErrorCode` 상수는 추가만 한다(이름 변경·삭제 금지 — kista-ui 계약).
- `@Async`/`CompletableFuture` 금지(Virtual Thread). `@Aspect` 금지.
- 응답 DTO의 `quantity`는 BigDecimal이지만 주식 값은 JSON에서 정수(`10`)로 나가야 한다(`10.00000000`·`1E+1` 금지).
- **A 완료 = Task 5가 확정한 커밋 해시를 kista-ui-22에 전달. B 완료 = Task 12가 확정한 커밋 해시를 kista-ui-22에 다시 전달**(B도 openapi를 바꾼다: 신규 엔드포인트 2개, ErrorCode 2개, AccountResponse/AccountRequest 변경).

## Review Focus

1. **DB 왕복 후 주식 주문 `quantity` JSON 형태** — `numeric(20,8)`에서 읽은 `10`이 응답에 `"quantity":10`으로 나가야 한다(`10.00000000`·`1E+1`이 아니라). 같은 이유로 `Order.equals`(scale 민감)도 깨지면 안 된다 → Task 3 Step 1의 `OrderQuantityNormalizationTest`·`OrderPersistenceAdapterDbTest.quantityRoundTripsAsPlainInteger`.
2. **업비트 계좌를 가진 사용자가 다른 계좌를 등록** — 기존 UPBIT 계좌의 `accountNo`가 null이라 `AccountService.register`의 동일 사용자 중복 fallback(`a.accountNo().equals(...)`)이 NPE를 내면 안 된다 → Task 9 Step 1의 `register_kis_whenUserAlreadyHasUpbitAccount_doesNotNpe`.
3. **현행 kista-ui 관리자 화면이 UPBIT 키 없이 런타임 설정 PUT** — 400이 아니라 저장돼야 하고, UPBIT의 enabled·allowedIp는 현재 값이 보존돼야 한다 → Task 2 Step 1의 `updateSettings_withoutUpbitKey_preservesCurrentUpbitSettings`.
4. **ACTIVE 코인 DCA 전략이 있는 상태에서 주식 개장/마감 배치** — C 배포 전까지 `BrokerRouter`가 UPBIT를 몰라 매 배치마다 관리자 오류 알림이 쏟아지면 안 된다(조용히 제외) → Task 4 Step 1의 `buildAll_skipsCryptoAccountStrategies`.
5. **DCA 요청에 `amount`와 `amountUsd`를 같은 값·다른 scale로 함께 보냄(`300` vs `300.00`)** — 400이 아니라 통과해야 하고, 값이 정말 다를 때만 400 → Task 4 Step 1의 `DcaConfigRequestTest`.

---

## 파일 구조 (요약)

| 영역 | 생성 | 수정 |
|---|---|---|
| shared kernel | `Market.java`, `Currency.java`(이동) | `Broker`, `StrategyTicker`, `OrderType`, `DcaSkipReason`, `StrategyType`, `BrokerSettings`, `TradingPolicySettings` |
| shared platform | — | `web/ErrorCode.java` |
| shared contract | — | `trading/OrderResponse.java`, `stats/PortfolioOrderResponse.java` |
| root web/admin | `web/dto/BrokerMeta.java` | `MetaController`, `TickerMeta`, `MetaBundle`, `AdminSettingsRequest`, `AdminSettingsController`, `RuntimeSettingsResponse`, `AdminTradeResponse`, `TelegramBotService` |
| trading-core broker | `domain/model/{Crypto*,BrokerAccountVerification,BrokerIpNotAllowedException,BrokerPermissionDeniedException}`, `domain/model/upbit/UpbitApiException`, `application/port/output/CryptoExchangePort`, `application/service/CryptoExchanges`, `adapter/out/upbit/*` | `BrokerConnectionTestPort`, `BrokerCredentialException`, `KisAuthApi`, `TossAuthApi`, `MockAuthApi`, `KisOrderApi`, `TossOrderApi`, 9개 `Currency` import |
| trading-core account | `domain/model/UpdateCredentialsCommand`, `adapter/in/web/dto/CredentialsRequest` | `Account`, `AccountService`, `AccountUseCase`, `AccountPort`, `AccountPersistenceAdapter`, `AccountEntity`, `AccountController`, `AccountRequest`, `AccountResponse` |
| trading-core trading | `domain/model/Quantities.java` | `Order`, `DcaExecution`, 엔티티·리포지토리, DTO, 서비스(Task 3 목록), `DcaConfig`(matching), `DcaConfigRequest`, `DcaResponse`, `StrategyCreationService`, `DcaConfigService`, `BatchContextFactory` |
| trading-core stats | `CryptoPortfolio*` 5파일 | `AccountStatisticsService` |
| trading-core web | — | `TradingExceptionHandler` |
| migration | `V8__order_quantity_numeric.sql`, `V9__dca_amount_execution_hour.sql`, `V10__account_upbit.sql` | — |
| docs | — | `modules/shared/kernel.md`, `modules/tradingcore/{broker,account,trading,stats}.md`, `modules/api/admin.md`, `flows.yml`, `README.md` |

---

## 묶음 A — 공유 어휘·API 계약

### Task 1: 공유 어휘 확장 (Market·Currency 승격·UPBIT·BTC/ETH·MARKET·스킵 사유) + `/api/meta`

**Files:**
- Create: `shared/src/main/java/com/kista/shared/kernel/Market.java`
- Move: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/Currency.java` → `shared/src/main/java/com/kista/shared/kernel/Currency.java` (`git mv`, package 변경)
- Modify: `shared/src/main/java/com/kista/shared/kernel/Broker.java:10-21`
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyTicker.java:13-22`
- Modify: `shared/src/main/java/com/kista/shared/kernel/OrderType.java:4-9`
- Modify: `shared/src/main/java/com/kista/shared/kernel/DcaSkipReason.java:9-14`
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyType.java:30-33`
- Modify (Currency import 교체, 9파일): `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossHoldingsApi.java`, `.../broker/adapter/out/mock/MockBrokerAdapter.java`, `.../broker/adapter/out/kis/KisTradingApi.java`, `.../broker/adapter/out/kis/KisBrokerAdapter.java`, `.../broker/domain/model/MarginItem.java`(같은 패키지였으므로 import 추가), `.../broker/domain/model/package-info.java`(Currency 언급 시 문구만), `.../stats/adapter/in/web/dto/MarginResponse.java`, `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/toss/TossHoldingsApiTest.java`, `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/mock/MockBrokerAdapterTest.java`
- Modify (MARKET 스위치 분기): `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisOrderApi.java:113-120`, `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/toss/TossOrderApi.java:160-165`
- Create: `src/main/java/com/kista/api/web/dto/BrokerMeta.java`
- Modify: `src/main/java/com/kista/api/web/MetaController.java:77-81`, `src/main/java/com/kista/api/web/dto/TickerMeta.java`, `src/main/java/com/kista/api/web/dto/MetaBundle.java:13`
- Test: `shared/src/test/java/com/kista/shared/kernel/MarketVocabularyTest.java` (create), `src/test/java/com/kista/api/web/MetaControllerTest.java` (modify :88 + 추가)
- Docs: `docs/agents/modules/shared/kernel.md`, `docs/agents/modules/tradingcore/broker.md`(domain/model 목록에서 Currency 제거)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `enum Market { US_STOCK, CRYPTO_KRW }` (`com.kista.shared.kernel`)
  - `enum Currency { KRW, USD, JPY; static Optional<Currency> tryParse(String) }` (`com.kista.shared.kernel`)
  - `Broker.UPBIT`, `Market Broker.getMarket()`, `Currency Broker.getCurrency()`
  - `StrategyTicker.BTC`, `StrategyTicker.ETH`, `Market StrategyTicker.getMarket()`
  - `OrderType.MARKET`
  - `DcaSkipReason.BELOW_MIN_ORDER_AMOUNT`, `EXCHANGE_API_ERROR`, `API_KEY_EXPIRED`
  - `StrategyType.DCA.capability()`: tickers `{UPRO, QLD, BTC, ETH}`, orderTypes `[MOO, MOC, MARKET]`
  - `/api/meta` `brokers[]`: `{code,label,description,market,currency}`, `tickers[]`: `{code,description,targetProfitRate,market}`

- [ ] **Step 1: 실패 테스트 작성 — 어휘**

`shared/src/test/java/com/kista/shared/kernel/MarketVocabularyTest.java`:

```java
package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarketVocabularyTest {

    @Test
    void brokersCarryMarketAndCurrency() {
        assertThat(Broker.UPBIT.getMarket()).isEqualTo(Market.CRYPTO_KRW);
        assertThat(Broker.UPBIT.getCurrency()).isEqualTo(Currency.KRW);
        for (Broker stock : new Broker[]{Broker.KIS, Broker.TOSS, Broker.MOCK}) {
            assertThat(stock.getMarket()).isEqualTo(Market.US_STOCK);
            assertThat(stock.getCurrency()).isEqualTo(Currency.USD);
        }
        assertThat(Broker.UPBIT.supportsOpenAuctionOrders()).isFalse();
    }

    @Test
    void cryptoTickersAreDcaOnlyCryptoMarket() {
        assertThat(StrategyTicker.BTC.getMarket()).isEqualTo(Market.CRYPTO_KRW);
        assertThat(StrategyTicker.ETH.getMarket()).isEqualTo(Market.CRYPTO_KRW);
        assertThat(StrategyTicker.BTC.getTargetProfitRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(StrategyTicker.SOXL.getMarket()).isEqualTo(Market.US_STOCK);
        assertThat(StrategyType.DCA.availableTickers()).contains(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(StrategyType.INFINITE.availableTickers()).doesNotContain(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(StrategyType.DCA.capability().orderTypes()).containsExactly(OrderType.MOO, OrderType.MOC, OrderType.MARKET);
    }

    @Test
    void newSkipReasonsHaveKoreanLabels() {
        assertThat(DcaSkipReason.BELOW_MIN_ORDER_AMOUNT.getLabel()).isEqualTo("최소 주문 금액 미달");
        assertThat(DcaSkipReason.EXCHANGE_API_ERROR.getLabel()).isEqualTo("거래소 API 오류");
        assertThat(DcaSkipReason.API_KEY_EXPIRED.getLabel()).isEqualTo("API 키 만료");
    }

    @Test
    void currencyLivesInKernel() {
        assertThat(Currency.tryParse(" KRW ")).contains(Currency.KRW);
        assertThat(Currency.tryParse("EUR")).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :shared:test --tests 'com.kista.shared.kernel.MarketVocabularyTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`cannot find symbol: Market`, `UPBIT`, `BTC`).

- [ ] **Step 3: 어휘 구현**

`shared/src/main/java/com/kista/shared/kernel/Market.java`:

```java
package com.kista.shared.kernel;

// 거래 시장 — 증권사(Broker)와 종목(StrategyTicker)이 같은 시장이어야 전략을 만들 수 있다. 상수명은 API 계약(변경 금지)
public enum Market {
    US_STOCK,   // 미국 주식 (KIS/Toss/MOCK)
    CRYPTO_KRW  // 원화 코인 마켓 (UPBIT)
}
```

`git mv trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/Currency.java shared/src/main/java/com/kista/shared/kernel/Currency.java` 후 첫 줄을 `package com.kista.shared.kernel;`로 바꾸고 enum 위에 주석 한 줄 추가:

```java
// 통화 — broker(KIS crcy_cd 파싱)와 계좌 응답(broker 파생 통화)이 공유하는 어휘. broker.domain.model에서 승격(복제 금지)
public enum Currency {
```

`Broker.java` 상수·필드 교체:

```java
public enum Broker {
    TOSS("토스증권",    "토스", Market.US_STOCK, Currency.USD),  // 토스증권 Open API
    KIS("한국투자증권", "한투", Market.US_STOCK, Currency.USD),  // 한국투자증권 Open API
    MOCK("모의계좌",    "모의", Market.US_STOCK, Currency.USD),  // 증권사 연동 없는 DB 기반 모의매매
    UPBIT("업비트",     "업비트", Market.CRYPTO_KRW, Currency.KRW); // 업비트 Open API (KRW 마켓 코인)

    private final String label;      // 한국어 전체 이름
    private final String shortLabel; // UI 모바일 약칭
    private final Market market;     // 거래 시장 — 종목 시장과 일치해야 전략 생성 가능
    private final Currency currency; // 계좌 기준 통화 (주문 금액 단위)
```

`StrategyTicker.java` 상수·필드 교체:

```java
    MAGX(new BigDecimal("0.15"), "ROUNDHILL DAILY MAGNIFICENT SEVEN 2X", Market.US_STOCK), // 베타: 2.2~2.4
    USD(new BigDecimal("0.20"), "PROSHARES SEMICONDUCTORS 2X", Market.US_STOCK), // 베타: 3.5~3.7
    TQQQ(new BigDecimal("0.15"), "PROSHARES QQQ 3X", Market.US_STOCK), // 베타: 3.4~3.5
    SOXL(new BigDecimal("0.20"), "DIREXION SEMICONDUCTOR DAILY 3X", Market.US_STOCK), // 베타: 5.3~5.5
    UPRO(BigDecimal.ZERO, "PROSHARES ULTRAPRO S&P500 3X", Market.US_STOCK), // DCA 전용 — 익절률 미사용
    QLD(BigDecimal.ZERO, "PROSHARES ULTRA QQQ 2X", Market.US_STOCK),        // DCA 전용 — 익절률 미사용
    BTC(BigDecimal.ZERO, "BITCOIN", Market.CRYPTO_KRW),                     // 코인 DCA 전용 — 업비트 마켓 코드는 어댑터 레지스트리 소유
    ETH(BigDecimal.ZERO, "ETHEREUM", Market.CRYPTO_KRW);                    // 코인 DCA 전용

    private final BigDecimal targetProfitRate;  // 익절 목표 수익률 (매매 도메인 정책)
    private final String description;           // 종목 설명 (UI 메타)
    private final Market market;                // 거래 시장 — 계좌 증권사 시장과 일치해야 한다
```

`OrderType.java`:

```java
    MOO,   // Market On Open: 장개시 시장가 — 매수는 브로커 어댑터가 캡 지정가(KIS LOO)로 변환
    MARKET // 즉시 시장가 — 코인 거래소 전용(업비트 매수 ord_type=price, 매도 ord_type=market). 주식 어댑터는 거부
```

`DcaSkipReason.java`:

```java
    BELOW_ONE_SHARE("1주 미만 금액"),
    BELOW_MIN_ORDER_AMOUNT("최소 주문 금액 미달"), // 코인 — 산정 금액이 거래소 최소 주문 금액(5,000원) 미만
    EXCHANGE_API_ERROR("거래소 API 오류"),        // 코인 — 업비트 API 실패
    API_KEY_EXPIRED("API 키 만료");               // 코인 — 인증 실패(키 만료·폐기)
```

`StrategyType.java` DCA 분기:

```java
            case DCA -> new StrategyCapability(
                    EnumSet.of(StrategyTicker.UPRO, StrategyTicker.QLD, StrategyTicker.BTC, StrategyTicker.ETH),
                    false, false, false, List.of(),
                    List.of(OrderType.MOO, OrderType.MOC, OrderType.MARKET), List.of(OrderDirection.BUY, OrderDirection.SELL),
                    List.of(DcaFrequency.values()), List.of(DcaIndicator.values()));
```

같은 파일 18행 주석을 `// UPRO/QLD(주식)·BTC/ETH(코인) — 주기별 고정 금액 단방향 매매`로 바꾼다.

- [ ] **Step 4: Currency import 교체 + 주식 어댑터 MARKET 거부**

9개 파일에서 `import com.kista.tradingcore.broker.domain.model.Currency;`를 `import com.kista.shared.kernel.Currency;`로 바꾼다. `MarginItem.java`(같은 패키지라 import가 없었음)와 `broker.domain.model.*` 와일드카드로 Currency를 쓰던 파일에는 `import com.kista.shared.kernel.Currency;`를 추가한다. 누락은 Step 5 컴파일이 잡는다.

`KisOrderApi.resolveOrderDvsn` switch에 분기 추가:

```java
            case LIMIT -> "00";             // 지정가
            case MARKET -> throw new IllegalArgumentException("MARKET 주문은 코인 거래소 전용입니다"); // 주식 경로 도달 불가 방어
```

`TossOrderApi.resolveTimeInForce` switch:

```java
            case LIMIT, MOO -> "DAY";
            case MARKET -> throw new IllegalArgumentException("MARKET 주문은 코인 거래소 전용입니다"); // 주식 경로 도달 불가 방어
```

- [ ] **Step 5: 어휘 테스트 통과 + 컴파일 확인**

Run: `./gradlew :shared:test --tests 'com.kista.shared.kernel.*' :trading-core:compileTestJava compileTestJava 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: `BUILD SUCCESSFUL`. (`TradingPolicySettingsTest.defaultsPreserveCurrentRuntimeBehavior`는 아직 UPBIT도 enabled=true라 통과한다 — Task 2에서 바뀐다.)

주의: `Broker.UPBIT`가 생기는 순간 `AdminSettingsRequest.toDomain()`이 모든 Broker 키를 요구하므로 `AdminSettingsControllerTest`의 PUT 성공 테스트(요청 본문에 UPBIT 없음)가 400으로 깨진다. 이 깨짐이 Review Focus 3의 실제 증상이며 Task 2가 고친다 — Task 1과 Task 2는 연달아 실행하고, Task 1 리뷰 게이트에서는 위 명령과 Step 8의 `MetaControllerTest`만 본다. 같은 이유로 Task 1 커밋 단독 배포는 금지(Task 2까지 함께 배포).

- [ ] **Step 6: 실패 테스트 작성 — `/api/meta`**

`src/test/java/com/kista/api/web/MetaControllerTest.java` 88행 기대값을 `contains("MOO", "MOC", "MARKET")`로 바꾸고, 같은 클래스에 테스트 추가:

```java
    @Test
    void getBundle_exposesMarketAndCurrency() throws Exception {
        mockMvc.perform(get("/api/meta").with(authentication(userToken(java.util.UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].market").value(org.hamcrest.Matchers.contains("CRYPTO_KRW")))
                .andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].currency").value(org.hamcrest.Matchers.contains("KRW")))
                .andExpect(jsonPath("$.brokers[?(@.code=='KIS')].market").value(org.hamcrest.Matchers.contains("US_STOCK")))
                .andExpect(jsonPath("$.brokers[?(@.code=='KIS')].description").value(org.hamcrest.Matchers.contains("한투")))
                .andExpect(jsonPath("$.tickers[?(@.code=='BTC')].market").value(org.hamcrest.Matchers.contains("CRYPTO_KRW")))
                .andExpect(jsonPath("$.tickers[?(@.code=='SOXL')].market").value(org.hamcrest.Matchers.contains("US_STOCK")))
                .andExpect(jsonPath("$.dcaSkipReasons[?(@.code=='API_KEY_EXPIRED')].label").value(org.hamcrest.Matchers.contains("API 키 만료")));
    }
```

(파일 상단에 기존 테스트가 쓰는 인증 방식이 `userToken`이면 그대로, 아니면 기존 `getBundle_*` 성공 테스트와 같은 인증 처리로 맞춘다.)

Run: `./gradlew :test --tests 'com.kista.api.web.MetaControllerTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: FAIL (`market` 경로 없음).

- [ ] **Step 7: meta 구현**

`src/main/java/com/kista/api/web/dto/BrokerMeta.java`:

```java
package com.kista.api.web.dto;

import com.kista.shared.kernel.Broker;
import io.swagger.v3.oas.annotations.media.Schema;

// 증권사 메타 — EnumMeta 3필드(code/label/description=약칭)에 시장·통화를 더한다. 시장·통화는 enum name 문자열(스키마 이름 충돌 방지)
public record BrokerMeta(
        @Schema(description = "enum name() 값", example = "UPBIT")
        String code,        // enum name() 값
        @Schema(description = "한국어 표시 이름")
        String label,       // 한국어 표시 이름
        @Schema(description = "UI 모바일 약칭")
        String description, // UI 모바일 약칭 (기존 EnumMeta.description 자리 유지)
        @Schema(description = "거래 시장", example = "CRYPTO_KRW")
        String market,      // Market name()
        @Schema(description = "계좌 기준 통화", example = "KRW")
        String currency     // Currency name()
) {
    public static BrokerMeta from(Broker b) {
        return new BrokerMeta(b.name(), b.getLabel(), b.getShortLabel(), b.getMarket().name(), b.getCurrency().name());
    }
}
```

`TickerMeta.java`에 필드 추가:

```java
        @Schema(description = "익절 목표 수익률")
        BigDecimal targetProfitRate,   // 익절 목표 수익률
        @Schema(description = "거래 시장", example = "US_STOCK")
        String market                  // Market name() — 계좌 시장과 일치하는 종목만 선택 가능
) {
    public static TickerMeta from(StrategyTicker t) {
        return new TickerMeta(
                t.name(), t.getDescription(), t.getTargetProfitRate(), t.getMarket().name()
        );
    }
}
```

`MetaBundle.java` 13행 `List<EnumMeta> brokers,`를 `List<BrokerMeta> brokers,`로 바꾼다. `MetaController.getBrokerList()`:

```java
    private List<BrokerMeta> getBrokerList() {
        return Arrays.stream(Broker.values())
                .map(BrokerMeta::from)
                .toList();
    }
```

(`MetaController`는 `com.kista.api.finance.domain.model.Market`을 import하므로 kernel `Market`을 import하지 않는다 — DTO가 `name()` 문자열로만 다룬다.)

- [ ] **Step 8: 통과 확인**

Run: `./gradlew :test --tests 'com.kista.api.web.MetaControllerTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: 문서 갱신**

`docs/agents/modules/shared/kernel.md` "공유 enum" 줄의 `Broker(TOSS/KIS/MOCK)`를 `Broker(TOSS/KIS/MOCK/UPBIT — market·currency 보유)/Market(US_STOCK/CRYPTO_KRW)/Currency(KRW/USD/JPY — broker.domain.model에서 승격)`로, `OrderType` 뒤에 `(MARKET은 코인 거래소 전용)`을, `StrategyTicker` 뒤에 `(market 보유 — BTC/ETH는 CRYPTO_KRW)`를 덧붙인다. `docs/agents/modules/tradingcore/broker.md` `domain/model/` 줄 첫 머리 `Currency/`를 지운다.

- [ ] **Step 10: 커밋**

```bash
git add shared/src trading-core/src src/main/java/com/kista/api/web src/test/java/com/kista/api/web docs/agents/modules
git commit -m "$(cat <<'EOF'
feat(kernel): 업비트·코인 시장 어휘를 추가하고 Currency를 sharedkernel로 승격한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 2: 런타임 정책 — UPBIT 기본 비활성·allowedIp·DCA 코인 티커·관리자 PUT 누락 키 보존

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/BrokerSettings.java`
- Modify: `shared/src/main/java/com/kista/shared/kernel/TradingPolicySettings.java:23-41`
- Modify: `src/main/java/com/kista/api/admin/adapter/in/web/dto/AdminSettingsRequest.java:36-48,67-70`
- Modify: `src/main/java/com/kista/api/admin/adapter/in/web/AdminSettingsController.java:42-43`
- Modify: `src/main/java/com/kista/api/admin/adapter/in/web/dto/RuntimeSettingsResponse.java:34,46-49`
- Modify (테스트 기대값): `shared/src/test/java/com/kista/shared/kernel/TradingPolicySettingsTest.java:19`, `src/test/java/com/kista/api/admin/adapter/in/web/AdminSettingsControllerTest.java`(getSettings stub), `src/test/java/com/kista/api/admin/application/service/RuntimeConfigServiceTest.java:40`, `src/test/java/com/kista/api/admin/application/service/AdminSettingsServiceTest.java:108`, `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/policy/TradingPolicySettingsPersistenceAdapterIT.java:34`, `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/TradingPolicyServiceTest.java:78` (`new BrokerSettings(x)` → `new BrokerSettings(x, null)`)
- Test: `shared/src/test/java/com/kista/shared/kernel/TradingPolicySettingsTest.java`, `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/policy/TradingPolicySettingsPersistenceAdapterTest.java`, `src/test/java/com/kista/api/admin/adapter/in/web/AdminSettingsControllerTest.java`
- Docs: `docs/agents/modules/api/admin.md`

**Interfaces:**
- Consumes: Task 1 `Broker.UPBIT`, `StrategyTicker.BTC/ETH`
- Produces:
  - `record BrokerSettings(boolean enabled, String allowedIp)` (`allowedIp` nullable)
  - `TradingPolicySettings.UPBIT_ALLOWED_IP = "168.107.63.152"`
  - `TradingPolicySettings.defaults()`: UPBIT `(false, "168.107.63.152")`, 나머지 `(true, null)`, DCA 티커 허용값 `[UPRO, QLD, BTC, ETH]`(기본 UPRO)
  - `RuntimeSettingsBundle AdminSettingsRequest.toDomain(Map<Broker, BrokerSettings> currentBrokers)`
  - `/api/runtime-config`·`/api/admin/settings` 응답 `brokers.<KEY>.allowedIp`

- [ ] **Step 1: 실패 테스트 작성**

`TradingPolicySettingsTest.java` 19행을 아래로 바꾸고 테스트 추가:

```java
        assertThat(settings.brokers().entrySet()).allMatch(e -> e.getValue().enabled() == (e.getKey() != Broker.UPBIT)); // UPBIT만 기본 비활성
```

```java
    @Test
    void upbitDefaultsToDisabledWithServerIp() {
        BrokerSettings upbit = TradingPolicySettings.defaults().brokers().get(Broker.UPBIT);
        assertThat(upbit.enabled()).isFalse();
        assertThat(upbit.allowedIp()).isEqualTo("168.107.63.152");
        assertThat(TradingPolicySettings.defaults().brokers().get(Broker.KIS).allowedIp()).isNull();
    }

    @Test
    void dcaDefaultsAllowCryptoTickers() {
        assertThat(TradingPolicySettings.defaults().strategy(StrategyType.DCA).ticker().allowedValues())
                .containsExactly(StrategyTicker.UPRO, StrategyTicker.QLD, StrategyTicker.BTC, StrategyTicker.ETH);
    }
```

`TradingPolicySettingsPersistenceAdapterTest.java`에 추가(운영 행에 UPBIT 키·allowedIp 필드가 없는 상태 재현):

```java
    @Test
    @DisplayName("UPBIT 키가 없는 기존 행은 defaults(UPBIT 비활성·허용 IP)로 보충되고 기존 브로커 allowedIp는 null로 로드된다")
    void loadBackfillsUpbitAsDisabled() throws Exception {
        ObjectNode root = (ObjectNode) objectMapper.valueToTree(TradingPolicySettings.defaults());
        ObjectNode brokers = (ObjectNode) root.get("brokers");
        brokers.remove("UPBIT");
        brokers.properties().forEach(e -> ((ObjectNode) e.getValue()).remove("allowedIp"));
        when(repository.findById(TradingPolicySettingsPersistenceAdapter.SETTING_KEY)).thenReturn(Optional.of(
                new TradingPolicySettingsEntity(TradingPolicySettingsPersistenceAdapter.SETTING_KEY, objectMapper.writeValueAsString(root))));

        TradingPolicySettings loaded = adapter.load();

        assertThat(loaded.brokerEnabled(Broker.UPBIT)).isFalse();
        assertThat(loaded.brokers().get(Broker.UPBIT).allowedIp()).isEqualTo("168.107.63.152");
        assertThat(loaded.brokers().get(Broker.KIS).allowedIp()).isNull();
        assertThat(loaded.brokerEnabled(Broker.KIS)).isTrue();
    }
```

`AdminSettingsControllerTest.java`에 추가(기존 PUT 성공 테스트의 요청 JSON 생성 헬퍼를 재사용하되 brokers에서 UPBIT를 뺀 본문을 쓴다 — 기존 헬퍼가 없으면 아래처럼 직접 쓴다):

```java
    @Test
    void updateSettings_withoutUpbitKey_preservesCurrentUpbitSettings() throws Exception {
        // 현재 저장 값: UPBIT 활성 + 다른 IP — 현행 UI는 brokers에 UPBIT를 보내지 않는다
        java.util.Map<Broker, BrokerSettings> currentBrokers = new java.util.EnumMap<>(TradingPolicySettings.defaults().brokers());
        currentBrokers.put(Broker.UPBIT, new BrokerSettings(true, "10.0.0.1"));
        TradingPolicySettings current = new TradingPolicySettings(currentBrokers, TradingPolicySettings.defaults().strategies());
        when(adminSettingsUseCase.getSettings()).thenReturn(new RuntimeSettingsBundle(RuntimeSettings.defaults(), current));
        when(adminSettingsUseCase.updateSettings(eq(ADMIN_ID), any(), eq(false)))
                .thenAnswer(inv -> inv.getArgument(1));

        mockMvc.perform(put("/api/admin/settings")
                        .with(csrf()).with(authentication(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJsonWithBrokers("""
                                {"KIS":{"enabled":true},"TOSS":{"enabled":false},"MOCK":{"enabled":true}}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers.UPBIT.enabled").value(true))
                .andExpect(jsonPath("$.brokers.UPBIT.allowedIp").value("10.0.0.1"))
                .andExpect(jsonPath("$.brokers.TOSS.enabled").value(false));
    }
```

`adminToken()`·`requestJsonWithBrokers(String brokersJson)`는 이 테스트 클래스의 기존 인증 헬퍼·요청 본문 상수 이름에 맞춘다: 기존 PUT 성공 테스트(90행 근처)가 쓰는 본문 문자열에서 `"brokers":{...}` 부분만 인자로 치환하는 private 헬퍼를 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :shared:test --tests 'com.kista.shared.kernel.TradingPolicySettingsTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`allowedIp()` 없음).

- [ ] **Step 3: 구현 — 정책 값 타입·기본값**

`BrokerSettings.java`:

```java
// 증권사별 신규 계좌 등록·연결 테스트 허용 정책 — TradingPolicySettings.brokers() 값 타입.
// 소유자는 trading-core(정책이 지키는 불변식이 AccountService.register()에 있음), admin은 편집만 한다.
public record BrokerSettings(
        boolean enabled,  // 신규 등록 허용 여부
        String allowedIp  // 거래소 API 키에 등록할 서버 공인 IP 안내 (업비트만 값, 그 외 null) — 관리자 PUT으로는 바꾸지 않는다
) {
}
```

`TradingPolicySettings.java` `defaults()`:

```java
    public static final String UPBIT_ALLOWED_IP = "168.107.63.152"; // OCI Reserved IPv4 — 업비트 API 키 허용 IP 안내값

    // 현재 운영 동작을 보존하는 기본값 — 저장 행이 없을 때와 신규 enum 상수 백필에 쓰인다
    public static TradingPolicySettings defaults() {
        Map<Broker, BrokerSettings> brokers = new EnumMap<>(Broker.class);
        for (Broker broker : Broker.values()) {
            brokers.put(broker, new BrokerSettings(true, null));
        }
        brokers.put(Broker.UPBIT, new BrokerSettings(false, UPBIT_ALLOWED_IP)); // 단계 배포 — 운영자가 관리자 설정에서 켠다
```

DCA 줄:

```java
        strategies.put(StrategyType.DCA, new StrategyCreationSettings(false, // 단계 배포 — 운영자가 관리자 설정에서 켠다
                field(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD, StrategyTicker.BTC, StrategyTicker.ETH),
                        StrategyTicker.UPRO), null, null, null, null));
```

그 외 `new BrokerSettings(x)` 호출(Files 목록의 테스트 6곳 + `AdminSettingsRequest`)을 2인자로 바꾼다.

- [ ] **Step 4: 구현 — 관리자 PUT 누락 키 보존**

`AdminSettingsRequest.java` `toDomain()`을 교체:

```java
    // currentBrokers: 현재 저장된 증권사 정책 — 요청에 없는 증권사 키는 현재 값을 유지하고(구 UI 호환), allowedIp는 항상 현재 값을 유지한다
    public RuntimeSettingsBundle toDomain(Map<Broker, BrokerSettings> currentBrokers) {
        // 모든 enum 키와 전략별 필수 필드를 먼저 변환·검증한 뒤 도메인 설정을 생성한다 — brokers/strategies는
        // trading-core 소유 정책(TradingPolicySettings), auth/benchmarks는 root 소유(RuntimeSettings)로 갈라 담는다.
        Map<Broker, BrokerSettings> brokerSettings = new EnumMap<>(currentBrokers);
        brokers.forEach((key, value) -> brokerSettings.put(key,
                new BrokerSettings(require(value, "broker").enabled(), allowedIpOf(currentBrokers, key))));
        Map<StrategyType, StrategyCreationSettings> strategySettings = new EnumMap<>(StrategyType.class);
        strategies.forEach((key, value) -> strategySettings.put(key,
                require(value, "strategy").toDomain(key)));
        BenchmarkSettings benchmarkSettings = benchmarks != null ? benchmarks.toDomain() : null;
        return new RuntimeSettingsBundle(new RuntimeSettings(auth.approvalRequired(), benchmarkSettings),
                new TradingPolicySettings(brokerSettings, strategySettings));
    }

    private static String allowedIpOf(Map<Broker, BrokerSettings> currentBrokers, Broker broker) {
        BrokerSettings current = currentBrokers.get(broker);
        return current != null ? current.allowedIp() : null;
    }
```

`brokers` 필드 `@Schema` 설명을 `"증권사별 신규 등록/연결 테스트 활성화 설정 (key=Broker, 생략한 증권사는 현재 값 유지)"`로 바꾼다.

`AdminSettingsController.updateSettings`:

```java
        // 요청에 빠진 증권사 키는 현재 정책 값을 유지한다 — 신규 증권사(UPBIT)를 모르는 구 관리자 화면 호환
        RuntimeSettingsBundle requested = request.toDomain(adminSettingsUseCase.getSettings().tradingPolicy().brokers());
        return noStore(RuntimeSettingsResponse.from(
                adminSettingsUseCase.updateSettings(adminId, requested, request.benchmarks() != null)));
```

`AdminSettingsControllerTest`의 기존 PUT 테스트들(90·120행 성공, 163~252행 400 검증)은 컨트롤러가 이제 `getSettings()`를 먼저 호출하므로, 클래스에 `@BeforeEach`를 추가한다:

```java
    @BeforeEach
    void stubCurrentSettings() {
        // PUT은 누락 증권사 키 보존을 위해 현재 정책을 먼저 읽는다
        when(adminSettingsUseCase.getSettings()).thenReturn(RuntimeSettingsBundle.defaults());
    }
```

`RuntimeSettingsResponse.java`:

```java
        bundle.tradingPolicy().brokers().forEach((key, value) -> brokers.put(key, new BrokerResponse(value.enabled(), value.allowedIp())));
```

```java
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrokerResponse(
            @Schema(description = "증권사 신규 계좌 등록 허용 여부")
            boolean enabled,
            @Schema(description = "거래소 API 키에 등록할 서버 공인 IP (업비트만, 그 외 생략)", example = "168.107.63.152")
            String allowedIp) { // 증권사 신규 등록 공개 설정
    }
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :shared:test --tests 'com.kista.shared.kernel.*' :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.out.persistence.policy.TradingPolicySettingsPersistenceAdapterTest' --tests 'com.kista.tradingcore.trading.application.service.TradingPolicyServiceTest' :test --tests 'com.kista.api.admin.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: 문서 갱신**

`docs/agents/modules/api/admin.md` 런타임 설정 항목에 한 줄 추가: `- 관리자 설정 PUT의 brokers는 부분 맵을 허용한다 — 생략한 증권사 키는 현재 값을 유지(AdminSettingsController가 getSettings()로 현재 정책을 읽어 AdminSettingsRequest.toDomain(currentBrokers)에 넘김), allowedIp는 PUT으로 바꾸지 않는다(기본값 TradingPolicySettings.UPBIT_ALLOWED_IP). UPBIT·DCA는 기본 비활성.` 또한 운영 메모 한 줄: `- 기존 운영 행의 DCA 티커 허용값에는 BTC/ETH가 없다(키 단위 백필이라 값 내부는 보충되지 않음) — 코인 DCA를 열 때 관리자 화면에서 DCA 티커에 BTC/ETH를 추가해야 한다.`

- [ ] **Step 7: 커밋**

```bash
git add shared/src src/main/java/com/kista/api/admin src/test/java/com/kista/api/admin trading-core/src/test docs/agents/modules/api/admin.md
git commit -m "$(cat <<'EOF'
feat(admin): 업비트를 기본 비활성으로 두고 허용 IP를 노출하며 누락된 증권사 키를 보존한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 3: 주문 수량 BigDecimal 전환 (`orders`·`dca_execution` numeric(20,8))

기계적 전환 태스크다. 아래 "전환 규칙"과 "파일 전수 목록"(스크래치 worktree에서 타입만 바꿔 `-Xmaxerrs 10000`으로 main·test를 컴파일해 얻은 실제 오류 목록)을 따른다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/Quantities.java`
- Create: `trading-core/src/main/resources/db/migration-trading/V8__order_quantity_numeric.sql`
- Modify (main, 전수):
  - `trading-core/.../trading/domain/model/Order.java` — 필드 2개·14인자 보조 생성자·팩토리
  - `trading-core/.../trading/domain/model/DcaExecution.java:18,25`
  - `trading-core/.../trading/adapter/out/persistence/OrderEntity.java:64-75`
  - `trading-core/.../trading/adapter/out/persistence/DcaExecutionEntity.java:51-52`
  - `trading-core/.../trading/adapter/out/persistence/DcaExecutionJpaRepository.java:41`
  - `trading-core/.../trading/adapter/out/persistence/OrderJpaRepository.java:73-87`
  - `trading-core/.../trading/adapter/out/persistence/OrderPersistenceAdapter.java:198-203,266`
  - `trading-core/.../trading/application/port/output/OrderPort.java:72`
  - `trading-core/.../trading/application/service/TradingReporter.java:164,169`
  - `trading-core/.../trading/application/service/OrderSubmitter.java:87`
  - `trading-core/.../trading/application/service/ReorderService.java:59-62,112`
  - `trading-core/.../trading/application/service/ReorderBuyBudgetService.java:73`
  - `trading-core/.../trading/application/service/TradingPreviewService.java:178`
  - `trading-core/.../trading/adapter/out/MockSimulationDataAdapter.java:59`
  - `trading-core/.../stats/application/service/AccountStatisticsService.java:70-75`
  - `trading-core/.../trading/adapter/in/web/dto/StrategyOrdersResponse.java:25,31`
  - `trading-core/.../trading/adapter/in/web/dto/ExecuteOrdersResponse.java:24`
  - `trading-core/.../trading/adapter/in/web/dto/NextOrdersResponse.java:107` (`TodayOrderItem.quantity`만 — 79행 `OrderItem`은 PlannedOrder 기반이라 int 유지)
  - `trading-core/.../trading/adapter/in/web/dto/DcaExecutionPageResponse.java:16,19`
  - `shared/src/main/java/com/kista/shared/contract/trading/OrderResponse.java:24,28`
  - `shared/src/main/java/com/kista/shared/contract/stats/PortfolioOrderResponse.java:16`
  - `src/main/java/com/kista/api/admin/adapter/in/web/dto/AdminTradeResponse.java:39,47`
  - `src/main/java/com/kista/api/admin/adapter/in/telegram/TelegramBotService.java:153-154` (`%d주` → 런타임 `IllegalFormatConversionException` 방지)
  (`trading-core/...` = `trading-core/src/main/java/com/kista/tradingcore`)
- Modify (test, 컴파일 오류 전수 19파일): `src/test/java/com/kista/api/admin/adapter/in/web/AdminTradeControllerTest.java`, `trading-core/src/test/java/com/kista/tradingcore/stats/adapter/in/web/PortfolioQueryInternalControllerTest.java`, `.../trading/adapter/out/persistence/{DcaPersistenceAdapterDbTest,OrderPersistenceAdapterTest}.java`, `.../trading/application/service/{BuyOrderPriceCapperTest,DcaExecutionQueryServiceTest,DcaRoundPlannerTest,DcaSummaryAssemblerTest,ManualTradingServiceTest,OrderCancelServiceTest,OrderSubmissionReconcilerTest,OrderSubmitterTest,ReorderBuyBudgetServiceTest,ReorderServiceTest,SubmittingOrderAwaiterTest,TradingCandidatePlannerDcaTest,TradingOrderExecutorTest,TradingReporterTest,TradingServiceTest}.java`, `.../trading/application/service/support/SelectionChainTest.java` (+ Step 7 전체 스위트가 드러내는 런타임 단언 실패 파일)
- Test (create): `trading-core/src/test/java/com/kista/tradingcore/trading/domain/model/OrderQuantityNormalizationTest.java`; (modify) `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/OrderPersistenceAdapterDbTest.java`, `src/test/java/com/kista/api/admin/adapter/in/telegram/TelegramBotServiceTest.java`
- Docs: `docs/agents/modules/tradingcore/trading.md`

**전환 규칙:**
1. `Order.quantity`·`Order.filledQuantity`, `DcaExecution.quantity`는 `BigDecimal`. compact 생성자에서 `Quantities.normalize`로 꼬리 0 제거(음수 scale은 0으로).
2. `int`를 받는 팩토리(`filledManual`·`withQuantity`·`reorder`)는 시그니처 유지, 내부에서 `BigDecimal.valueOf(int)`. `fromPlanned`는 `BigDecimal.valueOf(p.quantity())`, `toPlanned()`는 `quantity.intValueExact()`.
3. 브로커 wire·커널로 가는 주식 전용 경로(`OrderInstruction`, `PlacedOrderView`, `DailyTransaction`, `ReorderResult`, `PlannedOrder`)는 `intValueExact()`.
4. 금액 계산은 `BigDecimal.valueOf(o.quantity())` → `o.quantity()`.
5. 응답 DTO·contract record의 `quantity`/`filledQuantity`는 `BigDecimal`. `ReorderRequest`·`TradeCorrectionRequest`(관리자 주식 입력)·`TradeEventMessage`(알림)·`PlannedOrder`·`CyclePosition.holdings`·privacy 표는 그대로 int.
6. 문자열 포맷에서 `%d`로 수량을 찍던 곳은 `%s` + `toPlainString()`.
7. 테스트: `new Order(..., 3, ...)`의 int 리터럴 → `BigDecimal.valueOf(3)`(null 리터럴 그대로), `verify(...).markFilled(id, 3, ...)` → `BigDecimal.valueOf(3)`, `assertThat(o.quantity()).isEqualTo(3)` → `isEqualByComparingTo("3")`. 정규화 덕분에 `isEqualTo(BigDecimal.valueOf(3))`도 동작한다.

**Interfaces:**
- Consumes: Task 1 `Market`, `StrategyTicker.getMarket()`
- Produces:
  - `record Order(..., BigDecimal quantity, ..., BigDecimal filledQuantity, BigDecimal filledPrice)` (구성 순서 불변)
  - `record DcaExecution(..., BigDecimal quantity, ...)`, `DcaExecution.ordered(UUID, LocalDate, UUID, BigDecimal, BigDecimal)`
  - `OrderPort.markFilled(UUID orderId, BigDecimal filledQuantity, BigDecimal filledPrice, OrderStatus status)`
  - `final class Quantities { static BigDecimal normalize(BigDecimal) }`
  - contract `OrderResponse.quantity/filledQuantity`, `PortfolioOrderResponse.quantity`: `BigDecimal`

- [ ] **Step 1: 실패 테스트 작성**

`trading-core/src/test/java/com/kista/tradingcore/trading/domain/model/OrderQuantityNormalizationTest.java`:

```java
package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderQuantityNormalizationTest {

    private static Order order(String quantity, String filled) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 10, 17),
                StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY,
                new BigDecimal(quantity), new BigDecimal("22.00"), OrderStatus.FILLED, "X1",
                filled == null ? null : new BigDecimal(filled), new BigDecimal("22.00"));
    }

    @Test
    void dbScaleTrailingZerosAreStripped() {
        // numeric(20,8)에서 읽은 값 — equals(scale 민감)와 JSON 모두 정수 주식 수량과 같아야 한다
        assertThat(order("10.00000000", "10.00000000")).isEqualTo(order("10", "10"));
        assertThat(order("100.00000000", null).quantity().toString()).isEqualTo("100"); // 1E+2 금지
        assertThat(order("0.00012300", null).quantity().toString()).isEqualTo("0.000123");
        assertThat(order("0E-8", null).quantity().toString()).isEqualTo("0");
    }

    @Test
    void jsonKeepsIntegerShapeForStock() throws Exception {
        String json = JsonMapper.builder().build().writeValueAsString(order("10.00000000", "100.00000000"));
        assertThat(json).contains("\"quantity\":10,").contains("\"filledQuantity\":100,");
    }

    @Test
    void dcaExecutionQuantityIsNormalized() {
        DcaExecution e = DcaExecution.ordered(UUID.randomUUID(), LocalDate.of(2026, 10, 17), UUID.randomUUID(),
                new BigDecimal("3.00000000"), new BigDecimal("50.00"));
        assertThat(e.quantity().toString()).isEqualTo("3");
    }
}
```

`OrderPersistenceAdapterDbTest.java`에 추가:

```java
    @Test
    void quantityRoundTripsAsPlainInteger() {
        PlannedOrder planned = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 10, new BigDecimal("22.00"));
        adapter.saveAll(List.of(Order.fromPlanned(planned, accountId, cycleId)));

        Order saved = adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now()).getFirst();

        // DB는 numeric(20,8) — 읽은 뒤에도 scale 0 정수여야 한다
        assertThat(saved.quantity()).isEqualTo(BigDecimal.TEN);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT numeric_precision || ',' || numeric_scale FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'orders' AND column_name = 'quantity'
                """, String.class)).isEqualTo("20,8");
    }

    @Test
    void fractionalCryptoQuantityIsStored() {
        Order fractional = new Order(null, accountId, cycleId, LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                com.kista.shared.kernel.OrderTiming.AT_CLOSE, OrderDirection.BUY,
                new BigDecimal("0.00012345"), new BigDecimal("22.00"), OrderStatus.PLANNED, null, null, null);
        adapter.saveAll(List.of(fractional));

        assertThat(adapter.findPlannedByCycleAndDate(cycleId, LocalDate.now()).getFirst().quantity())
                .isEqualByComparingTo("0.00012345");
    }

    @Test
    void sellReservationSumReadsNumericColumn() {
        PlannedOrder sell = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.SELL, 7, new BigDecimal("22.00"));
        adapter.saveAll(List.of(Order.fromPlanned(sell, accountId, cycleId)));

        // SUM(numeric)은 BigDecimal — Long 수신이면 런타임 변환 실패
        assertThat(adapter.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(accountId, LocalDate.now(), StrategyTicker.SOXL))
                .isEqualTo(7);
    }
```

(`sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker`의 실제 파라미터 순서는 `OrderPersistenceAdapter.java:198-200`에 맞춘다.)

`TelegramBotServiceTest.java`에 추가(기존 `history_command_with_days_delegates_to_usecase` 옆, 같은 stub 방식):

```java
    @Test
    void history_command_formats_decimal_quantity() {
        when(portfolioQueryPort.getHistory(any(), any(), any(), eq(StrategyTicker.SOXL))).thenReturn(List.of(
                new PortfolioOrderResponse(LocalDate.of(2026, 10, 16), StrategyTicker.SOXL, OrderDirection.BUY,
                        OrderType.LOC, new BigDecimal("12"), new BigDecimal("22.5000"))));

        sut.handle(update("/history 14"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramHttpClient, atLeastOnce()).sendMessage(any(), captor.capture());
        assertThat(captor.getValue()).contains("12주");
    }
```

(사용자 조회 stub·`telegramHttpClient` 필드명·`sendMessage` 시그니처는 같은 파일 기존 `/history` 테스트가 쓰는 이름에 맞춘다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.model.OrderQuantityNormalizationTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`BigDecimal cannot be converted to Integer`).

- [ ] **Step 3: 마이그레이션**

`trading-core/src/main/resources/db/migration-trading/V8__order_quantity_numeric.sql`:

```sql
-- 코인 소수 수량(업비트 8자리) 수용 — 주문·DCA 회차 수량을 integer → numeric(20,8)로 넓힌다.
-- trading-core 단독 스키마라 root expand/contract 제약 밖(kista-trading은 stop-first 단일 인스턴스).
-- 주의: 이 마이그레이션 이후 구 이미지(int 엔티티)는 ddl-auto validate에서 기동 실패 — 이미지 롤백 불가.
ALTER TABLE trading.orders ALTER COLUMN quantity TYPE numeric(20,8);
ALTER TABLE trading.orders ALTER COLUMN filled_quantity TYPE numeric(20,8);
ALTER TABLE trading.dca_execution ALTER COLUMN quantity TYPE numeric(20,8);
```

- [ ] **Step 4: 도메인·영속 구현**

`Quantities.java`:

```java
package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 주문 수량 정규화 — DB numeric(20,8) 왕복 뒤 생기는 꼬리 0을 걷어 equals·JSON을 정수 주식 수량과 같게 맞춘다
public final class Quantities {

    private Quantities() {}

    // stripTrailingZeros()만 쓰면 100 → 1E+2(JSON 지수 표기)라 음수 scale은 0으로 되돌린다
    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
```

`Order.java` 변경점:

```java
        BigDecimal quantity,             // 주문 수량 — 주식은 정수 값, 코인은 소수 8자리 (금액 시장가 매수는 추정 수량)
        BigDecimal price,                // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
        OrderStatus status,              // 주문 상태
        String externalOrderId,          // 증권사 부여 주문 번호 (KIS: ODNO, Toss: orderId), PLACED 이후 설정
        BigDecimal filledQuantity,       // 체결 수량 (null=미확인, 0=미체결)
        BigDecimal filledPrice           // 체결 가중평균가 (null=미체결)
) {
    public static final String UNKNOWN_LEG = "UNKNOWN";

    public Order(UUID id, UUID accountId, UUID strategyCycleId, LocalDate tradeDate, StrategyTicker ticker,
                 OrderType orderType, OrderTiming timing, OrderDirection direction,
                 BigDecimal quantity, BigDecimal price, OrderStatus status, String externalOrderId,
                 BigDecimal filledQuantity, BigDecimal filledPrice) {
        this(id, accountId, strategyCycleId, tradeDate, ticker, orderType, timing, direction,
                UNKNOWN_LEG, quantity, price, status, externalOrderId, filledQuantity, filledPrice);
    }

    public Order {
        if (orderLeg == null || orderLeg.isBlank()) orderLeg = UNKNOWN_LEG;
        quantity = Quantities.normalize(quantity);             // DB scale 8 → 정수 주식 수량 형태 유지
        filledQuantity = Quantities.normalize(filledQuantity);
    }

    // 커널 계획 주문(PlannedOrder) → 특정 계좌·사이클 PLANNED Order 승격 — 유일한 승격 지점(TradingOrderPlanner.savePlannedOrders)
    public static Order fromPlanned(PlannedOrder p, UUID accountId, UUID strategyCycleId) {
        return new Order(null, accountId, strategyCycleId, p.tradeDate(), p.ticker(), p.orderType(),
                p.timing(), p.direction(), p.orderLeg(), BigDecimal.valueOf(p.quantity()), p.price(),
                OrderStatus.PLANNED, null, null, null);
    }

    // 영속 Order → 계획 주문 강등 (주식 전용 — 커널은 정수 수량)
    public PlannedOrder toPlanned() {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity.intValueExact(), price);
    }
```

`filledManual`: `UNKNOWN_LEG, BigDecimal.valueOf(quantity), price, OrderStatus.FILLED, externalOrderId, BigDecimal.valueOf(quantity), price`. `withQuantity`: `orderLeg, BigDecimal.valueOf(newQuantity), price`. `reorder`: `source.orderLeg(), BigDecimal.valueOf(quantity), price`.

`DcaExecution.java`:

```java
        BigDecimal quantity,        // 계획 수량 (축소 반영 — 코인은 소수)
```
```java
    public DcaExecution {
        quantity = Quantities.normalize(quantity); // DB scale 8 → 정수 주식 수량 형태 유지
    }

    public static DcaExecution ordered(UUID strategyId, LocalDate tradeDate, UUID orderId, BigDecimal quantity, BigDecimal referencePrice) {
```

`OrderEntity.java`:

```java
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity; // 주문 수량 (주식 정수·코인 소수 8자리)
```
```java
    @Column(name = "filled_quantity", precision = 20, scale = 8)
    private BigDecimal filledQuantity; // 체결 수량 (null=미확인, 0=미체결)
```

`DcaExecutionEntity.java`: `@Column(name = "quantity", precision = 20, scale = 8) private BigDecimal quantity; // 계획 수량`. `DcaExecutionJpaRepository.java:41` `@Param("quantity") BigDecimal quantity`.

`OrderJpaRepository.java` 74·84행:

```java
    // PostgreSQL SUM(numeric)은 numeric을 반환하므로 BigDecimal로 수신한다
```
```java
    BigDecimal sumPlannedOrPlacedSellQuantityByAccountIdAndTradeDateAndTicker(
```

`OrderPersistenceAdapter.java` 201-203행:

```java
        BigDecimal quantity = repository.sumPlannedOrPlacedSellQuantityByAccountIdAndTradeDateAndTicker(
                accountId, tradeDate, ticker.name());
        return quantity != null ? quantity.intValueExact() : 0; // 주식 SELL 예약 전용 — 종목 필터라 코인 소수 수량은 섞이지 않는다
```

(`ticker.name()` 등 기존 인자 표현은 그대로 둔다.) `markFilled(UUID orderId, BigDecimal filledQuantity, BigDecimal filledPrice, OrderStatus status)` — `OrderPort.java:72`도 같은 시그니처.

- [ ] **Step 5: 소비처 전환 (main 전수)**

- `TradingReporter.java:164` → `int orderedQuantity = order.quantity() != null ? order.quantity().intValueExact() : 0;`, `:169` → `orderPort.markFilled(order.id(), BigDecimal.valueOf(filledQuantity), avgFilledPrice, newStatus);`
- `OrderSubmitter.java:87` → `order.quantity().intValueExact()` (OrderInstruction은 주식 정수 wire)
- `ReorderService.java` — 62행 `SelectionChain.validate(...)` 바로 아래에 추가:
  ```java
          // 재주문은 주식 정수 수량 전용 — 코인 주문(소수 수량)은 관리자 재주문 대상이 아니다
          if (sourceOrder.ticker().getMarket() != Market.US_STOCK) {
              throw new IllegalArgumentException("코인 주문은 재주문할 수 없습니다.");
          }
  ```
  112행 → `sourceOrder.price(), sourceOrder.quantity().intValueExact(), direction);` (`import com.kista.shared.kernel.Market;`)
- `ReorderBuyBudgetService.java:73` → `source.price().multiply(source.quantity())`
- `TradingPreviewService.java:178` → `.map(o -> o.price().multiply(o.quantity()))`
- `MockSimulationDataAdapter.java:59` → `order.quantity().intValueExact()` (MOCK 계좌는 주식 전용)
- `AccountStatisticsService.java:70-75`:
  ```java
          List<DailyTransaction> items = filled.stream()
                  // 일별 체결 요약(DailyTransaction)은 주식 정수 수량 계약 — 코인 체결은 1차에서 대시보드 집계 밖
                  .filter(o -> o.ticker().getMarket() == Market.US_STOCK)
                  .filter(o -> o.filledQuantity() != null && o.filledQuantity().signum() > 0)
                  .map(o -> {
                      BigDecimal price = o.filledPrice() != null ? o.filledPrice() : o.price();
                      int qty = o.filledQuantity().intValueExact();
  ```
  (`import com.kista.shared.kernel.Market;`)
- DTO: `StrategyOrdersResponse.Item.quantity`·`filledQuantity`, `ExecuteOrdersResponse.PlacedOrderItem.quantity`, `NextOrdersResponse.TodayOrderItem.quantity`, `DcaExecutionPageResponse.Item.quantity`·`OrderSummary.filledQuantity` → `BigDecimal`(필요 시 `import java.math.BigDecimal;`)
- contract `OrderResponse.java` 24·28행, `PortfolioOrderResponse.java` 16행 → `BigDecimal quantity, // 주문 수량 (주식 정수 값·코인 소수)` 형태
- root `AdminTradeResponse.java` 39행 `BigDecimal quantity,`, 47행 `BigDecimal filledQuantity,` (`import java.math.BigDecimal;`)
- root `TelegramBotService.java:153-154`:
  ```java
                      list.forEach(h -> sb.append("%s %s %s %s주 $%.4f%n".formatted(
                              h.tradeDate(), h.direction(), h.orderType(), h.quantity().toPlainString(), h.price())));
  ```

Run: `./gradlew :trading-core:compileJava compileJava 2>&1 | grep -E "error:|BUILD"`
Expected: `BUILD SUCCESSFUL` (오류가 남으면 위 규칙으로 고친다 — 목록 외 파일이 나오면 이 태스크 보고에 추가로 적는다).

- [ ] **Step 6: 테스트 전환 + 신규 테스트 통과**

전환 규칙 7번으로 Files의 테스트 19파일을 고친다.

Run: `docker compose up -d postgres && ./gradlew :trading-core:compileTestJava compileTestJava 2>&1 | grep -E "error:|BUILD"` → `BUILD SUCCESSFUL`
Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.model.OrderQuantityNormalizationTest' --tests 'com.kista.tradingcore.trading.adapter.out.persistence.OrderPersistenceAdapterDbTest' --tests 'com.kista.tradingcore.trading.adapter.out.persistence.DcaPersistenceAdapterDbTest' :test --tests 'com.kista.api.admin.adapter.in.telegram.TelegramBotServiceTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: 런타임 단언 전수 확인 (이 태스크의 게이트)**

`isEqualTo(5)`처럼 컴파일은 되지만 BigDecimal과 Integer를 비교해 실패하는 단언은 컴파일러가 못 잡는다. 이 태스크만은 두 프로세스 테스트를 끝까지 돌린다.

Run: `./gradlew :trading-core:test :test 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`. 실패하면 `grep -l 'failures="[1-9]' trading-core/build/test-results/test/TEST-*.xml build/test-results/test/TEST-*.xml`로 파일을 찾아 규칙 7로 고친 뒤 해당 클래스만 재실행한다.

- [ ] **Step 8: 문서 갱신**

`docs/agents/modules/tradingcore/trading.md`에 한 줄 추가: `- 주문 수량: orders.quantity/filled_quantity·dca_execution.quantity는 numeric(20,8), Order/DcaExecution은 BigDecimal(compact 생성자에서 Quantities.normalize — 꼬리 0 제거). PlannedOrder·매칭 공식·CyclePosition.holdings·privacy 표는 int이고 주식 경로(브로커 wire·커널 재입력)는 intValueExact()로 변환한다. 재주문(ReorderService)과 일별 체결 요약(AccountStatisticsService)은 주식 주문만 다룬다.`

- [ ] **Step 9: 커밋**

```bash
git add shared/src trading-core/src src/main src/test docs/agents/modules/tradingcore/trading.md
git commit -m "$(cat <<'EOF'
refactor(trading): 주문·DCA 회차 수량을 소수 8자리 BigDecimal로 넓힌다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 4: DCA `amount`·`executionHour`·시장 검증 + 주식 배치의 코인 전략 제외

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V9__dca_amount_execution_hour.sql`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/DcaConfig.java` (전체)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategy.java:77,80` (`amountUsd()` → `amount()`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaVersionEntity.java:37-38,54`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/StrategyDcaDetailPersistenceAdapter.java:33,73-77`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConfigRequest.java` (전체)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaResponse.java` (전체)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationService.java:67-70`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/DcaConfigService.java:32`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/schedule/BatchContextFactory.java:62-65`
- Modify: `trading-core/src/testFixtures/java/com/kista/tradingcore/support/TradingFixtures.java` (`upbitAccount` 추가)
- Test: `trading-core/src/test/java/com/kista/tradingcore/matching/domain/model/DcaConfigTest.java`, `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConfigRequestTest.java`(create), `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/StrategyServiceTest.java`, `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/DcaConfigServiceTest.java`, `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/schedule/BatchContextFactoryTest.java`, `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/DcaPersistenceAdapterDbTest.java`, `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/TradingCycleControllerTest.java`
- Docs: `docs/agents/modules/tradingcore/trading.md`, `src/test/resources/architecture/flows.yml`(전략 등록 검증 desc)

**Interfaces:**
- Consumes: Task 1 `Market`, `OrderType.MARKET`, `Broker.getMarket()`, `StrategyTicker.getMarket()`
- Produces:
  - `record DcaConfig(OrderDirection direction, BigDecimal amount, OrderType orderType, DcaFrequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth, List<DcaCondition> conditions, Integer executionHour)` + 기존 6·7인자 보조 생성자(executionHour=null)
  - `DcaConfig.CRYPTO_MIN_AMOUNT = 5000`
  - `void DcaConfig.requireCompatible(Market accountMarket)`
  - DB: `strategy_dca_version.amount numeric(20,2)`, `execution_hour smallint NULL CHECK 0~23`
  - API 요청 `dca.amount | dca.amountUsd`(하나 필수, 둘 다면 같은 값), `dca.executionHour`; 응답 `dca.amount`, `dca.amountUsd`(같은 값), `dca.executionHour`
  - `TradingFixtures.upbitAccount(UUID id, UUID userId)`

- [ ] **Step 1: 실패 테스트 작성**

`DcaConfigTest.java`에 추가:

```java
    @Test
    void market_order_requires_execution_hour_and_allows_weekend() {
        DcaConfig crypto = new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.WEEKLY, DayOfWeek.SUNDAY, null, List.of(), 9);
        assertThat(crypto.executionHour()).isEqualTo(9);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("executionHour");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 24))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0~23");
        // 주식 주문유형은 주말·실행 시각을 허용하지 않는다
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC,
                DcaFrequency.WEEKLY, DayOfWeek.SUNDAY, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC,
                DcaFrequency.DAILY, null, null, List.of(), 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireCompatible_enforces_market_rules() {
        DcaConfig crypto = new DcaConfig(OrderDirection.BUY, new BigDecimal("5000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0);
        crypto.requireCompatible(Market.CRYPTO_KRW);
        assertThatThrownBy(() -> crypto.requireCompatible(Market.US_STOCK)).hasMessageContaining("MOO 또는 MOC");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.DAILY, null, null)
                .requireCompatible(Market.CRYPTO_KRW)).hasMessageContaining("MARKET");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("4999"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0).requireCompatible(Market.CRYPTO_KRW)).hasMessageContaining("5,000");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("5000.5"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0).requireCompatible(Market.CRYPTO_KRW)).hasMessageContaining("정수");
        new DcaConfig(OrderDirection.BUY, new BigDecimal("5000.00"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0).requireCompatible(Market.CRYPTO_KRW); // 5000.00은 정수 값
    }
```

(import 추가: `com.kista.shared.kernel.Market`, `java.util.List`.)

`trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/dto/DcaConfigRequestTest.java`:

```java
package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConfigRequestTest {

    private static DcaConfigRequest request(String amount, String amountUsd, OrderType type, Integer hour) {
        return new DcaConfigRequest(OrderDirection.BUY, amount == null ? null : new BigDecimal(amount),
                amountUsd == null ? null : new BigDecimal(amountUsd), type, DcaFrequency.DAILY, null, null, null, hour);
    }

    @Test
    void amountOrLegacyAmountUsdIsAccepted() {
        assertThat(request("300", null, OrderType.MOC, null).toDomain().amount()).isEqualByComparingTo("300");
        assertThat(request(null, "300", OrderType.MOC, null).toDomain().amount()).isEqualByComparingTo("300");
    }

    @Test
    void sameValueWithDifferentScaleIsNotAConflict() {
        assertThat(request("300", "300.00", OrderType.MOC, null).toDomain().amount()).isEqualByComparingTo("300");
    }

    @Test
    void differentValuesOrNoneAreRejected() {
        assertThatThrownBy(() -> request("300", "301", OrderType.MOC, null).toDomain())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amountUsd");
        assertThatThrownBy(() -> request(null, null, OrderType.MOC, null).toDomain())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionHourIsIgnoredForStockOrderTypes() {
        assertThat(request("300", null, OrderType.MOC, 9).toDomain().executionHour()).isNull();
        assertThat(request("10000", null, OrderType.MARKET, 9).toDomain().executionHour()).isEqualTo(9);
    }
}
```

`StrategyServiceTest.java`에 추가(기존 DCA 헬퍼 재사용):

```java
    private static DcaConfig cryptoDcaConfig() {
        return new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET, DcaFrequency.DAILY,
                null, null, List.of(), 9);
    }

    private void stubDcaPolicyWithCrypto() {
        when(strategyCreationPolicyPort.find(StrategyType.DCA)).thenReturn(Optional.of(new StrategyCreationSettings(true,
                new StrategyFieldSettings<>(true, List.of(StrategyTicker.UPRO, StrategyTicker.QLD, StrategyTicker.BTC, StrategyTicker.ETH),
                        StrategyTicker.UPRO), null, null, null, null)));
    }

    @Test
    @DisplayName("주식 계좌에 코인 종목 DCA는 시장 불일치로 거부한다")
    void register_dca_rejectsTickerMarketMismatch() {
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(ownerAccount());

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.BTC, cryptoDcaConfig())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("시장");
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("코인 계좌는 DCA 외 전략을 등록할 수 없다")
    void register_cryptoAccount_rejectsNonDca() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID,
                commandFor(StrategyType.INFINITE, StrategyTicker.SOXL, 20, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(strategyPort, never()).save(any());
    }

    @Test
    @DisplayName("코인 계좌 DCA는 MARKET·원 단위 정수 금액만 허용한다")
    void register_cryptoDca_rejectsStockOrderType() {
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));

        assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.BTC, dcaConfig(OrderDirection.BUY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MARKET");
    }

    @Test
    @DisplayName("코인 계좌 BTC DCA는 실행 시각과 함께 저장된다")
    void register_cryptoDca_succeeds() {
        stubDcaRegistration(StrategyTicker.BTC);
        stubDcaPolicyWithCrypto();
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, StrategyTicker.BTC)).thenReturn(List.of());

        strategyService.register(USER_ID, ACCOUNT_ID, dcaCommand(StrategyTicker.BTC, cryptoDcaConfig()));

        verify(dcaDetailPort).save(STRATEGY_VERSION_ID, cryptoDcaConfig());
        verifyNoInteractions(marginPort, brokerPricePort);
    }
```

(`stubDcaRegistration`이 정책·계좌를 이미 stub하므로 `register_cryptoDca_succeeds`에서 두 번째 stub이 덮어쓴다 — strict stubs가 "unnecessary stubbing"을 내면 `stubDcaRegistration` 안의 정책·계좌 stub을 `lenient()`로 바꾼다.)

`DcaConfigServiceTest.java` — `setUp()` 끝에 추가하고 테스트 추가:

```java
        // 시장 규칙 검증용 계좌 — 기존 테스트는 주식(KIS) 계좌
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID))
                .thenReturn(com.kista.tradingcore.support.TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));
```

```java
    @Test
    void updateConfig_stockAccount_rejectsMarketOrderType() {
        when(strategyPort.findByIdOrThrow(STRATEGY_ID)).thenReturn(strategy(StrategyType.DCA));
        DcaConfig crypto = new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, java.util.List.of(), 9);

        assertThatThrownBy(() -> service.updateConfig(STRATEGY_ID, USER_ID, crypto))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MOO 또는 MOC");
        verify(strategyVersionPort, never()).save(any());
    }
```

`BatchContextFactoryTest.java`에 추가:

```java
    @Test
    void buildAll_skipsCryptoAccountStrategies() {
        UUID upbitAccountId = UUID.randomUUID();
        Strategy stock = mockStrategy(ACCOUNT_ID);
        Strategy crypto = new Strategy(UUID.randomUUID(), upbitAccountId, StrategyType.DCA,
                StrategyStatus.ACTIVE, StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        when(accountPort.findAll()).thenReturn(List.of(mockAccount(ACCOUNT_ID), TradingFixtures.upbitAccount(upbitAccountId, USER_ID)));
        when(tradingUserProfilePort.findAllByUserIds(any())).thenReturn(Map.of(USER_ID, mockUserProfile()));
        when(strategyCyclePort.findLatestByStrategyIds(any()))
                .thenReturn(Map.of(stock.id(), mockCycle(stock.id()), crypto.id(), mockCycle(crypto.id())));

        List<BatchContext> contexts = factory.buildAll(List.of(stock, crypto));

        // 코인 전략은 주식 배치에서 조용히 빠진다 — 관리자 오류 알림 없음
        assertThat(contexts).extracting(c -> c.strategy().id()).containsExactly(stock.id());
        verifyNoInteractions(errorReportPort);
    }
```

`DcaPersistenceAdapterDbTest.java`에 추가(기존 저장 테스트의 선행 행·`versionId` 변수를 재사용):

```java
    @Test
    void saveAndLoad_cryptoConfigWithExecutionHour() {
        DcaConfig crypto = new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.WEEKLY, java.time.DayOfWeek.SUNDAY, null, java.util.List.of(), 23);

        DcaConfig saved = adapter.save(versionId, crypto);

        assertThat(saved.executionHour()).isEqualTo(23);
        assertThat(saved.amount()).isEqualByComparingTo("10000");
        assertThat(saved.dayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
    }
```

`TradingCycleControllerTest.java`에 추가:

```java
    @Test
    void reconfigureDca_conflictingAmounts_returns400() throws Exception {
        mockMvc.perform(put("/api/trading-cycles/{id}/dca-config", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction":"BUY","amount":300,"amountUsd":301,"orderType":"MOC","frequency":"MONTHLY"}
                                """))
                .andExpect(status().isBadRequest());
        verify(dcaConfig, never()).updateConfig(any(), any(), any());
    }

    @Test
    void reconfigureDca_responseCarriesAmountAndAmountUsd() throws Exception {
        when(tradingCycle.getById(CYCLE_ID, USER_ID)).thenReturn(dcaDetail());

        mockMvc.perform(put("/api/trading-cycles/{id}/dca-config", CYCLE_ID)
                        .with(csrf()).with(authentication(userToken(USER_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"direction":"BUY","amount":300,"orderType":"MOC","frequency":"MONTHLY","conditions":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dca.amount").value(300))
                .andExpect(jsonPath("$.dca.amountUsd").value(300));
        verify(dcaConfig).updateConfig(CYCLE_ID, USER_ID, DCA_CONFIG);
    }
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.model.DcaConfigTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(8인자 생성자·`requireCompatible` 없음).

- [ ] **Step 3: 마이그레이션**

`V9__dca_amount_execution_hour.sql`:

```sql
-- DCA 금액을 계좌 통화 단위(amount)로 일반화하고 코인 실행 시각(KST 시)을 추가한다.
-- trading-core 단독 스키마라 root expand/contract 제약 밖(kista-trading stop-first 단일 인스턴스 — 이름 변경과 이미지 교체가 겹치지 않는다).
ALTER TABLE trading.strategy_dca_version RENAME COLUMN amount_usd TO amount;
ALTER TABLE trading.strategy_dca_version RENAME CONSTRAINT strategy_dca_version_amount_usd_check TO strategy_dca_version_amount_check;
ALTER TABLE trading.strategy_dca_version ADD COLUMN execution_hour smallint;
ALTER TABLE trading.strategy_dca_version ADD CONSTRAINT strategy_dca_version_execution_hour_check
    CHECK (execution_hour BETWEEN 0 AND 23);
COMMENT ON COLUMN trading.strategy_dca_version.execution_hour IS '코인 DCA 실행 시각(KST 시 0~23) — 주식은 NULL';
```

- [ ] **Step 4: 도메인 구현 — `DcaConfig`**

`DcaConfig.java` 레코드 헤더·compact 생성자·보조 생성자·검증 메서드 교체(`hasIndicator`/`timing`/`orderLeg`는 그대로):

```java
package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Objects;

// DCA 전략 버전 설정 — strategy_dca_version 1행.
public record DcaConfig(
        OrderDirection direction, // 매수/매도 단방향
        BigDecimal amount,        // 회차당 고정 금액 (계좌 통화 — 주식 USD, 코인 KRW, > 0)
        OrderType orderType,      // 주식 MOO(개장)·MOC(마감), 코인 MARKET
        DcaFrequency frequency,   // 실행 주기
        DayOfWeek dayOfWeek,      // WEEKLY 전용 (주식 월~금, 코인 월~일)
        Integer dayOfMonth,       // MONTHLY 전용 1~31, null이면 말일
        List<DcaCondition> conditions, // 추가 조건 (AND, 입력 순서 보존, 최대 10개)
        Integer executionHour     // 코인 실행 시각 (KST 0~23) — MARKET에서만 필수, 주식은 null
) {
    public static final int MAX_CONDITIONS = 10; // 전략당 조건 상한
    public static final BigDecimal CRYPTO_MIN_AMOUNT = new BigDecimal("5000"); // 업비트 KRW 마켓 최소 주문 금액

    public DcaConfig {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(frequency, "frequency");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount는 0보다 커야 합니다");
        if (orderType != OrderType.MOO && orderType != OrderType.MOC && orderType != OrderType.MARKET) {
            throw new IllegalArgumentException("DCA 주문유형은 MOO·MOC·MARKET만 허용합니다");
        }
        boolean market = orderType == OrderType.MARKET;
        if (market != (executionHour != null)) throw new IllegalArgumentException("executionHour는 MARKET(코인) 주문에서만 필수입니다");
        if (executionHour != null && (executionHour < 0 || executionHour > 23)) throw new IllegalArgumentException("executionHour는 0~23입니다");
        boolean weekly = frequency == DcaFrequency.WEEKLY;
        boolean monthly = frequency == DcaFrequency.MONTHLY;
        if (weekly != (dayOfWeek != null)) throw new IllegalArgumentException("dayOfWeek는 WEEKLY에서만 필수입니다");
        // 코인(24/7)만 주말 요일 허용
        if (weekly && !market && (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY)) throw new IllegalArgumentException("주말 요일은 지정할 수 없습니다");
        if (!monthly && dayOfMonth != null) throw new IllegalArgumentException("dayOfMonth는 MONTHLY에서만 허용합니다");
        if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) throw new IllegalArgumentException("dayOfMonth는 1~31입니다");
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (conditions.size() > MAX_CONDITIONS) throw new IllegalArgumentException("DCA 조건은 최대 " + MAX_CONDITIONS + "개입니다.");
    }

    // 주식 설정(실행 시각 없음) — 기존 호출부 호환
    public DcaConfig(OrderDirection direction, BigDecimal amount, OrderType orderType, DcaFrequency frequency,
                     DayOfWeek dayOfWeek, Integer dayOfMonth, List<DcaCondition> conditions) {
        this(direction, amount, orderType, frequency, dayOfWeek, dayOfMonth, conditions, null);
    }

    // 조건 없는 주식 설정 — 기존 호출부(테스트·단계 ① 경로) 호환
    public DcaConfig(OrderDirection direction, BigDecimal amount, OrderType orderType, DcaFrequency frequency,
                     DayOfWeek dayOfWeek, Integer dayOfMonth) {
        this(direction, amount, orderType, frequency, dayOfWeek, dayOfMonth, List.of(), null);
    }

    // 계좌 시장별 규칙 — 등록·수정 시 계좌 증권사 시장으로 호출한다(설정 자체는 시장을 모른다)
    public void requireCompatible(Market accountMarket) {
        if (accountMarket == Market.CRYPTO_KRW) {
            if (orderType != OrderType.MARKET) throw new IllegalArgumentException("코인 DCA 주문유형은 MARKET만 허용합니다");
            if (amount.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("코인 DCA 금액은 원 단위 정수여야 합니다");
            if (amount.compareTo(CRYPTO_MIN_AMOUNT) < 0) throw new IllegalArgumentException("코인 DCA 금액은 5,000원 이상이어야 합니다");
        } else if (orderType == OrderType.MARKET) {
            throw new IllegalArgumentException("주식 DCA 주문유형은 MOO 또는 MOC만 허용합니다");
        }
    }
```

`DcaCycleOrderStrategy.java` 77·80행 `c.amountUsd()` → `c.amount()`.

- [ ] **Step 5: 영속·DTO 구현**

`StrategyDcaVersionEntity.java`:

```java
    @Column(name = "amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal amount; // 회차당 고정 금액 (계좌 통화 — 주식 USD, 코인 KRW)
```
`dayOfMonth` 필드 아래(마지막)에:
```java
    @Column(name = "execution_hour")
    private Short executionHour; // 코인 실행 시각 (KST 0~23) — smallint라 Short로 매핑(Integer면 validate 불일치)
```

`StrategyDcaDetailPersistenceAdapter.java`: 33행 `entity.setAmount(config.amount());` 다음 줄에 `entity.setExecutionHour(config.executionHour() == null ? null : config.executionHour().shortValue());`. `toDomain`:

```java
        return new DcaConfig(e.getDirection(), e.getAmount(), e.getOrderType(),
                e.getFrequency(), e.getDayOfWeek(), e.getDayOfMonth(),
                conditions.stream().map(StrategyDcaConditionEntity::toDomain).toList(),
                e.getExecutionHour() == null ? null : e.getExecutionHour().intValue());
```

`DcaConfigRequest.java` 전체:

```java
package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;

// DCA 등록·수정 요청 본문 — amount/amountUsd는 한 배포 사이클 동안 이중 계약(UI 전환 후 amountUsd 제거는 별도 작업)
public record DcaConfigRequest(
        @NotNull OrderDirection direction,  // 매수/매도
        @Schema(description = "회차당 금액 (계좌 통화 — 주식 USD, 코인 KRW 원 단위 정수). amount·amountUsd 중 하나 필수")
        BigDecimal amount,                  // 회차당 금액 (계좌 통화)
        @Schema(description = "회차당 금액 (구 필드, amount와 같은 의미 — 둘 다 보내면 같은 값이어야 함)", deprecated = true)
        BigDecimal amountUsd,               // 구 필드 — 전환 기간 호환
        @NotNull OrderType orderType,       // 주식 MOO | MOC, 코인 MARKET
        @NotNull DcaFrequency frequency,    // 주기
        DayOfWeek dayOfWeek,                // WEEKLY 전용
        Integer dayOfMonth,                 // MONTHLY 전용, null=말일
        List<DcaConditionRequest> conditions, // 추가 조건(AND) — null이면 빈 목록
        @Schema(description = "코인 실행 시각 (KST 0~23, 코인 필수). 주식 주문유형이면 무시")
        Integer executionHour               // 코인 전용 실행 시각
) {
    public DcaConfig toDomain() {
        if (conditions != null && conditions.contains(null)) {
            throw new IllegalArgumentException("조건 항목이 비어 있습니다.");
        }
        List<DcaCondition> domainConditions = conditions == null ? List.of()
                : conditions.stream().map(DcaConditionRequest::toDomain).toList();
        // 주식 주문유형이면 실행 시각을 저장하지 않는다(무시)
        Integer hour = orderType == OrderType.MARKET ? executionHour : null;
        return new DcaConfig(direction, resolveAmount(), orderType, frequency, dayOfWeek, dayOfMonth, domainConditions, hour);
    }

    // 두 필드 중 하나 — 둘 다 오면 값(compareTo)이 같아야 한다(300과 300.00은 같은 값)
    private BigDecimal resolveAmount() {
        if (amount == null && amountUsd == null) throw new IllegalArgumentException("amount는 필수입니다.");
        if (amount != null && amountUsd != null && amount.compareTo(amountUsd) != 0) {
            throw new IllegalArgumentException("amount와 amountUsd 값이 다릅니다.");
        }
        return amount != null ? amount : amountUsd;
    }
}
```

`DcaResponse.java` 레코드 헤더와 `from`:

```java
public record DcaResponse(
        OrderDirection direction,
        BigDecimal amount,                // 회차당 금액 (계좌 통화)
        BigDecimal amountUsd,             // 구 필드 — amount와 같은 값 (전환 기간 호환)
        OrderType orderType, DcaFrequency frequency,
        DayOfWeek dayOfWeek, Integer dayOfMonth,
        Integer executionHour,            // 코인 실행 시각 (KST, 주식 null)
        List<DcaConditionResponse> conditions, // 추가 조건(AND), 입력 순서
        NextExecution nextExecution,      // 보정 반영 판정 예정일 (null 가능)
        LastExecution lastExecution       // 최근 회차 (null 가능)
) {
```
```java
        return new DcaResponse(c.direction(), c.amount(), c.amount(), c.orderType(), c.frequency(), c.dayOfWeek(), c.dayOfMonth(),
                c.executionHour(), c.conditions().stream().map(DcaConditionResponse::from).toList(), next, last);
```

- [ ] **Step 6: 서비스·배치 구현**

`StrategyCreationService.java` 67행 `StrategyTicker resolvedTicker = resolved.ticker();` 바로 아래에 `validateMarket(account, cmd, resolvedTicker);`를 넣고 메서드 추가:

```java
    // 계좌 시장(증권사)과 종목 시장 일치 + 코인 계좌는 DCA 전용 + DCA 설정의 시장별 규칙(주문유형·금액·요일)
    private void validateMarket(Account account, RegisterStrategyCommand cmd, StrategyTicker ticker) {
        Market market = account.broker().getMarket();
        if (market == Market.CRYPTO_KRW && cmd.type() != StrategyType.DCA) {
            throw new IllegalArgumentException("코인 계좌는 DCA 전략만 등록할 수 있습니다.");
        }
        if (ticker.getMarket() != market) {
            throw new IllegalArgumentException("계좌와 종목의 시장이 다릅니다: " + ticker);
        }
        if (cmd.dca() != null) cmd.dca().requireCompatible(market);
    }
```

(`com.kista.shared.kernel.*` 와일드카드가 이미 있어 `Market` import 불필요.)

`DcaConfigService.java` 32행:

```java
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (!strategy.isDca()) throw new IllegalArgumentException("DCA 전략이 아닙니다: " + strategyId);
        config.requireCompatible(account.broker().getMarket()); // 수정도 등록과 같은 시장 규칙 — executionHour 수정 가능
```

(`import com.kista.tradingcore.account.domain.model.Account;`)

`BatchContextFactory.java` 65행(`계좌를 찾을 수 없습니다` throw 블록) 바로 아래:

```java
                // 코인 계좌 전략은 주식 개장·마감 배치 대상이 아니다 — 코인 DCA는 전용 스케줄러(묶음 C)가 실행
                if (account.broker().getMarket() != Market.US_STOCK) continue;
```

(`import com.kista.shared.kernel.Market;`)

`TradingFixtures.java` `tossAccount` 아래에:

```java
    // 기본 업비트 계좌 — 계좌번호 없음(access key가 중복 판정 키)
    public static Account upbitAccount(UUID id, UUID userId) {
        return new Account(id, userId, "업비트", null, "access-key", "secret-key", null, Broker.UPBIT, null);
    }
```

- [ ] **Step 7: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.domain.*' --tests 'com.kista.tradingcore.trading.adapter.in.web.*' --tests 'com.kista.tradingcore.trading.application.service.StrategyServiceTest' --tests 'com.kista.tradingcore.trading.application.service.DcaConfigServiceTest' --tests 'com.kista.tradingcore.trading.adapter.in.schedule.*' --tests 'com.kista.tradingcore.trading.adapter.out.persistence.DcaPersistenceAdapterDbTest' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL` (컨텍스트 로드가 V9·`Short` 매핑 validate를 확인한다).

- [ ] **Step 8: 문서 갱신**

`docs/agents/modules/tradingcore/trading.md` "DCA 회차 실행" 절에 두 줄 추가:
`- DCA 설정 필드: amount(계좌 통화 — 주식 USD·코인 KRW, 요청은 amount|amountUsd 이중 계약, 응답은 둘 다), executionHour(코인 MARKET 전용 KST 0~23, 주식은 무시·NULL). 시장 규칙은 DcaConfig.requireCompatible(account.broker().getMarket())가 SSOT — 코인: MARKET만·원 단위 정수·5,000원 이상·주말 허용 / 주식: MOO|MOC만·평일만. 계좌 시장 ≠ 종목 시장이면 거부, 코인 계좌는 DCA만.`
`- 주식 개장·마감 배치(BatchContextFactory)는 US_STOCK 계좌 전략만 담는다 — 코인 DCA 전략은 조용히 제외(묶음 C 전용 스케줄러가 실행).`
`src/test/resources/architecture/flows.yml` `strategy-register` 흐름에서 `StrategyCreationService#resolveCreationSettings`가 있는 step의 `desc` 끝에 `계좌 증권사 시장(US_STOCK/CRYPTO_KRW)과 종목 시장이 다르면, 코인 계좌에 DCA 외 전략이면, DCA 주문유형·금액이 시장 규칙(코인 MARKET·5,000원 이상 정수, 주식 MOO|MOC)에 어긋나면 400.`을 덧붙이고 같은 step `code:`에 `DcaConfig#requireCompatible`를 추가한다. `close-batch`/`open-batch` 흐름에서 `BatchContextFactory`를 참조하는 step이 있으면 desc에 `코인 계좌 전략은 제외한다.`를 덧붙인다.

Run: `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"` → `BUILD SUCCESSFUL`

- [ ] **Step 9: 커밋**

```bash
git add trading-core/src docs/agents/modules/tradingcore/trading.md src/test/resources/architecture/flows.yml
git commit -m "$(cat <<'EOF'
feat(trading): DCA 금액을 계좌 통화로 일반화하고 코인 실행 시각과 시장 규칙을 검증한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 5: A 계약 검증 — 전체 스위트 + openapi 병합 확인 + 계약 커밋 확정

**Files:**
- 코드 변경 없음이 원칙. 검증에서 결함이 나오면 해당 파일만 고쳐 이 태스크에서 커밋한다.
- Local only(커밋 금지): `src/main/resources/application-local.yml`, `trading-core/src/main/resources/application-local.yml`(.gitignored — main 체크아웃에서 복사)

**Interfaces:**
- Consumes: Task 1~4 전체
- Produces: **A 계약 커밋 해시** (kista-ui-22 전달용)

- [ ] **Step 1: 전체 스위트 1회**

Run: `docker compose up -d postgres redis && ./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL` (`InternalApiContractTest`, `ArchitectureMapTest`, 두 `ModulithArchitectureTest`, `HexagonalArchitectureTest`, `GradleModuleBoundaryTest` 포함).

- [ ] **Step 2: 두 프로세스 로컬 기동**

```bash
cp /Users/phs/workspace/kista/kista-api/src/main/resources/application-local.yml src/main/resources/application-local.yml
cp /Users/phs/workspace/kista/kista-api/trading-core/src/main/resources/application-local.yml trading-core/src/main/resources/application-local.yml
git status --short | grep application-local && echo "STOP: gitignore 확인" || true
./gradlew bootJar :trading-core:bootJar -q 2>&1 | grep -E "error:|FAILED"
```

이후 `docs/agents/commands.md` "로컬 2-프로세스 부팅" 절의 환경변수 그대로 root(8080)·trading-core(8081)를 백그라운드로 띄운다(`SPRING_PROFILES_ACTIVE=local`, 양쪽 동일 `INTERNAL_API_TOKEN`, root는 `INTERNAL_API_BASE_URL=http://localhost:8081`, trading-core는 `SERVER_PORT=8081`). `SCHEDULER_ENABLED`는 켜지 않는다.
Expected: `curl -s -o /dev/null -w '%{http_code}' localhost:8080/api-docs` = `200`, `localhost:8081/api-docs` = `200`.

- [ ] **Step 3: kista-ui 병합 스크립트로 계약 검증**

```bash
SPEC=/private/tmp/claude-501/-Users-phs-workspace-kista-kista-api/97f81ced-78a1-4a7e-9634-9c9364288a41/scratchpad/spec-a
mkdir -p $SPEC && cd $SPEC && node /Users/phs/workspace/kista/kista-ui/scripts/fetch-spec.mjs && echo MERGE_OK
jq -r '.components.schemas.BrokerMeta.properties | keys | join(",")' openapi.json
jq -r '.components.schemas.TickerMeta.properties.market.type' openapi.json
jq -r '.components.schemas.DcaConfigRequest.properties | keys | join(",")' openapi.json
jq -r '.components.schemas.DcaResponse.properties | keys | join(",")' openapi.json
jq -r '[.components.schemas | to_entries[] | select(.value.properties.quantity != null) | "\(.key)=\(.value.properties.quantity.type)"] | join(" ")' openapi.json
jq -r '.components.schemas.BrokerResponse.properties | keys | join(",")' openapi.json
```

Expected: `MERGE_OK`(스키마 이름 충돌·경로 중복 없음), BrokerMeta에 `code,currency,description,label,market`, TickerMeta market `string`, DcaConfigRequest에 `amount,amountUsd,...,executionHour`, DcaResponse에 `amount,amountUsd,executionHour`, 주문 계열 quantity가 `number`(PlannedOrder 기반 `OrderItem`만 `integer`), BrokerResponse에 `allowedIp,enabled`.
Run: `curl -s -H "Authorization: Bearer $(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)" localhost:8080/api/meta | jq -c '.brokers[] | select(.code=="UPBIT")'`
Expected: `{"code":"UPBIT","label":"업비트","description":"업비트","market":"CRYPTO_KRW","currency":"KRW"}`.
Run: `curl -s localhost:8080/api/runtime-config | jq -c '.brokers.UPBIT'`
Expected: `{"enabled":false,"allowedIp":"168.107.63.152"}`.

`MERGE_OK`가 실패하면 충돌 스키마 이름을 고치고 Step 1(해당 클래스)·Step 3을 다시 돈다.

- [ ] **Step 4: 정리**

두 java 프로세스 종료(`jps -l`로 PID 확인 후 `kill`), `rm src/main/resources/application-local.yml trading-core/src/main/resources/application-local.yml`은 하지 않는다(gitignored라 무해 — 다음 Task 12에서 재사용). `git status --short`가 비어 있는지 확인.

- [ ] **Step 5: 계약 커밋 확정**

Step 3에서 수정이 있었다면:

```bash
git add <수정 파일>
git commit -m "$(cat <<'EOF'
fix(contract): 업비트·코인 DCA 계약 스키마 충돌을 정리한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

수정이 없었다면 Task 4 커밋이 계약 커밋이다.

스펙 섹션 1(A)의 "계좌 응답 `currency`·`apiKeyExpiresAt`"은 `accounts.api_key_expires_at`(V10)·업비트 검증 결과에 묶여 있어 이 계획은 Task 9(B)에서 넣는다 — A 해시의 openapi에는 없고 B 해시에 포함된다. 전달 메시지에 이 한 줄을 함께 적는다.
Run: `git log -1 --format='%H %s'`
**A 완료 = 이 커밋 해시를 kista-ui-22에 전달**(전달 메시지: "kista-api `feat/crypto-upbit-dca` <hash> — 로컬 8080/8081 기동 후 `npm run fetch:spec && npm run gen:types`로 diff 확인. quantity number화, DCA amount/executionHour, meta brokers/tickers market·currency, runtime-config allowedIp, UPBIT 기본 비활성").

---

## 묶음 B — 업비트 어댑터·계좌

### Task 6: 업비트 에러 어휘 — ErrorCode 2종·예외 2종·브로커 중립 레이트리밋 제목

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/platform/web/ErrorCode.java:7-8`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/BrokerCredentialException.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/BrokerIpNotAllowedException.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/BrokerPermissionDeniedException.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/web/TradingExceptionHandler.java:58-69`
- Test: `trading-core/src/test/java/com/kista/tradingcore/web/TradingExceptionHandlerTest.java` (있으면 추가, 없으면 create)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ErrorCode.BROKER_IP_NOT_ALLOWED`, `ErrorCode.BROKER_PERMISSION_DENIED`
  - `class BrokerIpNotAllowedException extends BrokerCredentialException` (422, code BROKER_IP_NOT_ALLOWED)
  - `class BrokerPermissionDeniedException extends BrokerCredentialException` (422, code BROKER_PERMISSION_DENIED)
  - `protected BrokerCredentialException(String message)`
  - 429 title `"Broker Rate Limit"`

- [ ] **Step 1: 실패 테스트 작성**

`ls trading-core/src/test/java/com/kista/tradingcore/web/`로 기존 핸들러 테스트를 확인한다. 없으면 아래 파일을 만든다(있으면 테스트 메서드만 옮긴다):

```java
package com.kista.tradingcore.web;

import com.kista.shared.platform.web.ErrorCode;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TradingExceptionHandlerTest {

    private final TradingExceptionHandler handler = new TradingExceptionHandler(mock(ApplicationEventPublisher.class));

    @Test
    void upbitSpecificCredentialErrorsCarryOwnCodes() {
        ProblemDetail ip = handler.handleTradingCoreExceptions(new BrokerIpNotAllowedException());
        assertThat(ip.getStatus()).isEqualTo(422);
        assertThat(ip.getProperties()).containsEntry("code", ErrorCode.BROKER_IP_NOT_ALLOWED);
        assertThat(ip.getDetail()).isEqualTo("거래소 API 키에 서버 IP가 허용되어 있지 않습니다.");

        ProblemDetail scope = handler.handleTradingCoreExceptions(new BrokerPermissionDeniedException());
        assertThat(scope.getStatus()).isEqualTo(422);
        assertThat(scope.getProperties()).containsEntry("code", ErrorCode.BROKER_PERMISSION_DENIED);

        ProblemDetail invalid = handler.handleTradingCoreExceptions(new BrokerCredentialException());
        assertThat(invalid.getProperties()).containsEntry("code", ErrorCode.BROKER_CREDENTIAL_INVALID);
    }

    @Test
    void rateLimitTitleIsBrokerNeutral() {
        ProblemDetail p = handler.handleTradingCoreExceptions(new BrokerRateLimitException());
        assertThat(p.getStatus()).isEqualTo(429);
        assertThat(p.getTitle()).isEqualTo("Broker Rate Limit");
    }
}
```

(`code` 프로퍼티 값이 enum이 아니라 문자열로 실리면 `ErrorCode.X.name()`으로 비교한다 — `ProblemDetailMappings.toProblem` 구현을 보고 맞춘다.)

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.web.TradingExceptionHandlerTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패.

- [ ] **Step 2: 구현**

`ErrorCode.java`:

```java
    BROKER_CREDENTIAL_INVALID,   // 422 증권사 API 키 오류
    BROKER_IP_NOT_ALLOWED,       // 422 거래소 API 키에 서버 IP 미허용 (업비트 no_authorization_ip)
    BROKER_PERMISSION_DENIED,    // 422 거래소 API 키 권한 부족 (업비트 out_of_scope)
```

`BrokerCredentialException.java`:

```java
public class BrokerCredentialException extends RuntimeException {
    public BrokerCredentialException() {
        super("증권사 API 키가 유효하지 않습니다.");
    }

    // 세부 사유 서브클래스용 — detail은 사용자 노출 한국어 문구
    protected BrokerCredentialException(String message) {
        super(message);
    }
}
```

`BrokerIpNotAllowedException.java`:

```java
package com.kista.tradingcore.broker.domain.model;

// 거래소 API 키의 허용 IP에 서버 IP가 없을 때(업비트 no_authorization_ip) — 자격증명 실패의 세부 사유라 상위 타입을 잇는다(확정 거절·422)
public class BrokerIpNotAllowedException extends BrokerCredentialException {
    public BrokerIpNotAllowedException() {
        super("거래소 API 키에 서버 IP가 허용되어 있지 않습니다.");
    }
}
```

`BrokerPermissionDeniedException.java`:

```java
package com.kista.tradingcore.broker.domain.model;

// 거래소 API 키에 필요한 권한(자산조회·주문하기)이 없을 때(업비트 out_of_scope) — 자격증명 실패의 세부 사유(확정 거절·422)
public class BrokerPermissionDeniedException extends BrokerCredentialException {
    public BrokerPermissionDeniedException() {
        super("거래소 API 키에 필요한 권한이 없습니다.");
    }
}
```

`TradingExceptionHandler.java` `MAPPINGS`(엔트리가 10개를 넘으므로 `Map.of` → `Map.ofEntries`):

```java
    private static final Map<Class<? extends Exception>, Mapping> MAPPINGS = Map.ofEntries(
            Map.entry(BrokerIpNotAllowedException.class,      new Mapping(HttpStatus.UNPROCESSABLE_ENTITY, "Broker IP Not Allowed", ErrorCode.BROKER_IP_NOT_ALLOWED)),
            Map.entry(BrokerPermissionDeniedException.class,  new Mapping(HttpStatus.UNPROCESSABLE_ENTITY, "Broker Permission Denied", ErrorCode.BROKER_PERMISSION_DENIED)),
            Map.entry(BrokerCredentialException.class,        new Mapping(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid Broker Credentials", ErrorCode.BROKER_CREDENTIAL_INVALID)),
            Map.entry(BrokerRateLimitException.class,          new Mapping(HttpStatus.TOO_MANY_REQUESTS,     "Broker Rate Limit", ErrorCode.BROKER_RATE_LIMITED)),
            Map.entry(AlreadyOrderedTodayException.class,      new Mapping(HttpStatus.CONFLICT,              "Conflict", ErrorCode.ALREADY_ORDERED_TODAY)),
            Map.entry(DcaManualExecutionUnsupportedException.class, new Mapping(HttpStatus.CONFLICT,         "Conflict", ErrorCode.DCA_MANUAL_EXECUTION_UNSUPPORTED)),
            Map.entry(ManualTradingFailedException.class,      new Mapping(HttpStatus.INTERNAL_SERVER_ERROR, "Manual Trading Failed", null,
                                                             "주문 계산 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")),
            Map.entry(ManualTradingException.class,            new Mapping(HttpStatus.CONFLICT,              "Conflict")),
            Map.entry(OrderCancelException.class,              new Mapping(HttpStatus.CONFLICT,              "Conflict", ErrorCode.ORDER_NOT_CANCELLABLE)),
            Map.entry(PrivacyTradeConflictException.class,     new Mapping(HttpStatus.CONFLICT,              "Conflict")),
            Map.entry(Account.DuplicateAccountException.class, new Mapping(HttpStatus.CONFLICT,              "Conflict", ErrorCode.DUPLICATE_ACCOUNT))
    );
```

(import 2개 추가. `@ExceptionHandler` 목록은 상위 `BrokerCredentialException`이 서브클래스를 잡으므로 그대로.)

- [ ] **Step 3: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.web.*' :test --tests 'com.kista.api.web.ErrorCodeOpenApiCustomizerTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: 커밋**

```bash
git add shared/src/main/java/com/kista/shared/platform/web/ErrorCode.java trading-core/src
git commit -m "$(cat <<'EOF'
feat(broker): 거래소 IP 미허용·권한 부족 오류 코드를 추가하고 레이트리밋 제목을 중립화한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 7: 업비트 HTTP 클라이언트 — 요청별 HS512 JWT·에러 매핑·설정·마켓 레지스트리

**업비트 공식 문서 확인 결과(2026-10-17 WebFetch):**
- 인증(`docs.upbit.com/kr/reference/auth`): `Authorization: Bearer <JWT>`. 헤더 `alg`은 실제 서명 알고리즘, **HS512 권장**(예제 일부는 HS256). Secret Key는 Base64 인코딩이 아니다(UTF-8 바이트 그대로). payload: `access_key`(필수), `nonce`(요청마다 새 UUID, 필수), `query_hash`(쿼리/본문이 있으면 필수), `query_hash_alg`(기본 `SHA512`). GET/DELETE는 실제 요청 쿼리 문자열을 **URL 인코딩하지 않은 상태**로, 순서를 바꾸지 않고 해시. POST는 JSON 본문의 key=value를 `&`로 이은 문자열을 해시.
- 에러(`/kr/reference/rest-api-guide`): 바디 `{"error":{"name","message"}}`. `jwt_verification`(401), `expired_access_key`(401), `nonce_used`(401), `no_authorization_ip`(401), `invalid_query_payload`(401), `no_authorization_token`(401), `out_of_scope`(403, 영역에 따라 401). 429 한도 초과, 418 반복 초과 차단. 문서에 `invalid_access_key`는 없다(스펙 표에는 있음 — 그대로 매핑에 포함).
- 레이트리밋(`/kr/reference/rate-limits`): 응답 헤더 `Remaining-Req: group=default; min=1800; sec=29`(`min` deprecated). Exchange `default` 30/s, `order` 12/s, Quotation(캔들·티커) IP당 10/s.
- 시세(티커·캔들)는 문서에 인증 요구가 없다 → JWT 없이 호출(IP 제한도 무관).
- 미확인(구현자가 실키 없이 확인 불가): 실제 Secret Key 길이, `Remaining-Req` 파싱 필요성(1차 미사용).

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/upbit/UpbitApiException.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitConfig.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitHttpClient.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitMarketRegistry.java`
- Modify: `trading-core/src/main/resources/application.yml:91-95` (upbit 블록 추가)
- Modify: `src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java:158`, `trading-core/src/test/java/com/kista/tradingcore/architecture/TradingCoreTestVendorModelTest.java:34` (벤더 패키지에 `upbit..` 추가)
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitHttpClientTest.java`, `.../upbit/UpbitMarketRegistryTest.java`

**Interfaces:**
- Consumes: Task 6 `BrokerIpNotAllowedException`, `BrokerPermissionDeniedException`; 기존 `BrokerCredentialException`, `BrokerRateLimitException`, `BrokerApiException`, `BrokerAccountRef`
- Produces (모두 package-private, `upbit` 패키지 내부 전용):
  - `UpbitHttpClient.getPublic(String path, LinkedHashMap<String,String> query, ParameterizedTypeReference<T> type): T`
  - `UpbitHttpClient.get(String path, LinkedHashMap<String,String> query, BrokerAccountRef auth, ParameterizedTypeReference<T> type): T`
  - `UpbitHttpClient.post(String path, LinkedHashMap<String,String> body, BrokerAccountRef auth, ParameterizedTypeReference<T> type): T`
  - `String UpbitHttpClient.token(String accessKey, String secretKey, String queryString)`
  - `UpbitMarketRegistry.marketOf(StrategyTicker): String`, `UpbitMarketRegistry.tickerOfCurrency(String): Optional<StrategyTicker>`, `UpbitMarketRegistry.tickerOfMarket(String): Optional<StrategyTicker>`
  - `public class UpbitApiException extends BrokerApiException { int status(); String errorName(); }` (`broker.domain.model.upbit` — broker 밖 비공개)

- [ ] **Step 1: 실패 테스트 작성**

`UpbitHttpClientTest.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.*;
import com.kista.tradingcore.broker.domain.model.upbit.UpbitApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpbitHttpClientTest {

    private static final String BASE_URL = "https://api.upbit.com";
    private static final String ACCESS = "test-access";
    private static final String SECRET = "test-secret-key-40-chars-xxxxxxxxxxxxxxx";
    private static final String NONCE = "00000000-0000-0000-0000-000000000001";
    private static final BrokerAccountRef AUTH = new BrokerAccountRef(null, ACCESS, SECRET, null, null, Broker.UPBIT);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private UpbitHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UpbitHttpClient(builder.build(), mapper, () -> NONCE);
    }

    @Test
    void tokenMatchesIndependentHs512Vector() {
        // 기대값은 Python hmac/hashlib/base64로 독립 계산(헤더 {"alg":"HS512","typ":"JWT"}, compact JSON, 키 순서 access_key·nonce·query_hash·query_hash_alg)
        assertThat(client.token(ACCESS, SECRET, "market=KRW-BTC")).isEqualTo(
                "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJhY2Nlc3Nfa2V5IjoidGVzdC1hY2Nlc3MiLCJub25jZSI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSIsInF1ZXJ5X2hhc2giOiJiNzQ5ZGZjMmUxN2Y3NWU1YjQ2YzgxNjFmOTdmZTdjOTI5OGVkNDE2N2VhMjFjNWM5NGQxNjU3M2VmZDhhODAxMzUxNDcwYzBmZjFhOWEzZjFlNzYzZjgyNDk5NjgyMThjMDRjNTcxYzhiNDVhYTgwY2Q0NTg4ZTZjNGJlMDczOCIsInF1ZXJ5X2hhc2hfYWxnIjoiU0hBNTEyIn0.Rr2j6LBD2LHYGUskoMaVtAVU0aFGaGpjvfBJXhBxMHcrNYEznisHbvb_zqusnzMoP5PUKeSNDk8TV40CVb7WRA");
        assertThat(client.token(ACCESS, SECRET, "")).isEqualTo(
                "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9.eyJhY2Nlc3Nfa2V5IjoidGVzdC1hY2Nlc3MiLCJub25jZSI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMSJ9.fPITQYWYA6Rr0lypI8HGdimnqNIAUKk6DwPtUqzqFQUSVy-pqDBBbf6xN9qCj4AEiFqzCFFv8LqRvBHhK3nopg");
    }

    @Test
    void tokenSignatureVerifiesAndHashesUnencodedQuery() throws Exception {
        String token = client.token(ACCESS, SECRET, "market=KRW-BTC&side=bid&ord_type=price&price=5000&identifier=0190a5c4-0000-7000-8000-000000000001");
        String[] parts = token.split("\\.");
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("access_key").asText()).isEqualTo(ACCESS);
        assertThat(payload.get("nonce").asText()).isEqualTo(NONCE);
        assertThat(payload.get("query_hash_alg").asText()).isEqualTo("SHA512");
        // Python hashlib.sha512 독립 계산값
        assertThat(payload.get("query_hash").asText()).isEqualTo(
                "edff38f20d7c635e5ea5c0d55236e934b3286c530667e2e1dca234100f043294fe990714b757c6f54f290a7968a4188e9c6a32d212960b38cbbffef69a172319");
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        String expectedSig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        assertThat(parts[2]).isEqualTo(expectedSig);
    }

    @Test
    void postSendsJsonBodyAndBearerTokenHashedFromSameOrderedPairs() {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("market", "KRW-BTC");
        body.put("side", "bid");
        body.put("ord_type", "price");
        body.put("price", "5000");
        body.put("identifier", "0190a5c4-0000-7000-8000-000000000001");
        String expectedToken = client.token(ACCESS, SECRET,
                "market=KRW-BTC&side=bid&ord_type=price&price=5000&identifier=0190a5c4-0000-7000-8000-000000000001");
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + expectedToken))
                .andExpect(content().json("""
                        {"market":"KRW-BTC","side":"bid","ord_type":"price","price":"5000","identifier":"0190a5c4-0000-7000-8000-000000000001"}
                        """))
                .andRespond(withSuccess("{\"uuid\":\"u-1\"}", MediaType.APPLICATION_JSON));

        Map<String, Object> res = client.post("/v1/orders", body, AUTH, new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(res).containsEntry("uuid", "u-1");
        server.verify();
    }

    @Test
    void publicGetHasNoAuthorizationHeader() {
        LinkedHashMap<String, String> q = new LinkedHashMap<>();
        q.put("markets", "KRW-BTC,KRW-ETH");
        server.expect(requestTo(BASE_URL + "/v1/ticker?markets=KRW-BTC,KRW-ETH"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> res = client.getPublic("/v1/ticker", q, new ParameterizedTypeReference<>() {});

        assertThat(res).isEmpty();
        server.verify();
    }

    @Test
    void errorNamesMapToBrokerExceptions() {
        assertMapped(HttpStatus.UNAUTHORIZED, "no_authorization_ip", BrokerIpNotAllowedException.class);
        assertMapped(HttpStatus.FORBIDDEN, "out_of_scope", BrokerPermissionDeniedException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "out_of_scope", BrokerPermissionDeniedException.class); // 영역에 따라 401
        assertMapped(HttpStatus.UNAUTHORIZED, "invalid_access_key", BrokerCredentialException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "jwt_verification", BrokerCredentialException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "expired_access_key", BrokerCredentialException.class);
        assertMapped(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", BrokerRateLimitException.class);
        assertMapped(HttpStatus.I_AM_A_TEAPOT, "blocked", BrokerRateLimitException.class);
        assertMapped(HttpStatus.UNAUTHORIZED, "nonce_used", UpbitApiException.class); // 우리 쪽 결함 — 일반 실패
    }

    @Test
    void unknownErrorKeepsNameButNotRawMessage() {
        server.expect(requestTo(BASE_URL + "/v1/accounts"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"name\":\"invalid_parameter\",\"message\":\"내부 원문\"}}"));

        assertThatThrownBy(() -> client.get("/v1/accounts", new LinkedHashMap<>(), AUTH, new ParameterizedTypeReference<List<Object>>() {}))
                .isInstanceOfSatisfying(UpbitApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.errorName()).isEqualTo("invalid_parameter");
                    assertThat(e.getMessage()).doesNotContain("내부 원문");
                    assertThat(e.isRejected()).isTrue();
                    assertThat(e.vendorLabel()).isEqualTo("Upbit");
                });
    }

    private void assertMapped(HttpStatus status, String name, Class<? extends RuntimeException> expected) {
        server.reset();
        server.expect(requestTo(BASE_URL + "/v1/accounts"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"name\":\"" + name + "\",\"message\":\"m\"}}"));
        assertThatThrownBy(() -> client.get("/v1/accounts", new LinkedHashMap<>(), AUTH, new ParameterizedTypeReference<List<Object>>() {}))
                .isExactlyInstanceOf(expected);
    }
}
```

`UpbitMarketRegistryTest.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpbitMarketRegistryTest {

    private final UpbitMarketRegistry registry = new UpbitMarketRegistry();

    @Test
    void everyCryptoTickerHasKrwMarket() {
        Arrays.stream(StrategyTicker.values()).filter(t -> t.getMarket() == Market.CRYPTO_KRW)
                .forEach(t -> assertThat(registry.marketOf(t)).isEqualTo("KRW-" + t.name()));
    }

    @Test
    void reverseLookups() {
        assertThat(registry.tickerOfCurrency("BTC")).contains(StrategyTicker.BTC);
        assertThat(registry.tickerOfCurrency("XRP")).isEmpty();
        assertThat(registry.tickerOfMarket("KRW-ETH")).contains(StrategyTicker.ETH);
        assertThatThrownBy(() -> registry.marketOf(StrategyTicker.SOXL)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.upbit.*' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패.

- [ ] **Step 2: 구현 — 예외·설정·레지스트리**

`UpbitApiException.java`:

```java
package com.kista.tradingcore.broker.domain.model.upbit;

import com.kista.tradingcore.broker.domain.model.BrokerApiException;

// 업비트 API 실패(자격증명·한도 외) — 503(BROKER_UNAVAILABLE)으로 매핑된다. 업비트 원문 message는 싣지 않는다(로그 전용)
public class UpbitApiException extends BrokerApiException {

    private final int status;       // HTTP 상태 (I/O 실패면 0)
    private final String errorName; // 업비트 error.name (없으면 null) — 어댑터가 order_not_found 등 분기에 사용

    public UpbitApiException(int status, String errorName, String message, Throwable cause, boolean rejected) {
        super("Upbit", message, cause, Conflict.NONE, rejected);
        this.status = status;
        this.errorName = errorName;
    }

    public int status() {
        return status;
    }

    public String errorName() {
        return errorName;
    }
}
```

`UpbitConfig.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.platform.http.HttpClientTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class UpbitConfig {

    // baseUrl 고정 — 호출부는 상대 경로만. 타임아웃은 Toss와 같은 JDK HttpClient 공용 헬퍼
    @Bean
    public RestClient upbitRestClient(@Value("${upbit.base-url:https://api.upbit.com}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 10_000))
                .build();
    }
}
```

`application.yml` `toss:` 블록 아래:

```yaml
upbit: # 사용자별 키라 서버 공용 시크릿 없음 — 요청마다 계좌 자격증명으로 JWT 서명
  base-url: https://api.upbit.com
```

`UpbitMarketRegistry.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.StrategyTicker;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

// StrategyTicker ↔ 업비트 마켓 코드(KRW-BTC) — 어댑터 디테일(KisExchangeRegistry와 같은 방식). 새 코인 추가 시 양쪽 갱신
@Component
class UpbitMarketRegistry {

    private static final Map<StrategyTicker, String> MARKETS = Map.of(
            StrategyTicker.BTC, "KRW-BTC",
            StrategyTicker.ETH, "KRW-ETH"
    );

    String marketOf(StrategyTicker ticker) {
        String market = MARKETS.get(ticker);
        if (market == null) throw new IllegalArgumentException("업비트 마켓 매핑 누락: " + ticker);
        return market;
    }

    // /v1/accounts currency(BTC) → 지원 종목, 미지원 코인은 empty(잔고에서 제외)
    Optional<StrategyTicker> tickerOfCurrency(String currency) {
        return MARKETS.keySet().stream().filter(t -> t.name().equals(currency)).findFirst();
    }

    // /v1/ticker market(KRW-BTC) → 지원 종목
    Optional<StrategyTicker> tickerOfMarket(String market) {
        return MARKETS.entrySet().stream().filter(e -> e.getValue().equals(market)).map(Map.Entry::getKey).findFirst();
    }
}
```

- [ ] **Step 3: 구현 — HTTP 클라이언트**

`UpbitHttpClient.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.tradingcore.broker.domain.model.*;
import com.kista.tradingcore.broker.domain.model.upbit.UpbitApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// 업비트 REST 공통 — 요청마다 HS512 JWT 서명(토큰 캐시 없음), 오류 바디 error.name → 벤더 중립 예외 매핑
@Slf4j
@Component
class UpbitHttpClient {

    private static final String JWT_HEADER = "{\"alg\":\"HS512\",\"typ\":\"JWT\"}"; // 업비트 권장 HS512
    private static final Set<String> CREDENTIAL_ERRORS = Set.of("invalid_access_key", "jwt_verification", "expired_access_key"); // 422 BROKER_CREDENTIAL_INVALID

    private final RestClient upbitRestClient;     // baseUrl 고정 RestClient (UpbitConfig)
    private final ObjectMapper objectMapper;      // JWT payload 직렬화·오류 바디 파싱
    private final Supplier<String> nonceSupplier; // 요청마다 새 nonce — 테스트에서 고정값 주입

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패(Toss Redis Store 장애 교훈)
    UpbitHttpClient(RestClient upbitRestClient, ObjectMapper objectMapper) {
        this(upbitRestClient, objectMapper, () -> UUID.randomUUID().toString());
    }

    UpbitHttpClient(RestClient upbitRestClient, ObjectMapper objectMapper, Supplier<String> nonceSupplier) {
        this.upbitRestClient = upbitRestClient;
        this.objectMapper = objectMapper;
        this.nonceSupplier = nonceSupplier;
    }

    // 공개 시세(티커·캔들) — 인증 없음
    <T> T getPublic(String path, LinkedHashMap<String, String> query, ParameterizedTypeReference<T> type) {
        return execute("GET " + path, () -> upbitRestClient.get().uri(withQuery(path, query)).retrieve().body(type));
    }

    // 인증 GET — query_hash는 실제 요청과 같은 비인코딩 쿼리 문자열
    <T> T get(String path, LinkedHashMap<String, String> query, BrokerAccountRef auth, ParameterizedTypeReference<T> type) {
        String bearer = "Bearer " + token(auth.appKey(), auth.secretKey(), queryString(query));
        return execute("GET " + path, () -> upbitRestClient.get().uri(withQuery(path, query))
                .header("Authorization", bearer).retrieve().body(type));
    }

    // 인증 POST — JSON 본문과 query_hash를 같은 순서의 key=value 쌍에서 만든다
    <T> T post(String path, LinkedHashMap<String, String> body, BrokerAccountRef auth, ParameterizedTypeReference<T> type) {
        String bearer = "Bearer " + token(auth.appKey(), auth.secretKey(), queryString(body));
        return execute("POST " + path, () -> upbitRestClient.post().uri(path)
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(type));
    }

    // JWT(HS512) — queryString이 비면 query_hash 생략
    String token(String accessKey, String secretKey, String queryString) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("access_key", accessKey);
        payload.put("nonce", nonceSupplier.get());
        if (!queryString.isEmpty()) {
            payload.put("query_hash", sha512Hex(queryString));
            payload.put("query_hash_alg", "SHA512");
        }
        String signingInput = base64Url(JWT_HEADER.getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(hmacSha512(secretKey, signingInput));
    }

    private <T> T execute(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpStatusCodeException e) {
            throw translate(e, action);
        } catch (RestClientException e) {
            // I/O 실패 — 접수 여부 불명(rejected=false)
            log.warn("Upbit {} 통신 실패: {}", action, e.getMessage());
            throw new UpbitApiException(0, null, "Upbit " + action + " 통신 실패", e, false);
        }
    }

    // error.name → 벤더 중립 예외. 원문 바디는 로그에만
    private RuntimeException translate(HttpStatusCodeException e, String action) {
        int status = e.getStatusCode().value();
        String name = errorName(e.getResponseBodyAsString());
        log.warn("Upbit {} 실패: status={}, name={}, body={}", action, status, name, e.getResponseBodyAsString());
        if (status == 429 || status == 418) return new BrokerRateLimitException();
        if ("no_authorization_ip".equals(name)) return new BrokerIpNotAllowedException();
        if ("out_of_scope".equals(name)) return new BrokerPermissionDeniedException();
        if (name != null && CREDENTIAL_ERRORS.contains(name)) return new BrokerCredentialException();
        return new UpbitApiException(status, name, "Upbit " + action + " 실패: status=" + status + ", name=" + name,
                e, status >= 400 && status < 500);
    }

    private String errorName(String body) {
        try {
            JsonNode name = objectMapper.readTree(body).path("error").path("name");
            return name.isMissingNode() || name.isNull() ? null : name.asText();
        } catch (RuntimeException ex) {
            return null; // 바디가 JSON이 아님 — 상태 코드만으로 판정
        }
    }

    private static String withQuery(String path, LinkedHashMap<String, String> query) {
        String qs = queryString(query);
        return qs.isEmpty() ? path : path + "?" + qs;
    }

    // 비인코딩 key=value&... — 값은 마켓 코드·UUID·숫자 문자열이라 인코딩 대상 문자가 없다
    private static String queryString(LinkedHashMap<String, String> pairs) {
        return pairs.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }

    private static String sha512Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-512 미지원", e);
        }
    }

    // JDK Mac — Nimbus MACSigner는 HS512에 512bit 미만 키를 거부하므로 쓰지 않는다
    private static byte[] hmacSha512(String secretKey, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA512 서명 실패", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

(Jackson 3의 `writeValueAsString`은 unchecked `JacksonException`을 던진다 — Jackson 2를 쓰는 환경이 아니므로 try/catch 불필요.)

`HexagonalArchitectureTest.java:158`과 `TradingCoreTestVendorModelTest.java:34`의 `resideInAnyPackage(...)`에 `"com.kista.tradingcore.broker.domain.model.upbit.."`를 추가하고, 같은 테스트의 `@DisplayName`의 `kis/toss`를 `kis/toss/upbit`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.upbit.*' --tests 'com.kista.tradingcore.architecture.*' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' :test --tests 'com.kista.api.architecture.HexagonalArchitectureTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`. JWT 고정 벡터가 다르면 payload JSON에 공백이 들어갔거나 키 순서가 바뀐 것이다 — `objectMapper` 설정(INDENT 등)을 확인한다.

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java
git commit -m "$(cat <<'EOF'
feat(broker): 업비트 HTTP 클라이언트에 요청별 HS512 서명과 오류 매핑을 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 8: `CryptoExchangePort` + `CryptoExchanges` 라우터 + `UpbitExchangeAdapter`

**업비트 공식 문서 확인 결과(2026-10-17 WebFetch):**
- `GET /v1/accounts`(자산조회 권한): 배열 `{currency, balance, locked, avg_buy_price, avg_buy_price_modified, unit_currency}` — 숫자는 **문자열**(decimal). KRW 행의 `balance`가 주문 가능 원화.
- `GET /v1/ticker?markets=KRW-BTC,KRW-ETH`: 배열 `{market, trade_price, ...}`(trade_price = 현재가).
- `GET /v1/candles/days?market=&count=&to=`: count 최대 200(기본 1). `candle_date_time_kst`(시작 시각 `yyyy-MM-dd'T'HH:mm:ss`, 일봉은 09:00 KST 시작), `trade_price`(종가/현재가). **정렬 방향·진행 중 봉 포함 여부는 문서에 없음(미확인)** → 어댑터가 `candle_date_time_kst`로 오름차순 정렬하고, 시작+1일 > 현재(KST)인 봉(진행 중)을 버린다.
- `POST /v1/orders`(주문하기 권한, JSON 본문 필수): `market`, `side`(bid|ask), `ord_type`(`price`=시장가 매수: price=총액·volume 생략 / `market`=시장가 매도: volume 필수·price 생략), `identifier`(계정 내 영구 고유, 재사용 불가, 최대 64자). 201 응답 `{uuid, identifier, state(wait|watch|done|cancel), volume, executed_volume, paid_fee, ...}`. order 그룹 12/s.
- `GET /v1/order?uuid=|identifier=`: 둘 중 하나 필수(둘 다면 uuid 우선). 평균가 필드 없음 → `trades[]`의 `funds`·`volume` 합으로 계산(Σfunds/Σvolume — 문서가 공식을 명시하지 않음, 관례). 없으면 404 `order_not_found`.
- 최소 주문 금액 5,000원: 공식 튜토리얼 주문 가능 정보 예시의 `bid.min_total: "5000"`으로만 간접 확인(KRW 마켓 가이드 본문은 미확인).
- 미확인(실주문 필요): 시장가 매수(`price`) 완료 주문의 최종 `state`가 `done`인지 `cancel`(잔액 반환)인지 — C가 `executedVolume > 0`으로 체결 판정하도록 C 계획에 넘긴다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/CryptoBalance.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/CryptoCandle.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/CryptoOrderResult.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/application/port/output/CryptoExchangePort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/application/service/CryptoExchanges.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitExchangeAdapter.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitExchangeAdapterTest.java`, `trading-core/src/test/java/com/kista/tradingcore/broker/application/service/CryptoExchangesTest.java`
- Docs: `docs/agents/modules/tradingcore/broker.md`

**Interfaces:**
- Consumes: Task 7 `UpbitHttpClient`, `UpbitMarketRegistry`, `UpbitApiException.errorName()`
- Produces:
  - `record CryptoBalance(BigDecimal krwAvailable, BigDecimal krwLocked, Map<StrategyTicker, Holding> holdings)` + `record Holding(BigDecimal available, BigDecimal locked, BigDecimal avgBuyPrice) { BigDecimal total() }`
  - `record CryptoCandle(LocalDate kstDate, BigDecimal close)` — 완성 일봉(KST 09:00 시작일 기준)
  - `record CryptoOrderResult(String uuid, String identifier, State state, BigDecimal executedVolume, BigDecimal avgPrice, BigDecimal paidFee)` + `enum State { WAIT, WATCH, DONE, CANCEL }` (`avgPrice` 체결 없으면 null)
  - `interface CryptoExchangePort { Broker supports(); CryptoBalance getBalance(BrokerAccountRef); Map<StrategyTicker,BigDecimal> getCurrentPrices(Collection<StrategyTicker>); List<CryptoCandle> getCompletedDailyCandles(StrategyTicker, int count); CryptoOrderResult buyMarketByAmount(BrokerAccountRef, StrategyTicker, BigDecimal krwAmount, String identifier); CryptoOrderResult sellMarketByVolume(BrokerAccountRef, StrategyTicker, BigDecimal volume, String identifier); Optional<CryptoOrderResult> findOrderByUuid(BrokerAccountRef, String uuid); Optional<CryptoOrderResult> findOrderByIdentifier(BrokerAccountRef, String identifier); }`
  - `public class CryptoExchanges { public CryptoExchangePort of(Broker) }` — 미지원이면 `IllegalArgumentException`(400)

(스펙은 `supports(Broker)`로 적었지만 저장소의 라우팅 포트 관례(`BrokerConnectionTestPort.supports()`·`BrokerAdapterPort.supports()`)가 `Broker supports()`라 그쪽을 따른다.)

- [ ] **Step 1: 실패 테스트 작성**

`UpbitExchangeAdapterTest.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpbitExchangeAdapterTest {

    private static final String BASE_URL = "https://api.upbit.com";
    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(null, "ak", "sk-0123456789012345678901234567890123", null, null, Broker.UPBIT);
    // 2026-10-17 10:30 KST — 10-17 09:00에 시작한 봉은 진행 중
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T01:30:00Z"), ZoneId.of("Asia/Seoul"));

    private MockRestServiceServer server;
    private UpbitExchangeAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        UpbitHttpClient http = new UpbitHttpClient(builder.build(), JsonMapper.builder().build(), () -> "n");
        adapter = new UpbitExchangeAdapter(http, new UpbitMarketRegistry(), CLOCK);
    }

    @Test
    void supportsUpbit() {
        assertThat(adapter.supports()).isEqualTo(Broker.UPBIT);
    }

    @Test
    void balanceParsesStringDecimalsAndSkipsUnsupportedCoins() {
        server.expect(requestTo(BASE_URL + "/v1/accounts"))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
                .andRespond(withSuccess("""
                        [{"currency":"KRW","balance":"1000000.0","locked":"5000.0","avg_buy_price":"0","avg_buy_price_modified":false,"unit_currency":"KRW"},
                         {"currency":"BTC","balance":"0.00123456","locked":"0.0","avg_buy_price":"150000000","avg_buy_price_modified":false,"unit_currency":"KRW"},
                         {"currency":"XRP","balance":"10","locked":"0","avg_buy_price":"800","avg_buy_price_modified":false,"unit_currency":"KRW"}]
                        """, MediaType.APPLICATION_JSON));

        CryptoBalance balance = adapter.getBalance(ACCOUNT);

        assertThat(balance.krwAvailable()).isEqualByComparingTo("1000000");
        assertThat(balance.krwLocked()).isEqualByComparingTo("5000");
        assertThat(balance.holdings()).containsOnlyKeys(StrategyTicker.BTC);
        assertThat(balance.holdings().get(StrategyTicker.BTC).available()).isEqualByComparingTo("0.00123456");
        assertThat(balance.holdings().get(StrategyTicker.BTC).avgBuyPrice()).isEqualByComparingTo("150000000");
    }

    @Test
    void currentPricesUsePublicTicker() {
        server.expect(requestTo(BASE_URL + "/v1/ticker?markets=KRW-BTC,KRW-ETH"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                        [{"market":"KRW-BTC","trade_price":151000000.0},{"market":"KRW-ETH","trade_price":5200000.0}]
                        """, MediaType.APPLICATION_JSON));

        var prices = adapter.getCurrentPrices(List.of(StrategyTicker.BTC, StrategyTicker.ETH));

        assertThat(prices).containsOnlyKeys(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(prices.get(StrategyTicker.BTC)).isEqualByComparingTo("151000000"); // scale은 JSON 표기에 따름 — 값만 비교
    }

    @Test
    void completedCandlesDropInProgressAndSortAscending() {
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=3"))
                .andRespond(withSuccess("""
                        [{"candle_date_time_kst":"2026-10-17T09:00:00","trade_price":152000000},
                         {"candle_date_time_kst":"2026-10-16T09:00:00","trade_price":151000000},
                         {"candle_date_time_kst":"2026-10-15T09:00:00","trade_price":150000000}]
                        """, MediaType.APPLICATION_JSON));

        List<CryptoCandle> candles = adapter.getCompletedDailyCandles(StrategyTicker.BTC, 2);

        assertThat(candles).extracting(CryptoCandle::kstDate)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(candles.getLast().close()).isEqualByComparingTo("151000000");
    }

    @Test
    void marketBuyPostsPriceOrderWithIdentifier() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"market":"KRW-BTC","side":"bid","ord_type":"price","price":"10000","identifier":"id-1"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"uuid":"u-1","identifier":"id-1","state":"wait","executed_volume":"0.0","paid_fee":"0.0"}
                        """));

        CryptoOrderResult r = adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, new BigDecimal("10000"), "id-1");

        assertThat(r.uuid()).isEqualTo("u-1");
        assertThat(r.state()).isEqualTo(CryptoOrderResult.State.WAIT);
        assertThat(r.avgPrice()).isNull();
    }

    @Test
    void marketSellPostsVolumeOrder() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(content().json("""
                        {"market":"KRW-ETH","side":"ask","ord_type":"market","volume":"0.00123456","identifier":"id-2"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"uuid":"u-2","identifier":"id-2","state":"wait","executed_volume":"0","paid_fee":"0"}
                        """));

        assertThat(adapter.sellMarketByVolume(ACCOUNT, StrategyTicker.ETH, new BigDecimal("0.00123456"), "id-2").uuid())
                .isEqualTo("u-2");
    }

    @Test
    void findOrderComputesAveragePriceFromTrades() {
        server.expect(requestTo(BASE_URL + "/v1/order?identifier=id-1"))
                .andRespond(withSuccess("""
                        {"uuid":"u-1","identifier":"id-1","state":"done","executed_volume":"0.0002","paid_fee":"5",
                         "trades":[{"price":"150000000","volume":"0.0001","funds":"15000"},
                                   {"price":"151000000","volume":"0.0001","funds":"15100"}]}
                        """, MediaType.APPLICATION_JSON));

        CryptoOrderResult r = adapter.findOrderByIdentifier(ACCOUNT, "id-1").orElseThrow();

        assertThat(r.state()).isEqualTo(CryptoOrderResult.State.DONE);
        assertThat(r.executedVolume()).isEqualByComparingTo("0.0002");
        assertThat(r.avgPrice()).isEqualByComparingTo("150500000");
    }

    @Test
    void findOrderNotFoundIsEmpty() {
        server.expect(requestTo(BASE_URL + "/v1/order?uuid=missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"name\":\"order_not_found\",\"message\":\"주문을 찾지 못했습니다.\"}}"));

        assertThat(adapter.findOrderByUuid(ACCOUNT, "missing")).isEmpty();
    }
}
```

`CryptoExchangesTest.java`:

```java
package com.kista.tradingcore.broker.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CryptoExchangesTest {

    @Test
    void routesBySupportedBrokerAndRejectsStockBrokers() {
        CryptoExchangePort upbit = mock(CryptoExchangePort.class);
        when(upbit.supports()).thenReturn(Broker.UPBIT);
        CryptoExchanges exchanges = new CryptoExchanges(List.of(upbit));

        assertThat(exchanges.of(Broker.UPBIT)).isSameAs(upbit);
        assertThatThrownBy(() -> exchanges.of(Broker.KIS)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.upbit.UpbitExchangeAdapterTest' --tests 'com.kista.tradingcore.broker.application.service.CryptoExchangesTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패.

- [ ] **Step 2: 도메인 타입·포트·라우터 구현**

`CryptoBalance.java`:

```java
package com.kista.tradingcore.broker.domain.model;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.Map;

// 코인 거래소 잔고 — 원화 가용·잠금 + 지원 코인별 보유(소수 8자리)·평단(KRW). 미지원 코인은 담지 않는다
public record CryptoBalance(
        BigDecimal krwAvailable,                  // 주문 가능 원화
        BigDecimal krwLocked,                     // 주문·출금 대기로 잠긴 원화
        Map<StrategyTicker, Holding> holdings     // 지원 코인별 보유
) {
    public CryptoBalance {
        holdings = Map.copyOf(holdings);
    }

    public record Holding(
            BigDecimal available,   // 매도 가능 수량
            BigDecimal locked,      // 주문 대기로 잠긴 수량
            BigDecimal avgBuyPrice  // 평균 매수가 (KRW, 업비트 avg_buy_price)
    ) {
        // 보유 총수량 — 평가·표시용
        public BigDecimal total() {
            return available.add(locked);
        }
    }
}
```

`CryptoCandle.java`:

```java
package com.kista.tradingcore.broker.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 완성된 코인 일봉 — kstDate는 봉 시작일(KST 09:00 시작, 다음날 09:00 마감), close는 마감 종가
public record CryptoCandle(LocalDate kstDate, BigDecimal close) {
}
```

`CryptoOrderResult.java`:

```java
package com.kista.tradingcore.broker.domain.model;

import java.math.BigDecimal;

// 코인 주문 접수·조회 결과 — 벤더 중립(업비트 state 4종을 그대로 대응)
public record CryptoOrderResult(
        String uuid,                // 거래소 주문 ID
        String identifier,          // 우리가 지정한 멱등 식별자 (주문 UUID)
        State state,                // 주문 상태
        BigDecimal executedVolume,  // 체결 수량 (0이면 미체결)
        BigDecimal avgPrice,        // 체결 평균가 (KRW, 체결 없으면 null)
        BigDecimal paidFee          // 지불 수수료 (KRW)
) {
    public enum State {
        WAIT,   // 체결 대기
        WATCH,  // 예약 주문 대기
        DONE,   // 체결 완료
        CANCEL  // 취소 — 시장가 매수는 잔액 반환으로 체결 후에도 CANCEL일 수 있음(미확인, executedVolume으로 판정)
    }
}
```

`CryptoExchangePort.java`:

```java
package com.kista.tradingcore.broker.application.port.output;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 코인 거래소 포트 — 정수 holdings·LOC/MOC 전제의 BrokerCapabilitiesPort와 분리. CryptoExchanges.of(broker)로 라우팅(2차 빗썸은 구현체만 추가)
public interface CryptoExchangePort {
    // 이 구현체가 담당하는 거래소
    Broker supports();

    // 원화 가용·잠금 + 지원 코인 보유·평단
    CryptoBalance getBalance(BrokerAccountRef account);

    // 현재가 (KRW) — 공개 시세, 계좌 불필요
    Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers);

    // 완성 일봉 최근 count개(1~199), 오래된 순 — 진행 중인 오늘 봉(KST 09:00 시작)은 제외
    List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count);

    // 금액 기준 시장가 매수 — identifier는 계정 내 영구 고유(재사용 불가)
    CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal krwAmount, String identifier);

    // 수량 기준 시장가 매도
    CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String identifier);

    // 주문 조회 — 없으면 empty(재실행 시 identifier로 접수 여부 복구)
    Optional<CryptoOrderResult> findOrderByUuid(BrokerAccountRef account, String uuid);

    Optional<CryptoOrderResult> findOrderByIdentifier(BrokerAccountRef account, String identifier);
}
```

`CryptoExchanges.java`:

```java
package com.kista.tradingcore.broker.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 코인 거래소 포트 라우터 — supports()로 Map 빌드, broker enum으로 조회(BrokerConnectionTesters 패턴)
@Slf4j
@Component
public class CryptoExchanges {

    private final Map<Broker, CryptoExchangePort> ports;

    CryptoExchanges(List<CryptoExchangePort> list) {
        ports = list.stream().collect(Collectors.toMap(CryptoExchangePort::supports, Function.identity()));
        log.info("CryptoExchanges 초기화: {}", ports.keySet());
    }

    // 코인 거래소가 아니면 IllegalArgumentException → 400
    public CryptoExchangePort of(Broker broker) {
        return Optional.ofNullable(ports.get(broker))
                .orElseThrow(() -> new IllegalArgumentException("코인 거래소가 아닌 증권사입니다: " + broker));
    }
}
```

- [ ] **Step 3: 어댑터 구현**

`UpbitExchangeAdapter.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.broker.domain.model.upbit.UpbitApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

// 업비트 CryptoExchangePort — 잔고·시세·일봉·시장가 주문·주문 조회
@Component
class UpbitExchangeAdapter implements CryptoExchangePort {

    private static final int MAX_CANDLES = 200; // 업비트 일봉 1회 최대 개수

    private final UpbitHttpClient http;          // JWT 서명·오류 매핑 공통
    private final UpbitMarketRegistry registry;  // StrategyTicker ↔ KRW-XXX
    private final Clock clock;                   // 진행 중 일봉 판정 — 테스트에서 고정

    @Autowired // 생성자 2개 — 누락 시 빈 생성 실패
    UpbitExchangeAdapter(UpbitHttpClient http, UpbitMarketRegistry registry) {
        this(http, registry, Clock.system(TimeZones.KST));
    }

    UpbitExchangeAdapter(UpbitHttpClient http, UpbitMarketRegistry registry, Clock clock) {
        this.http = http;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    public Broker supports() {
        return Broker.UPBIT;
    }

    @Override
    public CryptoBalance getBalance(BrokerAccountRef account) {
        List<AccountRow> rows = http.get("/v1/accounts", new LinkedHashMap<>(), account, new ParameterizedTypeReference<>() {});
        BigDecimal krwAvailable = BigDecimal.ZERO;
        BigDecimal krwLocked = BigDecimal.ZERO;
        Map<StrategyTicker, CryptoBalance.Holding> holdings = new EnumMap<>(StrategyTicker.class);
        for (AccountRow row : rows) {
            if ("KRW".equals(row.currency())) {
                krwAvailable = row.balance();
                krwLocked = row.locked();
                continue;
            }
            // KRW 기준 평단인 지원 코인만 — 그 외 코인은 전략 대상이 아니라 제외
            if (!"KRW".equals(row.unitCurrency())) continue;
            registry.tickerOfCurrency(row.currency()).ifPresent(t ->
                    holdings.put(t, new CryptoBalance.Holding(row.balance(), row.locked(), row.avgBuyPrice())));
        }
        return new CryptoBalance(krwAvailable, krwLocked, holdings);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers) {
        if (tickers.isEmpty()) return Map.of();
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("markets", tickers.stream().map(registry::marketOf).collect(Collectors.joining(",")));
        List<TickerRow> rows = http.getPublic("/v1/ticker", query, new ParameterizedTypeReference<>() {});
        Map<StrategyTicker, BigDecimal> prices = new EnumMap<>(StrategyTicker.class);
        rows.forEach(r -> registry.tickerOfMarket(r.market()).ifPresent(t -> prices.put(t, r.tradePrice())));
        return prices;
    }

    @Override
    public List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count) {
        if (count < 1 || count >= MAX_CANDLES) throw new IllegalArgumentException("일봉 개수는 1~199입니다: " + count);
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("market", registry.marketOf(ticker));
        query.put("count", String.valueOf(count + 1)); // 진행 중 봉 1개를 버릴 여유
        List<CandleRow> rows = http.getPublic("/v1/candles/days", query, new ParameterizedTypeReference<>() {});
        LocalDateTime now = LocalDateTime.now(clock.withZone(TimeZones.KST));
        // 정렬·진행 중 봉 포함 여부가 문서에 없어 직접 보장 — 시작+1일(다음날 09:00)이 지나야 완성
        List<CryptoCandle> completed = rows.stream()
                .filter(r -> !r.startKst().plusDays(1).isAfter(now))
                .sorted(Comparator.comparing(CandleRow::startKst))
                .map(r -> new CryptoCandle(r.startKst().toLocalDate(), r.tradePrice()))
                .toList();
        return completed.subList(Math.max(0, completed.size() - count), completed.size());
    }

    @Override
    public CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal krwAmount, String identifier) {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("market", registry.marketOf(ticker));
        body.put("side", "bid");
        body.put("ord_type", "price"); // 시장가 매수 — price=총액, volume 생략
        body.put("price", krwAmount.toPlainString());
        body.put("identifier", identifier);
        return toResult(http.post("/v1/orders", body, account, new ParameterizedTypeReference<OrderRow>() {}));
    }

    @Override
    public CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String identifier) {
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        body.put("market", registry.marketOf(ticker));
        body.put("side", "ask");
        body.put("ord_type", "market"); // 시장가 매도 — volume 필수, price 생략
        body.put("volume", volume.toPlainString());
        body.put("identifier", identifier);
        return toResult(http.post("/v1/orders", body, account, new ParameterizedTypeReference<OrderRow>() {}));
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByUuid(BrokerAccountRef account, String uuid) {
        return findOrder(account, "uuid", uuid);
    }

    @Override
    public Optional<CryptoOrderResult> findOrderByIdentifier(BrokerAccountRef account, String identifier) {
        return findOrder(account, "identifier", identifier);
    }

    private Optional<CryptoOrderResult> findOrder(BrokerAccountRef account, String key, String value) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put(key, value);
        try {
            return Optional.of(toResult(http.get("/v1/order", query, account, new ParameterizedTypeReference<OrderRow>() {})));
        } catch (UpbitApiException e) {
            if ("order_not_found".equals(e.errorName())) return Optional.empty(); // 미접수 — 재실행 복구 판단 근거
            throw e;
        }
    }

    // trades가 있으면 Σfunds/Σvolume로 평균가 산출(업비트 응답에 평균가 필드 없음)
    private static CryptoOrderResult toResult(OrderRow o) {
        List<TradeRow> trades = o.trades() == null ? List.of() : o.trades();
        BigDecimal volume = trades.stream().map(TradeRow::volume).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal funds = trades.stream().map(TradeRow::funds).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = volume.signum() > 0 ? funds.divide(volume, 8, RoundingMode.HALF_UP).stripTrailingZeros() : null;
        return new CryptoOrderResult(o.uuid(), o.identifier(), CryptoOrderResult.State.valueOf(o.state().toUpperCase(Locale.ROOT)),
                o.executedVolume() == null ? BigDecimal.ZERO : o.executedVolume(), avg,
                o.paidFee() == null ? BigDecimal.ZERO : o.paidFee());
    }

    // ── 업비트 응답 (숫자는 문자열 decimal — Jackson이 BigDecimal로 변환) ──
    record AccountRow(String currency, BigDecimal balance, BigDecimal locked,
                      @JsonProperty("avg_buy_price") BigDecimal avgBuyPrice,
                      @JsonProperty("unit_currency") String unitCurrency) {}

    record TickerRow(String market, @JsonProperty("trade_price") BigDecimal tradePrice) {}

    record CandleRow(@JsonProperty("candle_date_time_kst") LocalDateTime startKst,
                     @JsonProperty("trade_price") BigDecimal tradePrice) {}

    record OrderRow(String uuid, String identifier, String state,
                    @JsonProperty("executed_volume") BigDecimal executedVolume,
                    @JsonProperty("paid_fee") BigDecimal paidFee,
                    List<TradeRow> trades) {}

    record TradeRow(BigDecimal price, BigDecimal volume, BigDecimal funds) {}
}
```

(Jackson 3에서도 `@JsonProperty`는 `com.fasterxml.jackson.annotation` 패키지 그대로다 — 기존 `KisOrderApi.OrderResponse`가 같은 import를 쓴다. 알 수 없는 필드 무시는 Boot 기본값(`FAIL_ON_UNKNOWN_PROPERTIES=false`)에 기대고, 테스트의 `JsonMapper.builder().build()`가 알 수 없는 필드에서 실패하면 테스트 매퍼를 `JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()`로 만든다.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.*' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 문서 갱신**

`docs/agents/modules/tradingcore/broker.md`:
- `domain/model/` 줄 끝에 `+ 코인 거래소 벤더 중립 타입 CryptoBalance(+Holding)/CryptoCandle(완성 일봉)/CryptoOrderResult(+State)`를 추가하고, `domain/model/kis/·toss/` 줄을 `domain/model/kis/·toss/·upbit/`로, 예외 목록에 `UpbitApiException`을 추가한다.
- `application/port/output/` 줄에 `+ CryptoExchangePort(코인 전용 — BrokerCapabilitiesPort와 분리, 잔고·시세·완성 일봉·금액 시장가 매수·수량 시장가 매도·uuid/identifier 주문 조회)`.
- `application/service/` 줄에 `/CryptoExchanges(of(Broker) — 코인 거래소 아니면 400)`.
- 새 줄: `adapter/out/upbit/ ← UpbitConfig(upbitRestClient)/UpbitHttpClient(요청별 HS512 JWT — JDK Mac, nonce 매 요청, query_hash=비인코딩 쿼리/본문 key=value SHA512, error.name 매핑: no_authorization_ip→BrokerIpNotAllowedException, out_of_scope→BrokerPermissionDeniedException, invalid_access_key/jwt_verification/expired_access_key→BrokerCredentialException, 429·418→BrokerRateLimitException, 그 외 UpbitApiException)/UpbitMarketRegistry(BTC→KRW-BTC)/UpbitExchangeAdapter(CryptoExchangePort)/UpbitAuthApi(BrokerConnectionTestPort). 시세(티커·캔들)는 JWT 없이 호출`.

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src docs/agents/modules/tradingcore/broker.md
git commit -m "$(cat <<'EOF'
feat(broker): 코인 거래소 포트와 업비트 잔고·시세·일봉·시장가 주문 어댑터를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 9: 업비트 계좌 등록 — 검증 결과 record·`UpbitAuthApi`·키 만료일·access key 중복 판정

**업비트 공식 문서 확인 결과:** `GET /v1/api_keys`(별도 권한 불필요) → 배열 `{access_key, expire_at}`, `expire_at`은 `yyyy-MM-dd'T'HH:mm:ss+09:00`(예 `"2026-06-25T11:22:54+09:00"`). 사용자 키가 여러 개일 수 있어 요청 access key와 같은 행을 고른다. 유효기간 1년 여부·실값 형식은 실키로만 확인 가능(미검증).

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V10__account_upbit.sql`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/BrokerAccountVerification.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitAuthApi.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/application/port/output/BrokerConnectionTestPort.java:17-18`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/kis/KisAuthApi.java:129,173`, `.../toss/TossAuthApi.java:80-84`, `.../mock/MockAuthApi.java:24-27`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/domain/model/Account.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/port/output/AccountPort.java` (`existsByAccountNo` → `existsByDedupeKey`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/service/AccountService.java:42-84,144`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/out/persistence/AccountEntity.java:39-40,53`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/out/persistence/AccountPersistenceAdapter.java:71-103`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/dto/AccountRequest.java:13-21`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/dto/AccountResponse.java`
- Modify: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/toss/TossAuthApiTest.java:227-229`, `trading-core/src/test/java/com/kista/tradingcore/account/application/service/AccountServiceTest.java:74,204,225,338` (`thenReturn(null)` → `thenReturn(new BrokerAccountVerification(null, null))`, `"42"` → `new BrokerAccountVerification("42", null)`, `existsByAccountNo` → `existsByDedupeKey`)
- Test: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitAuthApiTest.java`(create), `AccountServiceTest.java`, `trading-core/src/test/java/com/kista/tradingcore/account/adapter/out/persistence/AccountPersistenceAdapterTest.java`, `trading-core/src/test/java/com/kista/tradingcore/account/adapter/in/web/AccountControllerTest.java`, `trading-core/src/test/java/com/kista/tradingcore/account/adapter/out/persistence/AccountPersistenceAdapterDbTest.java`(create)
- Docs: `docs/agents/modules/tradingcore/account.md`, `src/test/resources/architecture/flows.yml`(`account-link`)

**Interfaces:**
- Consumes: Task 7 `UpbitHttpClient`; Task 1 `Broker.UPBIT`, `Broker.getCurrency()`
- Produces:
  - `record BrokerAccountVerification(String brokerAccountCode, Instant apiKeyExpiresAt)` (broker `domain/model`)
  - `BrokerConnectionTestPort.verifyAccount(String appKey, String secretKey, String accountNo): BrokerAccountVerification`
  - `record Account(UUID id, UUID userId, String nickname, String accountNo, String appKey, String secretKey, String brokerAccountCode, Broker broker, Instant createdAt, Instant apiKeyExpiresAt)` + 기존 9인자 보조 생성자(apiKeyExpiresAt=null)
  - `static String Account.dedupeKeyOf(Broker, String accountNo, String appKey)`, `String Account.dedupeKey()`, `Account Account.withCredentials(String appKey, String secretKey, String brokerAccountCode, Instant apiKeyExpiresAt)`
  - `AccountPort.existsByDedupeKey(String dedupeKey): boolean`
  - DB: `accounts.account_no` NULL 허용, `accounts.api_key_expires_at timestamptz NULL`
  - API: `AccountResponse.currency`, `AccountResponse.apiKeyExpiresAt`(nullable), UPBIT는 `accountNo`/`accountNoMasked` null; `AccountRequest.broker` allowableValues에 UPBIT

- [ ] **Step 1: 실패 테스트 작성**

`UpbitAuthApiTest.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpbitAuthApiTest {

    private static final String BASE_URL = "https://api.upbit.com";
    private MockRestServiceServer server;
    private UpbitAuthApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        api = new UpbitAuthApi(new UpbitHttpClient(builder.build(), JsonMapper.builder().build(), () -> "n"));
    }

    @Test
    void verifyAccountReturnsExpiryOfMatchingKey() {
        server.expect(once(), requestTo(BASE_URL + "/v1/accounts"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/api_keys"))
                .andRespond(withSuccess("""
                        [{"access_key":"other","expire_at":"2026-01-01T00:00:00+09:00"},
                         {"access_key":"my-key","expire_at":"2026-06-25T11:22:54+09:00"}]
                        """, MediaType.APPLICATION_JSON));

        BrokerAccountVerification v = api.verifyAccount("my-key", "secret-0123456789012345678901234567890", "ignored");

        assertThat(v.brokerAccountCode()).isNull();
        assertThat(v.apiKeyExpiresAt()).isEqualTo(Instant.parse("2026-06-25T02:22:54Z"));
        assertThat(api.supports()).isEqualTo(Broker.UPBIT);
        server.verify();
    }

    @Test
    void verifyAccountWithoutMatchingKeyHasNullExpiry() {
        server.expect(requestTo(BASE_URL + "/v1/accounts")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/api_keys")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(api.verifyAccount("my-key", "s", null).apiKeyExpiresAt()).isNull();
    }

    @Test
    void ipNotAllowedSurfacesAsSpecificException() {
        server.expect(requestTo(BASE_URL + "/v1/accounts")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"name\":\"no_authorization_ip\",\"message\":\"x\"}}"));

        assertThatThrownBy(() -> api.verifyCredentials("my-key", "s", null)).isInstanceOf(BrokerIpNotAllowedException.class);
    }
}
```

`AccountServiceTest.java`에 추가(기존 `connectionTester`/`brokerEnabledPort` mock 재사용):

```java
    @Test
    @DisplayName("업비트 등록: 계좌번호를 무시하고 access key 해시로 중복 판정, 만료일 저장")
    void register_upbit_ignoresAccountNoAndStoresExpiry() {
        Instant expiresAt = Instant.parse("2027-10-17T00:00:00Z");
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("ak", "sk", null)).thenReturn(new BrokerAccountVerification(null, expiresAt));
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.register(userId,
                new RegisterAccountCommand("업비트", "74420614-01", "ak", "sk", Broker.UPBIT));

        assertThat(saved.accountNo()).isNull();
        assertThat(saved.apiKeyExpiresAt()).isEqualTo(expiresAt);
        verify(accountPort).existsByDedupeKey("ak");
    }

    @Test
    @DisplayName("업비트 등록: 같은 access key가 이미 등록돼 있으면 409")
    void register_upbit_duplicateAccessKey() {
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);
        when(accountPort.countByUserId(userId)).thenReturn(0);
        when(accountPort.existsByDedupeKey("ak")).thenReturn(true);

        assertThatThrownBy(() -> accountService.register(userId,
                new RegisterAccountCommand("업비트", null, "ak", "sk", Broker.UPBIT)))
                .isInstanceOf(Account.DuplicateAccountException.class);
        verifyNoInteractions(connectionTesters);
    }

    @Test
    @DisplayName("업비트 등록: access key가 비면 400")
    void register_upbit_requiresKeys() {
        when(brokerEnabledPort.enabled(Broker.UPBIT)).thenReturn(true);

        assertThatThrownBy(() -> accountService.register(userId,
                new RegisterAccountCommand("업비트", null, " ", "sk", Broker.UPBIT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("업비트 계좌(계좌번호 null)를 가진 사용자가 KIS 계좌를 등록해도 NPE가 나지 않는다")
    void register_kis_whenUserAlreadyHasUpbitAccount_doesNotNpe() {
        when(brokerEnabledPort.enabled(Broker.KIS)).thenReturn(true);
        when(connectionTesters.of(any())).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("appKey", "appSecret", "74420614")).thenReturn(new BrokerAccountVerification(null, null));
        when(accountPort.countByUserId(userId)).thenReturn(1);
        when(accountPort.findByUserId(userId)).thenReturn(List.of(TradingFixtures.upbitAccount(UUID.randomUUID(), userId)));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.register(userId, registerCmd());

        assertThat(saved.accountNo()).isEqualTo("74420614");
    }
```

(import: `com.kista.tradingcore.broker.domain.model.BrokerAccountVerification`, `java.time.Instant`.)

`AccountPersistenceAdapterTest.java`에 추가(기존 mock `crypto`/`hasher`/`accountJpaRepository` 재사용):

```java
    @Test
    void save_upbitWithoutAccountNo_hashesAccessKeyAndSkipsEncryptingNull() {
        Account upbit = new Account(null, UUID.randomUUID(), "업비트", null, "ak", "sk", null, Broker.UPBIT, null,
                Instant.parse("2027-01-01T00:00:00Z"));
        when(crypto.encrypt("ak")).thenReturn("enc-ak");
        when(crypto.encrypt("sk")).thenReturn("enc-sk");
        when(hasher.hash("ak")).thenReturn("hash-ak");
        when(accountJpaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(crypto.decrypt("enc-ak")).thenReturn("ak");
        when(crypto.decrypt("enc-sk")).thenReturn("sk");

        Account saved = adapter.save(upbit);

        verify(crypto, never()).encrypt(null);
        verify(accountJpaRepository).save(argThat(e -> e.getAccountNo() == null && "hash-ak".equals(e.getAccountNoHash())
                && Instant.parse("2027-01-01T00:00:00Z").equals(e.getApiKeyExpiresAt())));
        assertThat(saved.accountNo()).isNull();
        assertThat(saved.apiKeyExpiresAt()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }
```

(테스트 클래스의 어댑터 필드명이 `adapter`가 아니면 맞춘다.)

`AccountPersistenceAdapterDbTest.java`(create — 실 DB에서 nullable·컬럼·unique 인덱스 재사용 확인):

```java
package com.kista.tradingcore.account.adapter.out.persistence;

import com.kista.shared.kernel.Broker;
import com.kista.shared.platform.crypto.AccountNoHasher;
import com.kista.shared.platform.crypto.AesCryptoService;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.account.domain.model.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V10 — account_no NULL 허용·api_key_expires_at·access key 해시가 기존 unique 인덱스를 재사용하는지 실 DB로 확인
@Import({AccountPersistenceAdapter.class, AesCryptoService.class, AccountNoHasher.class})
@Execution(ExecutionMode.SAME_THREAD)
class AccountPersistenceAdapterDbTest extends DataJpaTestBase {

    @Autowired AccountPersistenceAdapter adapter;

    @Test
    void upbitAccountRoundTripsAndDuplicateAccessKeyIsRejectedByIndex() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2027-10-17T00:00:00Z");
        Account saved = adapter.save(new Account(null, userId, "업비트", null, "dup-ak", "sk", null, Broker.UPBIT, null, expiresAt));

        Account loaded = adapter.findById(saved.id()).orElseThrow();
        assertThat(loaded.accountNo()).isNull();
        assertThat(loaded.apiKeyExpiresAt()).isEqualTo(expiresAt);
        assertThat(adapter.existsByDedupeKey("dup-ak")).isTrue();

        assertThatThrownBy(() -> adapter.save(new Account(null, UUID.randomUUID(), "업비트2", null, "dup-ak", "sk2", null, Broker.UPBIT, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

(`AesCryptoService`/`AccountNoHasher`가 `@Import`로 안 올라오면(설정 프로퍼티 필요) 기존 trading-core DB 테스트 중 암호화 빈을 쓰는 클래스의 설정을 따른다 — `grep -rl "AesCryptoService" trading-core/src/test`.)

`AccountControllerTest.java`에 추가:

```java
    @Test
    void register_upbit_responseHasNullAccountNoAndKrwCurrency() throws Exception {
        java.time.Instant expiresAt = java.time.Instant.parse("2027-10-17T00:00:00Z");
        when(accountUseCase.register(any(), any())).thenReturn(new Account(UUID.randomUUID(), UUID.fromString(USER_ID),
                "업비트", null, "ak", "sk", null, Broker.UPBIT, null, expiresAt));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"업비트\",\"appKey\":\"ak\",\"secretKey\":\"sk\",\"broker\":\"UPBIT\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.broker").value("UPBIT"))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.accountNo").doesNotExist())
                .andExpect(jsonPath("$.accountNoMasked").doesNotExist())
                .andExpect(jsonPath("$.apiKeyExpiresAt").value("2027-10-17T00:00:00Z"));
    }
```

(`doesNotExist()`는 null 값 키도 없음으로 본다 — 응답에 `"accountNo":null`로 나가도 통과한다. 기존 KIS 응답 테스트에 `$.currency`=`USD` 단언도 하나 추가한다.)

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.upbit.UpbitAuthApiTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패.

- [ ] **Step 2: 마이그레이션**

`V10__account_upbit.sql`:

```sql
-- 업비트 계좌 — 계좌번호 개념이 없어 account_no NULL 허용, API 키 만료일(업비트 /v1/api_keys expire_at) 저장.
-- 업비트 중복 판정은 account_no_hash에 access key HMAC을 넣어 기존 uq_accounts_account_no_hash를 재사용한다.
-- trading-core 단독 스키마(root expand/contract 밖). 구 이미지는 nullable 차이를 validate하지 않아 호환.
ALTER TABLE trading.accounts ALTER COLUMN account_no DROP NOT NULL;
ALTER TABLE trading.accounts ADD COLUMN api_key_expires_at timestamp with time zone;
COMMENT ON COLUMN trading.accounts.api_key_expires_at IS '거래소 API 키 만료 시각 — 업비트만 값, 그 외 NULL';
```

- [ ] **Step 3: broker 쪽 구현**

`BrokerAccountVerification.java`:

```java
package com.kista.tradingcore.broker.domain.model;

import java.time.Instant;

// 계좌 등록·키 수정 검증 결과 — KIS/Mock: (null, null), Toss: (accountSeq, null), 업비트: (null, 키 만료 시각 또는 null)
public record BrokerAccountVerification(
        String brokerAccountCode, // 증권사 API 보조 식별자
        Instant apiKeyExpiresAt   // 거래소 API 키 만료 시각 (모르면 null)
) {
}
```

`BrokerConnectionTestPort.java` 17-18행:

```java
    // 자격증명+계좌 검증 후 보조 식별자·키 만료일 반환 (KIS: 둘 다 null, Toss: accountSeq, 업비트: accountNo 무시·만료일). 실패 시 BrokerCredentialException
    BrokerAccountVerification verifyAccount(String appKey, String secretKey, String accountNo);
```

`KisAuthApi.verifyAccount` 반환 타입 `BrokerAccountVerification`, 173행 `return new BrokerAccountVerification(null, null); // KIS: brokerAccountCode 없음 (accountNo에 통합)`.
`TossAuthApi.verifyAccount`: `return new BrokerAccountVerification(fetchAccountSeq(token), null);`.
`MockAuthApi.verifyAccount`: `return new BrokerAccountVerification(null, null);`.
`TossAuthApiTest.java:227-229`: `assertThat(api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null).brokerAccountCode()).isEqualTo("42");`.

`UpbitAuthApi.java`:

```java
package com.kista.tradingcore.broker.adapter.out.upbit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.application.port.output.BrokerConnectionTestPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

// 업비트 연결 테스트 — /v1/accounts로 키·허용 IP·자산조회 권한 검증, /v1/api_keys로 해당 키 만료일 조회(토큰 캐시 없음)
@Component
@RequiredArgsConstructor
class UpbitAuthApi implements BrokerConnectionTestPort {

    private final UpbitHttpClient http; // JWT 서명·오류 매핑 공통

    @Override
    public Broker supports() {
        return Broker.UPBIT;
    }

    @Override
    public void verifyCredentials(String appKey, String secretKey, UUID accountId) {
        fetchAccounts(auth(appKey, secretKey)); // 실패는 UpbitHttpClient가 422(IP/권한/키)·429·503으로 매핑
    }

    @Override
    public BrokerAccountVerification verifyAccount(String appKey, String secretKey, String accountNo) {
        // 업비트는 계좌번호가 없다 — accountNo 무시. 주문하기 권한은 실주문 없이 확인할 수 없어 검증 밖
        BrokerAccountRef auth = auth(appKey, secretKey);
        fetchAccounts(auth);
        List<ApiKeyRow> keys = http.get("/v1/api_keys", new LinkedHashMap<>(), auth, new ParameterizedTypeReference<>() {});
        Instant expiresAt = keys.stream()
                .filter(k -> appKey.equals(k.accessKey()) && k.expireAt() != null)
                .map(k -> OffsetDateTime.parse(k.expireAt()).toInstant()) // "2026-06-25T11:22:54+09:00"
                .findFirst().orElse(null);
        return new BrokerAccountVerification(null, expiresAt);
    }

    private void fetchAccounts(BrokerAccountRef auth) {
        http.get("/v1/accounts", new LinkedHashMap<>(), auth, new ParameterizedTypeReference<List<Object>>() {});
    }

    private static BrokerAccountRef auth(String appKey, String secretKey) {
        return new BrokerAccountRef(null, appKey, secretKey, null, null, Broker.UPBIT);
    }

    record ApiKeyRow(@JsonProperty("access_key") String accessKey, @JsonProperty("expire_at") String expireAt) {}
}
```

- [ ] **Step 4: account 쪽 구현**

`Account.java`:

```java
public record Account(
        UUID id,               // PK
        UUID userId,           // FK → users.id
        String nickname,       // 계좌 별칭
        String accountNo,      // 계좌번호 (복호화된 값) — 업비트는 null
        String appKey,      // API 앱 키 — KIS App Key / Toss Client ID / 업비트 Access Key (복호화된 값)
        String secretKey,   // API 앱 시크릿 — KIS Secret Key / Toss Client Secret / 업비트 Secret Key (복호화된 값)
        String brokerAccountCode, // 증권사 API 보조 식별자 — KIS: null(accountNo에 통합), TOSS: accountSeq
        Broker broker,            // 증권사 (기본: KIS)
        Instant createdAt,        // DB created_at, 신규 등록 시 null
        Instant apiKeyExpiresAt   // 거래소 API 키 만료 시각 — 업비트만 값
) {
    // 만료일 없는 기존 생성 지점 호환(KIS/Toss/MOCK·테스트 33곳)
    public Account(UUID id, UUID userId, String nickname, String accountNo, String appKey, String secretKey,
                   String brokerAccountCode, Broker broker, Instant createdAt) {
        this(id, userId, nickname, accountNo, appKey, secretKey, brokerAccountCode, broker, createdAt, null);
    }

    // 전역 중복 판정 키 SSOT — 업비트는 계좌번호가 없어 access key, 그 외는 계좌번호
    public static String dedupeKeyOf(Broker broker, String accountNo, String appKey) {
        return broker == Broker.UPBIT ? appKey : accountNo;
    }

    public String dedupeKey() {
        return dedupeKeyOf(broker, accountNo, appKey);
    }

    // nickname만 교체 — AccountService.update 전용
    public Account withNickname(String newNickname) {
        return new Account(id, userId, newNickname != null ? newNickname : nickname,
                accountNo, appKey, secretKey, brokerAccountCode, broker, createdAt, apiKeyExpiresAt);
    }

    // 자격증명 교체 — AccountService.updateCredentials 전용 (검증 결과의 보조 식별자·만료일 반영)
    public Account withCredentials(String newAppKey, String newSecretKey, String newBrokerAccountCode, Instant newApiKeyExpiresAt) {
        return new Account(id, userId, nickname, accountNo, newAppKey, newSecretKey,
                newBrokerAccountCode, broker, createdAt, newApiKeyExpiresAt);
    }
```

(`toBrokerRef`·`verifyOwnedBy`·`DuplicateAccountException`은 그대로.)

`AccountPort.java`: `boolean existsByAccountNo(String accountNo);`를 아래로 교체(주석 포함):

```java
    // 전역 중복 판정 — Account.dedupeKey() 값(업비트 access key / 그 외 계좌번호)의 해시로 조회
    boolean existsByDedupeKey(String dedupeKey);
```

`AccountEntity.java`:

```java
    @Column(name = "account_no", length = 512)
    private String accountNo;            // AES-256 암호화 저장 — 업비트는 null
```
`deletedAt` 아래(마지막):
```java
    @Column(name = "api_key_expires_at")
    private Instant apiKeyExpiresAt; // 거래소 API 키 만료 시각 — 업비트만 값
```

`AccountPersistenceAdapter.java`:

```java
    @Override
    public boolean existsByDedupeKey(String dedupeKey) {
        // 플레인텍스트 해시 후 DB 조회 — AES-GCM은 비결정론적이므로 해시 경유 필수
        return jpaRepository.existsByAccountNoHashAndDeletedAtIsNull(hasher.hash(dedupeKey));
    }
```
`toEntity`:
```java
        e.setAccountNo(a.accountNo() != null ? crypto.encrypt(a.accountNo()) : null); // 업비트는 계좌번호 없음
        e.setAccountNoHash(hasher.hash(a.dedupeKey())); // 전역 중복 체크용 HMAC-SHA256 — 업비트는 access key
```
(`setBrokerAccountCode` 아래에 `e.setApiKeyExpiresAt(a.apiKeyExpiresAt());`)
`toDomain`:
```java
        return new Account(
                e.getId(), e.getUserId(), e.getNickname(),
                e.getAccountNo() != null ? crypto.decrypt(e.getAccountNo()) : null,
                crypto.decrypt(e.getAppKey()),
                crypto.decrypt(e.getSecretKey()),
                e.getBrokerAccountCode(),
                e.getBroker(),
                e.getCreatedAt(),
                e.getApiKeyExpiresAt()
        );
```

`AccountService.register` 46~84행 교체:

```java
        // MOCK은 서버가 합성 자격증명 생성, UPBIT는 계좌번호 없이 키만, 그 외 브로커는 계좌번호 필수
        String accountNo = cmd.accountNo();
        String appKey = cmd.appKey();
        String secretKey = cmd.secretKey();
        if (broker == Broker.MOCK) {
            if (accountNo == null || accountNo.isBlank()) accountNo = generateMockAccountNo();
            if (appKey == null || appKey.isBlank()) appKey = "MOCK";
            if (secretKey == null || secretKey.isBlank()) secretKey = "MOCK";
        } else if (broker == Broker.UPBIT) {
            accountNo = null; // 업비트는 계좌번호 개념이 없다 — 요청에 와도 무시
            if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalArgumentException("API 키와 시크릿은 필수입니다");
            }
        } else if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("계좌번호는 필수입니다");
        }
        // 람다 캡처를 위한 effectively-final 복사본 (accountNo는 위 분기에서 재할당됨)
        final String resolvedAccountNo = accountNo;

        if (accountPort.countByUserId(userId) >= MAX_ACCOUNTS_PER_USER) {
            throw new IllegalStateException("계좌는 최대 " + MAX_ACCOUNTS_PER_USER + "개까지 등록 가능합니다");
        }
        // 전역 중복 체크 (크로스-유저, 해시 기반) — 업비트는 access key, 그 외 계좌번호
        String dedupeKey = Account.dedupeKeyOf(broker, resolvedAccountNo, appKey);
        if (accountPort.existsByDedupeKey(dedupeKey)) {
            throw new Account.DuplicateAccountException(dedupeKey);
        }
        // 동일 사용자 중복 체크 (V11 이전 NULL-hash 기존 레코드 대비 fallback) — 업비트 기존 계좌는 accountNo null이라 null-safe 비교
        if (resolvedAccountNo != null) {
            accountPort.findByUserId(userId).stream()
                    .filter(a -> resolvedAccountNo.equals(a.accountNo()))
                    .findAny()
                    .ifPresent(a -> { throw new Account.DuplicateAccountException(resolvedAccountNo); });
        }
        // 증권사별 자격증명+계좌 검증 — KIS: 계좌번호 소유, Toss: accountSeq, 업비트: 키 만료일
        BrokerAccountVerification verified = connectionTesters.of(broker)
                .verifyAccount(appKey, secretKey, accountNo);

        Account account = new Account(
                null, userId, cmd.nickname(),
                accountNo, appKey, secretKey,
                verified.brokerAccountCode(),
                broker,
                null,    // createdAt — DB에서 자동 설정
                verified.apiKeyExpiresAt()
        );
```

`generateMockAccountNo`의 `accountPort.existsByAccountNo(candidate)` → `existsByDedupeKey(candidate)`. import `com.kista.tradingcore.broker.domain.model.BrokerAccountVerification`.

`AccountRequest.java`:

```java
        @Schema(description = "계좌번호 — KIS: XXXXXXXX-XX (예: 74420614-01), Toss: XXX-XX-XXXXXX (예: 131-01-001931), MOCK: 생략 가능(서버 자동 생성), UPBIT: 받지 않음(오면 무시)", example = "74420614-01")
        @Pattern(regexp = "\\d{8}-\\d{2}|\\d{3}-\\d{2}-\\d{6}", message = "계좌번호는 KIS XXXXXXXX-XX 또는 Toss XXX-XX-XXXXXX 형식이어야 합니다")
        String accountNo,
        @Schema(description = "API 앱 키 (KIS App Key / Toss Client ID / 업비트 Access Key)", example = "PSxxxxxxxxxx")
        String appKey,
        @Schema(description = "API 앱 시크릿 (KIS App Secret / Toss Client Secret / 업비트 Secret Key)")
        String secretKey,
        @Schema(description = "증권사 — null이면 KIS 기본값 적용", example = "KIS", allowableValues = {"KIS", "TOSS", "MOCK", "UPBIT"})
        Broker broker
```

(업비트 요청에 형식이 틀린 accountNo가 오면 `@Pattern`이 400을 낸다 — 스펙 "오면 무시"는 형식이 맞는 값에만 성립한다. UI는 업비트에서 accountNo를 보내지 않으므로 1차는 이대로 둔다.)

`AccountResponse.java`:

```java
public record AccountResponse(
        @Schema(description = "계좌 고유 ID")
        UUID id,
        @Schema(description = "계좌 별명", example = "내 메인 계좌")
        String nickname,
        @Schema(description = "마스킹된 계좌번호 (업비트는 null)", example = "****0614")
        String accountNoMasked,
        @Schema(description = "전체 계좌번호 (본인 계좌, 토글 표시용 — 업비트는 null)", example = "74420614")
        String accountNo,
        @Schema(description = "증권사", example = "KIS")
        String broker,
        @Schema(description = "계좌 기준 통화 (증권사 파생)", example = "USD")
        String currency,
        @Schema(description = "거래소 API 키 만료 시각 (업비트만, 그 외 null)")
        Instant apiKeyExpiresAt
) {
    public static AccountResponse from(Account a) {
        // accountNo가 이미 전체 표시 형식 — KIS: "74420614-01", TOSS: "131-01-001931", 업비트: null
        return new AccountResponse(
                a.id(),
                a.nickname(),
                a.accountNo() != null ? AccountNumberMasker.mask(a.accountNo()) : null, // mask(null)은 "****"라 직접 분기
                a.accountNo(),
                a.broker() != null ? a.broker().name() : null,
                a.broker() != null ? a.broker().getCurrency().name() : null,
                a.apiKeyExpiresAt()
        );
    }
}
```

(import `java.time.Instant`.) 테스트 4곳(`AccountServiceTest` 74·204·225·338)을 Files 목록대로 고친다.

- [ ] **Step 5: 통과 확인**

Run: `docker compose up -d postgres && ./gradlew :trading-core:test --tests 'com.kista.tradingcore.account.*' --tests 'com.kista.tradingcore.broker.*' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' :test --tests 'com.kista.api.admin.AdminInternalApiIntegrationTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: 문서·flows 갱신**

`docs/agents/modules/tradingcore/account.md` `domain/model/` 줄에 `Account(apiKeyExpiresAt — 업비트 키 만료, dedupeKey() — 업비트 access key·그 외 계좌번호 = 전역 중복 판정 키 SSOT, withCredentials)` 를 반영하고, `adapter/out/persistence/` 줄에 `account_no NULL 허용(업비트), account_no_hash = HMAC(dedupeKey)`를 덧붙인다.
`flows.yml` `account-link`:
- `summary`의 `KIS·Toss·MOCK 계좌를`을 `KIS·Toss·MOCK·업비트 계좌를`로.
- `connection-test` desc 끝에 `업비트는 /v1/accounts로 키·허용 IP·자산조회 권한을 확인한다.`, `code:`에 `UpbitAuthApi#verifyCredentials` 추가, `fail` 끝에 `업비트 허용 IP 미등록은 422(BROKER_IP_NOT_ALLOWED), 권한 부족은 422(BROKER_PERMISSION_DENIED).`
- `register` desc 끝에 `업비트는 계좌번호 없이 키·시크릿만 보낸다(와도 무시).`
- `guard` desc의 `(계좌번호는 해시로 비교한다)`를 `(계좌번호 — 업비트는 access key — 를 해시로 비교한다)`로, `code:`의 `AccountPort#existsByAccountNo`를 `AccountPort#existsByDedupeKey`로.
- `verify` desc 끝에 `업비트는 키 검증 후 /v1/api_keys에서 그 키의 만료일(expire_at)을 받아 함께 저장한다.`, `code:`에 `UpbitAuthApi#verifyAccount` 추가.
- `save` desc의 `계좌번호의 HMAC 해시`를 `계좌번호(업비트는 access key)의 HMAC 해시`로, `state`를 `"accounts +1 (업비트: account_no NULL·api_key_expires_at)"`로.

Run: `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"` → `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src docs/agents/modules/tradingcore/account.md src/test/resources/architecture/flows.yml
git commit -m "$(cat <<'EOF'
feat(account): 업비트 계좌를 access key 중복 판정과 키 만료일로 등록한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 10: 키 수정 `PUT /api/accounts/{id}/credentials`

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/account/domain/model/UpdateCredentialsCommand.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/dto/CredentialsRequest.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/usecase/AccountUseCase.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/service/AccountService.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/AccountController.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/account/application/service/AccountServiceTest.java`, `trading-core/src/test/java/com/kista/tradingcore/account/adapter/in/web/AccountControllerTest.java`
- Docs: `docs/agents/modules/tradingcore/account.md`, `src/test/resources/architecture/flows.yml`

**Interfaces:**
- Consumes: Task 9 `Account.withCredentials`, `AccountPort.existsByDedupeKey`, `BrokerAccountVerification`; 기존 `BrokerTokenCachePort.deleteByAccountIds`
- Produces:
  - `record UpdateCredentialsCommand(String appKey, String secretKey)`
  - `Account AccountUseCase.updateCredentials(UUID accountId, UUID requesterId, UpdateCredentialsCommand command)`
  - `PUT /api/accounts/{id}/credentials` body `{appKey, secretKey}`(둘 다 `@NotBlank`) → 200 `AccountResponse`

**결정(스펙에 없음, 이 계획이 정함):** 키 교체는 신규 등록이 아니므로 `requireBrokerEnabled`를 적용하지 않는다(비활성 증권사 계좌도 만료 키를 교체할 수 있어야 함). MOCK은 키가 없어 400. 증권사별 동일 계좌 확인: KIS는 저장된 계좌번호로 `verifyAccount`, Toss는 반환 accountSeq가 저장된 `brokerAccountCode`와 같아야 함, 업비트는 새 access key가 다른 계좌에 이미 있으면 409. 저장 후 KIS 토큰 캐시(`broker_tokens`)를 지운다(옛 키로 발급된 토큰 — Toss Redis 토큰은 401 시 새 키로 자동 재발급되므로 대상 아님).

- [ ] **Step 1: 실패 테스트 작성**

`AccountServiceTest.java`에 추가:

```java
    @Test
    @DisplayName("키 수정: 연결 테스트 통과 시 저장하고 만료일을 갱신하며 KIS 토큰 캐시를 지운다")
    void updateCredentials_upbit_savesAndEvictsTokenCache() {
        Account upbit = TradingFixtures.upbitAccount(accountId, userId);
        Instant expiresAt = Instant.parse("2027-10-17T00:00:00Z");
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(upbit);
        when(accountPort.existsByDedupeKey("new-ak")).thenReturn(false);
        when(connectionTesters.of(Broker.UPBIT)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("new-ak", "new-sk", null)).thenReturn(new BrokerAccountVerification(null, expiresAt));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("new-ak", "new-sk"));

        assertThat(saved.appKey()).isEqualTo("new-ak");
        assertThat(saved.apiKeyExpiresAt()).isEqualTo(expiresAt);
        InOrder order = inOrder(accountPort, brokerTokenCachePort);
        order.verify(accountPort).save(any());
        order.verify(brokerTokenCachePort).deleteByAccountIds(List.of(accountId));
        verifyNoInteractions(brokerEnabledPort); // 키 교체는 신규 등록 정책 대상이 아니다
    }

    @Test
    @DisplayName("키 수정: 연결 테스트 실패면 저장하지 않는다")
    void updateCredentials_verificationFails_doesNotSave() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(activeAccount(userId));
        when(connectionTesters.of(Broker.KIS)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("bad", "bad", "74420614"))
                .thenThrow(new com.kista.tradingcore.broker.domain.model.BrokerCredentialException());

        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("bad", "bad")))
                .isInstanceOf(com.kista.tradingcore.broker.domain.model.BrokerCredentialException.class);
        verify(accountPort, never()).save(any());
        verifyNoInteractions(brokerTokenCachePort);
    }

    @Test
    @DisplayName("키 수정: Toss 새 키가 다른 계좌를 가리키면 거부한다")
    void updateCredentials_toss_differentAccountSeq_rejected() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.tossAccount(accountId, userId)); // brokerAccountCode "1"
        when(connectionTesters.of(Broker.TOSS)).thenReturn(connectionTester);
        when(connectionTester.verifyAccount("cid", "cs", "123-45-678901")).thenReturn(new BrokerAccountVerification("2", null));

        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("cid", "cs")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("다른 계좌");
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("키 수정: 업비트 새 access key가 다른 계좌에 있으면 409, MOCK은 400, 남의 계좌는 403")
    void updateCredentials_guards() {
        when(accountPort.requireOwnedAccount(accountId, userId)).thenReturn(TradingFixtures.upbitAccount(accountId, userId));
        when(accountPort.existsByDedupeKey("taken")).thenReturn(true);
        assertThatThrownBy(() -> accountService.updateCredentials(accountId, userId, new UpdateCredentialsCommand("taken", "sk")))
                .isInstanceOf(Account.DuplicateAccountException.class);

        UUID mockId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(mockId, userId)).thenReturn(
                new Account(mockId, userId, "모의", "12345678-01", "MOCK", "MOCK", null, Broker.MOCK, null));
        assertThatThrownBy(() -> accountService.updateCredentials(mockId, userId, new UpdateCredentialsCommand("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);

        UUID foreignId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(foreignId, userId)).thenThrow(new SecurityException("계좌에 대한 접근 권한이 없습니다"));
        assertThatThrownBy(() -> accountService.updateCredentials(foreignId, userId, new UpdateCredentialsCommand("a", "b")))
                .isInstanceOf(SecurityException.class);
        verify(accountPort, never()).save(any());
    }
```

(import: `com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand`.)

`AccountControllerTest.java`에 추가:

```java
    @Test
    void updateCredentials_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(accountUseCase.updateCredentials(eq(id), eq(UUID.fromString(USER_ID)), any()))
                .thenReturn(new Account(id, UUID.fromString(USER_ID), "업비트", null, "new-ak", "new-sk", null, Broker.UPBIT, null,
                        java.time.Instant.parse("2027-10-17T00:00:00Z")));

        mockMvc.perform(put("/api/accounts/{id}/credentials", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\"new-ak\",\"secretKey\":\"new-sk\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyExpiresAt").value("2027-10-17T00:00:00Z"))
                .andExpect(jsonPath("$.appKey").doesNotExist());
        verify(accountUseCase).updateCredentials(id, UUID.fromString(USER_ID),
                new com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand("new-ak", "new-sk"));
    }

    @Test
    void updateCredentials_blankKey_returns400() throws Exception {
        mockMvc.perform(put("/api/accounts/{id}/credentials", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appKey\":\" \",\"secretKey\":\"sk\"}")
                        .with(csrf()).with(authentication(userToken(UUID.fromString(USER_ID)))))
                .andExpect(status().isBadRequest());
    }
```

(필요 static import: `org.mockito.ArgumentMatchers.eq`, `org.mockito.Mockito.verify` — `WebMvcTestSupport.*`에 `put`이 없으면 `MockMvcRequestBuilders.put`.)

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.account.*' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패.

- [ ] **Step 2: 구현**

`UpdateCredentialsCommand.java`:

```java
package com.kista.tradingcore.account.domain.model;

// 계좌 API 키 교체 입력 — 평문(저장 시 persistence 경계에서 암호화)
public record UpdateCredentialsCommand(
        String appKey,    // 새 앱 키 (업비트 Access Key)
        String secretKey  // 새 앱 시크릿 (업비트 Secret Key)
) {}
```

`CredentialsRequest.java`:

```java
package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 계좌 API 키 교체 요청 — 모든 증권사 공통(MOCK 제외)
public record CredentialsRequest(
        @Schema(description = "새 API 앱 키 (KIS App Key / Toss Client ID / 업비트 Access Key)")
        @NotBlank String appKey,
        @Schema(description = "새 API 앱 시크릿 (KIS App Secret / Toss Client Secret / 업비트 Secret Key)")
        @NotBlank String secretKey
) {
    public UpdateCredentialsCommand toCommand() {
        return new UpdateCredentialsCommand(appKey.strip(), secretKey.strip());
    }
}
```

`AccountUseCase.java` `// --- 수정 ---` 아래:

```java
    // 계좌 API 키 교체 — 소유권 → 연결 테스트 통과 시 저장·만료일 갱신. 실패 시 BrokerCredentialException(422)
    Account updateCredentials(UUID accountId, UUID requesterId, UpdateCredentialsCommand command);
```

`AccountService.java` `update` 메서드 아래:

```java
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 증권사 검증 HTTP 포함 — 트랜잭션 없이 실행(단건 저장은 JPA auto-commit)
    public Account updateCredentials(UUID accountId, UUID requesterId, UpdateCredentialsCommand cmd) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (account.broker() == Broker.MOCK) {
            throw new IllegalArgumentException("모의계좌는 API 키를 바꿀 수 없습니다.");
        }
        // 업비트는 access key가 곧 중복 판정 키 — 다른 계좌가 이미 쓰는 키면 거부(같은 키 재입력은 허용)
        if (account.broker() == Broker.UPBIT && !cmd.appKey().equals(account.appKey())
                && accountPort.existsByDedupeKey(cmd.appKey())) {
            throw new Account.DuplicateAccountException(cmd.appKey());
        }
        // 키 교체는 신규 등록이 아니라 requireBrokerEnabled를 적용하지 않는다 — KIS는 저장된 계좌번호 소유까지 검증
        BrokerAccountVerification verified = connectionTesters.of(account.broker())
                .verifyAccount(cmd.appKey(), cmd.secretKey(), account.accountNo());
        if (account.broker() == Broker.TOSS && !Objects.equals(verified.brokerAccountCode(), account.brokerAccountCode())) {
            throw new IllegalArgumentException("등록된 계좌와 다른 계좌의 API 키입니다.");
        }
        Account saved = accountPort.save(account.withCredentials(
                cmd.appKey(), cmd.secretKey(), account.brokerAccountCode(), verified.apiKeyExpiresAt()));
        brokerTokenCachePort.deleteByAccountIds(List.of(accountId)); // 옛 키로 발급된 KIS 토큰 캐시 제거
        log.info("계좌 키 교체: accountId={}, broker={}", accountId, account.broker());
        return saved;
    }
```

(import `java.util.Objects`, `com.kista.tradingcore.account.domain.model.UpdateCredentialsCommand`.)

`AccountController.java` `update` 메서드 아래:

```java
    // 계좌 API 키 교체 (소유권 검증 → 연결 테스트 통과 시 저장)
    @Operation(summary = "계좌 API 키 수정", description = "새 앱 키·시크릿으로 증권사 연결을 검증한 뒤 저장하고 키 만료일(업비트)을 갱신. 모의계좌는 불가.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공"),
            @ApiResponse(responseCode = "400", description = "모의계좌이거나 다른 계좌의 키"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "다른 계좌에 이미 등록된 키(업비트)"),
            @ApiResponse(responseCode = "422", description = "키가 유효하지 않거나 IP 미허용·권한 부족")
    })
    @PutMapping("/{id}/credentials")
    public AccountResponse updateCredentials(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody CredentialsRequest request) {
        return AccountResponse.from(accountUseCase.updateCredentials(id, userId, request.toCommand()));
    }
```

(import `com.kista.tradingcore.account.adapter.in.web.dto.CredentialsRequest`.)

- [ ] **Step 3: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.account.*' :test --tests 'com.kista.api.architecture.CaddyRoutingTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: 문서·flows 갱신**

`account.md` `application/service/` 줄에 `updateCredentials(키 교체 — NOT_SUPPORTED, 소유권 → MOCK 거부 → 업비트 키 중복 → verifyAccount(KIS 저장 계좌번호·Toss accountSeq 일치) → 저장·만료일 갱신 → broker_tokens 삭제. 신규 등록 정책(requireBrokerEnabled)은 적용하지 않음)`을 추가한다.
`flows.yml` `account-link`의 `rename` step 아래에 step 추가:

```yaml
      - id: rotate-credentials
        lane: ui
        to: [trading, broker, db]
        title: API 키 교체
        desc: >-
          계좌 화면에서 새 키·시크릿을 입력하면 PUT /api/accounts/{id}/credentials가 소유권을 확인하고, 증권사 연결 테스트를 통과해야 저장한다.
          KIS는 저장된 계좌번호 소유까지, Toss는 같은 계좌(accountSeq)인지까지 확인하고, 업비트는 새 access key가 다른 계좌에 이미 있으면 거부하며 키 만료일을 갱신한다.
          저장 후 옛 키로 발급된 KIS 토큰 캐시를 지운다. 신규 등록이 꺼진 증권사도 키 교체는 할 수 있다. 모의계좌는 키가 없어 불가.
        code: [AccountController#updateCredentials, AccountService#updateCredentials, BrokerConnectionTesters#of, BrokerTokenCachePort#deleteByAccountIds]
        state: "accounts.app_key·secret_key·api_key_expires_at 갱신 · broker_tokens 삭제"
        fail: 키 오류 422(BROKER_CREDENTIAL_INVALID·BROKER_IP_NOT_ALLOWED·BROKER_PERMISSION_DENIED), 다른 계좌 키는 400(Toss)·409(업비트 DUPLICATE_ACCOUNT), 모의계좌 400, 남의 계좌 403.
```

`rename` step desc의 `계좌번호와 자격증명은 수정할 수 없다.`를 `계좌번호는 수정할 수 없고, 자격증명은 아래 키 교체로 바꾼다.`로 바꾼다.

Run: `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"` → `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src docs/agents/modules/tradingcore/account.md src/test/resources/architecture/flows.yml
git commit -m "$(cat <<'EOF'
feat(account): 연결 테스트를 통과한 새 API 키로 계좌 자격증명을 교체하는 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 11: 코인 포트폴리오 `GET /api/accounts/{id}/crypto-portfolio`

소유 모듈: `tradingcore.stats`(계좌 잔고·포트폴리오 조회 담당, `BrokerCallGuard`·broker "application" NamedInterface 이미 소비). 기존 `StatisticsController`/`AccountStatisticsService`에 섞지 않고(생성자 주입 테스트 연쇄 수정 회피) 단일 책임 파일로 둔다.

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoPortfolio.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/usecase/CryptoPortfolioUseCase.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CryptoPortfolioService.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/CryptoPortfolioController.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/dto/CryptoPortfolioResponse.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/application/service/CryptoPortfolioServiceTest.java`, `trading-core/src/test/java/com/kista/tradingcore/stats/adapter/in/web/CryptoPortfolioControllerTest.java`
- Docs: `docs/agents/modules/tradingcore/stats.md`, `src/test/resources/architecture/flows.yml`(`result.account-detail`)

**Interfaces:**
- Consumes: Task 8 `CryptoExchanges.of(Broker)`, `CryptoExchangePort.getBalance/getCurrentPrices`, `CryptoBalance`; 기존 `AccountPort.requireOwnedAccount`, `BrokerCallGuard.wrap`
- Produces:
  - `record CryptoPortfolio(BigDecimal krwCash, List<Holding> holdings)` + `record Holding(StrategyTicker ticker, BigDecimal quantity, BigDecimal avgBuyPrice, BigDecimal currentPrice, BigDecimal evalAmountKrw)` + `BigDecimal totalAssetKrw()`
  - `CryptoPortfolio CryptoPortfolioUseCase.getCryptoPortfolio(UUID accountId, UUID requesterId)` — 주식 계좌면 `IllegalArgumentException`(400)
  - 응답 `{krwCash, holdings:[{ticker, quantity, avgBuyPrice, currentPrice, evalAmountKrw}], totalAssetKrw}`

- [ ] **Step 1: 실패 테스트 작성**

`CryptoPortfolioServiceTest.java`:

```java
package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CryptoPortfolioServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock AccountPort accountPort;
    @Mock CryptoExchanges exchanges;
    @Mock CryptoExchangePort upbit;
    CryptoPortfolioService service;

    @BeforeEach
    void setUp() {
        service = new CryptoPortfolioService(accountPort, exchanges);
    }

    @Test
    void portfolioEvaluatesHoldingsInKrw() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(new BigDecimal("1000000"), new BigDecimal("5000"),
                Map.of(StrategyTicker.BTC, new CryptoBalance.Holding(new BigDecimal("0.001"), new BigDecimal("0.0005"), new BigDecimal("140000000")))));
        when(upbit.getCurrentPrices(Set.of(StrategyTicker.BTC))).thenReturn(Map.of(StrategyTicker.BTC, new BigDecimal("150000000")));

        CryptoPortfolio p = service.getCryptoPortfolio(ACCOUNT_ID, USER_ID);

        assertThat(p.krwCash()).isEqualByComparingTo("1005000");
        assertThat(p.holdings()).singleElement().satisfies(h -> {
            assertThat(h.quantity()).isEqualByComparingTo("0.0015");
            assertThat(h.evalAmountKrw()).isEqualByComparingTo("225000");
        });
        assertThat(p.totalAssetKrw()).isEqualByComparingTo("1230000");
    }

    @Test
    void emptyHoldingsSkipPriceCall() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.upbitAccount(ACCOUNT_ID, USER_ID));
        when(exchanges.of(Broker.UPBIT)).thenReturn(upbit);
        when(upbit.getBalance(any())).thenReturn(new CryptoBalance(BigDecimal.TEN, BigDecimal.ZERO, Map.of()));

        assertThat(service.getCryptoPortfolio(ACCOUNT_ID, USER_ID).holdings()).isEmpty();
        verify(upbit, never()).getCurrentPrices(any());
    }

    @Test
    void stockAccountIsRejected() {
        when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));

        assertThatThrownBy(() -> service.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(exchanges);
    }
}
```

`CryptoPortfolioControllerTest.java`:

```java
package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.application.usecase.CryptoPortfolioUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.mockito.Mockito.when;

@WebMvcTest(CryptoPortfolioController.class)
@Execution(ExecutionMode.SAME_THREAD)
class CryptoPortfolioControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CryptoPortfolioUseCase useCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void returnsKrwPortfolio() throws Exception {
        when(useCase.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).thenReturn(new CryptoPortfolio(new BigDecimal("1000"),
                List.of(new CryptoPortfolio.Holding(StrategyTicker.BTC, new BigDecimal("0.0015"), new BigDecimal("140000000"),
                        new BigDecimal("150000000"), new BigDecimal("225000")))));

        mockMvc.perform(get("/api/accounts/{id}/crypto-portfolio", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.krwCash").value(1000))
                .andExpect(jsonPath("$.holdings[0].ticker").value("BTC"))
                .andExpect(jsonPath("$.holdings[0].quantity").value(0.0015))
                .andExpect(jsonPath("$.totalAssetKrw").value(226000));
    }

    @Test
    void stockAccountReturns400() throws Exception {
        when(useCase.getCryptoPortfolio(ACCOUNT_ID, USER_ID)).thenThrow(new IllegalArgumentException("코인 계좌가 아닙니다."));

        mockMvc.perform(get("/api/accounts/{id}/crypto-portfolio", ACCOUNT_ID).with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }
}
```

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.*Crypto*' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패.

- [ ] **Step 2: 구현**

`CryptoPortfolio.java`:

```java
package com.kista.tradingcore.stats.domain.model;

import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.util.List;

// 코인 계좌 포트폴리오 — 원화 예수금 + 코인별 보유·평단·현재가·평가액(KRW)
public record CryptoPortfolio(
        BigDecimal krwCash,     // 원화 예수금 (가용 + 주문 대기 잠금)
        List<Holding> holdings  // 지원 코인별 보유
) {
    public CryptoPortfolio {
        holdings = List.copyOf(holdings);
    }

    public record Holding(
            StrategyTicker ticker,     // 코인 종목
            BigDecimal quantity,       // 보유 총수량 (가용 + 잠금)
            BigDecimal avgBuyPrice,    // 평균 매수가 (KRW)
            BigDecimal currentPrice,   // 현재가 (KRW, 시세 없으면 null)
            BigDecimal evalAmountKrw   // 평가액 = 수량 × 현재가 (원 단위 반올림, 시세 없으면 0)
    ) {}

    // 총자산 = 예수금 + 평가액 합
    public BigDecimal totalAssetKrw() {
        return holdings.stream().map(Holding::evalAmountKrw).reduce(krwCash, BigDecimal::add);
    }
}
```

`CryptoPortfolioUseCase.java`:

```java
package com.kista.tradingcore.stats.application.usecase;

import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;

import java.util.UUID;

// 코인 계좌 포트폴리오 조회 — 주식 계좌는 IllegalArgumentException(400)
public interface CryptoPortfolioUseCase {
    CryptoPortfolio getCryptoPortfolio(UUID accountId, UUID requesterId);
}
```

`CryptoPortfolioService.java`:

```java
package com.kista.tradingcore.stats.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.application.port.output.CryptoExchangePort;
import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.tradingcore.broker.application.service.CryptoExchanges;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.stats.application.usecase.CryptoPortfolioUseCase;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 코인 계좌 잔고 → KRW 평가 포트폴리오 (거래소 실잔고가 SSOT, DB 포지션 없음)
@Service
@RequiredArgsConstructor
class CryptoPortfolioService implements CryptoPortfolioUseCase {

    private final AccountPort accountPort;       // 소유권 검증
    private final CryptoExchanges exchanges;     // 코인 거래소 라우터

    @Override
    public CryptoPortfolio getCryptoPortfolio(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (account.broker().getMarket() != Market.CRYPTO_KRW) {
            throw new IllegalArgumentException("코인 계좌가 아닙니다. 주식 계좌는 /portfolio를 사용하세요.");
        }
        CryptoExchangePort exchange = exchanges.of(account.broker());
        CryptoBalance balance = BrokerCallGuard.wrap("코인 잔고 조회", () -> exchange.getBalance(account.toBrokerRef()));
        // 보유 코인이 없으면 시세 조회 생략
        Map<StrategyTicker, BigDecimal> prices = balance.holdings().isEmpty() ? Map.of()
                : BrokerCallGuard.wrap("코인 시세 조회", () -> exchange.getCurrentPrices(balance.holdings().keySet()));
        List<CryptoPortfolio.Holding> holdings = balance.holdings().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> toHolding(e.getKey(), e.getValue(), prices.get(e.getKey())))
                .toList();
        return new CryptoPortfolio(balance.krwAvailable().add(balance.krwLocked()), holdings);
    }

    private static CryptoPortfolio.Holding toHolding(StrategyTicker ticker, CryptoBalance.Holding h, BigDecimal price) {
        BigDecimal quantity = h.total();
        BigDecimal eval = price == null ? BigDecimal.ZERO : quantity.multiply(price).setScale(0, RoundingMode.HALF_UP);
        return new CryptoPortfolio.Holding(ticker, quantity, h.avgBuyPrice(), price, eval);
    }
}
```

(`BrokerCallGuard.wrap`의 실제 시그니처(`String, Supplier<T>`)를 `AccountStatisticsService`의 사용과 맞춘다.)

`CryptoPortfolioResponse.java`:

```java
package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

// 코인 계좌 포트폴리오 응답 — 주식 /portfolio(USD·정수 holdings)와 스키마를 분리해 openapi 단일 타입 유지
public record CryptoPortfolioResponse(
        @Schema(description = "원화 예수금 (가용 + 주문 대기)") BigDecimal krwCash,
        @Schema(description = "코인별 보유") List<CryptoHoldingResponse> holdings,
        @Schema(description = "총자산 (KRW) = 예수금 + 평가액 합") BigDecimal totalAssetKrw
) {
    public static CryptoPortfolioResponse from(CryptoPortfolio p) {
        return new CryptoPortfolioResponse(p.krwCash(),
                p.holdings().stream().map(CryptoHoldingResponse::from).toList(), p.totalAssetKrw());
    }

    public record CryptoHoldingResponse(
            @Schema(description = "종목", example = "BTC") String ticker,
            @Schema(description = "보유 수량 (소수 8자리)") BigDecimal quantity,
            @Schema(description = "평균 매수가 (KRW)") BigDecimal avgBuyPrice,
            @Schema(description = "현재가 (KRW, 시세 없으면 null)") BigDecimal currentPrice,
            @Schema(description = "평가액 (KRW)") BigDecimal evalAmountKrw
    ) {
        static CryptoHoldingResponse from(CryptoPortfolio.Holding h) {
            return new CryptoHoldingResponse(h.ticker().name(), h.quantity(), h.avgBuyPrice(), h.currentPrice(), h.evalAmountKrw());
        }
    }
}
```

`CryptoPortfolioController.java`:

```java
package com.kista.tradingcore.stats.adapter.in.web;

import com.kista.tradingcore.stats.adapter.in.web.dto.CryptoPortfolioResponse;
import com.kista.tradingcore.stats.application.usecase.CryptoPortfolioUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "통계", description = "계좌별 손익·체결·잔고·증거금·현재가 조회 (KIS/Toss 증권사 자동 분기)")
@RestController
@RequiredArgsConstructor
public class CryptoPortfolioController {

    private final CryptoPortfolioUseCase cryptoPortfolio; // 코인 계좌 포트폴리오 조회

    @Operation(summary = "코인 계좌 포트폴리오 조회", description = "업비트 실잔고 기준 원화 예수금과 코인별 보유·평단·현재가·평가액(KRW). 주식 계좌는 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "코인 계좌가 아님"),
            @ApiResponse(responseCode = "403", description = "내 계좌가 아님"),
            @ApiResponse(responseCode = "404", description = "계좌를 찾을 수 없음"),
            @ApiResponse(responseCode = "422", description = "거래소 API 키 오류·IP 미허용·권한 부족"),
            @ApiResponse(responseCode = "503", description = "거래소 API 호출 실패")
    })
    @GetMapping("/api/accounts/{accountId}/crypto-portfolio")
    public CryptoPortfolioResponse getCryptoPortfolio(
            @Parameter(description = "계좌 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @PathVariable UUID accountId,
            @AuthenticationPrincipal UUID userId) {
        return CryptoPortfolioResponse.from(cryptoPortfolio.getCryptoPortfolio(accountId, userId));
    }
}
```

- [ ] **Step 3: 통과 확인**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.*' --tests 'com.kista.tradingcore.architecture.*' --tests 'com.kista.tradingcore.TradingApplicationContextLoadTest' :test --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL` (Modulith verify가 stats → broker "application"/"domain"/"port" 참조를 허용하는지 함께 확인).

- [ ] **Step 4: 문서·flows 갱신**

`stats.md` `adapter/in/web/` 줄에 `/CryptoPortfolioController(GET /api/accounts/{id}/crypto-portfolio — 코인 계좌 전용, 주식 계좌 400)`, `application/service/` 줄에 `/CryptoPortfolioService(CryptoExchanges.of(broker) 잔고 → 시세 → KRW 평가, 보유 없으면 시세 생략)`, `domain/model/` 줄에 `+ CryptoPortfolio(+Holding)`, usecase 줄에 `CryptoPortfolioUseCase`를 추가한다.
`flows.yml` `result.account-detail` step desc 끝에 `업비트 계좌는 /portfolio 대신 코인 포트폴리오(GET /api/accounts/{id}/crypto-portfolio — 원화 예수금·코인별 보유·평단·현재가·평가액)를 부른다.`를 덧붙이고 `code:`에 `CryptoPortfolioController#getCryptoPortfolio, CryptoPortfolioService#getCryptoPortfolio`를 추가한다.

Run: `./gradlew :test --tests 'com.kista.api.architecture.ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"` → `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src docs/agents/modules/tradingcore/stats.md src/test/resources/architecture/flows.yml
git commit -m "$(cat <<'EOF'
feat(stats): 업비트 계좌의 원화 기준 코인 포트폴리오 조회 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
```

---

### Task 12: B 계약 검증 + README 드리프트 + 두 번째 해시 확정

**Files:**
- Modify: `README.md:8,14`
- 그 외 코드 변경 없음이 원칙(검증 결함만 수정)

**Interfaces:**
- Consumes: Task 6~11 전체
- Produces: **B 계약 커밋 해시** (kista-ui-22 재전달용)

- [ ] **Step 1: README 드리프트 수정**

`README.md` 8행 `한국투자증권(KIS)·토스증권 API 기반 해외주식 자동 분할매매 SaaS의 백엔드.`를 `한국투자증권(KIS)·토스증권 API 기반 해외주식과 업비트 API 기반 코인(BTC/ETH, DCA) 자동 분할매매 SaaS의 백엔드.`로, 14행 `- **증권사**: KIS · 토스증권 · MOCK(모의 계좌)`를 `- **증권사·거래소**: KIS · 토스증권 · MOCK(모의 계좌) · 업비트(KRW 마켓 코인, DCA 전용 — 실행 스케줄러는 후속)`로 바꾼다. `docs/agents/` 표(67행 근처)에 업비트 전용 문서는 추가하지 않는다(`broker.md`가 담는다).

- [ ] **Step 2: 전체 스위트 1회**

Run: `docker compose up -d postgres redis && ./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: 로컬 기동 + 병합 계약 검증**

Task 5 Step 2와 같은 방식으로 두 프로세스를 띄운다(`application-local.yml`은 Task 5에서 복사돼 있음).

```bash
SPEC=/private/tmp/claude-501/-Users-phs-workspace-kista-kista-api/97f81ced-78a1-4a7e-9634-9c9364288a41/scratchpad/spec-b
mkdir -p $SPEC && cd $SPEC && node /Users/phs/workspace/kista/kista-ui/scripts/fetch-spec.mjs && echo MERGE_OK
jq -r '.components.schemas.ErrorCode.enum | join(",")' openapi.json
jq -r '.paths | keys[] | select(test("credentials|crypto-portfolio"))' openapi.json
jq -r '.components.schemas.AccountResponse.properties | keys | join(",")' openapi.json
jq -r '.components.schemas.AccountRequest.properties.broker.enum | join(",")' openapi.json
jq -r '.components.schemas.CryptoPortfolioResponse.properties | keys | join(",")' openapi.json
```

Expected: `MERGE_OK`, ErrorCode에 `BROKER_IP_NOT_ALLOWED,BROKER_PERMISSION_DENIED` 포함, 경로 `/api/accounts/{accountId}/crypto-portfolio`·`/api/accounts/{id}/credentials`, AccountResponse에 `apiKeyExpiresAt,currency`, AccountRequest broker enum에 `UPBIT`, CryptoPortfolioResponse에 `holdings,krwCash,totalAssetKrw`.
Run: `curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)" -H 'Content-Type: application/json' -d '{"broker":"UPBIT","appKey":"x","appSecret":"y"}' localhost:8081/api/accounts/connection-tests`
Expected: `400`(UPBIT 기본 비활성 — 키가 업비트로 나가지 않음). 실키 검증은 하지 않는다(미검증 사유: 실키 필요).

두 java 프로세스를 종료하고 `git status --short`가 README 외에 비어 있는지 확인한다.

- [ ] **Step 4: 커밋 + B 해시 확정**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
docs: README에 업비트 코인 DCA 지원 범위를 반영한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DUinWS2yR2qt3biEqZLjrP
EOF
)"
git log -1 --format='%H %s'
```

**B 완료 = 이 커밋 해시를 kista-ui-22에 다시 전달**(전달 메시지: "kista-api <hash> — 신규 PUT /api/accounts/{id}/credentials, GET /api/accounts/{id}/crypto-portfolio, ErrorCode BROKER_IP_NOT_ALLOWED·BROKER_PERMISSION_DENIED(둘 다 422), AccountResponse currency·apiKeyExpiresAt(업비트 accountNo/accountNoMasked null), AccountRequest broker에 UPBIT. 연결 테스트·등록은 UPBIT를 관리자 설정에서 켜야 동작").

---

## 묶음 C에 넘기는 항목 (이 계획 범위 밖 — 기록만)

- 코인 DCA 실행은 `CryptoExchanges.of(account.broker())`로 포트를 받는다. 주식 배치(`BatchContextFactory`)는 이미 코인 전략을 제외한다(Task 4).
- 시장가 매수 완료 주문의 업비트 `state`가 `done`이 아니라 `cancel`일 수 있다(문서 미기재) — 체결 판정은 `executedVolume > 0`으로.
- `getCompletedDailyCandles`는 최대 199봉(진행 중 봉 1개 여유) — 250봉 SMA가 필요하면 `to` 파라미터 페이징을 추가해야 한다.
- `DcaSummaryAssembler`(다음 실행일)는 미국 거래일 캘린더 기준이라 코인 전략 등록 응답의 `nextExecution`이 부정확하다 — C에서 24/7 규칙으로 분기.
- `DailyTransaction` 대시보드 집계·관리자 재주문은 코인 주문을 제외/거부한다(Task 3) — 코인 체결 집계가 필요하면 C 이후 별도 작업.
- 코인 체결 알림(`TradeEventMessage.quantity`는 int)은 C에서 BigDecimal 경로를 별도로 만든다.
- 키 만료 D-30/D-7/당일 알림은 `accounts.api_key_expires_at`(Task 9)을 읽는다.

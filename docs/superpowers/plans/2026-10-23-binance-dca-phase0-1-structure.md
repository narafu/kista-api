# 코인 거래소 일반화 (바이낸스 Phase 0+1) Implementation Plan

> 상태: 완료 (2026-10-23, 커밋 87af22fe)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "코인 = 업비트 = KRW" 리터럴 전제를 `shared.kernel` capability(시장·통화·거래소)와 거래소 포트 계약으로 바꾼다(Phase 0). 이어서 BTC·ETH가 여러 시장에서 거래되도록 종목↔시장 관계를 바꾸고, 시장은 계좌에서 정하게 한다(Phase 1). 업비트·KIS·Toss·메리츠·MOCK의 관찰 가능한 동작은 바뀌지 않고, 기존 테스트는 전부 통과해야 한다.
**Architecture:** 어휘는 `shared.kernel`이 가진다. `Market`(`isCrypto`/`quoteCurrency`/`minOrderAmount`/`amountScale`), `Currency`(`USDT`, `amountScale`/`round`/`format`), `Broker`(생성자 capability 플래그), `StrategyTicker`(`markets`/`tradableOn`/`isCrypto`), 신규 `CryptoTradingRules`가 여기에 든다. 거래소별 실행 규칙은 `CryptoExchangePort.getTradingRules`가 공급하고, 실행기가 `DcaRuleInputs`에 실어 matching 커널로 넘긴다. 김치 프리미엄용 업비트 KRW 시세(KRW-USDT·코인 KRW 현재가·완성 일봉)는 broker 거래 포트에서 빼서, stats 소유 공개 시세 포트 `UpbitMarketDataPort`와 어댑터 `stats.adapter.out.upbit.UpbitMarketDataAdapter`(키 불필요, `BinanceMarketDataAdapter`와 같은 패턴)로 옮긴다. 이로써 김프 지표는 broker `CryptoExchanges`·`Broker.UPBIT`에 의존하지 않는다. 일반화의 기준은 "업비트 동작 보존 + 바이낸스가 1급"이다. 중립 이름·계약은 바이낸스 쪽(USDT 소수 금액, 받은 자산 수수료, 순수량)을 자연스럽게 표현한다. 업비트 고유 개념(KRW 정수, 견적 통화 추가 수수료)은 업비트 구현체와 `Market.CRYPTO_KRW` capability 안에 둔다. 업비트 거래 제거가 `broker.adapter.out.upbit` 패키지·`Broker.UPBIT` 상수·정책 기본값 정리로 끝나게 한다. `Broker.BINANCE`와 바이낸스 어댑터는 만들지 않는다. `Market.CRYPTO_USDT`·`Currency.USDT`만 추가해 일반화 경로를 테스트로 검증한다.
**Tech Stack:** Java 25, Spring Boot 4, Spring Modulith, JUnit 5, Mockito, AssertJ
**Spec:** docs/superpowers/specs/2026-10-23-binance-spot-dca-design.md (이 계획 범위: 설계 1·2·3·4·9절 + `TickerMeta.markets` expand)

## Global Constraints
- enum 상수명 변경 금지(DB·API 계약): `Market.US_STOCK`/`CRYPTO_KRW`, `Currency.KRW`/`USD`/`JPY`, `Broker.*`, `StrategyTicker.*`. 신규 상수는 `Market.CRYPTO_USDT`, `Currency.USDT`, `CryptoTradingRules.FeeSide.QUOTE_SURCHARGE`/`RECEIVED_ASSET`뿐이다. 신규 상수는 기존 상수 뒤에 선언한다(순서 의존 `List.getFirst()`·EnumSet 보호).
- `Broker.BINANCE`·바이낸스 어댑터·`TradingPolicySettings` 변경 금지 — Phase 2 몫이다.
- matching(`com.kista.tradingcore.matching`)은 `com.kista.shared.kernel` 외 `com.kista` 의존 금지. broker는 matching을 import하지 않는다(현재 0건 유지).
- API 응답 계약: `CryptoPortfolioResponse` 필드명·필드 집합은 유지하고(`krwCash`·`evalAmountKrw`·`totalAssetKrw`), 신규 필드 추가는 다음 계획 몫이다. 이 계획의 계약 변경은 `TickerMeta.markets` 추가(expand) 하나다.
- EPR: `CryptoOrderFilledEvent`의 클래스·패키지는 옮기지 않는다. 필드 개명은 `@JsonAlias`로 구 JSON을 수용하고, 신규 필드는 null 폴백을 둔다.
- 주석은 `//` 인라인만 쓴다. Javadoc·블록 주석은 금지다. 신규 필드에는 `// 역할`을, 비즈니스 블록 앞에는 단계 설명 한 줄을 단다.
- 테스트 명령은 서브프로젝트 접두사 필수: `./gradlew :shared:test --tests ...` / `./gradlew :trading-core:test --tests ...` / `./gradlew :test --tests ...`. 전체 스위트(`./gradlew test`)는 Task 8에서 1회만 돌린다.
- 빌드 로그는 필터해서 받는다: `2>&1 | grep -E "FAILED|BUILD|error:|Tests? .*(failed|completed)"`.
- 커밋: 한글 Conventional Commit(`refactor(scope):`/`feat(scope):`/`test:`/`docs:`), author `narafu <narafu@kakao.com>`(커밋 전 `git config user.name`/`user.email` 확인). push 금지. `git add`는 경로를 명시한다(동시 세션 오염 방지 — 커밋 전 `git diff --cached --stat`로 확인).
- 순수 치환 태스크(Task 2·3·6·7 일부)의 회귀 게이트는 기존 테스트다. 기존 테스트의 단언 값(문구·금액·scale)은 바꾸지 않는다. 시그니처 변경에 따른 기계적 수정(생성자 인자·접근자명·mock 매처)만 허용한다.
- 신규 own-type 쌍을 만들지 않는다. 거래 규칙 타입은 sharedkernel 단일 타입으로 둔다(Task 4 게이트 판정 참고).
- 운영 방침(사용자, 2026-10-23): 크립토는 바이낸스 중심이고 업비트는 옵션이다(향후 거래 제거 가능, 공개 시세는 김프용으로 유지). 공통 경로(커널·포트·서비스)에 업비트 전용 이름·상수·분기를 새로 두지 않는다. 업비트 값은 `UpbitExchangeAdapter`(거래)·`UpbitMarketDataAdapter`(공개 시세)·`Market.CRYPTO_KRW`·`Broker.UPBIT`·`TradingPolicySettings` 기본값에만 둔다.

## Review Focus
1. **USDT 금액이 원 단위로 잘리지 않는다(10.5 → 10 금지)**: 커널 BUY 경로의 금액 scale은 `Market.amountScale()`에서 온다. 잠금 테스트는 Task 4 `DcaRuleDecisionTest.크립토_USDT_BUY는_소수2자리_금액을_유지한다`(notional `10.50`)와 Task 1 `CurrencyTest.roundUsesCurrencyScale`(`10.4000192` → `10.40`)다.
2. **CRYPTO_USDT가 USD 통계 합계에 섞이지 않는다**: 집계 필터는 제외식(`!= CRYPTO_KRW`)이 아니라 포함식(`getCurrency() == Currency.USD`)으로 바꾼다. USDT 브로커가 없어 서비스 테스트로는 USDT 계좌를 만들 수 없다. 그래서 Task 1 `MarketVocabularyTest.onlyUsStockQuotesUsd`(모든 `Market` 순회 — USD 견적은 US_STOCK뿐)와 Task 3 필터 형태 검토로 잠근다. 리뷰어는 Task 3의 3곳이 모두 포함식인지 확인한다.
3. **여러 시장 종목이 US_STOCK 계좌에서 거부된다**: Task 7 `StrategyServiceTest.register_btcOnKisAccount_rejectedAsMarketMismatch`(KIS 계좌 + BTC → `"계좌와 종목의 시장이 다릅니다"`)와 Task 1/7 `MarketVocabularyTest.cryptoTickersTradeOnBothCryptoMarkets`(`SOXL.tradableOn(CRYPTO_USDT)==false`)가 잠근다. 등록 검증 순서도 본다. `validateMarket`이 `BootstrapPosition.validate`보다 먼저 와야 한다(Task 7).
4. **시장은 계좌에서 온다(하드코딩·종목 시장 금지)**: `CryptoDcaRoundExecutor`의 `Market.CRYPTO_KRW` 하드코딩을 없애는 일은 USDT 브로커가 없어 테스트로 구분할 수 없다. Task 8의 grep 게이트(`trading-core/src/main`의 `Market.CRYPTO_KRW`·`Broker.UPBIT` 잔존 목록이 허용 목록과 일치)로 확인하고, Phase 2 바이낸스 실행기 테스트가 최종으로 잠근다. `StrategyDetail`의 정수 보유 null 처리는 시장이 아니라 자산군 판정(`ticker.isCrypto()`)이다. 설계 2절과 다른 점이라 의도된 편차로 명시한다(아래 "설계와의 차이").
5. **업비트 KRW 반올림·문구 불변**: 아래 기존 단언이 수정 없이 통과해야 한다.
   - `CryptoDcaRoundExecutorTest`: 매수 금액 `49975`·scale 0, `"주문 금액 4999 < 최소 5000"`
   - `TradingUserNotificationAdapterWordingTest`: `"평균가 150,000,000원, 금액 15,000원"`
   - `CryptoOrderSynchronizerTest`: 체결 금액 `15000`(접근자명만 `amount()`)

   EPR 구 row 호환도 본다. 잠금 테스트는 Task 5 `CryptoOrderFilledEventJsonTest.oldRowWithAmountKrwDeserializesAsKrw`다(`amountKrw` 키 + currency 없음 → `amount=15000`, `currency=KRW`).
6. **업비트 거래 제거가 국소적이다**: 김프 지표(`CryptoMarketIndicatorService`)는 `CryptoExchanges`·`CryptoExchangePort`·`Broker`를 import하지 않고 stats `UpbitMarketDataPort`만 쓴다. stats 도메인(`CryptoPremiums`)은 broker `CryptoCandle` 대신 stats `DatedValue`를 받는다. 잠금은 Task 6 `CryptoMarketIndicatorServiceTest`(생성자가 `UpbitMarketDataPort`를 받음 — `CryptoExchanges` 없이 컨텍스트 구성)와 Task 8 Step 1 "업비트 잔존 지점" grep(허용 목록 밖 0건)이다.

### 설계와의 차이 (의도된 편차)
- 설계 2절은 `StrategyDetail.java:22`의 compact 생성자에 시장을 인자로 넘기라고 한다. 이 계획은 레코드 시그니처를 유지하고 `strategy.ticker().isCrypto()`(자산군)로 판정한다. 판정이 "소수 원장이라 정수 보유를 싣지 않는다" 하나뿐이고, 코인 종목은 코인 시장에서만 등록되므로(등록 검증) 결과가 같다. 생성자 호출부 12곳(main 4·test 8)의 수정도 피한다. 응답에 통화가 필요해지면 Phase 3 계획이 `StrategyDetail`에 시장을 추가한다.
- 설계 4절 "일봉 신선도를 포트 계약으로"는 포트 default 메서드가 아니라 벤더 중립 타입의 정적 규칙 `CryptoCandle.latestCompletedKstDate(LocalDateTime)`로 구현한다. Mockito mock은 인터페이스 default 메서드를 null로 덮어쓰고(testing.md), 업비트·바이낸스 경계가 같은 KST 09:00이기 때문이다. 경계가 다른 거래소가 생기면 그때 포트 메서드로 올린다.

---

## 파일 구조

| 파일 | 변경 | 책임 |
|---|---|---|
| `shared/src/main/java/com/kista/shared/kernel/Market.java` | Modify | `CRYPTO_USDT` + `quoteCurrency`/`isCrypto`/`minOrderAmount`/`amountScale` |
| `shared/src/main/java/com/kista/shared/kernel/Currency.java` | Modify | `USDT` + `amountScale`/`round`/`format` — 금액 반올림·표기 SSOT |
| `shared/src/main/java/com/kista/shared/kernel/Broker.java` | Modify | currency 필드 제거(`getCurrency()`가 `market.quoteCurrency()` 위임) + `keyOnlyCredentials`/`apiKeyExpiring` 생성자 플래그 + `isCryptoExchange()` |
| `shared/src/main/java/com/kista/shared/kernel/StrategyTicker.java` | Modify | `market` → `List<Market> markets` + `tradableOn`/`isCrypto` (Task 7) |
| `shared/src/main/java/com/kista/shared/kernel/CryptoTradingRules.java` | Create | 거래소 실행 규칙(수수료율·차감 쪽·최소 명목가·수량 step) — 포트 반환 겸 커널 입력 |
| `shared/src/test/java/com/kista/shared/kernel/MarketVocabularyTest.java` | Modify | capability 잠금 |
| `shared/src/test/java/com/kista/shared/kernel/CurrencyTest.java` | Create | scale·round·format |
| `shared/src/test/java/com/kista/shared/kernel/CryptoTradingRulesTest.java` | Create | 지출·가용 역산·수량 scale |
| `trading-core/.../account/domain/model/Account.java` | Modify | `dedupeKeyOf` → `broker.isKeyOnlyCredentials()` |
| `trading-core/.../account/application/service/AccountService.java` | Modify | 57·120행 키 전용 분기 → capability |
| `trading-core/.../account/adapter/in/schedule/ApiKeyExpiryScheduler.java` | Modify | 40행 → `isApiKeyExpiring()` |
| `trading-core/.../trading/application/service/OrderCancelService.java` | Modify | 58·94·152행 → `isCryptoExchange()` |
| `trading-core/.../trading/application/service/CryptoDcaRunner.java` | Modify | 63행 → `isCryptoExchange()` |
| `trading-core/.../trading/adapter/in/schedule/BatchContextFactory.java` | Modify | 52행 → `isCryptoExchange()` |
| `trading-core/.../trading/application/service/DcaSummaryAssembler.java` | Modify | 37·52행 → `market.isCrypto()` |
| `trading-core/.../trading/application/service/StrategyCreationService.java` | Modify | 70행(Task 2), 47-51·83·137·142행(Task 7) |
| `trading-core/.../stats/application/service/TradingStatsService.java` | Modify | 179행 USD 포함식 |
| `trading-core/.../stats/application/service/InvestmentPointsQueryService.java` | Modify | 60행 USD 포함식 |
| `trading-core/.../trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java` | Modify | 30-32행 `NON_USD_BROKERS` 포함식 반전 |
| `trading-core/.../matching/domain/model/DcaRule.java` | Modify | `CRYPTO_MIN_AMOUNT`·`CRYPTO_FEE_FACTOR` 삭제, `requireCompatible`이 시장 capability 사용 |
| `trading-core/.../matching/domain/strategy/DcaCycleOrderStrategy.java` | Modify | `DcaRuleInputs.tradingRules` + scale·최소금액·수수료를 규칙·시장에서 |
| `trading-core/.../matching/domain/model/BootstrapPosition.java` | Modify | `== CRYPTO_KRW` → `isCrypto()` |
| `trading-core/.../broker/application/port/output/CryptoExchangePort.java` | Modify | `getTradingRules` 추가(Task 4), USDT 메서드 제거·중립 이름·조회 ticker 인자(Task 6) |
| `trading-core/.../stats/application/port/output/UpbitMarketDataPort.java` | Create | 김프용 업비트 공개 시세 포트(KRW-USDT·코인 KRW 현재가·완성 일봉) — stats 소유 |
| `trading-core/.../stats/adapter/out/upbit/{UpbitMarketDataAdapter,UpbitMarketDataConfig}.java` | Create | 키 불필요 공개 시세 어댑터 + `statsUpbitRestClient` 빈(`BinanceMarketDataAdapter`/`BinanceConfig` 패턴) |
| `trading-core/.../stats/domain/model/CryptoPremiums.java` | Modify | 입력을 broker `CryptoCandle` → stats `DatedValue`로 |
| `trading-core/.../broker/adapter/out/upbit/UpbitMarketRegistry.java` | Modify | `USDT_MARKET` 삭제(stats로 이동) |
| `trading-core/.../broker/domain/model/CryptoBalance.java` | Modify | `quoteAvailable`/`quoteLocked` |
| `trading-core/.../broker/domain/model/CryptoOrderResult.java` | Modify | `netExecutedVolume`·`feeAsset` 추가 |
| `trading-core/.../broker/domain/model/CryptoCandle.java` | Modify | `latestCompletedKstDate(LocalDateTime)` 정적 규칙 |
| `trading-core/.../broker/adapter/out/upbit/UpbitExchangeAdapter.java` | Modify | `TRADING_RULES` 상수, USDT 메서드 삭제, 신규 필드 매핑 |
| `trading-core/.../trading/application/service/CryptoDcaRoundExecutor.java` | Modify | 규칙 메모·계좌 시장·지갑 차감(Task 4), 신선도·이름(Task 6) |
| `trading-core/.../trading/application/service/CryptoOrderSynchronizer.java` | Modify | 통화 scale 금액(Task 5), ticker 조회·순수량 원장(Task 6) |
| `trading-core/.../trading/application/event/CryptoOrderFilledEvent.java` | Modify | `amountKrw` → `amount`(+`@JsonAlias`), `currency` 추가(null→KRW) |
| `trading-core/.../notify/adapter/out/gateway/TradingUserNotificationAdapter.java` | Modify | `krw()` → `Currency.format` |
| `trading-core/.../notify/adapter/out/gateway/CryptoTradeNotifier.java` | Modify | `amountKrw()` → `amount()` |
| `trading-core/.../stats/domain/model/CryptoPortfolio.java` | Modify | 통화 중립 필드 + `currency` |
| `trading-core/.../stats/adapter/in/web/dto/CryptoPortfolioResponse.java` | Modify | 매핑만(JSON 필드 불변) |
| `trading-core/.../stats/application/service/CryptoPortfolioService.java` | Modify | 59행(Task 2), 통화 scale(Task 5), 49행(Task 7) |
| `trading-core/.../stats/application/service/CryptoMarketIndicatorService.java` | Modify | `UpbitMarketDataPort` 주입·broker 의존 제거(Task 6), `TICKERS` 필터(Task 7) |
| `trading-core/.../trading/domain/model/StrategyDetail.java` | Modify | compact 판정 `ticker().isCrypto()` |
| `trading-core/.../trading/application/service/{StrategyService,TradingPreviewService,ManualTradeCorrectionService,DcaPlanInputCollector,ReorderService}.java` | Modify | 종목 시장 → 계좌 시장/자산군 (Task 7) |
| `trading-core/.../stats/application/service/AccountStatisticsService.java` | Modify | 74행 → `!ticker().isCrypto()` |
| `src/main/java/com/kista/api/web/dto/TickerMeta.java` | Modify | `markets` 추가, `market` 유지(첫 시장) |
| `src/test/java/com/kista/api/web/MetaControllerTest.java` | Modify | `markets` 단언 추가 |
| 문서 | Modify | `docs/agents/modules/shared/kernel.md`, `modules/tradingcore/{account,matching,trading,broker,stats}.md`, `src/test/resources/architecture/flows.yml` (각 소유 태스크에서) |

(`trading-core/...` = `trading-core/src/main/java/com/kista/tradingcore`, 테스트는 같은 패키지의 `trading-core/src/test/java/...`)

---

### Task 1: shared.kernel 시장·통화·거래소 capability

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/Market.java`(전체), `Currency.java`(전체), `Broker.java`(9-25행)
- Create: `shared/src/test/java/com/kista/shared/kernel/CurrencyTest.java`
- Modify(Test): `shared/src/test/java/com/kista/shared/kernel/MarketVocabularyTest.java`
- Modify(Doc): `docs/agents/modules/shared/kernel.md` 4행(공유 enum 줄)

**Interfaces:**
- Consumes: 없음
- Produces:
  - `enum Market { US_STOCK, CRYPTO_KRW, CRYPTO_USDT }` — `Currency quoteCurrency()`, `boolean isCrypto()`, `BigDecimal minOrderAmount()`, `int amountScale()`
  - `enum Currency { KRW, USD, JPY, USDT }` — `int amountScale()`, `BigDecimal round(BigDecimal amount)`, `String format(BigDecimal amount)`, 기존 `static Optional<Currency> tryParse(String)`
  - `Broker` — `Currency getCurrency()`(위임), `boolean isKeyOnlyCredentials()`, `boolean isApiKeyExpiring()`, `boolean isCryptoExchange()`, 기존 `getLabel()`/`getShortLabel()`/`getMarket()`/`supportsOpenAuctionOrders()`

- [ ] **Step 1: 실패 테스트 작성**

`CurrencyTest`:
```java
@Test void amountScaleIsSingleSource() {
    assertThat(Currency.KRW.amountScale()).isZero();
    assertThat(Currency.JPY.amountScale()).isZero();
    assertThat(Currency.USD.amountScale()).isEqualTo(2);
    assertThat(Currency.USDT.amountScale()).isEqualTo(2);
}
@Test void roundUsesCurrencyScale() {
    assertThat(Currency.KRW.round(new BigDecimal("14999.5"))).isEqualTo(new BigDecimal("15000"));
    assertThat(Currency.USDT.round(new BigDecimal("10.4000192"))).isEqualTo(new BigDecimal("10.40")); // isEqualTo는 scale까지 비교
}
@Test void formatKeepsUpbitWording() {
    assertThat(Currency.KRW.format(new BigDecimal("150000000"))).isEqualTo("150,000,000원");
    assertThat(Currency.KRW.format(new BigDecimal("5000"))).isEqualTo("5,000원");
    assertThat(Currency.USDT.format(new BigDecimal("10.5"))).isEqualTo("10.50 USDT");
}
@Test void tryParseKnowsUsdt() { assertThat(Currency.tryParse("USDT")).contains(Currency.USDT); }
```
`MarketVocabularyTest`에 추가:
```java
@Test void marketCapabilities() {
    assertThat(Market.US_STOCK.quoteCurrency()).isEqualTo(Currency.USD);
    assertThat(Market.CRYPTO_KRW.quoteCurrency()).isEqualTo(Currency.KRW);
    assertThat(Market.CRYPTO_USDT.quoteCurrency()).isEqualTo(Currency.USDT);
    assertThat(Market.US_STOCK.isCrypto()).isFalse();
    assertThat(Market.CRYPTO_KRW.isCrypto()).isTrue();
    assertThat(Market.CRYPTO_USDT.isCrypto()).isTrue();
    assertThat(Market.CRYPTO_KRW.minOrderAmount()).isEqualTo(new BigDecimal("5000"));
    assertThat(Market.CRYPTO_USDT.minOrderAmount()).isEqualTo(new BigDecimal("5"));
    assertThat(Market.US_STOCK.minOrderAmount()).isEqualTo(BigDecimal.ZERO);
    assertThat(Market.CRYPTO_KRW.amountScale()).isZero();
    assertThat(Market.CRYPTO_USDT.amountScale()).isEqualTo(2);
}
@Test void onlyUsStockQuotesUsd() { // USD 통계 합산(Task 3)의 포함식 전제 — 신규 시장이 USD 합계에 조용히 섞이지 않게
    assertThat(Arrays.stream(Market.values()).filter(m -> m.quoteCurrency() == Currency.USD)).containsExactly(Market.US_STOCK);
}
@Test void brokerCapabilityFlags() {
    assertThat(Broker.UPBIT.isKeyOnlyCredentials()).isTrue();
    assertThat(Broker.MERITZ.isKeyOnlyCredentials()).isTrue();
    for (Broker b : new Broker[]{Broker.KIS, Broker.TOSS, Broker.MOCK}) assertThat(b.isKeyOnlyCredentials()).isFalse();
    assertThat(Arrays.stream(Broker.values()).filter(Broker::isApiKeyExpiring)).containsExactly(Broker.UPBIT);
    assertThat(Arrays.stream(Broker.values()).filter(Broker::isCryptoExchange)).containsExactly(Broker.UPBIT);
    for (Broker b : Broker.values()) assertThat(b.getCurrency()).isEqualTo(b.getMarket().quoteCurrency());
}
```
기존 `brokersCarryMarketAndCurrency`·`currencyLivesInKernel`은 그대로 둔다(회귀 게이트).

- [ ] **Step 2: 실패 확인**
`./gradlew :shared:test --tests 'com.kista.shared.kernel.*' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패(`CRYPTO_USDT`·`USDT`·`amountScale`·`isKeyOnlyCredentials` 심볼 없음).

- [ ] **Step 3: 구현**
- `Market`: `US_STOCK(Currency.USD, false, BigDecimal.ZERO)`, `CRYPTO_KRW(Currency.KRW, true, new BigDecimal("5000"))`, `CRYPTO_USDT(Currency.USDT, true, new BigDecimal("5"))`. 바이낸스 BTCUSDT·ETHUSDT `NOTIONAL` 5 USDT는 실측값이다. 최소금액은 scale 0 리터럴로 둔다(`toPlainString()` 문구 `"5000"`/`"5"` 보존). `amountScale()`은 `quoteCurrency.amountScale()`에 위임하고, 별도 표는 두지 않는다. `US_STOCK` 최소금액 ZERO = "금액 하한 없음, 1주 단위 판정". 주석에 "주식 DCA 금액 scale은 강제하지 않는다(기존 동작)"를 남긴다.
- `Currency`: `KRW(0), USD(2), JPY(0), USDT(2)`.
  - `round(v)` = `v.setScale(amountScale, RoundingMode.HALF_UP)`
  - `format(v)`: 숫자는 `("%,." + amountScale + "f").formatted(round(v))`. 단위는 `this == KRW ? 숫자 + "원" : 숫자 + " " + name()`이다.
- `Broker`: `Currency currency` 필드를 제거하고 `getCurrency()`가 `market.quoteCurrency()`를 돌려준다(명시 메서드 — `BrokerMeta`·`AccountResponse` 호출부 불변). 생성자는 `(String label, String shortLabel, Market market, boolean keyOnlyCredentials, boolean apiKeyExpiring)`이다. 신규 상수가 플래그를 빠뜨리면 컴파일 단계에서 걸린다. 값은 다음과 같다.
  - `TOSS(…, US_STOCK, false, false)`
  - `KIS(…, US_STOCK, false, false)`
  - `MOCK(…, US_STOCK, false, false)`
  - `UPBIT(…, CRYPTO_KRW, true, true)`
  - `MERITZ(…, US_STOCK, true, false)`

  Lombok `@Getter`가 `isKeyOnlyCredentials()`/`isApiKeyExpiring()`를 생성한다. `isCryptoExchange()`는 `market.isCrypto()`다.

- [ ] **Step 4: 통과 확인** — 같은 명령 → BUILD SUCCESSFUL. 이어서 `./gradlew compileJava :trading-core:compileJava 2>&1 | grep -E "error:|BUILD"` → BUILD SUCCESSFUL(`getCurrency()` 호출부 불변 확인).

- [ ] **Step 5: 문서** — `kernel.md` 공유 enum 줄의 `Broker`·`Market`·`Currency` 설명을 바꾼다.
  - `Broker(… — market 보유, currency는 market.quoteCurrency() 파생, keyOnlyCredentials·apiKeyExpiring 생성자 플래그 + isCryptoExchange())`
  - `Market(US_STOCK/CRYPTO_KRW/CRYPTO_USDT — quoteCurrency·isCrypto·minOrderAmount(등록 시점 정적 최소 주문 금액)·amountScale)`
  - `Currency(KRW/USD/JPY/USDT — amountScale·round·format: 금액 반올림·표기 SSOT)`

- [ ] **Step 6: 커밋**
```
git add shared/src/main/java/com/kista/shared/kernel/{Market,Currency,Broker}.java shared/src/test/java/com/kista/shared/kernel/{CurrencyTest,MarketVocabularyTest}.java docs/agents/modules/shared/kernel.md
git commit -m "feat(kernel): 시장·통화·거래소 capability와 USDT 어휘를 추가한다"
```

---

### Task 2: 거래소 리터럴 분기를 capability로 치환

**Files:**
- Modify: `account/domain/model/Account.java:28-30`, `account/application/service/AccountService.java:49,57,118-122`, `account/adapter/in/schedule/ApiKeyExpiryScheduler.java:40`
- Modify: `trading/application/service/OrderCancelService.java:55-58,93-94,152`, `CryptoDcaRunner.java:63`, `trading/adapter/in/schedule/BatchContextFactory.java:52`, `StrategyCreationService.java:69-70`, `DcaSummaryAssembler.java:37,52`
- Modify: `stats/application/service/CryptoPortfolioService.java:59`
- Test(회귀 게이트, 수정 없음): `AccountTest`, `AccountServiceTest`, `ApiKeyExpirySchedulerTest`, `OrderCancelServiceTest`, `CryptoDcaRunnerTest`, `StrategyServiceTest`, `DcaSummaryAssemblerTest`, `CryptoPortfolioServiceTest`, `BatchContextFactory` 관련 테스트
- Modify(Doc): `docs/agents/modules/tradingcore/account.md` 4행(`dedupeKey()` 설명)·16행(`broker == UPBIT` 서술)

**Interfaces:**
- Consumes: Task 1 `Broker.isKeyOnlyCredentials()`/`isApiKeyExpiring()`/`isCryptoExchange()`, `Market.isCrypto()`
- Produces: 없음(시그니처 불변)

- [ ] **Step 1: 테스트** — 순수 치환이라 신규 실패 테스트는 없다. 잠금 테스트 1개만 추가한다. `ApiKeyExpirySchedulerTest.meritzAccountWithExpiryIsIgnored`: MERITZ 계좌에 `apiKeyExpiresAt` = 오늘+7일 → `verifyNoInteractions(eventPublisher)`. 치환 전후 모두 통과해야 한다(플래그가 브로커 비교와 같은 집합인지 잠근다).
- [ ] **Step 2: 치환**
  - `Account.dedupeKeyOf`: `broker.isKeyOnlyCredentials() ? appKey : accountNo`
  - `AccountService:57`: `else if (broker.isKeyOnlyCredentials())`. 주석은 "키 전용 브로커(계좌번호 없음)"로 바꾼다.
  - `AccountService:120`: `account.broker().isKeyOnlyCredentials() && …`
  - `ApiKeyExpiryScheduler:40`: `a.broker().isApiKeyExpiring() && a.apiKeyExpiresAt() != null`
  - `OrderCancelService`: 58행 `if (!account.broker().isCryptoExchange()) return;`, 94행 `if (account.broker().isCryptoExchange())`, 152행 `… && account.broker().isCryptoExchange()`. 주석의 "코인(UPBIT)"은 "코인 거래소"로 바꾼다.
  - `CryptoDcaRunner:63`: `.filter(a -> a.broker().isCryptoExchange())`
  - `BatchContextFactory:52`: `if (owner != null && owner.broker().isCryptoExchange()) continue;`
  - `StrategyCreationService:70`: `account.broker().isCryptoExchange() && cmd.type() != StrategyType.DCA`
  - `DcaSummaryAssembler:37,52`: `if (market.isCrypto())`
  - `CryptoPortfolioService:59`: `if (!account.broker().isCryptoExchange())`
  - 각 파일에서 쓰지 않게 된 `Broker`/`Market` import를 제거한다.
- [ ] **Step 3: 실행**
```
./gradlew :trading-core:test --tests 'com.kista.tradingcore.account.*' --tests '*OrderCancelServiceTest' --tests '*CryptoDcaRunnerTest' --tests '*StrategyServiceTest' --tests '*DcaSummaryAssemblerTest' --tests '*CryptoPortfolioServiceTest' --tests '*BatchContextFactory*' 2>&1 | grep -E "FAILED|BUILD"
```
→ BUILD SUCCESSFUL
- [ ] **Step 4: 문서** — `account.md`: `dedupeKey()`를 "키 전용 브로커(`Broker.isKeyOnlyCredentials()` — 업비트·메리츠)는 appKey, 그 외 계좌번호"로 바꾼다. 업비트 키 만료 점검 절의 `broker == UPBIT`는 `broker.isApiKeyExpiring()`로 바꾼다.
- [ ] **Step 5: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/account/domain/model/Account.java trading-core/src/main/java/com/kista/tradingcore/account/application/service/AccountService.java trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/schedule/ApiKeyExpiryScheduler.java trading-core/src/main/java/com/kista/tradingcore/trading/application/service/{OrderCancelService,CryptoDcaRunner,StrategyCreationService,DcaSummaryAssembler}.java trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/schedule/BatchContextFactory.java trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CryptoPortfolioService.java trading-core/src/test/java/com/kista/tradingcore/account/adapter/in/schedule/ApiKeyExpirySchedulerTest.java docs/agents/modules/tradingcore/account.md
git commit -m "refactor(trading): 업비트·코인 리터럴 분기를 거래소 capability로 바꾼다"
```

---

### Task 3: USD 통계 합산을 포함식 필터로 (정확성 전제)

**Files:**
- Modify: `stats/application/service/TradingStatsService.java:174-180`, `InvestmentPointsQueryService.java:59-60`, `trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java:30-32,102,127`
- Test(회귀 게이트): `TradingStatsServiceTest`, `InvestmentPointsQueryServiceTest`, `CyclePositionPersistenceAdapterTest`

**Interfaces:**
- Consumes: Task 1 `Broker.getCurrency()`(파생), `Currency.USD`
- Produces: 없음. `CyclePositionPersistenceAdapter.NON_USD_BROKERS`는 private이며 의미가 같다.

- [ ] **Step 1: 테스트** — USDT 브로커가 없어 서비스 테스트로는 USDT 계좌를 만들 수 없다. 실패를 막는 근거는 Task 1 `onlyUsStockQuotesUsd`와 이 태스크의 필터 형태다. 기존 UPBIT 제외 테스트가 회귀 게이트다. 해당 테스트가 없으면 `TradingStatsServiceTest.aggregateExcludesNonUsdAccounts`를 추가한다(KIS + UPBIT 계좌, aggregate 조회 → `strategyPort.findByAccountIds`에 KIS id만 전달).
- [ ] **Step 2: 치환**
  - `TradingStatsService:179`: `.filter(a -> !aggregate || (a.broker() != Broker.MOCK && a.broker().getCurrency() == Currency.USD))`. 주석: "USD 합산 — 견적 통화가 USD인 계좌만(코인 KRW·USDT 제외)".
  - `InvestmentPointsQueryService:60`: `.filter(a -> a.broker() != Broker.MOCK && a.broker().getCurrency() == Currency.USD)`
  - `CyclePositionPersistenceAdapter:31-32`: `Arrays.stream(Broker.values()).filter(b -> b.getCurrency() != Currency.USD).map(Enum::name).toList()`. 상수명 `NON_USD_BROKERS`는 유지하고, 주석에서 "CRYPTO_KRW 제외" 문구를 "USD 견적 아님"으로 바꾼다.
  - 쓰지 않게 된 `Market` import를 제거한다.
- [ ] **Step 3: 실행** `./gradlew :trading-core:test --tests '*TradingStatsServiceTest' --tests '*InvestmentPointsQueryServiceTest' --tests '*CyclePositionPersistenceAdapterTest' 2>&1 | grep -E "FAILED|BUILD"` → BUILD SUCCESSFUL (DB 테스트는 `docker compose up -d postgres` 필요)
- [ ] **Step 4: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/stats/application/service/{TradingStatsService,InvestmentPointsQueryService}.java trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/CyclePositionPersistenceAdapter.java [추가한 테스트 경로]
git commit -m "fix(stats): USD 합산 통계를 USD 견적 계좌 포함식으로 바꿔 새 코인 시장이 섞이지 않게 한다"
```

---

### Task 4: DCA 거래 규칙을 시장 capability + 커널 입력으로

**own-type 게이트 판정(이 태스크에서 기록):** 실행 시점 거래 규칙은 broker 포트가 반환하고 matching 커널이 입력으로 받는다. 두 모듈에 각각 타입을 두면 (a) 순환 불가피(matching은 sharedkernel만 의존하므로 sharedkernel 승격으로 끊긴다)에도, (b) 외부 계약 분리(값 집합 자체에 wire 계약이 없다 — exchangeInfo 매핑은 Phase 2 어댑터 내부)에도 해당하지 않는다. 그래서 복제하지 않고 `com.kista.shared.kernel.CryptoTradingRules` 단일 타입으로 둔다(선례: `TradeLegSummary`·`Quantities` — trading-core 내부 모듈 간 공유 값을 sharedkernel에 둔 사례). own-type이 아니므로 `own-type-ledger.md`에는 추가하지 않고 `kernel.md` 값 타입 줄에 기록한다.

**Files:**
- Create: `shared/src/main/java/com/kista/shared/kernel/CryptoTradingRules.java`, `shared/src/test/java/com/kista/shared/kernel/CryptoTradingRulesTest.java`
- Modify: `matching/domain/model/DcaRule.java:31-33,60-72`, `matching/domain/strategy/DcaCycleOrderStrategy.java:31-36,74-77,89,105,122-143`, `matching/domain/model/BootstrapPosition.java:26-30`
- Modify: `broker/application/port/output/CryptoExchangePort.java`(메서드 추가), `broker/adapter/out/upbit/UpbitExchangeAdapter.java`(상수·구현)
- Modify: `trading/application/service/CryptoDcaRoundExecutor.java:63-68,93-97,120-121,210`, `DcaPlanInputCollector.java:116-118`(인자 `null` 추가만)
- Test: `matching/domain/strategy/DcaRuleDecisionTest.java`, `DcaCycleOrderStrategyTest.java`(헬퍼 인자 추가), `trading/application/service/CryptoDcaRoundExecutorTest.java`, `TradingCandidatePlannerDcaTest.java`(헬퍼 인자 추가), `matching/domain/model/DcaRuleTest.java`, `broker/adapter/out/upbit/UpbitExchangeAdapterTest.java`
- Modify(Doc): `docs/agents/modules/shared/kernel.md`(값 타입 줄), `docs/agents/modules/tradingcore/matching.md`(DCA 절 — `DcaRuleInputs.tradingRules`, `requireCompatible` 시장 capability), `docs/agents/modules/tradingcore/trading.md:90`(`DcaRule.CRYPTO_MIN_AMOUNT`·"1.0005 상수" → `Market.minOrderAmount()`·`CryptoExchangePort.getTradingRules`), `src/test/resources/architecture/flows.yml:510`("코인 MARKET·5,000원 이상 정수" → "코인 MARKET·시장 최소 주문 금액 이상·통화 소수 자릿수")

**Interfaces:**
- Consumes: Task 1 `Market.isCrypto()`/`minOrderAmount()`/`amountScale()`/`quoteCurrency()`, `Currency.format()`
- Produces:
  - `public record CryptoTradingRules(BigDecimal feeRate, FeeSide feeSide, BigDecimal minNotional, BigDecimal quantityStep)` + `public enum FeeSide { QUOTE_SURCHARGE, RECEIVED_ASSET }`
    - `BigDecimal quoteSpendFor(BigDecimal amount)` — 주문 금액 대비 견적 통화 실지출
    - `BigDecimal affordableAmount(BigDecimal cash, int amountScale)` — 지갑으로 낼 수 있는 최대 주문 금액(FLOOR)
    - `int quantityScale()` — `quantityStep.stripTrailingZeros().scale()`
  - `CryptoExchangePort.getTradingRules(StrategyTicker ticker)` → `CryptoTradingRules` (계좌 불필요, 실패 시 예외 — 실행기 조회 단계 실패 경로)
  - `DcaCycleOrderStrategy.DcaRuleInputs(DcaRule rule, Market market, List<BigDecimal> closes, AccountBalance ledger, BigDecimal sellableAfterReserved, BigDecimal fearGreed, BigDecimal currentPrice, BigDecimal availableCash, CryptoTradingRules tradingRules)` — 마지막 인자 추가. `market.isCrypto()`면 `tradingRules` non-null(compact 생성자 `IllegalArgumentException`), 주식은 null.
  - `UpbitExchangeAdapter.TRADING_RULES`(package-private static final) = `new CryptoTradingRules(new BigDecimal("0.0005"), FeeSide.QUOTE_SURCHARGE, new BigDecimal("5000"), new BigDecimal("0.00000001"))`
  - 삭제: `DcaRule.CRYPTO_MIN_AMOUNT`, `DcaRule.CRYPTO_FEE_FACTOR`

- [ ] **Step 1: 실패 테스트 작성**

선행 보정(Phase 2 계획 검토에서 발견): `CryptoDcaRoundExecutorTest`·`CryptoOrderSynchronizerTest`는 KIS 계좌(`TradingFixtures.tradingAccount`)로 코인 경로를 돈다. 이 태스크가 시장을 계좌에서 가져오게 바꾸면 US_STOCK이 되어 기존 단언(`49975`, `"주문 금액 4999 < 최소 5000"`)과 Task 5의 `currency == KRW` 단언이 깨진다. `trading-core/src/testFixtures/java/com/kista/tradingcore/support/TradingFixtures.java`에 `public static TradingAccount upbitTradingAccount(UUID id, UUID userId)` = `TradingAccount.from(upbitAccount(id, userId))`를 추가하고, 두 테스트의 계좌 픽스처를 이것으로 바꾼다(커밋 경로에 `TradingFixtures.java`·`CryptoOrderSynchronizerTest.java` 추가).

`CryptoTradingRulesTest`:
```java
static final CryptoTradingRules UPBIT_LIKE = new CryptoTradingRules(new BigDecimal("0.0005"), FeeSide.QUOTE_SURCHARGE, new BigDecimal("5000"), new BigDecimal("0.00000001"));
static final CryptoTradingRules BINANCE_LIKE = new CryptoTradingRules(new BigDecimal("0.001"), FeeSide.RECEIVED_ASSET, new BigDecimal("5"), new BigDecimal("0.00001"));
@Test void quoteSurchargeAddsFee() { assertThat(UPBIT_LIKE.quoteSpendFor(new BigDecimal("15000"))).isEqualByComparingTo("15007.5"); }
@Test void receivedAssetSpendsExactAmount() { assertThat(BINANCE_LIKE.quoteSpendFor(new BigDecimal("10.50"))).isEqualByComparingTo("10.50"); }
@Test void affordableReversesFee() {
    assertThat(UPBIT_LIKE.affordableAmount(new BigDecimal("50000"), 0)).isEqualTo(new BigDecimal("49975")); // 현행 divide(1.0005, 0, FLOOR)와 동일
    assertThat(BINANCE_LIKE.affordableAmount(new BigDecimal("100.129"), 2)).isEqualTo(new BigDecimal("100.12"));
}
@Test void quantityScaleFromStep() {
    assertThat(UPBIT_LIKE.quantityScale()).isEqualTo(8); // 현행 코인 scale 8과 동일
    assertThat(BINANCE_LIKE.quantityScale()).isEqualTo(5);
}
@Test void rejectsNonPositiveStep() { assertThatIllegalArgumentException().isThrownBy(() -> new CryptoTradingRules(BigDecimal.ZERO, FeeSide.RECEIVED_ASSET, BigDecimal.ONE, BigDecimal.ZERO)); }
```
`DcaRuleDecisionTest`: 크립토 헬퍼 `decide(...)`가 `CRYPTO_KRW` + `UPBIT_LIKE`(테스트 로컬 상수, 위와 같은 값)를 넘기도록 바꾼다. 기존 크립토 케이스의 단언은 불변이다. 다음 케이스를 추가한다.
```java
@Test void 크립토_USDT_BUY는_소수2자리_금액을_유지한다() {
    DcaRule r = crypto(OrderDirection.BUY, amount("10.5"), DcaShortfallPolicy.REJECT, List.of());
    DcaRuleDecision.Planned p = planned(decideWith(r, Market.CRYPTO_USDT, BINANCE_LIKE, "100", "65000"));
    assertThat(p.notional()).isEqualTo(new BigDecimal("10.50"));
    assertThat(p.quantity()).isEqualTo(new BigDecimal("0.00016")); // 10.50/65000=0.000161538… → step 0.00001 FLOOR
}
@Test void 크립토_USDT_최소금액_미달_문구() {
    DcaRule r = crypto(OrderDirection.BUY, amount("10"), DcaShortfallPolicy.PARTIAL, List.of());
    DcaRuleDecision.Skipped s = skipped(decideWith(r, Market.CRYPTO_USDT, BINANCE_LIKE, "4.99", "65000"));
    assertThat(s.reason()).isEqualTo(DcaSkipReason.BELOW_MIN_ORDER_AMOUNT);
    assertThat(s.detail()).isEqualTo("주문 금액 4.99 < 최소 5");
}
@Test void 크립토_입력은_거래규칙_필수() {
    assertThatIllegalArgumentException().isThrownBy(() -> new DcaCycleOrderStrategy.DcaRuleInputs(
        crypto(OrderDirection.BUY, amount("5000"), DcaShortfallPolicy.REJECT, List.of()), Market.CRYPTO_KRW,
        List.of(BigDecimal.TEN), null, null, null, BigDecimal.TEN, BigDecimal.TEN, null));
}
```
(`decideWith(rule, market, rules, availableCash, currentPrice)` = 종가 `[currentPrice]`·원장 0 헬퍼 — 기존 `decide` 옆에 추가)

`DcaRuleTest`에 추가:
```java
@Test void usdtMarketAllowsTwoDecimalsAndMin5() {
    DcaRule ok = rule("a", OrderDirection.BUY, OrderType.MARKET, 9, amount("10.50"));
    assertThatCode(() -> ok.requireCompatible(Market.CRYPTO_USDT)).doesNotThrowAnyException();
    assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.MARKET, 9, amount("10.505")).requireCompatible(Market.CRYPTO_USDT))
        .withMessage("코인 DCA 금액은 소수 2자리까지 입력할 수 있습니다");
    assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.MARKET, 9, amount("4.99")).requireCompatible(Market.CRYPTO_USDT))
        .withMessage("코인 DCA 금액은 5.00 USDT 이상이어야 합니다");
}
@Test void krwMarketKeepsUpbitMessages() {
    assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.MARKET, 9, amount("5000.5")).requireCompatible(Market.CRYPTO_KRW))
        .withMessage("코인 DCA 금액은 원 단위 정수여야 합니다");
    assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.MARKET, 9, amount("4999")).requireCompatible(Market.CRYPTO_KRW))
        .withMessage("코인 DCA 금액은 5,000원 이상이어야 합니다");
}
```
(`rule(...)` 헬퍼의 실제 인자 순서는 파일 기존 헬퍼를 따른다. executionHour가 필요하면 헬퍼에 맞춘다.)

`CryptoDcaRoundExecutorTest`:
- setUp에서 `when(exchange.getTradingRules(any())).thenReturn(UpbitLikeRules)`를 stub한다. 값은 위 UPBIT_LIKE이고, 테스트 로컬 상수로 둔다 — broker adapter 상수를 import하지 않는다.
- 다음 케이스를 추가한다.
```java
@Test void receivedAssetFeeSideDebitsWalletWithoutSurcharge() {
    // 업비트 계좌지만 규칙만 RECEIVED_ASSET으로 바꿔 지갑 차감 규칙을 격리 검증 — 시장(KRW, scale 0)은 계좌에서
    when(exchange.getTradingRules(any())).thenReturn(new CryptoTradingRules(new BigDecimal("0.0005"), FeeSide.RECEIVED_ASSET, new BigDecimal("5000"), new BigDecimal("0.00000001")));
    balance("20000", "0");
    // 두 전략(BTC 15000, ETH 5000 REJECT) 같은 틱 — secondStrategyUsesLocallyDeductedBalance 패턴
    // QUOTE_SURCHARGE였다면 잔액 4992.5 → ETH 4990 < 5000 REJECT. RECEIVED_ASSET은 잔액 5000 → ETH 5000 주문
    verify(exchange).buyMarketByAmount(any(), eq(StrategyTicker.ETH), eq(new BigDecimal("5000")), anyString());
}
```
`UpbitExchangeAdapterTest.tradingRulesAreUpbitKrwConstants`: `adapter.getTradingRules(StrategyTicker.BTC)` → feeRate `0.0005`, `QUOTE_SURCHARGE`, minNotional `5000`, step `0.00000001`.

- [ ] **Step 2: 실패 확인**
`./gradlew :shared:test --tests 'com.kista.shared.kernel.CryptoTradingRulesTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패(`CryptoTradingRules` 없음).
`./gradlew :trading-core:test --tests '*DcaRuleDecisionTest' --tests '*DcaRuleTest' --tests '*CryptoDcaRoundExecutorTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패(`DcaRuleInputs` 9인자·`getTradingRules` 없음).

- [ ] **Step 3: 구현**
  - `CryptoTradingRules` compact 생성자는 4필드 non-null과 `quantityStep > 0`, `feeRate >= 0`, `minNotional >= 0`을 검사한다.
    - `quoteSpendFor(a)` = `feeSide == QUOTE_SURCHARGE ? a.multiply(BigDecimal.ONE.add(feeRate)) : a`
    - `affordableAmount(cash, s)` = `QUOTE_SURCHARGE ? cash.divide(ONE.add(feeRate), s, FLOOR) : cash.setScale(s, FLOOR)`
    - `quantityScale()`에는 `// ponytail: 10의 거듭제곱 step만 지원(업비트 1e-8, 바이낸스 BTC 1e-5·ETH 1e-4) — 0.5 같은 step이 나오면 step 배수 내림으로 확장` 주석을 단다.
  - `DcaRule.requireCompatible(Market m)`: `if (m.isCrypto())` 분기 안에서 MARKET 검사(문구 불변)를 먼저 한다. AMOUNT는 다음 둘을 검사한다.
    - `stripTrailingZeros().scale() > m.amountScale()` → 문구: `m.amountScale() == 0 ? "코인 DCA 금액은 원 단위 정수여야 합니다" : "코인 DCA 금액은 소수 " + m.amountScale() + "자리까지 입력할 수 있습니다"`
    - `amount < m.minOrderAmount()` → `"코인 DCA 금액은 " + m.quoteCurrency().format(m.minOrderAmount()) + " 이상이어야 합니다"`

    `CRYPTO_MIN_AMOUNT`·`CRYPTO_FEE_FACTOR` 상수와 ponytail 주석은 삭제한다.
  - `DcaCycleOrderStrategy.decideRule`:
    - `crypto = in.market().isCrypto()`, 수량 scale은 `crypto ? in.tradingRules().quantityScale() : 0`
    - 크립토 BUY: `affordable = in.tradingRules().affordableAmount(in.availableCash(), in.market().amountScale())`, `want = rule.sizing().amount().setScale(in.market().amountScale(), FLOOR)`
    - 최소 금액: `value.compareTo(in.tradingRules().minNotional()) < 0`, detail은 `"주문 금액 " + value.setScale(in.market().amountScale(), FLOOR).toPlainString() + " < 최소 " + in.tradingRules().minNotional().toPlainString()`
    - `withSellable`은 `tradingRules`를 전달한다.
    - 125행 주석의 "업비트 금액 주문은 정수 원"은 "금액은 계좌 통화 소수 자릿수"로 바꾼다. 124행 문구 "원화 잔고 조회 실패"는 "주문 가능 잔고 조회 실패"로 바꾼다. 기존 테스트가 이 문구를 단언하지 않음을 grep으로 확인한다(`grep -rn "원화 잔고 조회 실패" trading-core/src/test` 0건 — 확인됨).
  - `BootstrapPosition`: `market != CRYPTO_KRW` → `!market.isCrypto()`, `market == CRYPTO_KRW` → `market.isCrypto()`(2곳). `CRYPTO_SCALE = 8`은 DB numeric(20,8) 제약이라 유지한다.
  - `CryptoExchangePort`에 `CryptoTradingRules getTradingRules(StrategyTicker ticker);`를 추가한다. 주석: "// 종목별 실행 규칙(수수료·차감 쪽·최소 명목가·수량 step) — 계좌 불필요, 조회 실패는 예외". `UpbitExchangeAdapter`는 `TRADING_RULES`를 돌려준다.
  - `CryptoDcaRoundExecutor`:
    - `Map<StrategyTicker, CryptoTradingRules> rulesMemo`를 `candleMemo` 옆에 둔다(try 안 `computeIfAbsent(ticker, exchange::getTradingRules)` — 실패는 기존 조회 실패 경로).
    - 120행을 `new DcaRuleInputs(rule, ctx.account().broker().getMarket(), closes, ledger, sellable, fearGreed, price, wallet.krw, rules)`로 바꾼다(하드코딩 제거).
    - `place(...)`에 `CryptoTradingRules rules`를 넘기고, 210행을 `wallet.krw = wallet.krw.subtract(rules.quoteSpendFor(amount))`로 바꾼다.
    - `DcaRule` import가 남는지 확인한다.
    - `Wallet.krw` 필드명은 Task 6에서 바꾼다.
  - `DcaPlanInputCollector:116`: 마지막 인자 `null`(주식) 추가.
  - 테스트 헬퍼 `DcaCycleOrderStrategyTest:44,350`, `TradingCandidatePlannerDcaTest:115`: 마지막 인자 `null`(주식) 또는 UPBIT_LIKE(크립토) 추가.

- [ ] **Step 4: 통과 확인**
```
./gradlew :shared:test --tests 'com.kista.shared.kernel.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :trading-core:test --tests 'com.kista.tradingcore.matching.*' --tests '*CryptoDcaRoundExecutorTest' --tests '*DcaPlanInputCollectorTest' --tests '*TradingCandidatePlannerDcaTest' --tests '*UpbitExchangeAdapterTest' --tests '*StrategyServiceTest' --tests '*DcaConfigServiceTest' --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
```
→ 둘 다 BUILD SUCCESSFUL. 기존 단언 `49975`(scale 0)·`"주문 금액 4999 < 최소 5000"`가 수정 없이 통과해야 한다.
- [ ] **Step 5: 문서** — Files의 Doc 4곳을 갱신한다. `kernel.md` 값 타입 줄에 다음을 추가한다: `CryptoTradingRules(feeRate, FeeSide QUOTE_SURCHARGE|RECEIVED_ASSET, minNotional, quantityStep) — 거래소 실행 규칙, broker CryptoExchangePort.getTradingRules 반환 = matching DcaRuleInputs 입력(단일 타입 — own-type 게이트 (a)(b) 비해당이라 sharedkernel 승격)`.
- [ ] **Step 6: 커밋**
```
git add shared/src/main/java/com/kista/shared/kernel/CryptoTradingRules.java shared/src/test/java/com/kista/shared/kernel/CryptoTradingRulesTest.java trading-core/src/main/java/com/kista/tradingcore/matching/domain/model/{DcaRule,BootstrapPosition}.java trading-core/src/main/java/com/kista/tradingcore/matching/domain/strategy/DcaCycleOrderStrategy.java trading-core/src/main/java/com/kista/tradingcore/broker/application/port/output/CryptoExchangePort.java trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitExchangeAdapter.java trading-core/src/main/java/com/kista/tradingcore/trading/application/service/{CryptoDcaRoundExecutor,DcaPlanInputCollector}.java trading-core/src/test/java/com/kista/tradingcore/matching trading-core/src/test/java/com/kista/tradingcore/trading/application/service/{CryptoDcaRoundExecutorTest,TradingCandidatePlannerDcaTest}.java trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/upbit/UpbitExchangeAdapterTest.java docs/agents/modules/shared/kernel.md docs/agents/modules/tradingcore/{matching,trading}.md src/test/resources/architecture/flows.yml
git commit -m "refactor(matching): 코인 DCA 최소 금액·수수료·자릿수를 시장 capability와 거래소 규칙 입력으로 옮긴다"
```

---

### Task 5: 계좌 통화 scale 반올림 (체결 이벤트·알림·포트폴리오)

**Files:**
- Modify: `trading/application/event/CryptoOrderFilledEvent.java`(전체), `trading/application/service/CryptoOrderSynchronizer.java:114-116`
- Modify: `notify/adapter/out/gateway/TradingUserNotificationAdapter.java:111-117,128-131`, `notify/adapter/out/gateway/CryptoTradeNotifier.java:32`
- Modify: `stats/domain/model/CryptoPortfolio.java`(전체), `stats/application/service/CryptoPortfolioService.java:34-44,64-70`, `stats/adapter/in/web/dto/CryptoPortfolioResponse.java:16-19,28-30`(매핑만)
- Create(Test): `trading-core/src/test/java/com/kista/tradingcore/trading/application/event/CryptoOrderFilledEventJsonTest.java`
- Modify(Test): `TradingUserNotificationAdapterWordingTest`, `CryptoTradeNotifierTest`, `CryptoOrderSynchronizerTest:190`(접근자명만), `CryptoPortfolioServiceTest`

**Interfaces:**
- Consumes: Task 1 `Broker.getCurrency()`, `Currency.round()`/`format()`
- Produces:
  - `public record CryptoOrderFilledEvent(UUID userId, UUID accountId, UUID strategyId, String accountNickname, StrategyTicker ticker, OrderDirection direction, BigDecimal filledQuantity, BigDecimal avgPrice, @JsonAlias("amountKrw") BigDecimal amount, Currency currency)` — `amount` = 체결 금액(계좌 통화 scale HALF_UP), `currency` null → `Currency.KRW`(compact — 이 필드 추가 전 EPR row는 전부 업비트)
  - `public record CryptoPortfolio(Currency currency, BigDecimal cash, List<Holding> holdings)` — `Holding(StrategyTicker ticker, BigDecimal quantity, BigDecimal avgBuyPrice, BigDecimal currentPrice, BigDecimal evalAmount)`, `BigDecimal totalAsset()`
  - 응답 JSON 불변: `CryptoPortfolioResponse.krwCash ← p.cash()`, `totalAssetKrw ← p.totalAsset()`, `evalAmountKrw ← h.evalAmount()`

- [ ] **Step 1: 실패 테스트 작성**
```java
// CryptoOrderFilledEventJsonTest — EPR(trading.event_publication)에 남은 구 JSON 형태 역직렬화
@Test void oldRowWithAmountKrwDeserializesAsKrw() {
    String json = """
        {"userId":"%s","accountId":"%s","strategyId":"%s","accountNickname":"업비트","ticker":"BTC","direction":"BUY",
         "filledQuantity":0.0001,"avgPrice":150000000,"amountKrw":15000}""".formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    CryptoOrderFilledEvent e = JsonMapper.builder().build().readValue(json, CryptoOrderFilledEvent.class);
    assertThat(e.amount()).isEqualByComparingTo("15000");
    assertThat(e.currency()).isEqualTo(Currency.KRW);
}
@Test void newRowRoundTrips() { /* currency USDT, amount 10.50 → write → read 동일 */ }
```
`TradingUserNotificationAdapterWordingTest`:
- 기존 `cryptoTradeWording`은 생성자에 `Currency.KRW`만 추가하고 기대 문자열은 불변이다(`"…평균가 150,000,000원, 금액 15,000원"`).
- 다음 케이스를 추가한다.
```java
@Test void cryptoTradeWordingUsdt() {
    adapter.notifyCryptoTrade(profile, new CryptoOrderFilledEvent(profile.userId(), UUID.randomUUID(), UUID.randomUUID(), "바낸", StrategyTicker.BTC,
        OrderDirection.BUY, new BigDecimal("0.00016"), new BigDecimal("65000.123"), new BigDecimal("10.40"), Currency.USDT));
    verify(telegram).sendMessage("chat", "[바낸] BTC 매수 체결 — 수량 0.00016, 평균가 65,000.12 USDT, 금액 10.40 USDT", "tok");
}
```
`CryptoOrderSynchronizerTest.placedFilledIsMarkedAndEventPublished`: 단언을 `cap.getValue().amount()`(값 `15000` 불변)로 바꾸고 `assertThat(cap.getValue().currency()).isEqualTo(Currency.KRW)`를 추가한다.
`CryptoPortfolioServiceTest`: 기존 KRW 평가액 단언은 불변이다(접근자 `evalAmount()`/`cash()`/`totalAsset()`로만 수정). `assertThat(portfolio.currency()).isEqualTo(Currency.KRW)`를 추가한다.

- [ ] **Step 2: 실패 확인** `./gradlew :trading-core:test --tests '*CryptoOrderFilledEventJsonTest' --tests '*TradingUserNotificationAdapterWordingTest' --tests '*CryptoOrderSynchronizerTest' --tests '*CryptoPortfolioServiceTest' --tests '*CryptoTradeNotifierTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패(`amount()`·`currency()` 없음).

- [ ] **Step 3: 구현**
  - `CryptoOrderFilledEvent`: `amountKrw` → `@JsonAlias("amountKrw") BigDecimal amount`(`com.fasterxml.jackson.annotation.JsonAlias` — Jackson 3도 이 어노테이션 패키지를 쓴다, toss 어댑터 선례), 마지막에 `Currency currency` 추가, compact `if (currency == null) currency = Currency.KRW;`. 주석은 `// EPR 구 row 호환 — currency 추가 전 발행분은 전부 업비트(KRW)`이고, 한 릴리스 뒤 alias 제거 여부는 Phase 2 계획에서 정한다. 클래스·패키지는 이동하지 않는다.
  - `CryptoOrderSynchronizer:114-116`: `Currency currency = ctx.account().broker().getCurrency();`, 금액 `currency.round(volume.multiply(r.avgPrice()))`, 이벤트 마지막 인자 `currency`. `RoundingMode` import를 제거한다.
  - `TradingUserNotificationAdapter.notifyCryptoTrade`: 포맷을 `"[%s] %s %s 체결 — 수량 %s, 평균가 %s, 금액 %s"`로 바꾸고 인자는 `e.currency().format(e.avgPrice())`, `e.currency().format(e.amount())`로 넘긴다. `krw()` 메서드와 `RoundingMode` import는 삭제한다(다른 사용처 없음 — grep 확인).
  - `CryptoTradeNotifier:32`: `e.amount().doubleValue()`
  - `TradingUserNotificationAdapter.notifyApiKeyExpiring:123-124`: 하드코딩 "업비트 API 키"를 `e.broker().getLabel() + " API 키"`로 바꾼다(`ApiKeyExpiringEvent.broker` 기존 필드, `Broker.UPBIT.getLabel()` = "업비트"라 기존 `TradingUserNotificationAdapterWordingTest` 키 만료 2케이스가 수정 없이 통과해야 한다 — 출력 불변, 공통 경로의 업비트 리터럴 제거)
  - `CryptoPortfolio`: 위 Produces 시그니처. 주석의 "원화"는 "견적 통화"로 바꾼다.
  - `CryptoPortfolioService`: `Currency currency = account.broker().getCurrency()`, `toHolding(..., currency)`에서 `eval = price == null ? null : currency.round(quantity.multiply(price))`, `new CryptoPortfolio(currency, balance.krwAvailable().add(balance.krwLocked()), holdings)`(필드명은 Task 6에서 바뀜).
  - `CryptoPortfolioResponse.from`: `p.cash()`, `p.totalAsset()`, `h.evalAmount()`로 매핑한다. 레코드 컴포넌트명·`@Schema`는 불변이다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.kista.tradingcore.stats.*'` → BUILD SUCCESSFUL
- [ ] **Step 5: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/trading/application/event/CryptoOrderFilledEvent.java trading-core/src/main/java/com/kista/tradingcore/trading/application/service/CryptoOrderSynchronizer.java trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/{TradingUserNotificationAdapter,CryptoTradeNotifier}.java trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoPortfolio.java trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CryptoPortfolioService.java trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/dto/CryptoPortfolioResponse.java trading-core/src/test/java/com/kista/tradingcore/trading/application/event/CryptoOrderFilledEventJsonTest.java trading-core/src/test/java/com/kista/tradingcore/notify/adapter/out/gateway/{TradingUserNotificationAdapterWordingTest,CryptoTradeNotifierTest}.java trading-core/src/test/java/com/kista/tradingcore/trading/application/service/CryptoOrderSynchronizerTest.java trading-core/src/test/java/com/kista/tradingcore/stats/application/service/CryptoPortfolioServiceTest.java
git commit -m "refactor(trading): 코인 체결 금액·알림·포트폴리오 반올림을 계좌 통화 자릿수로 바꾼다"
```

---

### Task 6: CryptoExchangePort 정리 (김프 시세를 stats 공개 시세로 이전·견적 중립 이름·조회 종목·순수량·일봉 신선도)

**Files:**
- Create: `stats/application/port/output/UpbitMarketDataPort.java`, `stats/adapter/out/upbit/UpbitMarketDataAdapter.java`, `stats/adapter/out/upbit/UpbitMarketDataConfig.java`
- Modify: `stats/application/service/CryptoMarketIndicatorService.java:3-9,63,74-94,115-126,164,176,230-237`, `stats/domain/model/CryptoPremiums.java:3,43,60,97-98`, `stats/application/service/CryptoPortfolioService.java:43`
- Modify: `broker/application/port/output/CryptoExchangePort.java`(전체), `broker/domain/model/{CryptoBalance,CryptoOrderResult,CryptoCandle}.java`, `broker/adapter/out/upbit/UpbitExchangeAdapter.java:57-73,86-111,137-191`, `broker/adapter/out/upbit/UpbitMarketRegistry.java:18`
- Modify: `trading/application/service/CryptoDcaRoundExecutor.java:63-68,96,121,168-176,210`, `CryptoOrderSynchronizer.java:71,85,107-116`
- Test:
  - 신규 `stats/adapter/out/upbit/UpbitMarketDataAdapterTest.java` — `UpbitExchangeAdapterTest:105-131`의 USDT 3케이스를 이관하고 코인 KRW 시세 케이스를 추가한다.
  - 신규 `broker/domain/model/CryptoCandleTest.java`
  - 수정: `UpbitExchangeAdapterTest`(USDT 3케이스 삭제), `CryptoMarketIndicatorServiceTest`, `CryptoPremiumsTest`, `CryptoPortfolioServiceTest`, `CryptoDcaRoundExecutorTest`, `CryptoOrderSynchronizerTest`
- Modify(Doc): `docs/agents/modules/tradingcore/broker.md`
  - 6행: CryptoExchangePort 설명에서 "원화 USDT(KRW-USDT) 현재가/완성 일봉"을 빼고 거래 규칙·조회 ticker를 넣는다.
  - 11행: UpbitMarketRegistry 설명
  - 36행: USDT 메서드 줄은 "김프 시세는 stats `UpbitMarketDataAdapter`로 이전"으로 바꾼다.

  `docs/agents/modules/tradingcore/stats.md` 14행(`CryptoExchanges.of(UPBIT)` → stats `UpbitMarketDataPort`, adapter/out 목록에 `upbit/` 추가)

**Interfaces:**
- Consumes: Task 4 `CryptoExchangePort.getTradingRules`
- Produces:
  - `public interface UpbitMarketDataPort` (stats `application.port.output` — `BinanceMarketDataPort`·`CoinbaseMarketDataPort`와 같은 벤더 공개 시세 포트, 김프·USDT 프리미엄 전용)
    - `BigDecimal getUsdtKrwPrice()` — KRW-USDT 현재가, 응답이 비면 `IllegalStateException`
    - `List<DatedValue> getCompletedUsdtKrwDailyCloses(int count)` — 1~400, 완성 봉만(KST 09:00 시작 봉의 KST 시작일), 오래된 순
    - `Map<StrategyTicker, BigDecimal> getKrwPrices(Collection<StrategyTicker> tickers)` — 코인 KRW 현재가(`KRW-<ticker>`)
    - `List<DatedValue> getCompletedKrwDailyCloses(StrategyTicker ticker, int count)` — 위 일봉과 같은 계약
    - 구현: `stats.adapter.out.upbit.UpbitMarketDataAdapter`(package-private `@Component`, 생성자 `UpbitMarketDataAdapter(RestClient statsUpbitRestClient)` + 테스트용 `Clock` 오버로드). 빈: `UpbitMarketDataConfig`(`@Configuration("statsUpbitConfig")`, `@Bean RestClient statsUpbitRestClient()` = `RestClients.withTimeouts(3_000, 7_000)`). 엔드포인트는 절대 URL `https://api.upbit.com/v1/ticker`·`/v1/candles/days`(인증 없음 — `BinanceMarketDataAdapter`의 `SPOT` 상수 패턴)이다.
  - `CryptoPremiums.kimchiHistory(List<DatedValue> upbitCoin, List<DatedValue> upbitUsdt, …)`, `usdtPremiumHistory(List<DatedValue> upbitUsdt, …)` — 첫 인자 타입만 `CryptoCandle` → `DatedValue`로 바꾸고 나머지 인자·반환은 불변이다(stats 도메인이 broker 타입을 import하지 않게)
  - `CryptoExchangePort`(최종)
    - `Broker supports()`
    - `CryptoBalance getBalance(BrokerAccountRef account)`
    - `Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers)`
    - `List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count)`
    - `CryptoTradingRules getTradingRules(StrategyTicker ticker)`
    - `CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal quoteAmount, String clientOrderId)`
    - `CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String clientOrderId)`
    - `Optional<CryptoOrderResult> findOrderByExchangeOrderId(BrokerAccountRef account, StrategyTicker ticker, String exchangeOrderId)` — 구 `findOrderByUuid`
    - `Optional<CryptoOrderResult> findOrderByClientOrderId(BrokerAccountRef account, StrategyTicker ticker, String clientOrderId)` — 구 `findOrderByIdentifier`
    - 멱등 계약 주석(구 "identifier는 계정 내 영구 고유" 대체): `// clientOrderId = 주문 UUID. 같은 값으로 재전송 금지 — 고유 범위가 거래소마다 다르다(업비트 영구, 바이낸스 열린 주문 중에서만)`
    - 삭제: `getUsdtKrwPrice()`, `getCompletedUsdtKrwDailyCandles(int)`
    - 조회 계약 주석: "empty는 거래소가 '없음'을 확정 응답했을 때만 — 네트워크·5xx는 예외(동기화기가 다음 틱 재시도, empty로 바꾸면 FAILED → 재판정 → 이중 매수)"
  - `public record CryptoBalance(BigDecimal quoteAvailable, BigDecimal quoteLocked, Map<StrategyTicker, Holding> holdings)` — `Holding(available, locked, avgBuyPrice)` 불변(평단은 견적 통화)
  - `public record CryptoOrderResult(String exchangeOrderId, String clientOrderId, State state, BigDecimal executedVolume, BigDecimal netExecutedVolume, BigDecimal avgPrice, BigDecimal paidFee, String feeAsset)`
    - `netExecutedVolume` = 원장 반영 순 체결 수량(바이낸스처럼 받은 자산에서 수수료가 빠지면 차감 후, 업비트는 `executedVolume`과 동일)
    - `feeAsset` = 수수료 자산 코드(바이낸스 BNB·BTC·USDT 등, 업비트 `"KRW"` — `Currency`로 표현 불가라 String)
    - `State` 상수(`WAIT`/`WATCH`/`DONE`/`CANCEL`)는 유지하고 주석만 중립으로 바꾼다: `WAIT` = 열린 주문(체결 대기·부분 체결), `WATCH` = 예약 대기, `DONE` = 체결 완료, `CANCEL` = 체결 없이 또는 일부 체결 후 종료(취소·만료·거부). 바이낸스 매핑(`NEW`/`PARTIALLY_FILLED`/`PENDING_NEW` → WAIT, `FILLED` → DONE, `CANCELED`/`EXPIRED`/`EXPIRED_IN_MATCH`/`REJECTED` → CANCEL)이 이 주석에 들어간다. 상수 개명은 동기화기 분기 외 의미 차이가 없어 하지 않는다. 체결 판정은 상태가 아니라 `executedVolume > 0`이다.
  - `CryptoCandle.latestCompletedKstDate(LocalDateTime nowKst)` → `LocalDate` (static): `nowKst.getHour() >= 9 ? today − 1 : today − 2`(KST 09:00 = UTC 00:00 경계 — 바이낸스 1d·업비트 공통)
  - 삭제: `UpbitMarketRegistry.USDT_MARKET`

- [ ] **Step 1: 실패 테스트 작성**
```java
// CryptoCandleTest
@Test void latestCompletedCandleBoundaryIsKst0900() {
    assertThat(CryptoCandle.latestCompletedKstDate(LocalDateTime.of(2026, 10, 23, 8, 59))).isEqualTo(LocalDate.of(2026, 10, 21));
    assertThat(CryptoCandle.latestCompletedKstDate(LocalDateTime.of(2026, 10, 23, 9, 0))).isEqualTo(LocalDate.of(2026, 10, 22));
}
// UpbitMarketDataAdapterTest (MockRestServiceServer — BinanceMarketDataAdapterTest 패턴)
@Test void usdtKrwPriceUsesKrwUsdtMarket() { /* GET https://api.upbit.com/v1/ticker?markets=KRW-USDT → [{"market":"KRW-USDT","trade_price":1452}] → 1452 */ }
@Test void emptyUsdtResponseThrows() { /* [] → IllegalStateException */ }
@Test void completedUsdtClosesDropInProgressCandle() { /* 기존 UpbitExchangeAdapterTest:123 케이스 이관 — 고정 Clock, 진행 중 봉 제외, 오래된 순, DatedValue(date=KST 시작일, value=trade_price) */ }
@Test void krwPricesMapTickersToKrwMarkets() { /* markets=KRW-BTC,KRW-ETH → {BTC: …, ETH: …} */ }
// CryptoOrderSynchronizerTest — 순수량이 원장 입력
@Test void ledgerUsesNetExecutedVolume() {
    Order o = order(OrderStatus.PLACED, TODAY, "upbit-uuid");
    when(exchange.findOrderByExchangeOrderId(any(), eq(o.ticker()), anyString())).thenReturn(Optional.of(
        new CryptoOrderResult("upbit-uuid", orderId.toString(), State.DONE, new BigDecimal("0.0001"), new BigDecimal("0.0000999"), new BigDecimal("150000000"), new BigDecimal("0.0000001"), "BTC")));
    when(ledgerRecorder.recordFill(any(), any(), any(), any())).thenReturn(true);
    run(o, 13);
    verify(ledgerRecorder).recordFill(ctx, o, new BigDecimal("0.0000999"), new BigDecimal("150000000"));
}
@Test void lookupsPassOrderTicker() { /* SUBMITTING → findOrderByClientOrderId(ref, o.ticker(), o.id().toString()) 호출 검증 */ }
// UpbitExchangeAdapterTest
@Test void upbitNetVolumeEqualsExecutedAndFeeAssetIsKrw() { /* 기존 주문 조회 응답 fixture → netExecutedVolume == executedVolume, feeAsset == "KRW" */ }
```
- `CryptoMarketIndicatorServiceTest`: `CryptoExchanges`/`CryptoExchangePort` mock을 `UpbitMarketDataPort` mock으로 바꾼다. stub은 `getKrwPrices`/`getCompletedKrwDailyCloses`/`getCompletedUsdtKrwDailyCloses`로 바꾸고, `CryptoCandle` fixture는 `DatedValue`로 바꾼다. 기존 지표 단언 값은 불변이다.
- `CryptoPremiumsTest`: fixture 타입을 `DatedValue`로 바꾸고 단언은 불변이다.
- 그 외 기존 테스트의 기계적 수정 범위:
  - 메서드·접근자 개명: `findOrderByUuid(any(), anyString())` → `findOrderByExchangeOrderId(any(), any(), anyString())`, `findOrderByIdentifier` → `findOrderByClientOrderId`, `result.uuid()` → `exchangeOrderId()`, `identifier()` → `clientOrderId()`
  - `CryptoOrderResult` 생성자는 6 → 8인자. 동기화 테스트 헬퍼 `result(...)`는 `netExecutedVolume = volume`, `feeAsset = "KRW"`로 둔다.
  - `CryptoBalance` 접근자명

- [ ] **Step 2: 실패 확인** `./gradlew :trading-core:test --tests '*CryptoCandleTest' --tests '*UpbitMarketDataAdapterTest' --tests '*CryptoOrderSynchronizerTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패.

- [ ] **Step 3: 구현**
  - stats `UpbitMarketDataPort`·`UpbitMarketDataConfig`·`UpbitMarketDataAdapter`를 생성한다.
    - 어댑터 클래스 주석: `// 업비트 공개(무인증) 시세 — 김프·USDT 프리미엄 전용. 업비트 거래(broker UpbitExchangeAdapter)를 제거해도 이 어댑터는 남는다`
    - 일봉 페이징(`to` exclusive·count+1 첫 페이지·완성 봉 필터·`TreeMap` 중복 제거·최대 400)은 `UpbitExchangeAdapter.completedDailyCandles`의 로직을 그대로 옮긴다. 반환 타입만 `DatedValue`로 바꾼다.
    - 응답 record(`TickerRow`·`CandleRow`)는 이 패키지에 package-private로 둔다. 모듈 경계상 broker의 package-private 타입은 재사용할 수 없다. `// ponytail: broker UpbitExchangeAdapter와 일봉 페이징 중복 — 모듈 경계(stats↛broker adapter) 때문, 업비트 거래 제거 시 broker 쪽이 사라져 해소`
    - 종목 마켓 코드는 `"KRW-" + ticker.name()`(BTC·ETH)이다. `UpbitMarketRegistry`는 broker internal이라 참조하지 않는다.
  - `CryptoPremiums`: `CryptoCandle` 파라미터를 `DatedValue`로 바꾸고 `closeByDate`는 `DatedValue::date`/`DatedValue::value`로 바꾼다. broker import를 제거한다.
  - `CryptoMarketIndicatorService`:
    - 필드 `private final UpbitMarketDataPort upbit;`(주석 "업비트 공개 시세 (국내 기준가 — 김프)")
    - 두 생성자의 첫 인자 `CryptoExchanges exchanges`를 `UpbitMarketDataPort upbit`로 바꾸고 `exchanges.of(Broker.UPBIT)`를 제거한다.
    - 119-120행 `upbit.getUsdtKrwPrice()`/`upbit.getCompletedUsdtKrwDailyCloses(MAX_DAYS)`, 164행 `upbit.getKrwPrices(TICKERS)`, 236행 `upbit.getCompletedKrwDailyCloses(t, MAX_DAYS)`
    - `usdtCandles` 지역변수 타입은 `List<DatedValue>`
    - `Broker`·`CryptoExchanges`·`CryptoExchangePort`·`CryptoCandle` import를 제거한다(broker 의존 0).
  - `UpbitExchangeAdapter`:
    - `getUsdtKrwPrice`·`getCompletedUsdtKrwDailyCandles`를 삭제하고 `UpbitMarketRegistry.USDT_MARKET`도 삭제한다.
    - `getBalance` 지역변수는 `quoteAvailable`/`quoteLocked`, `buyMarketByAmount` 인자명은 `quoteAmount`로 바꾼다.
    - `findOrderByExchangeOrderId`/`findOrderByClientOrderId`로 개명하고 ticker 인자를 받되 업비트에서는 쓰지 않는다. 주석: `// 업비트 조회는 uuid/identifier만으로 충분`. 업비트 wire 쿼리 키(`uuid`/`identifier`)와 `OrderRow` 필드는 어댑터 내부에 그대로 둔다. `toResult`는 `new CryptoOrderResult(o.uuid(), o.identifier(), …)` 위치 매핑이다.
    - `toResult`는 `netExecutedVolume = executedVolume`, `feeAsset = "KRW"`(KRW 마켓 수수료는 원화)로 둔다.
  - `CryptoCandle`에 static `latestCompletedKstDate`를 추가한다. `CryptoExchangePort.getCompletedDailyCandles` 주석에 "마지막 봉 일자 계약 = `CryptoCandle.latestCompletedKstDate(now)`"를 명시한다.
  - `CryptoDcaRoundExecutor`:
    - `Wallet.krw` → `Wallet.quote`(주석 "견적 통화 가용"), `balance.quoteAvailable()`
    - 173행 → `LocalDate expected = CryptoCandle.latestCompletedKstDate(now);`, 172행 주석의 "업비트 일봉"은 "KST 09:00 마감(CryptoCandle 계약)"으로 바꾼다.
  - `CryptoOrderSynchronizer`:
    - 71행 `exchange.findOrderByClientOrderId(ref, order.ticker(), order.id().toString())`, 80행 `found.get().exchangeOrderId()`, 85행 `exchange.findOrderByExchangeOrderId(ref, order.ticker(), order.externalOrderId())`
    - `CryptoDcaRoundExecutor:198` `result.exchangeOrderId()`, 지역변수 `identifier` → `clientOrderId`
    - settle: 체결 판정은 기존 `executedVolume > 0`을 유지한다. 원장은 `ledgerRecorder.recordFill(ctx, order, r.netExecutedVolume(), r.avgPrice())`에 넘긴다. 이벤트 `filledQuantity`·금액은 `executedVolume` 기준을 유지한다(실지출·알림 불변).
  - `CryptoPortfolioService:43`: `balance.quoteAvailable().add(balance.quoteLocked())`
- [ ] **Step 4: 통과 확인**
```
./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.*' --tests 'com.kista.tradingcore.stats.*' --tests '*CryptoDcaRoundExecutorTest' --tests '*CryptoOrderSynchronizerTest' --tests '*CryptoDcaRunnerTest' --tests 'com.kista.tradingcore.architecture.*' --tests '*TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD"
```
→ BUILD SUCCESSFUL(컨텍스트 로드로 `statsUpbitRestClient`·`UpbitMarketDataPort` 단일 빈 주입을 확인한다. `RestClient` 빈이 여러 개라 파라미터명 `statsUpbitRestClient`가 빈 이름과 일치해야 한다 — Binance 선례). 이어서 `grep -n "import com.kista.tradingcore.broker" trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CryptoMarketIndicatorService.java trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoPremiums.java` → 0건.
- [ ] **Step 5: 문서** — broker.md 6·11·36행, stats.md 14행 갱신.
- [ ] **Step 6: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/stats/application/port/output/UpbitMarketDataPort.java trading-core/src/main/java/com/kista/tradingcore/stats/adapter/out/upbit trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/CryptoPremiums.java trading-core/src/main/java/com/kista/tradingcore/stats/application/service/{CryptoMarketIndicatorService,CryptoPortfolioService}.java trading-core/src/main/java/com/kista/tradingcore/broker/application/port/output/CryptoExchangePort.java trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/{CryptoBalance,CryptoOrderResult,CryptoCandle}.java trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/upbit/{UpbitExchangeAdapter,UpbitMarketRegistry}.java trading-core/src/main/java/com/kista/tradingcore/trading/application/service/{CryptoDcaRoundExecutor,CryptoOrderSynchronizer}.java trading-core/src/test/java/com/kista/tradingcore/broker trading-core/src/test/java/com/kista/tradingcore/stats trading-core/src/test/java/com/kista/tradingcore/trading/application/service/{CryptoDcaRoundExecutorTest,CryptoOrderSynchronizerTest}.java docs/agents/modules/tradingcore/{broker,stats}.md
git commit -m "refactor(broker): 김프 시세를 stats 업비트 공개 시세로 옮기고 코인 거래소 포트를 견적 중립 계약·순 체결 수량으로 정리한다"
```

---

### Task 7: 종목 ↔ 다중 시장, 시장은 계좌에서 (Phase 1)

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/StrategyTicker.java`(전체 — `getMarket()` 삭제로 컴파일러가 호출부를 열거)
- Modify (호출부 — 판정 기준을 옆에 적음):
  - `StrategyCreationService.java:46-51,81-86,137,142` — 계좌 시장(검증·원장 자릿수)
  - `StrategyService.java:141-161,166,186,208,239,253,321,345` — DCA 요약은 계좌 시장, 정수 보유는 `ticker.isCrypto()`
  - `StrategyDetail.java:22` — `ticker.isCrypto()`
  - `TradingPreviewService.java:56-58,82-85` — 계좌 거래소
  - `ManualTradeCorrectionService.java:81,129-136` — 계좌 시장
  - `DcaPlanInputCollector.java:116` — 계좌 시장
  - `ReorderService.java:65` — 계좌 거래소
  - `stats/.../CryptoPortfolioService.java:46-55` — 계좌 시장
  - `stats/.../AccountStatisticsService.java:74` — 자산군
  - `stats/.../CryptoMarketIndicatorService.java:60-61` — `tradableOn(CRYPTO_KRW)` — 업비트 KRW 참조 시세 전용
- Modify: `src/main/java/com/kista/api/web/dto/TickerMeta.java`
- Test: `shared/.../MarketVocabularyTest.java:22-31`, `trading-core/src/test/.../broker/adapter/out/upbit/UpbitMarketRegistryTest.java:18`, `StrategyServiceTest`, `CryptoPortfolioServiceTest`, `TradingPreviewServiceTest`, `ManualTradeCorrectionServiceTest`, `ReorderServiceTest`, `src/test/java/com/kista/api/web/MetaControllerTest.java:116-117`
- Modify(Doc): `docs/agents/modules/shared/kernel.md`(StrategyTicker 설명), `src/test/resources/architecture/flows.yml:510`("종목 시장이 다르면" → "종목이 계좌 시장에서 거래되지 않으면(`tradableOn`)")

**Interfaces:**
- Consumes: Task 1 `Market.CRYPTO_USDT`/`isCrypto()`, Task 2 `Broker.isCryptoExchange()`
- Produces:
  - `StrategyTicker`
    - `List<Market> getMarkets()` — 선언 순서, 첫 원소 = 레거시 대표 시장
    - `boolean tradableOn(Market market)`
    - `boolean isCrypto()` — 자산군: 어떤 시장이든 코인 시장이면 true
    - 기존 `getTargetProfitRate()`/`getDescription()`/`tryParse(String)`
    - 삭제: `getMarket()`
    - 값: 주식 6종 `[US_STOCK]`, `BTC`/`ETH` `[CRYPTO_KRW, CRYPTO_USDT]`
  - `TickerMeta(String code, String description, BigDecimal targetProfitRate, String market, List<String> markets)` — `market` = `getMarkets().getFirst().name()`(expand/contract — UI 전환 후 다음 릴리스 제거), `markets` = 전체 시장 name 목록

- [ ] **Step 1: 실패 테스트 작성**
`MarketVocabularyTest.cryptoTickersAreDcaOnlyCryptoMarket`을 다음으로 교체한다(같은 의도, 다중 시장 기준).
```java
@Test void cryptoTickersTradeOnBothCryptoMarkets() {
    assertThat(StrategyTicker.BTC.getMarkets()).containsExactly(Market.CRYPTO_KRW, Market.CRYPTO_USDT);
    assertThat(StrategyTicker.ETH.tradableOn(Market.CRYPTO_USDT)).isTrue();
    assertThat(StrategyTicker.BTC.tradableOn(Market.US_STOCK)).isFalse();
    assertThat(StrategyTicker.SOXL.getMarkets()).containsExactly(Market.US_STOCK);
    assertThat(StrategyTicker.SOXL.tradableOn(Market.CRYPTO_USDT)).isFalse();
    assertThat(StrategyTicker.BTC.isCrypto()).isTrue();
    assertThat(StrategyTicker.SOXL.isCrypto()).isFalse();
    // 기존 DCA 전용·익절률·orderTypes 단언 유지
}
```
`StrategyServiceTest`:
```java
@Test @DisplayName("주식 계좌에 코인 종목은 시장 불일치로 거부된다 — 중간부터 시작 소수 보유보다 시장 검증이 먼저")
void register_btcOnKisAccount_rejectedAsMarketMismatch() {
    when(accountPort.requireOwnedAccount(ACCOUNT_ID, USER_ID)).thenReturn(TradingFixtures.kisAccount(ACCOUNT_ID, USER_ID));
    // DCA·BTC·initialHoldings 0.5·avgPrice 100 — 시장 검증이 BootstrapPosition보다 늦으면 "정수여야" 문구가 먼저 나온다
    assertThatThrownBy(() -> strategyService.register(USER_ID, ACCOUNT_ID, dcaCommandWithHoldings(StrategyTicker.BTC, "0.5", "100")))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("계좌와 종목의 시장이 다릅니다: BTC");
}
```
(`dcaCommandWithHoldings`가 없으면 기존 `dcaCommand` 빌더에 초기 보유를 주는 헬퍼를 추가한다. 정책 stub은 기존 `stubDcaPolicyWithCrypto()`를 재사용한다.)
`MetaControllerTest`에 추가:
```java
.andExpect(jsonPath("$.tickers[?(@.code=='BTC')].markets[*]").value(Matchers.containsInAnyOrder("CRYPTO_KRW", "CRYPTO_USDT")))
.andExpect(jsonPath("$.tickers[?(@.code=='SOXL')].markets[*]").value(Matchers.contains("US_STOCK")))
```
116·117행 기존 `market` 단언(`CRYPTO_KRW`/`US_STOCK`)은 불변이다.

- [ ] **Step 2: 실패 확인** `./gradlew :shared:test --tests 'com.kista.shared.kernel.MarketVocabularyTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → 컴파일 실패(`getMarkets`·`tradableOn` 없음).

- [ ] **Step 3: 구현**
  - `StrategyTicker`: `@RequiredArgsConstructor`를 제거하고 명시 생성자 `StrategyTicker(BigDecimal targetProfitRate, String description, Market primary, Market... others)`를 둔다 → `markets = Stream.concat(Stream.of(primary), Arrays.stream(others)).toList()`(대표 시장 강제). `@Getter`는 유지한다(`getMarkets()` 생성).
    - `tradableOn(m)` = `markets.contains(m)`
    - `isCrypto()` = `markets.stream().anyMatch(Market::isCrypto)`
    - 클래스 주석의 "계좌 증권사 시장과 일치해야 한다"는 "계좌 시장에서 거래 가능해야 한다(`tradableOn`) — 실제 시장은 계좌 브로커가 정한다"로 바꾼다.
  - `StrategyCreationService`:
    - `validateMarket`은 `if (!ticker.tradableOn(market)) throw new IllegalArgumentException("계좌와 종목의 시장이 다릅니다: " + ticker);`
    - `validateMarket(account, dcaConfig, resolvedTicker)` 호출을 `BootstrapPosition.validate` 앞으로 옮긴다.
    - `BootstrapPosition.validate(…, account.broker().getMarket())`
    - 137행 `Market market = account.broker().getMarket();`, 142행 `market.isCrypto() ? null : …`
  - `StrategyService`:
    - `toDetail(Strategy strategy, Market market)`, `toDetails(List<Strategy> strategies, Map<UUID, Market> marketByAccountId)`를 둔다.
    - 호출부:
      - `listByUserId`는 이미 조회한 계좌에서 `Map<UUID, Market>`을 만든다(추가 조회 없음).
      - `listByAccountId`는 `requireOwnedAccount` 반환값의 시장을 쓴다.
      - `getById`/`update`는 `requireOwnedStrategy` 대신 `strategyPort.findByIdOrThrow` + `accountPort.requireOwnedAccount(...)`로 계좌를 받는다. `requireOwnedStrategy`는 나머지 호출부를 위해 유지한다.
    - 239·321행의 `buildDcaSummary(..., market, …)`에 계좌 시장을 넘긴다.
    - 345행 `strategy.ticker().isCrypto() ? null : …`
  - `StrategyDetail:22`: `if (strategy.ticker().isCrypto())`, `Market` import를 제거한다.
  - `TradingPreviewService`:
    - 57행 `Account account = accountPort.requireOwnedAccount(...)`, 58행 `if (account.broker().isCryptoExchange()) throw …`
    - 84-85행 `List<Strategy> strategies = account.broker().isCryptoExchange() ? List.of() : strategyPort.findByAccountId(accountId);`(계좌 단위 동일 판정)
  - `ManualTradeCorrectionService`: `validateQuantity(fill, account.broker().getMarket())`, 시그니처 `validateQuantity(ManualTradeCorrectionCommand.Fill fill, Market market)`, `boolean crypto = market.isCrypto()`
  - `DcaPlanInputCollector:116`: `ctx.account().broker().getMarket()`(`ctx`가 루프 변수 — 실제 이름은 파일 기준)
  - `ReorderService:65`: `if (account.broker().isCryptoExchange())`
  - `CryptoPortfolioService.getCurrentPrices`: `requireCryptoAccount`를 먼저 호출하고(소유권·코인 계좌 검증 — 거래소 HTTP 전이라 guard 밖 IAE 400 유지), 종목 검증은 `tickers.isEmpty() || tickers.stream().anyMatch(t -> !t.tradableOn(account.broker().getMarket()))`로 한다. 테스트가 계좌 stub 없이 종목 거부를 검증하면 계좌 stub을 추가한다.
  - `AccountStatisticsService:74`: `.filter(o -> !o.ticker().isCrypto())`
  - `CryptoMarketIndicatorService:61`: `.filter(t -> t.tradableOn(Market.CRYPTO_KRW))`. 주석: `// 업비트 KRW 참조 시세 대상`.
  - `UpbitMarketRegistryTest:18`: `.filter(t -> t.tradableOn(Market.CRYPTO_KRW))`
  - `TickerMeta`: `markets` 컴포넌트 추가(`@Schema(description = "거래 가능 시장 목록 (Market name())")`). `market`의 `@Schema` 설명에 "대표 시장 — markets로 대체, 다음 릴리스 제거"를 단다.
- [ ] **Step 4: 통과 확인**
```
./gradlew :shared:test --tests 'com.kista.shared.kernel.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.*' --tests 'com.kista.tradingcore.stats.*' --tests '*UpbitMarketRegistryTest' --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :test --tests 'com.kista.api.web.MetaControllerTest' 2>&1 | grep -E "FAILED|BUILD"
```
→ 모두 BUILD SUCCESSFUL. 이어서 `grep -rn "getMarket()" --include='*.java' trading-core/src/main shared/src/main src/main | grep -v "broker()\.getMarket()\|b\.getMarket()\|getMarkets()"` → 0건(종목 `getMarket()` 잔존 없음 — 컴파일로 이미 보장, 확인용).
- [ ] **Step 5: 문서** — `kernel.md` 공유 enum 줄의 `StrategyTicker(market 보유 — BTC/ETH는 CRYPTO_KRW)`를 `StrategyTicker(markets 보유·tradableOn·isCrypto — BTC/ETH는 CRYPTO_KRW·CRYPTO_USDT 공유, 실제 시장은 계좌 브로커)`로 바꾼다. `flows.yml:510`도 갱신한다.
- [ ] **Step 6: 커밋**
```
git add shared/src/main/java/com/kista/shared/kernel/StrategyTicker.java shared/src/test/java/com/kista/shared/kernel/MarketVocabularyTest.java trading-core/src/main/java/com/kista/tradingcore/trading/application/service/{StrategyCreationService,StrategyService,TradingPreviewService,ManualTradeCorrectionService,DcaPlanInputCollector,ReorderService}.java trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/StrategyDetail.java trading-core/src/main/java/com/kista/tradingcore/stats/application/service/{CryptoPortfolioService,AccountStatisticsService,CryptoMarketIndicatorService}.java src/main/java/com/kista/api/web/dto/TickerMeta.java [수정한 테스트 경로들] docs/agents/modules/shared/kernel.md src/test/resources/architecture/flows.yml
git commit -m "feat(trading): 코인 종목을 여러 시장이 공유하고 전략 시장은 계좌 브로커에서 정한다"
```

---

### Task 8: 최종 검증·잔존 리터럴 게이트·문서 드리프트

**Files:**
- Modify(필요 시): `README.md`(기술 스택·아키텍처 서술에 "업비트 KRW 전용" 표현이 있으면), `../kista-ui/README.md`(영향 없음 확인만)
- Modify: 이 계획 문서 제목 아래 `> 상태: 완료 (YYYY-MM-DD, 커밋 <해시>)`

**Interfaces:** Consumes Task 1~7 전부 / Produces 없음

- [ ] **Step 1: 잔존 리터럴 게이트**
```
grep -rn -E "Market\.CRYPTO_KRW|Broker\.UPBIT|== UPBIT|CRYPTO_MIN_AMOUNT|CRYPTO_FEE_FACTOR|getUsdtKrw|amountKrw|krwAvailable|krwLocked" --include='*.java' trading-core/src/main shared/src/main src/main
```
- 기대 허용 목록:
  - `Market.java`·`Broker.java`(상수 정의)
  - `StrategyTicker.java`(BTC/ETH 시장)
  - `TradingPolicySettings.java:30`(UPBIT 정책 기본값)
  - `UpbitAuthApi.java:27,55`, `UpbitExchangeAdapter.java` `supports()`(업비트 거래 구현체 자기 식별)
  - `CryptoMarketIndicatorService` `tradableOn(Market.CRYPTO_KRW)`(업비트 공개 시세 김프 대상 — 의도)
  - `stats/application/port/output/UpbitMarketDataPort`·`stats/adapter/out/upbit/*`, 그리고 이를 호출하는 `CryptoMarketIndicatorService`의 `getUsdtKrw*`(김프 공개 시세 — 의도)
  - `CryptoOrderFilledEvent`의 `@JsonAlias("amountKrw")`
  - `CryptoPortfolioResponse`의 `krwCash`/`evalAmountKrw`/`totalAssetKrw`(계약 — 다음 계획)
- 그 외 hit는 결함이다(해당 태스크로 되돌아가 수정).
- 업비트 잔존 지점 게이트(업비트 거래 제거 시 손댈 곳이 국소적인지):
```
grep -rln -E "UPBIT|Upbit|upbit|업비트|CRYPTO_KRW" --include='*.java' trading-core/src/main shared/src/main src/main | grep -v -E "/broker/adapter/out/upbit/|/broker/domain/model/upbit/|/stats/adapter/out/upbit/|/stats/application/port/output/UpbitMarketDataPort"
```
- 기대 허용 목록:
  - kernel `Broker.java`·`Market.java`·`StrategyTicker.java`·`TradingPolicySettings.java`
  - `CryptoMarketIndicatorService.java`(김프 — 공개 시세라 업비트 거래 제거와 무관)
  - `CryptoOrderFilledEvent.java`(구 row KRW 폴백 주석)
  - 주석·Swagger 설명에만 업비트가 남은 파일(코드 분기·사용자 문구 없음 — 리뷰어가 hit마다 주석인지 확인. 사용자에게 나가는 문자열 리터럴의 "업비트"는 결함)
  - root `AdminSettingsController.java`·`AdminAccount*`(기존 주석)
- 그 외 코드 분기는 결함이다.
- [ ] **Step 2: 전체 스위트 1회** (postgres·redis 기동 필요: `docker compose up -d postgres redis`)
```
./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"
grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml trading-core/build/test-results/test/TEST-*.xml shared/build/test-results/test/TEST-*.xml 2>/dev/null
```
→ BUILD SUCCESSFUL, 실패 XML 0건. `ArchitectureMapTest`(flows.yml `code:` 참조), `HexagonalArchitectureTest`(matching·sharedkernel 의존), `ModulithArchitectureTest`(api·trading-core), `InternalApiContractTest`(contract 불변) 포함.
- [ ] **Step 3: 드리프트 확인** — `grep -n -i "upbit\|업비트\|KRW" README.md`를 실행해 코드와 어긋난 서술(예: "코인은 업비트 KRW 전용 구조")이 있으면 고친다. kista-ui 영향을 정리한다: `TickerMeta.markets` 추가만 있고 UI 변경은 불필요하다(`market` 유지). `gen:types`는 Phase 3에서 한다. `docs/agents/modules/tradingcore/trading.md` "코인 DCA 실행" 절에 남은 "1.0005"·"원화"·"KST 09:00(업비트)" 서술을 grep해 Task 4·6 결과와 맞춘다.
- [ ] **Step 4: 커밋**
```
git add [수정한 문서 경로] docs/superpowers/plans/2026-10-23-binance-dca-phase0-1-structure.md
git commit -m "docs: 코인 거래소 일반화 Phase 0+1 완료를 문서에 반영한다"
```

---

## 자체 검토 (writing-plans)
- 스펙 커버리지는 다음과 같다.
  - 1절: Task 1·2, `Broker.currency` 파생 포함
  - 2절: Task 7(편차 1건 명시), `TickerMeta.markets` 포함
  - 3절: Task 4(정적 = `Market`, 동적 = `CryptoTradingRules`, 지갑 차감 포함)
  - 4절: Task 6(김프 시세 → stats 업비트 공개 시세 어댑터 이전, 중립 이름·조회 종목·신선도·순수량)
  - 9절: Task 3, `CryptoMarketIndicatorService:61`은 Task 7
  - EPR 개명: Task 5
  - 범위 밖으로 남긴 것: `CryptoPortfolioResponse` 신규 필드·`currency`, SSE `TradeEventMessage` 통화, `Broker.BINANCE`
- 타입 일관성: `DcaRuleInputs` 9번째 인자 `tradingRules`(Task 4)는 Task 6·7에서 재정의하지 않는다. `CryptoOrderResult` 8인자(Task 6). `CryptoOrderFilledEvent` 10인자(Task 5). `findOrderByExchangeOrderId`/`findOrderByClientOrderId` 3인자(Task 6).
- 태스크 간 같은 줄 이중 수정은 없다. `CryptoDcaRoundExecutor:120`은 Task 4가, `Wallet.krw` 이름은 Task 6이, `CryptoPortfolioService`는 59행(Task 2)·반올림(Task 5)·43행(Task 6)·49행(Task 7)을 맡아 줄 단위로 나뉜다.

## 다음 계획이 소비하는 인터페이스

```java
// ── shared.kernel ─────────────────────────────────────────────
public enum Market {
    US_STOCK, CRYPTO_KRW, CRYPTO_USDT;                 // 신규: CRYPTO_USDT
    public Currency quoteCurrency();                   // US_STOCK→USD, CRYPTO_KRW→KRW, CRYPTO_USDT→USDT
    public boolean isCrypto();                         // CRYPTO_KRW·CRYPTO_USDT true
    public BigDecimal minOrderAmount();                // 등록 시점 최소 주문 금액: US_STOCK 0, CRYPTO_KRW 5000, CRYPTO_USDT 5
    public int amountScale();                          // = quoteCurrency().amountScale()
}

public enum Currency {
    KRW, USD, JPY, USDT;                               // 신규: USDT
    public int amountScale();                          // KRW 0, USD 2, JPY 0, USDT 2
    public BigDecimal round(BigDecimal amount);        // setScale(amountScale, HALF_UP)
    public String format(BigDecimal amount);           // KRW "15,000원", 그 외 "10.50 USDT"
    public static Optional<Currency> tryParse(String code);
}

public enum Broker {                                   // 생성자: (label, shortLabel, Market market, boolean keyOnlyCredentials, boolean apiKeyExpiring)
    TOSS, KIS, MOCK, UPBIT, MERITZ;
    public String getLabel(); public String getShortLabel(); public Market getMarket();
    public Currency getCurrency();                     // = market.quoteCurrency() (필드 없음)
    public boolean isKeyOnlyCredentials();             // 계좌번호 없이 키만: UPBIT, MERITZ
    public boolean isApiKeyExpiring();                 // API 키 만료일 보유(만료 알림 대상): UPBIT
    public boolean isCryptoExchange();                 // = market.isCrypto()
    public boolean supportsOpenAuctionOrders();        // 불변
}
// Phase 2 BINANCE 상수 예: BINANCE("바이낸스", "바이낸스", Market.CRYPTO_USDT, true, false)

public enum StrategyTicker {                           // 상수 불변, BTC/ETH markets = [CRYPTO_KRW, CRYPTO_USDT]
    public List<Market> getMarkets();                  // 선언 순서, 첫 원소 = 레거시 대표 시장
    public boolean tradableOn(Market market);
    public boolean isCrypto();                         // 자산군
    public BigDecimal getTargetProfitRate(); public String getDescription();
    public static Optional<StrategyTicker> tryParse(String name);
    // 삭제: getMarket()
}

public record CryptoTradingRules(
        BigDecimal feeRate,          // 수수료율 (업비트 0.0005)
        FeeSide feeSide,             // 수수료 차감 쪽
        BigDecimal minNotional,      // 최소 주문 금액 (견적 통화)
        BigDecimal quantityStep      // 수량 최소 단위 (10의 거듭제곱)
) {
    public enum FeeSide { QUOTE_SURCHARGE, RECEIVED_ASSET } // 업비트: 견적 통화 추가 차감 / 바이낸스: 받은 자산(또는 BNB)에서 차감
    public BigDecimal quoteSpendFor(BigDecimal amount);                 // 견적 통화 실지출
    public BigDecimal affordableAmount(BigDecimal cash, int amountScale); // 지갑으로 가능한 최대 주문 금액(FLOOR)
    public int quantityScale();                                         // quantityStep.stripTrailingZeros().scale()
}

// ── tradingcore.broker (application.port.output / domain.model) ──
public interface CryptoExchangePort {
    Broker supports();
    CryptoBalance getBalance(BrokerAccountRef account);
    Map<StrategyTicker, BigDecimal> getCurrentPrices(Collection<StrategyTicker> tickers);
    List<CryptoCandle> getCompletedDailyCandles(StrategyTicker ticker, int count);   // 마지막 봉 = CryptoCandle.latestCompletedKstDate(now)
    CryptoTradingRules getTradingRules(StrategyTicker ticker);                       // 신규
    // clientOrderId = 주문 UUID. 같은 값으로 재전송 금지 — 고유 범위가 거래소마다 다르다(업비트 영구, 바이낸스 열린 주문 중에서만)
    CryptoOrderResult buyMarketByAmount(BrokerAccountRef account, StrategyTicker ticker, BigDecimal quoteAmount, String clientOrderId);
    CryptoOrderResult sellMarketByVolume(BrokerAccountRef account, StrategyTicker ticker, BigDecimal volume, String clientOrderId);
    Optional<CryptoOrderResult> findOrderByExchangeOrderId(BrokerAccountRef account, StrategyTicker ticker, String exchangeOrderId); // 구 findOrderByUuid, ticker 신규
    Optional<CryptoOrderResult> findOrderByClientOrderId(BrokerAccountRef account, StrategyTicker ticker, String clientOrderId);     // 구 findOrderByIdentifier, ticker 신규
    // empty = 거래소 '없음' 확정 응답일 때만. 네트워크·5xx는 예외
    // 삭제: getUsdtKrwPrice(), getCompletedUsdtKrwDailyCandles(int)
}

// ── tradingcore.stats (application.port.output) — 김프용 업비트 공개 시세 ──
public interface UpbitMarketDataPort {               // 신규 — 구현 stats.adapter.out.upbit.UpbitMarketDataAdapter(키 불필요, 빈 statsUpbitRestClient), 소비 CryptoMarketIndicatorService
    BigDecimal getUsdtKrwPrice();                                          // KRW-USDT 현재가
    List<DatedValue> getCompletedUsdtKrwDailyCloses(int count);            // 1~400, 완성 봉만, 오래된 순, date = KST 봉 시작일
    Map<StrategyTicker, BigDecimal> getKrwPrices(Collection<StrategyTicker> tickers); // KRW-<ticker> 현재가
    List<DatedValue> getCompletedKrwDailyCloses(StrategyTicker ticker, int count);    // 위와 같은 일봉 계약
}
// CryptoPremiums.kimchiHistory/usdtPremiumHistory — 업비트 일봉 인자 타입 CryptoCandle → DatedValue (stats 도메인 broker 의존 0)
// 업비트 거래(broker) 제거 시에도 이 포트·어댑터는 유지 — 김프 지표 소스

public record CryptoBalance(BigDecimal quoteAvailable, BigDecimal quoteLocked, Map<StrategyTicker, Holding> holdings) {
    public record Holding(BigDecimal available, BigDecimal locked, BigDecimal avgBuyPrice) { BigDecimal total(); } // avgBuyPrice: 견적 통화, 없으면 원장 값 사용(Phase 2)
}

public record CryptoOrderResult(
        String exchangeOrderId,         // 구 uuid — 거래소 주문 ID (Order.externalOrderId에 저장)
        String clientOrderId,           // 구 identifier — 우리 주문 UUID
        State state,
        BigDecimal executedVolume,      // 체결 수량 (체결 판정: > 0)
        BigDecimal netExecutedVolume,   // 신규 — 원장 반영 순수량(받은 자산 수수료 차감 후, 업비트 = executedVolume)
        BigDecimal avgPrice,            // 체결 평균가 (견적 통화)
        BigDecimal paidFee,             // 수수료 금액
        String feeAsset                 // 신규 — 수수료 자산 코드 (업비트 "KRW", 바이낸스 "BNB"/"BTC"/"USDT" …)
) { public enum State { WAIT, WATCH, DONE, CANCEL } } // WAIT=열린 주문, WATCH=예약 대기, DONE=체결 완료, CANCEL=체결 없이/일부 체결 후 종료(취소·만료·거부)

public record CryptoCandle(LocalDate kstDate, BigDecimal close) {
    public static LocalDate latestCompletedKstDate(LocalDateTime nowKst); // 신규 — 09시 이후 today-1, 이전 today-2
}

// UpbitExchangeAdapter.TRADING_RULES (package-private) = (0.0005, QUOTE_SURCHARGE, 5000, 0.00000001)

// ── tradingcore.matching ──────────────────────────────────────
public record DcaCycleOrderStrategy.DcaRuleInputs(
        DcaRule rule, Market market, List<BigDecimal> closes, AccountBalance ledger,
        BigDecimal sellableAfterReserved, BigDecimal fearGreed, BigDecimal currentPrice,
        BigDecimal availableCash,          // 코인 BUY만 — 견적 통화 지갑 가용
        CryptoTradingRules tradingRules)   // 신규 — market.isCrypto()면 필수, 주식 null
// DcaRule.requireCompatible(Market accountMarket) — 시그니처 불변, 코인 금액 규칙 = accountMarket.amountScale()/minOrderAmount()
// 삭제: DcaRule.CRYPTO_MIN_AMOUNT, DcaRule.CRYPTO_FEE_FACTOR

// ── tradingcore.trading (application.event) — EPR 저장 ─────────
public record CryptoOrderFilledEvent(
        UUID userId, UUID accountId, UUID strategyId, String accountNickname,
        StrategyTicker ticker, OrderDirection direction,
        BigDecimal filledQuantity,                     // 체결 수량 (executedVolume)
        BigDecimal avgPrice,                           // 체결 평균가 (견적 통화)
        @JsonAlias("amountKrw") BigDecimal amount,     // 개명 — 체결 금액, currency.round(수량 × 평균가)
        Currency currency)                             // 신규 — null(구 row) → KRW

// ── tradingcore.stats (domain.model) — 내부 타입, 응답 JSON 불변 ──
public record CryptoPortfolio(Currency currency, BigDecimal cash, List<Holding> holdings) {
    public record Holding(StrategyTicker ticker, BigDecimal quantity, BigDecimal avgBuyPrice, BigDecimal currentPrice, BigDecimal evalAmount) {}
    public BigDecimal totalAsset();
}
// CryptoPortfolioResponse(krwCash, holdings[evalAmountKrw…], totalAssetKrw) — 필드 불변, 통화 중립 필드·currency 추가는 Phase 3 계획

// ── root api (web.dto) — kista-ui 계약 expand ──────────────────
public record TickerMeta(String code, String description, BigDecimal targetProfitRate,
                         String market,          // 대표 시장 = getMarkets().getFirst() — 다음 릴리스 제거
                         List<String> markets)   // 신규 — 거래 가능 시장 name 목록
```

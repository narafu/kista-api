# 바이낸스 현물 DCA 연동 (Phase 2) Implementation Plan

> 상태: 완료 (2026-10-23, 커밋 0c3c923e)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Broker.BINANCE`를 추가한다. 바이낸스 현물(BTCUSDT·ETHUSDT) 계좌 등록(연결 테스트 포함)부터 코인 DCA 실행·동기화·원장 반영·알림·포트폴리오까지 업비트와 같은 경로로 동작하게 한다. 관리자 정책 기본값은 비활성이다. 백엔드 쪽 API 계약 expand(포트폴리오 중립 필드·`currency`, 브로커 메타 최소 금액·자릿수, SSE 통화)를 같이 낸다.
**Architecture:** 벤더 어댑터는 `com.kista.tradingcore.broker.adapter.out.binance`(internal)이고, 벤더 예외는 `broker.domain.model.binance.BinanceApiException`이다. 공통 경로(실행기·동기화기·원장·알림·포트폴리오)는 Phase 0+1이 만든 계약(`CryptoExchangePort`, `CryptoTradingRules`, `Market`/`Currency` capability)만 쓰므로 `BINANCE` 분기를 새로 두지 않는다. 바이낸스 고유 의미(받은 자산 수수료 → 순수량, `newClientOrderId` 비영구 고유, -2013만 "없음")는 어댑터 안에 가둔다.
**Tech Stack:** Java 25, Spring Boot 4 (`RestClient`, Jackson 3 `tools.jackson`), JUnit 5, Mockito, AssertJ, `MockRestServiceServer`
**Spec:** docs/superpowers/specs/2026-10-23-binance-spot-dca-design.md (이 계획 범위: 5·6·7·8절 + 7·10절의 백엔드 API 계약 expand. kista-ui 작업 제외)
**선행 계획:** docs/superpowers/plans/2026-10-23-binance-dca-phase0-1-structure.md (전부 완료 후 착수)

## Global Constraints
- 선행 계획의 "다음 계획이 소비하는 인터페이스"를 그대로 쓴다. 포트·커널 시그니처(`CryptoExchangePort` 9메서드, `CryptoTradingRules`, `CryptoOrderResult` 8인자, `DcaRuleInputs` 9인자, `CryptoOrderFilledEvent` 10인자)는 이 계획에서 바꾸지 않는다.
- enum 상수명 불변이다. 신규 상수는 `Broker.BINANCE`(마지막에 선언)와 `ErrorCode.BROKER_WITHDRAWAL_ENABLED`(마지막에 선언) 둘뿐이다.
- `Broker.BINANCE("바이낸스", "바이낸스", Market.CRYPTO_USDT, true, false)`.
- 바이낸스 base URL은 프로퍼티 `binance.base-url`(기본 `https://api.binance.com`)이다. 테스트넷은 `https://testnet.binance.vision`이다. 서명 HMAC-SHA256 hex, 헤더 `X-MBX-APIKEY`, `recvWindow=5000`, `timestamp`=`Clock.millis()`이다. 모든 파라미터(POST 포함)는 query string에 넣고 body는 비운다(문서: 중복 시 query 우선 — 서명 경로 1개).
- `newClientOrderId`·`origClientOrderId` = 주문 UUID 하이픈 제거 32자 소문자 hex이다. 변환은 `BinanceClientOrderIds` 한 곳에서만 한다.
- 수수료율 상수는 `BinanceExchangeAdapter.STANDARD_FEE_RATE = 0.001`, 차감 쪽은 `FeeSide.RECEIVED_ASSET`이다. 실제 수수료·순수량은 체결 내역(`fills`/`myTrades`)에서 계산한다.
- "주문 없음"(`Optional.empty()`)은 -2013(그리고 clientOrderId 조회의 -2026)일 때만이다. 5xx·-1006·-1007·I/O는 `rejected=false` 예외다.
- broker 밖 테스트는 벤더 타입(`broker.domain.model.binance..`)을 import하지 않는다. 실패는 `StubBrokerApiException`으로 만든다(`TradingCoreTestVendorModelTest`).
- 주석은 `//` 인라인만 쓴다. Javadoc·블록 주석은 금지다. 신규 필드에는 `// 역할`, API 상수에는 `"BTCUSDT" // 심볼: ...` 형식으로 단다.
- 테스트 명령은 서브프로젝트 접두사 필수다(`./gradlew :shared:test`·`:trading-core:test`·`:test --tests ...`). 출력은 `2>&1 | grep -E "FAILED|BUILD|error:"`로 받는다. 전체 스위트는 Task 9에서 1회만 돌린다.
- 커밋: 한글 Conventional Commit, author `narafu <narafu@kakao.com>`(커밋 전 `git config user.name`/`user.email` 확인). push 금지. `git add`는 경로 명시 + `git diff --cached --stat`로 확인한다(동시 세션 오염 방지). 커밋 메시지 끝에 세션 attribution 줄을 붙인다.
- 경로 약어: `tc/` = `trading-core/src/main/java/com/kista/tradingcore/`, `tct/` = `trading-core/src/test/java/com/kista/tradingcore/`.

## Review Focus
1. **같은 회차 이중 매수(재전송·5xx를 실패로 오인)**
   - 바이낸스 `newClientOrderId`는 열린 주문 중에서만 고유다. 같은 ID 재전송과 "결과 불명을 없음으로 판정"이 둘 다 이중 매수로 이어진다.
   - 잠금 테스트:
     - Task 4 `BinanceExchangeAdapterTest.lookup_5xx_throwsNotEmpty`·`lookup_minus1007_throwsUnknown`·`lookup_ioError_throws`·`lookup_minus2013_isEmpty`
     - Task 4 `clientOrderIdWireFormIsSameOnPostAndGet`(전송 형태 ≠ 조회 형태면 -2013 → FAILED → 재판정 → 이중 매수)
     - Task 4 `CryptoOrderSynchronizerTest.submittingLookupFailureKeepsSubmittingAndNeverSends`
     - Task 4 `CryptoDcaRoundExecutorTest.transientBuyKeepsSubmittingAndNextTickDoesNotResend`
2. **BTC 수수료 미차감 → 원장 보유 과다**
   - 원장은 체결 동기화(`findOrderBy*` 결과)만 쓴다. FULL 주문 응답의 `fills`는 원장에 닿지 않는다.
   - 잠금 테스트:
     - Task 4 `BinanceExchangeAdapterTest.filledBuyNetsBaseAssetCommissionFromMyTrades`(net `0.00015985`)
     - Task 4 `sellNetEqualsExecuted`·`bnbCommissionDoesNotReduceNet`·`myTradesShortOfExecutedThrows`
     - Task 6 `CryptoLedgerRecorderIT.원가는_순수량_곱하기_체결평균가_수수료_제외`
3. **USDT 금액이 원 단위로 잘림(10.50 → 10)**
   - 잠금 테스트:
     - Task 4 `CryptoDcaRoundExecutorTest.binanceAccountBuysWithTwoDecimalUsdt`(`buyMarketByAmount(…, 10.50, …)`, 시장은 계좌에서 — 선행 계획 Review Focus 4의 최종 잠금)
     - Task 4 `BinanceExchangeAdapterTest.buyUsesQuoteOrderQtyPlain`(`quoteOrderQty=10.5`)
     - Task 7 `CryptoPortfolioServiceTest.binanceUsesUsdtScaleAndLedgerAvg`
4. **출금 권한이 켜진 키를 받음**
   - 잠금 테스트: Task 5 `BinanceAuthApiTest.withdrawalEnabledIsRejected`(→ `BrokerWithdrawalEnabledException`, 422 `BROKER_WITHDRAWAL_ENABLED`)와 `verifyCredentialsAppliesSameRestrictionChecks`(연결 테스트 경로도 같은 판정)
5. **바이낸스 금액이 USD 통계 합계에 섞임**
   - 선행 계획 Review Focus 2는 USDT 브로커가 없어 서비스 테스트를 이 계획으로 넘겼다.
   - 잠금 테스트: Task 1 `TradingStatsServiceTest.aggregateExcludesBinanceAccount`, `InvestmentPointsQueryServiceTest.excludesBinanceAccount`

### 설계와의 차이 (의도된 편차)
- **거래 규칙 캐시 없음**(설계 5절 "프로세스 내 캐시"). 근거는 셋이다.
  - 성공 요청은 weight 0이다(2026-04-02~).
  - 실행기가 계좌·틱 단위로 메모한다(`rulesMemo`).
  - 호출은 실행 시각 규칙이 있는 틱에만 일어난다.
  - 캐시가 없으므로 `CANCEL_ONLY` 등 상태 변화도 즉시 반영된다. `// ponytail:` 주석으로 상한(계좌 수 증가 시 TTL 캐시)을 남긴다.
- **계정별 수수료 조회(`/api/v3/account/commission`) 안 함.** `RECEIVED_ASSET`이면 `CryptoTradingRules.quoteSpendFor`·`affordableAmount`가 `feeRate`를 쓰지 않는다(선행 계획 Task 4 구현). 커널 판정은 수수료율과 무관하고, 원장 순수량은 실제 체결 내역에서 온다. 그래서 `getTradingRules(ticker)`에 계좌 인자를 추가할 필요가 없다. `feeRate`는 표시·기록용 표준값 0.001이다.
- **`X-MBX-USED-WEIGHT-1M` 로깅 생략.** 성공 요청 weight 0이고 레이트는 IP 단위다. 429/418 예외 매핑만 둔다.
- **-2015는 `BrokerCredentialException`(IP 미허용 아님).** 바이낸스는 키·IP·권한을 구분하지 않는다("Invalid API-key, IP, or permissions for action."). IP로 단정하면 잘못된 안내가 된다. 실행 중에는 `CryptoFailure.KEY_EXPIRED`로 분류된다(남은 규칙 일괄 미주문 기록, 문구 "만료되었거나 폐기되었습니다" — 폐기·권한 제거 포함). 연결 테스트에서 IP 미제한(`ipRestrict=false`)만 `BrokerIpNotAllowedException`으로 명시 판정한다.
- **-1021(timestamp)은 확정 거절이다.** 재시도하지 않고 서버 시각 동기화도 두지 않는다. OCI는 NTP 동기화 상태이고 요청이 처리되지 않았음이 확실하다. 로그에 `// ponytail:` 상한(로그에 -1021이 보이면 `/api/v3/time` 오프셋 보정)을 남긴다.
- **신규 `ErrorCode.BROKER_WITHDRAWAL_ENABLED`.** kista-ui `brokerCredentials.ts`는 `code`별 고정 문구를 쓰고 `detail`을 쓰지 않는다. 기존 `BROKER_PERMISSION_DENIED` 문구("권한을 켜세요")는 "출금 권한을 끄세요"와 반대 뜻이라 재사용할 수 없다.

---

## 파일 구조

| 파일 | 변경 | 책임 |
|---|---|---|
| `shared/src/main/java/com/kista/shared/kernel/Broker.java` | Modify | `BINANCE` 상수 |
| `shared/src/main/java/com/kista/shared/kernel/TradingPolicySettings.java` | Modify | `UPBIT_ALLOWED_IP` → `SERVER_EGRESS_IP` 개명(Java 전용, wire 아님), BINANCE 기본 비활성 + 허용 IP |
| `shared/src/main/java/com/kista/shared/platform/web/ErrorCode.java` | Modify | `BROKER_WITHDRAWAL_ENABLED` |
| `shared/src/main/java/com/kista/shared/contract/notify/TradeEventMessage.java` | Modify | `Currency currency` 컴포넌트(마지막), 팩토리 인자 |
| `tc/account/adapter/in/web/dto/AccountRequest.java` | Modify | allowableValues·`@Schema` 문구 |
| `tc/broker/domain/model/binance/BinanceApiException.java` | Create | 벤더 예외(status·code·rejected) |
| `tc/broker/domain/model/BrokerWithdrawalEnabledException.java` | Create | 출금 권한 켜진 키 — `BrokerCredentialException` 하위(422) |
| `tc/broker/adapter/out/binance/BinanceConfig.java` | Create | `binanceRestClient` 빈(타임아웃) |
| `tc/broker/adapter/out/binance/BinanceHttpClient.java` | Create | 서명·요청·오류 → 벤더 중립 예외 매핑 |
| `tc/broker/adapter/out/binance/BinanceSymbolRegistry.java` | Create | BTC↔BTCUSDT, ETH↔ETHUSDT, base asset |
| `tc/broker/adapter/out/binance/BinanceClientOrderIds.java` | Create | UUID ↔ 32자 hex |
| `tc/broker/adapter/out/binance/BinanceExchangeAdapter.java` | Create | `CryptoExchangePort` 구현 |
| `tc/broker/adapter/out/binance/BinanceAuthApi.java` | Create | `BrokerConnectionTestPort` 구현(apiRestrictions) |
| `tc/web/TradingExceptionHandler.java` | Modify | 출금 권한 예외 매핑 |
| `tc/stats/application/service/CryptoPortfolioService.java` | Modify | 거래소 평단 null → 원장 평단 |
| `tc/stats/adapter/in/web/dto/CryptoPortfolioResponse.java` | Modify | `currency`·`cash`·`totalAsset`·holding `evalAmount` 추가(krw* 유지·deprecated) |
| `tc/notify/adapter/out/gateway/{CryptoTradeNotifier,TradingReportNotifier}.java` | Modify | SSE 메시지에 통화 |
| `src/main/java/com/kista/api/web/dto/BrokerMeta.java` | Modify | `minOrderAmount`·`amountScale` |
| `trading-core/src/testFixtures/.../support/TradingFixtures.java` | Modify | `binanceAccount`·`binanceTradingAccount` |
| 테스트 JSON 픽스처 | Modify | `TradingPolicyInternalControllerTest.FULL_BODY`, `TradingPolicyHttpAdapterTest.POLICY_JSON`에 `"BINANCE"` 키 |
| ArchUnit | Modify | `HexagonalArchitectureTest:160`, `TradingCoreTestVendorModelTest:34`에 `..domain.model.binance..` |
| 문서 | Create/Modify | `docs/agents/binance-api.md`(신규), `CLAUDE.md`, `README.md`, `docs/agents/modules/{shared/kernel,tradingcore/broker,tradingcore/account,tradingcore/trading,tradingcore/stats,tradingcore/notify,api/admin}.md`, `src/test/resources/architecture/flows.yml` |

Caddy·공개 경로 변경은 없다. 바이낸스 호출은 아웃바운드뿐이고, 계좌 등록·포트폴리오 경로는 기존 그대로다.

---

### Task 1: `Broker.BINANCE` 상수·정책 기본값·계좌 등록 계약·enum 순회 테스트

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/Broker.java`(상수 추가, MERITZ 다음), `TradingPolicySettings.java:22,30-31`
- Modify: `tc/account/adapter/in/web/dto/AccountRequest.java:13,16,18,20`
- Modify(Test fixture): `trading-core/src/testFixtures/java/com/kista/tradingcore/support/TradingFixtures.java`(`upbitAccount` 다음)
- Modify(Test): `shared/src/test/java/com/kista/shared/kernel/{TradingPolicySettingsTest,MarketVocabularyTest}.java`, `tct/trading/adapter/in/web/TradingPolicyInternalControllerTest.java:48`, `src/test/java/com/kista/api/admin/adapter/out/internal/TradingPolicyHttpAdapterTest.java:24`, `tct/trading/adapter/out/persistence/policy/TradingPolicySettingsPersistenceAdapterTest.java`, `tct/account/domain/model/AccountTest.java`, `tct/account/application/service/AccountServiceTest.java`, `tct/stats/application/service/{TradingStatsServiceTest,InvestmentPointsQueryServiceTest}.java`, `src/test/java/com/kista/api/web/MetaControllerTest.java`
- Modify: `src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java:153-160`, `tct/architecture/TradingCoreTestVendorModelTest.java:27-34`(binance 패키지 추가 — 벤더 모델은 Task 2에서 생기지만 규칙은 지금 넓혀도 무해)

**Interfaces:**
- Consumes: 선행 계획 `Broker` 생성자 `(label, shortLabel, Market, keyOnlyCredentials, apiKeyExpiring)`, `Broker.isKeyOnlyCredentials()`/`isCryptoExchange()`/`isApiKeyExpiring()`, `Account.dedupeKeyOf`, `AccountService` 키 전용 분기(선행 Task 2 — capability라 코드 수정 없음)
- Produces:
  - `Broker.BINANCE` — `getMarket()=CRYPTO_USDT`, `getCurrency()=USDT`, `isKeyOnlyCredentials()=true`, `isApiKeyExpiring()=false`, `isCryptoExchange()=true`
  - `TradingPolicySettings.SERVER_EGRESS_IP = "168.107.63.152"`(구 `UPBIT_ALLOWED_IP`), `defaults().brokers().get(BINANCE) = BrokerSettings(false, SERVER_EGRESS_IP)`
  - `TradingFixtures.binanceAccount(UUID id, UUID userId)` = `new Account(id, userId, "바이낸스", null, "binance-key", "binance-secret", null, Broker.BINANCE, null)`, `binanceTradingAccount(...)` = `TradingAccount.from(binanceAccount(...))`

- [ ] **Step 1: 실패 테스트 작성**
  - `TradingPolicySettingsTest`:
    - `defaultsPreserveCurrentRuntimeBehavior`의 비활성 집합을 `Set.of(Broker.UPBIT, Broker.MERITZ, Broker.BINANCE).contains(e.getKey()) != e.getValue().enabled()`로 바꾼다.
    - 추가한다.
      ```java
      @Test void binanceDefaultsToDisabledWithServerIp() {
          BrokerSettings b = TradingPolicySettings.defaults().brokers().get(Broker.BINANCE);
          assertThat(b.enabled()).isFalse();
          assertThat(b.allowedIp()).isEqualTo("168.107.63.152");
      }
      ```
  - `MarketVocabularyTest.brokerCapabilityFlags`(선행 Task 1)의 기대 집합을 갱신한다. 선행 계획이 의도적으로 UPBIT만 잠갔으므로 이 계획에서 갱신하는 것이 정상이다.
    - `isCryptoExchange` → `containsExactly(Broker.UPBIT, Broker.BINANCE)`
    - `isApiKeyExpiring` → `containsExactly(Broker.UPBIT)`(불변)
    - `isKeyOnlyCredentials`에 BINANCE true 단언을 추가한다.
    - 추가 단언: `Broker.BINANCE.getCurrency() == Currency.USDT`
  - `AccountTest.dedupeKeyOf_binance_is_app_key`: `Account.dedupeKeyOf(Broker.BINANCE, null, "K")` → `"K"`.
  - `AccountServiceTest.register_binance_ignores_account_no_and_requires_keys`(MERITZ 케이스 116-130행 복제)
    - BINANCE + accountNo `"123"` → 저장 `accountNo()==null`, `connectionTester.verifyAccount("ak","sk",null)`
    - appKey `" "` → `IllegalArgumentException("API 키와 시크릿은 필수입니다")`
  - `TradingPolicySettingsPersistenceAdapterTest`: 83-95행 UPBIT 백필 케이스를 복제한다. `brokers.remove("BINANCE")` → 로드 후 `brokerEnabled(BINANCE)==false`, `allowedIp=="168.107.63.152"`.
  - `TradingStatsServiceTest.aggregateExcludesBinanceAccount`: KIS + BINANCE 계좌로 aggregate 조회 → `strategyPort.findByAccountIds`에 KIS id만 전달되어야 한다. 선행 Task 3 기존 UPBIT 제외 테스트의 형태를 따른다.
  - `InvestmentPointsQueryServiceTest.excludesBinanceAccount`: 같은 형태.
  - `MetaControllerTest`에 추가한다.
    ```java
    .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].market").value(Matchers.contains("CRYPTO_USDT")))
    .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].currency").value(Matchers.contains("USDT")))
    .andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].label").value(Matchers.contains("바이낸스")))
    ```
- [ ] **Step 2: 실패 확인**
```
./gradlew :shared:test --tests 'com.kista.shared.kernel.*' 2>&1 | grep -E "FAILED|BUILD|error:"
```
→ 컴파일 실패(`Broker.BINANCE` 없음)
- [ ] **Step 3: 구현**
  - `Broker`: `BINANCE("바이낸스", "바이낸스", Market.CRYPTO_USDT, true, false); // 바이낸스 현물 USDT 마켓 (API key/secret HMAC, 계좌번호 없음·키 만료일 없음)`.
  - `TradingPolicySettings`:
    - `UPBIT_ALLOWED_IP`를 `SERVER_EGRESS_IP`로 개명한다. 주석: `// OCI Reserved IPv4 — 거래소 API 키 허용 IP 안내값(업비트·바이낸스)`.
    - 다음 줄을 추가한다: `brokers.put(Broker.BINANCE, new BrokerSettings(false, SERVER_EGRESS_IP)); // 단계 배포 — 운영자가 관리자 설정에서 켠다`.
    - 사용처 grep 결과 2곳, 같은 파일만이다.
  - `AccountRequest`:
    - allowableValues에 `"BINANCE"`를 추가한다.
    - accountNo 설명: `UPBIT·MERITZ·BINANCE: 받지 않음(오면 무시)`
    - appKey 설명에 `/ 바이낸스 API Key`, secret 설명에 `/ 바이낸스 Secret Key`를 추가한다.
  - JSON 픽스처 2곳의 `"brokers"`에 `,"BINANCE":{"enabled":false,"allowedIp":"168.107.63.152"}`를 추가한다. `immutableEnumMap`은 키 누락을 거부하므로 추가하지 않으면 두 테스트가 역직렬화 단계에서 실패한다.
  - ArchUnit 2곳 `resideInAnyPackage(...)`에 `"com.kista.tradingcore.broker.domain.model.binance.."`를 추가하고, `@DisplayName`의 `kis/toss/upbit`을 `kis/toss/upbit/meritz/binance`로 바꾼다.
  - `TradingFixtures`에 위 두 팩토리를 추가한다. 주석: `// 기본 바이낸스 계좌 — 계좌번호 없음(API key가 중복 판정 키)`.
- [ ] **Step 4: 통과 확인**
```
./gradlew :shared:test --tests 'com.kista.shared.kernel.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :trading-core:test --tests 'com.kista.tradingcore.account.*' --tests '*TradingPolicyInternalControllerTest' --tests '*TradingPolicySettingsPersistenceAdapterTest' --tests '*TradingStatsServiceTest' --tests '*InvestmentPointsQueryServiceTest' --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :test --tests '*MetaControllerTest' --tests '*TradingPolicyHttpAdapterTest' --tests '*AdminSettings*' --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"
```
→ 모두 BUILD SUCCESSFUL. DB 테스트는 `docker compose up -d postgres`가 필요하다.

이 시점 배포 안전성: 정책 기본값이 비활성이라 BINANCE 계좌가 생길 수 없다. 그래서 `CryptoExchanges.of(BINANCE)`·`BrokerConnectionTesters.of(BINANCE)` 미구현이 런타임에 닿지 않는다. Task 3·5가 구현체를 채운다.
- [ ] **Step 5: 커밋**
```
git add shared/src/main/java/com/kista/shared/kernel/{Broker,TradingPolicySettings}.java shared/src/test/java/com/kista/shared/kernel/{TradingPolicySettingsTest,MarketVocabularyTest}.java trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/dto/AccountRequest.java trading-core/src/testFixtures/java/com/kista/tradingcore/support/TradingFixtures.java trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/web/TradingPolicyInternalControllerTest.java trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/policy/TradingPolicySettingsPersistenceAdapterTest.java trading-core/src/test/java/com/kista/tradingcore/account trading-core/src/test/java/com/kista/tradingcore/stats/application/service/{TradingStatsServiceTest,InvestmentPointsQueryServiceTest}.java trading-core/src/test/java/com/kista/tradingcore/architecture/TradingCoreTestVendorModelTest.java src/test/java/com/kista/api/admin/adapter/out/internal/TradingPolicyHttpAdapterTest.java src/test/java/com/kista/api/web/MetaControllerTest.java src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java
git commit -m "feat(kernel): 바이낸스 거래소 상수와 기본 비활성 정책을 추가한다"
```

---

### Task 2: 바이낸스 HTTP 기반 — Config·서명·오류 매핑·심볼·clientOrderId

**Files:**
- Create: `tc/broker/domain/model/binance/BinanceApiException.java`, `tc/broker/adapter/out/binance/{BinanceConfig,BinanceHttpClient,BinanceSymbolRegistry,BinanceClientOrderIds}.java`
- Create(Test): `tct/broker/adapter/out/binance/{BinanceHttpClientTest,BinanceSymbolRegistryTest,BinanceClientOrderIdsTest}.java`

**Interfaces:**
- Consumes: `BrokerApiException(vendorLabel, message, cause, Conflict, rejected)`, `BrokerCredentialException`, `BrokerRateLimitException`, `HttpClientTimeouts.jdkTimeouts(connectMs, readMs)`(업비트 Config 선례)
- Produces:
  - `public class BinanceApiException extends BrokerApiException` — `BinanceApiException(int status, Integer code, String message, Throwable cause, boolean rejected)`, `int status()`(I/O 0), `Integer code()`(바이낸스 오류 코드, 없으면 null). vendorLabel `"Binance"`, Conflict `NONE`.
  - `@Configuration class BinanceConfig` — `@Bean RestClient binanceRestClient()` = `RestClient.builder().requestFactory(HttpClientTimeouts.jdkTimeouts(3_000, 10_000)).build()`. baseUrl을 두지 않는다 — 서명된 절대 URI를 그대로 보내 템플릿 재인코딩을 피한다. 기본 빈 이름 `binanceConfig`는 stats `statsBinanceConfig`와 충돌하지 않는다.
  - `@Component class BinanceHttpClient`
    - 생성자 `@Autowired BinanceHttpClient(RestClient binanceRestClient, ObjectMapper objectMapper, @Value("${binance.base-url:https://api.binance.com}") String baseUrl)` + 테스트용 `(…, String baseUrl, Clock clock)`
    - `<T> T getPublic(String path, LinkedHashMap<String,String> params, ParameterizedTypeReference<T> type)`
    - `<T> T getSigned(String path, LinkedHashMap<String,String> params, BrokerAccountRef auth, ParameterizedTypeReference<T> type)`
    - `<T> T postSigned(String path, LinkedHashMap<String,String> params, BrokerAccountRef auth, ParameterizedTypeReference<T> type)`
    - `static String sign(String secretKey, String payload)` — HMAC-SHA256 소문자 hex(`HexFormat`)
    - `static String queryString(LinkedHashMap<String,String> params)` — `k=URLEncoder.encode(v, UTF_8)`를 `&`로 연결(퍼센트 인코딩한 문자열이 곧 서명 대상, 2026-01 -1022 규칙)
    - 서명 요청 URI = `URI.create(baseUrl + path + "?" + payload + "&signature=" + sign(secret, payload))`. payload는 params + `recvWindow=5000` + `timestamp=clock.millis()` 순서다. 헤더는 `X-MBX-APIKEY: auth.appKey()`.
    - 키·시크릿이 비면 서명 전에 `BrokerCredentialException`을 던진다(null 서명 방지 — 업비트 선례).
  - `@Component class BinanceSymbolRegistry`
    - `String symbolOf(StrategyTicker)` — `"BTCUSDT" // 심볼: BTC/USDT 현물`, `"ETHUSDT"`. 매핑 누락이면 `IllegalArgumentException("바이낸스 심볼 매핑 누락: " + t)`.
    - `String baseAssetOf(StrategyTicker)` = `ticker.name()`
    - `Optional<StrategyTicker> tickerOfAsset(String asset)`
    - `static final String QUOTE_ASSET = "USDT"`
  - `final class BinanceClientOrderIds`(package-private, static만)
    - `static String toWire(String uuid)` = `UUID.fromString(uuid).toString().replace("-", "")`(32자 — 형식이 다르면 IAE, 주문 ID가 아닌 값의 전송 방지)
    - `static String fromWire(String wire)` — 32자 hex면 8-4-4-4-12로 되돌린다. 아니면(바이낸스 자동 생성 ID 등) 그대로 둔다.
  - 오류 매핑 `translate(int status, Integer code)`는 위에서부터 첫 매치로 판정한다.
    - 1) 429·418 → `BrokerRateLimitException`
    - 2) code ∈ {-2014, -2015, -1022} → `BrokerCredentialException`(-2015는 키·IP·권한 구분 불가 — 편차 절)
    - 3) status ≥ 500 또는 code ∈ {-1006, -1007} → `BinanceApiException(rejected=false)`(문서: 실행 상태 불명 — 실패 취급 금지)
    - 4) 그 외 4xx → `BinanceApiException(rejected=true)`(-1013·-2010·-1100·-1021·-2013·-2026·403 WAF 포함)
    - I/O(`RestClientException`) → `BinanceApiException(0, null, …, false)`
    - 원문 바디는 `log.warn`에만 남긴다. -1021은 `log.error`에 `// ponytail:` 오프셋 보정 안내를 단다.

- [ ] **Step 1: 실패 테스트 작성**
```java
// BinanceHttpClientTest
@Test void signMatchesOfficialVector() {
    String payload = "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559";
    assertThat(BinanceHttpClient.sign("NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j", payload))
        .isEqualTo("c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71");
}
@Test void signedGetPutsParamsTimestampAndSignatureInQueryWithApiKeyHeader() {
    // Clock.fixed(Instant.ofEpochMilli(1499827319559L)) — 기대 URL을 리터럴로 고정
    server.expect(requestTo("https://api.binance.com/api/v3/account?omitZeroBalances=true&recvWindow=5000&timestamp=1499827319559&signature=" + BinanceHttpClient.sign("sk", "omitZeroBalances=true&recvWindow=5000&timestamp=1499827319559")))
          .andExpect(header("X-MBX-APIKEY", "ak")).andRespond(withSuccess("{}", APPLICATION_JSON));
}
@Test void signedPostSendsParamsInQueryWithEmptyBody() { /* method POST, content 없음 */ }
@Test void blankSecretRejectedBeforeSigning() { /* assertThatThrownBy(...).isExactlyInstanceOf(BrokerCredentialException.class); server.verify() 요청 0건 */ }
@ParameterizedTest @CsvSource({
    "400,-2015,CREDENTIAL", "401,-2014,CREDENTIAL", "400,-1022,CREDENTIAL",
    "429,-1003,RATE", "418,-1003,RATE",
    "500,,UNKNOWN", "503,,UNKNOWN", "400,-1006,UNKNOWN", "408,-1007,UNKNOWN",
    "400,-2010,REJECTED", "400,-1013,REJECTED", "400,-1021,REJECTED", "400,-2013,REJECTED", "403,,REJECTED"})
void errorMapping(int status, Integer code, String kind) {
    // body = code == null ? "<html>" : {"code":<code>,"msg":"x"}
    // CREDENTIAL: isExactlyInstanceOf(BrokerCredentialException), RATE: BrokerRateLimitException,
    // UNKNOWN: BinanceApiException && !isRejected() && BrokerApiException.isRejection(e)==false,
    // REJECTED: BinanceApiException && isRejected() && code()==code
}
@Test void ioFailureIsUnknown() { /* withException(new SocketTimeoutException()) → BinanceApiException, isRejected()==false, status()==0 */ }

// BinanceClientOrderIdsTest
@Test void roundTrip() {
    String id = "0192f0aa-1111-7222-8333-444455556666";
    assertThat(BinanceClientOrderIds.toWire(id)).isEqualTo("0192f0aa111172228333444455556666").hasSize(32);
    assertThat(BinanceClientOrderIds.fromWire("0192f0aa111172228333444455556666")).isEqualTo(id);
    assertThat(BinanceClientOrderIds.fromWire("web_abc")).isEqualTo("web_abc");
    assertThatIllegalArgumentException().isThrownBy(() -> BinanceClientOrderIds.toWire("not-a-uuid"));
}
// BinanceSymbolRegistryTest — 모든 tradableOn(CRYPTO_USDT) 종목이 매핑됨(UpbitMarketRegistryTest 패턴)
@Test void everyUsdtTickerHasSymbol() {
    Arrays.stream(StrategyTicker.values()).filter(t -> t.tradableOn(Market.CRYPTO_USDT))
        .forEach(t -> assertThat(registry.symbolOf(t)).isEqualTo(t.name() + "USDT"));
}
```
- [ ] **Step 2: 실패 확인**
```
./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.binance.*' 2>&1 | grep -E "FAILED|BUILD|error:"
```
→ 컴파일 실패
- [ ] **Step 3: 구현** — 위 시그니처대로 구현한다. 구조는 `UpbitHttpClient`를 따른다(`execute(action, Supplier)` + `translate` + `errorCode(body)` JSON 파싱 실패 시 null). `@Autowired`는 생성자가 2개라 필수다(Toss Redis Store 장애 교훈). HMAC은 JDK `Mac.getInstance("HmacSHA256")`를 쓴다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 → BUILD SUCCESSFUL
- [ ] **Step 5: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/binance trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/binance trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/binance
git commit -m "feat(broker): 바이낸스 HMAC 서명 클라이언트와 오류 코드 매핑을 추가한다"
```

---

### Task 3: `BinanceExchangeAdapter` 조회 — 잔고·현재가·완성 일봉·거래 규칙

**Files:**
- Create: `tc/broker/adapter/out/binance/BinanceExchangeAdapter.java`(조회 메서드 + 주문 메서드는 Task 4까지 `UnsupportedOperationException`. 같은 PR 안에서 Task 4가 바로 채운다 — 정책 비활성이라 미도달)
- Create(Test): `tct/broker/adapter/out/binance/BinanceExchangeAdapterTest.java`
- Modify(Test): `tct/broker/application/service/CryptoExchangesTest.java`(라우터가 BINANCE 포트를 찾는지 1케이스 — 기존 형태가 Mockito 목록이면 목록에 mock 추가)

**Interfaces:**
- Consumes: 선행 `CryptoExchangePort`, `CryptoBalance(quoteAvailable, quoteLocked, holdings)`, `CryptoCandle(kstDate, close)` + `latestCompletedKstDate`, `CryptoTradingRules(feeRate, FeeSide, minNotional, quantityStep)`, Task 2 클래스, `TimeZones.KST`
- Produces: `@Component class BinanceExchangeAdapter implements CryptoExchangePort`
  - 생성자: `@Autowired (BinanceHttpClient http, BinanceSymbolRegistry registry)` + 테스트용 `(…, Clock clock)`
  - `static final BigDecimal STANDARD_FEE_RATE = new BigDecimal("0.001"); // 현물 표준 taker 0.1% — RECEIVED_ASSET이라 커널 판정에 쓰이지 않음, 실제 수수료는 체결 내역`
  - `supports()` = `BINANCE`
  - `getBalance(ref)` = `GET /api/v3/account?omitZeroBalances=true`(서명)
    - `USDT` free/locked → quote
    - 지원 자산(BTC·ETH) → `Holding(free, locked, null)`. 평단 없음(거래소 미제공) → 포트폴리오는 원장 평단(Task 7).
    - 그 외 자산은 무시한다.
  - `getCurrentPrices(tickers)` = 종목별 `GET /api/v3/ticker/price?symbol=`(공개, 최대 2회). 빈 입력이면 `Map.of()`.
  - `getCompletedDailyCandles(ticker, count)` = `GET /api/v3/klines?symbol=&interval=1d&limit=count+1`(공개)
    - `count`는 1~999(`limit` ≤ 1000)이다. 범위 밖이면 IAE.
    - 응답은 배열의 배열 `[openTime, open, high, low, close(문자열), …]`이다(`List<List<JsonNode>>` 또는 `JsonNode`).
    - 완성 판정은 `openTime + 86_400_000 <= clock.millis()`이다.
    - `kstDate = Instant.ofEpochMilli(openTime).atZone(KST).toLocalDate()`(UTC 00:00 = KST 09:00이라 일자가 같다)
    - 오래된 순(응답 순서)으로 두고 끝에서 `count`개를 자른다.
  - `getTradingRules(ticker)` = `GET /api/v3/exchangeInfo?symbol=`(공개)
    - `symbols[0].status != "TRADING"` → `BinanceApiException(200, null, "Binance 거래 중단 상태: symbol=…, status=…", null, false)`(TRANSIENT — 틱마다 재시도, 50분 이후 미주문 기록)
    - `LOT_SIZE.stepSize` → `quantityStep`
    - `NOTIONAL.minNotional`(없으면 `MIN_NOTIONAL.minNotional`) → `minNotional`. 둘 다 없으면 `IllegalStateException`.
    - 반환 `new CryptoTradingRules(STANDARD_FEE_RATE, FeeSide.RECEIVED_ASSET, minNotional, stepSize)`
    - `// ponytail: 캐시 없음 — 성공 요청 weight 0·실행기 계좌 단위 메모, 계좌 수가 늘면 TTL 캐시` 주석을 단다.
  - 응답 record(package-private): `AccountRow(List<BalanceRow> balances)`, `BalanceRow(String asset, BigDecimal free, BigDecimal locked)`, `PriceRow(String symbol, BigDecimal price)`, `ExchangeInfoRow(List<SymbolRow> symbols)`, `SymbolRow(String symbol, String status, List<FilterRow> filters)`, `FilterRow(String filterType, BigDecimal stepSize, BigDecimal minNotional)`(Jackson 3 기본은 모르는 필드를 무시한다 — 업비트 테스트 선례)

- [ ] **Step 1: 실패 테스트 작성** — `MockRestServiceServer`, 고정 `Clock`(`2026-10-23T01:30:00Z` = KST 10:30), base `https://api.binance.com`
```java
@Test void balanceMapsUsdtAndSupportedAssetsWithoutAvgPrice() {
    // {"balances":[{"asset":"USDT","free":"100.12","locked":"5.00"},{"asset":"BTC","free":"0.00150000","locked":"0"},{"asset":"BNB","free":"1","locked":"0"}]}
    // → quoteAvailable 100.12, quoteLocked 5.00, holdings keys [BTC], BTC.available 0.0015, BTC.avgBuyPrice null
}
@Test void currentPricesPerSymbol() { /* ticker/price?symbol=BTCUSDT → {"symbol":"BTCUSDT","price":"65000.01"} → {BTC: 65000.01} */ }
@Test void completedCandlesDropInProgressAndUseKstStartDate() {
    // limit=3 요청(count 2), 응답 openTime 3개: 10-21 00:00Z, 10-22 00:00Z, 10-23 00:00Z(진행 중)
    // → [CryptoCandle(2026-10-21, c1), CryptoCandle(2026-10-22, c2)], 마지막 kstDate == CryptoCandle.latestCompletedKstDate(KST 10:30) == 10-22
}
@Test void tradingRulesFromExchangeInfo() {
    // status TRADING, LOT_SIZE stepSize "0.00001000", NOTIONAL minNotional "5.00000000"
    CryptoTradingRules r = adapter.getTradingRules(StrategyTicker.BTC);
    assertThat(r.feeSide()).isEqualTo(FeeSide.RECEIVED_ASSET);
    assertThat(r.feeRate()).isEqualByComparingTo("0.001");
    assertThat(r.minNotional()).isEqualByComparingTo("5");
    assertThat(r.quantityScale()).isEqualTo(5);
}
@Test void nonTradingSymbolIsUnknownFailure() { /* status "CANCEL_ONLY" → BinanceApiException, isRejected()==false */ }
@Test void candleCountOutOfRangeRejected() { /* 0, 1000 → IAE, 요청 없음 */ }
```
- [ ] **Step 2: 실패 확인**
```
./gradlew :trading-core:test --tests '*BinanceExchangeAdapterTest' --tests '*CryptoExchangesTest' 2>&1 | grep -E "FAILED|BUILD|error:"
```
→ 컴파일 실패
- [ ] **Step 3: 구현** — 위 Produces대로 구현한다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests '*TradingApplicationContextLoadTest'` → BUILD SUCCESSFUL. 컨텍스트 로드로 `binanceRestClient` 단일 빈 주입을 확인한다(`RestClient` 빈이 여럿이라 파라미터명이 빈 이름과 일치해야 한다).
- [ ] **Step 5: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/binance/BinanceExchangeAdapter.java trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/binance/BinanceExchangeAdapterTest.java trading-core/src/test/java/com/kista/tradingcore/broker/application/service/CryptoExchangesTest.java
git commit -m "feat(broker): 바이낸스 잔고·시세·완성 일봉·거래 규칙 조회를 구현한다"
```

---

### Task 4: 바이낸스 주문·조회·순수량 + 멱등 불변식

**Files:**
- Modify: `tc/broker/adapter/out/binance/BinanceExchangeAdapter.java`(주문·조회)
- Modify(Test): `tct/broker/adapter/out/binance/BinanceExchangeAdapterTest.java`, `tct/trading/application/service/{CryptoOrderSynchronizerTest,CryptoDcaRoundExecutorTest}.java`

**Interfaces:**
- Consumes: Task 2·3, 선행 `CryptoOrderResult(exchangeOrderId, clientOrderId, State, executedVolume, netExecutedVolume, avgPrice, paidFee, feeAsset)`
- Produces(`BinanceExchangeAdapter`):
  - `buyMarketByAmount(ref, ticker, quoteAmount, clientOrderId)` = `POST /api/v3/order`
    - 파라미터: `symbol`, `side=BUY`, `type=MARKET`, `quoteOrderQty=quoteAmount.stripTrailingZeros().toPlainString()`, `newClientOrderId=toWire(clientOrderId)`, `newOrderRespType=FULL`
    - `quoteAmount <= 0`이면 IAE(전송 전)
  - `sellMarketByVolume(ref, ticker, volume, clientOrderId)`
    - `qty = volume.setScale(getTradingRules(ticker).quantityScale(), RoundingMode.DOWN)`(LOT_SIZE step FLOOR — 보유 초과·-1013 방지). `<= 0`이면 IAE.
    - `POST /api/v3/order`: `side=SELL`, `type=MARKET`, `quantity=qty.toPlainString()`, `newClientOrderId`, `newOrderRespType=FULL`
  - `findOrderByExchangeOrderId(ref, ticker, id)` = `GET /api/v3/order?symbol=&orderId=id`
    - -2013 → empty
    - -2026(90일 지난 무체결 종료) → `Optional.of(archived(id))` = `CryptoOrderResult(id, null, CANCEL, 0, 0, null, 0, null)`. empty로 두면 PLACED가 `ifPresent` no-op로 영구 정체한다.
  - `findOrderByClientOrderId(ref, ticker, clientOrderId)` = `GET /api/v3/order?symbol=&origClientOrderId=toWire(clientOrderId)`
    - -2013 → empty
    - -2026 → empty(무체결 확정 → 동기화기 FAILED. 재전송하지 않으므로 안전)
  - 결과 조립 `toResult(OrderRow o, List<FillRow> fills, String baseAsset)`:
    - 상태:
      - `NEW`/`PENDING_NEW`/`PARTIALLY_FILLED`/`PENDING_CANCEL` → `WAIT`
      - `FILLED` → `DONE`
      - `CANCELED`/`EXPIRED`/`EXPIRED_IN_MATCH`/`REJECTED` → `CANCEL`
      - 그 외 → `BinanceApiException(rejected=false)`
    - `executedVolume = executedQty`
    - `avgPrice = executedQty > 0 ? cummulativeQuoteQty / executedQty (scale 8 HALF_UP, stripTrailingZeros) : null` — fills 가중 평균과 같다
    - `paidFee = Σ fills.commission`, `feeAsset = fills.isEmpty() ? null : fills.getFirst().commissionAsset()`. `// ponytail: 자산 혼합 수수료(BNB 부족 시 BNB+BTC)는 첫 자산만 표기 — 원장은 아래 base 차감으로 정확`
    - `netExecutedVolume = executedQty − Σ(commission | commissionAsset == baseAsset)`. SELL은 수수료가 USDT라 자연히 `net == executed`다. BNB 수수료도 `net == executed`다.
    - `clientOrderId = fromWire(o.clientOrderId())`, `exchangeOrderId = String.valueOf(o.orderId())`
  - 조회 경로의 체결 내역은 조건부로 가져온다. 상태가 `DONE`/`CANCEL`이고 `executedQty > 0`일 때만 `GET /api/v3/myTrades?symbol=&orderId=`(서명)를 호출한다. 동기화기는 닫힌 주문만 정산하므로 열린 주문 조회에 weight를 쓰지 않는다. 열린 상태는 fills가 비어 `net = executed`이고 미사용이다. 주문 경로는 FULL 응답의 `fills`를 쓴다.
  - 불일치 방어: 닫힌 주문에서 `Σ trades.qty ≠ executedQty`면 `BinanceApiException(rejected=false, "Binance 체결 내역 불일치")`을 던진다. 접수 직후 Memory→DB 지연으로 일부만 보이는 경우 순수량을 잘못 확정하지 않고 다음 틱에 재시도한다.
  - 응답 record: `OrderRow(String symbol, long orderId, String clientOrderId, String status, BigDecimal executedQty, BigDecimal cummulativeQuoteQty, List<FillRow> fills)`, `FillRow(BigDecimal price, BigDecimal qty, BigDecimal commission, String commissionAsset)`. myTrades 행도 같은 필드명(`price, qty, commission, commissionAsset`)이라 `FillRow`를 재사용한다.

- [ ] **Step 1: 실패 테스트 작성**
```java
// BinanceExchangeAdapterTest
@Test void buyUsesQuoteOrderQtyPlain() {
    // POST /api/v3/order?symbol=BTCUSDT&side=BUY&type=MARKET&quoteOrderQty=10.5&newClientOrderId=0192f0aa111172228333444455556666&newOrderRespType=FULL&recvWindow=5000&timestamp=…&signature=…
    // 응답 FULL: orderId 28, clientOrderId hex, status FILLED, executedQty "0.00016000", cummulativeQuoteQty "10.40000000",
    //   fills [{price "65000.00", qty "0.00016000", commission "0.00000016", commissionAsset "BTC"}]
    CryptoOrderResult r = adapter.buyMarketByAmount(ACCOUNT, BTC, new BigDecimal("10.50"), "0192f0aa-1111-7222-8333-444455556666");
    assertThat(r.exchangeOrderId()).isEqualTo("28");
    assertThat(r.clientOrderId()).isEqualTo("0192f0aa-1111-7222-8333-444455556666");
    assertThat(r.netExecutedVolume()).isEqualByComparingTo("0.00015984");
}
@Test void clientOrderIdWireFormIsSameOnPostAndGet() {
    // 같은 UUID로 buy → findOrderByClientOrderId. POST의 newClientOrderId 값 == GET의 origClientOrderId 값(queryParam 매처 둘 다 "0192f0aa111172228333444455556666")
}
@Test void filledBuyNetsBaseAssetCommissionFromMyTrades() {
    // GET order: status FILLED, executedQty "0.00016000", cummulativeQuoteQty "10.40000000"
    // GET myTrades?symbol=BTCUSDT&orderId=28: [{qty "0.00010000", commission "0.00000010", commissionAsset "BTC"},{qty "0.00006000", commission "0.00000005", commissionAsset "BTC"}]
    CryptoOrderResult r = adapter.findOrderByExchangeOrderId(ACCOUNT, BTC, "28").orElseThrow();
    assertThat(r.state()).isEqualTo(State.DONE);
    assertThat(r.executedVolume()).isEqualByComparingTo("0.00016");
    assertThat(r.netExecutedVolume()).isEqualByComparingTo("0.00015985");
    assertThat(r.avgPrice()).isEqualByComparingTo("65000");
    assertThat(r.paidFee()).isEqualByComparingTo("0.00000015");
    assertThat(r.feeAsset()).isEqualTo("BTC");
}
@Test void bnbCommissionDoesNotReduceNet() { /* commissionAsset BNB → net == executed, feeAsset "BNB" */ }
@Test void sellNetEqualsExecuted() { /* SELL 체결, commissionAsset USDT → net == executed */ }
@Test void expiredWithFillCountsAsClosedFill() { /* status EXPIRED, executedQty>0 → CANCEL, executedVolume>0, myTrades 호출됨 */ }
@Test void openOrderSkipsMyTrades() { /* status NEW → WAIT, myTrades 요청 0건(server.verify) */ }
@Test void myTradesShortOfExecutedThrows() { /* Σqty 0.0001 ≠ executedQty 0.00016 → BinanceApiException, !isRejected() */ }
@Test void sellFloorsToLotSizeStep() {
    // exchangeInfo stepSize 0.00001 → volume 0.000159999 → quantity=0.00015 전송
    // volume 0.000009 → IAE, 주문 요청 0건
}
@Test void lookup_minus2013_isEmpty() { /* 400 {"code":-2013,"msg":"Order does not exist."} → 두 조회 메서드 모두 Optional.empty() */ }
@Test void lookup_minus2026() {
    // exchangeOrderId 조회 → CANCEL·executedVolume 0
    // clientOrderId 조회 → empty
}
@Test void lookup_5xx_throwsNotEmpty() { /* 502 → assertThatThrownBy(findOrderByClientOrderId).isInstanceOf(BinanceApiException) — empty가 아님 */ }
@Test void lookup_minus1007_throwsUnknown() { /* 400 {"code":-1007} → 예외, BrokerApiException.isRejection(e)==false */ }
@Test void lookup_ioError_throws() { /* withException(IOException) → 예외 */ }
@Test void unknownStatusIsUnknownFailure() { /* status "FOO" → BinanceApiException, !isRejected() */ }
```
  - `CryptoOrderSynchronizerTest`: 이 클래스는 broker 밖이라 `StubBrokerApiException`을 쓴다.
    ```java
    @Test void submittingLookupFailureKeepsSubmittingAndNeverSends() {
        Order o = order(OrderStatus.SUBMITTING, TODAY, null);
        when(exchange.findOrderByClientOrderId(any(), eq(o.ticker()), eq(o.id().toString()))).thenThrow(new StubBrokerApiException("502"));
        run(o, 13);
        verify(orderPort, never()).markFailed(any());
        verify(orderPort, never()).markPlaced(any(), any());
        verify(exchange, never()).buyMarketByAmount(any(), any(), any(), any());
        verify(exchange, never()).sellMarketByVolume(any(), any(), any(), any());
    }
    @Test void submittingNotFoundFailsWithoutResend() { /* empty → markFailed 1회, buy/sell never */ }
    ```
  - `CryptoDcaRoundExecutorTest` 케이스(binance 계좌는 `TradingFixtures.binanceTradingAccount`, 규칙 stub은 테스트 로컬 상수 `BINANCE_LIKE = (0.001, RECEIVED_ASSET, 5, 0.00001)`):
    ```java
    @Test void binanceAccountBuysWithTwoDecimalUsdt() {
        account = TradingFixtures.binanceTradingAccount(UUID.randomUUID(), UUID.randomUUID());
        when(exchange.getTradingRules(any())).thenReturn(BINANCE_LIKE);
        // 잔고 USDT 100, BTC 가격 65000, 규칙 BUY AMOUNT 10.50 REJECT
        verify(exchange).buyMarketByAmount(any(), eq(StrategyTicker.BTC), eq(new BigDecimal("10.50")), anyString()); // scale 2 — 10 아님
    }
    @Test void transientBuyKeepsSubmittingAndNextTickDoesNotResend() {
        // 1틱: buy가 StubBrokerApiException("502")(rejected=false) → markFailed never, markSubmitting 1회
        // 2틱(같은 시각): orderPort.findByStrategyId가 그 주문을 SUBMITTING으로 돌려줌 → buyMarketByAmount 총 1회(times(1))
    }
    ```
- [ ] **Step 2: 실패 확인**
```
./gradlew :trading-core:test --tests '*BinanceExchangeAdapterTest' --tests '*CryptoOrderSynchronizerTest' --tests '*CryptoDcaRoundExecutorTest' 2>&1 | grep -E "FAILED|BUILD|error:"
```
→ 어댑터 케이스 FAIL(`UnsupportedOperationException`). 동기화기·실행기 신규 케이스는 현재 코드가 이미 불변식을 지키면 PASS할 수 있다. 이 경우 잠금 테스트이므로 변이 확인을 한다. 동기화기 `catch` 블록을 잠시 `markFailed`로 바꿔 `submittingLookupFailure…`가 FAIL하는지 확인하고 되돌린다. 실행기는 TRANSIENT 분기를 잠시 `markFailed`로 바꿔 확인한다.
- [ ] **Step 3: 구현** — Produces대로 구현한다. 동기화기·실행기 main 코드는 바꾸지 않는다(선행 계획 계약으로 충분).
- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.kista.tradingcore.broker.*'` → BUILD SUCCESSFUL
- [ ] **Step 5: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/binance/BinanceExchangeAdapter.java trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/binance/BinanceExchangeAdapterTest.java trading-core/src/test/java/com/kista/tradingcore/trading/application/service/{CryptoOrderSynchronizerTest,CryptoDcaRoundExecutorTest}.java
git commit -m "feat(broker): 바이낸스 시장가 주문·조회와 받은 자산 수수료 순수량을 구현하고 재전송 금지 불변식을 잠근다"
```

---

### Task 5: 연결 테스트 `BinanceAuthApi` + 출금 권한 거부 오류 코드

**Files:**
- Create: `tc/broker/adapter/out/binance/BinanceAuthApi.java`, `tc/broker/domain/model/BrokerWithdrawalEnabledException.java`
- Modify: `shared/src/main/java/com/kista/shared/platform/web/ErrorCode.java`(마지막에 상수), `tc/web/TradingExceptionHandler.java:62-64`(매핑 1줄)
- Create(Test): `tct/broker/adapter/out/binance/BinanceAuthApiTest.java`
- Modify(Test): `tct/web/TradingExceptionHandlerTest.java`, `src/test/java/com/kista/api/web/ErrorCodeOpenApiCustomizerTest.java`(값 집합을 리터럴로 단언하는 경우만)

**Interfaces:**
- Consumes: `BrokerConnectionTestPort`, `BrokerAccountVerification(brokerAccountCode, apiKeyExpiresAt)`, Task 2 `BinanceHttpClient.getSigned`
- Produces:
  - `public class BrokerWithdrawalEnabledException extends BrokerCredentialException` — 메시지 `"출금 권한이 켜진 API 키는 등록할 수 없습니다."`
  - `ErrorCode.BROKER_WITHDRAWAL_ENABLED // 422 거래소 API 키 출금 권한 켜짐 (바이낸스 enableWithdrawals)`
  - `TradingExceptionHandler`: `Map.entry(BrokerWithdrawalEnabledException.class, new Mapping(HttpStatus.UNPROCESSABLE_ENTITY, "Broker Withdrawal Enabled", ErrorCode.BROKER_WITHDRAWAL_ENABLED))`. `resolve()`가 계층 하위부터 찾으므로 상위 `BrokerCredentialException`보다 먼저 매칭된다.
  - `@Component class BinanceAuthApi implements BrokerConnectionTestPort`
    - `supports()=BINANCE`
    - `verifyCredentials(appKey, secret)` → `checkRestrictions(...)`
    - `verifyAccount(appKey, secret, accountNo)` → `checkRestrictions(...)` + `new BrokerAccountVerification(null, null)`(accountNo 무시, 만료일 없음)
  - `checkRestrictions` = `GET /sapi/v1/account/apiRestrictions`(서명) → `ApiRestrictionsRow(boolean ipRestrict, boolean enableReading, boolean enableSpotAndMarginTrading, boolean enableWithdrawals)`. 판정 순서:
    - 1) `enableWithdrawals` → `BrokerWithdrawalEnabledException`(출금은 IP 제한이 있어야 켤 수 있어 1번이 먼저)
    - 2) `!ipRestrict` → `BrokerIpNotAllowedException`(UI 문구 "허용 IP가 등록되지 않았습니다…"가 그대로 맞다)
    - 3) `!enableSpotAndMarginTrading || !enableReading` → `BrokerPermissionDeniedException`
    - 서버 IP가 허용 목록에 없거나 키·시크릿이 틀리면 이 호출 자체가 -2015/-1022 → `BrokerCredentialException`(Task 2 매핑)

- [ ] **Step 1: 실패 테스트 작성**
```java
// BinanceAuthApiTest (UpbitAuthApiTest 패턴, MockRestServiceServer)
@Test void validKeyReturnsNullExpiry() {
    // {"ipRestrict":true,"enableReading":true,"enableSpotAndMarginTrading":true,"enableWithdrawals":false,...}
    assertThat(api.verifyAccount("ak", "sk", "ignored")).isEqualTo(new BrokerAccountVerification(null, null));
}
@Test void withdrawalEnabledIsRejected() { /* enableWithdrawals true → isExactlyInstanceOf(BrokerWithdrawalEnabledException) */ }
@Test void noIpRestrictionIsRejected() { /* ipRestrict false → BrokerIpNotAllowedException */ }
@Test void tradingDisabledIsRejected() { /* enableSpotAndMarginTrading false → BrokerPermissionDeniedException */ }
@Test void verifyCredentialsAppliesSameRestrictionChecks() { /* enableWithdrawals true → verifyCredentials도 BrokerWithdrawalEnabledException */ }
@Test void invalidKeyIsCredentialFailure() { /* 400 {"code":-2015} → isExactlyInstanceOf(BrokerCredentialException) */ }
@Test void supportsBinance() { assertThat(api.supports()).isEqualTo(Broker.BINANCE); }
// TradingExceptionHandlerTest
@Test void withdrawalEnabledMapsTo422WithCode() { /* status 422, $.code "BROKER_WITHDRAWAL_ENABLED", $.detail "출금 권한이 켜진 API 키는 등록할 수 없습니다." */ }
```
- [ ] **Step 2: 실패 확인**
```
./gradlew :trading-core:test --tests '*BinanceAuthApiTest' --tests '*TradingExceptionHandlerTest' 2>&1 | grep -E "FAILED|BUILD|error:"
```
→ 컴파일 실패
- [ ] **Step 3: 구현** — Produces대로 구현한다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 + `--tests 'com.kista.tradingcore.account.*' --tests '*TradingApplicationContextLoadTest'`, 그리고 `./gradlew :test --tests '*ErrorCodeOpenApiCustomizerTest' --tests '*GlobalExceptionHandlerTest'` → BUILD SUCCESSFUL. `BrokerConnectionTesters`가 BINANCE를 찾는지는 컨텍스트 로드 + 기존 라우터 테스트로 확인한다.
- [ ] **Step 5: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/broker/adapter/out/binance/BinanceAuthApi.java trading-core/src/main/java/com/kista/tradingcore/broker/domain/model/BrokerWithdrawalEnabledException.java shared/src/main/java/com/kista/shared/platform/web/ErrorCode.java trading-core/src/main/java/com/kista/tradingcore/web/TradingExceptionHandler.java trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/binance/BinanceAuthApiTest.java trading-core/src/test/java/com/kista/tradingcore/web/TradingExceptionHandlerTest.java [수정한 경우 ErrorCodeOpenApiCustomizerTest]
git commit -m "feat(broker): 바이낸스 키 권한 연결 테스트를 추가하고 출금 권한이 켜진 키를 거부한다"
```

---

### Task 6: 원장 원가 기준 잠금 (수수료 제외 = 순수량 × 체결 평균가)

**Files:**
- Modify(Test): `tct/trading/application/service/CryptoLedgerRecorderIT.java`

**Interfaces:**
- Consumes: 선행 Task 6 `CryptoOrderSynchronizer.settle` → `ledgerRecorder.recordFill(ctx, order, r.netExecutedVolume(), r.avgPrice())`(선행 계획 `ledgerUsesNetExecutedVolume`이 전달을 잠근다), `CryptoLedgerRecorder.recordFill`(main 변경 없음 — 금액 = `volume × avgPrice`이고 volume이 순수량이므로 원가 = 순수량 × 평균가)
- Produces: 없음

- [ ] **Step 1: 테스트 작성**
```java
@Test void 원가는_순수량_곱하기_체결평균가_수수료_제외() {
    // 초기 원장 0, BUY 주문, recordFill(ctx, order, 0.00015985(순수량), 65000)
    // → 최신 cycle_position holdings 0.00015985, avgPrice 65000(수수료 포함 실지출 10.40/0.00015985 = 65061.0… 가 아님)
    //   orders.filled_quantity 0.00015985, filled_price 65000
}
```
- [ ] **Step 2: 실행** `./gradlew :trading-core:integration --tests '*CryptoLedgerRecorderIT' 2>&1 | grep -E "FAILED|BUILD"`(postgres 필요) → PASS 기대(현 코드가 이미 규칙을 지킨다 — 잠금 테스트). 변이 확인: `recordFill`의 금액식을 잠시 `volume.multiply(avgPrice).multiply(new BigDecimal("1.001"))`로 바꿔 FAIL을 확인하고 되돌린다.
- [ ] **Step 3: 커밋**
```
git add trading-core/src/test/java/com/kista/tradingcore/trading/application/service/CryptoLedgerRecorderIT.java
git commit -m "test(trading): 코인 원장 원가가 수수료를 제외한 순수량 기준임을 잠근다"
```

---

### Task 7: API 계약 expand — 포트폴리오 중립 필드·원장 평단, 브로커 메타 금액 규칙, SSE 통화

**Files:**
- Modify: `tc/stats/application/service/CryptoPortfolioService.java`, `tc/stats/adapter/in/web/dto/CryptoPortfolioResponse.java`
- Modify: `shared/src/main/java/com/kista/shared/contract/notify/TradeEventMessage.java`, `tc/notify/adapter/out/gateway/{CryptoTradeNotifier,TradingReportNotifier}.java`
- Modify: `src/main/java/com/kista/api/web/dto/BrokerMeta.java`, `src/main/java/com/kista/api/admin/adapter/in/web/dto/AdminTradeResponse.java`(통화 — 관리자 거래 목록은 계좌 broker를 응답에 싣지 않아 UI가 통화를 알 길이 없다. 현재 UI는 `findTicker(ticker).market`으로 판정해 BTC면 무조건 KRW다)
- Modify(Test): `src/test/java/com/kista/api/admin/adapter/in/web/AdminTradeControllerTest.java`, `tct/stats/application/service/CryptoPortfolioServiceTest.java`, `tct/stats/adapter/in/web/CryptoPortfolioControllerTest.java`(있으면 — JSON 필드 단언), `shared/src/test/java/com/kista/shared/contract/notify/TradeEventMessageTest.java`, `src/test/java/com/kista/api/notify/adapter/out/sse/TradeEventMessageSerializationTest.java`, `tct/notify/adapter/out/gateway/{CryptoTradeNotifierTest,TradingReportNotifierTest,RedisTradeEventPublisherTest}.java`, `src/test/java/com/kista/api/notify/adapter/out/sse/{RedisTradeEventSubscriberTest,TradeSseEmitterRegistryTest}.java`(팩토리 인자 기계적 수정), `src/test/java/com/kista/api/web/MetaControllerTest.java`

**Interfaces:**
- Consumes: 선행 `CryptoPortfolio(currency, cash, holdings)`·`Holding(..., evalAmount)`·`totalAsset()`, `CryptoOrderFilledEvent.currency()`, `Market.minOrderAmount()`/`amountScale()`, trading `StrategyPort.findByAccountIdAndTicker(UUID, StrategyTicker)`·`StrategyCyclePort.findLatestByStrategyId(UUID)`·`CyclePositionPort.findLatestByCycleIds(Collection<UUID>)`(stats가 이미 import하는 trading 공개 포트)
- Produces:
  - `CryptoPortfolioService` — 필드 `StrategyPort strategyPort`, `StrategyCyclePort strategyCyclePort`, `CyclePositionPort cyclePositionPort`를 추가한다. 보유 평단은 거래소 값을 쓰고, `null`이면 원장 평단을 쓴다(`private BigDecimal ledgerAvgPrice(UUID accountId, StrategyTicker ticker)` — 그 계좌·종목 전략의 최신 사이클의 최신 포지션 `avgPrice`, 없으면 null). 브로커 분기 없이 중립 규칙이다(업비트는 거래소 평단이 있어 조회하지 않는다).
  - `CryptoPortfolioResponse(String currency, BigDecimal cash, List<CryptoHoldingResponse> holdings, BigDecimal totalAsset, BigDecimal krwCash, BigDecimal totalAssetKrw)` — 신규 `currency`(Currency name)·`cash`·`totalAsset`. `krwCash`·`totalAssetKrw`는 같은 값이고 `@Schema(deprecated = true, description = "… — cash/totalAsset로 대체, kista-ui 전환 후 제거")`.
  - `CryptoHoldingResponse(String ticker, BigDecimal quantity, BigDecimal avgBuyPrice, BigDecimal currentPrice, BigDecimal evalAmount, BigDecimal evalAmountKrw)` — `avgBuyPrice`를 `nullable = true`로 표시한다(원장도 없으면 null). `evalAmountKrw`는 deprecated(같은 값). 설명의 "(KRW)"는 "(계좌 통화 = currency)"로 바꾼다. 모든 BigDecimal은 기존처럼 `PlainDecimalSerializer`다.
  - `TradeEventMessage(Kind, ticker, quantity, price, amount, time, accountNickname, message, accountId, strategyId, Currency currency)` — 마지막 컴포넌트 `currency`(price·amount의 통화, null = 구버전·INFO/FAIL → UI는 USD로 해석). 팩토리는 `buy(…, UUID strategyId, Currency currency)`/`sell(…)`이다. 호출부는 둘이다.
    - `CryptoTradeNotifier`: `e.currency()`
    - `TradingReportNotifier`: `Currency.USD`(주식 체결 리포트 — US_STOCK 견적 통화)
  - `BrokerMeta(code, label, description, market, currency, String minOrderAmount, int amountScale)` — `minOrderAmount = b.getMarket().minOrderAmount().toPlainString()`(US_STOCK `"0"` = 하한 없음, CRYPTO_KRW `"5000"`, CRYPTO_USDT `"5"`), `amountScale = b.getMarket().amountScale()`. 별도 시장 메타 목록은 만들지 않는다 — `MetaBundle.markets`는 finance `Market`이라 겹치고, UI는 계좌의 broker 코드로 이 값을 찾는다.
  - `AdminTradeResponse` 마지막 컴포넌트 `String currency` — `@Schema(description = "price·filledPrice 통화 (Currency name, 계좌를 찾지 못하면 null)", nullable = true)`. 값은 `account != null ? account.broker().getCurrency().name() : null`(`AccountSummaryResponse.broker` 기존 필드 — 추가 조회 없음).
  - 전략 상세·DCA 이력·사이클 성과는 응답 변경이 필요 없다. 화면이 이미 `accountId`(전략 상세·DCA 이력) 또는 계좌 `broker`(`CycleRows` — `brokerCurrency(broker)`)를 가지고 있어 `brokers[].currency`로 파생한다(kista-ui 인계 참고).

- [ ] **Step 1: 실패 테스트 작성**
```java
// CryptoPortfolioServiceTest
@Test void binanceUsesUsdtScaleAndLedgerAvg() {
    Account a = TradingFixtures.binanceAccount(ACCOUNT_ID, USER_ID);
    // 잔고 USDT 100.125 가용 + 0 잠금, BTC 0.00015985(avgBuyPrice null), 현재가 65000.555
    // strategyPort.findByAccountIdAndTicker(ACCOUNT_ID, BTC) → [전략], findLatestByStrategyId → 사이클, findLatestByCycleIds → avgPrice 65000
    CryptoPortfolio p = service.getCryptoPortfolio(ACCOUNT_ID, USER_ID);
    assertThat(p.currency()).isEqualTo(Currency.USDT);
    assertThat(p.holdings().getFirst().avgBuyPrice()).isEqualByComparingTo("65000");
    assertThat(p.holdings().getFirst().evalAmount()).isEqualTo(new BigDecimal("10.39")); // 0.00015985×65000.555=10.3903… → scale 2 HALF_UP
}
@Test void exchangeAvgPriceWinsAndLedgerIsNotQueried() { /* 업비트 avgBuyPrice 150000000 → verifyNoInteractions(strategyPort) */ }
@Test void noStrategyLeavesAvgNull() { /* findByAccountIdAndTicker → [] → avgBuyPrice null */ }
// CryptoPortfolioResponse 직렬화(컨트롤러 테스트 또는 DTO 단위)
//   $.currency "USDT", $.cash "100.125"(plain), $.totalAsset, $.holdings[0].evalAmount, 그리고 $.krwCash·$.totalAssetKrw·$.holdings[0].evalAmountKrw가 같은 값으로 유지
// TradeEventMessageTest / TradeEventMessageSerializationTest
@Test void buyCarriesCurrency() { assertThat(TradeEventMessage.buy("BTC", q, 65000.0, 10.4, "바낸", aid, sid, Currency.USDT).currency()).isEqualTo(Currency.USDT); }
@Test void legacyJsonWithoutCurrencyDeserializesAsNull() { /* currency 키 없는 JSON → currency()==null (Redis 재기동 직후 구 메시지) */ }
@Test void serializesCurrencyName() { /* $.currency == "USDT" */ }
// CryptoTradeNotifierTest — USDT 이벤트 → realtimeNotificationPort.notifyTrade 메시지 currency USDT
// TradingReportNotifierTest — 주식 리포트 메시지 currency USD
// MetaControllerTest
.andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].minOrderAmount").value(Matchers.contains("5")))
.andExpect(jsonPath("$.brokers[?(@.code=='BINANCE')].amountScale").value(Matchers.contains(2)))
.andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].minOrderAmount").value(Matchers.contains("5000")))
.andExpect(jsonPath("$.brokers[?(@.code=='UPBIT')].amountScale").value(Matchers.contains(0)))
// AdminTradeControllerTest — 계좌 요약 stub의 broker BINANCE → $[0].currency "USDT", 계좌 맵에 없음 → $[0].currency null
```
- [ ] **Step 2: 실패 확인**
```
./gradlew :shared:test --tests '*TradeEventMessageTest' 2>&1 | grep -E "FAILED|BUILD|error:"
./gradlew :trading-core:test --tests '*CryptoPortfolio*' --tests '*CryptoTradeNotifierTest' --tests '*TradingReportNotifierTest' 2>&1 | grep -E "FAILED|BUILD|error:"
```
→ 컴파일 실패
- [ ] **Step 3: 구현**
  - Produces대로 구현한다.
  - `CryptoPortfolioService`의 원장 조회는 `BrokerCallGuard.wrap` 밖에서 한다(DB, HTTP 아님).
  - 배포 순서는 무관하다. 구 kista-api가 새 `currency` 필드를 받아도 무시된다(Jackson 3 + Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false` — `RedisTradeEventSubscriber`의 주입 `ObjectMapper`). 확인을 위해 `grep -rn "FAIL_ON_UNKNOWN_PROPERTIES" src/main shared/src/main trading-core/src/main`이 0건(재활성화 없음)인지 본다. 구 kista-trading 메시지는 `currency` 없음 → null이다.
- [ ] **Step 4: 통과 확인**
```
./gradlew :shared:test --tests 'com.kista.shared.contract.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.*' --tests 'com.kista.tradingcore.notify.*' 2>&1 | grep -E "FAILED|BUILD"
./gradlew :test --tests 'com.kista.api.notify.*' --tests '*MetaControllerTest' --tests '*AdminTradeControllerTest' --tests '*InternalApiContractTest' 2>&1 | grep -E "FAILED|BUILD"
```
→ BUILD SUCCESSFUL
- [ ] **Step 5: 커밋**
```
git add trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CryptoPortfolioService.java trading-core/src/main/java/com/kista/tradingcore/stats/adapter/in/web/dto/CryptoPortfolioResponse.java shared/src/main/java/com/kista/shared/contract/notify/TradeEventMessage.java trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/{CryptoTradeNotifier,TradingReportNotifier}.java src/main/java/com/kista/api/web/dto/BrokerMeta.java src/main/java/com/kista/api/admin/adapter/in/web/dto/AdminTradeResponse.java [수정한 테스트 경로들]
git commit -m "feat(stats): 코인 포트폴리오에 통화 중립 필드와 원장 평단을 추가하고 메타·실시간 알림에 통화 규칙을 싣는다"
```

---

### Task 8: 테스트넷 통합 테스트 + 문서

**Files:**
- Create(Test): `tct/broker/adapter/out/binance/BinanceTestnetIT.java`
- Create(Doc): `docs/agents/binance-api.md`
- Modify(Doc):
  - `CLAUDE.md` "참고 문서"에 `- 바이낸스 API 작업: \`docs/agents/binance-api.md\`` 한 줄
  - `README.md` 8·13·14·15행(코인 = 업비트 KRW·바이낸스 USDT, 증권사·거래소 목록에 "바이낸스(USDT 마켓 코인, DCA 전용, 관리자 설정 기본 비활성)")
  - `docs/agents/modules/shared/kernel.md`(Broker 목록·`SERVER_EGRESS_IP`)
  - `docs/agents/modules/tradingcore/broker.md`(adapter/out 목록에 `binance/` 줄, domain.model 벤더 목록에 `binance`, `BrokerWithdrawalEnabledException`)
  - `docs/agents/modules/tradingcore/account.md`(키 전용 브로커에 BINANCE, 연결 테스트 판정 3단계)
  - `docs/agents/modules/tradingcore/trading.md`(코인 DCA 절 — 거래소 2개, 바이낸스 clientOrderId 재전송 금지·-2013만 empty)
  - `docs/agents/modules/tradingcore/stats.md`(CryptoPortfolio — 원장 평단 대체·통화 중립 응답)
  - `docs/agents/modules/tradingcore/notify.md`(SSE 통화)
  - `docs/agents/modules/api/admin.md`(기본 비활성 브로커 목록)
  - `docs/agents/constraints.md`(ErrorCode 목록 서술이 있으면 `BROKER_WITHDRAWAL_ENABLED`)
- Modify: `src/test/resources/architecture/flows.yml`
  - 55-56행: "10분마다 (업비트·바이낸스, 24/7)"
  - 687·707-711행: 연결 테스트 서술에 바이낸스 apiRestrictions, `code:`에 `BinanceAuthApi#verifyCredentials`, fail에 `BROKER_WITHDRAWAL_ENABLED`
  - 717·727·738·745·763행: "메리츠·업비트" → "메리츠·업비트·바이낸스", 738 `code:`에 `BinanceAuthApi#verifyAccount`
  - 828-830행: 코인 포트폴리오 통화
  - 1623-1639행: 제목 "코인 DCA 실행 (업비트·바이낸스)", 동기화 서술에 "바이낸스는 clientOrderId(주문 UUID 32자 hex)·symbol로 조회, -2013만 없음 판정, 체결 시 myTrades로 받은 자산 수수료를 뺀 순수량을 원장에"
  - 거래소 호출 `code:`에 `BinanceExchangeAdapter#findOrderByClientOrderId` 등 실제 메서드명

**Interfaces:** Consumes Task 2~5 클래스(테스트넷은 Spring 컨텍스트 없이 직접 조립) / Produces 없음

- [ ] **Step 1: 테스트넷 IT 작성** — `@Tag("integration")`, `@EnabledIfEnvironmentVariable(named = "BINANCE_TESTNET_API_KEY", matches = ".+")`. 조립은 `new BinanceHttpClient(RestClients.withTimeouts(3_000, 10_000), JsonMapper.builder().build(), "https://testnet.binance.vision")` + `new BinanceExchangeAdapter(http, new BinanceSymbolRegistry())`이고, 계정은 `new BrokerAccountRef(UUID.randomUUID(), key, secret, null, null, Broker.BINANCE)`이다. 각 케이스는 결과(`exchangeOrderId`·상태·순수량·수수료 자산·소요 시간)를 stdout에 출력한다.
  - `buyByQuoteAmountThenQueryByClientOrderId`: 새 UUID로 `buyMarketByAmount(BTC, 10.50)` → 2초 대기 후 `findOrderByClientOrderId` → `DONE`, `executedVolume > 0`, `netExecutedVolume <= executedVolume`, `clientOrderId()`가 보낸 UUID와 같다.
  - `queryByExchangeOrderIdFetchesMyTradesFee`: 위 주문 `findOrderByExchangeOrderId` → `feeAsset` non-null, `paidFee >= 0`.
  - `sellFlooredQuantity`: 방금 산 순수량으로 `sellMarketByVolume` → `DONE`.
  - `unknownClientOrderIdIsEmpty`: 새 UUID 조회 → `Optional.empty()`(-2013 실측).
  - `hyphenatedClientOrderIdAcceptance`: 하이픈 포함 36자 UUID를 `newClientOrderId`로 보내는 원시 POST(`http.postSigned` 직접 호출, 금액 10.50)의 수락 여부를 출력한다. 단언하지 않는다 — 형식 규칙 실측 기록용. 32자 hex 결정은 이 결과와 무관하게 유지한다.
  - `balanceAndRules`: `getBalance`의 USDT > 0, `getTradingRules(BTC).quantityScale() == 5`, `getCompletedDailyCandles(BTC, 3)`의 마지막 일자 == `CryptoCandle.latestCompletedKstDate(now)`.
  - 테스트넷은 `/sapi`(apiRestrictions)를 지원하지 않는다 — 연결 테스트는 실계좌 미검증 항목이다.
- [ ] **Step 2: 실행(키가 있을 때)** — `BINANCE_TESTNET_API_KEY=… BINANCE_TESTNET_SECRET_KEY=… ./gradlew :trading-core:integration --tests '*BinanceTestnetIT' 2>&1 | grep -E "FAILED|BUILD|PASSED|SKIPPED"`. 키가 없으면 SKIPPED이고 미검증 항목으로 보고한다. 실행 결과(36자 수락 여부·-2013 응답·myTrades 지연 유무)를 `binance-api.md` "실측" 절에 날짜와 함께 기록한다.
- [ ] **Step 3: 문서 작성** — `binance-api.md`는 `meritz-api.md` 구성을 따른다.
  - 호스트·서명(퍼센트 인코딩 payload = 서명 대상, query 전용)
  - 엔드포인트 표(`/api/v3/account`·`ticker/price`·`klines`·`exchangeInfo`·`order`·`myTrades`·`/sapi/v1/account/apiRestrictions`) — weight·필수 파라미터
  - 오류 코드 → 예외 매핑 표(Task 2 `translate` 순서 그대로, -2015·-1021 결정 근거)
  - 주문 상태 → `CryptoOrderResult.State` 표
  - clientOrderId 규칙(열린 주문 중 고유·체결 후 재사용 허용 → 재전송 금지, 32자 hex)
  - 순수량 규칙(base asset 수수료만 차감, myTrades Σqty == executedQty 검증)
  - 거래 규칙(LOT_SIZE·NOTIONAL, 캐시 없음 근거, `CANCEL_ONLY`)
  - 테스트넷 사용법·한계(`/sapi` 없음)
  - 실측 기록(2026-10-23 운영 서버 ping·exchangeInfo·kline openTime)
- [ ] **Step 4: 확인** — `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"` → BUILD SUCCESSFUL(flows.yml `code:` 참조 존재 확인)
- [ ] **Step 5: 커밋**
```
git add trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/binance/BinanceTestnetIT.java docs/agents/binance-api.md CLAUDE.md README.md docs/agents/modules docs/agents/constraints.md src/test/resources/architecture/flows.yml
git commit -m "docs: 바이낸스 API 런북·모듈 문서·아키텍처 맵을 갱신하고 테스트넷 통합 테스트를 추가한다"
```

---

### Task 9: 최종 검증

**Files:** Modify: 이 계획 문서 제목 아래 `> 상태: 완료 (YYYY-MM-DD, 커밋 <해시>)`

- [ ] **Step 1: 잔존 분기 게이트** — `grep -rn "BINANCE" --include='*.java' trading-core/src/main shared/src/main src/main`. 허용 목록은 `Broker.java` 상수, `TradingPolicySettings` 기본값, `broker/adapter/out/binance/*`(`supports()`)뿐이다. 공통 경로(trading·stats·notify·account 서비스)의 `== Broker.BINANCE` 분기는 결함이다.
- [ ] **Step 2: 전체 스위트 1회** (`docker compose up -d postgres redis`)
```
./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"
grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml trading-core/build/test-results/test/TEST-*.xml shared/build/test-results/test/TEST-*.xml 2>/dev/null
```
./gradlew integration 2>&1 | grep -E "FAILED|BUILD|tests completed"   # @Tag("integration") — 기본 test에서 제외되므로 CryptoLedgerRecorderIT(Review Focus 2)·DB IT를 여기서 1회. 루트 build.gradle.kts의 integration 태스크(서브프로젝트 포함)
```
→ 둘 다 BUILD SUCCESSFUL, 실패 XML 0건. `BinanceTestnetIT`는 키 환경변수가 없으면 SKIPPED. `HexagonalArchitectureTest`·`ModulithArchitectureTest`(api·trading-core)·`TradingCoreTestVendorModelTest`·`InternalApiContractTest`·`ArchitectureMapTest`·컨텍스트 로드 2종을 포함한다.
- [ ] **Step 3: 커밋 전 검토자 검수**(전역 규칙) — 리뷰어 서브에이전트에 Review Focus 5항목과 "설계와의 차이"를 넘긴다. 발견한 결함은 수정·재검증한다.
- [ ] **Step 4: 커밋** — `git add docs/superpowers/plans/2026-10-23-binance-dca-phase2-binance.md` → `docs: 바이낸스 Phase 2 계획 완료를 기록한다`
- [ ] **Step 5: 배포 메모(사용자 보고)**
  - 정책 기본 비활성이다. 관리자가 켜기 전에 kista-ui 바이낸스 등록 UI가 배포돼야 한다.
  - `CryptoOrderFilledEvent`의 `@JsonAlias("amountKrw")`는 유지한다(선행 계획 도입 후 한 릴리스가 지나지 않았다). 제거는 kista-ui 전환 후 contract 릴리스에서 한다.
  - **kista-api와 kista-trading은 같은 릴리스로 함께 배포한다.** root `TradingPolicyHttpAdapter`가 trading-core 정책 JSON을 `TradingPolicySettings`로 역직렬화하고, `immutableEnumMap`은 누락 키를 거부한다. 이미지가 어긋나는 동안에는 두 방향 모두 깨진다.
    - 신 root + 구 trading-core: `BINANCE` 키 없음 → 거부
    - 구 root + 신 trading-core: 모르는 enum 키 `BINANCE` → 역직렬화 거부

    교체 창 동안 관리자 정책 GET/PUT(과 그 정책을 쓰는 root 경로)이 일시 실패할 수 있다. 착수 시 MERITZ 롤아웃이 이 창을 어떻게 넘겼는지 `git log`로 확인해 같은 방식을 따른다. SSE `currency`·포트폴리오·메타·관리자 거래 필드는 추가 필드라 순서 무관하다.

---

## 자체 검토 (writing-plans)
- 스펙 커버리지는 다음과 같다.
  - 5절 어댑터: Task 2·3·4. 서명·-1021·키 유형(HMAC만 — Ed25519/RSA 미지원은 Task 2 서명 경로 하나로 자연히 성립)·quoteOrderQty·FULL·LOT_SIZE FLOOR·-2013·myTrades·상태 매핑·잔고·시세·일봉·exchangeInfo·오류 매핑을 다룬다. 레이트 헤더 로깅만 생략했다(편차 절).
  - 6절 멱등: Task 4.
  - 7절: 원장 Task 6(+선행 Task 6), 포트폴리오 Task 7, 알림 문구는 선행 Task 5(`Currency.format`·브로커 라벨)라 추가 작업이 없다. 키 만료는 `isApiKeyExpiring=false`(Task 1 단언). SSE 통화는 Task 7.
  - 8절: Task 1·5.
  - 10절 백엔드 절반: Task 7(포트폴리오 필드·브로커 메타 최소 금액·자릿수).
- 타입 일관성: 선행 계획 인터페이스를 수정 없이 소비한다. 신규 공개 타입은 `BinanceApiException`, `BrokerWithdrawalEnabledException`, `ErrorCode.BROKER_WITHDRAWAL_ENABLED`, `TradeEventMessage.currency`, `BrokerMeta.minOrderAmount/amountScale`, `AdminTradeResponse.currency`, `CryptoPortfolioResponse` 신규 필드다. 설계 10절의 통화 표기 대상 화면(전략 상세·DCA 이력·사이클 성과·관리자 거래)은 각각 응답 추가 또는 기존 필드 파생 경로를 인계에 적었다. 벤더 타입은 broker 밖에 노출하지 않는다(Task 1 ArchUnit).
- 태스크 간 같은 파일 이중 수정: `BinanceExchangeAdapter`(Task 3 조회 → Task 4 주문, 메서드 단위 분리), `MetaControllerTest`(Task 1 브로커 기본 → Task 7 메타 필드, 단언 추가만).

## 선행 계획(Phase 0+1) 보정 필요 사항
1. **`CryptoDcaRoundExecutorTest`·`CryptoOrderSynchronizerTest`가 KIS 계좌로 코인 경로를 돈다.** `TradingFixtures.tradingAccount` = `TradingAccount.from(kisAccount(...))`이다(`CryptoDcaRoundExecutorTest:85`, `CryptoOrderSynchronizerTest:70`). 선행 Task 4가 실행기 시장을 `ctx.account().broker().getMarket()`(→ US_STOCK)로 바꾸면 크립토 판정이 주식 경로로 바뀐다. 그러면 기존 단언(`49975`·scale 0·`"주문 금액 4999 < 최소 5000"`)이 깨진다. 선행 Task 5의 신규 단언 `currency == KRW`도 KIS(USD) 때문에 실패한다. 보정: 선행 Task 4 Step 1에 `TradingFixtures.upbitTradingAccount(UUID, UUID)` = `TradingAccount.from(upbitAccount(...))`를 추가하고, 두 테스트의 setUp/헬퍼 계좌를 이것으로 바꾼다(기계적 수정 — 단언 불변 원칙과 충돌 없음).
2. **`MarketVocabularyTest.brokerCapabilityFlags`의 `containsExactly(Broker.UPBIT)`**(isCryptoExchange)는 이 계획 Task 1이 `UPBIT, BINANCE`로 갱신한다. 선행 계획 쪽 수정은 불필요하고, 의도된 갱신으로 기록만 한다.
3. 포트·커널 시그니처 변경은 없다. `getTradingRules(ticker)`에 계좌 인자는 필요 없다(편차 절 — `RECEIVED_ASSET`에서 `feeRate` 미사용).

## kista-ui 세션 인계
착수 전 `../kista-ui/CLAUDE.md`를 Read하고, 이 계획 배포 후 `gen:types`로 `shared/lib/api-types.ts`를 재생성한다. 순서는 DCA 다중 규칙 UI·메리츠 UI 뒤다.

- **브로커 메타 `GET /api/meta` → `brokers[]`**
  - 신규 항목 `{code:"BINANCE", label:"바이낸스", description:"바이낸스", market:"CRYPTO_USDT", currency:"USDT", minOrderAmount:"5", amountScale:2}`
  - 모든 브로커에 신규 필드 `minOrderAmount`(string, 견적 통화 최소 주문 금액 — US_STOCK `"0"` = 하한 없음, UPBIT `"5000"`, BINANCE `"5"`)와 `amountScale`(number — KRW 0, USD·USDT 2)이 붙는다.
  - DCA 폼은 하드코딩 `MIN_CRYPTO_ORDER_KRW`·정수 검사 대신 계좌 broker의 이 두 값을 쓴다. 금액 단위 라벨은 `currency`(USDT는 "USDT", 소수 2자리 입력 허용)에서 온다. **두 값은 broker의 `market`이 코인 시장(`CRYPTO_KRW`/`CRYPTO_USDT`)일 때만 적용한다.** 서버는 주식 DCA 금액 자릿수를 강제하지 않으므로 US_STOCK의 `amountScale` 2는 표시용이다.
- **통화 파생(응답 변경 없음)**: 전략 상세(`DcaStrategyDetail.tsx:39`)·DCA 실행 이력(`DcaExecutionHistory.tsx:25`)은 `isCrypto ? 'KRW' : 'USD'`를 `accountId` → 계좌 `broker` → `brokers[].currency`로 바꾼다. 사이클 성과(`CycleRows.tsx:24-27`)는 이미 쓰는 `brokerCurrency(broker)`가 USDT를 돌려주도록 확장하고 `fmtMoney(…, currency)`로 통일한다.
- **관리자 거래 목록 `AdminTradeResponse`**: 신규 `currency`(`price`·`filledPrice` 통화, 계좌 못 찾으면 null)가 추가된다. `AdminTradesTable.tsx:57`의 `isCryptoMarket(findTicker(ticker).market) ? 'KRW' : 'USD'` 판정을 `trade.currency ?? 'USD'`로 바꾼다(BTC가 두 시장을 공유하므로 종목으로는 통화를 정할 수 없다).
- **티커 메타**: 선행 계획에서 `tickers[].markets`(BTC·ETH = `["CRYPTO_KRW","CRYPTO_USDT"]`)가 이미 추가됐다. `market`(단수)은 다음 릴리스에 제거된다. 코인 종목 판정은 `markets`에 계좌 시장이 있는지로 한다.
- **계좌 등록 `POST /api/accounts`**
  - `broker:"BINANCE"`, `appKey` = 바이낸스 API Key, `secretKey` = Secret Key, `accountNo`는 보내지 않는다(와도 무시). 응답 `accountNo`/`accountNoMasked`는 null이고 `apiKeyExpiresAt`도 null이다(만료 알림 없음).
  - 연결 테스트 동일 경로. 422 `code`별 안내:
    - `BROKER_WITHDRAWAL_ENABLED`(신규) — "출금 권한을 끈 키를 사용하세요"
    - `BROKER_IP_NOT_ALLOWED` — IP 제한 미설정. 허용 IP는 관리자 정책 `brokers.BINANCE.allowedIp`(기본 `168.107.63.152`)
    - `BROKER_PERMISSION_DENIED` — "현물 거래(Enable Spot & Margin Trading)·읽기 권한을 켜세요"
    - `BROKER_CREDENTIAL_INVALID` — 키·시크릿 오류이거나 허용 IP 목록에 서버 IP가 없음(바이낸스는 구분하지 않으므로 둘 다 안내)
  - 키 안내 화면: HMAC 키만 지원(Ed25519·RSA 자가 생성 키 불가), 필요 권한 = 읽기 + 현물 거래, 출금 금지, IP 제한 필수 + 허용 IP.
- **ErrorCode 스키마**: `BROKER_WITHDRAWAL_ENABLED`가 추가된다(`brokerCredentials.ts` 문구 매핑 필요).
- **코인 포트폴리오 `GET /api/accounts/{id}/crypto-portfolio`**
  - 신규 필드: `currency`(`"KRW"`|`"USDT"`), `cash`, `totalAsset`, `holdings[].evalAmount`. 값은 JSON 숫자이고 지수 표기가 없다(`PlainDecimalSerializer.writeNumber(toPlainString())`, 기존과 동일).
  - `krwCash`·`totalAssetKrw`·`holdings[].evalAmountKrw`는 deprecated이고 같은 값을 담는다. UI 전환 후 다음 릴리스에서 제거한다.
  - `holdings[].avgBuyPrice`는 nullable이다(바이낸스는 원장 평단, 전략 없으면 null).
  - 모든 금액·가격 표기는 `currency` 기준이다(`fmtMoney` USDT 지원, `Math.round` 제거).
- **실시간 SSE `TradeEventMessage`**: 신규 `currency`(`"USD"`|`"KRW"`|`"USDT"`|null)는 `price`·`amount`의 통화다. null(구버전 메시지·INFO/FAIL)은 USD로 해석한다. `TradeToast`의 `$${fmtUsd(price)}` 하드코딩을 통화 포맷으로 바꾼다(업비트 KRW도 현재 `$`로 표시되는 기존 결함 해소).
- **관리자 설정**: `brokers.BINANCE`(enabled 기본 false, allowedIp `168.107.63.152`)가 정책 응답에 추가된다. 구 UI가 BINANCE 키를 보내지 않아도 서버가 현재 값을 유지한다(`AdminSettingsController` 병합).
- **그 외 표기**: "업비트 일봉" 문구를 거래소 중립으로 바꾸고, 바이낸스 브로커 카드·아이콘을 추가한다.

## 실계좌·테스트넷 미검증 항목
- **연결 테스트(`/sapi/v1/account/apiRestrictions`)**: 테스트넷이 `/sapi`를 지원하지 않는다. 실계좌 키로 정상·출금 켜짐·IP 미제한·거래 권한 꺼짐 4가지를 확인해야 한다(키 발급·권한 토글은 사용자 작업). 단위 테스트는 공식 응답 필드명 기준이다.
- **실계좌 실주문**: 매수 10.50 USDT·매도·BNB 수수료 차감 계정의 `commissionAsset` 실측. 테스트넷은 수수료 체계가 실계좌와 다를 수 있다(BNB 할인 미적용 가능).
- **`newClientOrderId` 형식 규칙**: 공식 문서에 명시가 없다. 테스트넷 IT `hyphenatedClientOrderIdAcceptance`로 36자 수락 여부를 기록한다. 32자 hex는 키가 없으면 미검증이다(Task 8 Step 2).
- **myTrades 반영 지연(Memory→Database)**: 동기화기는 10분 뒤 조회하고, 불일치면 예외 → 다음 틱 재시도로 방어한다. 실제 지연 시간은 미실측이다.
- **-1021 빈도**: OCI 시각 오차 실측이 없다. 운영 로그에 -1021이 나오면 `/api/v3/time` 오프셋 보정을 추가한다.
- **운영 서버에서 서명 요청 허용 여부**: 공개 `ping` 200만 확인했다(2026-10-23). 서명 요청·IP 허용 목록 동작은 실키 등록 시 확인한다.
- **등록 후 출금 권한을 켠 경우**: 연결 테스트는 등록·키 교체 시점에만 돈다. 이후 권한 변경 감시는 범위 밖이다(필요하면 일일 점검 스케쥴러 — 향후 제안).

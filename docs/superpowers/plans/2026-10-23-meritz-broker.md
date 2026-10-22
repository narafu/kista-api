# 메리츠증권 브로커 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Broker.MERITZ`를 추가해 메리츠증권 계좌로 kista 미국주식 전략이 KIS와 동일하게 돌게 한다(관리자 기본 비활성).

**Architecture:** `broker.adapter.out.meritz`에 KIS와 대칭인 Http/Auth/Price/Trading/Order API + `MeritzBrokerAdapter`(`BrokerCapabilitiesPort`)를 둔다. 토큰은 기존 `broker_tokens`(account_id PK)를 공용 코디네이터로 재사용하고, 앱키별 호출 간격 게이트는 KIS와 공용 유틸로 추출한다. 계좌는 UPBIT와 같은 "계좌번호 없음·appKey가 식별자" 경로.

**Tech Stack:** Java 25, Spring Boot 4 `RestClient`(JDK HttpClient), Jackson, JUnit5 + Mockito + `MockRestServiceServer`, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-10-23-meritz-broker-design.md` (실측 API 사실·엔드포인트·필드명은 spec이 SSOT — 구현자는 반드시 같이 읽는다)

## Global Constraints

- Base URL `https://openapi.imeritz.com:9443`, 설정 키 `meritz.base-url`(trading-core `application.yml`).
- 모든 요청 헤더: `authorization: Bearer <token>`, `mac_address: 0242AC110002`(고정 상수 — 값 검증 없음 실측).
- 계좌 파라미터(`acno`·`asno`·`acnt_pwd`)를 요청에 절대 넣지 않는다.
- 응답 판정은 HTTP 상태코드가 아니라 본문: 성공 `rsp_cd` ∈ {`0000`,`0001`,`5620`,`5762`,`5766`,`5820`,`5822`}; 자료 유무는 `data`로.
- 주문·취소 POST는 자동 재시도 금지(예외: 토큰 무효 `EGW00121`/`EGW00123` 재발급 1회, 경고 `warn_cls_code` `1`/`3` → `warn_cnfr_yn="Y"` 1회 재전송).
- `oauth2/revoke` 호출 금지.
- 메리츠 날짜(`date`, `oder_date`, `oder_sta_date`/`oder_fin_date`, `from`/`to`)는 US 거래일 — KST↔US는 `UsTradeDates`만, 사용 클래스는 `HexagonalArchitectureTest.usTradeDates_must_only_be_used_by_allowlisted_adapters`에 FQCN 추가.
- 벤더 타입은 `com.kista.tradingcore.broker.domain.model.meritz`(internal), 어댑터는 `com.kista.tradingcore.broker.adapter.out.meritz`(package-private 기본).
- 주석 규칙: `//` 인라인만, Javadoc 금지. 4-space, record, 생성자 주입.
- 커밋: 한글 Conventional Commit, author `narafu <narafu@kakao.com>`, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. push 금지.
- 테스트에서 기대 날짜는 리터럴(`LocalDate.of(...)`, `"20261022"`)로 쓴다 — 테스트 클래스가 `UsTradeDates`를 import하면 ArchUnit allowlist 규칙이 깨진다.
- 테스트 명령은 반드시 서브프로젝트 지정(`./gradlew :trading-core:test --tests '...'`), 출력은 `2>&1 | grep -E "FAILED|BUILD|Tests? (run|failed)|ERROR"`로 필터. 전체 스위트는 마지막 Task에서 1회.

## 접수 시각 확인 (workflow.md·scheduler-time-table.md 대조)

kista 접수는 모두 정규장 안이다 — 마감 배치 04:30 KST(DST, 비DST 05:30) AT_CLOSE 접수(사용자 수동 주문도 같은 시각에 일반 주문 API로 접수됨 — 실측 693건), 개장 배치 AT_OPEN은 `waitUntilMarketOpen()` 이후 접수, MOO 개장 전 접수는 `supportsOpenAuctionOrders()==true`(KIS)만이라 메리츠는 개장 후. 수동 실행도 PLANNED만 만들고 접수는 개장 후. 따라서 예약주문 API 불필요. `SubmittingOrderAwaiter` 60s 예산 대비 메리츠 최악 경로(게이트 20s + 읽기 10s + 토큰 재시도 10s) ≈ 40s로 안쪽.

## Review Focus

1. **토큰 만료 계산** — 만료 전 재발급은 같은 토큰과 같은 `expires_date`(KST `yyyy-MM-dd HH:mm:ss`, 최초 발급 기준)를 돌려준다(실측). `expires_in`은 상대값이 아닐 수 있어 신뢰하지 않는다. `expires_in`으로 만료를 잡으면 캐시가 실제보다 최대 12h 늦게 만료돼 `EGW00123` 폭주. → Task 3 `MeritzAuthApiTest.expiry_uses_expires_date_not_expires_in`.
2. **HTTP 500 + 성공 본문 / HTTP 200 + 오류 본문** — RestClient 기본 `retrieve()`는 500에서 예외를 던져 본문 판정을 건너뛴다. → Task 3 `MeritzHttpClientTest.business_error_on_http_500_is_rejected_with_rsp_cd`, `success_body_on_http_500_is_success`.
3. **취소 대상이 이미 만료(장 마감)·체결** — 당일 목록 `oder_able_qty=0`에서 취소 API를 부르면 업무 오류로 사용자에게 실패가 보인다. → Task 6 `cancel_terminal_order_throws_conflict_without_calling_cancel_api`.
4. **수량 문자열 소수 표기** — `"417.0000000000"`을 `Integer.parseInt`하면 NumberFormatException으로 잔고 조회 전체가 실패. → Task 5 `holdings_parse_decimal_string_quantity`.
5. **연속조회 키 공백** — `tr_cont_key`를 trim하면 같은 페이지 무한 반복/다른 구간. → Task 3 `paging_sends_tr_cont_key_verbatim_and_stops_on_5766`(최대 페이지 상한 50도 검증).

---

### Task 1: Broker.MERITZ 상수·정책 기본 비활성·계좌 등록 분기

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/kernel/Broker.java` (상수 추가)
- Modify: `shared/src/main/java/com/kista/shared/kernel/TradingPolicySettings.java:30` (UPBIT 오버라이드 다음 줄)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/service/AccountService.java:57`(register 분기), `:120`(updateCredentials 중복 키 검사)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/domain/model/Account.java:28-30` (`dedupeKeyOf`)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/adapter/in/web/dto/AccountRequest.java:13,20` (`@Schema` 문구·allowableValues)
- Modify: `src/test/java/com/kista/api/architecture/HexagonalArchitectureTest.java:158`, `trading-core/src/test/java/com/kista/tradingcore/architecture/TradingCoreTestVendorModelTest.java:34` (`...domain.model.meritz..` 추가)
- Test: `shared/src/test/java/com/kista/shared/kernel/TradingPolicySettingsTest.java`, `trading-core/src/test/java/.../account/application/service/AccountServiceTest.java`, `Account` 도메인 테스트(기존 dedupeKey 테스트 파일)

**Interfaces:**
- Produces: `Broker.MERITZ("메리츠증권", "메리츠", Market.US_STOCK, Currency.USD)`; `supportsOpenAuctionOrders()`는 변경 없음(false).

- [ ] **Step 1: 실패 테스트 작성**
  - `TradingPolicySettingsTest.defaults_disable_meritz`: `defaults().brokers().get(Broker.MERITZ)` → `enabled()==false`, `allowedIp()==null`.
  - `AccountServiceTest.register_meritz_ignores_account_no_and_requires_keys`: MERITZ + accountNo `"123"` → 저장된 `Account.accountNo()==null`, `verifyAccount(appKey, secret, null)` 호출 검증; appKey 빈값 → `IllegalArgumentException("API 키와 시크릿은 필수입니다")`.
  - `AccountServiceTest.update_credentials_meritz_rejects_key_used_by_other_account`: UPBIT 기존 케이스와 같은 형태로 MERITZ에서 `DuplicateAccountException`.
  - Account 테스트 `dedupeKeyOf_meritz_is_app_key`: `Account.dedupeKeyOf(MERITZ, null, "K")` == `"K"`.
- [ ] **Step 2: 실패 확인** — `./gradlew :shared:test --tests '*TradingPolicySettingsTest' :trading-core:test --tests '*AccountServiceTest' --tests '*AccountTest'` → 컴파일 실패(MERITZ 없음).
- [ ] **Step 3: 구현** — UPBIT 분기를 `broker == Broker.UPBIT || broker == Broker.MERITZ`로 확장(주석 "업비트·메리츠는 앱키에 계좌가 묶여 계좌번호를 받지 않는다"), `dedupeKeyOf`도 동일, updateCredentials 중복 검사 동일. `defaults()`에 `brokers.put(Broker.MERITZ, new BrokerSettings(false, null)); // 베타 API·모의계좌 없음 — 운영자가 본인 계좌 검증 후 켠다`. allowableValues에 `"MERITZ"`, accountNo 설명에 `MERITZ: 받지 않음(오면 무시)`. ArchUnit 두 곳에 meritz 패키지 추가.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS. 추가로 `./gradlew :test --tests 'com.kista.api.architecture.*' --tests '*MetaControllerTest' --tests '*AdminSettings*'` PASS(`Broker.values()` 순회 테스트 회귀 확인).
- [ ] **Step 5: 커밋** — `feat(account): 메리츠증권 증권사 상수를 추가하고 계좌번호 없이 앱키로 등록한다`

### Task 2: 토큰 코디네이터·호출 간격 게이트 공용화 (리팩토링)

**Files:**
- Move/Rename: `broker/adapter/out/kis/KisTokenCoordinator.java` → `broker/adapter/out/internal/DbTokenCoordinator.java` (public class, `@Component`, 동작 불변). 클래스 주석에서 KIS 전용 문구를 "account_id 키 `broker_tokens` 캐시 — 재발급이 이전 토큰을 무효화하지 않는 브로커(KIS·메리츠) 공용"으로.
- Create: `broker/adapter/out/internal/CallIntervalGate.java`
- Modify: `kis/KisAuthApi.java`(주입 타입), `kis/KisHttpClient.java`(게이트 위임)
- Move test: `KisTokenCoordinatorTest` → `internal/DbTokenCoordinatorTest`
- Test: `internal/CallIntervalGateTest.java`

**Interfaces:**
- Produces:
  - `public class DbTokenCoordinator implements TokenCoordinator` (생성자 `DbTokenCoordinator(BrokerTokenCachePort)`)
  - `public final class CallIntervalGate { public CallIntervalGate(long minIntervalMillis, long maxWaitMillis); public void await(String key, Function<String, RuntimeException> onReject); }` — `onReject` 인자는 사람용 사유 문자열(대기 초과 ms 또는 인터럽트). 인터럽트 시 interrupt 플래그 복원 후 `onReject` 예외 throw. 알고리즘은 현 `KisHttpClient.awaitAccountSlot`(CAS 슬롯 예약·거부 시 슬롯 미커밋) 그대로 이동.

- [ ] **Step 1: 실패 테스트** — `CallIntervalGateTest`: `second_call_waits_at_least_interval`(간격 100ms, 두 번째 await 경과 ≥ 90ms), `rejects_without_committing_slot_when_wait_exceeds_max`(간격 1000/최대 500 → 두 번째 호출 즉시 onReject 예외, 세 번째도 동일하게 즉시 — 대기열 누적 없음), `different_keys_do_not_wait`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*CallIntervalGateTest'` → 컴파일 실패.
- [ ] **Step 3: 구현** — 게이트 추출, `KisHttpClient`는 `new CallIntervalGate(350, 20_000)` 필드로 `gate.await(account.appKey(), reason -> KisApiException.rejected("KIS 계좌 호출 대기열 과다/인터럽트 — 요청 거부: " + reason + " accountId=" + account.id(), null))`. 코디네이터 이동·개명.
- [ ] **Step 4: 통과 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.*'` PASS (KIS 기존 테스트 전부 무변경 통과).
- [ ] **Step 5: 커밋** — `refactor(broker): 계좌 토큰 DB 코디네이터와 앱키 호출 간격 게이트를 공용 internal로 추출한다`

### Task 3: 메리츠 HTTP 기반 — Config·예외·인증·응답 판정

**Files:**
- Create: `broker/domain/model/meritz/MeritzApiException.java`
- Create: `broker/adapter/out/meritz/MeritzConfig.java`, `MeritzAuthApi.java`, `MeritzHttpClient.java`, `MeritzResponse.java`
- Modify: `trading-core/src/main/resources/application.yml` (`meritz:\n  base-url: https://openapi.imeritz.com:9443  # 앱키마다 계좌 1개 — 서버 공용 시크릿 없음`)
- Test: `meritz/MeritzHttpClientTest.java`, `meritz/MeritzAuthApiTest.java`

**Interfaces:**
- Consumes: `DbTokenCoordinator`, `CallIntervalGate`, `TokenCoordinator.IssuedToken/RecoveredToken` (Task 2).
- Produces:
  - `public class MeritzApiException extends BrokerApiException` — `MeritzApiException(String rspCd, String message, Throwable cause, Conflict conflict, boolean rejected)`, `static MeritzApiException rejected(String rspCd, String message)`, `String rspCd()`. vendorLabel `"Meritz"`.
  - `@Bean RestClient meritzRestClient(@Value("${meritz.base-url:https://openapi.imeritz.com:9443}") String)` — `HttpClientTimeouts.jdkTimeouts(3_000, 10_000)`.
  - `record MeritzResponse(String rspCd, String rspMsg, String rspSubMsg, String trCont, String trContKey, JsonNode data)` + `boolean success()`(성공 코드 집합), `boolean hasNextPage()`(`"5762".equals(rspCd) && trContKey != null && !trContKey.isEmpty()`).
  - `MeritzHttpClient`: `MeritzResponse get(String path, BrokerAccountRef account, MultiValueMap<String,String> params)`; `<T> List<T> getAllPages(String path, BrokerAccountRef account, MultiValueMap<String,String> params, Class<T> rowType)`; `MeritzResponse post(String path, BrokerAccountRef account, Object body)`; `<T> T rows(MeritzResponse, Class<T>)`/`<T> List<T> rowList(MeritzResponse, Class<T>)` 변환 헬퍼(빈 data → 빈 리스트).
  - `MeritzAuthApi implements BrokerConnectionTestPort`: `String getToken(BrokerAccountRef)`, `TokenCoordinator.RecoveredToken recoverToken(BrokerAccountRef, String rejected)`, `supports()=MERITZ`, `verifyCredentials`, `verifyAccount → new BrokerAccountVerification(null, null)`.

판정 규칙(MeritzHttpClient, 모든 요청 공통 — 요청은 `.exchange(...)`로 상태와 무관하게 본문을 읽는다):
1. 토큰 확보 실패 → `MeritzApiException.rejected`(자격증명/권한 예외는 그대로 전파).
2. `gate.await(appKey, …)` (간격 110ms, 최대 대기 20s) → 거부 시 `rejected`.
3. I/O `RestClientException` → `new MeritzApiException(null, …, NONE, false)`(결과 불명).
4. 본문 JSON 파싱 불가/빈 본문 → 결과 불명(rejected=false).
5. `rsp_cd` ∈ {`EGW00121`,`EGW00123`} → `recoverToken` 후 1회 재시도(GET·POST 모두), 재시도도 같은 코드면 `rejected`.
6. `EGW00200` → GET만 1s·2s 지수 백오프(+≤200ms 지터) 최대 2회, 소진/POST면 `BrokerRateLimitException`.
7. `EGW00103` → `BrokerCredentialException`; `0760`·`EGW00202` → `BrokerPermissionDeniedException`.
8. 그 외 비성공 `rsp_cd` → `MeritzApiException.rejected(rspCd, rspMsg + " / " + rspSubMsg)`(사용자 노출 금지 — 로그용 메시지).
9. 성공 → `MeritzResponse` 반환.

`getAllPages`: 첫 요청 후 `hasNextPage()`이면 `tr_cont=1`, `tr_cont_key=<원문 그대로>`를 쿼리스트링에 추가해 반복, 최대 50페이지(초과 시 warn 로그 후 누적분 반환).

토큰 발급(MeritzAuthApi, RestClient 직접 — 순환 회피): `POST /oauth2/token` form `grant_type=client_credentials`, `client_id`, `client_secret`, `scope=login`. 응답 `access_token`, `expires_date`(KST `yyyy-MM-dd HH:mm:ss`) → `IssuedToken(token, Duration.between(now KST, expires_date).getSeconds())`. 실패 본문 `rsp_cd`/`error_code` 중 `EGW00103` 포함 → `BrokerCredentialException`, 그 외 → `MeritzApiException`. `verifyCredentials`: 발급 성공 + `accountId!=null`이면 `BrokerTokenCachePort.saveToken`. `verifyAccount`: 발급 + `GET /accounts/v1/me/overseas/currencies` 성공 확인(실패 코드 7번 규칙으로 422 계열) → `(null, null)`.

- [ ] **Step 1: 실패 테스트** (`MockRestServiceServer.bindTo(RestClient.builder().baseUrl(BASE))`)
  - `business_error_on_http_500_is_rejected_with_rsp_cd`: 500 + `{"rsp_cd":"7936","rsp_msg":"해당데이터가 없습니다.","rsp_sub_msg":"조회시작일"}` → `MeritzApiException`, `isRejected()`, `rspCd()=="7936"`.
  - `success_body_on_http_500_is_success`: 500 + `{"rsp_cd":"5766","data":[...]}` → 정상 반환.
  - `io_error_is_unknown_outcome`: `withException(new IOException())` → `isRejected()==false`.
  - `expired_token_reissues_once_then_succeeds`: 1차 `EGW00123` → `recoverToken` 호출 → 2차 성공. POST에도 동일.
  - `rate_limit_retries_get_but_not_post`: GET 2회 `EGW00200` 후 성공; POST 1회 `EGW00200` → `BrokerRateLimitException`, 요청 1건만 발생.
  - `permission_denied_maps_to_broker_permission_exception`: `0760` → `BrokerPermissionDeniedException`.
  - `paging_sends_tr_cont_key_verbatim_and_stops_on_5766`: 1페이지 `5762` + `tr_cont_key:"ABC   "` → 2번째 요청 쿼리 `tr_cont=1&tr_cont_key=ABC%20%20%20`, 2페이지 `5766` → 행 합산; 무한 `5762` → 50회 후 중단.
  - `headers_include_bearer_and_mac_address`.
  - `MeritzAuthApiTest.expiry_uses_expires_date_not_expires_in`: `expires_in:43200`, `expires_date`=now+10분 → `IssuedToken.expiresInSeconds` ≈ 600(±5).
  - `MeritzAuthApiTest.invalid_client_id_is_credential_exception`: 403 `{"error_code":"EGW00103",...}` → `BrokerCredentialException`.
  - `MeritzAuthApiTest.verify_account_returns_null_codes`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.meritz.*'` → 컴파일 실패.
- [ ] **Step 3: 구현** (위 Interfaces·판정 규칙대로. 숫자형/문자열 혼재는 행 record 필드를 모두 `String`으로 선언해 Jackson 강제 변환을 쓰고, `MeritzResponse`에 `static BigDecimal decimal(String)`(null/blank → null), `static int wholeQuantity(String)`(`new BigDecimal(s).setScale(0, RoundingMode.DOWN).intValueExact()`) 둔다.)
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.
- [ ] **Step 5: 커밋** — `feat(broker): 메리츠 HTTP 클라이언트에 본문 기반 응답 판정·토큰 재발급·호출 간격을 추가한다`

### Task 4: 시세 — 거래소 매핑·현재가·전일종가·확정종가

**Files:**
- Create: `meritz/MeritzExchangeRegistry.java`, `meritz/MeritzPriceApi.java`
- Modify: `HexagonalArchitectureTest.java` UsTradeDates allowlist에 `...adapter.out.meritz.MeritzPriceApi`
- Test: `meritz/MeritzExchangeRegistryTest.java`, `meritz/MeritzPriceApiTest.java`

**Interfaces:**
- Consumes: `MeritzHttpClient.get` (Task 3).
- Produces:
  - `MeritzExchangeRegistry`: `String vendorId(StrategyTicker)`(주문·잔고 `ovrs_exch_vndr_idno`), `String marketCode(StrategyTicker)`(시세 `mrkt_cls_code`). 매핑: TQQQ=`0537`/`OQ`; SOXL·USD·MAGX·UPRO·QLD=`0066`/`AX`. 미등록(코인 포함) → `NoSuchElementException`.
  - `MeritzPriceApi`: `BigDecimal getPrice(StrategyTicker, BrokerAccountRef)`, `PriceSnapshot getPriceSnapshot(...)`, `BigDecimal getPrevClose(...)`, `BigDecimal getClosingPrice(StrategyTicker, LocalDate kstTradeDate, BrokerAccountRef)` + 각 다건(`Map<StrategyTicker, …>`, 종목 순회·입력 순서 보존 `LinkedHashMap`).

현재가: `GET /market/v1/overseas/prices` `mrkt_div_code=OV, mrkt_cls_code, iscd=ticker.name(), dely_rltm_cls_code=0` → `prpr`, `prdy_clpr`. `prpr` 없음/0 → `MeritzApiException`(null 반환 금지). 확정 종가: `GET /market/v1/overseas/candles/days` `mod_stpr_cls_code=1, from=to=UsTradeDates.toUsTradeDate(kst) BASIC_ISO` → 봉 `date`가 기대 US일과 **정확히 일치**할 때만 `prpr`; 빈 응답·불일치·예외면 warn 후 `getPrice`로 fallback(`KisPriceApi.getClosingPrice:126-134` 계약과 동일).

- [ ] **Step 1: 실패 테스트** — `registry_maps_all_us_tickers`(6종 전부 vendorId/marketCode, BTC → `NoSuchElementException`); `price_reads_prpr_and_prev_close`(`{"rsp_cd":"0000","data":{"prpr":"82.1100","prdy_clpr":"103.2400"}}` → 82.1100/103.2400); `closing_price_uses_us_session_bar`(KST 2026-10-23 → 요청 `from=to=20261022`, 봉 `date:"20261022", prpr:"82.11"` → 82.11); `closing_price_falls_back_to_live_when_bar_date_mismatch`(봉 `20261021` → 현재가 반환); `zero_price_throws`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*MeritzPriceApiTest' --tests '*MeritzExchangeRegistryTest'` → 컴파일 실패.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인** — 같은 명령 + `./gradlew :test --tests 'com.kista.api.architecture.HexagonalArchitectureTest'` PASS.
- [ ] **Step 5: 커밋** — `feat(broker): 메리츠 해외주식 현재가·전일종가·확정 종가 조회를 추가한다`

### Task 5: 계좌 조회 — 잔고·예수금·판매가능·포트폴리오·체결

**Files:**
- Create: `meritz/MeritzTradingApi.java`
- Modify: `HexagonalArchitectureTest.java` UsTradeDates allowlist에 `...adapter.out.meritz.MeritzTradingApi`
- Test: `meritz/MeritzTradingApiTest.java`

**Interfaces:**
- Consumes: `MeritzHttpClient.get/getAllPages`, `MeritzResponse.decimal/wholeQuantity` (Task 3).
- Produces (`MeritzTradingApi`):
  - `BrokerBalance getBalance(BrokerAccountRef, StrategyTicker)` — 잔고 행(`ovrs_stck_symb_iscd==ticker.name()`)의 `wholeQuantity(rmnd_dqty)`, `decimal(avrg_unpr)`(보유 0이면 null), `usdDeposit=getUsdBuyableAmount`.
  - `BigDecimal getUsdBuyableAmount(BrokerAccountRef)` — `GET /trading/v1/overseas/orders/estimate` USD 행 `oder_crnc_oder_able_camt`(USD 행 없으면 0).
  - `List<MarginItem> getMargin(BrokerAccountRef)` — USD 1건: `MarginItem(Currency.USD, able, able, able, exrt)`.
  - `SellableQuantity getSellableQuantity(StrategyTicker, BrokerAccountRef)` — `wholeQuantity(seln_able_dqty)`, 미보유 0.
  - `PresentBalanceResult getPresentBalance(BrokerAccountRef)` — `PresentBalanceResult.aggregateToss(holdings, usdDeposit=currencies USD frcr_amt, krwDeposit=estimate KRW stnd_deps_camt, rate=currencies USD vltn_exrt)` 재사용(StrategyTicker로 파싱 안 되는 보유 종목은 `StrategyTicker.tryParse`로 제외). `aggregateToss` 이름 일반화는 범위 밖 — 보고서에 제안만.
  - `List<Execution> getExecutions(LocalDate kstFrom, LocalDate kstTo, StrategyTicker, BrokerAccountRef)` — `getAllPages("/trading/v1/overseas/orders/history", oder_sta_date=toUsTradeDate(from), oder_fin_date=toUsTradeDate(to), cntg_cls_code=0)` — `1`(체결만)이 부분 체결 후 만료(stat `3`) 행을 주는지 미실측이라 전체 조회 후 `cntg_dqty>0`로 거른다; 필터 `oder_rctf_cncl_cls_code=="0"`, `ovrs_stck_symb_iscd==ticker.name()`, `wholeQuantity(cntg_dqty)>0`; 매핑 `Execution(toKstTradeDate(oder_date), ticker, "1"→SELL/"2"→BUY, qty, decimal(cntg_crnc_prc), decimal(cntg_camt), String.valueOf(oder_no))`.

- [ ] **Step 1: 실패 테스트** — 실측 JSON(spec·`docs/agents/meritz-api.md` 예시) 사용:
  - `holdings_parse_decimal_string_quantity`: `rmnd_dqty:"417.0000000000"`, `avrg_unpr:"124.812200"` → `BrokerBalance(417, 124.812200, <estimate USD>)`.
  - `balance_for_unheld_ticker_is_zero_with_null_avg`.
  - `buyable_amount_reads_usd_row`: estimate 5행 중 USD `oder_crnc_oder_able_camt:"3445.0100"` → 3445.0100.
  - `executions_convert_us_dates_and_filter_filled`: KST 2026-09-02~09-02 → 요청 `oder_sta_date=oder_fin_date=20260901`; 행 `oder_date:"20260901", seln_byov_clcd:"2", cntg_dqty:"36.0000000000", cntg_crnc_prc:"106.04440000", cntg_camt:"3817.6000", oder_no:1295619` → `Execution(2026-09-02, SOXL, BUY, 36, 106.04440000, 3817.6000, "1295619")`; `cntg_dqty:"0"` 행·다른 종목 행 제외.
  - `present_balance_aggregates_holdings_with_usd_and_krw_deposit`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*MeritzTradingApiTest'` → 컴파일 실패.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인** — 같은 명령 + 루트 HexagonalArchitectureTest PASS.
- [ ] **Step 5: 커밋** — `feat(broker): 메리츠 잔고·주문가능금액·판매가능수량·체결 내역 조회를 추가한다`

### Task 6: 주문 접수·취소 — 유형 매핑·경고 재전송·원주문 조회

**Files:**
- Create: `meritz/MeritzOrderApi.java`
- Test: `meritz/MeritzOrderApiTest.java`

**Interfaces:**
- Consumes: `MeritzHttpClient.get/post/getAllPages`, `MeritzExchangeRegistry.vendorId` (Task 3·4).
- Produces: `OrderResult place(OrderInstruction, BrokerAccountRef)`, `void cancel(CancelInstruction, BrokerAccountRef)`.

접수: `POST /trading/v1/overseas/orders/{buy|sell}` body `{ovrs_stck_symb_iscd, ovrs_exch_vndr_idno, oder_cls_code, oder_cond_cls_code, odqt, ovrs_stck_oder_prc}` (`odqt`는 정수 문자열, 가격 `price.setScale(2, HALF_UP).toPlainString()`, 시장가 계열은 `"0"`). 매핑(spec 표 그대로):

| OrderType | BUY | SELL |
|---|---|---|
| LOC | `32`/`7`/가격 | `32`/`7`/가격 |
| MOC | `32`/`7`/가격(LOC 캡) | `34`/`7`/`0` |
| LIMIT | `01`/`0`/가격 | `01`/`0`/가격 |
| MOO | `01`/`0`/가격(캡) | `05`/`0`/`0` |
| MARKET | `IllegalArgumentException` | `IllegalArgumentException` |

응답 `data.warn_cls_code`: `"0"` → `OrderResult(String.valueOf(oder_no))`; `"1"`/`"3"` → warn 로그(`warn_msg` 포함) 후 같은 body + `warn_cnfr_yn:"Y"`로 1회 재전송, 재전송 응답이 `"0"`이 아니면 `MeritzApiException.rejected("WARN_"+code, warn_msg)`; 그 외(`"2"` 보류 등) → 즉시 `rejected`. `oder_no` 없음/0 → 결과 불명(`rejected=false`) 예외.

취소: 원주문 조회 → ① `GET /trading/v1/overseas/orders/today` (`cntg_cls_code=0, seln_byov_clcd=0`)에서 `oder_no==externalOrderId` 행 → 취소가능 `oder_able_qty`, 체결 `cntg_qty`, 주문 `odqt`, `aspr_type_code`, `oder_date`; ② 없으면 `getAllPages(history, oder_sta_date=LocalDate.now(KST).minusDays(8) BASIC, oder_fin_date=LocalDate.now(KST).plusDays(1) BASIC, cntg_cls_code=0)`에서 같은 번호 → 취소가능 `ncnt_odqt`, 체결 `cntg_dqty`, 주문 `oder_dqty`(검색 창일 뿐 거래일 변환 아님 — 주석 명시); ③ 둘 다 없음 → `new MeritzApiException(null, "원주문 조회 실패", null, NONE, false)`.
- 취소가능 수량 0: 체결==주문 → `Conflict.ALREADY_FILLED`, 아니면 `Conflict.ALREADY_CANCELED`(장 마감 만료 포함) 예외, 취소 API 미호출.
- 그 외 `POST /trading/v1/overseas/orders/cancel` body `{oder_date, orgl_oder_no, oder_cls_code=aspr역매핑(2→01,5→32,7→34,4→31,6→33,1→05,8→35,9→36,A→37,B→38), odqt=취소가능 수량}` + 접수와 같은 경고 재전송 규칙.

- [ ] **Step 1: 실패 테스트**
  - `@ParameterizedTest @CsvSource` `place_maps_order_type`: 위 표 8행(타입,방향 → 경로·`oder_cls_code`·`oder_cond_cls_code`·가격 문자열) — body를 `MockRestServiceServer` `jsonPath`로 단언, 가격 `82.111` → `"82.11"`.
  - `place_market_throws_illegal_argument`.
  - `place_warning_1_resends_once_with_confirm_yes`: 1차 `warn_cls_code:"1"` → 2차 요청 body에 `warn_cnfr_yn=="Y"`, 2차 `"0"` + `oder_no:2700586` → `"2700586"`; 요청 정확히 2건.
  - `place_hold_2_is_rejected_without_resend`: `"2"` → `isRejected()`, 요청 1건.
  - `place_warning_persisting_after_resend_is_rejected`.
  - `cancel_uses_today_row_remaining_qty_and_mapped_code`: 당일 행 `oder_no:2700586, aspr_type_code:"5", oder_able_qty:30, odqt:36, cntg_qty:6, oder_date:"20261022"` → cancel body `oder_cls_code:"32", odqt:"30", oder_date:"20261022", orgl_oder_no:"2700586"`.
  - `cancel_terminal_order_throws_conflict_without_calling_cancel_api`: `oder_able_qty:0, cntg_qty:0` → `isAlreadyCanceledConflict()`; `cntg_qty==odqt` → `isAlreadyFilledConflict()`; cancel POST 0건.
  - `cancel_falls_back_to_history_then_unknown`.
- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*MeritzOrderApiTest'` → 컴파일 실패.
- [ ] **Step 3: 구현**.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS.
- [ ] **Step 5: 커밋** — `feat(broker): 메리츠 해외주식 주문 접수·취소와 경고 확인 재전송을 추가한다`

### Task 7: 어댑터 배선·문서

**Files:**
- Create: `meritz/MeritzBrokerAdapter.java` (`@Component implements BrokerCapabilitiesPort`, `supports()=MERITZ`, `KisBrokerAdapter`와 같은 위임 형태 — `getPresentBalance`는 `MeritzTradingApi.getPresentBalance` 그대로)
- Create: `docs/agents/meritz-api.md` (spec "실측 API 사실" + Task 3 판정 규칙 + 주문 매핑 + 취소 원주문 조회 + 미검증 목록(LOC·MOC·시장가 매도·경고 코드 실발생))
- Modify: `README.md`(증권사 목록), `docs/agents/modules/shared/kernel.md`(Broker 목록), `docs/agents/modules/tradingcore/broker.md`(adapter/out/meritz 줄, internal에 `DbTokenCoordinator`/`CallIntervalGate`, "KIS/Toss/Mock" 문구, domain.model.meritz), `docs/agents/modules/tradingcore/account.md`(계좌번호 없는 브로커에 MERITZ), `docs/agents/modules/api/admin.md`(기본 비활성 브로커 목록), `CLAUDE.md` "참고 문서"에 `메리츠 API 작업: docs/agents/meritz-api.md` 한 줄, `constraints.md` UsTradeDates allowlist 문장(4→6클래스), `src/test/resources/architecture/flows.yml`은 증권사 나열 문구가 있을 때만.
- Test: `meritz/MeritzBrokerAdapterTest.java`

- [ ] **Step 1: 실패 테스트** — `MeritzBrokerAdapterTest.supports_meritz_and_delegates`(Mockito로 Price/Trading/Order API 위임 몇 개 검증) + `BrokerRouter`에 등록되는지: 기존 `BrokerRouterTest` 패턴이 있으면 MERITZ 케이스 1개.
- [ ] **Step 2: 실패 확인 → Step 3: 구현·문서 → Step 4:** `./gradlew :trading-core:test --tests '*Meritz*' --tests '*BrokerRouter*' --tests 'com.kista.tradingcore.architecture.*' --tests '*TradingApplicationContextLoadTest'` PASS (컨텍스트 로드 — `meritzRestClient` 빈 이름 충돌 없음 확인).
- [ ] **Step 5: 커밋** — `feat(broker): 메리츠 브로커 어댑터를 라우터에 연결하고 연동 문서를 추가한다`

### Task 8: 실 API 검증 (조회 + 1주 주문·취소)

**Files:**
- Create: `trading-core/src/test/java/com/kista/tradingcore/broker/adapter/out/meritz/MeritzLiveIT.java` — `@Tag("integration")`, `@EnabledIfEnvironmentVariable(named="MERITZ_APP_KEY", matches=".+")`. Spring 컨텍스트 없이 Task 3~6 클래스를 직접 조립(실 `RestClient`, 메모리 `BrokerTokenCachePort` 람다/익명 구현, `BrokerAccountRef(UUID.randomUUID(), key, secret, null, null, MERITZ)`).
  - `read_capabilities_work`: 연결 테스트, SOXL 현재가/전일종가/확정 종가(KST 전 거래일), 잔고·판매가능·주문가능금액, 최근 30일 체결 — 예외 없음·값 양수 단언.
  - `place_and_cancel_one_share_far_loc` / `..._far_limit`: 추가로 `MERITZ_LIVE_ORDER=1`일 때만(`assumeTrue`). SOXL **LOC** BUY 1주 @ 현재가×0.5(소수 2자리 — INFINITE 실경로 `32`/`7` 검증, 마감가가 절반이 될 수 없어 체결 불가)와 LIMIT BUY 1주 @ 현재가×0.5 각각 → `OrderResult` → 당일 목록 반영 대기(최대 10s 폴링) → `cancel` → 당일 목록에서 `oder_able_qty==0` 확인. 응답 `warn_cls_code`·소요 시간을 stdout 출력. 마감 10분 전 이후엔 실행 금지(LOC 취소 불가 구간).

- [ ] **Step 1:** 테스트 작성·컴파일(`./gradlew :trading-core:compileTestJava`).
- [ ] **Step 2: 조회 검증** — 컨트롤러가 실행: `set -a; . <scratchpad>/meritz.env; set +a; ./gradlew :trading-core:integration --tests '*MeritzLiveIT.read_capabilities_work'` (integration 태스크명이 다르면 `docs/agents/commands.md` 확인) → PASS.
- [ ] **Step 3: 주문 검증** — **실행 직전 사용자에게 "지금 실계좌에 SOXL 1주 반값 지정가 매수 후 즉시 취소합니다" 한 줄 알림 후** kista 실제 접수 시각대인 KST 04:30 전후(DST — 마감 10분 전 04:50 이전에 취소까지 끝나야 함, 비DST는 +1h)에 `MERITZ_LIVE_ORDER=1`로 같은 명령 `--tests '*MeritzLiveIT.place_and_cancel_*'`. 정규장 밖이면 접수 가능 여부도 기록 대상(거부 시 rsp_cd를 meritz-api.md에 기록하고 정규장에 재시도).
- [ ] **Step 4:** 관찰 결과(경고 코드, 당일 목록 반영 지연, 취소 응답)를 `docs/agents/meritz-api.md` "실측" 절에 추가.
- [ ] **Step 5: 커밋** — `test(broker): 메리츠 실계좌 조회·주문 취소 검증 테스트를 추가한다`

### Task 9: 최종 검증

- [ ] `./gradlew test 2>&1 | grep -E "FAILED|BUILD|tests completed"` 전체 1회 → BUILD SUCCESSFUL.
- [ ] kista-ui 연계 요청 정리: `/api/meta` brokers에 MERITZ 노출, 계좌 등록 요청 `broker:"MERITZ"`(appKey·secretKey만, accountNo 미전송), 계좌 응답 `accountNo`/`accountNoMasked`가 null — UI는 UPBIT 폼·표시 경로 재사용. 사용자에게 kista-ui 세션을 요청.

# 30. 크립토 삭제 전수표 (지침서 15 T1)

작성 2026-11-15, 기준 커밋 `c882fbf3e`, 읽기 전용(코드 변경 없음). `findings/20`(2026-11-14) 대비: KIS 삭제(04 A)·T0(BITO·EETH)가 반영돼 줄 번호가 움직였고, **업비트는 아직 전부 남아 있다**(이 지침서 몫). 지침서의 UseCase 3개(`CryptoDcaExecutionUseCase`·`CryptoMarketIndicatorUseCase`·`CryptoPortfolioUseCase`)는 이미 없다. 통째 삭제는 main 70파일/약 3,977줄, test 35파일/약 5,257줄(바이낸스+업비트+코인 전부).
약어: `TC`=`trading-core/src/main/java/com/kista/tradingcore`, `TCT`=`trading-core/src/test/java/com/kista/tradingcore`, `SH`=`shared/src/main/java/com/kista/shared`, `API`=`src/main/java/com/kista/api`, `TCR`=`trading-core/src/main/resources`. 단계 A=통째 삭제+분기 제거+마이그레이션 한 배포, B=`Market`·`execution_hour` 후속, D=문서·운영. "분기 제거(문구)"는 주석·`@Schema` 설명만 고치는 파일이다.
조사 패턴: `Upbit|UPBIT|Binance|BINANCE|Crypto|crypto|USDT|\bBTC\b|\bETH\b|코인|업비트|바이낸스`(+`case MARKET`, `isCrypto`, `executionHour`), `docs/superpowers/fable-review/` 제외 308파일. 마이그레이션 번호: trading는 V22(KIS 삭제)까지 쓰여 **다음은 V23**, root는 V6까지라 다음은 V7.

## 1. main — 통째 삭제 (단계 A)

| 파일 | 분류 | 근거 | 단계 |
|---|---|---|---|
| `TC/broker/adapter/out/binance/` (7: 어댑터 6 + CLAUDE.md) | 삭제 | `BinanceExchangeAdapter`·`HttpClient`·`AuthApi`·`SymbolRegistry`·`ClientOrderIds`·`Config` | A |
| `TC/broker/adapter/out/upbit/` (6: 어댑터 5 + CLAUDE.md) | 삭제 | 업비트 전체가 15 몫(04 A는 KIS만) | A |
| `TC/broker/domain/model/{binance/BinanceApiException,upbit/UpbitApiException}` (2) | 삭제 | 코인 어댑터 전용 예외 | A |
| `TC/broker/application/port/output/CryptoExchangePort`, `application/service/CryptoExchanges` (2) | 삭제 | 구현체가 업비트·바이낸스뿐 | A |
| `TC/broker/domain/model/Crypto{Balance,Candle,OrderResult}` (3) | 삭제 | 코인 포트 전용 값 타입 | A |
| `SH/kernel/CryptoTradingRules` | 삭제 | `DcaCycleOrderStrategy.java:3,38`·코인 어댑터만 사용 | A |
| `TC/trading/adapter/in/schedule/CryptoDcaScheduler` | 삭제 | 10분 cron, `HeartbeatPort.pingCrypto` 호출(:32) | A |
| `TC/trading/application/service/Crypto{DcaRunner,DcaRoundExecutor,OrderSynchronizer,LedgerRecorder,Failure}` (5) | 삭제 | 코인 틱 일체. 외부 사용처는 6절 분기 목록 | A |
| `TC/trading/application/event/CryptoOrderFilledEvent` | 삭제 | EPR 고아 행 확인(지침서 15 단계 P ③) | A |
| `TC/trading/application/port/output/CryptoFearGreedIndexPort`, `adapter/out/feargreed/CryptoFearGreedIndexAdapter` (2) | 삭제 | 코인 틱만 소비 | A |
| `TC/notify/adapter/out/gateway/CryptoTradeNotifier` | 삭제 | `CryptoOrderFilledEvent` 구독 | A |
| `TC/stats/adapter/in/web/{CryptoMarketIndicatorController,CryptoPortfolioController}` + `dto/{CryptoMarketIndicatorsResponse,CryptoPortfolioResponse,CryptoPricesResponse}` (5) | 삭제 | 엔드포인트 3개 | A |
| `TC/stats/application/service/{CryptoMarketIndicatorService,CryptoPortfolioService}` (2) | 삭제 | | A |
| `TC/stats/domain/model/Crypto{HashRibbons,MarketIndicators,Portfolio,Premiums,Valuations}` (5) | 삭제 | | A |
| `TC/stats/domain/model/{DatedHashRibbon,DatedLongShortRatio,DatedPremiumPair,DatedValuation,DatedValue,FundingRateQuote,FundingRateRecord}` (7) | 삭제 | **findings/20에 없던 것** — 이름에 Crypto가 없지만 코인 지표 전용 | A |
| `TC/stats/application/port/output/{BinanceMarketDataPort,CoinbaseMarketDataPort,CryptoMarketMetricsPort,UpbitMarketDataPort,BlockHeightPort,HashrateHistoryPort}` (6) | 삭제 | 뒤 둘은 이름에 Crypto 없음(findings/20 누락) | A |
| `TC/stats/adapter/out/{binance(2),coinbase(2),coinmarketcap(3),mempool(2),upbit(2)}` (11) | 삭제 | 지표용 외부 API 어댑터+Config(`CoinMarketCapProperties` 포함) | A |
| `API/market/adapter/out/feargreed/CryptoFearGreedAdapter`, `application/port/output/CryptoFearGreedPort`, `domain/model/CryptoFearGreedHistory` (3) | 삭제 | root 코인 공포탐욕 | A |
| `TC/stats/application/port/output/HistoricalFxRatePort`, `stats/adapter/out/frankfurter/FrankfurterFxRateAdapter` (2) | 삭제 | 소비처가 `CryptoMarketIndicatorService.java:59`뿐(`getUsdKrwRates` 범위 조회 하나). 어댑터에 다른 메서드 없음. 지침서 14 T4가 아직이면 이 둘도 14가 쓸지 확인 | A |
| `TC/stats/adapter/out/frankfurter/FrankfurterConfig` | 유지 | 14 T4가 쓴다(지침서 15 2-1절) | — |

## 2. main — 분기 제거 (코드)

| 파일 | 분류 | 근거 | 단계 |
|---|---|---|---|
| `SH/kernel/Broker.java` | 분기 제거 | `UPBIT`:13·`BINANCE`:15 상수, `isCryptoExchange`:34, `keyOnlyCredentials`(MERITZ 사용)·필드는 유지 | A |
| `SH/kernel/Market.java` | 분기 제거 → B에서 삭제 | `CRYPTO_KRW`:11·`CRYPTO_USDT`:12·`isCrypto`:22. 소비처가 `Broker.market`·`StrategyTicker.markets`·`BrokerMeta` | A(상수)/B(enum) |
| `SH/kernel/Currency.java` | 분기 제거 | `USDT(2)`:13, `format` 주석:30. `KRW` 유지(`TossHoldingsApi.java:75`) | A |
| `SH/kernel/StrategyTicker.java` | 분기 제거 | `BTC`:23·`ETH`:24, `isCrypto`:41. BITO:21·EETH:22는 T0 결과로 유지 | A |
| `SH/kernel/StrategyType.java` | 분기 제거 | DCA 허용 주문유형에서 `MARKET`(:33), 종목에서 BTC·ETH, 주석:18 | A |
| `SH/kernel/OrderType.java` | 분기 제거 | `MARKET`:15 삭제(8절 결론: 주식 경로 생성 0) | A |
| `SH/kernel/TradingPolicySettings.java` | 분기 제거 | `SERVER_EGRESS_IP`:22, `UPBIT`:30·`BINANCE`:31 기본값, DCA 종목:45의 BTC·ETH | A |
| `SH/kernel/BrokerSettings.java` | 분기 제거 | `allowedIp`:7 | A |
| `SH/kernel/ApiKeyExpirySource.java` | 분기 제거 | `BROKER_API`:6(업비트 전용). 소비처 `AccountService.java:98,152`·`BrokerMeta.java:22` | A |
| `SH/kernel/DcaSkipReason.java` | 분기 제거(사용자 확인) | `BELOW_MIN_ORDER_AMOUNT`:16·`EXCHANGE_API_ERROR`:17·`API_KEY_EXPIRED`:18은 코인 전용(소비처 `CryptoFailure:21`, `CryptoOrderSynchronizer:96`, `DcaCycleOrderStrategy:164`). 상수명은 DB·UI 계약 — 9절 | A |
| `SH/platform/web/ErrorCode.java` | 분기 제거 | `BROKER_IP_NOT_ALLOWED`:8(C5), `BROKER_WITHDRAWAL_ENABLED`:19(바이낸스 전용). `BROKER_PERMISSION_DENIED`:9는 메리츠가 써서 유지(주석만) — 9절 | A |
| `TC/broker/domain/model/{BrokerIpNotAllowedException,BrokerWithdrawalEnabledException}` | 삭제 | 업비트·바이낸스 어댑터만 던진다(메리츠·토스 grep 0) | A |
| `TC/broker/adapter/in/web/BrokerProblemDetails` | 분기 제거 | 위 두 예외 매핑(:34-35). `BrokerPermissionDeniedException`:36은 유지 | A |
| `TC/broker/domain/model/BrokerPermissionDeniedException` | 유지(문구) | `MeritzAuthApi.java:89`, `MeritzResponse.java:63` 사용. 주석의 "업비트 out_of_scope"만 수정 | A |
| `TC/broker/adapter/out/meritz/MeritzOrderApi.java:82`, `toss/TossOrderApi.java:183` | 분기 제거 | `case MARKET -> throw` 2곳(KIS는 04 A로 없음) | A |
| `TC/broker/adapter/out/toss/TossRedisTokenStore.java` | 유지 | `platform.crypto.Sha256` import(AES 패키지) | — |
| `TC/matching/domain/model/DcaRule.java` | 분기 제거 | `executionHour`:25·검증:44-45, `market` 변수:43, 주말 예외:49-50, `requireCompatible`:58-77 코인 분기, 생성자:107·111 | A |
| `TC/matching/domain/model/DcaStrategyConfig.java:36` | 분기 제거 | `requireCompatible(Market)` 위임. `Market` 삭제(B) 때 함께 | A/B |
| `TC/matching/domain/strategy/DcaCycleOrderStrategy.java` | 분기 제거 | `CryptoTradingRules` 입력:3,38-41, 코인 삼항:96-137,160. 주식 케이스 기대값 불변 | A |
| `TC/matching/domain/model/BootstrapPosition.java` | 분기 제거 | `CRYPTO_SCALE`:13, `isCrypto`:26-30 | A |
| `TC/matching/domain/strategy/DcaConditions.java:18`, `matching/domain/model/AccountBalance.java:17` | 분기 제거(문구) | 주석 | A |
| `TC/trading/adapter/in/web/dto/DcaRuleRequest.java` | 분기 제거 | `executionHour`:27-28, `MARKET` 분기:41 | A |
| `TC/trading/adapter/in/web/dto/DcaRuleResponse.java`, `adapter/out/persistence/StrategyDcaRuleEntity.java` | 분기 제거 | `executionHour` 필드(:24 / 엔티티 :69-70,96,111). 컬럼 `execution_hour` 삭제는 B | A/B |
| `TC/trading/application/service/DcaConfigService.java:40` | 분기 제거 | `requireCompatible(Market)` 호출 | A/B |
| `TC/trading/application/service/DcaSummaryAssembler.java` | 분기 제거 | `isCrypto`:39,61, `executionHour`:64-65, 주석:36,59 | A |
| `TC/trading/application/service/OrderCancelService.java` | 분기 제거 | `requireNoCryptoInFlight`:58-67 삭제, `isCryptoExchange`:59,82,98,168-170 | A |
| `TC/trading/application/service/AccountOpenOrderCanceller.java:26-27`, `StrategyService.java:74,97,123,136-138` | 분기 제거 | `requireNoCryptoInFlight` 호출 4곳, `isCrypto` 1곳 | A |
| `TC/trading/application/service/TradingPreviewService.java` | 분기 제거 | `isCryptoExchange`:78,102,107(거래일 `DstInfo` vs `SessionTradeDates`) | A |
| `TC/trading/application/service/StrategyCreationService.java:75-77` | 분기 제거 | 코인 계좌 DCA 전용 거부 분기. `:57`의 `requireCompatible`은 B | A/B |
| `TC/trading/application/service/{AccountLedgerTotalsService,support/UnassignedCashCalculator}` | 분기 제거 | `CryptoExchanges` 주입(:9,32 / :5,29)과 코인 잔고·가격 분기(:54 / :63-64) | A |
| `TC/trading/application/service/{CapitalLedgerService,StrategyUpdateWriter,ManualTradeCorrectionService}` | 분기 제거 | `owned.crypto()`(:124,206 / :112), `CRYPTO_SCALE`:59, 코인 리포트 예외:128,157,177-181 | A |
| `TC/trading/application/service/support/{OwnedStrategy,StrategyHistoryQueryService}`, `StrategyDetailAssembler.java:180`, `domain/model/StrategyDetail.java:23` | 분기 제거 | `crypto()`:26, `isCrypto`, 코인 정수 보유 null 처리 | A |
| `TC/trading/adapter/in/schedule/BatchContextFactory.java:51` | 분기 제거 | 코인 계좌 전략 `continue`(isCryptoExchange) | A |
| `TC/trading/domain/model/Order.java` | 분기 제거 | `cryptoMarket()`:85-88 삭제(호출처는 `CryptoDcaRoundExecutor:183`뿐) | A |
| `TC/trading/application/port/output/HeartbeatPort.java:7`, `adapter/out/heartbeat/HeartbeatAdapter.java:28-29`, `HeartbeatProperties.java:7` | 분기 제거 | `pingCrypto`·`cryptoUrl`. 설정 키는 `heartbeat.crypto-url`(`HEARTBEAT_CRYPTO_URL`) — 3절 | A |
| `TC/notify/application/port/output/TradingUserNotificationPort.java`, `adapter/out/gateway/TradingUserNotificationAdapter.java` | 분기 제거 | `notifyCryptoTrade`(:40-41 / :141-146)와 import. `:42`의 업비트 만료 문구 수정 | A |
| `TC/notify/adapter/out/gateway/TradingAlertNotifier.java`, `trading/application/event/DcaRoundSkippedEvent`, `DcaExecutionRecorder:87` | 유지 | 주식 DCA도 발행(`DcaExecutionRecorder.java:87`) | — |
| `TC/account/domain/model/Account.java` | 분기 제거 | 정렬 리스트 :25의 BINANCE·UPBIT, 주석:15-23,38,78 | A |
| `TC/account/application/service/AccountService.java` | 분기 제거 | `case BROKER_API`:98,152, 업비트 주석:68,81,86,93,96,139,150. `keyOnlyCredentials`:67,141은 유지 | A |
| `TC/account/adapter/in/web/{AccountController,dto/AccountRequest,dto/AccountResponse,dto/CredentialsRequest}`, `domain/model/UpdateCredentialsCommand`, `adapter/out/persistence/AccountEntity`, `application/port/output/AccountPort`, `broker/.../{BrokerConnectionTestPort,BrokerAccountVerification}` | 분기 제거(문구) | `@Schema`·주석의 업비트·바이낸스 문구(`AccountRequest:16-23` 등) | A |
| `TC/stats/adapter/in/web/{PortfolioQueryInternalController,dto/DailyTransactionResponse}`, `domain/model/PortfolioPosition`, `broker/domain/model/DailyTransaction` | 분기 제거(문구) | "USDT·코인 소수" 주석·`@Schema` | A |
| `TC/trading/adapter/in/web/dto/{DcaResponse,DcaSizingRequest,NextOrdersResponse,StrategySeedPreviewResponse,TradingCycleRequest,TradingCycleResponse}`, `adapter/out/persistence/{CyclePositionJpaRepository,OrderEntity,OrderPersistenceAdapter}`, `application/port/output/{CyclePositionPort,OrderPort}`, `application/service/{ExecutionFillMapper}`, `domain/model/{DcaExecution,DcaSummary,DepositAmounts,ManualTradeCorrectionCommand,RegisterStrategyCommand,StrategySeedPreview}` | 분기 제거(문구) | "코인 소수·KRW·USDT" 주석·`@Schema`. 수량 `numeric(20,8)` 자체는 유지 | A |
| `TC/web/TradingSecurityPolicy.java:19-23`, `web/package-info.java:5` | 분기 제거 | 공개 경로 `CRYPTO_MARKET_INDICATORS` 상수 전체(라우트 정책 클래스에 남는 것이 없으면 클래스째 삭제 여부 판단) | A |
| `SH/contract/{notify/TradeEventMessage,stats/PortfolioCurrentResponse,stats/PortfolioOrderResponse,trading/OrderResponse,trading/TradeCorrectionRequest}` | 분기 제거(문구) | wire 타입은 그대로, 주석의 코인·USDT만 | A |
| `API/market/application/service/FearGreedQueryService.java` | 분기 제거 | `CRYPTO_*` 상수 6개:27-34, `cryptoHistory`:69, `cryptoTtl`:84, 분기:53, 생성자:41 | A |
| `API/market/adapter/in/web/{FearGreedController(:26,39),dto/FearGreedResponse(:14,32-33)}` | 분기 제거 | `SOURCE_CRYPTO`, `crypto` 응답 필드(UI 계약, 단계 U 선행) | A |
| `API/market/application/service/FearGreedService.java:14`, `adapter/out/persistence/feargreed/FearGreedSnapshotEntity.java:32`, `domain/model/FearGreedSnapshot.java:9-10` | 분기 제거(문구) | `"CRYPTO" | "CNN"` 주석. `source` 컬럼 유지 여부는 별도 판단(findings/20 4-1) | A |
| `API/admin/adapter/in/web/{AdminSettingsController:41,dto/RuntimeSettingsResponse:51}`, `AdminSettingsRequest`(allowedIp) | 분기 제거 | `allowedIp` 필드·"신규 증권사(UPBIT)" 주석 | A |
| `API/admin/adapter/in/web/dto/{AdminAccountItem,AdminAccountResponse,AdminManualTradeCorrectionRequest,AdminTradeResponse}` | 분기 제거(문구) | `AccountNumberMasker` 줄(코인 null 주석)·`@Schema` 예시 `USDT` | A |
| `API/web/dto/BrokerMeta.java:8,14,20,22` | 분기 제거(문구) | `@Schema` 예시 `UPBIT`·`CRYPTO_KRW`·"USDT 2"·`BROKER_API`. 필드 삭제(`market`·`minOrderAmount`·`amountScale`)는 B | A/B |
| `API/finance/domain/model/AssetClass.java:14` | 유지 | `CRYPTO("크립토")` 가계부 분류(C3) | — |
| `API/finance/domain/model/Market.java` | 유지 | finance 별개 enum(C4 대상 아님, `MetaController.java:57`이 이 enum) | — |
| `SH/platform/crypto/*` (4), `API/finance/.../{FinanceAccountEntity,FinanceAccountPersistenceAdapter}`, `API/user/.../{UserEntity,TokenService}`, `TC/account/adapter/out/persistence/AccountPersistenceAdapter(:3,51)`, `TC/trading/adapter/out/persistence/UserNotifyProfileEntity`, `SH/platform/PlatformAutoConfiguration` | 유지 | AES·해시 패키지 이름만 같다 |
| `SH/kernel/Quantities.normalize`, `DcaIndicator.FEAR_GREED`, `StrategyType.DCA`, `Account.keyOnlyCredentials`·`Broker.keyOnlyCredentials` | 유지 | 지침서 15 2-3절·8절 |

## 3. resources·설정

| 파일 | 분류 | 근거 | 단계 |
|---|---|---|---|
| `TCR/application.yml:93-94` `upbit:` 블록 | 삭제 | `base-url` 하나 | A |
| `TCR/application.yml:103` `heartbeat.crypto-url` | 삭제 | `${HEARTBEAT_CRYPTO_URL:}`(`HeartbeatProperties.cryptoUrl`)가 findings/20의 "키 미확인" 답 | A |
| `TCR/application.yml:111-112` `cmc:` 블록 | 삭제 | `CMC_API_KEY` | A |
| `TCR/application.yml:82`, `src/main/resources/application.yml:88` `crypto:` | 유지 | AES 키(`crypto.aes-key`) | — |
| `TCR/db/migration-trading/V8·V9·V10·V12·V18·V19` (코인 주석·`execution_hour`) | 유지 | 적용된 마이그레이션 수정 금지(checksum). `V10__account_upbit.sql`도 같음 | — |
| `src/main/resources/db/migration/V5__fear_greed_crypto_snapshots_delete.sql` | 유지 | 적용됨 | — |
| 신규 `V23` 데이터 정리(가드·완전 삭제·정책 JSON) | 신설 | 지침서 15 4절. 번호는 착수 때 `ls`로 센다 | A |
| 신규 `V24` `execution_hour` 컬럼 삭제 | 신설 | | B |
| `.env.example:55,58` | 분기 제거 | `HEARTBEAT_CRYPTO_URL=` 삭제, 55행 "코인 DCA 틱" 문구. `CMC_API_KEY`는 이미 없다(09 T3 철회 반영) | D |
| `deploy/server/README.md:79` | 분기 제거 | `CryptoDcaScheduler`·`HEARTBEAT_CRYPTO_URL`·`CMC_API_KEY`(findings/20은 :91) | D |
| `deploy/server/caddy/kista-api.caddy:18` | 분기 제거 | 정규식의 `crypto-market-indicators`. `CaddyRoutingTest`가 대조 | A |
| `.github/`, `infra/`, `docker-compose.yml`, `Dockerfile`, 기타 `scripts/`(dev-seed 제외) | 대상 없음 | 패턴 0건 | — |
| `scripts/dev-seed/dev-seed.sql:224-429` (72곳) | 분기 제거 | BINANCE·UPBIT 시드 블록 삭제(`execution_hour` INSERT :254,340 포함). findings/20은 82개/224-414행 | D |

## 4. test

삭제 대상 35파일은 1~2절의 Crypto·Binance·Upbit 클래스 각각의 `*Test`/`*IT`다. 전부 `TCT/...`에 있고 이름이 클래스와 같다: `broker/adapter/out/binance/{AuthApi,ClientOrderIds,ExchangeAdapter,HttpClient,SymbolRegistry}Test + BinanceTestnetIT`(6), `broker/adapter/out/upbit/{AuthApi,ExchangeAdapter,HttpClient,MarketRegistry}Test`(4), `broker/application/service/CryptoExchangesTest`, `broker/domain/model/CryptoCandleTest`, `notify/.../CryptoTradeNotifierTest`, `stats/adapter/in/web/Crypto{MarketIndicator,Portfolio}ControllerTest`, `stats/adapter/out/{binance,coinbase,coinmarketcap,mempool,upbit}/*Test`(5), `stats/application/service/Crypto{MarketIndicator,Portfolio}ServiceTest`, `stats/domain/model/Crypto{HashRibbons,Premiums,Valuations}Test`, `trading/adapter/in/schedule/CryptoDcaSchedulerTest`, `trading/adapter/out/feargreed/CryptoFearGreedIndexAdapterTest`, `trading/application/event/CryptoOrderFilledEventJsonTest`, `trading/application/service/Crypto{DcaRoundExecutor,DcaRunner,Failure,OrderSynchronizer}Test + CryptoLedgerRecorderIT`, `shared/src/test/.../kernel/CryptoTradingRulesTest`, `src/test/.../market/adapter/out/feargreed/CryptoFearGreedAdapterTest`. `FrankfurterFxRateAdapterTest`도 어댑터와 함께 삭제.

| 파일 | 분류 | 근거 | 단계 |
|---|---|---|---|
| 위 35파일 + `FrankfurterFxRateAdapterTest` | 삭제 | 5,257줄+ | A |
| `src/test/resources/architecture/single-impl-interfaces.txt:31,58,61,65,66` | 분기 제거 | 삭제 포트 5줄 제거 + 상한(줄어들기만 하는 목록) 하향. **findings/20에 없던 것** | A |
| `TCT/trading/adapter/in/schedule/CryptoDcaSchedulerTest` 외 `HeartbeatAdapterTest:34-37` | 분기 제거 | `pingCrypto` 케이스 삭제 | A |
| `TCT/web/TradingSecurityPolicyTest:40-52` | 분기 제거 | `/api/crypto-market-indicators` 케이스(이 경로가 없어지면 정책 클래스 존폐와 함께) | A |
| `TCT/web/ExceptionResponseTableTest:6-9,72-74` | 분기 제거 | `BrokerIpNotAllowed`·`BrokerWithdrawalEnabled` 행(`BrokerPermissionDenied`는 유지) | A |
| `TCT/matching/domain/model/DcaRuleTest`(8), `matching/domain/strategy/{DcaRuleDecisionTest(23),DcaConditionsTest(1)}` | 분기 제거 | 코인 케이스만. 주식 기대값 불변(지침서 15 6절 2번) | A |
| `TCT/trading/application/service/{StrategyServiceTest(38),AccountLedgerTotalsServiceTest,CapitalLedgerServiceTest,DcaConfigServiceTest,DcaSummaryAssemblerTest,ManualTradeCorrectionServiceTest,OrderCancelServiceTest(17),StrategySeedPreviewServiceTest(16),TradingPreviewServiceTest,support/UnassignedCashCalculatorTest}` | 분기 제거 | 코인 케이스·`cryptoDcaRule` 사용(`DcaConfigServiceTest:157`) | A |
| `TCT/trading/adapter/in/schedule/BatchContextFactoryTest`, `adapter/out/persistence/{CyclePositionDcaInclusionDbTest,CyclePositionPersistenceAdapterTest,DcaPersistenceAdapterDbTest,OrderPersistenceAdapterDbTest(11),policy/TradingPolicySettingsPersistenceAdapterTest(10)}`, `adapter/in/web/TradingPolicyInternalControllerTest`, `domain/model/OrderTest` | 분기 제거 | 코인 케이스, 정책 JSON 테스트(지침서 15 6절 4번) | A |
| `TCT/account/{adapter/in/schedule/ApiKeyExpirySchedulerTest,adapter/in/web/AccountControllerTest,adapter/out/persistence/AccountPersistenceAdapter{DbTest,Test},application/service/{AccountServiceTest(40),AccountServiceKeyRotationDbTest},domain/model/AccountTest}` | 분기 제거 | 업비트·바이낸스 계좌 케이스 | A |
| `TCT/notify/adapter/out/gateway/{NotificationMessageSnapshotTest(15),TradingUserNotificationAdapterWordingTest(11),ApiKeyExpiryNotifierTest}` | 분기 제거 | "코인 체결"·업비트 만료 문구 | A |
| `TCT/stats/adapter/in/web/{DashboardControllerTest,PortfolioQueryInternalControllerTest,TradingStatsControllerTest,TradingStatsInternalControllerTest}`, `stats/application/service/{AccountStatisticsServiceTest,InvestmentPointsQueryServiceTest(12),PortfolioServiceTest,TradingStatsServiceTest(23)}` | 분기 제거 | 코인 계좌·USDT 케이스(`PortfolioServiceTest:58` 바이낸스) | A |
| `TCT/broker/adapter/out/toss/TossOrderApiTest` | 분기 제거 | `MARKET` 거부 케이스. Meritz 쪽 테스트는 grep 0이라 직접 확인 | A |
| `shared/src/test/.../{kernel/{MarketVocabularyTest(21),CurrencyTest(5),TradingPolicySettingsTest(4)},contract/{ContractWireFormatTest,notify/TradeEventMessageTest(4)}}` | 분기 제거 | 코인 값만 뺀다(`Market` 삭제는 B) | A/B |
| `src/test/.../web/MetaControllerTest(11)`, `admin/adapter/in/web/{AdminSettingsControllerTest(5),dto/AdminAccountResponseTest(3)}`, `admin/adapter/in/telegram/TelegramBotServiceTest(4)`, `admin/adapter/out/internal/{PortfolioQueryHttpAdapterTest,TradingPolicyHttpAdapterTest}` | 분기 제거 | `ETH`·`USDT` 샘플, `API_KEY_EXPIRED` 라벨(`MetaControllerTest:137`) | A |
| `src/test/.../benchmark/{adapter/in/web/BenchmarkControllerTest,adapter/in/web/dto/BenchmarkComparisonResponseTest(12),adapter/out/internal/InvestmentPointsHttpAdapterTest,application/service/{BenchmarkComparisonBuilderTest,BenchmarkServiceTest}}` | 분기 제거 | `Currency.USDT` 케이스(`PORTFOLIO_USDT`:47 등). **findings/20 파일 목록에 없던 것** | A |
| `src/test/.../market/{adapter/in/web/FearGreedControllerTest(6),adapter/out/persistence/feargreed/FearGreedSnapshotPersistenceAdapterTest(4),application/service/FearGreedQueryServiceTest(51)}` | 분기 제거 | 크립토 케이스 | A |
| `src/test/.../architecture/HexagonalArchitectureTest:131,339-340` | 유지(문구) | 주석의 crypto(AES)·코인 문구만. 삭제 클래스 FQCN 문자열은 없다(`ModulithArchitectureTest`도 0건) | — |
| `src/test/.../notify/adapter/out/gateway/AlertNotifierTest:26-28` | 유지 | 문자열 "crypto api down"은 임의 예외 메시지 | — |
| `shared/src/testFixtures/.../DataJpaTestBase`, `application-test.yml`, `FinanceAccountPersistenceAdapterTest`, `UserCascadeEndToEndIT` | 유지 | AES 패키지 import·`crypto:` 키 | — |
| `trading-core/src/testFixtures/.../TradingFixtures.java:58-80,95-98` | 삭제(T4에서 사용처 0 되면) | `upbitAccount`·`binanceAccount`·배치 투영 2개·`cryptoDcaRule`(`OrderType.MARKET`:98). 사용처는 코인 테스트와 위 "분기 제거" 테스트의 코인 케이스뿐 | A |

## 5. 문서·flows.yml·배포

| 파일 | 분류 | 근거 | 단계 |
|---|---|---|---|
| `docs/agents/vendors/binance.md`(26줄 언급), `vendors/upbit.md`(25) | 삭제 | 옛 경로 `binance-api.md`는 S8로 이동됨 | D |
| `docs/agents/decisions.md` | 분기 제거 | **7절 "코인 경로"(:397-407) 통째 삭제**(절 제목이 지시), 6절:358 보정, :89·:137·:177·:192·:379·:386은 코인 언급 문장 정리 | D |
| `docs/agents/modules/tradingcore/{dca(12),broker(4),account(5),matching(3),notify(1),trading(2),trading-testing(1),web(1)}.md`, `stats.md` | 분기 제거 | 코인 문장·패키지 트리. `stats.md`는 패턴 0건이나 stats 어댑터 트리 갱신 확인 | D |
| `docs/agents/modules/shared/{kernel(2),platform(2)}.md`, `modules/api/{admin(2),market(5)}.md` | 분기 제거 | `platform.md`의 2곳은 AES라 유지 확인 | D |
| `docs/agents/{constraints:23,docker-infra:15,87,local-dev:23,scheduler-time-table:16-17,workflow-ledger-price:6}.md` | 분기 제거 | `docker-infra.md:15` "바이낸스 배포·롤백 주의" 삭제, 시간표 2행(코인 틱·업비트 만료 점검 — 후자는 토스·메리츠 용도라 "업비트" 문구만 고친다: `ApiKeyExpiryScheduler`는 남는 기능) | D |
| `docs/agents/vendors/{meritz.md:13,53, toss.md:26}` | 분기 제거(문구) | "UPBIT처럼"·"MARKET(코인 전용)" 표 행 | D |
| `docs/architecture-map.md:94,103` | 분기 제거 | `CNN · Crypto`, "코인 틱 heartbeat". :162는 AES(유지) | D |
| `src/test/resources/architecture/flows.yml` (47곳) | 분기 제거 | 흐름 `crypto-market-indicators`(:51,790-821)·`crypto-dca`(:57,1606-1658) 삭제, `api-key-expiry`(:58) 분리·`flow:` 교체(지침서 10절), 문구 :88,288,500,504,678-778,830,857,1103. `code:` 줄은 `:698,728,798,821,1616,1624,1633,1642,1649`(지침서의 711·741·811·834·1629…는 이동됨) | A |
| `README.md:8,13,14,15` | 분기 제거 | 소개·전략(BTC/ETH 부분만, UPRO/QLD/BITO/EETH는 유지)·거래소·10분 스케줄러 | D |
| `CLAUDE.md:3` | 분기 제거 | "삭제 예정" 문구(포인터는 decisions.md 6절). findings/20은 63줄 중 2곳이라 했으나 지금 1곳 | D |
| `docs/agents/code-quality-backlog.md` | 대상 없음 | S8에서 `decisions.md`로 흡수 | — |
| `docs/agents/binance-api.md`, `upbit-api.md` | 대상 없음 | `vendors/*.md`로 이동됨(위 행) | — |
| `KisOrderApi.java:159`, `KisExchangeRegistry`, `docs/agents/vendors/kis.md` | 대상 없음 | 04 A로 삭제 | — |

## 6. 행 집계 (묶음 행은 1행, 1~5절 표 기준)

| 분류 | 행 수 |
|---|---|
| 삭제 | 26 (main 통째 삭제 20, 예외 클래스 1, 설정 3, test 묶음 1, fixtures 1) |
| 분기 제거 (문구 포함) | 74 |
| 유지 (문구만 고치는 유지 2 포함) | 14 |
| 대상 없음 | 4 |
| 신설 (마이그레이션) | 2 |

## 7. findings/20에 없던 것

1. 이름에 Crypto가 없는 코인 지표 전용 9파일: `Dated{HashRibbon,LongShortRatio,PremiumPair,Valuation,Value}`·`FundingRate{Quote,Record}`·`BlockHeightPort`·`HashrateHistoryPort`(1절).
2. `BrokerWithdrawalEnabledException`(바이낸스 전용)·`ErrorCode.BROKER_WITHDRAWAL_ENABLED`. C5는 IP 예외만 다뤘다. `BrokerPermissionDeniedException`·`BROKER_PERMISSION_DENIED`는 메리츠가 써서 유지.
3. `DcaSkipReason` 3상수 — 코인 전용인데 상수명이 DB·UI 계약(주석 :18)이다.
4. `HistoricalFxRatePort`+`FrankfurterFxRateAdapter`는 코인 지표 전용 소비라 둘 다 삭제 후보(`FrankfurterConfig`만 유지). 14 T4와 순서 확인.
5. 테스트: `single-impl-interfaces.txt` 5줄, `TradingSecurityPolicyTest`, `benchmark` 테스트 5파일의 `Currency.USDT`, `TelegramBotServiceTest`·`PortfolioQueryHttpAdapterTest`의 `ETH`·`USDT` 샘플.
6. `TradingSecurityPolicy`는 코인 지표 경로 하나가 전부라 상수 삭제 시 클래스(와 `SecurityRoutePolicy` 기여) 존폐를 같이 정해야 한다.
7. 지침서 10절의 `KisOrderApi.java:159`는 대상 없음(MARKET 거부는 2곳). `ExceptionResponseTableTest`는 `trading-core` 쪽 `:72-74`.

## 8. 미확인 닫음

**사용자 삭제 cascade는 코인 계좌에서도 주식 계좌와 같은 경로다. 코인 전용 분기는 `requireNoCryptoInFlight` 하나뿐이다.**
- 경로: `TradingUserCascadeListener.onUserDeleted`(`TC/trading/adapter/in/event/TradingUserCascadeListener.java:25-29`) → `UserTradingDataEraseService.cancelOpenOrders`(`:41-55`)가 `accountService.listByUser`로 계좌별 `AccountOpenOrderCancelPort.cancelOpenOrders` 호출 → `AccountOpenOrderCanceller`(`:23-33`)가 전략마다 `orderCancelService.requireNoCryptoInFlight`(`OrderCancelService.java:58-67`)·`stateWriter.pause`·`cancelOpenOrders`. 이어서 `erase`(`:60-66`)가 `deleteByUserId`로 cycle_position → strategy_cycle → strategy → accounts(소프트 삭제 `UPDATE ... WHERE user_id`)를 지운다(`AccountJpaRepository.java:28`, `StrategyJpaRepository.java:98`, `StrategyCycleJpaRepository.java:63`, `CyclePositionJpaRepository.java:160`). 어느 쿼리에도 `broker` 조건이 없다.
- 코인 계좌 차이: 체결 확인 중 주문이 있으면 `OrderCancelException`(`:64`)이 나고, `cancelOpenOrders`의 `catch (RuntimeException)`(`:46-49`)이 `failed=-1`로 흡수해 관리자 알림만 보내고 `erase`는 계속된다. 즉 삭제를 막지 않는다. 코드 삭제 뒤에는 이 분기가 사라진다.
- 계좌 단건 삭제(`AccountCascadeListener.java:29-38`)도 broker 조건이 없다. 주문(`trading.orders`)은 어느 경로에서도 지우지 않는다(주식과 같음).
- C2 완전 삭제 뒤: 코인 계좌 행이 없으면 `listByUser`가 그 계좌를 돌려주지 않고 소프트 삭제 UPDATE는 0행이다. 소프트 삭제된 코인 행을 읽는 곳은 `@SQLRestriction`이 가려 읽지 않는다. `Account.broker`의 enum 역직렬화는 삭제 표시가 아닌 행을 읽을 때만 일어나므로, 단계 P ③의 `live=0` 확인이 전제다. 지침서 15 8절 중단 조건 "삭제 표시된 코인 행을 읽어야 하는 기능"에는 해당하지 않는다. 실검증(0행 정상 종료 테스트)은 T4에서 `UserCascadeEndToEndIT`에 케이스를 더해 닫는다.

## 9. 주식 경로의 MARKET·executionHour

**주식 계좌에서 `MARKET` 주문이나 `executionHour` 값이 만들어지는 경로는 없다. 중단 조건에 걸리지 않는다.**
- 생성 경로는 DCA 규칙 등록·수정 둘이다. `DcaRuleRequest.toDomain`(`DcaRuleRequest.java:41`)은 `orderType != MARKET`이면 `executionHour`를 null로 만든다. `DcaRule` 생성자(`DcaRule.java:43-44`)는 `MARKET`과 `executionHour`의 동시 존재를 강제한다. `requireCompatible`(`:73-75`)은 주식 시장 계좌의 `MARKET`을 거부하고, 등록(`StrategyCreationService.java:57`)과 수정(`DcaConfigService.java:40`)이 둘 다 호출한다.
- `Order.cryptoMarket`(`Order.java:85-88`)의 호출처는 `CryptoDcaRoundExecutor.java:183` 하나다. 주식 배치(`DcaPlanInputCollector` → `DcaCycleOrderStrategy.plan()`)는 `DcaRule.timing()`(MOO/MOC)으로만 주문을 만든다.
- 어댑터 `case MARKET -> throw`는 메리츠:82·토스:183이고 "주식 경로 도달 불가" 주석이 붙어 있다(KIS는 삭제됨).
- 남은 확인은 T2의 주식 DCA 테스트에서 `MARKET`이 한 번도 만들어지지 않는지 보는 것(지침서 10절). 운영 DB에서 주식 계좌의 `order_type='MARKET'` 행 유무는 읽기 전용 SELECT가 필요해 이번엔 조회하지 않았다(findings/20 4-2는 규칙 20행이 전부 바이낸스).

## 10. 사용자 결정이 필요한 것

- `BROKER_WITHDRAWAL_ENABLED`(바이낸스 전용 ErrorCode)도 C5처럼 삭제할지. `DcaSkipReason` 3상수와 `ApiKeyExpirySource.BROKER_API`도 DB·UI 계약이라 같은 판단.
- `TradingSecurityPolicy`를 코인 지표 경로 삭제 뒤 클래스째 없앨지.

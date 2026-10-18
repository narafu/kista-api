# 업비트 계좌 연결 + 코인(BTC/ETH) DCA 1차 — kista-api 설계

- 작성일: 2026-10-17
- 상태: 사용자 승인(섹션 1~4), spec 리뷰 대기
- 상위 설계(UI 기준): kista-ui `docs/superpowers/specs/2026-10-17-crypto-upbit-dca-design.md` (커밋 157c556a, f3f2a1b8) "① 데이터 모델·API 계약" + "⑤ 업비트 키 만료"
- 브랜치/worktree: `feat/crypto-upbit-dca` — `/Users/phs/workspace/kista/kista-api-worktrees/crypto-upbit-dca`

## 목표와 범위

업비트(KRW 마켓) 계좌를 연결하고 BTC/ETH를 DCA 전략으로 매수·매도한다. 코인은 DCA만 지원한다.

1차 제외:
- 통계 `currency` 파라미터 — `TradingStatsService`/`InvestmentPointsQueryService`가 DCA 전략을 집계에서 제외하므로(`TradingStatsService.java:171`) 코인 계좌(DCA 전용)의 KRW 통계는 항상 공집합이다. UI 통화 토글은 1차에 숨긴다(kista-ui-22에 전달 완료).
- 벤치마크·백테스트.

인프라 전제(kista-ui-22 경유 확인): 서버 공인 IP `168.107.63.152`(OCI Reserved, IPv4 고정), 컨테이너에서 `api.upbit.com`·`api.alternative.me` egress 정상, 업비트 키는 사용자별이라 서버 공용 시크릿 없음.

## 작업 묶음

| 묶음 | 내용 | 세션 |
|---|---|---|
| A. 계약 | 섹션 1 — 운영 배포돼도 무해(UPBIT 기본 disabled) | 이 세션 |
| B. 업비트 어댑터·계좌 | 섹션 2 | 이 세션(A 직후) |
| C. 코인 DCA 실행 | 섹션 3 | 새 세션 권장 |

A 커밋 후 openapi 커밋 해시를 kista-ui-22에 전달한다.

## 핵심 결정

1. **코인 실행은 전용 경로(A안)**: 시간 단위 스케줄러 → 계좌별 러너가 조건 평가·금액 산정·주문·기록을 직접 수행한다. 기존 `TradingCandidatePlanner`/`TradingOrderBudgetAllocator`/`CyclePosition`과 무한매수·VR·PRIVACY 공식(정수 수량 경로)은 건드리지 않는다. 기존 파이프라인 확장(B안)은 정수 경로 전면 BigDecimal화(trading-core 약 95파일)와 개장/마감·DST·휴장 가정 해제가 필요해 기각, 별도 모듈(C안)은 DCA 커널·기록·알림을 위해 NamedInterface를 대량 신설해야 해 기각.
2. **금액 기준 시장가 매수는 추정 수량을 기록한다**: 주문 시 `amount ÷ 현재가`(소수 8자리)를 `orders.quantity`에, 기준가를 `price`에 넣고, 체결 후 `filledQuantity`/`filledPrice`를 실측으로 채운다. `orders.quantity NOT NULL`을 유지해 주문 소비처 전반의 null 분기를 피한다.
3. **업비트는 `BrokerCapabilitiesPort`를 구현하지 않는다**: 코인 전용 `CryptoExchangePort`를 신설한다(정수 holdings·LOC/MOC 전제를 피함). 2차 빗썸은 이 포트에 어댑터만 추가한다.

## 섹션 1 — 공유 어휘·API 계약 (묶음 A)

### sharedkernel
- `Market` enum 신설: `US_STOCK`, `CRYPTO_KRW`.
- `Currency`(`broker.domain.model`, KRW/USD/JPY)를 `com.kista.shared.kernel`로 승격한다(복제 금지). 기존 사용처 import만 바꾼다.
- `Broker`에 `market`, `currency` 필드 추가. `UPBIT`(CRYPTO_KRW, KRW) 상수 추가. KIS/TOSS/MOCK은 US_STOCK/USD. 상수명은 DB 문자열이므로 불변.
- `StrategyTicker`에 `market` 필드 추가. `BTC`, `ETH` 추가(`targetProfitRate` 0, CRYPTO_KRW). 업비트 마켓 코드(`KRW-BTC`)는 업비트 어댑터 내부 레지스트리에서만 만든다(`KisExchangeRegistry`와 같은 방식).
- `OrderType.MARKET` 추가.
- `DcaSkipReason`에 `BELOW_MIN_ORDER_AMOUNT`(최소 주문 금액 미달), `EXCHANGE_API_ERROR`(거래소 API 오류), `API_KEY_EXPIRED`(API 키 만료) 추가. 라벨은 `/api/meta`로 노출된다.
- `StrategyType.DCA` capability: `availableTickers`에 BTC/ETH, `orderTypes`에 MARKET 추가.

### 런타임 설정 (운영 안전장치)
- `TradingPolicySettings.defaults()`에서 UPBIT는 `enabled=false`. 로드 시 빠진 키 backfill도 이 기본값을 쓰므로 배포 직후 노출되지 않는다.
- `BrokerSettings`에 `allowedIp`(nullable) 추가. UPBIT 기본값 `168.107.63.152`. `/api/runtime-config`의 `brokers.UPBIT.allowedIp`로 노출.
- 관리자 설정 PUT(`AdminSettingsRequest`)은 빠진 브로커 키를 현재 값으로 유지한다 — 현행 kista-ui 관리자 화면이 UPBIT 키 없이 보내도 깨지지 않게 한다.

### meta
- `brokers[]`에 `market`, `currency`.
- `tickers[]`(TickerMeta)에 `market`.

### 계좌 응답
- `AccountResponse`에 `currency`(broker 파생), `apiKeyExpiresAt`(nullable) 추가. UPBIT는 `accountNo`/`accountNoMasked`가 null.

### 전략 검증 (서버)
- 계좌 broker의 market과 ticker market이 같아야 한다.
- `CRYPTO_KRW` 계좌는 DCA만 생성할 수 있다.
- `orderType`: 코인은 `MARKET`만, 주식은 `MOO|MOC`만.
- `executionHour`(0~23, KST): 코인 필수, 주식은 무시(저장하지 않음).
- 코인 `amount`: 원 단위 정수, 5,000 이상.
- WEEKLY 요일: 코인은 토·일 허용, 주식은 현행대로 평일만.
- 수정(`PUT /api/trading-cycles/{id}/dca-config`): 기존 잠금 정책 유지, `executionHour` 수정 가능.

### DCA 필드 (한 배포 사이클 동안 이중 계약)
- 요청: `amount`와 `amountUsd` 중 하나를 받는다. 둘 다 오고 값이 다르면 400. 도메인 필드는 `amount`(계좌 통화 단위).
- 응답: `amount`, `amountUsd` 둘 다 같은 값. UI 전환 후 `amountUsd` 제거는 별도 작업.
- DB(trading 스키마, root expand/contract 제약 밖): `strategy_dca_version.amount_usd` → `amount` 이름 변경, `execution_hour smallint NULL` + CHECK(0~23) 추가.

### 수량
- DB: `orders.quantity`·`orders.filled_quantity`·`dca_execution.quantity`를 `numeric(20,8)`로.
- 도메인: `Order.quantity`·`filledQuantity`, `DcaExecution.quantity`를 `BigDecimal`로. 응답 DTO의 `quantity`도 BigDecimal(주식은 정수 값이라 JSON 동일).
- 매칭 커널 `PlannedOrder`(Integer)와 무한매수·VR·PRIVACY 공식은 그대로 두고 `Order.fromPlanned`에서 변환한다. 정수 연산이 필요한 주식 경로(배정기, KIS/Toss 와이어 매핑 등, 약 30파일)는 `intValueExact()`로 변환한다.
- `cycle_position.holdings`, privacy 기준표 수량은 변경하지 않는다.

## 섹션 2 — 업비트 어댑터·계좌 (묶음 B)

### 포트
`com.kista.tradingcore.broker.application.port.output.CryptoExchangePort` (`supports(Broker)`로 라우팅):
- 잔고: KRW 가용액, 코인별 보유량(BigDecimal)·평단(`avg_buy_price`)
- 현재가
- 일봉 N개 (KST 09:00 마감 기준)
- 금액 시장가 매수(`ord_type=price`, `identifier` 포함)
- 수량 시장가 매도(`ord_type=market`, `identifier` 포함)
- 주문 조회(uuid 또는 identifier → 상태·체결 수량·평균가)

### 어댑터 `broker/adapter/out/upbit/`
- `UpbitConfig`(RestClient), `UpbitHttpClient`(요청마다 JWT 서명: access_key, nonce, query_hash SHA512 — 토큰 캐시 없음), `UpbitMarketRegistry`(StrategyTicker → `KRW-BTC`), `UpbitExchangeAdapter`(`CryptoExchangePort`), `UpbitAuthApi`(`BrokerConnectionTestPort` — `/v1/accounts`로 키 검증, `/v1/api_keys`의 `expire_at` 조회).
- 구현 전 업비트 공식 문서로 확인할 것: JWT 서명 알고리즘, 에러 바디 형식, `expire_at` 형식, 일봉 API의 진행 중 봉 포함 여부, `identifier` 조회 방식.

### 에러 매핑
업비트 에러 바디 `{error:{name,message}}`의 `name`을 kista `ErrorCode`로 매핑해 ProblemDetail `code`로 싣는다. 업비트 원문은 로그로만 남긴다.

| 업비트 name | ErrorCode |
|---|---|
| `no_authorization_ip` | `BROKER_IP_NOT_ALLOWED` (신규) |
| `invalid_access_key`, `jwt_verification` | `BROKER_CREDENTIAL_INVALID` (기존) |
| `out_of_scope` | `BROKER_PERMISSION_DENIED` (신규) |
| 그 외 | 기존 일반 실패 매핑 |

429는 `BrokerRateLimitException`. `TradingExceptionHandler`의 "KIS Rate Limit" 하드코딩 제목은 브로커 중립 문구로 바꾼다.

### 계좌 등록
- trading 마이그레이션: `accounts.account_no` nullable, `accounts.api_key_expires_at timestamptz NULL` 추가.
- UPBIT 등록 요청은 `accountNo`를 받지 않는다(오면 무시). 계좌번호는 null로 저장한다.
- 중복 판정: UPBIT는 `account_no_hash`에 access key의 HMAC을 넣어 기존 unique 인덱스를 재사용한다.
- `BrokerConnectionTestPort.verifyAccount` 반환을 `String` → record(`brokerAccountCode`, `apiKeyExpiresAt`)로 바꾼다(KIS/Toss/Mock은 expiresAt null).
- 연결 테스트 엔드포인트(`POST /api/accounts/connection-tests`)가 UPBIT를 지원한다.

### 키 수정
- `PUT /api/accounts/{id}/credentials` {appKey, secretKey} 신설. 소유권 검증 → 연결 테스트 통과 시 저장, 만료일 갱신. 모든 브로커 공통.

### UPBIT 포트폴리오
- `GET /api/accounts/{id}/crypto-portfolio` 신설: KRW 예수금, 코인별 보유량·평단·현재가·평가액(KRW). 기존 `/portfolio`(USD 필드명·정수 holdings)와 경로를 분리해 openapi 스키마를 단일 타입으로 유지한다(같은 경로 oneOf 분기는 UI 타입 생성을 복잡하게 만든다). 주식 계좌로 호출하면 400.

## 섹션 3 — 코인 DCA 실행 (묶음 C)

> 2026-10-18 개정: 묶음 C 착수 시 코드 탐색으로 전제를 정정했다(커널 진입점·일봉 개수·복구 순서·주문 혼입). 사용자 결정 3건(일시 오류 정책·체결 알림 계약·이월 Minor) 반영.

### 트리거
- trading-core `CryptoDcaScheduler`: cron `0 */10 * * * *`(KST), 락 `crypto-dca`, `scheduler.enabled` 게이트.
- 매 틱 처리 순서(계좌별 순차, 계좌 단위 실패 격리):
  1. **복구·동기화 — executionHour와 무관하게 매 틱**: 그 계좌의 PLANNED 코인 주문은 `findOrderByIdentifier`로 복구, PLACED 코인 주문은 `findOrderByUuid`로 체결 확인(아래 "중복 주문 방지").
  2. **신규 실행**: `executionHour == 현재 KST 시`이고 오늘(KST) `dca_execution`이 없으며 오늘 PLANNED/PLACED 주문도 없는 ACTIVE 코인 DCA 전략만.
- 10분 주기인 이유: kista-trading 재배포(stop-first, 최대 약 200초) 중 정각을 놓쳐도 같은 시간대 안에서 따라잡는다. 같은 이유로 일시 오류도 시간대 안에서 재시도한다(아래 "오류").
- `trade_date` = 실행 시점 KST 날짜.
- 실행일 판정: `DcaSchedule.isExecutionDay(kstDate, config, d -> true)` — 말일 보정만 적용되고 휴장 보정은 없다(24/7).

### 처리 순서 (계좌별 순차 — KRW 잔고 공유)
1. 계좌 잔고 1회 조회(`CryptoExchanges.of(broker).getBalance`).
2. 전략별: 실행일 → 오늘 실행 여부 → 조건 평가.
3. 금액 산정 — 매수 `min(amount, floor(KRW 가용액 ÷ (1 + 수수료율)))`(정수 KRW), 매도 수량 `amount ÷ 현재가`(8자리 FLOOR) 후 `min(보유 가용량)`. 수수료율은 상수(업비트 KRW 0.05%) — `ponytail:` 주석으로 거래소별 수수료 조회가 필요해지면 확장한다고 표시.
4. 산정 금액 < 5,000원(매도는 `수량 × 현재가` 기준) → `BELOW_MIN_ORDER_AMOUNT` skip + 알림. 매도인데 보유량 0 → `NO_HOLDINGS`.
5. 주문, 계좌 잔고 로컬 차감 후 다음 전략.

### 조건 평가
- 커널 `DcaCycleOrderStrategy.decide(DcaInputs)`는 정수 수량·가격 캡·`BELOW_ONE_SHARE`가 섞여 있어 코인에 그대로 쓰지 않는다. **조건 판정만 순수 함수로 추출**(matching 커널, 예: `DcaConditions.evaluate`)해 주식 `decide`와 코인 러너가 공유한다. 코인 금액 산정은 코인 러너 쪽(위 3·4).
  - 회귀 기준: 기존 `DcaCycleOrderStrategy` 단위 테스트가 수정 없이 통과.
  - 지표 계산은 `DcaIndicators`(SMA·RSI Wilder) 그대로.
- 일봉: 주식과 같은 **250봉**. 업비트 어댑터 `getCompletedDailyCandles`에 `to` 파라미터 페이징을 추가해 200봉 초과를 지원한다(RSI Wilder는 이력 길이에 따라 값이 달라지므로 주식과 같은 길이를 맞춘다).
- 신선도: 업비트 일봉은 KST 09:00에 시작한다. 마지막 완성 봉의 `kstDate` 기대값 = 실행 시각이 09시 이후면 `오늘 − 1일`, 09시 이전이면 `오늘 − 2일`. 다르면 `DATA_UNAVAILABLE`. "전일 종가" = 마지막 완성 봉 종가.
- AVG_PRICE = 업비트 `avg_buy_price` 기준(보유 없으면 조건 불충족 규칙은 주식과 동일).
- FEAR_GREED: 코인 전용 `CryptoFearGreedIndexPort`(주식 `FearGreedIndexPort`는 변경 없음). CRYPTO_KRW는 trading-core `CryptoFearGreedIndexAdapter`가 alternative.me를 직접 호출(CNN 어댑터와 같은 패턴, 실패 시 empty). 라벨 출처 표시는 UI 담당.

### 중복 주문 방지
1. 주문 행을 PLANNED로 저장(`orderType=MARKET`, `quantity`=추정 수량, `price`=기준가 — 핵심 결정 2). `orders.price numeric(12,2)`는 BTC 원화 가격을 담는다.
2. 업비트 주문 시 `identifier` = 주문 UUID.
3. PLACED 갱신(`markPlaced(id, uuid)`).
4. `dca_execution` 기록(`DcaExecutionRecorder`, `DcaRoundSkippedEvent` 재사용).
- 결과 불명 실패(`BrokerApiException.isRejection == false` — I/O·5xx): skip을 기록하지 않고 PLANNED로 남긴다.
- 복구(매 틱 1단계): PLANNED 주문을 `findOrderByIdentifier`로 조회한다.
  - 있음 → PLACED 갱신 + `dca_execution` 기록(재접수 없음).
  - 없음 → `markFailed`. 실행 시간대 안이면 다음 틱에 새 주문(새 identifier)으로 재시도, 시간대를 넘겼으면 `EXCHANGE_API_ERROR` skip.
- 체결 확정(매 틱 1단계): PLACED 코인 주문을 `findOrderByUuid`로 조회해 `executedVolume > 0`이면 `markFilled(실측 수량, 평균가)`(업비트 시장가 매수 완료가 `done`/`cancel` 중 무엇인지 미확인 — state 대신 체결량으로 판정). `executedVolume == 0`이고 `cancel`이면 `markCancelled`.
- 코인 주문은 정수 경로(`OrderSubmitter`·`TradingReporter`·`Order.toPlanned()`)를 타지 않는다.

### 코인 주문 혼입 감사 (필수)
코인 주문이 `orders`에 생기면 사이클·계좌로 좁혀지지 않은 기존 조회가 코인 주문을 집어 `BrokerRouter(UPBIT)` IAE나 `intValueExact` 예외를 낼 수 있다. 다음 경로를 전수 확인해 코인 제외 또는 코인 분기를 넣는다(계획 태스크로 명시).
- `OrderPort.findAll(from,to)`·`findDistinctAccountIdsByTradeDateBetween` 소비처(`TradingInternalQueryController` → root 관리자/리포트).
- 계좌 미체결 취소(`OrderCancelService`·`AccountOpenOrderCanceller`) — 코인 계좌 삭제·키 교체 시.
- `TradingBatchResumer`·`OrderSubmissionReconciler`·`SubmittingOrderAwaiter` — 코인 주문은 SUBMITTING을 쓰지 않음을 확인.
- `findByUser`/`findFilledByUser`/`findByStrategyId` 소비처(포트폴리오·통계·전략 주문 이력) — 정수 변환 지점 확인.

### 체결 알림
- `TradeEventMessage.quantity`를 `Integer` → `BigDecimal`로 넓힌다(사용자 결정). JSON number라 와이어 호환이지만 root·trading-core를 같은 커밋으로 배포하고, 타입 변경을 "업비트 ui" 세션에 전달한다.
- 코인 체결(`markFilled` 시점)에 BUY/SELL 알림(Telegram/FCM/SSE)을 발행한다 — 주식 `TradingReporter` 경로가 아닌 코인 전용 이벤트.

### 오류
- 일시 오류(rate limit·5xx·I/O, 잔고·시세·일봉 조회 포함): 그 시간대 안에서는 기록 없이 다음 틱 재시도. **시간대 마지막 틱(분 ≥ 50)** 에서도 실패하면 `EXCHANGE_API_ERROR` skip + 사용자·관리자 알림(사용자 결정).
- 인증 실패(`BrokerCredentialException` — 키 만료·무효): 즉시 `API_KEY_EXPIRED` skip + 알림.
- 그 밖의 확정 거부(4xx `rejected`): 즉시 `EXCHANGE_API_ERROR` skip + 알림.
- 계좌 단위 실패 격리.

### 다음 실행일
- `DcaSummaryAssembler`는 CRYPTO_KRW일 때 미국 캘린더 변환·`DstInfo.nextTradeDate()` 대신 KST 날짜와 `d -> true`로 `nextExecutionDay`를 계산한다(오늘 실행 시각 전이면 오늘 포함).

### 키 만료 알림
- 매일 10:00 KST trading-core 작업(락)이 `api_key_expires_at`이 D-30·D-7·당일인 계좌에 이벤트를 발행하고 `tradingcore.notify`가 발송한다.

### A·B 이월 Minor (사용자 결정으로 포함)
- 관리자 계좌 목록 UPBIT 계좌번호 `****` 표시(`AdminAccountResponse`/`AdminAccountItem`) — 계좌번호 없음을 그대로 표시.
- crypto-portfolio 현재가 누락 시 `evalAmountKrw`를 null로 두고 총자산 집계에서 제외(응답 계약 변경 → "업비트 ui" 전달).
- 업비트 키 교체 중복 경합의 unique 위반을 500이 아닌 409로.
- root `GlobalExceptionHandler`의 `AdminBrokerRateLimitException` 제목 "KIS Rate Limit" → 브로커 중립 문구.
- 제외: Toss 키 교체 시 Redis 토큰 무효화(코인 무관, 별도 작업).

### 문서 동기화
`flows.yml`(코인 DCA 흐름·업비트 통신·스케줄러), `docs/agents/scheduler-time-table.md`, 모듈 문서(`modules/tradingcore/broker.md`·`trading.md`·`account.md`·`matching.md`), README 드리프트 확인.

## 섹션 4 — 테스트·검증

### 단위
- 전략 생성 검증: market 일치, 코인 DCA 전용, orderType, executionHour 필수, 최소 5,000, `amount`/`amountUsd` 충돌.
- 코인 금액 축소·5,000 미달 skip 경계, 매도 수량 8자리 FLOOR.
- 직전 완성 일봉 선택(executionHour 09시 전/후), 말일 보정, 주말 실행.
- 업비트 JWT 서명(공식 문서 예시 대조), 에러 name → ErrorCode 매핑.
- `TradingPolicySettings`: UPBIT 기본 disabled, 관리자 PUT 누락 키 보존.

### 통합 (`*IT`, 실 DB)
- 마이그레이션: `account_no` nullable, 수량 `numeric(20,8)`, `amount` 이름 변경, `execution_hour`.
- UPBIT 계좌 중복 판정(access key 해시).
- 중복 주문 방지: PLANNED 잔존 상태 재실행 시 identifier 조회로 복구, 재접수 없음(업비트 HTTP stub).
- 주식 경로 회귀: `Order` 수량 BigDecimal 전환 후 기존 trading-core 테스트 전체 통과.

### 계약
- openapi 재생성 → kista-ui-22가 `gen:types`로 diff 확인.
- `InternalApiContractTest`, `ArchitectureMapTest`, Modulith `verify()`.

### 미검증 (사유: 실키·실계좌 필요)
- 실키 기반 업비트 응답(`/v1/accounts`, `/v1/api_keys`의 `expire_at`, 유효기간 1년 여부).
- 실제 시장가 주문·체결.
- 대체 수단: HTTP stub 테스트 + 업비트 공식 문서 예시 대조.

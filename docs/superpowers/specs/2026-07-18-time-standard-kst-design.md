# 시간 기준 KST 통일 설계

날짜: 2026-07-18
상태: 설계 확정 대기 (사용자 리뷰 전)

## 배경

코드 리뷰 중 UTC/KST 혼용으로 인한 변환 누락·이중 변환 의심이 제기되어 전수조사를 수행했다.
현행 구조는 "DB `trade_date` = UTC(=US 거래일), 도메인/API = KST"로 persistence 경계마다
`TradeDateConverter`(±1일)를 적용하는데, 변환 지점이 15곳 이상으로 흩어져 있어 한 곳만 누락돼도
조용한 off-by-one이 된다 (실제 발생 — 아래 확증 버그 ①).

### 전수조사 결과 (2026-07-18)

정합 확인: zone 없는 `now()` 0건, 무타임존 TIMESTAMP 컬럼 0건, `orders.trade_date`·KIS·Toss·Alpaca 경로 변환 정합.

확증 버그:

| # | 위치 | 내용 |
|---|---|---|
| ① | `PrivacyTradePersistenceAdapter.findSeedPreviewBase` (:120-122) | 형제 메서드와 달리 변환 없이 KST 오늘을 DB 컬럼에 직접 비교 → 임계값이 하루 높아 오늘자 시드 미리보기 기준표를 항상 누락 |
| ② | `AccountStatisticsService` (:195,200), `StatsService` (:70) | KST 날짜를 UTC 자정으로 Instant화 (동일 목적의 `FearGreedQueryService`는 KST 자정) → 04:30 배치 스냅샷이 from/to 경계에서 하루 오분류 |
| ③ | `OrderPersistenceAdapter.findTradeDatesByStrategyId` (:128) | UTC 거래일 리스트를 `toKst` 없이 반환 → `AdminQueryService` 경유 어드민 응답이 하루 어긋남 |
| ④ | `DstInfo.nextTradeDate` (04:00 경계) vs 마감 배치(04:30 실행) | KST 04:00~04:30 창에서 미리보기 거래일(내일)과 임박한 배치 대상(오늘)이 불일치 |

구조적 문제:
- `privacy_trade_bases.release_date`를 엔진 경로는 "US 거래일"처럼 ±1 변환하고, 어드민 표시는 원본 그대로 노출 — 같은 컬럼에 두 기준.
- `PrivacyService`의 `toKst` → adapter의 `toUtc`가 왕복 상쇄되어 우연히 FIDA 원본이 저장됨. `PrivacyService:25` 주석("FIDA는 UTC 송신")은 사실과 다름.

### 확정된 사실 (사용자 확인)

- **`release_date`는 FIDA가 보낸 원본 그대로의 KST 발행일이며, 거래일이 아니다.**
- 엔진의 ±1일은 시간대 변환이 아니라 **"기준표는 발행 다음날 KST 거래일의 세션에 적용"이라는 도메인 규칙**이다.

## 결정 사항

1. **기준 재정의**: B안 — KST 통일 (사용자 결정)
2. **변경 허용 범위**: API breaking change + kista-ui 동시 수정까지 전부 허용
3. **`privacy_trade_bases.release_date`**: FIDA 원본(KST 발행일) 그대로 저장 — 마이그레이션·리네임 없음
4. **외부 원본 참조 테이블**(`us_market_holidays`, `market_index_prices`): US 기준 유지, 해당 adapter 내부에서만 변환

## 새 시간 기준 (단일 규칙)

| 데이터 성격 | 기준 | 예 |
|---|---|---|
| 사용자 거래 데이터의 거래일 | **KST 일자** (매매가 실행·정산되는 KST 아침이 속한 날) | `orders.trade_date`, `strategy_cycle.start_date/end_date`, 통계 from/to |
| 발행/수신 원본 일자 | **원본 그대로** (변환 금지) | `privacy_trade_bases.release_date` (KST 발행일) |
| 외부 원본 참조 데이터 | **원본 기준** + adapter 내부 변환 | `us_market_holidays`(US 달력일), `market_index_prices`(US 거래일) |
| 절대시각 | **Instant / TIMESTAMPTZ** (현행 유지) | `created_at`, 토큰 만료, 스냅샷 시각 |

파생 규칙:
- persistence 레이어에서 거래일 ±1 변환 전면 제거 — `orders` 경로의 `TradeDateConverter` 호출 삭제.
- KST↔US 거래일 변환은 **US 기준 외부 데이터를 만나는 adapter 내부에만** 존재: `KisTradingApi`(요청/응답 일자), `MarketCalendarPersistenceAdapter`(휴장일 대조), 지수 피드 어댑터. 헬퍼는 `toUsTradeDate(kst)` / `toKstTradeDate(us)`로 리네임해 의도를 명시.
- Instant ↔ KST 일자 경계는 `atStartOfDay(TimeZones.KST)` 단일 관용구 (`FearGreedQueryService` 패턴).
- Toss 어댑터는 이미 KST 기준 — 변경 없음.

## 컴포넌트별 설계

### 1. DB 마이그레이션 (V27)

- `UPDATE orders SET trade_date = trade_date + 1` — US 거래일 → KST 거래일 균일 shift.
  UNIQUE/인덱스는 값 shift에 영향 없음. `deleted_at` 행 포함 전체 shift (소프트 삭제 행도 동일 기준 유지).
- `privacy_trade_bases`: 변경 없음 (원본 보존 확정).
- `us_market_holidays`, `market_index_prices`: 변경 없음 (US 기준 유지 확정).
- 컬럼 주석(`COMMENT ON COLUMN orders.trade_date`)을 "KST 거래일"로 갱신.
- **배포 절차**: 스케쥴러 창(22:30, 04:30 KST)을 피해 배포. 단방향 마이그레이션이므로 롤백 시 역shift SQL 필요 — 배포 직후 최신 거래일 샘플 검증 쿼리로 확인.

### 2. orders 경로 (`OrderPersistenceAdapter`, `KisTradingApi`)

- `OrderPersistenceAdapter`: `toUtc`/`toKst` 호출 전부 제거 — 도메인 KST 일자를 그대로 저장/조회. 버그 ③ 자연 해소.
- `KisTradingApi`: KIS 요청·응답 일자 변환(현행 유지, 리네임된 헬퍼 사용). KIS가 US 거래일 기준인 유일한 지점.
- 버그 ① 성격의 재발 방지: 변환 자체가 사라져 원천 차단.

### 3. privacy 경로 (발행일 도메인 규칙 명문화)

- `PrivacyService`: 진입부 `toKst` 왕복 제거 — FIDA 원본 일자를 그대로 도메인에 전달. 잘못된 주석 삭제.
- `FidaOrderCommand.tradeDate` → `releaseDate` 리네임 (`FidaOrderRequest`/`FidaOrderResponse` 동일) — 거래일이 아님을 타입 수준에서 명시. FIDA 송신측 JSON 키 변경 여부는 구현 시 확인 (내부 API라 조율 가능; 불가 시 JSON 키만 유지하고 자바 필드만 리네임).
- `PrivacyTradePersistenceAdapter`: `toUtc`/`toKst` 제거. 발행일→거래일 매핑은 도메인 규칙 헬퍼로 대체:
  - `domain/model/privacy/PrivacyDates.releaseDateFor(kstTradeDate) = kstTradeDate - 1일` (기준표는 발행 다음날 KST 거래일에 적용)
  - `PrivacyDates.tradeDateOf(releaseDate) = releaseDate + 1일`
  - `findTodayTrade(today)`: `release_date >= releaseDateFor(today)` 조회 (현행 동작과 동일 결과, 의미만 교정)
  - **버그 ① 수정**: `findSeedPreviewBase`도 동일 규칙 적용 — `release_date >= releaseDateFor(todayKst)` (현재는 `>= todayKst`로 하루 높음)
- 어드민 표시(`toView`)·`PrivacyTradeBaseView.releaseDate`: 원본 그대로 (현행 유지) — 이제 예외가 아니라 규칙.

### 4. 통계 경계 (버그 ② 수정)

- `AccountStatisticsService.resolveFrom/resolveTo`, `StatsService.getEquityCurve`: `atStartOfDay(ZoneOffset.UTC)` → `atStartOfDay(TimeZones.KST)`.
- from/to 의미 확정: **KST 달력일** — KST day D의 04:30 배치 스냅샷은 day D 범위에 속한다.
- `StatsService` 벤치마크 대조부: `market_index_prices`가 US 기준을 유지하므로 KST 거래일 ↔ US 거래일 변환을 리네임된 헬퍼로 수행 (현행 로직 유지, 호출 지점만 정리).

### 5. 거래일 경계 SSOT (버그 ④ 수정)

- "다음/현재 거래일" 판정 경계를 실제 마감 배치 시각 **04:30 KST 단일 상수**로 정렬 — `DstInfo.SCHEDULER_RUN_TIME`(04:00)과 `TradingCloseScheduler` cron(04:30)의 30분 불일치 제거.
- preview·수동실행·`findTodayTrade` today 산출이 모두 동일 함수를 사용.
- **마감 배치의 tradeDate 산출(`LocalDate.now(KST)`) 동작은 불변** — 매매 로직 변경 없음을 회귀 테스트로 보증.

### 6. 어드민/명명 정리

- `AdminReorderCommand.tradeDateKst` 등 `~Kst` 접미사: 전 구간 KST 통일 후에는 잉여 — `tradeDate`로 리네임 (KST가 기본이라는 규칙이 문서 SSOT).
- `AdminQueryService.findTradeDatesByStrategyId` 소비처: adapter 변환 제거로 자연히 KST 반환 (버그 ③ 해소 확인 테스트 추가).

### 7. kista-ui 영향

- 대부분의 API 응답은 이미 KST — **변화 없음**.
- 어드민 privacy 기준표 화면: `releaseDate` 의미가 "KST 발행일"로 확정 (값 변화 없음). 라벨/툴팁 표기만 정합 확인.
- `FidaOrderResponse.tradeDate` → `releaseDate` 리네임 시 FIDA 연동측(내부 API) 필드 동기화.

### 8. 문서 갱신

- `docs/agents/constraints.md` "tradeDate 변환 정책" 섹션 전면 재작성 — 새 단일 규칙 표 반영, "FIDA 외부 입력(UTC)" 오기 정정 (실제: KST 발행일 원본).
- `CLAUDE.md` 날짜 변환 정책 요약, `docs/agents/architecture.md`·`workflow.md`의 관련 서술 갱신.

## 오류 처리·경계 사례

- **마이그레이션 전후 혼재 방지**: Flyway는 앱 기동 시 실행되므로 신규 코드와 원자적으로 함께 배포됨. 구버전 코드가 shift된 DB를 읽는 창이 없도록 단일 인스턴스 배포 확인.
- **주말/휴장일**: `findTodayTrade`의 `>=` + 최소값 조회 관용구 유지 — 토요일 아침 배치가 금요일 발행 기준표를 인식하는 현행 동작 보존.
- **DST**: 세션 시각 계산(`DstInfo`)은 본 설계 범위 밖 — 거래일 경계 상수만 04:30으로 정렬.

## 테스트 전략

- 확증 버그 4건 각각 회귀 테스트 (수정 전 실패 → 수정 후 통과 확인).
- `OrderPersistenceAdapter`·`PrivacyTradePersistenceAdapter` 저장↔조회 왕복 테스트: KST 일자 불변 검증.
- 마감 배치 tradeDate 산출 불변 회귀 테스트 (매매 동작 무변경 보증).
- 통계 경계: KST 04:30 스냅샷이 해당 KST 일자 범위에 포함되는 경계 테스트.
- V27 마이그레이션 검증: 로컬 DB에서 shift 전후 대표 행 대조.

## 범위 제외

- `DstInfo`의 미국 세션 시각 계산 로직 변경
- Toss/FearGreed/KB Land 어댑터 (이미 정합)
- `created_at` 등 절대시각 컬럼 (현행 유지)

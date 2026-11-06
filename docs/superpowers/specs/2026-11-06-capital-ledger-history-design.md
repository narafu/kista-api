# 투자금 이력·장부가 실현손익 설계 (CQ-35 + CQ-36(c) + CQ-33 체인 surplus)

## 왜

- **CQ-35**: 투자금 추가·인출(`CapitalInjectionService`)이 `strategy_cycle.start_amount`와 최신 `cycle_position` 행을 제자리 갱신하고 이력은 `log.info`뿐이다. 통계 원금선은 날짜와 무관하게 현재 `startAmount`를 써서, 06-20에 추가하면 06-01~06-19 원금선이 소급으로 바뀐다. 최신 행이 며칠 전인 정지 전략은 과거 자산값도 바뀐다.
- **CQ-36(c)**: VR 롤오버는 보유분을 팔지 않고 승계하는데 `endAmount`가 시가 평가라, 요약의 "실현손익" 합계에 팔지 않은 평가익이 섞인다.
- **CQ-33(체인 surplus)**: `TradingStatsService.chainAdjustment`의 surplus가 후속 사이클의 *현재* `startAmount`를 써서, 회전 뒤 추가·인출하면 자산선과 원금선이 같이 움직인다.

## 운영 실측 (2026-11-06, 읽기 전용)

- 활성 전략 8개, 활성 사이클 8개(7개가 10-24 이후 시작). **살아있는 종료 사이클 0개, VR 롤오버 0회**(종료 31개는 모두 삭제 전략 소속).
- SELL 체결: INFINITE 58, PRIVACY 98, VR 0.
- kista-trading 로그는 재기동(11-06 07:46) 이후분뿐이고 파일 로그가 없어, 과거 투자금 추가·인출을 복원할 근거가 없다.
- 결론: **백필하지 않고 시행일 이후만 기록한다.** 잃는 통계가 사실상 없다.
- 활성 사이클 첫 스냅샷 장부가 대조: INFINITE 2·PRIVACY(11-04)는 startAmount와 일치, VR 2는 시가·평단 차이뿐, DCA 2는 V14 보정분(영구 사이클). **PRIVACY(10-24)는 원장 변화(+189주, 예수금 −3,440.22)와 체결 주문 11건 합(+103주, −4,672.66)이 86주·$1,232.44 어긋난다** — 기록 없는 투자금(주식) 추가나 보정으로 추정. 이 사이클은 전량 청산으로 끝나므로 실현 = 종료 − 시작(§3)이라 누락 영향이 없다. 장부가 식이 쓰이는 보유 승계 종료는 VR뿐이고 VR은 투자금 추가·인출을 지원하지 않아, 배포 전 기록 누락이 새 정의에 닿지 않는다(플래그 불필요). 불일치 자체는 별도 백로그 항목으로 남긴다.

## 목표 / 비목표 / 안티목표

- 목표
  - 투자금 추가·인출 뒤에도 과거 날짜의 원금선·자산선이 바뀌지 않는다.
  - 요약 실현손익 = 매도로 확정한 금액(평균단가법). 실현 + 미실현 총합은 지금과 같다.
  - 체인 surplus가 회전 시점의 후속 사이클 시작금액을 쓴다.
- 비목표
  - 사이클 단위 지표(성과 목록 pnl·returnRate, 승률, 평균 수익률, 체인 보정의 realized)는 **지금처럼 구간 손익(endAmount − startAmount)** 을 유지한다. VR 사이클은 "N주 기간"이 단위이고, 매도가 드물어 실현 기준 승률은 무의미하다. kista-ui 라벨("종료 손익 / VR은 롤오버 평가액 기준", 39e4c77e)도 이 의미다.
  - CQ-33의 다른 항목(롤오버 지연 구멍, 본전=패배, 수동 보정 날짜 어긋남, TtlResultCache 세대, 이벤트 미발행, PRIVACY 초과분)은 다루지 않는다.
  - 과거 백필.
- 안티목표
  - 체결 반영 지점(마감 리포트·`CryptoLedgerRecorder`·수동 보정·`CancelledOrderFillRecorder`)에 손익 기록을 심는 것. 장부가 항등식으로 불필요하다(아래).
  - 스냅샷 제자리 갱신을 append로 바꾸는 것. append하면 INFINITE 리버스 판정·별점가·이력이 왜곡된다(`CapitalInjectionService` 주석).
  - 매매 공식·VR 공식 변경. 이 설계는 통계 조회와 투자금 기록만 바꾼다.

## 핵심 근거: 장부가 항등식

`AccountBalance.applyExecutions`는 평균단가법이다(매수는 원가 가산, 매도는 평단 불변). 장부가 `B = usdDeposit + holdings × avgPrice`(avgPrice null이면 0)로 두면:
- 매수: 예수금 −금액, 원가 +금액 → B 불변
- 매도: 예수금 +체결금액, 원가 −avg×수량 → B += (체결가 − avg) × 수량 = 실현손익
- 투자금 추가·인출: B += 원금 변화량(예수금 + 주식 원가)

따라서 **사이클 실현손익 = B(마지막 스냅샷) − B(첫 스냅샷의 갱신 전 값) − Σ사이클 원금 변화량**이고, 같은 날 체결 순서·수동 보정(`applyExecutions` 후 append)·취소 체결분(마감 리포트가 반영)에 무관하게 성립한다. VR 재설정·적립금(recurringAmount)의 자본 흐름은 새 사이클 첫 스냅샷(조정 후 잔고)에 들어가므로 사이클 내부 식에 영향이 없다. VR 재설정 종료도 롤오버와 같은 승계라 같은 식을 쓴다 — 옛 사이클의 마지막 스냅샷이 조정 전 잔고다(`CycleSnapshotCreator.reconfigureVrCycle`이 `findLatestOne`으로 종료금액을 계산).

허용 오차: `cycle_position.avg_price`가 `numeric(20,2)`라 체결마다 holdings × 0.005 이하의 반올림 잡음이 생긴다. 테스트는 정확값이 나오는 입력을 쓰거나 센트 단위 허용 오차로 단언한다.

## 결정 사항

### 1. 이력 테이블 `trading.cycle_capital_event` (trading-core V15)

| 컬럼 | 타입 | 뜻 |
|---|---|---|
| id | uuid PK | UUID v7(`@UuidGenerator VERSION_7`) |
| strategy_cycle_id | uuid NOT NULL, FK strategy_cycle ON DELETE CASCADE | 대상 사이클 |
| cycle_position_id | uuid NOT NULL, FK cycle_position ON DELETE CASCADE | 제자리 갱신된 스냅샷 행 |
| kind | VARCHAR(20) NOT NULL | `INJECT` / `WITHDRAW` (`@Enumerated(STRING)`) |
| deposit_delta | numeric(20,2) NOT NULL | 예수금 변화(인출은 음수) |
| holdings_delta | numeric(20,8) NOT NULL | 보유수량 변화(주식 추가만, 그 외 0) |
| principal_delta | numeric(20,2) NOT NULL | startAmount 변화 = deposit + 주식 원가(인출은 음수) |
| occurred_at | timestamptz NOT NULL | 발생 시각(`Instant.now(clock)`) |
| created_at | timestamptz NOT NULL DEFAULT now() | `BaseCreatedAtEntity` |

- 인덱스: `(strategy_cycle_id)`.
- 소프트 삭제 컬럼 없음 — 사이클·전략이 소프트 삭제되면 통계가 사이클을 안 읽으므로 이벤트도 같이 빠진다.
- 날짜 버킷은 `trade_date`가 아니라 `occurred_at`을 스냅샷 `createdAt`과 같은 KST 규칙(`atZone(TimeZones.KST).toLocalDate()`)으로 자른다. 기준이 다르면 CQ-33 수동 보정의 날짜 어긋남이 재발한다.
- 위치: 도메인 `trading/domain/model/CycleCapitalEvent`(record), 포트 `trading/application/port/output/CycleCapitalEventPort`(`save`, `findByCycleIds(Collection<UUID>)`), 어댑터 `trading/adapter/out/persistence`. stats는 trading "port"/"domain"으로 읽는다(기존 방향과 같음).

### 2. 기록 지점

- `CapitalInjectionService.inject`/`withdraw`가 `updateBalance`·`updateStartAmount`와 **같은 트랜잭션**에서 이벤트 1건을 저장한다. `cycle_position_id = latest.id()`.
- 시드 수정(`StrategyService.updateSeed`)은 이벤트를 남기지 않는 **소급 정정**이다. VR·DCA는 `update()`(`StrategyService.java:179-184`)에서 이미 거절되고, `updateSeed`는 `holdings=0`만 검사한다 — INFINITE·PRIVACY는 전량 매도 시 사이클이 끝나므로 이는 첫 매수 전과 같다. `updateSeed`에 **그 사이클에 투자금 이벤트가 있으면 거절**을 추가한다(이벤트 뒤 startAmount를 덮으면 원금 역산이 어긋난다). 문구: "투자금 추가·인출 이력이 있는 사이클은 예수금을 수정할 수 없습니다. 투자금 추가·인출을 이용해야 합니다."

### 3. 통계 소비 (`TradingStatsService`)

- 원금 시계열: `principalOn(date) = startAmount − Σ principal_delta (이벤트 KST 일자 > date)`. equity-curve 원금 합산이 이것을 쓴다. 요약 `activePrincipal`·성과 목록의 `principal()`은 현재 `startAmount` 그대로.
- 자산 시계열: 이벤트가 갱신한 스냅샷 행 R은 "갱신 전 값이 R.createdAt에, 갱신 후 값이 이벤트 시각에 생긴 것"으로 본다. 통계가 positions 목록을 만들 때 R을 갱신 전 값(`usdDeposit − Σdeposit_delta`, `holdings − Σholdings_delta`, 같은 R에 이벤트가 여럿이면 시각순으로 누적 복원)으로 바꾸고, 각 이벤트 시각에 그 시점 값의 가상 스냅샷을 끼워 넣는다. 이후 carry-forward 로직은 그대로.
  - 가상 스냅샷의 `avgPrice`는 R의 현재 값을 쓴다(종가 없을 때 평단 대체 평가에서만 쓰이는 값이라 오차 허용).
- 체인 surplus: `start_{i+1}`을 `startAmount_{i+1} − Σ(그 사이클 전체 이벤트 principal_delta)`(회전 시점 시작금액)로 바꾼다. 체인 realized(구간 손익)는 그대로.
- 요약 실현/미실현(`toTypeStats`):
  - `total = Σ종료(endAmount − startAmount) + Σ진행(최신 자산 − startAmount)` — 지금 realized + unrealized와 같은 값.
  - `realizedPnl = Σ종료 사이클 실현`. 종료 사이클 실현은:
    - 마지막 스냅샷 holdings = 0(전량 청산 — INFINITE·PRIVACY 전부, 보유 0으로 끝난 VR): 구간 손익 `endAmount − startAmount`. 다 팔았으니 사이클 시작 기준 전체가 실현이다. 장부가 식을 쓰면 초기 보유분 등록(평단 ≠ 시작 종가) 사이클에서 등록 전 평가익이 실현에 섞이고 보유 0인데 반대 부호 미실현이 남는다(유령 미실현).
    - 마지막 스냅샷 holdings > 0(보유 승계 종료 — VR 롤오버·재설정): `bookRealized = B(마지막 스냅샷) − (B(첫 스냅샷) − Σ첫 스냅샷 행에 걸린 이벤트 principal_delta) − Σ사이클 이벤트 principal_delta`. 이벤트 항은 지금 VR엔 항상 0이지만 일반식으로 둔다.
    - 한계: VR 첫 사이클을 초기 보유분으로 등록하고 그 주식을 팔면 등록 전 평가익이 실현에 섞이고, 같은 양이 반대 부호로 진행 중 후속 사이클 미실현에 들어간다(총합 불변). VR 매도가 드물어 수용.
  - `unrealizedPnl = total − realizedPnl` → 총합이 구조적으로 보존된다. VR 승계 평가익이 미실현 쪽으로 간다.
  - 첫·마지막 스냅샷은 기존 `findFirstByCycleIds`·`findLatestByCycleIds`로 종료 사이클만 일괄 조회한다.
- 승률·평균 수익률·성과 목록·체인 realized: 변경 없음(비목표).

### 4. API·UI 영향

- 응답 필드 추가·삭제 없음. `StatsSummaryResponse`의 `totalRealizedPnl`·`totalUnrealizedPnl`·타입별 `realizedPnl`·`unrealizedPnl` 값의 **의미**만 바뀐다(VR이 있으면 실현↓·미실현↑, 합 불변).
- kista-ui 요청(설계 승인 뒤 활성 UI 세션에 전달): 요약 "실현손익"이 "매도로 확정한 손익(평균단가 기준)"이고 VR 롤오버 승계 평가익은 미실현에 포함된다는 툴팁·설명 문구. 사이클 성과 쪽 라벨은 그대로.

### 5. 문서

- `flows.yml`: 투자금 추가·인출 흐름에 이력 기록 단계, 통계 흐름에 실현손익 정의.
- `modules/tradingcore/trading.md`(진행 중 투자금 추가·인출 항목), `modules/tradingcore/stats.md`(체인 보정·DCA 포함 절의 원금 정의, 실현손익 정의), 관련 workflow 문서.
- `code-quality-backlog.md`: CQ-35 done, CQ-36 (c) done(사이클 지표는 구간 손익 유지 근거 포함), CQ-33의 체인 surplus 항목 done. 신규 항목: PRIVACY(10-24) 원장·체결 86주 불일치 조사.

## 테스트 (관련 클래스만, 회귀는 변이 확인)

- `CapitalInjectionServiceTest`: 추가(예수금·주식)·인출이 올바른 delta로 이벤트를 저장.
- `StrategyServiceTest`: 이벤트 있는 사이클 시드 수정 거절.
- `TradingStatsServiceTest`:
  1. 06-01 시드 10,000, 06-20 +5,000 → 06-19 이전 원금선 10,000, 이후 15,000.
  2. 정지 전략(최신 행 06-10)에 06-20 추가 → 06-10~06-19 자산선 불변.
  3. 보유 승계 종료 사이클의 첫 스냅샷이 제자리 갱신된 경우 → bookRealized에 이중 차감 없음(일반식 고정).
  4. VR 롤오버 1회, VR 재설정 종료 1회(승계 보유분) → 실현 = 매도분만, 실현 + 미실현 = 기존 총합.
  7. 초기 보유분(평단 ≠ 시작 종가)으로 시작해 전량 청산된 종료 사이클 → 실현 = 종료 − 시작, 미실현 0.
  6. 회전 뒤 후속 사이클에 추가 → surplus가 회전 시점 시작금액 기준.
- `CycleCapitalEventPersistenceAdapterDbTest`: 저장·조회(실 DB, `DataJpaTestBase`).

## 빌더에게 맡김

- 가상 스냅샷 병합 구현 방식(정렬 리스트 삽입 등), 헬퍼 위치, 테스트 픽스처 구성.
- 이벤트 조회를 `loadCycles` 시점에 할지 각 계산 경로에서 할지(조회 1회로 묶을 것).

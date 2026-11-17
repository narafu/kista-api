# 30. 사후 검수 3회차 — S18·W1·W2·S13 (Fable, 2026-11-16)

`questions.md`의 "Fable 대기 작업" F-01~F-05에 대한 답이다. 대상은 `findings/29-implementation-review-2.md`(2026-11-15 16:54까지) 뒤의 커밋 `f105d68db`..`0ffaea0d0`이다. 측정은 Sonnet 서브에이전트 2개(W1, W2·S13)가 했고, S18과 판정은 Fable이 직접 했다. 빌드·테스트는 돌리지 않았다. 운영은 읽기 전용 조회(2026-11-16 14:30 KST, `ssh kista-server`)만 했다.

## 0. 운영 상태 (2026-11-16 14:30 KST)

- 세 role 이미지 `75957f431`, healthy. ui `8743fa99`.
- trading Flyway 최신 V22(2026-11-15 21:56 KST), root 최신 V10. V7·V8은 13:49, V9는 13:57, V10은 14:05 KST에 적용됐다(같은 날 16분 안에 expand와 contract가 모두 나갔다 — 4절).
- 살아 있는 계좌: MERITZ 3, MOCK 1, TOSS 1. KIS 행 없음(V22). BINANCE·UPBIT는 삭제 표시 각 1.
- 살아 있는 전략 6: PRIVACY SOXL(MERITZ), VR TQQQ(MERITZ), VR TQQQ·PRIVACY SOXL·DCA QLD·INFINITE USD(MOCK). **실계좌에 INFINITE 전략이 없다.**
- EPR: `public`·`trading` 모두 0행(완료 행 삭제 설정이 동작한다). 스케줄러 락: `public` 8, `trading` 6(`crypto-dca` 포함).
- 정책 JSON(`trading.trading_runtime_settings`): `brokers`에 `KIS`(enabled false)·`UPBIT`(false)·`BINANCE`(**true**) 키가 남아 있고 DCA 허용 종목이 `UPRO, QLD, BTC, ETH`다. BITO·EETH는 아직 없다.
- `dca_execution.skip_reason`: `BELOW_MIN_ORDER_AMOUNT` 4건은 전부 ETH 전략이다. 주식 행에는 `CONDITION_NOT_MET`·`INSUFFICIENT_BALANCE`·`NO_HOLDINGS`만 있다.
- 2026-11-15 배포 뒤 배치는 아직 돌지 않았다. 첫 배치는 2026-11-16 22:00 KST다. 그래서 로드맵의 "관찰 대기" 항목은 이 문서에서 닫지 못한다(5절에 모았다).

## 1. F-01 — S18 리버스 별지점·종료 조건 (`f105d68db`)

**판정: 유지. 결함 없음, 알아 둘 경계 2건.** 운영 영향은 MOCK 계좌의 INFINITE 1건(`01a1dcd7`, 일반모드)뿐이라 9-4의 전후 비교표는 필요 없다.

| 확인한 것 | 결과 | 근거 |
|---|---|---|
| "직전 5거래일"의 날짜 계산 | 맞다. KST 거래일 T → 미국 세션 T−1 → `lastBefore`가 `to = T−2`(그 세션 직전 봉까지) | `CycleOrderComputer.computeStarPointPrice`, `DailyCandleQuery.lastBefore` |
| 날짜만 적은 `end`가 그날 봉을 포함하는가 | 포함한다. 실호출(2026-11-16, 공용 시세 키, `symbols=USD&end=2026-11-12&sort=desc&limit=5`)이 11-12 봉(종가 58.90)부터 5개를 돌려줬다. 운영 원장의 11-12 종가 58.90(Q-05)과 같다 | 직접 호출. 계약 테스트는 mock이라 이 점을 보장하지 못했다 |
| 대체 경로가 주문을 늘리는가(U3) | 아니다. 원장 5행 평균은 바꾸기 전 동작 그대로다. 주문 수·수량은 별지점 값으로만 달라진다 | `starPointFromLedger` |
| 진행 중 전략(U4 ①) | 배포 시점에 리버스 2일차 이후 전략 0건. 지금도 0건 | 0절 |
| 다음 거래일(U4 ②) | 같은 거래일에 저장된 리버스 LOC 매도가 있으면 그 가격을 재사용해 매수·매도 leg의 별지점이 갈리지 않는다 | `computeStarPointPrice` 첫 블록 |
| 주문 0건 경로(U4 ③) | 새로 생기지 않는다. 별지점이 null이 되는 조건(원장에도 종가가 없음)은 전과 같다 | 같은 곳 |
| 종료 조건 `>` | 종가가 임계와 정확히 같은 날만 하루 늦게 복귀한다. 돈이 덜 움직이는 쪽 | `ReverseModePosition.shouldExit` |

경계 2건(고치지 않는다, 기록만):

1. `to`가 뉴욕 기준 어제 이전이면 `end`를 보낸다. Alpaca 무료 키는 `end`가 "최근 15분"에 걸리면 403인데, 날짜만 적은 `end`는 뉴욕 그날 끝으로 해석되는 것으로 보인다(2026-11-14 00:21 UTC에 `end=2026-11-13`이 403, 2026-11-16에는 200). 그러면 뉴욕 00:00~00:15(KST 14:00~14:15, 서머타임에는 13:00~13:15)에 미리보기를 열면 403 → 원장 평균 대체 + warn이 된다. 배치는 그 시각에 돌지 않는다. 대체 값이 화면에 15분 동안 보일 수 있다는 것뿐이다.
2. 일봉 조회가 주문 계획 경로의 트랜잭션 안에서 불린다(미리보기는 readOnly). 전일 종가 조회와 같은 기존 구조이고 1시간 캐시가 있다. `constraints.md`의 "`@Transactional` 안에서 외부 HTTP 금지"와는 어긋난 채다. 14 T3이 주문 경로의 시세 조회를 정리할 때 함께 트랜잭션 밖으로 뺀다(14의 11절에 적었다).

관찰(로드맵 S18 칸 그대로): 리버스 2일차가 처음 생긴 날 `REVERSE_INFINITE_LOC_SELL` 가격 = 직전 5세션 종가 평균, `kista-trading` 로그에 "별지점 일봉"·"조회에 실패해 건너뜁니다" warn 0건.

## 2. F-02 — W1: KIS 삭제(04 단계 A)와 개장 경매 경로 삭제(단계 B)

대상 `830f826b4`·`dfdecd8c4`·`a43b5af51`·`cd18ee952`·`4ecde8638`·`c882fbf3e`. 측정은 서브에이전트(Sonnet), 운영 값은 0절.

**판정: 유지. 운영 결함 없음. 후속 4건(전부 15 T4·T5에 싣는다)과 절차 지적 2건.**

| 볼 것(F-02) | 결과 |
|---|---|
| V22의 가드·누락 참조 | 살아 있는 KIS 계좌가 있으면 실패하는 `DO` 가드가 있다. `accounts`를 직·간접 참조하는 trading 테이블 16개 가운데 FK가 없는 것은 `trading_batch_report`(명시 DELETE)와 `dca_execution.order_id`(전략 FK로 행이 함께 지워진다)뿐이고 나머지는 전부 `ON DELETE CASCADE`다. 놓친 테이블 없음. `event_publication`의 payload는 다루지 않았지만 배포 전 미완료 0건이었고 지금은 0행이다 |
| V22의 복원 수단 | 마이그레이션 안에는 없다(정상). 덤프를 떴다는 기록과 사용자에게 삭제 SQL·행 수를 보인 기록이 커밋·문서에 없다 → 절차 지적 ① |
| `dfdecd8c4`(정책 JSON 키 버림) | `brokers`·`strategies`의 최상위 키만 버린다. 로그가 없다. 읽을 때만 버리고 DB는 관리자가 저장할 때 바뀐다(운영 JSON에 `KIS` 키가 아직 있다 — 0절). 15의 `UPBIT`·`BINANCE` 키에는 안전하다. **`allowedValues` 배열 안의 `BTC`·`ETH`에는 듣지 않는다** → 후속 ① |
| 오타 키 삼킴 | 삼킨다. 오타 키는 버려지고 원래 키는 "빠진 키"로 보아 `defaults()`(enabled true)로 채워진다. 정책은 관리자 화면이 enum에서 만든 키로만 저장하므로 운영 경로에서는 일어나지 않는다. 손으로 고치는 SQL에서만 가능하다 → 후속 ② |
| `supportsOpenAuctionOrders()` 삭제 | 삭제 직전 구현은 `return false` 하나였고 호출처는 그 필터 한 곳이었다. 도달 불가가 맞다. 메리츠·토스·MOCK의 개장 배치는 같은 경로를 탄다. 테스트는 죽은 경로의 단언(`verifyNoInteractions(dcaDetailPort)`)과 호출 횟수 stub만 바뀌었고 접수 순서 단언은 그대로다 |
| 관리자 시세 체인 접기 | 토스 `enabled()`는 원래 항상 true라 "토스 관리자 키 미설정"의 동작은 같다(예외 → warn → 계좌 시세로 보충). 중간 KIS 관리자 단계가 없어졌지만 운영 secret에 `KIS_*` 키가 없었으므로(infra 회신) 그 단계는 운영에서 늘 건너뛰어졌다. **달라진 것 하나**: 토스가 예외 없이 일부 종목만 돌려줄 때 남기던 "누락 — 다음 단계" warn이 사라졌다. 14 T5가 이 클래스를 지우므로 되살리지 않는다 |
| 배포 묶음 | 세 role이 같은 release로 나갔고 ui KIS 제거(`b33118f1`·`4f94b3dc`)가 같은 묶음이었다(로드맵 W1 칸, `cross-repo.md`) |
| 감량 | `a43b5af51` main −1,735줄(지침서 예상 약 1,764), test −1,936줄. 단계 B main −39줄 |
| 가드 | `HexagonalArchitectureTest`(허용 목록 7→5, KIS 파서 규칙 삭제), `flows.yml`이 삭제와 같은 커밋이다. `PENDING_REMOVAL_CEILING` 82 그대로 |

후속(코드·문서):

1. **15 T4의 정책 JSON 마이그레이션은 선택이 아니다.** 15의 11절에 적었다.
2. `backfillSection`이 키를 버릴 때 `log.warn("[정책] 알 수 없는 키를 버립니다: {}")` 한 줄을 남긴다(15 T4에서 같은 파일을 고칠 때).
3. 삭제된 패키지를 가리키는 문장 3곳: `docs/agents/modules/tradingcore/broker.md:6`, `docs/agents/testing.md:11`, `broker/package-info.java:1`의 `domain.model.kis/toss`. 15 T5에서 `kis`를 뺀다.
4. `archive/broker-kis` 태그가 만들어지지 않았다(로드맵 W1 "남은 것"). 이 검수에서 `a43b5af51^`에 로컬 태그를 걸었다. push는 사용자 요청 시(`archive/docs-2026-11`과 같다).

절차 지적:

- ① 운영 데이터를 지우는 SQL(V22: accounts 1·orders 925 등)의 사용자 승인과 덤프 기록이 없다(9-4). 지운 것은 삭제 표시된 KIS 계좌의 행이고 사용자가 KIS 삭제를 결정했으므로 결과는 받아들인다. 15 T4는 같은 종류의 삭제라 승인·덤프 경로를 로드맵 N18 칸에 적게 했다.
- ② S18·W1 둘 다 Fable 검수 없이 배포됐다(F-01·F-02가 생긴 이유). Opus 리뷰어 검수는 있었다. 규칙 위반은 아니다(9절은 Fable 검수를 요구하지 않는다).

## 3. F-03 — Q-14 (`brokerOrDefault` 기본값 MOCK)

**판정: 유지.** `AccountService.brokerOrDefault`의 호출처는 `register`(`:54`)와 `test`(`:199`) 둘이다. `AccountRequest.broker`는 `@Nullable`이다. MOCK 계좌는 주문 API를 부르지 않는다(`MockBrokerAdapter.place`는 합성 ID만 돌려준다). ui에는 `broker`를 생략하는 경로가 0건이다(ui 회신 2026-11-16, `ConfirmStep.tsx:31,38`). 필수로 바꾸면(②) 코드는 한 메서드 줄지만 요청 계약이 엄격해지는 것 말고 얻는 것이 없다. 기본값이 서비스 한 곳에 있는 것은 `constraints.md` "기본값"과 맞다. 다시 제안하지 않는다.

## 4. W2(05 P3·P4)와 S13(11 T5·T8, 13 U2, 12 F2)

대상 `1dc134c78`·`33b129e1a`, `7150d7fa8`·`fb4ff8eae`·`af9fe34e9`·`6bb83cf23`·`ba740bad7`·`0ffaea0d0`. 측정은 서브에이전트(Sonnet).

**판정: 유지. 운영 결함 없음. 로컬 환경 결함 1건(고침), 후속 4건, 절차 지적 2건.**

| 묶음 | 지침서 의도대로인가 | 근거 |
|---|---|---|
| P3 | 그렇다. main의 native SQL·JdbcTemplate SQL에 스키마 없는 테이블 참조 0건(직접 grep). 락 테이블 프로퍼티는 형식 검사(`^[a-z_]+\.[a-z_]+$`)가 있고 누락이면 기동 실패다. 기존 테스트 기대값 변경 0 | `SchedulerLockService.java:21,30` |
| P4 | 운영은 그렇다. compose가 `web`·`worker`·`worker`를 주고 prod 프로필은 기본값이 없어 누락이면 기동 실패다. **로컬은 아니다** — 아래 결함 | `application.yml:132`, `application-prod.yml:22` |
| S13 스키마(V7~V10) | 그렇다. 직전 이미지 호환을 코드로 확인했다(`af9fe34e9^`는 `owner_user_id` 미참조, `6bb83cf23^`는 `deleted_at` 미참조). contract 주석이 있다 | 마이그레이션 4개 |
| S13 `app_error_logs` | 그렇다. 테이블을 읽는 쿼리는 목록 하나이고 `resolved_at IS NULL` 필터가 있다. 재처리는 404 그대로. 응답 변화 0 | `AppErrorLogJpaRepository.java:13` |
| S13 EPR 완료 행 삭제 | 그렇다. 완료 행을 읽는 main 코드 0건, `pre-apply.sh`는 미완료만 본다. 운영 두 테이블 0행(0절) | `application.yml:42` 둘 |
| S13 삭제(엔드포인트 2, 메서드, `_ping`) | 그렇다. ui 호출 0건(ui 회신으로 재확인), 삭제 직전 main 호출처 0건 | — |

**결함(로컬 환경): P4 뒤 로컬 파일이 스케줄러를 끄지 못한다.** base 기본값이 `${KISTA_ROLE:all}`인데 gitignore된 `application-local.yml`은 커밋이 고칠 수 없다. 이 PC의 두 파일은 옛 `scheduler.enabled: false`만 갖고 있었다. 그대로 로컬을 띄우면 root 스케줄러와 trading-core의 개장·마감 배치가 로컬 DB를 대상으로 돌고 운영과 같은 텔레그램 봇으로 알림이 나간다(`commands.md`가 경고하는 바로 그 상황). `commands.md:19`와 05의 13절은 "로컬 파일에 `kista.role: web`이 있다"고 적고 있었다. 처리: 이 PC의 두 파일을 `kista.role: web`으로 고쳤다. 다른 PC는 사용자 확인. 기본값을 `web`으로 바꾸는 것은 05의 15절(M10)에 넣었다.

후속:

1. `FcmAdapter` 로그가 `log.warn(…, e.getMessage())`에서 예외 객체 전달로 바뀌어 스택트레이스가 붙는다. `constraints.md`("`log.warn`은 `e.getMessage()`만")와 어긋나는지 레벨을 보고 13 U5(N16) 때 맞춘다.
2. 문서 잔재 3곳: `src/main/resources/application.yml:34` 주석(옛 `flyway_schema_history` 보존 — 이미 drop했다), `flows.yml`의 "삭제되지 않은 오류 로그" 문구(`resolved_at` 기준으로), `completion-mode: delete`를 적은 문서가 없다(`modules/shared/platform.md`의 이벤트 절에 한 줄). 15 T5의 문서 커밋에 싣는다.
3. `0ffaea0d0`이 지운 테스트 6개 가운데 "예수금 − 다른 전략 점유분" 비교는 어느 테스트로도 옮겨지지 않았다. 메서드가 죽은 코드였으므로 지킬 동작이 없다 — 받아들인다. 같은 판정을 하는 살아 있는 경로(`BUY 합계` 검증)의 테스트는 그대로다.
4. `NativeSqlSchemaQualifiedTest`의 구멍(쉼표 조인, `USING`, 대문자 테이블명, 파일 전역 CTE 예외)은 로드맵 W2 칸에 이미 일부 적혀 있다. 지금 main에 해당 SQL이 없어 넓히지 않는다. 02 T6(Store 통합)이 SQL을 옮길 때 대문자·`USING`만 더한다.

절차 지적:

- ① **expand와 contract가 16분 안에 나갔다**(V7·V8 13:49, V9 13:57, V10 14:05 KST). `flyway.md`의 규칙(직전 배포 이미지와 호환)은 지켰고 role 교체도 매번 끝난 뒤였다. 잃은 것은 되돌릴 여지다: V10 뒤에는 `7150d7fa8` 이하로 롤백할 수 없는데 그 사이 배치가 한 번도 돌지 않았다. 대상이 오류 로그와 가계부 그룹 소유자 컬럼이라 결과는 받아들인다. 로드맵 9-4에 "contract는 expand 배포 뒤 1거래일" 규칙을 더했다.
- ② 8개 커밋과 로드맵 어디에도 완료 판정 명령의 결과가 적혀 있지 않다(`findings/29` 2-4에서 한 번 권고한 것). 배포 파이프라인이 테스트를 돌리므로 통과 자체는 간접 확인된다. 로드맵 10절에 규칙으로 올렸다.

V7 백필의 틈(V7 적용과 새 이미지 기동 사이에 옛 이미지가 "처리함"을 누른 행은 `resolved_at`이 비어 다시 보인다)은 몇 초짜리 창이고 운영 `app_error_logs`의 마지막 행이 2026-11-13이라 해당 행이 없다.

## 5. 닫지 못한 관찰 (첫 배치 2026-11-16 22:00 KST 뒤)

로드맵 10절에 날짜별 확인표로 옮겼다. 요약: S6·`2fe2fa152`·S11(Q-11)·S15 T2·W1 단계 B는 2026-11-16 개장 배치와 11-17 마감 리포트, S15 T2는 11-18까지.

## 6. Q-08~Q-14 사후 판정 (2026-11-17)

각 항목 "검토" 줄(Fable, 2026-11-14~16)이 원문·코드·운영 조회 근거를 갖고 있어 이 절은 그 판정을 한 표로 모은 것이다. 이 회차에 근거를 다시 열어 보지는 않았다. 뒤집을 항목은 없다.

| Q | 판정 | 근거 요약 | 남은 것 |
|---|---|---|---|
| Q-08 BITO·EETH 메리츠 코드 미실측 | 유지 | 같은 Arca 상장 종목이 전부 `0066`/`AX`(`vendors/meritz.md:42`). 틀리면 조회·주문이 실패하는 쪽(U3) | 사용자: 허용 전 현재가 조회 확인 |
| Q-09 EETH 명칭·enum 위치 | 유지 | 개명(2024-09-27)을 2차 출처로 확인, 선언 위치는 DB 영향 없음(`@Enumerated(STRING)`) | 개명 공시 원문 미확인(표시용 문자열 한 줄) |
| Q-10 `mod_stpr_cls_code=1` | 유지 | 스펙 해외 일봉 `"1": 보정 전 원래 주가`, `MeritzPriceApi.java:106`과 일치 | 없음 |
| Q-11 유일 제약 키 확대 | 유지 | 앱 슬롯 `(timing,direction,leg)`와 일치, 위반 0건, 막히면 주문이 안 나가는 쪽 | 11-16·17 위반 로그 0건 확인 뒤 답변됨 |
| Q-12 `MarketDataPort` 미생성 | 유지 | 01의 2절 원칙, 가드와 무관하게 같은 결론 | 14 T3 첫 작업으로 클래스명 벤더 중립화 |
| Q-13 토스 실호출 생략 | 사용자 결정 필요 | 생략은 U3대로. 대체 경로(Alpaca 현재가)가 "주문 현재가는 계좌 증권사" 결정과 어긋남 | ① 로컬 토스 키로 1회 호출 후 계좌 증권사 현재가(권고) ② 대체 경로 유지, 토스 전략 등록 전 재확인. T3 전에 답 필요 |
| Q-14 `brokerOrDefault`=MOCK | 유지 | 3절 참조 | 없음 |

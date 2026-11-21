# 실행 로드맵 — 지금 시작할 수 있는 것과 기다려야 하는 것

작성 2026-11-12 (Fable). 지침서별 실행 가능 여부와 세션 묶음이다. 실행 세션은 이 문서에서 묶음 하나를 골라 해당 지침서를 읽고 시작한다. 끝나면 이 표의 상태를 고친다.

**2026-11-14 개정:** Fable이 없는 상태에서 실행한다. 지침서의 전제가 코드와 다르거나 "중단 조건"에 걸렸을 때의 처리, 가드 충돌, 배포 승인, 스스로 정하는 범위는 이 문서의 **9절**이 정한다. 각 지침서 맨 끝의 "실행 전 보정" 절에 그 지침서의 정정과 기본값이 있다. 묶음을 시작하기 전에 둘을 먼저 읽는다.

## 1. 지금 시작할 수 있다 (결정 완료, 다른 작업을 기다리지 않음)

| 묶음 | 내용 | 지침서 | 모델 | 배포 주의 |
|---|---|---|---|---|
| **S1** ✅ 완료(2026-11-13, 2026-11-15 배포 `e044e983c`) | 브로커 스펙 결함 수정: B1 메리츠 취소 누락 → B3 바이낸스 403 → B2 → B6 → B5 → B7 → B4. 항목마다 스펙 원문 확인 → 재현 테스트 → 수정 → 변이 확인. **결과**: B1 `0c1edf76d`, B2 `3a08223a1`, B6 `89e047265`, B5 `b9a4763f1`, B4 `5016551a0`, FIDA `orders` `@NotNull`(cross-repo D-2) `820203d13`. 전체 스위트 통과(IT 제외). **고치지 않음**: B3 — 공식 문서상 403은 WAF 차단(매칭 엔진 전, 실행 상태 불명은 5XX·-1006·-1007뿐)이라 "접수 불명을 거절로 단정"이라는 전제가 맞지 않음(`questions.md` Q-01). B7 — 키 교체는 저장 뒤 캐시 제거로 이미 처리되고, 연결 테스트·신규 등록은 계좌 id가 없어 계좌 UUID 키 캐시를 갱신할 수 없음. 단계 D 토큰 단순화(앱키 키 캐시)와 함께 하는 편이 맞음(Q-02). B8·B9는 S1 범위 밖으로 남김 | 04 단계 C | Opus | 주문 안전 경로. 항목별 커밋, 매매 시간대 밖 배포 |
| **S2** ✅ 관찰 완료(구현 2026-11-13. 거래일 2026-11-14 주문이 Q-05의 "후" 열과 같다 — `findings/28` 0-2. 로드맵은 배포를 2026-11-15 `e044e983c`로 적었으나 실제는 그보다 앞이다, 시각 미확인. Q-05의 두 전략은 2026-11-15 삭제됨) | 매매 공식: T1 별% 반올림 제거(F1) + T2 LOC 0.01 위치(F3) + T3 첫 매수(F4) + T4 최종회차 MOC 분기(F6) + T6 공식 문서 재작성. **결과**: T1 `66213af3c`, T2 `480c456ff`, T3 `831ed3ade`·`1050a42e3`(첫 매수 가격 원장 상한 — cap 경로 포함, 0원 보정 생략), T4 `0f137cff2`(도달 경로 4개 확인 → 리버스 첫날 규칙으로 교체, 최소 1주), T6 `18e3ddd4c`(T5 포함). T7 전후 비교는 진행 중 INFINITE 2건(USD 20분할, 일반모드) — 별지점이 +0.29·−0.20 이동, 수량 변화 없음. 질문 Q-04(최종회차 MOC 뒤 첫날 MOC 재발) | 08 4절 | Opus | T7(진행 중 전략의 변경 전후 주문 미리보기를 사용자에게 보여주고 확인)을 거쳐 T1~T3을 한 번에 배포 |
| **S3** ✅ 완료(2026-11-13, `1915ba682`, 2026-11-15 배포 `e044e983c`) | UI 계약 선행 2건: T1 응답 required 정확화, T2 BigDecimal 전역 plain number. 끝나면 fable-ui 쪽 타입 재생성(ui 01)이 풀린다. **결과**: springdoc·swagger-core가 `@NullMarked`를 읽지 않아 `:shared` platform `NullMarkedRecordModelConverter` 1개 + 스펙 가드(root·trading-core). required 표기 root 68→253, trading-core 73→248(전체 프로퍼티 437·390). JSON 필드명 불변. 지침서와 다른 점: dto 패키지에 Request가 섞여 있어 같은 규칙 적용(NotNull 없는 참조 타입 `@Nullable`, NotNull 없는 primitive 11개는 required로 엄격해짐), 스키마명 충돌 `OrderLine`→`AdminPrivacyBaseOrderLine`, 수기 null 보정 customizer 2개 삭제. 남은 구멍: `@NullMarked` 밖 타입이 중첩된 1곳(`RuntimeSettingsResponse.FieldResponses` → `shared.kernel.StrategyFieldSettings`) | 10 5절 | Opus(T1 null 판정), Sonnet(적용) | 추가만이라 api 먼저 배포 |
| **S5** ✅ 완료(2026-11-13, 2026-11-15 배포 `e044e983c`) | 통합 사전 정리 중 독립 항목: P1 예외 처리기 통합, P2 중복 빈 정리, P5 사용자 이벤트 경로 단순화, P7 스케줄러 알림 필터. **결과**: P1 `228c0749a`(현재 응답 표 테스트 `ExceptionResponseTableTest` root·trading-core) → `0d23b822b`(advice를 platform `ProblemDetailExceptionHandler` 하나로, 매핑은 모듈 `*ProblemDetails`가 기여, 중복 기여는 기동 실패, 오류 보고는 프로세스별 `ExceptionReporter` — `app_error_logs` context의 caller 값이 `ApiExceptionReporter`/`TradingExceptionReporter`로 바뀜). P2 `f5e707675`(trading-core 블랙리스트 빈을 platform 조건부 빈으로, trading-core `fearGreedRestClient`→`tradingFearGreedRestClient`). P7 `1260139b8`(root `SchedulerNotifier`가 trading-core 잡 8개를 건너뜀 + 소스 대조 가드). 전체 스위트 통과(IT 제외). **범위 밖으로 넘김**: P5 → 단계 M9(Q-06, 스트림 직접 호출이 EPR 재시도보다 보장이 약함), P2 ClockConfig → M9·Alpaca 3벌 → 단계 X·R(Q-07) | 05 3절 | Opus(P1·P5), Sonnet(P2) | 기존 3 role 그대로 배포, 2거래일 관찰 |
| **S6** ✅ 완료(2026-11-15, 2026-11-15 배포 `252e63bfe` — 운영 `kista-trading` 10:56 KST 기동·healthy 확인, 동작 변경 없음. **관찰 대기**: 2026-11-16 개장 배치와 11-17 마감 리포트의 정상 완료. 가드 대상 확대는 01의 9절 답대로 단계 C의 첫 커밋). 단계 A `e72282b98`, 단계 B `012b0c362`..`775ec82be`(CI 오탐 수정 `fd89d449f` 별도) — S6 **관찰 2026-11-17 통과**: `trading-close` 락 1회(20:30:00Z 획득·21:10:13Z 종료), `event_publication` trading·public 0행 | **단계 B 결과**: UseCase·Query 52개 제거(모듈별 커밋 9개), 모듈 밖 소비 3개 서비스에 `@NamedInterface("api")`, 리뷰 후속 `5bfff390b`(공회전 notify 가드·web 가드 `mainClasses`·중복 record 통합)와 필드 이름 정리 `775ec82be`. `PENDING_REMOVAL_CEILING` 82(포트만). 단계 C로 넘긴 것: `interfaces_must_earn_their_keep` 대상 확대(01의 9절 "단계 B 실행 결과"). 원래 내용: 헥사고날: ArchUnit 규칙 개정(단계 A) → UseCase 인터페이스 제거(단계 B, 모듈 단위) | 01 | Opus(A), Sonnet(B) | 동작 변경 없음. 단계 B는 01의 9절 "단계 A 실행 결과"부터 읽는다: 인터페이스를 지우는 커밋마다 `single-impl-interfaces.txt`의 그 줄을 지우고 `PENDING_REMOVAL_CEILING`(134)을 내린다 |
| **2fe2fa152** ✅ 판정 유지(2026-11-15 `findings/28` 0절, 2026-11-15 배포 `252e63bfe` — 묶음 밖 커밋, 9-4 비교표 없이 배포돼 사후 판정) — **관찰 2026-11-17 통과(매도만 직접 비교)**: 입력 57주·300.00 그대로. LOC 매도 14주 @56.92·지정가 43주 @82.45는 "후" 열과 같다. 매수는 cap이 접수 직전 현재가(≈49.5)×1.05=52.00이라 3주 @56.91이 아니라 4주 @52.00→재산정 4주 @51.99(`BuyOrderPriceCapper` 로그). 210.82÷52.00=4.05→4주이고 보정 52.70은 cap 초과로 빠지고 42.16·35.13이 남아 공식과 맞다. 체결 4주 @49.68, 예수금 300.00→101.28 | 무한매수법 전반 절반 배분을 별지점 쪽 먼저로(08 F5 유지 결정을 대체). 원문 예시(78.11×3, 69.75×4)와 같고 갈리는 날 1주 덜 산다. 진행 중 INFINITE 1건(`01a1dcd7`)은 후반이라 전후 주문이 같다. **관찰**: 2026-11-16 개장 배치에서 `01a1dcd7`이 LOC 매수 3주 @56.91(cap이 걸리면 현재가 × 1.05)·LOC 매도 14주 @56.92·지정가 43주 @82.45인지(findings/28 0-3의 "후" 열). 전반 전략이 생기면 그 첫 배치에서 `INFINITE_EARLY_REF_BUY` 수량 = floor(1회분/2 ÷ (별지점−0.01))인지 | 08 F5 | — | 되돌릴 일이 생기면 매매 시간대 밖 |
| **S18** 🟡 구현 완료(2026-11-15, 2026-11-15 17:25 KST 배포 `f105d68db` — Fable 사후 검수 유지(2026-11-16, `findings/30` 1절) — 관찰 대기: 리버스 2일차 첫 발생일. **관찰 2026-11-20 통과**: 리버스 2일차가 거래일 11-19에 처음 생겼다(`01a1dcd7`). `REVERSE_INFINITE_LOC_SELL` 5주 @54.66, 11-20 계획은 52.55다. Alpaca SIP 일봉 종가(RAW, 2026-11-20 읽기 전용 조회)와 대조했다. 미국 11-11 59.99, 11-12 58.90, 11-13 54.89, 11-16 49.68, 11-17 49.82의 평균은 54.656이고 반올림하면 54.66이다. 창을 하루 옮겨 11-18 49.46을 넣으면 평균 52.55다. 둘 다 주문가와 같다. 11-13·16·17 값은 원장 `closing_price`와도 같다 "별지점 일봉"·"시세 조회에 실패" warn 0건(별지점 로그는 INFO뿐). 사용자 결정 2026-11-15 "권고대로") | 원문 재대조 후속(08 8절 T8~T10). **결과**: T8 리버스 별지점 = 직전 5거래일 종가 평균(공용 시세 일봉 RAW, 실패·5개 미만이면 원장 5행 평균 + warn, 같은 거래일에 저장된 리버스 LOC 매도가 있으면 그 가격 재사용, 미확정 당일 봉 제외), T9 리버스 종료 `>`, T10 `trading-formulas.md`·`broker.md`. 리뷰 1회(Opus) 지적 5건 반영. 14 T3 전에 Alpaca 일봉이 주문 가격에 쓰이는 첫 경로다(14의 11절). **배포 전 확인**: 리버스 2일차 이후인 INFINITE 전략 수(2026-11-15 0건 — `01a1dcd7`은 일반모드)와 그 거래일의 리버스 PLANNED 주문 0건. **관찰**: 리버스 2일차가 처음 생긴 날 `REVERSE_INFINITE_LOC_SELL` 가격이 직전 5세션 종가 평균과 같은지, `kista-trading` 로그에 "별지점 일봉"·"시세 조회에 실패" warn이 없는지 | 08 8절, `findings/28` | Opus(T8), Sonnet(T9·T10) | KST 07:00~16:00 배포 |
| **S7** | UI 계약 추가분: T5 메타 통합(REJECTED 라벨 "반려" 포함), T6 상태 플래그, T10 검증 전용 엔드포인트, T11 단건 조회, T12 차트 지표·`reapplyAvailableAt` | 10 5절 | Opus(T6·T10), Sonnet(T5·T11·T12) | 추가만이라 api 먼저. ui 05의 대응 태스크가 뒤따른다 |

같은 파일을 건드려 **동시에 돌리면 안 되는 조합**:
- S3(응답 DTO 전체)와 S6(컨트롤러·서비스 참조 치환)·S7 → S3을 먼저 끝낸다.
- S5의 P1(예외 처리기)과 지침서 10 T7(오류 문구) → P1이 먼저다. 그래서 T7은 S7에 넣지 않았다.
- S2(matching 커널)는 다른 묶음과 겹치지 않는다. 병렬 가능.

권장 시작 순서: **S1 → S2 → S3**을 먼저(각각 다른 세션, 병렬 가능). 이유: S1·S2는 실제 돈이 걸린 결함이고, S3은 kista-ui 작업 전체의 차단 조건이다.

## 2. 사용자 작업을 기다린다

| 묶음 | 내용 | 기다리는 것 |
|---|---|---|
| **W1** ✅ Fable 사후 검수 유지(2026-11-16, `findings/30` 2절). 04 단계 A 완료(2026-11-15, `830f826b4`·`dfdecd8c4`·`a43b5af51`·`cd18ee952`(문서)·`4ecde8638`(리뷰 지적)). **배포됨(2026-11-15 21:51 KST `966f5e284`, trading V22 적용 12:56 UTC)** — V22(`V22__remove_kis_accounts.sql`)가 운영 KIS 행(accounts 1, orders 925 등)을 지웠다. 세 role을 같은 release로 배포하고 kista-ui KIS 제거와 같은 배포 묶음으로 낸다(ui `ConfirmStep.tsx:24`의 `'KIS'` 폴백이 400이 된다, `cross-repo.md`). 04 단계 B ✅(2026-11-15, 미배포) — 개장 경매 사전 접수 경로(`supportsOpenAuctionOrders`·`placePreOpenDcaOrders`) 삭제, 동작 불변. 단계 A와 같은 release로 매매 시간대 밖에 배포한다(V22 배포 전제 그대로) — W1 단계 B **관찰 2026-11-17 통과**: `trading-open` 락 1회(13:00:00Z 획득·14:30:07Z 종료), `orders` 거래일 2026-11-17 67행·전략 6개(직전 11-14는 68행·6개), `app_error_logs` 2026-11-16 이후 0행 | KIS·업비트 삭제(04 단계 A)와 개장 경매 경로 정리(단계 B). **04 단계 A는 05 단계 M 브랜치를 만들기 전에 끝낸다(2026-11-15, 05 14-1절)** | ~~KIS 활성 전략 1건을 종료하거나 메리츠 계좌에 새로 등록~~(2026-11-15 종료 확인 — KIS 계좌의 live 전략 0). ~~KIS·업비트 계좌를 삭제~~(2026-11-15 KIS·업비트·바이낸스 계좌 soft delete 확인 — live 계좌 0, live 전략 0). **남은 것**: 04 단계 A 착수 때 태그(`archive/broker-kis` — 이 문서의 W1 보정 항목). 바이낸스 계좌도 삭제돼 15 단계 P의 ①이 끝났다(같은 날 운영 조회: KIS·업비트·바이낸스 계좌의 미체결 주문 0, 미완료 `event_publication` 0). ②(관리자 설정 `BINANCE` 비활성, DCA 허용 종목에서 BTC·ETH 제거)는 사용자 몫. 삭제 범위 전수표 작성(단계 A의 1·2번)은 지금 해도 된다 |
| **W2** ✅ P3·P4 완료(2026-11-15, 2026-11-15 17:45 KST 배포 `33b129e1a` — 동작 불변, 주문·원장 저장 쿼리 파일 포함이라 매매 시간대 밖 배포. P4는 compose `KISTA_ROLE`과 코드가 같은 release로 나가 "env 먼저"가 자동 충족 — 05 13절 P4 결과) | 05 단계 P의 P3(SQL 스키마 한정)·P4(role 프로퍼티). **P3 결과**: main native SQL·JdbcTemplate SQL 전부 스키마 한정(root 16·trading-core 39 + JdbcTemplate 3파일), `SchedulerLockService` 테이블을 `kista.scheduler-lock.table`(root `public.scheduler_locks`, trading-core `trading.scheduler_locks`)로, 가드 `NativeSqlSchemaQualifiedTest`(쉼표 조인·`DELETE ... USING`은 못 잡는다 — 지금 main에 없음) | **기다리는 것 없음 — 지금 시작한다(2026-11-15 재검토).** KIS·코인 전용 파일에는 native SQL이 0개라 W1을 기다려도 줄어드는 작업이 없다(`findings/29` 3-2). P3 → P4 순서, P4는 env 먼저 배포. 04 단계 A와 다른 세션에서 병행해도 된다(겹치는 파일 없음). 05의 13·14절부터 읽는다 |

## 3. 앞 묶음이 끝나야 한다

| 묶음 | 내용 | 선행 |
|---|---|---|
| N1 | 05 단계 M(한 애플리케이션으로 통합, 2 role 전환) | **2026-11-20 M 완료(main, push 안 함 — 전환일까지 보류)**: `26b4dda9d`(Flyway 마이그레이터·role 기본값 web) → `5f46cb2bb`(합병·스트림 경로 삭제) → `788aefd24`(RoleRoutingGuard 503 `WRONG_ROLE`) → `a163fdd53`(배포 2 role) → `c2ce024a9`(문서). 태스크마다 리뷰·수정 뒤 커밋(최종 전체 리뷰는 생략 — 태스크별 리뷰 완료). **검증**: `./gradlew test` BUILD SUCCESSFUL(root 950·trading-core 2378·shared 147, 실패 0), bats는 doc-budget 포함 통과(로컬 bats 경로 문제로 doc-budget은 `check-doc-budget.sh` 직접 실행), `*.sql` 변경 0(`git diff 537038a9c --stat -- '*.sql'` 빈 출력). 빈 DB `role=all` 기동: Flyway public v10·trading v24, 이력 테이블 2개, `Started ApiApplication` 5.7s, ERROR 0. 2 프로세스 리허설(빈 DB, MOCK 시드 생략 — `seed.sh`가 `kistadb`를 하드코딩, 텔레그램은 더미 토큰으로 덮어씀): worker만 `market-calendar-startup` 락 획득·`기동 시 캘린더 초기 적재 시작`, web은 스케줄 등록 0, web `/api/trading-cycles` 503 `WRONG_ROLE`·`/api/meta` 200·worker `/api/finance/accounts` 503, 탈퇴 → web 프로세스에서 `UserDeletedEvent`→`TradingUserCascadeListener` EPR 등록·완료(`public.event_publication` 0행, `trading.event_publication` 0행), `stream:user.deleted`·`stream:app.error` XLEN 변화 0. **14-3 D-1 d·e(2026-11-20, 사용자 "런북대로 진행")**: M이 origin/main `caa277abe`로 push됨. api `Server Deploy` #44674120775는 Maven Central 429(gradle 파일 변경으로 CI 캐시 콜드)로 테스트 단계 2회 실패 뒤 3차 재실행 success — Build & Push Image는 옛 state 키라 plan이 skipped(의도). infra reconcile #44676755077 failure `deploy/server/roles와 state의 role 목록이 다름`(bundle 구성 단계, 서버 스텝 미실행). 서버 3 role `ee752d02b` 그대로, `/opt/kista-api/current` = `releases/152f9958e339-b461ab57`(전환 전 롤백 기준 release id). **14-3 f·D-0 전환 완료(2026-11-20 금 KST, 사용자 결정: 일시 "지금"·전체 정지)**: f `Server Deploy` #44677931737(`build_only=true`) success, `ghcr.io/narafu/kista-api:caa277abe…` manifest 확인. 0단계 13:59 go(3 role healthy, EPR 0·0, 락 held f·f, SUBMITTING 0, 스트림 pending 0, 옛 release `152f9958e339-b461ab57`, XLEN 0·2·10·363, `app_error_logs` 최신 2026-11-13 04:13:47Z). 1단계 14:06:35~14:06:40 옛 3 role 정지. 2단계 EPR 0·0(이관 없음). 3단계 infra main `39f71e2` push(infra Server Deploy #44678990125 success, 옛 role 재기동 없음). 4단계 infra `Reconcile App` #44679440532 success(14:12~14:14), state `kista-worker`·`kista-web` = `caa277abe`, release `caa277abe522-e013fbfd`. 5단계 worker `Started ApiApplication` 18.2s·ERROR 0·`market-calendar-startup` 락 05:13:13Z worker가 획득(운영은 캘린더가 이미 적재돼 `기동 시 캘린더 초기 적재` 줄 없이 debug 스킵 — 정상). 6단계 Caddy `kista-worker` 2줄·`kista-web` 1줄, `/api/meta` 200·`/api/trading-cycles` 401, web ERROR 0·스케줄 줄 0. 7단계 `app_error_logs` 신규 0, EPR 0·0, XLEN 0단계와 같음, 사용자 브라우저 확인 통과. 8단계 14:15 옛 3 컨테이너 rm. 전체 정지 약 8분. **다음**: 관찰 2거래일(11-23 월 22:00 개장 배치부터 — 14-3 "전환 뒤"), ui I-6 로컬 `2ae62e26`·`d9835f3e` push(ui 세션), api 로컬 문서 커밋 push(사용자 승인). **배포 전 확인표(14-3 D-7~D-1 a·b, 9-4)**: a ✅(위 검증·리허설). b: 사용자 보고 대기 — 런북 14-3, 기대 로그는 14-3 5단계 표 아래 "리허설 값", 마이그레이션 0개. **전환 전 남은 것**: kista-infra I-1 브랜치(state 키 `kista-web`·`kista-worker` — 옛 키 그대로면 role SHA가 바뀌지 않는다, deploy-plan.bats 고정), ui 로컬·e2e·`fetch-spec.mjs` 단일 스펙 후속(I-6), 14-3 D-1 d는 "main을 push"로 읽는다. 일정: N19와 다른 주, 11-26 휴장·11-27 조기 마감. **M 밖 남은 일**: `check-migrations.sh`의 trading 디렉토리 적용(D3), `trading-web` 모듈이 빈 껍데기(package-info만), `RedisStreams.DEFAULT_MAXLEN`·3인자 `add` 운영 미사용, `seed.sh`의 `kistadb` 하드코딩·"Redis Stream 경유" 주석. **2026-11-16: 15 단계 A(N18)를 먼저 하고 1거래일 관찰 뒤 M 브랜치를 만든다(05의 15절 — 14-1의 판정 규칙을 고쳤다. 런북 보정도 15절).** 2026-11-15 재검토(`findings/29`, 05의 14절). 선행: S5 ✅ + W2(P3·P4) + **04 단계 A(KIS 삭제)가 main에 들어간 뒤** M 브랜치를 만든다. 15 단계 A(코인 삭제)는 그 시점에 선행 넷이 끝나 있으면 M 앞, 아니면 M 관찰 뒤(판정 규칙은 05 14-1절). S11·S15 T2 관찰(2026-11-16~18)이 끝난 뒤, N9·N19와 다른 주에 전환한다. M 브랜치가 열린 동안 main에는 문서와 긴급 수정만. 전환은 05 **14-3절 런북**(4-3절 대체): 토요일 낮 전체 정지 15~25분, 일시와 무중단 여부는 사용자 결정, kista-infra 쪽 준비는 `cross-repo.md` I절. 실행 세션은 M 브랜치와 리허설, 배포 전 확인표까지 하고 전환은 사용자 승인 뒤 |
| N2 | 05 단계 X(경계 코드 제거) → R(모듈 재구성) → G | N1의 2거래일 관찰 통과. R의 선행 S6 ✅. 판단 지점의 기본값은 05 14-4절, 바뀐 전제는 14-5절(X8에서 `TradeEventEnvelope`·`TradeEventMessage`는 지우지 않는다, 내부 핸들러 30개, `PENDING_REMOVAL_CEILING` 여유 0). 15를 M 뒤로 미뤘으면 X6 앞에 15를 끝낸다(호출처 2개가 먼저 사라진다) |
| N3 | 04 단계 D(벤더 이름 제거, 토큰 단순화, 공통 골격)·E(facade, 코인 포트) | W1. 토큰 단순화는 N1 방향이 유지되는 한 지금 구조에서도 전제가 성립한다 |
| N5 | 10 T3·T4(목록 규약 전환) | S3. 묶음별로 kista-ui와 같은 날 배포. 내부 API 묶음(④)은 N2에서 사라지므로 하지 않는다 |
| N6 | 10 T7(오류 문구), T8(가계부 집계), T9(원칙 문서 상시화) | T7은 S5의 P1 뒤. T8은 지금도 가능하지만 분량이 커서 별도 세션 |
| N7 | 02(영속·DTO 축소) | S3 → 02 T1(스펙 스냅샷) → 시범 모듈. trading 저장소 통합(T6)은 N2 뒤 |

## 4. 2026-11-13 추가된 지침서(03·06·07·09)의 묶음

지금 시작할 수 있다:

| 묶음 | 내용 | 지침서 | 모델 | 주의 |
|---|---|---|---|---|
| **S8** ✅ 완료(2026-11-14, 2026-11-15 배포 `e044e983c`(3 role 교체. 먼저 한 push `125c07d6b`는 migration 디렉토리 CLAUDE.md 삭제를 마이그레이션 변경으로 본 검사 오탐으로 실패했다). 동작 변화는 gson 2.14.0→2.13.2(BOM)와 `/`(index.html 삭제 → 401)뿐이고 나머지는 주석이지만 주문 계획 파일 주석이 포함돼 매매 시간대 밖 push. 문서 예산 여유: 항상 로드 약 300B·dca 22B·web 14B·admin 6B — 문서를 늘리는 묶음(S6 등)은 같은 커밋에서 줄인다) | **결과**: T1 `c2484085a`(decisions.md), T2·T3·T6 `ac278e583`(archive 179파일 등 삭제, 태그 `archive/docs-2026-11` 로컬만 — push는 사용자 요청 시, 항상 로드 5파일 45.4→25.5KB, 리소스 CLAUDE.md → `.claude/rules/flyway.md`), T3 코드 `74d59302e`(gson 핀 제거 → BOM 2.13.2, mockwebserver 카탈로그, index.html 삭제, 메리츠·토스 주석 — Q-10), T4 `3cdf3866b`·`7fae356ae`·`2f0059277`(trading 35→6.3KB, stats 20→4.3KB, platform 18→7.4KB, platform의 거래일 경계 드리프트 수정), T5 `7b8b9e9d2`(모듈 10개 예산 충족, 벤더 문서 `docs/agents/vendors/`), T7 `9ae228828`(예산 가드 bats). T8: memory 재론 금지 5파일 → `reference_decisions_and_fable_review.md` 포인터 1개, 로컬 잔재(`bin/`·`logs/*.tmp`·`trading-core/logs/`·빈 worktrees 디렉토리·레포 밖 `ARCHITECTURE.md`) 삭제, `.superpowers/`는 미추적 SDD 기록이 있어 남김(사용자 판단). 전체 테스트·bats 32건 통과, jar에 CLAUDE.md 0. T9는 N11(단계 2 마감). 원래 내용: 문서 단계 1: `decisions.md` 작성 → archive·backlog·history·schema-reorg 삭제 → 작은 수정 → 모듈 문서·항상 로드 문서 축소 → 크기 예산 가드 | 09 4절, T1~T7 | Opus(T1·T4·T6), Sonnet(나머지) | **사용자 승인(09 8절 A~G) 뒤.** 가장 먼저 하는 편이 좋다(뒤따르는 세션 전부의 자동 로드 비용이 준다). 문서를 건드리는 다른 묶음과 동시에 돌리지 않는다 |
| **S9** | 테스트 fixture 치환과 생성자 가드 | 07 T1 | Sonnet | 테스트 파일 전반을 건드린다. S3·S6과 동시에 돌리지 않는다 |
| **S10** 🟡 T1 완료(2026-11-15, `be6956778`, 2026-11-15 배포 `e044e983c` — 아무도 쓰지 않는 새 클래스라 동작 변화 없음). **T2는 남았다** | **T1 결과**: `trading.domain.model.TradingBatchStep.decide`와 `TradingBatchStepTest` 68행. 본문과 다르게 만든 것·T3·T4가 표를 붙일 때 확인할 차이는 06의 12절. 원래 내용: 배치 판정표와 표 기반 테스트(기존 코드 불변) → 마감 리포트 단계 분리 | 06 T1·T2 | Opus | T2는 주문 경로. 매매 시간대 밖 배포, 2거래일 관찰 |

앞 묶음이 끝나야 한다:

| 묶음 | 내용 | 선행 |
|---|---|---|
| N8 | 03 전략 축 전체(T1~T10) | S2(같은 커널 파일). T6은 02 T5와 한 세션, T9는 05 단계 R과 순서 조정 |
| N9 | 06 T3(개장 단계)·T4(스케줄러 통합)·T5(시각 모델)·T6(코인)·T7 | T3은 04 단계 B(W1 뒤). T6은 03과 업비트 삭제 뒤. N1(통합 전환)과 같은 주에 하지 않는다 |
| N10 | 07 T2(03 뒤)·T3(S5의 P1 뒤)·T4·T6(S6 뒤)·T5(N7 뒤)·T7(N9 뒤) | 괄호 안 |
| N11 | 09 단계 2(리팩토링별 문서 반영)와 마감 점검 T9 | 각 지침서의 마지막 커밋에서. T9는 전부 끝난 뒤 |

## 5. 다른 세션이 이미 실행한 것 (2026-11-12 확인)

- kista-api: `required-env` 경로 제거(`bd1e56f80`), readiness 계약 파일 폐지와 Docker healthcheck 전환(`158722438`, infra 01 T3·T4), 배포 구성도 링크와 postgres 런북 경로 정정. 운영 배포됨(`deploy(kista-api): 158722438a6d`).
- kista-ui: 테스트 무대·빌더(ui 07), `FormDialog`와 인터셉트 라우트 폐지(ui 04), `required-env` 폐지, archive 71개 삭제.
- kista-infra: sops + age 전환, 고아 컨테이너 드리프트 알림(03 T8), 배포 구성도 생성기 삭제, readiness·required-env 옛 계약 삭제, 서버 스크립트 공용부 추출.
- fida: 검토 완료(지침서 4개, 커밋됨). **별도 레포 유지로 사용자 결정.** kista 쪽 할 일 4건은 `cross-repo.md` D절(05 X5와 묶음). 그중 `FidaOrderRequest.orders` `@NotNull`은 독립 소규모 수정이라 S1 세션에 끼워도 된다.

## 6. 답을 기다리는 질문 (실행을 막지 않음)

- (해소) VR 기본값 차이 8건: KISTA 값이 맞다(2026-11-12).
- (해소) Alpaca 약관: 기본 마켓 데이터는 토스 유지로 결정(04 4-3절).
- 06 D1(개장 배치 대상 확정 시각을 22:00 → 개장 시각), D2(하트비트 하루 1회, healthchecks.io 일정 변경), D3(조기 마감일·평일 휴장일의 수동 실행 차단 구간 — 2026-11 바로 주문 삭제로 해소). 권고는 전부 진행. 06 3절.
- 09 승인 요청 A~G(archive·backlog·history·schema-reorg 삭제, 모듈 문서 축소, memory 정리, 로컬 잔재). **S8의 시작 조건이다.** 09 8절.

## 7. 2026-11-13 추가 검토(지침서 11·12·13)의 묶음

지침서: `11-db-tables.md`(DB 테이블), `12-features.md`(기능 유지·보완·추가), `13-unassigned-areas.md`(지정하지 않은 영역). 측정 원본은 `findings/15`~`18`.

**먼저 사용자 결정**: 12의 D0(다른 사용자를 받을 것인가)·D1(주택 벤치마크 삭제)·D2(코인 시장 지표)가 다른 지침서의 범위를 바꾼다. 기능을 지우기로 하면 그 기능의 리팩토링은 하지 않는다. 답이 없으면 "전부 유지"로 진행하되 admin·benchmark·코인 지표에 닿는 작업(13 U7, 02·01의 해당 모듈)은 뒤로 미룬다.

지금 시작할 수 있다:

| 묶음 | 내용 | 지침서 | 모델 | 주의 |
|---|---|---|---|---|
| **S11** ✅ 완료(2026-11-15, `0cd178893`, 2026-11-15 배포 `e044e983c` — **관찰 대기(2026-11-16·17 거래일)**) — **관찰 2026-11-17 통과(1거래일)**: `kista-trading` 로그(2026-11-16T13:00Z~) `uq_orders_live_slot`·`uq_strategy_cycle_open`·`ConstraintViolation` 0건. Q-11은 11-18 마감 리포트까지 0건이어야 답변됨이라 아직 열어 둔다 | **결과**: trading `V21`(`uq_orders_live_slot`·`uq_strategy_cycle_open`), flush 순서 수정 2곳(`cancelPlanned`·`markEnded`), `LedgerUniqueConstraintIT`. 키에 timing·direction을 넣었다(Q-11). 운영 위반 0건(2026-11-15). 배포는 매매 시간대 밖, 직전에 11의 9절 SQL 재확인, 관찰 항목은 11의 10절. 원래 내용: `orders` 슬롯 유일 제약과 열린 사이클 유일 제약 | 11 T1 | Opus | 주문 경로. 저장 경로 전수 확인이 먼저. 매매 시간대 밖 배포. S1과 한 세션에 묶어도 된다 |
| **S12** ✅ PENDING 차단 제외(2026-11-15, `09011d9bf`, 2026-11-15 배포 `e044e983c` — 운영 로그인·갱신·로그아웃 확인은 실행 기록 없음, 사용자 확인 대기. 13의 5절 fable-ui 요청 ①~④의 상태가 어디에도 없다 — PENDING 차단 착수 때 함께 적는다, `findings/29` 2-1) | 보안: 로그아웃 jti 차단, 내부 토큰 상수 시간 비교, 공개 경로 명시, PENDING 차단(D0=(가)일 때) | 13 U1 | Opus | PENDING 차단은 ui가 승인 대기 경로 목록을 준 뒤(`cross-repo.md` E절) | **남은 것**: PENDING 차단(ui 경로 목록은 도착, 13의 14절 끝 "U1 실행 결과"), 로그아웃 경로의 무효 토큰 익명 통과(`cross-repo.md` H절, 사용자 승인 대기). 배포 뒤 로그인·갱신·로그아웃을 운영에서 한 번씩 확인(사용자) |
| **S13** ✅ 완료(2026-11-16, contract까지 배포 — V7~V10 운영 적용 확인. Q-22 답함·삭제) — S13 **관찰 2026-11-17 통과**: 개장·마감 각 1회, `app_error_logs` 0행, EPR 두 테이블 0행, 마감 리포트 계정별 `리포트 발송 완료`(2개 계정 확인) | **결과**: 11 T5 root `V7`(`resolved_at` 추가·백필, `@SQLRestriction` 제거, 처리 시 `resolved_at`·`deleted_at` 함께 기록), 11 T8 root `V8`(`owner_user_id` NULL 허용, 코드 쓰기 중단)·`completion-mode: delete` 양쪽. **다음 배포(contract)**: `app_error_logs.deleted_at`·`finance_groups.owner_user_id` drop. **운영 SQL**: Q-16 실행 완료(2026-11-16). contract 1 `af9fe34e9`(V9 `owner_user_id` drop, `deleted_at` 코드 참조 제거). contract 2 `6bb83cf23`(V10 `app_error_logs.deleted_at` drop) 배포. V10 뒤에는 7150d7fa8 이하로 롤백 불가. 후속 `ba740bad7`(`trade-dates`·계좌 범위 `daily-trades`·`MarginPort.getMargin`/`MarginItem` 삭제)·`0ffaea0d0`(Q-22)은 2026-11-16 `75957f431`로 세 role 배포. `TradingPriceFetcher` raw catch는 Q-23 자체 결정(유지), 테스트만 쓰는 메서드 2개는 Q-22. 13 U2: `withAccountNo`·`MarginItem` 2컴포넌트·`isRejected`(테스트 36곳)·`FcmAdapter` 로그 완료. 남김: `AccountBalance.hasSufficientDepositFor`·`ReverseModePosition.calcLocBuyAmount`(공개 동작으로 옮길 테스트 없음), `TradingPriceFetcher` raw catch(`tryGet`과 로그·catch 범위가 달라 동작 변경), `MarginPort.getMargin`·`MarginItem` 전체가 호출처 0(범위 밖). 12 F2: `_ping` 삭제(본보기 `AdminUserControllerTest`), 낡은 주석 3곳(4곳 중 1곳은 대상 없음), KbLand 주석. 12 F1: 대상 없음(새 `AlpacaMarketDataAdapter`가 페이지네이션, stats 어댑터 통합은 14 T3·N19). `trade-dates`·`daily-trades`는 S13 범위 밖으로 남김 | 작은 정리: 미사용 코드(13 U2), Alpaca 일봉 페이지네이션(12 F1), `_ping`·낡은 주석(12 F2), `app_error_logs`의 `resolved_at`(11 T5), `owner_user_id`·EPR 완료 행 삭제 설정(11 T8) | 11·12·13 | Sonnet | S9(테스트 fixture)와 동시에 돌리지 않는다 |
| **S14** ✅ 완료(2026-11-15) | **결과**: `findings/25`(통계 대조 — 갈림 3건, 운영에서 실제로 갈리는 것은 최대 1건. N15 권고 규칙은 "`startDate`가 지난 가장 나중 사이클, 스냅샷이 아직 없으면 앞 사이클 값 이월"), `findings/26`(메리츠는 클라이언트 주문 키가 없다. "접수됨"만 확정하는 재전송 없는 복구를 04의 B10으로 올렸다. 선행은 사용자의 실계좌 실측 4건), 알림 문구 스냅샷 테스트 2개(사용자 알림은 23종이 아니라 16종, 26건). 원래 내용: 조사만: 통계 곡선·지수 대조 테스트(13 U3), 메리츠·바이낸스의 접수 불명 주문 복구 가능성(12 F4), 알림 문구 스냅샷(13 U5의 첫 단계) | 12·13 | Opus | 코드 불변. 결과는 findings 25·26으로(19~21은 2차 검토, 22~24는 3차 요청이 썼다). 2026-11-14: F4의 바이낸스 부분은 하지 않는다(15) |

앞 묶음이 끝나야 한다:

| 묶음 | 내용 | 선행 |
|---|---|---|
| N12 | 11 T2~T4(삭제 표시를 루트에만) | 11 D1 결정됨((가), 2026-11-13) + W2 ✅. **05 단계 M 브랜치 전이나 전환 뒤에 한다**(같은 JpaRepository 파일). N7의 02 T6(Store 통합) **앞** |
| N13 | 11 T6(계좌 삭제 시 자격증명 삭제) | 11 D3 결정됨(삭제, 2026-11-13) — 지금 시작할 수 있다. 운영 데이터를 지우는 SQL이라 9-4. 15 T4가 코인 계좌 행을 지운 뒤에 하면 대상이 준다 |
| N14 | 11 T7(1:1 상세 테이블 4개 접기) | 11 D2 결정됨(권고대로, 2026-11-13) + 02 T6 + N8(03) 뒤. 가장 마지막 |
| N15 | 13 U4(통계 일별 장부 하나로) | S14의 대조 결과 + 03 T7 |
| N16 | 13 U5(알림 문구·발송 통로) | N2의 05 X6·X7 |
| N17 | 13 U6(가계부) | S6의 finance 모듈 뒤. 삭제 방식 통일은 N7 안에서 |
| N22(2026-11-16 번호 변경 — 8절의 N18과 겹쳤다) | 13 U7(런타임 설정 한 벌) | N2(05 단계 X) + 12 D0=(가) |
| N23(2026-11-16 번호 변경) | 13 U8(오류 상태 409·502) | S5의 P1 ✅ + ui 확인 ✅(HTTP 400에 기대는 분기 0건, 2026-11-16 재확인). 502는 본문 `ErrorCode`로 재시작 안내와 구분돼야 한다 |
| N24(2026-11-16 번호 변경) | 12 F5~F8(결정된 기능 삭제·추가) | 결정(2026-11-13): D1 유지, D4·D5 추가하지 않음 → 그 태스크는 없다. 남은 것은 D3(수동 체결 보정, 06 완료 뒤 확인 1건). 원래 조건: 각 결정. 삭제는 S6·N7이 그 모듈에 닿기 **전에** 한다 |

순서를 다시 본 결과:

- **기능 삭제가 리팩토링보다 먼저다.** D1(주택)이 삭제면 S6(헥사고날 단계 B)이 benchmark 모듈에 닿기 전에 N24의 F5를 한다. D0이 (나)면 05 D4(admin 셸 해체)와 13 U7이 "남은 것만 옮긴다"로 줄어든다.
- 11의 삭제 규칙(N12)은 05 P3과 02 T6 사이에 끼운다. 셋이 같은 JpaRepository 파일을 고친다: P3(기계적 치환) → N12(쿼리 줄이기) → 02 T6(Store로 옮기기).
- 11 T7(테이블 접기)은 02 T6과 03 뒤다. Store와 종류별 핸들러가 생긴 뒤면 컬럼을 읽는 곳이 한 곳이다.
- **2026-11-15 재검토로 정한 통합 앞뒤 순서**: W2(P3 → P4)와 04 단계 A → (조건이 맞으면 15 단계 A) → 05 단계 M → 관찰 → X → R → G. 이유와 반대 순서의 비용은 05 14-1절.
- S11(유일 제약)은 05 단계 C의 선행 조건이지만 05 단계 M(통합 전환) 전에 걸어 두는 편이 낫다. 전환 중 이중 실행이 나도 이중 주문 대신 저장 실패가 난다.

사용자 결정(11의 D1~D4, 12의 D0~D5)은 2026-11-13에 전부 받았다. 답은 `PROGRESS.md` 맨 위 표다(각 지침서 8절의 표보다 우선).

## 8. 2026-11-14 추가 검토 2차(지침서 14·15)의 묶음

지침서: `14-market-data-provider.md`(공용 시세를 토스 관리자 키에서 Alpaca로), `15-crypto-removal.md`(바이낸스·업비트·코인 매매·코인 지표 삭제). 측정 원본은 `findings/19`~`21`.

**사용자 결정으로 확정된 것(2026-11-14 전부 확정)**: 관리자 증권사 키 시세 폐지, 토스는 주문 브로커로만, 크립토 전부 삭제, 공급자는 Alpaca, 주문 경로의 현재가는 계좌 증권사(14의 3-5절), 15의 C2~C5 권고대로, DCA 종목 BITO·EETH 추가(15 T0). 남은 사용자 작업: ETH 전략과 코인 계좌를 내리는 것(15 단계 P, 시점은 사용자).

**권장 순서: 삭제 먼저, 그다음 시세 전환, 구조 리팩토링은 그 뒤.** 지워질 코드(코인 분기, 관리자 시세 체인)를 03·04·06이 먼저 다듬으면 그 작업이 버려진다.

지금 시작할 수 있다:

| 묶음 | 내용 | 지침서 | 모델 | 주의 |
|---|---|---|---|---|
| **S15** 🟡 **T2 통과(2026-11-20, 판정 기준 변경 — `questions.md` Q-24): 새 경로 값이 독립 출처의 공식 종가와 3일 모두 일치하고, 어긋난 쪽은 구 경로다. N19 착수 가능.** T1·T2 구현 완료(2026-11-15, 2026-11-15 배포 `e044e983c` — **T2 관찰 대기(2026-11-16~18 거래일, 메리츠 한도 로그 0건 확인 포함 — 14의 11절)**). T4·T6은 남았다 — **T2 관찰 2026-11-17(1일차) 불통과 — T3·N19 보류**. **2·3일차(2026-11-18·19)도 불통과(2026-11-20 조회)**: `close+20`은 TQQQ만 매일 어긋난다(0.03·0.04·0.01달러, SOXL·QLD·USD는 매일 0). 기준 0.02를 넘은 날은 3일 중 2일이다. 같은 차이가 다음 날 `prevClose`로 이어지므로(예: old 53.06 대 new 53.03), TQQQ는 두 경로의 종가 출처가 체계적으로 다르다. `prevClose`의 큰 어긋남은 22:00 KST 배치에서 되풀이됐다(11-17 SOXL +4.1·TQQQ +4.0·USD +2.9, 11-18 SOXL +2.2·USD +1.9). old가 그 시각 현재가에 가깝다. 이것은 '구 경로가 프리장 가격을 포함한다'는 설명을 뒷받침하지만, 코드로는 확인하지 않았다. `current`는 ±0.2% 안. 메리츠 429·`BrokerRateLimitException` 0건. Toss 공통 API 429 6건이 있었다. 기준을 바꿀지는 사용자 판단이고, N19는 계속 보류한다. 1일차 기록: ① `prevClose` 8건 전부 diffPct≠0(SOXL +13.5·+18.4, TQQQ +4.0·+8.7, QLD +2.6·+5.6, USD +7.6·+11.0). `old`가 같은 시각 현재가와 거의 같고(예: SOXL 51.39 대 51.41) `new`는 직전 확정 종가(USD 54.89 = 원장 `closing_price`)라 old가 현재가로 대체된 값으로 보인다. 같은 시각 `Toss 401 — 관리자 토큰 복구 재시도 path=/api/v1/candles` warn이 있고 21:10Z에는 3/3까지 갔다. ② `close+20` TQQQ old 53.06 대 new 53.03 차이 0.03달러로 0.02 초과(SOXL·QLD·USD는 0). `current` ≤0.5% 통과(−0.14·−0.20·−0.19·−0.02), 메리츠 429·`BrokerRateLimit` 0건, `new=null` 0건. 원인은 10-2 표 아래 메모(프리장 하락, 사용자 확인) | **결과**: `AlpacaMarketDataAdapter`(인터페이스 없음, `questions.md` Q-12), `PriceSourceComparison`의 `[시세 비교]` 로그. 관찰 명령과 통과 기준은 14의 11절 "T2 실행 결과". 통과하면 N19. 원래 내용: 시세 포트와 Alpaca 어댑터(T1) → 병행 비교 로그(T2). 환율을 Frankfurter 최신값으로(T4), 벤치마크 지수 `feed=sip`(T6) | 14 | Opus 설계, Sonnet 구현 | T2는 운영 3거래일 관찰. 기존 시세 경로는 건드리지 않는다 |
| **S16** ✅ 완료(2026-11-15, 테스트·문서만 — 2026-11-15 `966f5e284`에 포함) | 코인 삭제 전수표(T1), 주식 DCA 테스트 분리·보강(T2). **결과**: T1 `findings/31-crypto-removal-inventory.md`(사용자 결정 `questions.md` Q-15, 다음 trading 마이그레이션은 V23). T2 `12c00e08b`(분리 1)·`05602bbef`(추가 9, 변이 12곳 전부 검출). 주식 경로에서 MARKET·executionHour가 만들어지는 곳 없음(T4 중단 조건 해당 없음) | 15 | Sonnet, Opus | 코드 삭제 없음 |
| **S17** 🟡 구현 완료(2026-11-14, 2026-11-15 배포 `e044e983c` — 사용자 확인 대기, `questions.md` Q-08·Q-09) | DCA 종목 BITO·EETH 추가(T0). **결과**: `StrategyTicker` 상수 2개(QLD 뒤), DCA capability·정책 기본값, 메리츠 `0066/AX`·KIS `AMEX/AMS` 매핑(메리츠는 QLD와 같은 값, 실측 전), 테스트·README·`meritz-api.md`. 운영 정책 JSON은 자동 반영되지 않는다. **배포 뒤 순서**: 메리츠 계좌 전략 등록 화면에서 두 종목 현재가 조회 확인 → 관리자 설정 DCA 허용 종목에 추가(조회 실패 종목은 허용하지 않고 매핑 수정) | 15 | Sonnet | 가장 먼저. 메리츠 거래소 코드 실측 뒤 넣는다. 배포 뒤 사용자가 관리자 설정에서 허용 |

앞 묶음이 끝나야 한다:

| 묶음 | 내용 | 선행 |
|---|---|---|
| N18 ✅ **배포 완료(2026-11-16 16:19 KST, 세 role `090dea39d`) — 관찰 2026-11-20 통과(거래일 11-17~19).** 같은 날 19:24 KST에 세 role이 `ca7709d26`(`a5519aa93` PRIVACY Limit VWAP 포함)으로 다시 배포돼 관찰 창은 두 변경이 섞여 있다 | **배포 확인**: V23 적용(success), 코인 계좌·BTC/ETH 전략·MARKET 주문·crypto-dca 락 0행, 정책 JSON brokers는 MOCK·TOSS·MERITZ만(allowedIp 없음)·DCA 허용 `UPRO,QLD,BITO,EETH` 유지, live 계좌 MERITZ 3·TOSS 1·MOCK 1·전략 6 그대로, 세 role 로그 ERROR 0. push 시각은 창(07:00~16:00)을 13분 넘김(사용자 지시). **남은 것 정리(2026-11-20)**: ① findings/31 8절의 cascade 실검증은 닫았다(`UserCascadeEndToEndIT.계좌가_없는_사용자의_탈퇴도_정상_종료된다`, 변이 확인). ② KRW 반올림 테스트 5건(`TradingStatsServiceTest`의 krwSummary_*·krwEquityCurve_pointsScale0·krwCycles_*)은 되살리지 않기로 확정했다. `TradingStatsService`에는 통화별 분기가 없고 `broker().getCurrency()`와 `Currency.round()`로 일반화돼 있다. KRW 계좌를 만들 수 없어 그 입력이 생기지 않는다. 그래서 T6에서 지울 '통화 분기'도 없었다. 다시 볼 조건은 USD 밖의 통화를 쓰는 증권사가 추가될 때다. ③ `BrokerAccountVerification.apiKeyExpiresAt`은 T6에서 지웠다. `AccountService`가 읽지 않았고 동작은 그대로다. ④ 15 T7: memory `project_broker_strategy`를 '크립토 삭제 완료'로 갱신했고 `reference_oci_region`에도 각주를 달았다. ⑤ G8은 이미 완료돼 있다(`147291878`). **관찰(2026-11-20 03시 KST, 읽기 전용)**: 세 role 로그(컨테이너 기동 11-16 10:24Z 이후 전부)에서 `[정책] 알 수 없는 키를 버립니다`·`No enum constant`·ERROR·Exception 0건. `trading-open`·`trading-close` 매 거래일 한 번씩 끝났다(마지막 close 11-18 20:30Z~21:10Z, open 11-19 13:00Z~14:30Z). 전략별 주문 꼴은 11-12~14와 같다(VR MERITZ 32~40·MOCK 20~24 @14:30Z, PRIVACY 2~4 @20:30Z, DCA 1). 다른 것은 INFINITE `01a1dcd7`뿐이고, 11-18에 리버스모드로 들어간 정상 전이다(MOC 매도 6주, 11-19 LOC 매도 5주 @54.66·매수 1주). Mock QLD DCA는 11-17·18·19 매일 `ORDERED` 1주이고 주문이 연결돼 있다(reference 79.30·75.08·74.12). 마감 리포트는 거래일마다 `리포트 발송 완료` 6건이다. 1통이 아니라 전략별로 1통씩이고, `9e84d5c1` 4건은 MOCK 계좌의 전략 넷(VR·PRIVACY·DCA·INFINITE)이다. EPR은 두 테이블 모두 0행, `app_error_logs`는 5일 동안 0행. `/api/meta` 200: `brokers`는 TOSS·MOCK·MERITZ, DCA 허용 종목은 UPRO·QLD·BITO·EETH. 남은 `CRYPTO`는 가계부 `assetClasses` 값이라 의도대로 남긴 것이다. `/api/market/fear-greed` 200. 계좌 등록·관리자 설정 **화면은 보지 않았다**(API와 정책 JSON으로 대신 확인했다. 화면은 사용자가 확인한다). 같은 시간대 warn: Toss 401(관리자 토큰, 1/3에서 복구), Toss 공통 API 429 6건(`/api/v1/candles`, 1/2에서 재시도), `[PRIVACY] BUY 후보 없음 — 보유 차이 보정 불가`(diff 0.25·15.25, 웹 조회 경로). 메리츠 429와 `BrokerRateLimitException`은 0건. | 15 T3~T5(단계 A: 통째 삭제, 분기 제거, 데이터 정리, 문서). **T3(2026-11-16 15:10 KST, 읽기 전용)**: 10절 SQL 4개 모두 0(BINANCE·UPBIT live 0/total 각 1, ETH 전략 live 0/total 2, 코인 미체결 0, 미완료 코인 EPR 0 — trading EPR 전체 0행). 코인 계좌 밖 BTC·ETH·MARKET·코인 skip_reason·execution_hour 0건. 운영 정책 JSON은 이미 KIS 키 없음·DCA 허용 `UPRO,QLD,BITO,EETH`(2026-11-16 05:55 UTC 관리자 저장), `UPBIT`(false)·`BINANCE`(true)와 `allowedIp`는 남음. **사용자 승인 2026-11-16**(V23 삭제 SQL·행 수·덤프 명령). **덤프** `kista-server:/home/ubuntu/backups/n18-crypto-2026-11-16/`(테이블별 CSV, 권한 700). **실행 전 행 수**: accounts 2, strategy 2, strategy_version 6, strategy_dca_rule 20, strategy_dca_condition 14, strategy_cycle 2, cycle_position 66, cycle_capital_event 4, orders 65, dca_execution 79, trading_batch_report 0, broker_tokens 0, scheduler_locks(crypto-dca) 1. 로컬 태그 `archive/crypto`(삭제 직전 `8170a9484`, push는 사용자 요청 시). **T4·T5 커밋**: `bc158dc59`(통째 삭제)·`1f360bf50`(분기 제거)·`5bc4b3632`(V23)·문서 커밋. `./gradlew test` BUILD SUCCESSFUL, 2026-11-16(리뷰 반영 뒤 관련 테스트 재실행 통과). Opus 리뷰: 결함 없음, 일반 동작 테스트 복원·V23 단언에 trading_ref 추가 반영. **배포 전**: push 직전 15의 10절 SQL 재실행(전부 0), KST 07:00~16:00, 세 role 같은 release. 배포 중 몇 분간 옛 kista-api가 새 trading-core 정책 응답(UPBIT·BINANCE 키 없음)을 읽지 못해 관리자 설정 GET 오류·`/api/runtime-config`가 기본값으로 응답할 수 있다(집행은 새 trading-core라 표시만). **배포 뒤**: infra에 `CMC_API_KEY`·`HEARTBEAT_CRYPTO_URL` 제거 통지(G8), ui에 `gen:types` + `brokerCredentials.ts:16` 정리 통지, 1거래일 관찰(15의 7절 끝) → N20. | **선행 충족(2026-11-16).** S17 배포 ✅, 단계 P ① ✅(코인 계좌·전략 live 0), ui G1 배포 ✅(`8743fa99`), S16 ✅. 단계 P ②(정책 비활성)는 선행에서 뺐다 — 마이그레이션이 정리한다(15의 11절). 05 단계 M **앞**에 한다(05의 15절). 착수 전 15의 11절부터 읽는다: 정책 JSON의 `BTC`·`ETH`는 코드가 걸러 주지 않는다, Q-15는 "함께 삭제"로 답변됨(2026-11-16). 운영 데이터 삭제라 9-4(덤프·SQL·행 수를 사용자에게 보이고 승인, 승인과 덤프 경로를 이 칸에 적는다), 되돌릴 수 없다, KST 07:00~16:00. 원래 조건: S17 배포 + 사용자의 단계 P + ui G1 + S16. N8(03)·N9(06)·N3(04 D·E)보다 먼저 |
| N19 🟡 **T3 구현 완료(2026-11-21, 브랜치 `n19-t3-market-data`, push·배포 안 함 — 배포는 사용자 승인 대기). T5는 T3 관찰 종료 뒤** | **T3 커밋**: `11c4055b1`(계획) → `ba3aa5baa`(`MarketDataClient` 개명, 뉴욕 16:16 전 당일 봉을 전일 종가에서 제외) → `2afb4d5de`(주문 경로 현재가 = 계좌 증권사 `currentPrices`, (계좌, 종목) 키, 전일 종가 = Alpaca, `PriceSourceComparison`·`selectPriceAccount`·`fetchWithFallback` 삭제) → `bbf6cf1f5`(마감 리포트 마감+20분, 확정 종가 없음 → 10분 뒤 1회 재조회 → 그래도 없으면 그 전략 리포트 보류·DONE 미기록, `reconcile`이 closeAt+40분부터 매시 재시도 — Q-30) → `241b777c7`(등록·미리보기·이력·VR 롤오버·DCA 지표·별지점 세션일을 공용 시세로, VR 재설정은 계좌 증권사, Alpaca 전송 오류는 `MarketDataApiException` 503) → `69cbe05dd`(차트 RAW·count 1..600, stats `AlpacaCandleAdapter`·`HistoricalCandlePort` 통합, Mock은 `MarketDataClient` 주입·확정 일봉 없으면 체결 조회 실패, 토스 계좌 현재가 = Alpaca(Q-13 대체 경로, 아래 후속 커밋에서 계좌 토큰으로 교체)) → `9a0a0261d`(문서) → `63b868a70`(차트 종목 코드 검증 — 보안 리뷰) → `6928d9783`(전일 종가 미정산 구간을 정산 시각까지 캐시) → 토스 계좌 현재가를 계좌 토큰 `/api/v1/prices`로(Q-13 운영 읽기 전용 확인 2026-11-21: 토큰 200, prices 200). 태스크마다 Opus 리뷰·수정 뒤 커밋(수정 라운드 T3·T4·T5 각 1회, 재리뷰는 Sonnet). `63b868a70`과 `latestCloses` 캐시 보정은 Sonnet 리뷰. 최종 전체 브랜치 리뷰는 생략(태스크별 리뷰 완료, 사용자 결정 2026-11-21). 별지점 일봉 조회는 배치 계획이 트랜잭션 밖이라 옮기지 않았다(14의 11절). **검증**: `./gradlew test --rerun` BUILD SUCCESSFUL(2026-11-21, root 951·trading-core 2395·shared 147, 실패 0). **main 병합(2026-11-21)**: 바로 주문 삭제와 충돌 8파일 해결, 해결분 Sonnet 리뷰 결함 없음, `./gradlew test --rerun` BUILD SUCCESSFUL(root 951·trading-core 2358·shared 147, 실패 0 — trading-core 감소는 수동 실행 테스트 삭제). **운영 근거(2026-11-20 22:00 KST 개장 배치, 읽기 전용)**: 구 경로 `prevClose`가 또 현재가로 대체됐다 — SOXL old 41.96 대 new 45.30, TQQQ 47.35 대 49.01, QLD 69.65 대 71.26, USD 44.48 대 46.51. new는 원장 `cycle_position.closing_price`(11-19 마감, 45.30·49.01·71.26·46.51)와 같다. `current`는 SOXL +0.05%, TQQQ −0.32%. **배포 전 확인표(9-4, 14 T3)**: ① 선행: infra `trading-guard.sh` 마감 창 상한 06:20 → 06:30 — infra main `b7f8dc9` push·배포 완료(2026-11-21, Server Deploy 44786728303 success) ✅(G7). ② T2 표: Q-24(TQQQ 11-13·16·17·18 종가 Alpaca = 독립 출처, SOXL 11-13 58.32 일치, 어긋난 쪽은 구 경로) + 위 11-20 값. ③ 분할 공시가 있는 주가 아님: 배포 주에 SOXL·TQQQ·USD·QLD·UPRO·BITO·EETH 분할 공시 없음을 배포 당일 확인. ④ 시각: KST 07:00~16:00(마감 리포트 뒤, 개장 배치 전). N9와 다른 배포, N1 전환 주(11-16~20)와 다른 주, N1 관찰(11-23·24 거래일) 통과 뒤 — 11-26 휴장·11-27 조기 마감 주에 배포하면 조기 마감일 리포트(closeAt+20 = KST 03:20)를 첫 관찰에 넣을 수 있다. ⑤ 입력 분석(커널 미실행 — 진행 중 6건, 커널 불변이라 가격 입력만 바뀐다): VR `01a19a27`(메리츠 TQQQ) 매수 사다리 최고 48.51이 cap(현재가×1.05: old 47.54→49.92, new 47.39→49.76) 아래라 주문 동일, 매도는 현재가 무관. VR `01a1923f`(Mock TQQQ) 매도만 — 동일. PRIVACY `01a16c04`·`01a1a5ae` P는 원장 종가라 불변, BUY cap만 현재가(±0.3%)에 따라 1센트 단위로 달라질 수 있다(11-20은 SELL만). INFINITE `01a1dcd7`(Mock USD, 리버스 2일차+) 별지점은 이미 Alpaca, 매수 cap만 Mock 현재가 출처(토스 → Alpaca)가 바뀐다. DCA `01a1a5ad`(Mock QLD) 매수 기준가 max(전일 종가, 현재가)의 전일 종가가 결함 값(69.65)에서 공식 종가(71.26)로 바뀐다 — 결함 수정 방향. ⑥ 배포 직전 SELECT 기대값: `trading.orders` SUBMITTING 0, 미완료 EPR 0(`public`·`trading` 두 테이블), 그날 CLOSE `trading_batch_run` DONE, 진행 중 전략이 위 6건과 같음(다르면 ⑤를 다시 만든다). ⑦ 배포 직전 커널 비교(9-4 "14 T3" 행의 본 비교표 — 아직 안 함): 직전 배치의 `[시세 비교]` old/new 값(T3 배포 전까지 운영이 계속 남긴다)으로, 커밋하지 않는 임시 테스트에서 같은 커널에 두 가격을 넣어 전략별 다음 거래일 주문을 비교한다. 포지션이 매일 바뀌어 지금 만들면 배포일에 낡는다. **관찰 계획(배포 뒤 2거래일, 전환 전날과 대조)**: 전략별 접수 주문의 가격·수량(위 ⑤의 예상과 비교), `[accountId=…] 현재가 조회 실패` 0건, `[마감 리포트 보류]` 0건, 마감 리포트 시각 closeAt+20분(`리포트 발송 완료` 로그 시각, 조기 마감일 03:20 KST), `cycle_position.closing_price`가 공식 종가와 같고 null 0건, 관리자·사용자 알림 건수가 전날과 같음, 메리츠 429·`BrokerRateLimitException` 0건, `MarketDataApiException`·ERROR 0. 명령: `ssh kista-server "docker logs kista-worker --since 24h 2>&1 | grep -E '현재가 조회 실패|마감 리포트 보류|리포트 발송 완료|BrokerRateLimit|429|ERROR'"`, 화면 경로(미리보기·등록·차트의 `MarketDataApiException`·503)는 같은 grep을 `kista-api-kista-web-1`에도. **배포**: 브랜치를 main에 합쳐 push(두 role 같은 release). 계획 파일 `docs/superpowers/plans/2026-11-20-14-t3-market-data-switch.md`는 관찰 끝에 지운다. **다음**: 관찰 통과 → T5(4-4절 삭제, 옛 포트 메서드·관리자 소스·`TOSS_ADMIN_*`, G8 통지) | S15의 T2 통과 ✅(2026-11-20, Q-24). 구 경로 전일 종가가 22:00 KST 배치에서 현재가로 대체되는 결함이 운영에 남아 있어 T3이 그것을 고친다. N9(06 T2~T5)와 같은 배포에 싣지 않는다 |
| N20 ✅ **배포 완료(2026-11-20 07:25 KST, 세 role `ee752d02b`)**: trading V24 success, 세 role 기동 직후 ERROR·Exception 0건, `/api/meta`의 `brokers[]` 키는 `apiKeyExpirySource`·`code`·`currency`·`description`·`label`, 종목은 `code`·`description`·`targetProfitRate`이고 최상위 `markets`는 남았다. ui `gen:types` 재생성 완료(kista-ui `a4b466dc`, typecheck 통과, 제품 코드 참조 0). 커밋 기록: — kernel `Market` 삭제(`Broker`는 `currency` 필드, 커널·조립기의 Market 인자는 `Currency`로), `StrategyTicker.markets`·`tradableOn`·`validateMarket`·`TradingPreviewService`의 항상 false 분기 삭제, `/api/meta`의 `brokers[].market`·`minOrderAmount`·`amountScale`과 종목 `markets`·`market` 삭제(최상위 `markets`·finance `Market`은 남김), 죽은 필드 `BrokerAccountVerification.apiKeyExpiresAt` 삭제, trading `V24__drop_dca_rule_execution_hour.sql`. 리뷰 1회(Sonnet): Critical·Important 0건, Minor 5건(중복 import·고아 주석) 반영. `./gradlew test` BUILD SUCCESSFUL(2026-11-20), V24가 로컬 `kistadb_test`에 적용됐다. **배포**: shared kernel을 바꿨으니 세 role을 같은 release로 배포하고, 시간은 KST 07:00~16:00 창 안이다. V24는 contract 마이그레이션이다. 엔티티 매핑은 `1f360bf50`에서 지웠고 그 expand가 2026-11-16에 배포돼 1거래일 넘게 지났으므로 10-3 규칙을 충족한다. 지금 운영 이미지 `ca7709d26`도 이 컬럼을 매핑하지 않아 V24 뒤 롤백해도 안전하다. 운영 값: 11행 중 non-null 0행. 배포 직후 확인: `/api/meta`에서 위 필드가 빠졌는지, V24 success, 세 role ERROR 0. ui는 `gen:types`만 다시 돌리면 된다(제품 코드 참조 0, 2026-11-20 재확인). **N1보다 먼저 배포한다.** M 브랜치가 열려 있는 동안 main에는 문서와 긴급 수정만 들어간다(N1 칸) | 15 T6(`Market` enum과 메타 필드, `execution_hour` 컬럼) | N18 배포 후 1거래일 + ui의 메타 필드 제거. N14(11 T7)보다 먼저 |
| N21 | 14 T7(접수 직전 재조회 삭제, 개장 조회 한 번) | N9의 06 T2·T3 |

기존 묶음에서 바뀌는 것:

- **W1**: 04 단계 A는 KIS만 다룬다. 업비트는 N18로 옮겼다. `git tag`도 `archive/broker-kis`와 `archive/crypto`로 나뉜다.
- **N3**: 04 단계 E의 코인 포트 정리(2-6)는 하지 않는다. 단계 D의 공통 골격 대상은 메리츠·토스다. 2-2절의 시세 포트 분리는 S15·N19가 한다.
- **N9**: 06 T6(코인)은 없다. `CLOSE_REPORT` 창 시작은 14 D3이 승인되면 `closeAt + 20분`이다.
- **S14**: 접수 불명 주문 복구 조사(12 F4)는 메리츠만 본다.
- **13**: stats의 코인 지표 리팩토링, 설정 정리의 바이낸스·업비트·`CMC_API_KEY` 항목은 대상이 없다.
- 12의 D2는 "삭제"로 바뀌었다(15).


## 9. Fable 없이 실행하는 공통 규칙 (2026-11-14 추가 — 모든 지침서에 적용, 지침서 본문보다 우선)

Fable 한도가 끝나면 `questions.md`에 답할 세션이 없다. 그래서 지금까지의 질문 Q-01~Q-07과 구현 평가(`findings/22`)에서 나온 "틀린 전제 16건"을 유형 7개로 묶고, 유형마다 실행 세션이 스스로 처리하는 방법을 정한다. 지침서별 구체 답은 각 지침서 맨 끝의 "실행 전 보정" 절에 있다. 측정 원본은 `findings/23`(01~07), `findings/24`(08~15)다.

### 9-1. 태스크 착수 전 전제 확인 (10분 안에 끝낸다)

지침서의 줄 번호·줄 수·호출처 수는 2026-11-11~14 시점 값이다. 틀린 전제의 절반 이상(16건 중 9건)이 "코드가 이렇다", "외부 문서가 이렇다"는 단정이었고 전부 실행 세션이 착수 전에 확인할 수 있는 것이었다.

1. 태스크가 이름으로 든 클래스·메서드·컬럼이 있는지 본다: `git grep -n "<이름>" -- '*.java' '*.sql' '*.yml' | head`. zsh에서는 `--include='*.java'`처럼 따옴표를 붙인다(안 붙이면 glob 오류로 결과가 비어 "없다"로 오판한다. `findings/24`에 이 오판이 2건 있었다).
2. "호출처 0", "N곳"이라는 문장은 다시 센다. 숫자가 달라도 태스크의 뜻이 같으면 지금 숫자로 진행하고 커밋 메시지에 한 줄 적는다.
3. 대상이 이미 없으면(다른 태스크가 지웠거나 이름이 바뀜) 그 항목만 건너뛰고 "대상 없음"으로 적는다. 비슷한 이름의 다른 코드를 대신 고치지 않는다.
4. 벤더 API 동작을 근거로 든 문장은 원문을 다시 본다: 메리츠 `/Users/phs/Downloads/meritz_openapi_spec_20261112.json`, 레포의 `docs/agents/*-api.md`, Alpaca는 `findings/21`의 실호출 기록. 토스 스펙 디렉토리(`/Users/phs/Downloads/toss/`)는 2026-11-14에 비어 있었다. 필요하면 사용자에게 다시 받는다.
5. 태스크가 클래스를 옮기거나 지우거나 이름을 바꾸면 9-3의 가드 확인 명령을 먼저 돌린다.
6. **S8(2026-11-14) 뒤 문서 경로가 바뀌었다.** 지침서가 옛 경로를 가리키면 새 경로로 읽는다(대상 없음으로 건너뛰지 않는다): `docs/agents/<벤더>-api.md` → `docs/agents/vendors/<벤더>.md`, `code-quality-backlog.md`·`own-type-ledger.md`·memory 재론 금지 파일 → `docs/agents/decisions.md`(own-type 원장은 5절), `docs/agents/README.md` → 루트 `CLAUDE.md` "문서 지도", `plans/archive`·`specs/archive`·`docs/history`·`docs/reviews`·`deploy/server/schema-reorg` → 삭제됨(git 태그 `archive/docs-2026-11`), 리소스 디렉토리 `CLAUDE.md` → `.claude/rules/flyway.md`. 문서 크기 예산 가드(`.github/tests/doc-budget.bats`)가 있으니 문서를 늘리는 태스크는 같은 커밋에서 `bash .github/scripts/check-doc-budget.sh`를 돌린다.

### 9-2. 유형별 처리

| 유형 | 예 | 처리 |
|---|---|---|
| U1 외부 문서와 지침서가 다르다 | Q-01(바이낸스 403) | 문서 원문을 따른다. 주문 경로면 "접수 여부를 모르면 재전송하지 않는다, 거절이 확실하면 건너뛴다"가 기준이다. 문서로 지침서 문장이 틀렸으면 그 항목을 하지 않고 지침서의 해당 문장 옆에 "철회(날짜, 문서 인용)"를 적는다 |
| U2 코드와 지침서가 다르다 | Q-02(없는 캐시 경로) | 태스크의 목적(지침서의 "문제" 절)이 지금 코드에서도 성립하는지 본다. 성립하면 지금 코드에 맞춰 같은 목적을 이루고, 성립하지 않으면(이미 해결됨, 다른 태스크와 함께 해야 함) 건너뛰거나 지침서가 가리킨 뒤 단계로 미룬다. 지침서 문장을 고쳐 둔다 |
| U3 스펙에 없는 동작이라 실측이 필요하다 | Q-03(메리츠 소수·대출 잔고) | 실계좌 실측은 하지 않는다(실계좌 `*IT`·주문·운영 키 호출 금지). 돈이 덜 움직이는 쪽을 기본값으로 한다: 수량을 부풀리지 않는다, 주문을 더 내지 않는다, 재시도하지 않는다. 고른 값과 반대쪽의 결과를 `questions.md`에 "자체 결정"으로 적는다 |
| U4 동작 변경의 2차 효과 | Q-04(MOC 이틀 연속) | 주문·원장·상태 전이를 바꾸는 태스크는 구현 뒤 아래 세 가지를 테스트나 추적으로 확인한다: ① 진행 중인 전략(배포 전에 저장된 행)이 새 코드에서 어떻게 읽히는가 ② 그 변경이 일어난 다음 거래일의 주문 ③ 주문이 0건이 되어 리포트·상태 전이가 멈추는 경로. 부작용이 운영에서 일어날 수 있으면 배포 전 확인표(9-4)에 적어 사용자에게 보인다. 드문 경로이고 손실 방향이 아니면 그대로 두고 기록한다(Q-04의 답과 같은 기준) |
| U5 배포 승인·시점 | Q-05 | 9-4 |
| U6 중단 조건에 걸렸다 | Q-06(보장 수준 차이) | 9-5 |
| U7 기존 가드와 부딪힌다 | Q-07(ClockConfig) | 9-3 |

### 9-3. 가드와 부딪힐 때

착수 전 확인 명령(옮기거나 지우거나 이름을 바꾸는 클래스의 단순명으로):

```bash
git grep -n "<단순명>" -- src/test/java/com/kista/api/architecture trading-core/src/test/java/com/kista/tradingcore/architecture src/test/resources/architecture/flows.yml .github/tests
```

- **FQCN·패키지 문자열이 가드에 박혀 있다**(허용 목록, 예외 목록, `DisplayName`의 개수): 같은 커밋에서 문자열을 고친다. 묻지 않는다. 지금 박혀 있는 곳: `HexagonalArchitectureTest`의 `UsTradeDates` 허용 목록(`:112-130`), `ClockConfig` 예외(`:179-180`), 계좌 접근 허용 서비스 목록(`:296-311`, `:316`이 존재를 단언), `GradleModuleBoundaryTest`의 `AdminSchedulerController` 예외와 루트 패키지 규칙, `ArchitectureMapTest`의 스케줄 시각·모듈명 단언(`:31-44`).
- **`flows.yml`의 `code:` 참조**: `ArchitectureMapTest`가 클래스·메서드 존재를 검증한다. 패키지 이동은 걸리지 않고 삭제·개명만 걸린다. **삭제·개명하는 그 커밋에서** `code:` 항목을 고친다(문서 태스크로 미루면 중간 커밋이 깨진다). 흐름 전체가 사라지면 그 흐름을 가리키는 `landscape`의 `flow:` 참조도 같이 지운다.
- **규칙 대상이 비게 된다**(`failOnEmptyShould=true`): 대상 클래스가 전부 사라지는 규칙은 규칙째 지운다(예: KIS 삭제 뒤 `kis_parser_must_not_depend_on_application_layer`).
- **규칙의 뜻 자체와 부딪힌다**(의존 방향, 모듈 경계, 시간 기준): 규칙을 약화하거나 예외를 늘려서 통과시키지 않는다. 기본값은 "그 항목을 지침서가 가리킨 뒤 단계(대개 05 단계 M·R)로 미룬다"다(Q-07의 답). 미룰 뒤 단계가 없으면 그 항목만 멈추고 `questions.md`에 "사용자 결정 필요"로 적는다.
- **새 가드를 넣는 태스크**: 지금 위반이 남아 있으면 `@Disabled`로 넣지 않는다. 치환을 끝낸 뒤 넣거나, 현재 위반 전체를 허용 목록에 넣고 켠다(07의 7절이 01의 단계 A-1 문장보다 우선한다).
- 마이그레이션 번호는 지침서에 적힌 값을 믿지 않고 착수할 때 센다: `ls trading-core/src/main/resources/db/migration-trading src/main/resources/db/migration | sort -V | tail`. 2026-11-15 기준 다음 번호는 trading `V22`(V21은 S11), root `V7`이고 지침서 04·11·13·14·15가 모두 여기서 번호를 가져간다. 먼저 커밋한 쪽이 번호를 갖는다.

### 9-4. 배포와 승인

배포는 `git push`로 시작되고 push는 사용자가 요청할 때만 한다(`constraints.md` "Git 규칙"). 그래서 승인 주체는 언제나 사용자다. 실행 세션은 커밋까지 하고, 아래에 해당하면 완료 보고에 **배포 전 확인표**를 붙인다.

| 변경 종류 | 실행 세션이 준비할 것 | 배포 시점 |
|---|---|---|
| 동작이 바뀌지 않는 리팩토링(01·02·03·05의 P·X·R, 07, 09) | 없음. 테스트 통과만 | 제한 없음. 주문 계산·원장 저장 파일을 건드렸으면 매매 시간대 밖 |
| 주문 가격·수량·시각이 바뀐다(06 T2~T5, 14 T3, 08 같은 공식 변경) | 진행 중인 전략 전부의 변경 전후 다음 거래일 주문 비교표(Q-05의 방식: 변경 전 커밋을 별도 worktree로 빌드해 커널을 직접 돌린다). 배포 직전에 돌릴 SELECT와 기대값 | KST 07:00~16:00(마감 리포트 뒤, 개장 배치 전). 미국장 정규 시간(22:30~06:00)에는 하지 않는다 |
| 운영 데이터를 고치거나 지우는 SQL(11 T6·T8, 15 T4, 04 단계 A) | SQL 본문, 실행 전 `SELECT count(*)`로 얻은 예상 행 수, 덤프 명령. 사용자가 보고 승인한 뒤에만 돌린다 | 위와 같다 |
| 스키마 변경 | root는 expand/contract(`check-migrations.sh`가 강제). trading 스키마는 스크립트가 강제하지 않지만 같은 규칙을 스스로 지킨다(05 D3) | 위와 같다 |
| kista-ui와 함께 나가야 하는 응답 변경(10 T4, 13 U8, 15 단계 U) | `cross-repo.md`에 항목을 적고 ui 쪽 커밋이 준비됐는지 사용자에게 확인을 요청한다. 이 세션에서 kista-ui를 고치지 않는다 | 같은 날 |

배포 직전 확인이 0건이어야 하는데 0건이 아니면 그날은 배포하지 않고 다음 거래일 마감 리포트 뒤에 다시 확인한다. 걸린 행을 손으로 지우거나 취소해서 맞추지 않는다.

"2거래일 관찰"의 주체는 실행 세션이다(읽기 전용: `ssh kista-server` 뒤 SELECT·`docker logs`). 관찰 결과는 다음 태스크의 커밋 메시지나 로드맵 상태 칸에 한 줄 적는다. 세션이 이어지지 않으면 로드맵 상태 칸에 "관찰 대기(배포일)"를 적어 다음 세션이 이어받게 한다.

### 9-5. 중단 조건에 걸렸을 때

지침서의 "중단 조건"은 "묻고 기다린다"가 아니라 아래 순서로 처리한다.

1. **되돌린다.** 그 태스크의 미배포 변경은 커밋하지 않거나 `git revert`한다. 배포된 뒤 관찰에서 걸렸으면 사용자에게 이전 커밋으로 되돌리는 배포를 요청한다(삭제 마이그레이션이 나간 뒤에는 되돌릴 수 없으니 그 지침서의 보정 절을 따른다).
2. **지침서에 대체 경로가 있으면 그것을 한다.** 각 지침서의 "실행 전 보정" 절에 중단 조건별 대체 경로를 적어 두었다. 대체 경로의 공통 꼴은 셋이다: 뒤 단계로 미룬다(Q-06·Q-07), 그 항목만 빼고 나머지를 한다(Q-01·Q-02), 지금 동작을 유지한다(Q-03·Q-04).
3. **대체 경로가 없으면** 그 태스크만 멈추고 `questions.md`에 "사용자 결정 필요"로 적은 뒤 독립된 다른 태스크를 계속한다. 선택지는 2~3개로 줄이고 각각의 결과와 실행 세션의 권고를 적는다.

기대값을 바꿔야 통과하는 동작 보존 테스트(스냅샷, 골든 파일, 동등성 IT)는 언제나 1번이다. 기대값을 고치지 않는다. 원인이 기존 결함이면 리팩토링과 분리해 따로 보고한다.

### 9-6. 스스로 정하는 것과 사용자에게 묻는 것

| 스스로 정하고 기록만 한다 | 사용자에게 묻는다 |
|---|---|
| 줄 번호·수치·이름이 지침서와 다르다 | 지침서의 "사용자 결정" 표나 `PROGRESS.md`의 결정을 뒤집어야 한다 |
| 대상이 이미 없다, 다른 태스크와 순서가 바뀌었다 | 운영의 주문 가격·수량·시각이 바뀌는 변경의 배포 |
| 가드의 FQCN·패키지 문자열, `flows.yml`의 `code:` | 운영 데이터를 고치거나 지우는 SQL |
| 동작이 같은 범위 안의 구조 선택(이름, 패키지, record로 묶을지) | kista-ui가 읽는 응답 필드·`ErrorCode`·상태 코드를 없애거나 뜻을 바꾼다 |
| 외부 문서와 지침서가 다를 때 문서를 따르는 것 | 실계좌 호출이나 사용자 계정 작업이 있어야 확인되는 것 |
| 스펙 공백에서 "돈이 덜 움직이는 쪽"을 고르는 것 | 대체 경로가 없는 중단 조건 |
| 뒤 단계로 미루는 것(미룬 곳을 지침서에 적는다) | 지침서가 다루지 않은 새 기능·새 테이블·새 외부 의존 |

기록하는 곳: 한 줄이면 커밋 메시지, 지침서 문장이 틀렸으면 그 지침서를 직접 고친다(답의 원본은 지침서다), 다음 세션이 알아야 하면 로드맵의 묶음 상태 칸, 고른 값의 반대쪽 결과를 남겨야 하면 `questions.md`.

## 10. 2026-11-16 Fable 검수 뒤 상태 (다음 실행 세션은 이 절부터 읽는다)

근거는 `findings/30-post-hoc-review.md`. S18·W1·W2·S13은 사후 검수에서 전부 유지 판정이다. `questions.md`의 Fable 대기 작업 F-01~F-05는 끝났다.

### 10-1. 다음 순서

1. **관찰(2026-11-17 아침, 읽기 전용).** 아래 10-2. 결과를 각 묶음 칸에 한 줄씩 적는다.
2. **N18(15 단계 A, T3 → T4 → T5).** 선행이 충족됐다. 15의 11절부터 읽는다. 05 단계 M보다 먼저다.
3. N18 배포 뒤 1거래일 관찰 → **N1(M 브랜치)**. 05의 14절 + 15절. 전환 방식은 **전체 정지로 결정**(2026-11-16, 사용자 — kista-infra 세션 경유). 전환 일시는 사용자 결정.
4. M 브랜치를 열기 전까지 병행할 수 있는 것: S12 남은 것(PENDING 차단 — `cross-repo.md` G절 "ui 회신 2026-11-16"), N13(11 T6), S15 T4·T6. **N18과 같은 파일을 건드리는 것은 N18 뒤에 한다**: S9(테스트 fixture — N18이 코인 테스트를 대량 삭제한다), S7 T5(메타 — N18·15 T6이 메타를 고친다), S10 T2.
5. N19(14 T3·T5)는 S15 T2 관찰(11-18까지)이 통과한 뒤, M 전환과 다른 주에. T3 전에 Q-13(토스 시세 1회 호출)을 사용자에게 요청하고 kista-infra에 가드 창 상한을 알린다(14의 11절 끝).

### 10-2. 관찰 확인표 (2026-11-15 배포분 — 첫 배치는 2026-11-16 22:00 KST)

| 언제 | 무엇 | 통과 기준 | 닫는 곳 |
|---|---|---|---|
| 11-17 아침 | 개장 배치(거래일 2026-11-17) | `trading.scheduler_locks`의 `trading-open`이 한 번 잡히고 끝남, `orders`에 그 거래일 행이 직전 거래일과 같은 꼴(전략 6개), `app_error_logs` 새 행 0 | S6, W1 단계 B, S13 |
| 11-17 아침 | `01a1dcd7`(MOCK INFINITE)의 주문 | `findings/28` 0-3의 "후" 열과 같은 꼴(LOC 매수 3주·LOC 매도 14주·지정가 43주 — 보유가 그대로일 때) | `2fe2fa152` |
| 11-17 아침 | 유일 제약 | `kista-trading` 로그에 `uq_orders_live_slot`·`uq_strategy_cycle_open`·`ConstraintViolation` 0건 | S11, Q-11(11-18 마감 리포트까지 0건이면 답변됨) |
| 11-17~19 아침 | `[시세 비교]` 로그 | 14의 11절 "T2 실행 결과"의 기준, 메리츠 429·`BrokerRateLimitException` 0건 | S15 T2 → 통과하면 N19 |
| 11-18 아침 | 마감 리포트(거래일 11-17) | `trading-close` 한 번, 텔레그램 리포트 1통, EPR 두 테이블 0행 | S6, S13 |
| 리버스 2일차가 처음 생긴 날 | `REVERSE_INFINITE_LOC_SELL` 가격 | 직전 5세션 종가 평균과 같음, "별지점 일봉" warn 0건 | S18 |

**관찰 결과 2026-11-17 09시 KST(읽기 전용, `ssh kista-server`)**

| 행 | 판정 | 근거 |
|---|---|---|
| 개장 배치 | 통과 | `trading-open` 한 번(13:00:00Z~14:30:07Z), `orders` 11-17 67행·전략 6개, `app_error_logs` 0행 |
| `01a1dcd7` 주문 | 통과(매도 일치, 매수는 cap 가정 차이) | 위 `2fe2fa152` 칸 |
| 유일 제약 | 통과(1거래일) | 세 문자열 0건. Q-11은 11-18까지 열어 둠 |
| `[시세 비교]` | **불통과** | `prevClose` 전부 어긋남, `close+20` TQQQ 0.03달러. 메리츠 429 0건 |
| 마감 리포트 | 선행 통과 | 이 세션의 마감이 이미 끝났다(`trading-close` 한 번, 06:10 KST, EPR 0행, 리포트 발송 완료). 표의 11-18 행은 다음 세션에서 한 번 더 본다 |
| 리버스 별지점 | 해당 없음 | 리버스 2일차 사이클이 없고 "별지점" 로그 0건 |

`[시세 비교]` 불통과 원인(사용자 확인 2026-11-17): Toss 관리자 키는 양호하다. 직전 거래일 프리장에서 크게 하락했고, 구 경로의 `prevClose`가 그 가격을 반영해 정규장 확정 종가(Alpaca)와 어긋난 것이다. 구 경로 `prevClose`가 프리장 가격을 포함하는지는 코드로 확인하지 않았다.
- `prevClose` 숫자 기준(diffPct 0)은 프리장이 크게 움직인 날 어긋날 수 있다. 기준을 바꿀지, 프리장이 조용한 날을 더 볼지는 사용자 결정이다.
- `close+20` TQQQ 0.03달러는 이 설명과 무관하다. 11-18·19를 더 본다.
- 관리자 토큰 401 warn은 남아 있다. 대부분 1/3에서 복구됐고 21:10Z 한 번만 3/3까지 갔다.

**관찰 결과 2026-11-20 03시 KST(읽기 전용, 거래일 11-17~19 — N18 1거래일 관찰과 같은 조회)**

| 행 | 판정 | 근거 |
|---|---|---|
| 개장·마감 배치 | 통과(3거래일) | 락은 매번 한 번 잡혔다가 끝났다. 전략 6개의 주문 꼴은 배포 전 11-12~14와 같다(INFINITE 리버스 전이만 다르다). EPR 0행·`app_error_logs` 0행 |
| 유일 제약 | 통과(3거래일) | 세 문자열 0건. **Q-11 답변됨** |
| `[시세 비교]` | **불통과(3일 중 3일)** | S15 칸. TQQQ `close+20`이 매일 어긋나고 `prevClose`가 22:00 배치에서 되풀이해 어긋난다 |
| 마감 리포트 | 통과 | 거래일마다 전략별 6통(MOCK 계좌 4·MERITZ 2), EPR 0행 |
| 리버스 별지점 | 통과 | S18 칸. 11-19 54.66, 11-20 52.55, warn 0건 |

사용자 확인 대기(실행 세션이 대신할 수 없다): Q-08(BITO·EETH 현재가 조회 → 관리자 설정에서 허용), S12의 운영 로그인·갱신·로그아웃 1회, 다른 PC의 `application-local.yml`에 `kista.role: web`이 있는지(05의 15절).

### 10-3. 9절에 더하는 규칙 (검수 3회에서 되풀이된 것)

- **contract 마이그레이션은 expand 배포 뒤 1거래일이 지나고 낸다**(9-4 "스키마 변경" 칸에 더한다). 직전 이미지와 호환되더라도 같은 날 연달아 내면 되돌릴 여지가 없어진다. 예외는 사용자가 그 자리에서 승인한 경우다.
- **운영 데이터를 지우는 마이그레이션은 승인과 덤프의 기록을 남긴다**(9-4 "운영 데이터 SQL" 칸에 더한다). 로드맵의 그 묶음 칸에 "사용자 승인(날짜), 덤프 경로, 실행 전 행 수"를 적는다. V22는 이 기록이 없었다.
- **완료 판정 명령의 결과 한 줄을 로드맵 묶음 칸에 적는다**(예: "`./gradlew test` BUILD SUCCESSFUL, 2026-11-16"). 커밋 메시지의 "변이 확인"만으로는 전체 스위트를 돌렸는지 알 수 없다.
- **gitignore된 설정 파일이 읽는 키를 바꾸는 커밋**(P4의 `scheduler.enabled` → `kista.role` 같은 것)은 완료 보고에 "로컬 파일을 이렇게 고쳐야 한다"를 적고, 기본값은 안전한 쪽(스케줄러 꺼짐)으로 둔다.
- **묶음 번호는 절을 넘어 겹치지 않게 센다.** 7절과 8절이 N18~N20을 따로 썼다. 7절 쪽을 N22~N24로 바꿨다. 다음 번호는 N25다.

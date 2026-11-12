# kista-api Fable 검토 진행 원장

## 추가 검토 요청 (2026-11-13, 사용자) — 다음 fable-api 세션이 한다

검토 단계를 한 번 더 연다. 아래 3건을 측정(서브에이전트, findings 15~17) → 판단 → 지침서(11~13) 순으로 진행한다. 형식·역할 분담은 `CHARTER.md` 그대로.

1. **DB 테이블 검토** (지침서 11). 기존 지침서는 스키마 5개 유지(05 D2)와 영속 계층 모양(02)만 정했고 테이블 단위는 보지 않았다. 볼 것: 테이블·컬럼 전수와 실제 사용처(읽기·쓰기 0인 컬럼), 전략 상세 테이블의 3단 분산(버전·사이클·포지션 × 종류, 03은 "테이블은 그대로"로 미뤘다), 정규화·중복 컬럼, 인덱스·유일 제약 누락(05 M1의 `orders` 슬롯 유일 제약 포함), soft delete 6테이블의 일관성, 금액·수량 타입과 자릿수, 운영 DB의 실제 행 수(읽기 전용 조회 `ssh kista-server`). 변경은 05의 expand/contract 규칙을 따른다.
2. **기능 검토: 유지가 애매한 것·보완할 것·추가할 것** (지침서 12). 엔드포인트·스케줄러·알림 전수를 기능 목록으로 만들고 기능별 코드량과 운영 사용 흔적(운영 DB 행 수, 로그)을 붙인다. 후보로 이미 보인 것: PRIVACY·FIDA 연동, 백테스트(DCA 미지원), 벤치마크(주택/ETF), 공포탐욕·코인 지표, 가계부(5,024줄), MOCK 브로커, 수동 체결 보정, 관리자 런타임 설정. kista-ui 쪽 화면 사용 여부는 fable-ui에 묻는다. 삭제·추가 판단은 사용자 결정 목록으로 낸다.
3. **사용자가 지정하지 않은 영역의 리팩토링 필요 여부** (지침서 13). 지침서 01~10이 직접 다루지 않은 곳: `api.finance`(5,024줄), `api.admin`(3,697), `tradingcore.stats`(5,357 — 03은 백테스트만 다룸, `TradingStatsService` 435·`InvestmentIndexCalculator` 335 내부 중복 미측정), `notify` 양쪽과 알림 문구, 보안·JWT·인증 흐름, 설정 파일(`application*.yml`의 미사용 키 — findings 06 미조사), 이벤트·EPR 사용 방식, 오류 처리·`BrokerCallGuard`, 메서드·필드 단위 미사용 코드. 영역별로 "손댈 필요 없음"도 근거와 함께 적는다.

끝나면 `guides/00-roadmap.md`에 묶음을 추가한다. 11은 02·03·05와, 12의 삭제 결정은 03·04·07과 겹치므로 실행 순서를 다시 본다(기능 삭제가 먼저면 그 기능의 리팩토링은 하지 않는다).

**진행 상태(2026-11-13 세 번째 fable-api 세션)**: 측정 서브에이전트 4개 투입(Sonnet, 죽으면 SendMessage로 재개. 산출물 파일에 절 단위로 기록하므로 파일을 읽고 빈 절부터 잇게 한다). 3번 항목은 분량 때문에 둘로 나눴다.

| 산출물 | 범위 | 상태 |
|---|---|---|
| `findings/15-db-tables.md` | 1번(DB 테이블) | 완료 → **지침서 `guides/11-db-tables.md` 작성 완료**(사용자 결정 D1~D4) |
| `findings/16-feature-inventory.md` | 2번(기능 목록) | 측정 완료, 지침서 12 작성 중 |
| `findings/17-unassigned-modules.md` | 3번 중 finance·admin·stats·notify | 측정 완료, 지침서 13 대기 |
| `findings/18-crosscutting.md` | 3번 중 보안·설정 키·이벤트·오류 처리·미사용 코드 | 측정 완료, 지침서 13 대기 |

표 정정 메모: 아래 "지침서 주제" 표의 04 행 "운영자 메리츠 앱키 결정 대기"는 04 4-3절(시세 토스 유지)로 해소, 08 행 "F1·F3·F4·F6·F7·F8 대기"는 08 3절에서 확정됐다.

## 새 세션 인계 (2026-11-13 새벽, 두 번째 fable-api 세션 종료)

새 fable 세션은 이 순서로 읽는다: `CHARTER.md` → 이 파일 → `guides/00-roadmap.md`(실행 가능 묶음) → `cross-repo.md`.

- **지침서 10개 전부 작성 완료**: 00 로드맵, 01 헥사고날, 02 영속·DTO, 03 전략 축, 04 브로커, 05 통합·모듈, 06 배치 오케스트레이션, 07 테스트 전략, 08 매매 공식, 09 문서·불필요 파일, 10 UI 계약. 원칙 문서 `ui-api-principles.md`.
- 남은 Fable 작업: 없음(검토 단계 종료). 이후는 실행 세션(Opus/Sonnet)이 `guides/00-roadmap.md`의 묶음을 골라 진행한다.
- **사용자 답을 기다리는 것**: ① 09 8절 승인 A~G(문서·파일 삭제 — S8의 시작 조건) ② 06 3절 D1~D3(개장 대상 확정 시각, 하트비트 하루 1회, 수동 실행 차단 구간. 실행을 막지는 않고 배포 전 확인).
- 이번 세션에서 코드로 확인한 것(06): 거래일 경계 규칙이 호출처마다 다르다(세션 기반 `SessionTradeDates` vs 04:30 고정 `DstInfo.nextTradeDate` — 조기 마감일 02:30~04:30 구간에서 답이 갈린다). `TradingService.executeBatch(contexts)`(주문 시각 대기 경로)는 스케줄러 호출처가 없어 보인다. `DstInfo`에 DST 고정 시각표와 세션 기반 두 모델이 공존한다.
- 09 작성 중 확인: `V1__init.sql` 2개의 주석이 `deploy/server/schema-reorg/`를 가리킨다 — 디렉토리를 지워도 이 주석은 고치면 안 된다(체크섬). `findings/06`의 "Java 소스 `CQ-` 인용 0건"은 틀렸다(테스트 `@DisplayName`·주석·`V17` 마이그레이션에 있다. 그대로 둔다).
- 진행 중 서브에이전트: 없음.
- 다른 세션 상태(2026-11-12 기준): fable-infra 검토 완료·실행 진행 중, fable-ui 지침서 9개 완료·실행 진행 중, fable-fida 검토 완료(별도 레포 유지 결정).

### 이전 인계 (2026-11-12 밤)

- 2026-11-12 밤 결정: 시세·캔들·환율 전부 토스 유지(04 4-3절). 공급자 이전 작업 없음.
- VR 기본값 차이 8건: KISTA 값이 맞다고 확정(2026-11-12). 기본값은 운영 판단으로 바뀔 수 있는 값이라 공식 SSOT의 "변경 금지" 대상에서 뺀다(08 T6에서 반영).


새 세션은 `CHARTER.md`를 먼저 읽는다.

## 규모 기준선 (2026-11-11)

- main 약 55,500줄: root 16,501 / trading-core 35,581 / shared 3,436
- test 약 74,500줄: root 21,102 / trading-core 53,406
- 큰 모듈: tradingcore.trading 16,324, broker 7,632, stats 5,357, api.finance 5,024, admin 3,697, matching 2,725

## 상태 (2026-11-11 12:40 갱신)

| 단계 | 상태 | 산출물 |
|---|---|---|
| 0 기준 합의 | 완료 | CHARTER.md |
| 1 측정 | findings 01~05 완료. 06(문서·불필요 파일)과 quantstack 정리는 서브에이전트 진행 중 | findings/ |
| 2 주제 선정 | 완료(아래 표) | |
| 3 지침서 | 미착수 | guides/ |
| 4 레포 간 계약 | infra 항목 수신·기록 | cross-repo.md |

## 측정 핵심 숫자

- 인터페이스 149개 중 138개(92.6%)가 구현 1개. UseCase 52개 전부.
- 영속 계층(Entity·JpaRepository·PersistenceAdapter) 120파일 7,243줄. 테이블 1개당 5파일 스택.
- record 618개 8,051줄(main 14.5%). Response DTO가 도메인과 필드 100% 일치하는 사례 다수.
- 전략 타입 분기 약 63곳. 다형성은 주문 계산·등록 resolver에만.
- broker 모듈의 60.7%(4,631줄)가 비주력 브로커. 운영 DB(2026-11-11 조회): MERITZ 계좌 3·활성 전략 2, BINANCE 1·1, KIS 1·1, MOCK 1·4, TOSS 1·0, UPBIT 1·0.
- root↔trading-core 경계 코드 약 89파일 3,430줄(6.2%) + 사용자 복제 동기화 약 730줄. ui는 trading-core를 직접 호출한다.
- 테스트만 줄여서는 5~8%가 한계. 보호군 약 22k줄.

## 사용자 결정 기록

- 2026-11-11 브로커: **업비트·KIS 삭제, 토스는 시세·환율·캔들·대시보드만 남기고 축소.** KIS 활성 전략 1건의 이전이 선행 조건.
- 2026-11-11 UI·API 책임 분담을 원칙으로 통일: `ui-api-principles.md`. 기본값은 API.
- 2026-11-11 문서 정리, 불필요 파일 삭제를 범위에 추가.
- infra 쪽 결정 6건은 `cross-repo.md` A절.
- 2026-11-12 지침서 05: **D1~D4 전부 권고대로**(한 코드베이스 + web/worker role, 스키마 5개 유지·물리 DB 분리 폐기, trading 스키마 expand/contract, admin·web 셸 해체). 합칠 때 생기는 문제의 대안을 Fable이 한 번 더 검토하고, Opus/Sonnet이 Fable 없이 실행할 수 있게 지침서를 상세화한다.
- 2026-11-12 지침서 08: F1~F6 권고대로 진행. **F7 KISTA 유지(실력공식 — 근거: 사용자 글 https://velog.io/@narafu/VR , 원저작자 라오어 카페 글 인용)**, F8 KISTA 유지.
- 2026-11-12 지침서 04: **토스 주문 유지**(대중적인 증권사라 연결을 남긴다. 메리츠를 우선하는 이유는 환율·매매 수수료). 메리츠·토스·바이낸스 API 문서를 다시 확인해 KISTA 필요 기능에 맞춰 리팩토링. 문서 원본: `/Users/phs/Downloads/meritz_openapi_spec_20261112.json`, `/Users/phs/Downloads/toss/`, https://developers.binance.com/en/docs/catalog

- 2026-11-12 추가 요청: ① OCI 인스턴스 2개(1번 kista-ui·kista-api, 2번 fida) 통합 여부와 더 나은 구성 → fable-infra에 위임(`kista-infra/.../guides/03-instance-topology.md`) ② fida를 KISTA에 합칠지 → fable-fida 세션에 위임 ③ 토스 관리자 키로 받는 공용 시장 데이터를 메리츠로 대체 가능한지 → findings 11의 8절(서브에이전트 진행 중), 결과를 지침서 04에 반영.

## 진행 중 서브에이전트 (2026-11-12 11:40, 죽으면 SendMessage로 재개)

| id | 산출물 | 쓰임 |
|---|---|---|
| (완료) | findings/09-process-local-state.md | 지침서 05 개정(아래 방향) |
| (완료) | findings/10-merge-collisions.md | 지침서 05 개정 완료 |
| (완료) | findings/11-broker-api-fit.md | 지침서 04 개정 완료 |
| (완료) | findings/12-binance-api-fit.md, findings/13-vr-velog-diff.md | 04 개정, 08 반영 완료 |

## 지침서 05 개정 방향 (2026-11-12, findings 09 수신 후 Fable 판단 — findings 10 수신 뒤 지침서에 반영)

findings 09가 "같은 trading 코드가 web·worker 두 프로세스에서 돌 때" 깨지는 것을 확인했다: M1 계좌 예산 락이 JVM 락뿐(`AccountBudgetLock`, Fable이 코드로 확인. `orders`에 슬롯 유일 제약도 없음), M2 브로커 호출 간격 게이트가 프로세스별, M3 토큰 발급 직렬화가 JVM 락.

**개정 원칙: 코드는 하나로 합치되, 매매 엔진(원장을 바꾸거나 브로커를 호출하는 코드)은 worker 한 프로세스에서만 실행한다.**

- 단계 A(필수): 한 코드베이스·한 모델·한 이미지. role은 web(user·finance·benchmark·market·ops의 HTTP)과 worker(모든 @Scheduled + trading·account·stats·broker의 HTTP 전부 = 지금의 kista-trading + kista-scheduler). trading 코드는 여전히 한 프로세스에서만 돌므로 M1~M3이 **발생하지 않는다**. 합치는 이득(내부 HTTP·contract·사용자 복제본·이중 셸·shared.kernel 해체)은 전부 얻는다. web이 trading 데이터가 필요하면 읽기 전용 모듈 API를 프로세스 안에서 직접 호출한다(DB 조회만, 브로커 호출·쓰기 금지 — 가드로 강제).
- UI 진입점은 Caddy가 경로로 web·worker에 나눠 보내 하나의 호스트로 보이게 한다. 경로 목록은 컨트롤러의 role 표시에서 생성하고 `CaddyRoutingTest`로 고정한다.
- 단계 B(선택, 나중): 브로커를 부르지 않는 trading 조회 엔드포인트를 web으로 옮겨 worker 배포 중에도 화면이 뜨게 한다. 전제: 통계 캐시 무효화 전파(S1).
- 단계 C(선택, 필요해질 때만): 쓰기·브로커 호출까지 web으로 — M1(세션 단위 advisory lock 또는 Redis 락)·M2(Redis 슬롯 예약)·M3(토큰 lease 일반화) 선행. 지금은 하지 않는다.
- 단계 1의 즉시 위험 M4(같은 이벤트를 root·trading이 서로 재발행 → 무한 순환, 오류 로그 이중 기록)는 "한 컨텍스트에 올리는 커밋"보다 **먼저** 스트림 경로를 끊는 것으로 순서를 바꾼다. M5: role 플래그 기본값은 꺼짐. M6: EPR 테이블 한 벌로.

- 2026-11-12 시세 공급자 결정: 메리츠 관리자 키는 쓰지 않는다(계좌 종속). 캔들·현재가·전일 종가는 Alpaca, 환율은 Frankfurter(예비 fawazahmed0), Alpaca로 안 되는 것(확정 종가)은 토스 유지. 지침서 04의 4-2절.
- 2026-11-12 인스턴스: 운영 1대 + 최소 대기 1대(fable-infra 세션에서 결정). fida는 별도 레포 유지 권고(fable-fida, 사용자 결정 대기).
- **실행 순서와 지금 시작할 수 있는 묶음: `guides/00-roadmap.md`.**

## 지침서 주제

| # | 주제 | 주 근거 | 상태 |
|---|---|---|---|
| 01 | 헥사고날 다이어트 | findings 02 B·C | **작성 완료** `guides/01-hexagonal-diet.md` |
| 02 | 영속·DTO 삼중 구조 축소: 조회는 View 직행, Command 직접 바인딩, 애그리게이트당 Store. JPA 유지(JDBC 전환 기각) | findings 02 A·D, JPA 사용 집계 | **작성 완료** `guides/02-persistence-and-dto.md` |
| 03 | 전략 축 재설계: sealed `PlanInput`·`PlanState`, 종류별 핸들러 `StrategyKind`, 종류별 패키지, 백테스트 `runDays` 통합 | findings 03 A·E | **작성 완료(2026-11-13)** `guides/03-strategy-axis.md` |
| 04 | 브로커 축: KIS·업비트 삭제, 토스 주문 유지, 스펙 결함 9건, 토큰 단순화, 시세 공급자 메리츠 이전(2단계) | findings 03 B·11·12 | **개정 완료(2026-11-12)** `guides/04-broker.md`. 사용자 결정 대기: 운영자 메리츠 앱키를 관리자 키로 쓸지, 시세 이전 2단계 권고 |
| 05 | 코드베이스 통합과 모듈 재구성(엔진은 worker 단일 프로세스, 단계 P→M→X→R→G) | findings 09·10 | **전면 개정 완료(2026-11-12)** `guides/05-modules-and-processes.md`. 사용자 확인 대기: 통합 방식 개정(첫 권고 대비 worker 배포 중 매매 화면이 멈추는 점) |
| 06 | 배치 오케스트레이션: 단계 3개(자지 않는 배치), 판정표 순수 함수, 스케줄러 하나, 시각 모델 하나, DCA 실행기는 둘 유지 | findings 03 C·D, 스케줄러·`TradingService`·`DstInfo` 직접 확인 | **작성 완료(2026-11-13)** `guides/06-batch-orchestration.md`. 사용자 결정 D1~D3 대기(실행은 막지 않음) |
| 07 | 테스트 전략: 종류 4개(규칙·흐름·저장·계약), 규칙을 아래로, fixture 단일화, 보안·오류 상태는 정책 테스트 한 곳 | findings 04 | **작성 완료(2026-11-13)** `guides/07-test-strategy.md`. T1만 지금 시작 가능 |
| 08 | 매매 공식 대조 판정 | docs/reference/quantstack, findings 08, 커널 코드 직접 확인 | **작성 완료** `guides/08-formulas.md`. **사용자 결정 F1·F3·F4·F6·F7·F8 대기**. F1(별%를 금액 자릿수로 반올림)은 결함 판정 |
| 09 | 문서 구조 개편과 불필요 파일 삭제: 남기는 기준 2문, `decisions.md` 한 곳, 크기 예산 CI 가드, archive 5.6MB 삭제 | findings 06, cross-repo A5·A6·결정 2·6 | **작성 완료(2026-11-13)** `guides/09-docs-and-dead-files.md`. **사용자 승인 A~G 대기** |
| 10 | UI 계약: 스펙 required·BigDecimal(T1·T2 최우선), 목록 규약(객체 봉투, 커서 1종), 메타 통합, 상태 플래그, 오류 문구, 가계부 집계 | cross-repo B, ui-api-principles.md, findings 07 | **작성 완료** `guides/10-ui-contract.md`. T8(가계부 집계)은 fable-ui 명세 대기 |

재심은 주제별로 한다: findings 05에서 그 주제에 걸리는 결정만 꺼내 지침서에 재심 결과를 적는다.

## 새 코드 반영 (2026-11-11 조기 마감 커밋 9e542baf7~d3d78b0b3)

- main 37파일 변경. 거래일·세션 개념이 trading 22·broker 7·marketcalendar 4·shared 5·root 2 파일에 흩어져 있음을 지침서 05의 3-3절에 반영.
- 의심: `TossPriceApi:163-199`의 정규장 판정 복제본이 조기 마감을 반영하지 않는다(미확인).
- `TradingCloseScheduler` 239줄에 스케줄 판단 로직이 있다. 지침서 06에서 다룬다.

## 다음 할 일

1. (완료) 지침서 04. DCA 실행 파이프라인 이원화는 06으로 옮겼다.
2. 지침서 02 작성.
3. (완료) 서브에이전트 측정 전부 종료. 진행 중인 서브에이전트 없음.
4. fable-ui가 보낼 "원칙 위반 전수 목록"을 받아 지침서 10에 반영.
5. findings 06에서 확인할 것: 레포 밖 `/Users/phs/workspace/kista/ARCHITECTURE.md`가 Java 21·Boot 3·Fly.io로 적혀 현재와 완전히 어긋남, `modules/tradingcore/dca.md` 미로드, `code-quality-backlog.md` 약 800줄 삭제 후보, plans/specs archive 179개 약 5.6MB.

## 다른 세션

- fable-infra: 검토 완료(지침서 2개, 사용자 결정 반영).
- fable-ui: 측정 완료, 주제 9개 선정. 원칙 문서 전달함, 위반 전수 목록 회신 대기.

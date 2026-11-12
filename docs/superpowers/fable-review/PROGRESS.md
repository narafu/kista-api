# kista-api Fable 검토 진행 원장

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

## 지침서 주제

| # | 주제 | 주 근거 | 상태 |
|---|---|---|---|
| 01 | 헥사고날 다이어트 | findings 02 B·C | **작성 완료** `guides/01-hexagonal-diet.md` |
| 02 | 영속·DTO 삼중 구조 축소: 조회는 View 직행, Command 직접 바인딩, 애그리게이트당 Store. JPA 유지(JDBC 전환 기각) | findings 02 A·D, JPA 사용 집계 | **작성 완료** `guides/02-persistence-and-dto.md` |
| 03 | 전략 축 재설계 | findings 03 A·E | 미착수 |
| 04 | 브로커 축: KIS·업비트 삭제, 토스 주문 유지, 스펙 결함 9건, 토큰 단순화, 시세 공급자 메리츠 이전(2단계) | findings 03 B·11·12 | **개정 완료(2026-11-12)** `guides/04-broker.md`. 사용자 결정 대기: 운영자 메리츠 앱키를 관리자 키로 쓸지, 시세 이전 2단계 권고 |
| 05 | 코드베이스 통합과 모듈 재구성(엔진은 worker 단일 프로세스, 단계 P→M→X→R→G) | findings 09·10 | **전면 개정 완료(2026-11-12)** `guides/05-modules-and-processes.md`. 사용자 확인 대기: 통합 방식 개정(첫 권고 대비 worker 배포 중 매매 화면이 멈추는 점) |
| 06 | 배치 오케스트레이션 | findings 03 C·D | 미착수 |
| 07 | 테스트 전략 | findings 04 | 01~03 뒤 |
| 08 | 매매 공식 대조 판정 | docs/reference/quantstack, findings 08, 커널 코드 직접 확인 | **작성 완료** `guides/08-formulas.md`. **사용자 결정 F1·F3·F4·F6·F7·F8 대기**. F1(별%를 금액 자릿수로 반올림)은 결함 판정 |
| 09 | 문서 구조 개편과 불필요 파일 삭제 | findings 06(완료), cross-repo A5·A6·결정 2·6 | 미착수 |
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

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

## 지침서 주제

| # | 주제 | 주 근거 | 상태 |
|---|---|---|---|
| 01 | 헥사고날 다이어트 | findings 02 B·C | **작성 완료** `guides/01-hexagonal-diet.md` |
| 02 | 영속·DTO 삼중 구조 축소(목록 페이징 형식 통일 포함) | findings 02 A, 01 패턴 1·2 | 다음 작성 |
| 03 | 전략 축 재설계 | findings 03 A·E | 미착수 |
| 04 | 브로커 축: 업비트·KIS 삭제, 시세 분리, HTTP·토큰 공통 골격, facade 제거. **가장 먼저 실행** | findings 03 B | **작성 완료** `guides/04-broker.md`. 토스 주문 삭제는 사용자 확인 대기 |
| 05 | Modulith 모듈 구성과 프로세스 분리 재검토 | findings 01·02 E·05, history, Modulith 2.1 문서 | **작성 완료** `guides/05-modules-and-processes.md`. **사용자 결정 D1~D4 대기**(D1: 한 코드베이스 + web/worker role 권고). 결정 뒤 단계 0 조사(`findings/09-process-local-state.md`)부터 |
| 06 | 배치 오케스트레이션 | findings 03 C·D | 미착수 |
| 07 | 테스트 전략 | findings 04 | 01~03 뒤 |
| 08 | 매매 공식 대조 | docs/reference/quantstack(49파일), findings/08-formula-diff.md(완료, 차이 약 40행. PRIVACY·DCA·재시작 금액·리버스 테스트는 미대조) | Fable이 코드 직접 확인 후 판정 필요. 최우선: 별% 1%p 반올림(`InfinitePositionTest.java:52`) |
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

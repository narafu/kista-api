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

## 지침서 주제 (작성 순서)

| # | 주제 | 주 근거 | 상태 |
|---|---|---|---|
| 01 | 헥사고날 다이어트: UseCase 인터페이스·단일 구현 포트·순수 위임층 제거, ArchUnit 규칙 개정 | findings 02 B·C, 01 패턴 3 | 미착수 |
| 02 | 영속·DTO 삼중 구조 축소: 테이블당 5파일 스택, domain↔Entity↔Response 재선언 | findings 02 A, 01 패턴 1·2 | 미착수 |
| 03 | 전략 축 재설계: 타입 분기 63곳, PlanContext nullable, 전략별 상세 영속 스택, BacktestEngine 3벌, DetailAssembler 이중 구현 | findings 03 A·E | 미착수 |
| 04 | 브로커 축: 비주력 제거 범위 + 공통 골격(HTTP·토큰·rate limit), facade 제거, DCA 실행 파이프라인 이원화 | findings 03 B | 사용자 결정 대기(KIS 활성 전략 1건) |
| 05 | 프로세스·스키마 전제 재검토: 2-프로세스, 5스키마, 사용자 복제, contract | findings 01 경계 집계, 05 S항목 | 미착수 |
| 06 | 배치 오케스트레이션: 마감 배치 서비스 약 20개, 의존 8개 이상 서비스 19개 | findings 03 C·D | 미착수 |
| 07 | 테스트 전략: 등록 규칙을 도메인 테스트로 이전, WebMvc 오류 매트릭스, fixture | findings 04 | 01~03 뒤 |
| 08 | 매매 공식 대조: quantstack 정리본 ↔ trading-formulas.md·matching 커널, cap 1.05 모순 | docs/reference/quantstack, findings 05 모순 | 정리본 대기 |
| 09 | 문서 구조 개편과 불필요 파일 삭제 | findings 06, cross-repo A5·A6 | 측정 대기 |

재심은 주제별로 한다: findings 05에서 그 주제에 걸리는 결정만 꺼내 지침서에 재심 결과를 적는다.

## 다음 할 일

1. 지침서 01 작성: findings 02의 B·C 절과 `HexagonalArchitectureTest` 규칙을 읽고 "남길 포트의 기준"을 정한다.
2. quantstack 에이전트(재개됨)와 06 에이전트 완료 알림을 받으면 README 누락 여부 확인.
3. cross-repo A3(readiness healthcheck) 판단을 fable-infra에 회신.
4. 사용자 결정 대기: 비주력 브로커 제거 범위.

## 다른 세션

- fable-ui, fable-infra에 착수·범위 추가(문서 정리, 불필요 파일) 전달 완료. fable-infra는 측정 완료, 지침서 2개 작성 중.

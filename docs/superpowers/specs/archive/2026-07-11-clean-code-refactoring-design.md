# 2차 검토 사이클 — 클린코드 전역 리팩토링 설계

**날짜**: 2026-07-11 (오전 전체 4렌즈 검토·Task 10건 실행 완료 후 2차 사이클)
**사용자 확정 컨텍스트**:
- 프로젝트 목적: 공개 SaaS 확장 목표
- 이번 사이클 최적화 대상: 기능 완성도 + 클린코드 병행 중 **클린코드 전역 우선** (접근 C)
- SaaS 상용화 갭(결제·구독·온보딩·권한 등)은 실행 Task가 아닌 "다음 사이클 후보 목록"으로만 계획에 첨부

## 목적

kista-api(Java 544파일) + kista-ui(TS 326파일) 전역을 다시 읽고, **동작 불변** 전제의 클린코드 리팩토링 계획을 작성한다. 산출물은 저렴한 모델이 단독 실행 가능한 계획 파일 1개.

## 검토 방법

- 양 레포 전체 읽기 — Explore 서브에이전트 영역별 병렬 fan-out + 오케스트레이터가 핵심 파일 직접 검증
- 4렌즈 적용 비중: ①과잉·중복(잘라낼 것) ②취약·불명확 중심 / ③누락은 SaaS 갭 목록화만 / ④구조충돌은 오전 "유지 확정" 목록 제외
- 오전 사이클 유지 확정(재론 금지): 매매 helper 세분화, CycleOrderStrategy 이중 계층, UseCase 1구현체 22개, admin 서비스 6종, 브로커 capability 포트 14개, UI FSD·프록시 구조, Prometheus/Grafana 스택

## 클린코드 판정 기준

- 프로젝트 코드 철학 그대로: 재사용성·가독성·최신 문법(Java 21 record/pattern matching, 최신 TS), 불변 record, package-private, 보일러플레이트·중복 null guard 제거
- **동작 불변이 절대 조건** — 기존 테스트 전체 통과로 검증
- 매매 공식·주문 생성 로직(`InfiniteStrategy`/`PrivacyStrategy`/`VrStrategy`/`InfinitePosition`/`VrPosition`)은 로직 구조 변경 금지, 주변 정리만 허용

## 산출물

`docs/superpowers/plans/2026-07-11-clean-code-refactoring.md` 1개:
- Task별 무엇을/왜/파일/순서/검증 방법, 영향력 내림차순
- 오케스트레이션·Task별 모델 라우팅 표 (오전 계획과 동일 형식)
- 열린 질문 상단 배치
- SaaS 상용화 갭 목록 섹션 (실행 Task 아님)

## 검증 게이트

- kista-api: `bash gradlew compileJava` + 전체 테스트 통과 (실패 진단은 XML: `grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'`)
- kista-ui: `npm run typecheck` + `npm run test`
- 리팩토링 Task마다 검증 명령 명시, BOM 삽입 금지

# Task 2: Fly 배포를 전체 테스트 게이트에 연결 — 실행 보고

## 상태
**DONE**

## 커밋
- Hash: `803566fb`
- Message: `ci: 배포 전 전체 테스트 게이트 — verify job에 PostgreSQL + 전체 테스트 추가`

## 실행 내역

### Step 1: fly-deploy.yml verify job 교체 ✓
- 기존: `Compile & Architecture Test` (compile + ArchUnit만)
- 변경: `Full Test Suite` (PostgreSQL 서비스 포함, 전체 테스트 실행)
- 변경점:
  - `services.postgres` 추가 (postgres:17, health check 포함)
  - compile/ArchUnit 스텝 제거
  - `./gradlew test --no-daemon` 단일 스텝 추가 (ArchUnit 포함)
  - `SPRING_PROFILES_ACTIVE: test` 환경변수 설정

### Step 2: ci.yml 트리거 블록 변경 ✓
- 기존: `push: [main]` + `pull_request: [main]` 병렬 실행
- 변경: `pull_request: [main]` 전용 (push 시 이중 실행 방지)
- `push: branches: [main]` 완전 제거

### Step 3: YAML 문법 검증 ✓
- Tool: `npx --yes yaml-lint`
- Result: ✔ YAML Lint successful (모두 통과)

### Step 4: 커밋 ✓
- `git add .github/workflows/fly-deploy.yml .github/workflows/ci.yml`
- Conventional Commit + Co-Authored-By 포함
- Author: narafu <narafu@kakao.com> (확인됨)

## 검증 결과
- fly-deploy.yml: verify job PostgreSQL 서비스 + 전체 테스트 (ArchUnit 포함)
- ci.yml: PR 전용 트리거, push 이중 실행 제거
- YAML 문법: 모두 valid

## 주의사항
- push는 사용자 요청 시에만 (현재 진행 X)
- Step 5 (push 후 `gh run list` 확인)는 사용자가 수행

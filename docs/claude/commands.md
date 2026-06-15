## 자주 쓰는 명령어

### Gradle
```bash
./gradlew bootJar                                               # app.jar 빌드
./gradlew bootRun --args='--spring.profiles.active=local'      # 로컬 실행
./gradlew test                                                  # 전체 테스트
./gradlew compileJava                                           # 컴파일만
./gradlew test --tests 'com.kista.architecture.*'               # ArchUnit 규칙만
./gradlew test --tests 'com.kista.domain.*'                     # 도메인 단위 테스트
./gradlew test --tests 'com.kista.adapter.out.kis.*'            # KIS Adapter 테스트
./gradlew test --rerun-tasks                                    # 캐시 무시 강제 재실행
./gradlew clean compileJava                                     # QueryDSL 생성파일 캐시 오염 시 (QXxxEntity.java "error reading")
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

### 미참조 타입 전수조사 (dead code 스캔)
```bash
for f in $(find src/main/java -name "*.java"); do
  name=$(basename "$f" .java)
  cnt=$(grep -rlw --include="*.java" "$name" src 2>/dev/null | grep -vF "$f" | wc -l)
  [ "$cnt" -eq 0 ] && echo "ZERO-REF: $f"
done
# 오탐 분류: @RestController/@Configuration → 컴포넌트 스캔으로 동작, @Service/@Component → 인터페이스 DI(UseCase/Port)로 주입
# 진짜 dead: annotation 없는 순수 record/class 이고 src 전체에 참조 없는 것 (예: 과거 StrategyConfig)
# 삭제 후 검증: ./gradlew compileJava && ./gradlew test --tests 'com.kista.architecture.*'
```

### 로컬 admin 토큰 발급 (DevAuthController, local 프로파일 전용)
```bash
# 일반 사용자 토큰
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/_ping  # 403 기대

# ADMIN 토큰 (고정 UUID 00000000-0000-0000-0000-000000000002)
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/_ping  # 200 기대
```

### Docker (로컬)
```bash
docker compose up -d                                            # PostgreSQL + Prometheus + Grafana (앱은 IntelliJ 또는 bootRun으로 별도 실행)
docker compose up -d postgres                                   # DB만 기동
docker compose build <service> && docker compose up -d --force-recreate <service>
```

### 로컬 서버 로그 (IntelliJ 실행 시)
```bash
tail -f logs/kista-api.log                                      # 실시간 로그 확인
tail -100 logs/kista-api.log                                    # 최근 100줄
```
# application-local.yml에 logging.file.name: {프로젝트루트}/logs/kista-api.log 설정됨
# IntelliJ에서 앱 재시작 후부터 파일에 기록됨
# IntelliJ MCP 플러그인: mcp__ide__getDiagnostics — 코드 오류/경고 확인 (런타임 로그 아님)

### Git 구조 (Claude Code 세션 필수 지식)
# kista-api와 kista-ui는 각각 독립 git 저장소 — 루트 /kista에는 git 없음
# 커밋 전 author 확인 필수: git config user.name / git config user.email — 올바른 값: narafu / narafu@kakao.com
# 로컬 repo config가 글로벌보다 우선 — 틀렸으면: git config user.name "narafu" && git config user.email "narafu@kakao.com"
# 잘못 커밋됐으면: git commit --amend --author="narafu <narafu@kakao.com>" --no-edit → git push --force-with-lease
# application-local.yml은 .gitignore에 포함 — git add 불가, Edit 도구로 직접 수정

### git commit 메시지 — Bash 도구 사용 시
# @'...'@ 히어스트링은 PowerShell 전용 — Bash 도구에서 쓰면 @ 가 메시지 앞뒤에 붙음
# Bash 도구에서는 아래 큰따옴표 방식 사용:
```bash
git commit -m "제목

본문

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

## 배포/인프라/외부 연동 런북

저빈도 운영 작업 — 필요시 `docs/claude/docker-infra.md` 참고:
- Fly.io 배포 모니터링/환경변수 설정
- kista-ui 운영 로그 조회, kista-api↔kista-ui URL 변경 연동
- kis-trade-mcp 재시작, .mcp.json 경로 이식성
- Privacy 기준표 운영 → 로컬 마이그레이션 (supabase-cli)

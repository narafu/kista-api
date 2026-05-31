## 자주 쓰는 명령어

### Gradle (macOS)
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

### Render 배포 모니터링
```bash
# 헬스 체크
curl https://kista-api.onrender.com/actuator/health
# 배포 실패 진단: mcp__render__list_deploys → status 확인 후 아래 에러 로그 조회
# mcp__render__list_logs resource=["srv-..."] type=["app"] level=["error"] — 에러만 필터링
# 서비스 ID: srv-d7sir2jbc2fs73cptpm0 (kista 워크스페이스 tea-d7sbrv3rjlhs7389pr60)
# Render MCP 로그 조회 전 반드시 먼저: mcp__render__select_workspace ownerID=tea-d7sbrv3rjlhs7389pr60
# 증상: "Connection to localhost:5432 refused" = DB_URL 환경변수 미설정
# 로컬 Docker 컨테이너명: kista-api-app-1 (앱), kista-api-postgres-1 (DB) — kista-kista-api-1 아님
# 로컬 로그 확인: ~/.local/bin/docker --context desktop-linux logs kista-api-app-1 --tail=200
```

### kista-ui TypeScript 타입 체크 (WSL 환경)
```bash
# npm run build는 lightningcss native module 오류로 WSL에서 실패
# 타입 오류 확인은 tsc 직접 사용
bash -c "cd /mnt/d/src/study/kista/kista-ui && npx tsc --noEmit"
```

### kista-ui URL 변경 연동 (멀티 레포)
# kista-api URL 변경 후 kista-ui에 전달하는 방법:
# 1) 이 대화의 변경 목록을 kista-ui 세션에 붙여넣기 (가장 빠름)
# 2) kista-ui 세션에서: git -C /Users/narafu/workspace/kista/kista-api diff HEAD~1
# kista-api 세션에서 절대경로로 kista-ui 파일 직접 편집 가능하나 git 커밋은 kista-ui 세션에서 따로 수행

### Git 구조 (Claude Code 세션 필수 지식)
# kista-api와 kista-ui는 각각 독립 git 저장소 — 루트 /kista에는 git 없음
# 커밋 전 author 확인 필수: git config user.name / git config user.email — 올바른 값: narafu / narafu@kakao.com
# 로컬 repo config가 글로벌보다 우선 — 틀렸으면: git config user.name "narafu" && git config user.email "narafu@kakao.com"
# 잘못 커밋됐으면: git commit --amend --author="narafu <narafu@kakao.com>" --no-edit → git push --force-with-lease
# application-local.yml은 .gitignore에 포함 — git add 불가, Edit 도구로 직접 수정


### GitHub 레포 rename 후 remote URL 업데이트
```bash
gh repo rename kista-api --repo narafu/kista --yes
git remote set-url origin git@github.com:narafu/kista-api.git
```
# gh rename 후 로컬 remote는 자동 업데이트 안 됨 — 반드시 set-url 필요

### Render 서비스 재생성 시 환경변수 설정
```bash
# mcp__render__update_environment_variables 로 한번에 주입 (serviceId 필요)
# 필수 16개: SPRING_PROFILES_ACTIVE=prod, KIS_APP_KEY/SECRET/HTS_ID/VTS,
#            KIS_ACCOUNT_NO/TYPE/SYMBOL/EXCHANGE_CODE,
#            TELEGRAM_BOT_TOKEN/CHAT_ID, DB_URL/USERNAME/PASSWORD, GEMINI_API_KEY,
#            ADMIN_KAKAO_IDS (쉼표 구분 카카오 ID 목록, 자동 ADMIN 승격)
# 주의: mcp__render__update_web_service 는 서비스명/URL 변경 미지원 → 대시보드 직접 변경
#       URL이 안 바뀌면 서비스를 새로 생성하는 것이 가장 빠른 해결책
```

### kis-trade-mcp 재시작
```bash
# 소스: ~/workspace/open-trading-api/MCP/Kis Trading MCP
# `KeyError: 'my_acct'` 오류 = ENV=live로 실행 시 재시작마다 yaml 재생성 → docker exec sed 수정은 무의미, 이미지 재빌드 필수
docker stop kis-trade-mcp && docker rm kis-trade-mcp
docker build -t kis-trade-mcp:latest ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP
docker run -d -p 3001:3000 --name kis-trade-mcp \
  --env-file ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP/.env.live \
  -e KIS_APP_KEY=<kista .env의 KIS_APP_KEY> \
  -e "KIS_APP_SECRET=<kista .env의 KIS_APP_SECRET>" \
  -e KIS_HTS_ID=<kista .env의 KIS_HTS_ID> \
  -e KIS_ACCT_STOCK=<kista .env의 KIS_ACCOUNT_NO> \
  -e KIS_PROD_TYPE=01 \
  kis-trade-mcp:latest
# KIS_ACCOUNT_NO → KIS_ACCT_STOCK (변수명 다름 주의)
# KIS_PROD_TYPE=01 필수 — .env.live에 빈값으로 있어서 누락 시 my_prod='' → changeTREnv 분기 미적용
```

### .mcp.json 경로 이식성
- args에 절대경로 하드코딩 금지 — `"command": "sh", "args": ["-c", "node ${HOME}/workspace/..."]` 패턴으로 Mac/WSL 공용화
- `env` 섹션 값은 쉘 확장 없이 리터럴 문자열로 전달됨 — `${HOME}` 써도 확장 안 됨
- 환경변수 참조가 필요한 값은 `sh -c "VAR=${HOME}/... node ..."` 형태로 args에 포함
- HTTP 타입 MCP `headers` 값도 리터럴 — 토큰 하드코딩 금지
- 인증이 필요한 MCP는 `stdio` 타입 + `sh -c "npx mcp-remote <url> --header \"Authorization: Bearer ${TOKEN_VAR}\""` 패턴, 토큰은 `~/.zshrc`에 `export TOKEN_VAR=...`
- `~/.claude/settings.json`은 `mcpServers` 미지원 — 글로벌 MCP 서버는 `~/.claude/.mcp.json`에 추가
- `/doctor` "Missing environment variables" 경고는 false positive — `sh`가 부모 환경에서 자동 상속

### Supabase 운영 DB → 로컬 DB 데이터 마이그레이션
# 접속 정보 확인: supabase db dump --linked --dry-run (PGHOST/PGUSER/PGPASSWORD 출력)
# cli_login_postgres 유저는 SET ROLE postgres 없이 테이블 접근 불가
# COPY TO FILE 서버 권한 없음 → COPY TO STDOUT + -q(quiet) 조합 사용
# pg_dump 버전 불일치(로컬 pg_dump < Supabase 서버) 시 psql+COPY 패턴으로 대체
```bash
# 운영 → CSV 내보내기
docker exec kista-api-postgres-1 bash -c "
  PGPASSWORD='<pw>' psql -h <supabase_host> -p 5432 \
    -U 'cli_login_postgres.<ref_id>' -d postgres -q \
    -c 'SET ROLE postgres; COPY public.<table> TO STDOUT CSV HEADER' \
    > /tmp/<table>.csv"

# 로컬 복원 (FK 종속 테이블 먼저 TRUNCATE)
docker exec kista-api-postgres-1 psql -U kista -d kistadb \
  -c "TRUNCATE TABLE <child>, <parent> CASCADE;"
docker exec kista-api-postgres-1 psql -U kista -d kistadb \
  -c "\COPY public.<table> FROM '/tmp/<table>.csv' CSV HEADER"
```

### git commit 메시지 — Bash 도구 사용 시
# @'...'@ 히어스트링은 PowerShell 전용 — Bash 도구에서 쓰면 @ 가 메시지 앞뒤에 붙음
# Bash 도구에서는 아래 큰따옴표 방식 사용:
```bash
git commit -m "제목

본문

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

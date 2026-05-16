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
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

### Docker (로컬)
```bash
docker compose up -d                                            # 앱 + PostgreSQL + Prometheus + Grafana
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
# 필수 15개: SPRING_PROFILES_ACTIVE=prod, KIS_APP_KEY/SECRET/HTS_ID/VTS,
#            KIS_ACCOUNT_NO/TYPE/SYMBOL/EXCHANGE_CODE,
#            TELEGRAM_BOT_TOKEN/CHAT_ID, DB_URL/USERNAME/PASSWORD, GEMINI_API_KEY
# 주의: mcp__render__update_web_service 는 서비스명/URL 변경 미지원 → 대시보드 직접 변경
#       URL이 안 바뀌면 서비스를 새로 생성하는 것이 가장 빠른 해결책
```

### kis-trade-mcp 재시작
```bash
# 소스: ~/workspace/open-trading-api/MCP/Kis Trading MCP
docker stop kis-trade-mcp && docker rm kis-trade-mcp
docker build -t kis-trade-mcp:latest ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP
docker run -d -p 3001:3000 --name kis-trade-mcp \
  --.env-file ~/workspace/open-trading-api/MCP/Kis\ Trading\ MCP/..env.live \
  -e KIS_APP_KEY=<kista .env의 KIS_APP_KEY> \
  -e "KIS_APP_SECRET=<kista .env의 KIS_APP_SECRET>" \
  -e KIS_HTS_ID=<kista .env의 KIS_HTS_ID> \
  -e KIS_ACCT_STOCK=<kista .env의 KIS_ACCOUNT_NO> \
  kis-trade-mcp:latest
# KIS_ACCOUNT_NO → KIS_ACCT_STOCK (변수명 다름 주의)
```

### .mcp.json 경로 이식성
- args에 절대경로 하드코딩 금지 — `"command": "sh", "args": ["-c", "node ${HOME}/workspace/..."]` 패턴으로 Mac/WSL 공용화
- `env` 섹션 값은 쉘 확장 없이 리터럴 문자열로 전달됨 — `${HOME}` 써도 확장 안 됨
- 환경변수 참조가 필요한 값은 `sh -c "VAR=${HOME}/... node ..."` 형태로 args에 포함
- HTTP 타입 MCP `headers` 값도 리터럴 — 토큰 하드코딩 금지
- 인증이 필요한 MCP는 `stdio` 타입 + `sh -c "npx mcp-remote <url> --header \"Authorization: Bearer ${TOKEN_VAR}\""` 패턴, 토큰은 `~/.zshrc`에 `export TOKEN_VAR=...`
- `~/.claude/settings.json`은 `mcpServers` 미지원 — 글로벌 MCP 서버는 `~/.claude/.mcp.json`에 추가
- `/doctor` "Missing environment variables" 경고는 false positive — `sh`가 부모 환경에서 자동 상속

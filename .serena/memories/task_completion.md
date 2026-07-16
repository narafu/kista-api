# 작업 완료 체크리스트

## 코드 수정 후 필수 확인

```bash
./gradlew compileJava    # 컴파일 오류 확인 (BOM 삽입·import 오류)
./gradlew test           # 전체 테스트 통과 확인
```

BOM 제거 명령어는 `docs/agents/constraints.md` "파일 인코딩 주의" 참고.

## 동시 수정 필요 파일 쌍

- 환경변수 추가/제거: `application.yml` + `.env.example` + `docker-compose.yml` + `docs/agents/docker-infra.md` 환경변수 목록
- 새 Flyway 마이그레이션: Entity와 크로스체크 (`nullable`/`length`/`precision`/`scale`)
- Port 인터페이스 수정: 구현 Adapter + 테스트 Mock
- Controller에 새 UseCase 필드 추가: 해당 `@WebMvcTest`에 `@MockitoBean` 추가
- `SecurityConfig`에 새 Filter 추가: `@WebMvcTest` `@Import` 목록에도 추가

## 커밋

`git config user.name`/`user.email` 확인 — `narafu <narafu@kakao.com>`. 한글 Conventional Commit.
push는 사용자가 명시적으로 요청한 경우에만.

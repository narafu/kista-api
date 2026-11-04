## 배포 스크립트 검증 (Docker)
```bash
# bats(.github/tests) — 판정 스크립트·원격 배포 스크립트(docker 스텁)
docker run --rm -v "$(pwd):/code" -w /code --entrypoint sh bats/bats:latest \
  -c "apk add -q git; git config --global --add safe.directory '*'; bats .github/tests"
docker run --rm -v "$(pwd):/mnt" -w /mnt koalaman/shellcheck:stable .github/scripts/*.sh deploy/hooks/*.sh .github/tests/stub/gh
docker run --rm -v "$(pwd):/repo" -w /repo rhysd/actionlint:latest
bash .github/scripts/check-migrations.sh origin/main   # 마이그레이션 불변·expand/contract
```
- Windows Git Bash: docker 볼륨 마운트 시 `MSYS_NO_PATHCONV=1` + `$(cygpath -aw .)` 사용
- 레포 루트에서 실행한다(볼륨 마운트가 `$(pwd)` 기준)

#!/usr/bin/env bash
# 매매 시간대 배포 가드 판정 — 차단이면 사유를 stderr에 쓰고 exit 1.
#
# 사용: trading-guard.sh <dow 1=월..7=일> <HHMM KST> <실행 중 매매 락 목록(쉼표, 없으면 빈 문자열)>
#   시각 창: 배치 기동 10분 전부터 차단 — 락은 기동 후에야 잡히므로 직전 구간은 시각으로만 막는다
#     개장 스케쥴러 월~금 22:30 기동(비DST 23:30 개장 접수) / 마감 스케쥴러 화~토 04:30 기동(비DST 06:10 리포트)
#   락: 시각 창을 넘겨 길어진 배치도 보호(락 목록은 deploy/server/bin/trading-locks.sh가 조회)
set -euo pipefail

dow=$1
now=$((10#$2))
locks=${3:-}

reason=""
if [ "$dow" -le 5 ] && [ "$now" -ge 2220 ] && [ "$now" -le 2340 ]; then reason="개장 스케쥴러 시각 창"; fi
if [ "$dow" -ge 2 ] && [ "$dow" -le 6 ] && [ "$now" -ge 420 ] && [ "$now" -le 620 ]; then reason="마감 스케쥴러 시각 창"; fi
if [ -n "$locks" ]; then reason="${reason:+$reason, }실행 중 매매 락(${locks})"; fi

if [ -n "$reason" ]; then
  echo "$reason" >&2
  exit 1
fi

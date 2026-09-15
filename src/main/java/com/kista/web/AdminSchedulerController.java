package com.kista.web;

import com.kista.stats.adapter.in.schedule.KbLandHousingBenchmarkScheduler;
import com.kista.stats.adapter.in.schedule.KbLandPriceIndexScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 2-role 배포에서 kista-scheduler에만 유효 — kista-api role(scheduler.enabled=false)에서는
// 참조하는 2개 KbLand 스케쥴러 빈과 동일 게이트로 이 컨트롤러 빈 자체가 등록되지 않는다(오라우팅 시 404).
// trading 개장/마감 트리거는 admin.adapter.in.web.AdminTradingSchedulerController(내부 API 호출)로 분리됨 —
// 같은 "/api/admin/scheduler" prefix를 공유하지만 하위 경로가 겹치지 않아 라우팅 충돌 없이 공존한다
// (이 컨트롤러: /kbland-*, 신규 컨트롤러: /open, /close)
@Slf4j
@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)
@Tag(name = "Admin", description = "관리자 API")
public class AdminSchedulerController {

    // 컨트롤러 빈이 존재하면 동일 게이트로 2개 스케쥴러 빈도 항상 함께 존재 — null 체크 불필요
    private final KbLandHousingBenchmarkScheduler kbLandScheduler;
    private final KbLandPriceIndexScheduler kbLandPriceIndexScheduler;

    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }

    // 3개 트리거 엔드포인트 공통 골격 — 백그라운드 가상 스레드 실행 + 인터럽트/예외 처리
    private void triggerAsync(String label, InterruptibleAction action) {
        Thread.ofVirtual().start(() -> {
            try {
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("{} 수동 트리거 인터럽트", label);
            } catch (Exception e) {
                log.error("{} 수동 트리거 오류: {}", label, e.getMessage(), e);
            }
        });
    }

    // KB Land 주택 벤치마크 스케쥴러 수동 트리거 — 수동으로 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "KB Land 주택 벤치마크 스케쥴러 수동 트리거", description = "운영 이슈 발생 시 다음 크론까지 기다리지 않고 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-housing-benchmark")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandHousingBenchmark() {
        triggerAsync("KB Land 주택 벤치마크 스케쥴러", kbLandScheduler::runNow);
    }

    // KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거 — 수동으로 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거", description = "운영 이슈 발생 시 다음 크론까지 기다리지 않고 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-price-index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandPriceIndex() {
        triggerAsync("KB Land 주간 아파트 매매가격지수 스케쥴러", kbLandPriceIndexScheduler::runNow);
    }

    // KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거 — 과거 값 보정을 다음 달 1일까지 기다리지 않고 즉시 반영
    @Operation(summary = "KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거", description = "20년 전체를 다시 받아 KB Land 과거 값 보정을 즉시 반영하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-price-index/full-refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandPriceIndexFullRefresh() {
        triggerAsync("KB Land 주간 아파트 매매가격지수 월간 풀 리프레시", kbLandPriceIndexScheduler::runFullRefreshNow);
    }
}

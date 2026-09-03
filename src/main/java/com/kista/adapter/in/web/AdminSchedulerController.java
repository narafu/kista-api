package com.kista.adapter.in.web;

import com.kista.adapter.in.schedule.KbLandHousingBenchmarkScheduler;
import com.kista.adapter.in.schedule.KbLandPriceIndexScheduler;
import com.kista.trading.adapter.in.schedule.TradingCloseScheduler;
import com.kista.trading.adapter.in.schedule.TradingOpenScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/admin/scheduler")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "관리자 API")
public class AdminSchedulerController {

    // ObjectProvider — @ConditionalOnProperty로 빈이 없을 수 있는 필드 주입을 생성자 주입으로 처리하기 위함(Modulith field-injection 경고 해소)
    private final ObjectProvider<TradingOpenScheduler> openScheduler;
    private final ObjectProvider<TradingCloseScheduler> closeScheduler;
    private final ObjectProvider<KbLandHousingBenchmarkScheduler> kbLandScheduler;
    private final ObjectProvider<KbLandPriceIndexScheduler> kbLandPriceIndexScheduler;

    private interface InterruptibleAction {
        void run() throws InterruptedException;
    }

    // 5개 트리거 엔드포인트 공통 골격 — 백그라운드 가상 스레드 실행 + 인터럽트/예외 처리
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

    // 개장 스케쥴러 수동 트리거 — 개장 대기 없이 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "개장 스케쥴러 수동 트리거", description = "개장 대기 없이 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        TradingOpenScheduler scheduler = openScheduler.getIfAvailable();
        if (scheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        triggerAsync("개장 스케쥴러", scheduler::runNow);
    }

    // 마감 스케쥴러 수동 트리거 — 주문 대기 없이 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "마감 스케쥴러 수동 트리거", description = "주문 대기 없이 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        TradingCloseScheduler scheduler = closeScheduler.getIfAvailable();
        if (scheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        triggerAsync("마감 스케쥴러", scheduler::runNow);
    }

    // KB Land 주택 벤치마크 스케쥴러 수동 트리거 — 수동으로 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "KB Land 주택 벤치마크 스케쥴러 수동 트리거", description = "운영 이슈 발생 시 다음 크론까지 기다리지 않고 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-housing-benchmark")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandHousingBenchmark() {
        KbLandHousingBenchmarkScheduler scheduler = kbLandScheduler.getIfAvailable();
        if (scheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        triggerAsync("KB Land 주택 벤치마크 스케쥴러", scheduler::runNow);
    }

    // KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거 — 수동으로 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거", description = "운영 이슈 발생 시 다음 크론까지 기다리지 않고 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-price-index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandPriceIndex() {
        KbLandPriceIndexScheduler scheduler = kbLandPriceIndexScheduler.getIfAvailable();
        if (scheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        triggerAsync("KB Land 주간 아파트 매매가격지수 스케쥴러", scheduler::runNow);
    }

    // KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거 — 과거 값 보정을 다음 달 1일까지 기다리지 않고 즉시 반영
    @Operation(summary = "KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거", description = "20년 전체를 다시 받아 KB Land 과거 값 보정을 즉시 반영하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-price-index/full-refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandPriceIndexFullRefresh() {
        KbLandPriceIndexScheduler scheduler = kbLandPriceIndexScheduler.getIfAvailable();
        if (scheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        triggerAsync("KB Land 주간 아파트 매매가격지수 월간 풀 리프레시", scheduler::runFullRefreshNow);
    }
}

package com.kista.adapter.in.web;

import com.kista.adapter.in.schedule.KbLandHousingBenchmarkScheduler;
import com.kista.adapter.in.schedule.KbLandPriceIndexScheduler;
import com.kista.adapter.in.schedule.TradingCloseScheduler;
import com.kista.adapter.in.schedule.TradingOpenScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/admin/scheduler")
@Tag(name = "Admin", description = "관리자 API")
public class AdminSchedulerController {

    // @ConditionalOnProperty로 빈이 없을 수 있으므로 required=false
    @Autowired(required = false)
    private TradingOpenScheduler openScheduler;

    @Autowired(required = false)
    private TradingCloseScheduler closeScheduler;

    @Autowired(required = false)
    private KbLandHousingBenchmarkScheduler kbLandScheduler;

    @Autowired(required = false)
    private KbLandPriceIndexScheduler kbLandPriceIndexScheduler;

    // 개장 스케쥴러 수동 트리거 — 개장 대기 없이 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "개장 스케쥴러 수동 트리거", description = "개장 대기 없이 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerOpen() {
        if (openScheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        Thread.ofVirtual().start(() -> {
            try {
                openScheduler.runNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("개장 스케쥴러 수동 트리거 인터럽트");
            } catch (Exception e) {
                log.error("개장 스케쥴러 수동 트리거 오류: {}", e.getMessage(), e);
            }
        });
    }

    // 마감 스케쥴러 수동 트리거 — 주문 대기 없이 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "마감 스케쥴러 수동 트리거", description = "주문 대기 없이 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerClose() {
        if (closeScheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        Thread.ofVirtual().start(() -> {
            try {
                closeScheduler.runNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("마감 스케쥴러 수동 트리거 인터럽트");
            } catch (Exception e) {
                log.error("마감 스케쥴러 수동 트리거 오류: {}", e.getMessage(), e);
            }
        });
    }

    // KB Land 주택 벤치마크 스케쥴러 수동 트리거 — 수동으로 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "KB Land 주택 벤치마크 스케쥴러 수동 트리거", description = "운영 이슈 발생 시 다음 크론까지 기다리지 않고 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-housing-benchmark")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandHousingBenchmark() {
        if (kbLandScheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        Thread.ofVirtual().start(() -> {
            try {
                kbLandScheduler.runNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("KB Land 주택 벤치마크 스케쥴러 수동 트리거 인터럽트");
            } catch (Exception e) {
                log.error("KB Land 주택 벤치마크 스케쥴러 수동 트리거 오류: {}", e.getMessage(), e);
            }
        });
    }

    // KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거 — 수동으로 즉시 실행, 202 반환 후 백그라운드 실행
    @Operation(summary = "KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거", description = "운영 이슈 발생 시 다음 크론까지 기다리지 않고 즉시 실행하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-price-index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandPriceIndex() {
        if (kbLandPriceIndexScheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        Thread.ofVirtual().start(() -> {
            try {
                kbLandPriceIndexScheduler.runNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거 인터럽트");
            } catch (Exception e) {
                log.error("KB Land 주간 아파트 매매가격지수 스케쥴러 수동 트리거 오류: {}", e.getMessage(), e);
            }
        });
    }

    // KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거 — 과거 값 보정을 다음 달 1일까지 기다리지 않고 즉시 반영
    @Operation(summary = "KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거", description = "20년 전체를 다시 받아 KB Land 과거 값 보정을 즉시 반영하며, 202 반환 후 백그라운드에서 처리합니다.")
    @PostMapping("/kbland-price-index/full-refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerKbLandPriceIndexFullRefresh() {
        if (kbLandPriceIndexScheduler == null) throw new IllegalStateException("스케쥴러가 비활성화 상태입니다");
        Thread.ofVirtual().start(() -> {
            try {
                kbLandPriceIndexScheduler.runFullRefreshNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거 인터럽트");
            } catch (Exception e) {
                log.error("KB Land 주간 아파트 매매가격지수 월간 풀 리프레시 수동 트리거 오류: {}", e.getMessage(), e);
            }
        });
    }
}

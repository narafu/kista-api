package com.kista.api.benchmark.application.service;

import com.kista.api.benchmark.domain.model.EtfBenchmarkSymbol;
import com.kista.api.benchmark.domain.model.IndexPrice;
import com.kista.api.benchmark.domain.model.SplitAdjustment;
import com.kista.api.benchmark.application.port.output.IndexPriceFeedPort;
import com.kista.api.benchmark.application.port.output.IndexPricePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketIndexPriceSyncService {

    private static final LocalDate EARLIEST_INDEX_DATE = LocalDate.of(2000, 1, 1);
    private static final int OVERLAP_DAYS = 10;                                      // 저장된 마지막 거래일 앞으로 다시 받는 달력일 — 분할 감지·정정값 반영 창
    private static final BigDecimal SPLIT_UPPER = new BigDecimal("1.25");             // 새/저장 종가 비율이 이 위나
    private static final BigDecimal SPLIT_LOWER = new BigDecimal("0.8");              // 이 아래면 분할로 본다(시세 오차는 수 % 이내)
    private static final BigDecimal SNAP_TOLERANCE = new BigDecimal("0.005");         // 분할 비율을 n:1·1:n으로 맞출 때 허용 상대 오차
    private static final int MAX_SPLIT_FACTOR = 100;                                  // 분할 배수 n 상한
    private static final int MIN_SPLIT_ROWS = 2;                                      // 분할비가 나온 날이 이보다 적으면 이상값으로 보고 건너뛴다

    private final IndexPricePort indexPricePort;
    private final IndexPriceFeedPort indexPriceFeedPort;
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    // ETF 벤치마크 지수 종가를 조회해 저장
    public void syncAndSave() {
        List<String> failed = new ArrayList<>();
        for (EtfBenchmarkSymbol symbol : EtfBenchmarkSymbol.values()) {
            try {
                syncOne(symbol.name());
            } catch (Exception e) {
                log.error("{} 지수 종가 동기화 실패: {}", symbol, e.getMessage(), e);
                failed.add(symbol.name());
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("동기화에 실패한 심볼은 %s입니다.".formatted(failed));
        }
    }

    private void syncOne(String symbol) {
        Optional<LocalDate> maxDate = indexPricePort.findMaxTradeDate(symbol);
        // 마지막 거래일 앞 OVERLAP_DAYS부터 다시 받아 겹치는 날로 분할을 감지하고 정정값을 덮어쓴다
        LocalDate from = maxDate.map(d -> d.minusDays(OVERLAP_DAYS)).orElse(EARLIEST_INDEX_DATE);
        LocalDate to = LocalDate.now(clock);
        if (from.isAfter(to)) return;
        List<IndexPrice> prices = indexPriceFeedPort.fetchDailyCloses(symbol, from, to);
        if (prices.isEmpty()) {
            log.info("{} 지수 종가 동기화 완료: 0건 ({} ~ {})", symbol, from, to);
            return;
        }
        // 분할 조정을 덮어쓰기보다 먼저 한다 — 덮어쓰기가 실패해도 다음 동기화의 겹친 날 비율이 1이라 두 번 곱하지 않는다
        maxDate.flatMap(max -> detectSplit(prices, indexPricePort.findBySymbolAndRange(symbol, from, max)))
                .ifPresent(adjustment -> {
                    int updated = indexPricePort.applySplit(symbol, adjustment);
                    log.warn("{} 분할 감지 — {} 이하 종가 {}건에 {}/{}를 곱했습니다", symbol, adjustment.through(),
                            updated, adjustment.numerator(), adjustment.denominator());
                });
        indexPricePort.saveAll(prices);
        log.info("{} 지수 종가 동기화 완료: {}건 ({} ~ {})", symbol, prices.size(), from, to);
    }

    // 겹친 날의 새/저장 종가 비율이 [분할비, …, 분할비, 1, …, 1] 꼴이면 마지막 분할비 날까지를 조정 대상으로 본다
    static Optional<SplitAdjustment> detectSplit(List<IndexPrice> fetched, List<IndexPrice> stored) {
        Map<LocalDate, BigDecimal> fresh = fetched.stream()
                .collect(Collectors.toMap(IndexPrice::tradeDate, IndexPrice::close, (left, right) -> right));
        Fraction split = null;          // 찾은 분할비
        LocalDate lastSplitDate = null; // 분할비가 나온 마지막 날
        int splitRows = 0;             // 분할비가 나온 날 수
        boolean unitSeen = false;      // 분할비 1(이미 새 기준)인 날을 지났는지
        for (IndexPrice row : stored.stream().sorted(Comparator.comparing(IndexPrice::tradeDate)).toList()) {
            BigDecimal close = fresh.get(row.tradeDate());
            if (close == null || row.close().signum() <= 0) continue;
            BigDecimal ratio = close.divide(row.close(), MathContext.DECIMAL64);
            if (ratio.compareTo(SPLIT_LOWER) > 0 && ratio.compareTo(SPLIT_UPPER) < 0) {
                unitSeen = true; // 분할 아님 — 작은 차이는 덮어쓰기가 정정한다
                continue;
            }
            Fraction snapped = snap(ratio);
            // 분할비가 정수 분수로 안 맞거나, 날마다 다르거나, 새 기준(1) 뒤에 다시 나오면 판단하지 않는다
            if (snapped == null || unitSeen || (split != null && !split.equals(snapped))) {
                log.warn("{} 겹친 날 종가 비율이 분할 꼴이 아니라 분할 조정을 건너뜁니다: {} {}", row.symbol(), row.tradeDate(), ratio);
                return Optional.empty();
            }
            split = snapped;
            lastSplitDate = row.tradeDate();
            splitRows++;
        }
        if (split == null) return Optional.empty();
        // 분할 직후 첫 동기화는 겹친 창의 분할 전 행이 모두 분할비라, 한 날만 튀면 분할이 아니라 이상값이다
        if (splitRows < MIN_SPLIT_ROWS) {
            log.warn("{} 분할비가 {}일에만 나와 분할 조정을 건너뜁니다: {}", stored.getFirst().symbol(), splitRows, lastSplitDate);
            return Optional.empty();
        }
        return Optional.of(new SplitAdjustment(lastSplitDate, split.numerator(), split.denominator()));
    }

    private record Fraction(int numerator, int denominator) {}

    // 비율을 n:1 또는 1:n(n은 2~MAX_SPLIT_FACTOR)에 상대 오차 SNAP_TOLERANCE 안으로 맞춘다 — 안 맞으면 null
    private static @Nullable Fraction snap(BigDecimal ratio) {
        boolean reverse = ratio.compareTo(BigDecimal.ONE) > 0; // 역분할(가격 상승)이면 n:1
        BigDecimal factor = reverse ? ratio : BigDecimal.ONE.divide(ratio, MathContext.DECIMAL64);
        if (factor.compareTo(BigDecimal.valueOf(MAX_SPLIT_FACTOR + 1)) > 0) return null;
        int n = factor.setScale(0, RoundingMode.HALF_UP).intValueExact();
        if (n < 2 || BigDecimal.valueOf(n).subtract(factor).abs().compareTo(factor.multiply(SNAP_TOLERANCE)) > 0) return null;
        return reverse ? new Fraction(n, 1) : new Fraction(1, n);
    }
}

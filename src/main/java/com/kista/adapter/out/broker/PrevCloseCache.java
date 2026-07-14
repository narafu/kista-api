package com.kista.adapter.out.broker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

// 전일종가(prevClose) 캐시 — 종목+거래일(KST) 단위로 하루 내 재조회 방지. KIS dailyprice / Toss candle API 공용.
// DoubleCheckedTokenCache와 동일하게 각 어댑터가 필드로 자체 인스턴스 소유(Spring bean 아님).
// 만료 로직 없음 — 날짜가 바뀌면 키가 자연히 달라지고, 종목 수가 적어(4개) 메모리 증가는 무시 가능.
public final class PrevCloseCache {

    private final ConcurrentMap<CacheKey, Optional<BigDecimal>> cache = new ConcurrentHashMap<>();

    // bucket: 같은 날짜라도 캐시를 분리해야 하는 상태 구분자 (기본 "") — Toss는 정규장 진행 중 여부로 분리해
    // 정규장 종료로 확정 종가가 바뀌는 순간 캐시를 재사용하지 않도록 함
    private record CacheKey(String symbol, LocalDate date, String bucket) {}

    // fetcher: 캐시 miss 시 실제 조회를 수행하는 함수 (실패 시 Optional.empty() 반환 관례)
    public Optional<BigDecimal> getOrFetch(String symbol, LocalDate date, Supplier<Optional<BigDecimal>> fetcher) {
        return getOrFetch(symbol, date, "", fetcher);
    }

    public Optional<BigDecimal> getOrFetch(String symbol, LocalDate date, String bucket,
                                           Supplier<Optional<BigDecimal>> fetcher) {
        return cache.computeIfAbsent(new CacheKey(symbol, date, bucket), k -> fetcher.get());
    }
}

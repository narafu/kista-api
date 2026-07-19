package com.kista.domain.model.stats;

// 벤치마크 비교에 쓸 수 있는 ETF 심볼 화이트리스트 — 스케쥴러 동기화 대상과
// StatsController symbol 파라미터 검증 양쪽의 단일 소스.
// Strategy.Ticker(매매 정책 결부, 폐쇄형 enum)와는 완전히 별개이며 서로 참조하지 않는다.
public enum EtfBenchmarkSymbol {
    SPY("SPDR S&P 500 ETF Trust"),
    QQQ("Invesco QQQ Trust"),
    QLD("ProShares Ultra QQQ (2x 레버리지)"),
    IBIT("iShares Bitcoin Trust");

    private final String description;

    EtfBenchmarkSymbol(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}

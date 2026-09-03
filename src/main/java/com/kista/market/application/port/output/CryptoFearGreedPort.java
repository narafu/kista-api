package com.kista.market.application.port.output;

import com.kista.market.domain.model.FearGreedRating;

// alternative.me Crypto Fear & Greed Index API
public interface CryptoFearGreedPort {
    record CryptoFearGreedData(int value, FearGreedRating rating) {}
    CryptoFearGreedData fetch();
}

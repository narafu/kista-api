package com.kista.domain.port.out;

import com.kista.domain.model.market.FearGreedRating;

// alternative.me Crypto Fear & Greed Index API
public interface CryptoFearGreedPort {
    record CryptoFearGreedData(int value, FearGreedRating rating) {}
    CryptoFearGreedData fetch();
}

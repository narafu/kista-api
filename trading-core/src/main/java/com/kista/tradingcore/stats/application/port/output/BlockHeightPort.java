package com.kista.tradingcore.stats.application.port.output;

// 비트코인 블록 높이 조회 포트
public interface BlockHeightPort {

    // 현재 BTC 메인넷 최신 블록 높이
    long currentBtcHeight();
}

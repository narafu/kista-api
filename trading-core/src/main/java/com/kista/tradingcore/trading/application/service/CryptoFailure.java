package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;

// 코인 거래소 호출 실패 분류 — KEY_EXPIRED(키 인증 실패: 만료·폐기·IP·권한, 확정 거절), REJECTED(그 외 확정 거절), TRANSIENT(결과 불명·일시 장애)
enum CryptoFailure {
    KEY_EXPIRED, REJECTED, TRANSIENT;

    static CryptoFailure classify(RuntimeException e) {
        // 정확히 상위 타입만 키 만료 — IP 미허용·권한 부족 하위 타입은 키 교체가 아닌 설정 문제라 REJECTED
        if (e.getClass() == BrokerCredentialException.class) return KEY_EXPIRED;
        // 호출 한도는 isRejection이 거절로 보지만 코인에선 다음 틱에 재시도할 일시 장애
        if (e instanceof BrokerRateLimitException) return TRANSIENT;
        return BrokerApiException.isRejection(e) ? REJECTED : TRANSIENT;
    }

    DcaSkipReason reason() {
        return this == KEY_EXPIRED ? DcaSkipReason.API_KEY_EXPIRED : DcaSkipReason.EXCHANGE_API_ERROR;
    }
}

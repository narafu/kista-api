package com.kista.domain.model;

import java.math.BigDecimal;

public record ReservationOrder(
        String receiptDate,            // 예약주문접수일자 (rsvn_ord_rcit_dt)
        String receiptTime,            // 주문접수시각 (ord_rcit_tmd)
        String reservationOrderId,     // 해외예약주문번호 (ovrs_rsvn_odno)
        Order.OrderDirection direction, // 매도/매수 방향 (sll_buy_dvsn_cd: 01=매도, 02=매수)
        String statusCode,             // 해외예약주문상태코드 (ovrs_rsvn_ord_stat_cd)
        String statusName,             // 해외예약주문상태코드명 (ovrs_rsvn_ord_stat_cd_name)
        String symbol,                 // 종목코드 (pdno)
        String symbolName,             // 상품명 (prdt_name)
        String exchangeCode,           // 해외거래소코드 (ovrs_excg_cd)
        int orderedQty,                // FT주문수량 (ft_ord_qty)
        BigDecimal orderedPrice,       // FT주문단가 (ft_ord_unpr3)
        int filledQty,                 // FT체결수량 (ft_ccld_qty)
        boolean cancelled              // 취소여부 (cncl_yn: Y=취소)
) {}

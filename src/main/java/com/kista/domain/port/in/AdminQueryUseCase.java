package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminAnomalies;
import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.model.admin.AuditLog;
import com.kista.domain.model.order.Order;

import java.util.List;

// 관리자 조회 전용 UseCase — 통계, 계좌·거래·감사·이상 조회 통합
public interface AdminQueryUseCase {
    AdminStats getStats();
    List<Account> listAccounts();
    List<Order> listTrades();
    List<AuditLog> listAuditLogs();
    AdminAnomalies getAnomalies();
}

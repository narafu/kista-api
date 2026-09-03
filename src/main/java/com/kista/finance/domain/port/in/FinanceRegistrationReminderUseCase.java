package com.kista.finance.domain.port.in;

import java.time.YearMonth;

public interface FinanceRegistrationReminderUseCase {
    // 대상 월(month) 가계부 등록이 없는 ACTIVE 사용자에게 알림 발송
    void notifyUsersWithoutThisMonthRegistration(YearMonth month);
}

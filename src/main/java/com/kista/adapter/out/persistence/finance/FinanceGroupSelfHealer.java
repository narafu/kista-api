package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceGroup;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

// FinanceGroupPersistenceAdapter.resolveGroupId()의 개인 그룹 자가 치유 전용 협력자로 분리했다.
// resolveGroupId는 대부분 @Transactional(readOnly = true) 서비스 메서드에서 호출되는데(계좌·거래·예산
// 목록 조회 등), 같은 클래스 안에서 createPersonalGroup을 직접 호출하면 Spring AOP self-invocation
// 한계로 REQUIRES_NEW가 적용되지 않고 바깥 트랜잭션의 FlushMode.MANUAL을 그대로 물려받아 INSERT가
// 조용히 flush되지 않는다(실측: DB 미반영, 로그도 없음). 별도 빈으로 분리해 프록시를 거치게 하면
// REQUIRES_NEW가 실제로 적용되어 호출부의 readOnly 여부와 무관하게 항상 커밋된다.
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FinanceGroupSelfHealer {

    private final FinanceGroupJpaRepository groupJpaRepository;
    private final FinanceGroupMemberJpaRepository memberJpaRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    UUID createPersonalGroup(UUID userId) {
        FinanceGroupEntity group = new FinanceGroupEntity();
        group.setOwnerUserId(userId);
        group.setName("개인");
        group.setPersonal(true);
        FinanceGroupEntity saved = groupJpaRepository.save(group);

        FinanceGroupMemberEntity member = new FinanceGroupMemberEntity();
        member.setGroupId(saved.getId());
        member.setUserId(userId);
        member.setRole(FinanceGroup.MemberRole.OWNER);
        member.setJoinedAt(Instant.now());
        memberJpaRepository.save(member);

        return saved.getId();
    }
}

package com.kista.finance.application.service;

import com.kista.finance.domain.model.FinanceAccount;
import com.kista.finance.domain.model.FinanceAccountCommand;
import com.kista.finance.domain.port.out.AssetSnapshotPort;
import com.kista.finance.domain.port.out.FinanceAccountPort;
import com.kista.finance.domain.port.out.FinanceGroupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceAccountService 단위 테스트")
class FinanceAccountServiceTest {

    @Mock FinanceAccountPort accountPort;
    @Mock FinanceGroupPort financeGroupPort;
    @Mock AssetSnapshotPort assetSnapshotPort;
    @InjectMocks FinanceAccountService accountService;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private FinanceAccount personalAccount() {
        return new FinanceAccount(accountId, null, userId, FinanceAccount.Type.SECURITIES,
                "토스증권", "1234", null, null);
    }

    private FinanceAccountCommand command() {
        return new FinanceAccountCommand(FinanceAccount.Type.BANK, "카카오뱅크", "5678", "메모");
    }

    @Test
    @DisplayName("list는 findCurrentGroupId로 얻은 currentGroupId로 조회")
    void list_queriesWithCurrentGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(accountPort.findMyScope(userId, groupId)).thenReturn(List.of(personalAccount()));

        List<FinanceAccount> result = accountService.list(userId, null);

        assertThat(result).hasSize(1);
        verify(accountPort).findMyScope(userId, groupId);
    }

    @Test
    @DisplayName("create는 requestedGroupId를 무시하고 개인 소유(groupId=null)로 저장")
    void create_alwaysSavesAsPersonalOwnership() {
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceAccount result = accountService.create(userId, groupId, command());

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.name()).isEqualTo("카카오뱅크");
        verifyNoInteractions(financeGroupPort); // create는 그룹 조회 없이 항상 개인 소유로 저장
    }

    @Test
    @DisplayName("update는 load-then-verify 패턴 — 기존 계좌의 groupId로 접근 검증")
    void update_loadsThenVerifiesAccess() {
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(personalAccount());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceAccount result = accountService.update(accountId, userId, command());

        assertThat(result.name()).isEqualTo("카카오뱅크");
        assertThat(result.id()).isEqualTo(accountId);
        assertThat(result.userId()).isEqualTo(userId); // 기존 소유자 유지
        verify(accountPort).findActiveByIdOrThrow(accountId);
    }

    @Test
    @DisplayName("update 시 접근 불가한 계좌면 SecurityException")
    void update_notAccessible_throwsSecurityException() {
        FinanceAccount othersAccount = new FinanceAccount(accountId, null, UUID.randomUUID(),
                FinanceAccount.Type.SECURITIES, "토스증권", "1234", null, null);
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(othersAccount);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.update(accountId, userId, command()))
                .isInstanceOf(SecurityException.class);

        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("delete는 load-then-verify 후 softDelete 호출")
    void delete_loadsThenVerifiesThenSoftDeletes() {
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(personalAccount());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(assetSnapshotPort.existsByAccountId(accountId)).thenReturn(false);

        accountService.delete(accountId, userId);

        verify(accountPort).softDelete(accountId);
    }

    @Test
    @DisplayName("delete는 매핑된 자산 기록이 있으면 LinkedAssetSnapshotsException으로 거부")
    void delete_withLinkedAssetSnapshots_throwsLinkedAssetSnapshotsException() {
        when(accountPort.findByIdOrThrow(accountId)).thenReturn(personalAccount());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(assetSnapshotPort.existsByAccountId(accountId)).thenReturn(true);

        assertThatThrownBy(() -> accountService.delete(accountId, userId))
                .isInstanceOf(FinanceAccount.LinkedAssetSnapshotsException.class);

        verify(accountPort, never()).softDelete(accountId);
    }

    @Test
    @DisplayName("create 중 계좌명 중복 시 DuplicateNameException이 그대로 전파됨")
    void create_duplicateName_propagatesUntouched() {
        when(accountPort.save(any())).thenThrow(new FinanceAccount.DuplicateNameException("카카오뱅크"));

        assertThatThrownBy(() -> accountService.create(userId, null, command()))
                .isInstanceOf(FinanceAccount.DuplicateNameException.class)
                .hasMessageContaining("카카오뱅크");
    }

    // 삭제된 계좌는 findActiveByIdOrThrow가 못 찾아야 함 — findByIdOrThrow(삭제 계좌도 조회됨)를 쓰면
    // save() merge 시 deletedAt이 조용히 풀려 되살아난다(코드리뷰에서 발견, 2026-08-19).
    @Test
    @DisplayName("update는 삭제된 계좌를 되살리지 못하고 404로 거부됨")
    void update_deletedAccount_throwsNotFound() {
        when(accountPort.findActiveByIdOrThrow(accountId))
                .thenThrow(new java.util.NoSuchElementException("계좌를 찾을 수 없습니다: " + accountId));

        assertThatThrownBy(() -> accountService.update(accountId, userId, command()))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("create는 계좌번호가 전역 중복이면 DuplicateAccountNoException")
    void create_duplicateAccountNo_throwsDuplicateAccountNoException() {
        when(accountPort.existsByAccountNo("5678", null)).thenReturn(true);

        assertThatThrownBy(() -> accountService.create(userId, null, command()))
                .isInstanceOf(FinanceAccount.DuplicateAccountNoException.class)
                .hasMessageContaining("5678");

        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("update는 계좌번호가 자신을 제외한 다른 계좌와 중복이면 DuplicateAccountNoException")
    void update_duplicateAccountNo_throwsDuplicateAccountNoException() {
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(personalAccount());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(accountPort.existsByAccountNo("5678", accountId)).thenReturn(true);

        assertThatThrownBy(() -> accountService.update(accountId, userId, command()))
                .isInstanceOf(FinanceAccount.DuplicateAccountNoException.class);

        verify(accountPort, never()).save(any());
    }

    // ----- shareToGroup -----

    @Test
    @DisplayName("shareToGroup은 본인 소유 개인 계좌를 현재 그룹으로 전환")
    void shareToGroup_ownedPersonalAccount_movesToCurrentGroup() {
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(personalAccount());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceAccount result = accountService.shareToGroup(accountId, userId);

        assertThat(result.groupId()).isEqualTo(groupId);
    }

    @Test
    @DisplayName("shareToGroup은 본인 소유가 아니면 SecurityException")
    void shareToGroup_notOwner_throwsSecurityException() {
        FinanceAccount othersAccount = new FinanceAccount(accountId, null, UUID.randomUUID(),
                FinanceAccount.Type.SECURITIES, "토스증권", "1234", null, null);
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(othersAccount);

        assertThatThrownBy(() -> accountService.shareToGroup(accountId, userId))
                .isInstanceOf(SecurityException.class);

        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("shareToGroup은 무그룹 유저면 IllegalStateException")
    void shareToGroup_noCurrentGroup_throwsIllegalState() {
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(personalAccount());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.shareToGroup(accountId, userId))
                .isInstanceOf(IllegalStateException.class);

        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("shareToGroup은 이미 다른 그룹에 공유돼 있으면 IllegalStateException")
    void shareToGroup_alreadySharedToAnotherGroup_throwsIllegalState() {
        FinanceAccount alreadyShared = new FinanceAccount(accountId, UUID.randomUUID(), userId,
                FinanceAccount.Type.SECURITIES, "토스증권", "1234", null, null);
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(alreadyShared);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        assertThatThrownBy(() -> accountService.shareToGroup(accountId, userId))
                .isInstanceOf(IllegalStateException.class);

        verify(accountPort, never()).save(any());
    }

    // ----- unshare -----

    @Test
    @DisplayName("unshare는 그룹 공유 계좌를 개인 소유로 되돌리고 소유자는 유지")
    void unshare_groupSharedAccount_movesToPersonalKeepingOwner() {
        UUID ownerId = UUID.randomUUID();
        FinanceAccount sharedAccount = new FinanceAccount(accountId, groupId, ownerId,
                FinanceAccount.Type.SECURITIES, "토스증권", "1234", null, null);
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(sharedAccount);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceAccount result = accountService.unshare(accountId, userId);

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("unshare는 같은 그룹 소속이면 소유자가 아니어도 허용")
    void unshare_nonOwnerSameGroupMember_allowed() {
        UUID ownerId = UUID.randomUUID();
        FinanceAccount sharedAccount = new FinanceAccount(accountId, groupId, ownerId,
                FinanceAccount.Type.SECURITIES, "토스증권", "1234", null, null);
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(sharedAccount);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(accountPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceAccount result = accountService.unshare(accountId, userId);

        assertThat(result.groupId()).isNull();
    }

    @Test
    @DisplayName("unshare는 소유자도 아니고 같은 그룹도 아니면 SecurityException")
    void unshare_notOwnerAndNotSameGroup_throwsSecurityException() {
        FinanceAccount sharedAccount = new FinanceAccount(accountId, UUID.randomUUID(), UUID.randomUUID(),
                FinanceAccount.Type.SECURITIES, "토스증권", "1234", null, null);
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(sharedAccount);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        assertThatThrownBy(() -> accountService.unshare(accountId, userId))
                .isInstanceOf(SecurityException.class);

        verify(accountPort, never()).save(any());
    }

    @Test
    @DisplayName("unshare는 이미 개인 소유면 그대로 반환(멱등)")
    void unshare_alreadyPersonal_isIdempotent() {
        when(accountPort.findActiveByIdOrThrow(accountId)).thenReturn(personalAccount());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        FinanceAccount result = accountService.unshare(accountId, userId);

        assertThat(result.groupId()).isNull();
        verify(accountPort, never()).save(any());
    }
}

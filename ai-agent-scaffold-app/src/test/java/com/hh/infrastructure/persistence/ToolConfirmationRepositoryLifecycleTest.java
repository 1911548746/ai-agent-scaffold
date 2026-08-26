package com.hh.infrastructure.persistence;

import com.hh.domain.agent.model.enums.ToolConfirmationStatus;
import com.hh.infrastructure.agent.adapter.repository.ToolConfirmationRepository;
import com.hh.infrastructure.agent.dao.ToolConfirmationDao;
import com.hh.infrastructure.agent.dao.po.ToolConfirmationPO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HITL-03：通用确认单以有效期和版本字段处理过期与并发决策。 */
class ToolConfirmationRepositoryLifecycleTest {

    @Test
    void expiredPendingConfirmationIsAdvancedByVersionedUpdateWhenRead() {
        ToolConfirmationDao dao = mock(ToolConfirmationDao.class);
        ToolConfirmationPO pending = confirmation("pending", 4L, LocalDateTime.now().minusMinutes(1));
        ToolConfirmationPO expired = confirmation("expired", 5L, pending.getExpiresAt());
        when(dao.selectByIdentity("confirmation-1", "user-1")).thenReturn(pending, expired);
        when(dao.expirePending("confirmation-1", "user-1", "expired", 4L)).thenReturn(1);

        var result = new ToolConfirmationRepository(dao).find("confirmation-1", "user-1");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().status()).isEqualTo(ToolConfirmationStatus.EXPIRED);
        assertThat(result.orElseThrow().version()).isEqualTo(5L);
        verify(dao).expirePending("confirmation-1", "user-1", "expired", 4L);
    }

    @Test
    void claimUsesExpectedVersionAsOptimisticLock() {
        ToolConfirmationDao dao = mock(ToolConfirmationDao.class);
        when(dao.claimPending("confirmation-1", "user-1", "app-a", "processing", 7L))
                .thenReturn(0);

        boolean claimed = new ToolConfirmationRepository(dao)
                .claimPending("confirmation-1", "user-1", "app-a", 7L);

        // 版本不匹配时数据库影响行数为 0，调用方据此拒绝重复决策。
        assertThat(claimed).isFalse();
    }

    private ToolConfirmationPO confirmation(String status, long version, LocalDateTime expiresAt) {
        return ToolConfirmationPO.builder()
                .id(11L)
                .confirmationId("confirmation-1")
                .conversationId(1L)
                .appName("app-a")
                .userId("user-1")
                .sessionId("session-1")
                .invocationId("invocation-1")
                .adkCallId("adk-call-1")
                .toolName("request_refund")
                .requestMessage("请确认")
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .expiresAt(expiresAt)
                .version(version)
                .build();
    }
}

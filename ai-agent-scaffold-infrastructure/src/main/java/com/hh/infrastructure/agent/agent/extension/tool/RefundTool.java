package com.hh.infrastructure.agent.agent.extension.tool;

import com.google.adk.events.ToolConfirmation;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component("refundTool")
public class RefundTool {

    private RefundTool() {
    }

    @Schema(name = "request_refund", description = "为订单发起退款。这是高风险操作，必须先由人工确认。")
    public Map<String, Object> requestRefund(
            @Schema(name = "orderId", description = "订单号") String orderId,
            @Schema(name = "amountCents", description = "退款金额，单位为分") int amountCents,
            @Schema(name = "reason", description = "退款原因") String reason,
            ToolContext toolContext) {

        ToolConfirmation confirmation = toolContext.toolConfirmation().orElse(null);
        if (confirmation == null) {
            Map<String, Object> request = Map.of(
                    "orderId", orderId,
                    "amountCents", amountCents,
                    "reason", reason);
            toolContext.requestConfirmation(
                    "即将为订单 " + orderId + " 退款 " + amountCents + " 分，是否批准？",
                    request);
            return Map.of("status", "pending_human_confirmation");
        }

        if (!confirmation.confirmed()) {
            return Map.of(
                    "status", "rejected_by_human",
                    "orderId", orderId,
                    "message", "人工审批拒绝，未执行退款。");
        }

        // This is a deterministic stand-in for a real payment-provider request.
        return Map.of(
                "status", "refund_executed",
                "orderId", orderId,
                "refundId", "rfnd-" + orderId.toLowerCase(),
                "amountCents", amountCents,
                "message", "人工审批已通过，退款已执行。");
    }
}

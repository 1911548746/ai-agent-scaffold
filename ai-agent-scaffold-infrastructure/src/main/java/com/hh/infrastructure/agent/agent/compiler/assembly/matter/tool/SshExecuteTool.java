package com.hh.infrastructure.agent.agent.compiler.assembly.matter.tool;

import com.google.adk.events.ToolConfirmation;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * SSH 命令执行工具的本地模拟实现。
 *
 * <p>本类不会建立 SSH 连接或执行系统命令，只返回模拟结果。危险命令必须先请求 HITL 确认，
 * 恢复调用时再根据确认结果决定是否返回“已模拟执行”。</p>
 */
@Component("sshExecuteTool")
public class SshExecuteTool {

    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "\\b(rm\\s+-rf\\s+/|dd\\s+if=|mkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|chmod\\s+-R\\s+777\\s+/)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private SshExecuteTool() {
    }

    /**
     * 模拟在指定 SSH 主机执行命令。
     *
     * <p>普通命令直接返回模拟结果；高危命令首次调用时暂停任务并创建确认单，拒绝后不执行，
     * 批准后才返回模拟执行结果。</p>
     */
    @Schema(name = "execute_ssh_command", description = "模拟通过 SSH 在远程主机执行命令。高危命令必须先经人工确认。")
    public Map<String, Object> executeSshCommand(
            @Schema(name = "host", description = "目标 SSH 主机地址或主机名") String host,
            @Schema(name = "username", description = "用于连接目标主机的用户名") String username,
            @Schema(name = "command", description = "需要执行的 Shell 命令") String command,
            ToolContext toolContext) {

        ToolConfirmation confirmation = toolContext.toolConfirmation().orElse(null);
        if (isDangerous(command) && confirmation == null) {
            Map<String, Object> request = Map.of(
                    "host", host,
                    "username", username,
                    "command", command);
            toolContext.requestConfirmation(
                    "即将在 SSH 主机 " + host + " 上以用户 " + username
                            + " 执行高危命令：" + command + "。是否批准？",
                    request);
            return Map.of("status", "pending_human_confirmation");
        }

        if (confirmation != null && !confirmation.confirmed()) {
            return Map.of(
                    "status", "rejected_by_human",
                    "host", host,
                    "command", command,
                    "message", "人工审批拒绝，未执行 SSH 命令。");
        }

        // 仅模拟执行，避免示例 Agent 对本机或远程环境产生真实副作用。
        return Map.of(
                "status", "ssh_command_simulated",
                "host", host,
                "username", username,
                "command", command,
                "message", "SSH 命令已模拟执行。");
    }

    private boolean isDangerous(String command) {
        return command != null && DANGEROUS_PATTERN.matcher(command).find();
    }
}

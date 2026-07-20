package com.hh.domain.agent.model.entity;

import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatCommandEntity {

    @NotBlank(message = "agentId不能为空")
    @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "agentId长度不能超过128个字符")
    private String agentId;

    @NotBlank(message = "userId不能为空")
    @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "userId长度不能超过128个字符")
    private String userId;

    @NotBlank(message = "sessionId不能为空")
    @Size(max = RequestPayloadLimits.MAX_SESSION_ID_LENGTH, message = "sessionId长度不能超过128个字符")
    private String sessionId;

    @Valid
    private List<Content.Text> texts;

    private List<Content.File> files;

    @Valid
    @Size(max = RequestPayloadLimits.MAX_INLINE_DATA_COUNT, message = "inlineDatas数量不能超过4个")
    private List<Content.InlineData> inlineDatas;

    /**
     * 单个文件受字段约束限制还不够；多个合法小文件叠加后同样可能耗尽请求内存，
     * 因此额外限制本次命令的内联数据总量。
     */
    @AssertTrue(message = "inlineData总大小不能超过8MiB")
    public boolean isInlineDataTotalSizeValid() {
        if (inlineDatas == null) {
            return true;
        }

        long totalBytes = inlineDatas.stream()
                .filter(inlineData -> inlineData != null && inlineData.getBytes() != null)
                .mapToLong(inlineData -> inlineData.getBytes().length)
                .sum();
        return totalBytes <= RequestPayloadLimits.MAX_INLINE_DATA_TOTAL_BYTES;
    }

    @Data
    public static class Content {

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Text {
            @NotBlank(message = "message不能为空")
            @Size(max = RequestPayloadLimits.MAX_TEXT_LENGTH, message = "message长度不能超过16000个字符")
            private String message;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class File {
            private String fileUri;
            private String mimeType;
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class InlineData {
            @NotNull(message = "inlineData字节不能为空")
            @Size(max = RequestPayloadLimits.MAX_INLINE_DATA_BYTES, message = "inlineData不能超过4MiB")
            private byte[] bytes;

            @NotBlank(message = "inlineData mimeType不能为空")
            @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "inlineData mimeType长度不能超过128个字符")
            private String mimeType;
        }
    }

    public ChatCommandEntity buildSessionCommand(String agentId, String userId) {
        return ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(userId)
                .sessionId(this.sessionId)
                .build();
    }

    public ChatCommandEntity buildChatCommand(String agentId, String userId, String message) {
        return ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(userId)
                .sessionId(this.sessionId)
                .texts(List.of(new Content.Text(message)))
                .build();
    }

}

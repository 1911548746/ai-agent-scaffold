package com.hh.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ContextCompressTypeEnum {

    SLIDING_WINDOW("slidingWindow"),
    FIXED_SIZE("fixedSize"),
    // 纯滑动窗口只保留最近若干完整 invocation，不调用模型生成摘要。
    PURE_SLIDING_WINDOW("pureSlidingWindow"),
    ;

    private String type;

    public static ContextCompressTypeEnum getByType(String type) {
        for (ContextCompressTypeEnum contextCompressTypeEnum : ContextCompressTypeEnum.values()) {
            if (contextCompressTypeEnum.type.equals(type)) {
                return contextCompressTypeEnum;
            }
        }
        // RunnerNode 对未知值按 fixedSize 装配；枚举解析必须保持同一回退语义。
        return ContextCompressTypeEnum.FIXED_SIZE;
    }

}

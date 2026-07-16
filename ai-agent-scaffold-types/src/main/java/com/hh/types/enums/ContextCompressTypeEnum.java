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
    ;

    private String type;

    public static ContextCompressTypeEnum getByType(String type) {
        for (ContextCompressTypeEnum contextCompressTypeEnum : ContextCompressTypeEnum.values()) {
            if (contextCompressTypeEnum.type.equals(type)) {
                return contextCompressTypeEnum;
            }
        }
        return ContextCompressTypeEnum.SLIDING_WINDOW;
    }

}

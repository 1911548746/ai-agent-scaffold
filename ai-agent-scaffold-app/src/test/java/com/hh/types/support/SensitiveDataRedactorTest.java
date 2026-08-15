package com.hh.types.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    @Test
    void formatsRootCauseFirstAndBoundsLargeStackTrace() {
        String secret = "do-not-log";
        IllegalStateException root = new IllegalStateException("apiKey=" + secret);
        StackTraceElement[] frames = new StackTraceElement[332];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement("com.example.Generated", "call" + i, "Generated.java", i);
        }
        root.setStackTrace(frames);
        RuntimeException wrapper = new RuntimeException("wrapper", root);

        String diagnostic = SensitiveDataRedactor.formatStackTrace(wrapper);

        assertThat(diagnostic.length()).isLessThanOrEqualTo(16 * 1024);
        assertThat(diagnostic).startsWith("Root cause: " + IllegalStateException.class.getName());
        assertThat(diagnostic).contains("apiKey=***");
        assertThat(diagnostic).doesNotContain(secret);
        assertThat(diagnostic).contains("省略 316 个栈帧");
        assertThat(diagnostic).contains("Caused by: " + RuntimeException.class.getName());
    }
}

package com.hh.trigger.http.agent.parser;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultimodalAttachmentParserTest {

    private final MultimodalAttachmentParser parser = new MultimodalAttachmentParser();

    @Test
    void parsesUtf8TextAsNormalizedPlainText() {
        ParsedAttachment attachment = parser.parse(List.of(file("notes.txt", "你好".getBytes(StandardCharsets.UTF_8)))).get(0);

        assertThat(attachment.mimeType()).isEqualTo("text/plain");
        assertThat(attachment.bytes()).isEqualTo("你好".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesImageBySignatureAndExtension() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        ParsedAttachment attachment = parser.parse(List.of(file("cat.png", png))).get(0);

        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(attachment.bytes()).isEqualTo(png);
    }

    @Test
    void rejectsUnsupportedOrMismatchedFiles() {
        assertThatThrownBy(() -> parser.parse(List.of(file("notes.md", "hello".getBytes(StandardCharsets.UTF_8)))))
                .hasFieldOrPropertyWithValue("info", "仅支持图片和txt文件");
        assertThatThrownBy(() -> parser.parse(List.of(file("cat.jpg", new byte[]{1, 2, 3}))))
                .hasFieldOrPropertyWithValue("info", "仅支持图片和txt文件");
    }

    @Test
    void rejectsInvalidUtf8Text() {
        assertThatThrownBy(() -> parser.parse(List.of(file("notes.txt", new byte[]{(byte) 0xC3, 0x28}))))
                .extracting("info")
                .asString()
                .contains("UTF-8");
    }

    @Test
    void rejectsTooManyAttachments() {
        List<MultipartFile> files = new ArrayList<>(List.of(
                file("1.txt", new byte[]{1}), file("2.txt", new byte[]{1}),
                file("3.txt", new byte[]{1}), file("4.txt", new byte[]{1}),
                file("5.txt", new byte[]{1})));

        assertThatThrownBy(() -> parser.parse(files))
                .extracting("info")
                .asString()
                .contains("附件数量不能超过");
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", bytes);
    }
}

package com.hh.trigger.http.agent.parser;

import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.validation.RequestPayloadLimits;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将 multipart 附件转换为 ADK 可消费的字节和 MIME 类型。
 *
 * <p>客户端声明的 MIME 只能作为提示，图片最终依据文件签名校验；文本只接受 UTF-8 的 .txt 文件。</p>
 */
@Component
public class MultimodalAttachmentParser {

    public List<ParsedAttachment> parse(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (files.size() > RequestPayloadLimits.MAX_INLINE_DATA_COUNT) {
            throw invalid("附件数量不能超过" + RequestPayloadLimits.MAX_INLINE_DATA_COUNT + "个");
        }

        List<ParsedAttachment> parsed = new ArrayList<>(files.size());
        long totalBytes = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw invalid("附件不能为空");
            }
            if (file.getSize() > RequestPayloadLimits.MAX_INLINE_DATA_BYTES) {
                throw invalid("单个附件不能超过4MiB");
            }
            totalBytes += file.getSize();
            if (totalBytes > RequestPayloadLimits.MAX_INLINE_DATA_TOTAL_BYTES) {
                throw invalid("附件总大小不能超过8MiB");
            }
            parsed.add(parseOne(file));
        }
        return List.copyOf(parsed);
    }

    private ParsedAttachment parseOne(MultipartFile file) {
        String fileName = safeFileName(file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw invalid("附件读取失败");
        }

        String extension = extension(fileName);
        // TODO 后续采用策略模式或工厂模式，根据文件扩展名确定解析器
        if ("txt".equals(extension)) {
            return new ParsedAttachment(fileName, normalizeUtf8(bytes), "text/plain");
        }
        String imageMimeType = detectImageMimeType(bytes);
        if (imageMimeType != null && extensionMatchesImage(extension, imageMimeType)) {
            return new ParsedAttachment(fileName, bytes, imageMimeType);
        }
        throw invalid("仅支持图片和txt文件");
    }

    private byte[] normalizeUtf8(byte[] bytes) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString().getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException exception) {
            throw invalid("txt文件必须使用UTF-8编码");
        }
    }

    private String detectImageMimeType(byte[] bytes) {
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(bytes, new byte[]{0x47, 0x49, 0x46, 0x38})) {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private boolean extensionMatchesImage(String extension, String mimeType) {
        return ("jpg".equals(extension) || "jpeg".equals(extension)) && "image/jpeg".equals(mimeType)
                || "png".equals(extension) && "image/png".equals(mimeType)
                || "gif".equals(extension) && "image/gif".equals(mimeType)
                || "webp".equals(extension) && "image/webp".equals(mimeType);
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private String safeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw invalid("附件文件名不能为空");
        }
        String fileName = originalFileName.replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        if (fileName.isBlank() || fileName.length() > RequestPayloadLimits.MAX_IDENTIFIER_LENGTH) {
            throw invalid("附件文件名不合法");
        }
        return fileName;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private AppException invalid(String message) {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
    }
}

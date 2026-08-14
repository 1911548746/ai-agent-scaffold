package com.hh.infrastructure.auth;

import com.hh.application.auth.port.PasswordHashPort;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/** 使用 JDK PBKDF2 生成带随机 salt 的密码哈希。 */
public class PasswordHashService implements PasswordHashPort {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT = "pbkdf2-sha256";
    private static final int ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String hash(String plainPassword) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(plainPassword, salt, ITERATIONS);
        return FORMAT + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    @Override
    public boolean matches(String plainPassword, String passwordHash) {
        if (plainPassword == null || passwordHash == null) {
            return false;
        }
        String[] segments = passwordHash.split("\\$", -1);
        if (segments.length != 4 || !FORMAT.equals(segments[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(segments[1]);
            if (iterations <= 0) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(segments[2]);
            byte[] expected = Base64.getDecoder().decode(segments[3]);
            byte[] actual = derive(plainPassword, salt, iterations);
            // 固定时间比较，避免根据失败位置泄漏哈希字节信息。
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] derive(String plainPassword, byte[] salt, int iterations) {
        char[] passwordChars = plainPassword == null ? new char[0] : plainPassword.toCharArray();
        PBEKeySpec keySpec = new PBEKeySpec(passwordChars, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec).getEncoded();
        } catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("PBKDF2 password hashing is unavailable", exception);
        } finally {
            keySpec.clearPassword();
            java.util.Arrays.fill(passwordChars, '\0');
        }
    }
}

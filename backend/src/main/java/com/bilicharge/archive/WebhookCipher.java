package com.bilicharge.archive;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class WebhookCipher {
    private static final int NONCE_SIZE = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    WebhookCipher(@Value("${app.feishu.webhook-key:}") String encodedKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("FEISHU_WEBHOOK_ENC_KEY must be a Base64 encoded 32-byte key");
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException("FEISHU_WEBHOOK_ENC_KEY must be a Base64 encoded 32-byte key");
        }
        key = new SecretKeySpec(bytes, "AES");
    }

    String encrypt(String webhook) {
        try {
            byte[] nonce = new byte[NONCE_SIZE];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(webhook.getBytes(StandardCharsets.UTF_8));
            // A fresh nonce per value keeps repeated Webhooks from producing identical ciphertext.
            byte[] packed = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Webhook encryption failed", error);
        }
    }

    String decrypt(String stored) {
        if (stored == null || !stored.startsWith("v1:")) {
            throw new IllegalArgumentException("Unsupported Webhook ciphertext version");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(3));
            if (packed.length <= NONCE_SIZE) throw new IllegalArgumentException("Invalid Webhook ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, packed, 0, NONCE_SIZE));
            return new String(cipher.doFinal(packed, NONCE_SIZE, packed.length - NONCE_SIZE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new IllegalArgumentException("Invalid Webhook ciphertext", error);
        }
    }
}

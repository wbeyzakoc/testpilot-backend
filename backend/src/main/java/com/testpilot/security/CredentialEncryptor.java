package com.testpilot.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

// LDAP manager şifresi gibi GERİ ÇÖZÜLEBİLMESİ gereken (LDAP'a bind olurken
// tekrar kullanılacak) sırlar için — kullanıcı şifreleri (BCrypt, tek yönlü)
// ile karıştırılmamalı, bkz. AppUser.passwordHash.
@Component
public class CredentialEncryptor {

    private final TextEncryptor encryptor;

    public CredentialEncryptor(
            @Value("${ldap.encryption.key}") String key,
            @Value("${ldap.encryption.salt}") String salt) {
        this.encryptor = Encryptors.text(key, salt);
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) return null;
        return encryptor.encrypt(plainText);
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) return null;
        return encryptor.decrypt(cipherText);
    }
}

package com.testpilot.controller;

import com.testpilot.dto.LdapSettingsDto;
import com.testpilot.dto.UpdateLdapSettingsRequest;
import com.testpilot.model.LdapSettings;
import com.testpilot.repository.LdapSettingsRepository;
import com.testpilot.security.CredentialEncryptor;
import com.testpilot.security.CurrentUserResolver;
import com.testpilot.security.LdapAuthException;
import com.testpilot.security.LdapAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/settings/ldap")
@CrossOrigin(origins = "*")
public class LdapSettingsController {

    private final LdapSettingsRepository repository;
    private final CredentialEncryptor credentialEncryptor;
    private final CurrentUserResolver currentUserResolver;
    private final LdapAuthenticator ldapAuthenticator;

    public LdapSettingsController(LdapSettingsRepository repository, CredentialEncryptor credentialEncryptor,
                                   CurrentUserResolver currentUserResolver, LdapAuthenticator ldapAuthenticator) {
        this.repository = repository;
        this.credentialEncryptor = credentialEncryptor;
        this.currentUserResolver = currentUserResolver;
        this.ldapAuthenticator = ldapAuthenticator;
    }

    private LdapSettings loadOrCreate() {
        return repository.findById(LdapSettings.SINGLETON_ID).orElseGet(() -> {
            LdapSettings fresh = new LdapSettings();
            fresh.setId(LdapSettings.SINGLETON_ID);
            return repository.save(fresh);
        });
    }

    // Bu panel sadece admin rolündeki kullanıcılara açık.
    @GetMapping
    public LdapSettingsDto get(@RequestHeader(value = "X-Username", required = false) String requester) {
        currentUserResolver.requireAdmin(requester);
        return LdapSettingsDto.from(loadOrCreate());
    }

    @PutMapping
    public LdapSettingsDto update(@RequestHeader(value = "X-Username", required = false) String requester,
                                   @RequestBody UpdateLdapSettingsRequest request) {
        currentUserResolver.requireAdmin(requester);
        LdapSettings settings = loadOrCreate();

        // Kaydetmeden ÖNCE gönderilen ayarlarla gerçekten LDAP'a bağlanabiliyor
        // muyuz test ediyoruz -- bağlanamıyorsak hiç kaydetmiyoruz. Böylece
        // yanlış/çalışmayan bir ayar veritabanına yazılıp sonraki tüm giriş
        // denemelerini sessizce bozmuyor; hata anında ldap.tsx'te görünür.
        LdapSettings candidate = new LdapSettings();
        candidate.setUrl(request.getUrl());
        candidate.setManagerDn(request.getManagerDn());
        String managerPasswordPlaintext =
                (request.getManagerPassword() != null && !request.getManagerPassword().isBlank())
                        ? request.getManagerPassword()
                        : credentialEncryptor.decrypt(settings.getManagerPasswordEncrypted());
        try {
            ldapAuthenticator.testConnection(candidate, managerPasswordPlaintext);
        } catch (LdapAuthException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        settings.setUrl(request.getUrl());
        settings.setBaseDn(request.getBaseDn());
        settings.setManagerDn(request.getManagerDn());
        settings.setUserDnPattern(request.getUserDnPattern());
        settings.setUserSearchFilter(request.getUserSearchFilter());
        settings.setGroupSearchBase(request.getGroupSearchBase());
        settings.setGroupSearchFilter(request.getGroupSearchFilter());
        if (request.getPasswordEncoderType() != null && !request.getPasswordEncoderType().isBlank()) {
            settings.setPasswordEncoderType(request.getPasswordEncoderType());
        }
        // Şifre alanı boş bırakılırsa mevcut şifre korunur — ldap.tsx da zaten
        // sadece kullanıcı yeni bir değer yazdığında bu alanı gönderiyor.
        if (request.getManagerPassword() != null && !request.getManagerPassword().isBlank()) {
            settings.setManagerPasswordEncrypted(credentialEncryptor.encrypt(request.getManagerPassword()));
        }
        repository.save(settings);
        return LdapSettingsDto.from(settings);
    }
}

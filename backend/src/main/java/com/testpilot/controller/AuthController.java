package com.testpilot.controller;

import com.testpilot.dto.LoginRequest;
import com.testpilot.dto.LoginResponse;
import com.testpilot.model.AppUser;
import com.testpilot.model.LdapSettings;
import com.testpilot.model.UserRole;
import com.testpilot.model.UserSource;
import com.testpilot.repository.AppUserRepository;
import com.testpilot.repository.LdapSettingsRepository;
import com.testpilot.security.LdapAuthException;
import com.testpilot.security.LdapAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final String BAD_CREDENTIALS = "Kullanıcı adı veya parola hatalı";
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppUserRepository userRepository;
    private final LdapSettingsRepository ldapSettingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final LdapAuthenticator ldapAuthenticator;

    public AuthController(AppUserRepository userRepository,
                          LdapSettingsRepository ldapSettingsRepository,
                          PasswordEncoder passwordEncoder,
                          LdapAuthenticator ldapAuthenticator) {
        this.userRepository = userRepository;
        this.ldapSettingsRepository = ldapSettingsRepository;
        this.passwordEncoder = passwordEncoder;
        this.ldapAuthenticator = ldapAuthenticator;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        String username = request.getUsername().trim();
        AppUser existing = userRepository.findByUsernameIgnoreCase(username).orElse(null);

        // 1) LOCAL kullanıcı (admin, ya da superadmin'in POST /users ile elle
        //    eklediği biri) ise şifre burada, hash ile karşılaştırılarak doğrulanır.
        if (existing != null && existing.getSource() == UserSource.LOCAL) {
            if (existing.getPasswordHash() != null
                    && passwordEncoder.matches(request.getPassword(), existing.getPasswordHash())) {
                return new LoginResponse(existing.getUsername(), existing.getRole());
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }

        // 2) existing == null (daha önce hiç girmemiş) ya da source == LDAP
        //    (önceden LDAP'tan oluşmuş) durumunda şirket LDAP'ına karşı doğrulanır.
        //    ldap_settings henüz doldurulmadıysa (url boş) LdapAuthenticator zaten
        //    hiçbir bağlantı denemeden false döner.
        //
        //    LDAP ayarları doluyken bir şey ters giderse (yanlış manager şifresi,
        //    sunucuya ulaşılamaması, kullanıcı bulunamaması vb.) LdapAuthenticator
        //    artık sessizce false değil, LdapAuthException fırlatıyor -- burada
        //    yakalayıp mesajını 401'in gövdesine koyuyoruz ki login ekranında
        //    ("Kullanıcı adı veya parola hatalı" yerine) gerçek LDAP hatası görünsün.
        //    Bunun için application.properties'te server.error.include-message=always
        //    ayarlı olması gerekiyor (bkz. o dosyadaki not).
        LdapSettings settings = ldapSettingsRepository.findById(LdapSettings.SINGLETON_ID).orElse(null);
        try {
            if (ldapAuthenticator.authenticate(settings, username, request.getPassword())) {
                AppUser user = existing;
                if (user == null) {
                    String managerCn = extractCn(settings.getManagerDn());
                    boolean isManagerAccount = managerCn != null && managerCn.equalsIgnoreCase(username);

                    user = new AppUser();
                    user.setUsername(username);
                    user.setRole(isManagerAccount ? UserRole.ADMIN : UserRole.USER);
                    user.setSource(UserSource.LDAP);
                    userRepository.save(user);
                }
                return new LoginResponse(user.getUsername(), user.getRole());
            }
        } catch (LdapAuthException e) {
            log.warn("LDAP login basarisiz - username={}: {}", username, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
    }

    // "CN=sh10799,OU=ServisHesaplari,DC=vakifbank,DC=intra" -> "sh10799"
    private static String extractCn(String dn) {
        if (dn == null || dn.isBlank()) return null;
        String firstComponent = dn.split(",")[0].trim();
        int eq = firstComponent.indexOf('=');
        if (eq < 0) return null;
        String attr = firstComponent.substring(0, eq).trim();
        if (!attr.equalsIgnoreCase("cn")) return null;
        return firstComponent.substring(eq + 1).trim();
    }
}
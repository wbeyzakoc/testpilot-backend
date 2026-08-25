package com.testpilot.security;

import com.testpilot.model.AppUser;
import com.testpilot.model.UserRole;
import com.testpilot.repository.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// Şu an gerçek bir oturum/token mekanizması yok — frontend giriş yapan
// kullanıcıyı localStorage'da tutuyor ve admin işlemi gerektiren her istekte
// "X-Username" header'ı olarak backend'e gönderiyor. Biz de o kullanıcıyı
// veritabanından bulup rolüne bakıyoruz.
//
// NOT: Bu header istemci tarafından gönderildiği için değiştirilebilir —
// gerçek/kriptografik bir güvenlik sınırı değil. Ama "role'u USER olan
// admin paneline (LDAP Ayarları / Kullanıcılar) erişemesin ve rol
// değiştiremesin" kuralını uygulamak için şu aşamada yeterli. İleride
// gerçek oturum/token (örn. JWT) eklenince bu sınıf onun üstüne kurulacak.
@Component
public class CurrentUserResolver {

    private final AppUserRepository userRepository;

    public CurrentUserResolver(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser requireUser(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Giriş yapmanız gerekiyor");
        }
        return userRepository.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Giriş yapmanız gerekiyor"));
    }

    public AppUser requireAdmin(String username) {
        AppUser user = requireUser(username);
        if (user.getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu işlem için admin yetkisi gerekli");
        }
        return user;
    }
}

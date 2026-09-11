package com.testpilot.startup;

import com.testpilot.model.AppUser;
import com.testpilot.model.UserRole;
import com.testpilot.model.UserSource;
import com.testpilot.repository.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// app_users tablosu tamamen boşsa (ilk kurulum) giriş yapabilmen için tek seferlik
// bir 'admin' kullanıcısı oluşturur (şifre: admin). Sonraki açılışlarda tablo boş
// olmadığı için hiçbir şey yapmaz.
@Component
public class UserBootstrap implements CommandLineRunner {

    private static final String DEFAULT_PASSWORD = "admin";
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserBootstrap(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) return;

        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setRole(UserRole.ADMIN);
        admin.setSource(UserSource.LOCAL);
        admin.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        userRepository.save(admin);

        System.out.println("=========================================================");
        System.out.println(" İlk kurulum: 'admin' kullanıcısı oluşturuldu.");
        System.out.println(" Kullanıcı adı : admin");
        System.out.println(" Şifre         : admin");
        System.out.println("=========================================================");
    }
}

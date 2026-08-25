package com.testpilot.startup;

import com.testpilot.model.AppUser;
import com.testpilot.model.UserRole;
import com.testpilot.model.UserSource;
import com.testpilot.repository.AppUserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

// app_users tablosu tamamen boşsa (ilk kurulum) giriş yapabilmen için tek seferlik
// bir 'admin' kullanıcısı oluşturur, şifreyi bir kez konsola basar. Sonraki
// açılışlarda tablo boş olmadığı için hiçbir şey yapmaz.
@Component
public class UserBootstrap implements CommandLineRunner {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserBootstrap(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) return;

        String generatedPassword = generatePassword(16);
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setRole(UserRole.ADMIN);
        admin.setSource(UserSource.LOCAL);
        admin.setPasswordHash(passwordEncoder.encode(generatedPassword));
        userRepository.save(admin);

        System.out.println("=========================================================");
        System.out.println(" İlk kurulum: 'admin' kullanıcısı oluşturuldu.");
        System.out.println(" Kullanıcı adı : admin");
        System.out.println(" Şifre         : " + generatedPassword);
        System.out.println(" Bu şifre sadece burada gösteriliyor, kaydet — bir daha basılmayacak.");
        System.out.println("=========================================================");
    }

    private String generatePassword(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}

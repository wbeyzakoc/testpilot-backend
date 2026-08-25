package com.testpilot.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityBeans {

    // LOCAL kullanıcıların şifreleri için — spring-boot-starter-security eklenmediği
    // için burada elle tanımlanıyor, otomatik endpoint kilitlemesi yok.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

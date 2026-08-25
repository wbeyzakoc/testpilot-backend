package com.testpilot.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class AppUser {

    // NOT: GenerationType.IDENTITY yerine SEQUENCE kullanılıyor — Hibernate 6 ile
    // bu Oracle JDBC driver sürümü (ojdbc11 21.9.0.0.0), IDENTITY için üretilen
    // "values (?,?,?,?,?,default)" insert'ünü RETURN_GENERATED_KEYS ile
    // hazırlarken driver içi SQL ayrıştırıcıda hataya düşüyor (ORA benzeri
    // "Çağrıda geçersiz bağımsız değişkenler var"). SEQUENCE bu problemli yolu
    // tamamen atlıyor, Oracle'da da geleneksel/en uyumlu yöntem zaten budur.
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_users_seq_gen")
    @SequenceGenerator(name = "app_users_seq_gen", sequenceName = "app_users_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 255)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserSource source;

    // Sadece source=LOCAL kullanıcılar için dolu; LDAP kullanıcılarının şifresi
    // burada hiç tutulmaz, doğrulama her seferinde LDAP sunucusuna karşı yapılır.
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
    public UserSource getSource() { return source; }
    public void setSource(UserSource source) { this.source = source; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

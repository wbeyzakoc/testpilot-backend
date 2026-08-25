package com.testpilot.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

// Bir proje = bir isim + kendisine dahil edilen kullanıcılar. Sadece admin
// oluşturabilir (bkz. ProjectController). Create Test sayfasında USER
// rolündeki bir kullanıcı sadece üye olduğu projeleri görüp seçebilir,
// adminler üyelikten bağımsız hepsini görür.
@Entity
@Table(name = "projects", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "projects_seq_gen")
    @SequenceGenerator(name = "projects_seq_gen", sequenceName = "projects_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToMany
    @JoinTable(
            name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private Set<AppUser> members = new HashSet<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Set<AppUser> getMembers() { return members; }
    public void setMembers(Set<AppUser> members) { this.members = members; }
}

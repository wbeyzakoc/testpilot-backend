package com.testpilot.model;

import jakarta.persistence.*;

import java.time.Instant;

// Bir suite = bir projeye ait, o projenin testlerini gruplamak için kullanılan
// alt küme (Project > Suite > Run hiyerarşisi). Suite silinince içindeki
// testler SİLİNMEZ -- sadece Run.suiteId/suiteName bağlantıları temizlenir
// (bkz. SuiteController.deleteSuite), tıpkı proje silmede olduğu gibi.
@Entity
@Table(name = "MOBILE_SUITES")
public class Suite {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "suites_seq_gen")
    @SequenceGenerator(name = "suites_seq_gen", sequenceName = "MOBILE_SUITES_SEQ", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

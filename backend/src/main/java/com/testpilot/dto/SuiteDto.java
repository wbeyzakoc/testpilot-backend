package com.testpilot.dto;

import com.testpilot.model.Suite;

import java.time.Instant;

// suites.tsx ve history.tsx'in (suite dropdown'ı) tükettiği görünüm.
public class SuiteDto {
    private String id;
    private String name;
    private String projectId;
    private String projectName;
    private String createdBy;
    private Instant createdAt;

    public static SuiteDto from(Suite s) {
        SuiteDto dto = new SuiteDto();
        dto.id = String.valueOf(s.getId());
        dto.name = s.getName();
        dto.projectId = String.valueOf(s.getProject().getId());
        dto.projectName = s.getProject().getName();
        dto.createdBy = s.getCreatedBy();
        dto.createdAt = s.getCreatedAt();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.testpilot.dto;

import com.testpilot.model.Project;

import java.time.Instant;
import java.util.List;

// projects.tsx ve create.tsx'in tükettiği görünüm.
public class ProjectDto {
    private String id;
    private String name;
    private String createdBy;
    private Instant createdAt;
    private List<AppUserDto> members;

    public static ProjectDto from(Project p) {
        ProjectDto dto = new ProjectDto();
        dto.id = String.valueOf(p.getId());
        dto.name = p.getName();
        dto.createdBy = p.getCreatedBy();
        dto.createdAt = p.getCreatedAt();
        dto.members = p.getMembers().stream().map(AppUserDto::from).toList();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<AppUserDto> getMembers() { return members; }
    public void setMembers(List<AppUserDto> members) { this.members = members; }
}

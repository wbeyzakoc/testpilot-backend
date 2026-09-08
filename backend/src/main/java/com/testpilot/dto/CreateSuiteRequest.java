package com.testpilot.dto;

public class CreateSuiteRequest {
    private String name;
    private Long projectId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
}

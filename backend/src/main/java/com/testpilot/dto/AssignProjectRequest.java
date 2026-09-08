package com.testpilot.dto;

import java.util.List;

// Test History'de secilen testleri (toplu) bir projeye eklemek icin --
// POST /runs/assign-project govdesi. projectId null gonderilirse secilen
// testlerin proje baglantisi tamamen kaldirilir (projectId/projectName null olur).
public class AssignProjectRequest {
    private List<String> runIds;
    private Long projectId;

    public List<String> getRunIds() { return runIds; }
    public void setRunIds(List<String> runIds) { this.runIds = runIds; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
}

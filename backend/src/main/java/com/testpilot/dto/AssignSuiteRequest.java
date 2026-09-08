package com.testpilot.dto;

import java.util.List;

// POST /runs/assign-suite gövdesi -- Test History'deki "seçili testleri
// suite'e ekle" toolbar'ı ile eşleşir (assign-project ile aynı desen).
// suiteId null gelirse seçili testlerin suite bağlantısı tamamen kaldırılır.
public class AssignSuiteRequest {
    private List<String> runIds;
    private Long suiteId;

    public List<String> getRunIds() { return runIds; }
    public void setRunIds(List<String> runIds) { this.runIds = runIds; }
    public Long getSuiteId() { return suiteId; }
    public void setSuiteId(Long suiteId) { this.suiteId = suiteId; }
}

package com.testpilot.model;

import java.util.Map;
public class TestRequest {
    private String name;
    private String goal;
    private Map<String, String> variables;
    private String appPackage;
    private String appActivity;
    private String platform;
    private boolean captureScreenshot;
    private boolean recordVideo;
    private boolean parallel;
    // Opsiyonel — seçilmezse null kalır, Run'da da projectId/projectName null olur.
    private Long projectId;
    // Sadece NightlySuiteScheduler set eder -- normal Create Test akışında hiç
    // dokunulmaz (varsayılan false). Amaç: gece koşumunun ÜRETTİĞİ Run'ı,
    // "bu run gece koşumundan geldi" diye işaretleyip Dashboard'daki özel
    // gece koşumu bölümünde gösterebilmek (bkz. Run.nightlyRun).
    private boolean nightlyRun;

    public boolean isNightlyRun() { return nightlyRun; }
    public void setNightlyRun(boolean nightlyRun) { this.nightlyRun = nightlyRun; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }
    public String getAppActivity() { return appActivity; }
    public void setAppActivity(String appActivity) { this.appActivity = appActivity; }
    public boolean isCaptureScreenshot() { return captureScreenshot; }
    public void setCaptureScreenshot(boolean captureScreenshot) { this.captureScreenshot = captureScreenshot; }
    public boolean isRecordVideo() { return recordVideo; }
    public void setRecordVideo(boolean recordVideo) { this.recordVideo = recordVideo; }
    public boolean isParallel() { return parallel; }
    public void setParallel(boolean parallel) { this.parallel = parallel; }
}
package com.testpilot.model;

import java.util.Map;
public class TestRequest {
    private String goal;
    private Map<String, String> variables;
    private String appPackage;
    private String appActivity;
    private String platform;
    private boolean captureScreenshot;
    private boolean recordVideo;

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
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
}
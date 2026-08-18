package com.testpilot.model;

import java.util.ArrayList;
import java.util.List;

public class Run {//bir testin tüm durumu
    private String id;
    private String goal;
    private volatile String status; // "running" | "passed" | "failed" | "error" | "stopped"
    private final List<RunStep> steps = new ArrayList<>();
    private String error;
    private String startedAt;
    private String finishedAt;
    private volatile boolean stopRequested = false;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<RunStep> getSteps() { return steps; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public boolean isStopRequested() { return stopRequested; }
    public void setStopRequested(boolean stopRequested) { this.stopRequested = stopRequested; }
    private String appPackage;
    private List<ScenarioSuggestion> suggestions;
    private String appActivity;

    public String getAppActivity() {
        return appActivity;
    }

    public void setAppActivity(String appActivity) {
        this.appActivity = appActivity;
    }
    public List<ScenarioSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<ScenarioSuggestion> suggestions) { this.suggestions = suggestions; }
    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }

    private String failureScreenshot;
    private boolean hasVideo;

    public String getFailureScreenshot() { return failureScreenshot; }
    public void setFailureScreenshot(String failureScreenshot) { this.failureScreenshot = failureScreenshot; }
    public boolean isHasVideo() { return hasVideo; }
    public void setHasVideo(boolean hasVideo) { this.hasVideo = hasVideo; }
}
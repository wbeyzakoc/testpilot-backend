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

    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }
}
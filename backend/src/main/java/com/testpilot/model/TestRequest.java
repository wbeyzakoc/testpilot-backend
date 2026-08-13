package com.testpilot.model;

import java.util.Map;

public class TestRequest {
    private String goal;
    private Map<String, String> variables;
    private String appPackage;
    private String appActivity;

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }
    public String getAppActivity() { return appActivity; }
    public void setAppActivity(String appActivity) { this.appActivity = appActivity; }
}
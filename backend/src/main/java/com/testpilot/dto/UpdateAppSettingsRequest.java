package com.testpilot.dto;

public class UpdateAppSettingsRequest {
    // Boş bırakılırsa mevcut key korunur (ldap manager password ile aynı mantık).
    private String openrouterApiKey;
    private String openrouterModel;
    private String llmApiUrl;
    private String appiumGridUrl;
    private String androidAppPackage;
    private String androidAppActivity;
    private Integer maxSteps;
    private String deviceName;
    private String platformVersion;

    public String getOpenrouterApiKey() { return openrouterApiKey; }
    public void setOpenrouterApiKey(String openrouterApiKey) { this.openrouterApiKey = openrouterApiKey; }
    public String getOpenrouterModel() { return openrouterModel; }
    public void setOpenrouterModel(String openrouterModel) { this.openrouterModel = openrouterModel; }
    public String getLlmApiUrl() { return llmApiUrl; }
    public void setLlmApiUrl(String llmApiUrl) { this.llmApiUrl = llmApiUrl; }
    public String getAppiumGridUrl() { return appiumGridUrl; }
    public void setAppiumGridUrl(String appiumGridUrl) { this.appiumGridUrl = appiumGridUrl; }
    public String getAndroidAppPackage() { return androidAppPackage; }
    public void setAndroidAppPackage(String androidAppPackage) { this.androidAppPackage = androidAppPackage; }
    public String getAndroidAppActivity() { return androidAppActivity; }
    public void setAndroidAppActivity(String androidAppActivity) { this.androidAppActivity = androidAppActivity; }
    public Integer getMaxSteps() { return maxSteps; }
    public void setMaxSteps(Integer maxSteps) { this.maxSteps = maxSteps; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }
}

package com.testpilot.dto;

import com.testpilot.model.AppSettings;

// settings.tsx'in tükettiği görünüm — gerçek API key asla dışarı sızmaz, sadece
// ayarlı olup olmadığı (openrouterApiKeySet) döner.
public class AppSettingsDto {
    private boolean openrouterApiKeySet;
    private String openrouterModel;
    private String appiumGridUrl;
    private String androidAppPackage;
    private String androidAppActivity;
    private Integer maxSteps;

    public static AppSettingsDto from(AppSettings s) {
        AppSettingsDto dto = new AppSettingsDto();
        dto.openrouterApiKeySet = s.getOpenrouterApiKeyEncrypted() != null && !s.getOpenrouterApiKeyEncrypted().isBlank();
        dto.openrouterModel = s.getOpenrouterModel();
        dto.appiumGridUrl = s.getAppiumGridUrl();
        dto.androidAppPackage = s.getAndroidAppPackage();
        dto.androidAppActivity = s.getAndroidAppActivity();
        dto.maxSteps = s.getMaxSteps();
        return dto;
    }

    public boolean isOpenrouterApiKeySet() { return openrouterApiKeySet; }
    public void setOpenrouterApiKeySet(boolean openrouterApiKeySet) { this.openrouterApiKeySet = openrouterApiKeySet; }
    public String getOpenrouterModel() { return openrouterModel; }
    public void setOpenrouterModel(String openrouterModel) { this.openrouterModel = openrouterModel; }
    public String getAppiumGridUrl() { return appiumGridUrl; }
    public void setAppiumGridUrl(String appiumGridUrl) { this.appiumGridUrl = appiumGridUrl; }
    public String getAndroidAppPackage() { return androidAppPackage; }
    public void setAndroidAppPackage(String androidAppPackage) { this.androidAppPackage = androidAppPackage; }
    public String getAndroidAppActivity() { return androidAppActivity; }
    public void setAndroidAppActivity(String androidAppActivity) { this.androidAppActivity = androidAppActivity; }
    public Integer getMaxSteps() { return maxSteps; }
    public void setMaxSteps(Integer maxSteps) { this.maxSteps = maxSteps; }
}

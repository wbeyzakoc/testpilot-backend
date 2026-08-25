package com.testpilot.model;

import jakarta.persistence.*;

// application.properties'te sabit duran, ama aslında koşum sırasında değişebilmesi
// gereken ayarların DB'deki karşılığı (openrouter api key/model, appium grid url,
// varsayılan Android paket/activity, maksimum adım sayısı). LdapSettings gibi tek
// satırlık (singleton) bir tablo — bkz. AppSettingsService.getOrCreate().
//
// NOT: DB_PASSWORD ve ldap.encryption.key/salt burada YOK ve OLAMAZ — uygulama
// veritabanına bağlanmadan önce onlara ihtiyaç duyuyor (bkz. secrets.properties).
@Entity
@Table(name = "app_settings")
public class AppSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "openrouter_api_key_encrypted", length = 1000)
    private String openrouterApiKeyEncrypted;

    @Column(name = "openrouter_model", length = 255)
    private String openrouterModel;

    @Column(name = "appium_grid_url", length = 255)
    private String appiumGridUrl;

    @Column(name = "android_app_package", length = 255)
    private String androidAppPackage;

    @Column(name = "android_app_activity", length = 255)
    private String androidAppActivity;

    @Column(name = "max_steps")
    private Integer maxSteps;

    // Device farm (BrowserStack vb.) kullanirken hangi cihazda calisilacagini
    // belirtmek icin -- bos birakilirsa (yerel Appium/Grid kullanimi) hicbir
    // capability gonderilmez, mevcut davranis degismez. Bkz. AppiumDriverManager.
    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "platform_version", length = 50)
    private String platformVersion;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOpenrouterApiKeyEncrypted() { return openrouterApiKeyEncrypted; }
    public void setOpenrouterApiKeyEncrypted(String openrouterApiKeyEncrypted) { this.openrouterApiKeyEncrypted = openrouterApiKeyEncrypted; }
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
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }
}

package com.testpilot.settings;

import com.testpilot.model.AppSettings;
import com.testpilot.repository.AppSettingsRepository;
import com.testpilot.security.CredentialEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// AppSettings tek satırlık (singleton) tablosunu okuyan/oluşturan merkezi yer —
// LlmAgent, AppiumDriverManager ve RunController artık ayarlarını buradan alıyor
// (application.properties'ten @Value ile DEĞİL).
//
// İlk açılışta (app_settings tablosu boşsa) satır, aşağıdaki @Value alanlarıyla —
// yani application.properties'teki ESKİ değerlerle — dolduruluyor. Böylece bu
// değişiklik mevcut kurulumu kırmıyor: OPENROUTER_API_KEY env'i hâlâ set'liyse
// ilk açılışta otomatik olarak panele taşınıyor. Satır bir kez oluşturulduktan
// sonra bu @Value'lar bir daha hiç okunmaz, her şey panelden (/settings/properties)
// yönetilir.
@Service
public class AppSettingsService {

    private final AppSettingsRepository repository;
    private final CredentialEncryptor credentialEncryptor;

    @Value("${openrouter.api-key:}")
    private String legacyOpenrouterApiKey;
    @Value("${openrouter.model:anthropic/claude-sonnet-5}")
    private String legacyOpenrouterModel;
    @Value("${appium.grid-url:http://127.0.0.1:4444}")
    private String legacyAppiumGridUrl;
    @Value("${android.app-package:}")
    private String legacyAndroidAppPackage;
    @Value("${android.app-activity:}")
    private String legacyAndroidAppActivity;

    public AppSettingsService(AppSettingsRepository repository, CredentialEncryptor credentialEncryptor) {
        this.repository = repository;
        this.credentialEncryptor = credentialEncryptor;
    }

    public AppSettings getOrCreate() {
        return repository.findById(AppSettings.SINGLETON_ID).orElseGet(() -> {
            AppSettings fresh = new AppSettings();
            fresh.setId(AppSettings.SINGLETON_ID);
            fresh.setOpenrouterModel(legacyOpenrouterModel);
            fresh.setAppiumGridUrl(legacyAppiumGridUrl);
            fresh.setAndroidAppPackage(legacyAndroidAppPackage);
            fresh.setAndroidAppActivity(legacyAndroidAppActivity);
            fresh.setMaxSteps(15);
            if (legacyOpenrouterApiKey != null && !legacyOpenrouterApiKey.isBlank()) {
                fresh.setOpenrouterApiKeyEncrypted(credentialEncryptor.encrypt(legacyOpenrouterApiKey));
            }
            return repository.save(fresh);
        });
    }

    public String getOpenrouterApiKeyDecrypted() {
        String encrypted = getOrCreate().getOpenrouterApiKeyEncrypted();
        String decrypted = credentialEncryptor.decrypt(encrypted);
        return decrypted == null ? "" : decrypted;
    }
}

package com.testpilot.controller;

import com.testpilot.dto.AppSettingsDto;
import com.testpilot.dto.UpdateAppSettingsRequest;
import com.testpilot.model.AppSettings;
import com.testpilot.repository.AppSettingsRepository;
import com.testpilot.security.CredentialEncryptor;
import com.testpilot.security.CurrentUserResolver;
import com.testpilot.settings.AppSettingsService;
import org.springframework.web.bind.annotation.*;

// settings.tsx'in (Panel sayfası) kullandığı endpoint — sadece admin görüp
// değiştirebilir (API key içerdiği için Panel sayfası artık admin-only).
@RestController
@RequestMapping("/settings/properties")
@CrossOrigin(origins = "*")
public class AppSettingsController {

    private final AppSettingsRepository repository;
    private final AppSettingsService appSettingsService;
    private final CredentialEncryptor credentialEncryptor;
    private final CurrentUserResolver currentUserResolver;

    public AppSettingsController(AppSettingsRepository repository, AppSettingsService appSettingsService,
                                  CredentialEncryptor credentialEncryptor, CurrentUserResolver currentUserResolver) {
        this.repository = repository;
        this.appSettingsService = appSettingsService;
        this.credentialEncryptor = credentialEncryptor;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public AppSettingsDto get(@RequestHeader(value = "X-Username", required = false) String requester) {
        currentUserResolver.requireAdmin(requester);
        return AppSettingsDto.from(appSettingsService.getOrCreate());
    }

    @PutMapping
    public AppSettingsDto update(@RequestHeader(value = "X-Username", required = false) String requester,
                                  @RequestBody UpdateAppSettingsRequest request) {
        currentUserResolver.requireAdmin(requester);
        AppSettings settings = appSettingsService.getOrCreate();
        settings.setOpenrouterModel(request.getOpenrouterModel());
        settings.setAppiumGridUrl(request.getAppiumGridUrl());
        settings.setAndroidAppPackage(request.getAndroidAppPackage());
        settings.setAndroidAppActivity(request.getAndroidAppActivity());
        settings.setDeviceName(request.getDeviceName());
        settings.setPlatformVersion(request.getPlatformVersion());
        if (request.getMaxSteps() != null && request.getMaxSteps() > 0) {
            settings.setMaxSteps(request.getMaxSteps());
        }
        if (request.getOpenrouterApiKey() != null && !request.getOpenrouterApiKey().isBlank()) {
            settings.setOpenrouterApiKeyEncrypted(credentialEncryptor.encrypt(request.getOpenrouterApiKey()));
        }
        repository.save(settings);
        return AppSettingsDto.from(settings);
    }
}

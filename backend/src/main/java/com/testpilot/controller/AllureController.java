package com.testpilot.controller;

import com.testpilot.agent.RunStore;
import com.testpilot.model.AppUser;
import com.testpilot.model.Project;
import com.testpilot.model.Run;
import com.testpilot.model.UserRole;
import com.testpilot.repository.AppUserRepository;
import com.testpilot.repository.ProjectRepository;
import com.testpilot.security.CurrentUserResolver;
import com.testpilot.service.AllureReportService;
import com.testpilot.settings.AppSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Dashboard'daki "Allure Raporu Oluştur" butonunun ucu. Görünürlük kuralı
// RunController.listRuns/filterVisible ile BİREBİR aynı (kasten kopyalandı,
// private oldukları için paylaşılamadı): admin tüm testleri, normal
// kullanıcı sadece kendi oluşturduğu ya da üyesi olduğu projelerin
// testlerini rapora dahil edebiliyor -- History'de neyi görüyorsa Allure
// raporuna da AYNI testler giriyor.
@RestController
@RequestMapping("/allure")
@CrossOrigin(origins = "*")
public class AllureController {

    private final RunStore runStore;
    private final AppUserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final AllureReportService allureReportService;
    private final CurrentUserResolver currentUserResolver;
    private final AppSettingsService appSettingsService;

    public AllureController(RunStore runStore, AppUserRepository userRepository,
                             ProjectRepository projectRepository, AllureReportService allureReportService,
                             CurrentUserResolver currentUserResolver, AppSettingsService appSettingsService) {
        this.runStore = runStore;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.allureReportService = allureReportService;
        this.currentUserResolver = currentUserResolver;
        this.appSettingsService = appSettingsService;
    }

    // scope=all (varsayılan) -- görebildiğin tüm tamamlanmış testler.
    // scope=last -- sadece en son BİTEN (finishedAt'i en yeni) tek test.
    // projectId verilmezse (Dashboard'daki genel rapor) -- SADECE admin
    // çağırabilir (Dashboard'daki bu bölüm artık admin'e özel). projectId
    // verilirse (Projeler/Suite'ler sayfasındaki proje bazlı buton) -- admin
    // ya da o projenin üyesi olan herhangi bir USER çağırabilir
    // (SuiteController.requireProjectAccess'teki AYNI kontrol).
    @PostMapping("/generate")
    @Transactional(readOnly = true)
    public Map<String, Object> generate(@RequestHeader(value = "X-Username", required = false) String requester,
                                         @RequestParam(required = false) Long projectId,
                                         @RequestParam(required = false, defaultValue = "all") String scope) {
        AppUser requestingUser = currentUserResolver.requireUser(requester);
        if (projectId == null) {
            if (requestingUser.getRole() != UserRole.ADMIN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Genel Allure raporu sadece adminler tarafından oluşturulabilir");
            }
        } else if (requestingUser.getRole() != UserRole.ADMIN) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proje bulunamadı"));
            boolean isMember = project.getMembers().stream()
                    .anyMatch(m -> m.getId().equals(requestingUser.getId()));
            if (!isMember) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu projenin raporunu oluşturma yetkiniz yok");
            }
        }

        List<Run> visible = filterVisible(runStore.getAll(), requester);
        if (projectId != null) {
            visible = visible.stream().filter(r -> projectId.equals(r.getProjectId())).toList();
        }
        // "Raporu Temizle" -- bu tarihten ÖNCE biten run'lar dahil edilmiyor
        // (bkz. AppSettingsService.markAllureCleared). Hiç temizlenmemişse
        // (allureClearedAt == null) hiçbir şey filtrelenmiyor -- eski davranış.
        Instant clearedAt = appSettingsService.getAllureClearedAt();
        if (clearedAt != null) {
            visible = visible.stream().filter(r -> {
                try {
                    return Instant.parse(r.getFinishedAt()).isAfter(clearedAt);
                } catch (Exception e) {
                    return true;
                }
            }).toList();
        }
        if ("last".equals(scope)) {
            visible = visible.stream()
                    .filter(r -> r.getFinishedAt() != null)
                    .sorted(java.util.Comparator.comparing(Run::getFinishedAt).reversed())
                    .limit(1)
                    .toList();
        }
        AllureReportService.GenerationResult result = allureReportService.generate(visible);
        return Map.of(
                "success", result.success,
                "message", result.message,
                "includedCount", result.includedCount,
                // Önbelleğe takılıp eski raporu göstermesin diye her seferinde
                // yeni bir query-string damgası ekleniyor (frontend bunu ekliyor
                // aslında ama burada da tutarlılık için hazır veriliyor).
                "url", "/allure-report/index.html"
        );
    }

    // Dashboard'daki "Raporu Temizle" butonu -- daha önce üretilmiş statik
    // Allure sitesini tamamen siler. Paylaşılan/ortak bir kaynak olduğu için
    // (herkesin gördüğü TEK statik site) sadece admin silebilir.
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestHeader(value = "X-Username", required = false) String requester) {
        currentUserResolver.requireAdmin(requester);
        boolean ok = allureReportService.clear();
        // Statik siteyi silmek yetmiyor -- run'lar DB'de duruyor, o yüzden
        // "kesim tarihini" de şimdiye çekiyoruz ki bir sonraki rapor üretimi
        // (genel ya da proje bazlı, hangi kullanıcı tetiklerse tetiklesin)
        // bu andan ÖNCE biten run'ları göstermesin -- gerçekten "temiz" başlasın.
        appSettingsService.markAllureCleared();
        return Map.of("success", ok);
    }

    private List<Run> filterVisible(List<Run> all, String requester) {
        if (requester == null || requester.isBlank()) return List.of();
        AppUser user = userRepository.findByUsernameIgnoreCase(requester).orElse(null);
        if (user == null) return List.of();
        if (user.getRole() == UserRole.ADMIN) return all;

        Set<Long> memberProjectIds = projectRepository.findByMembersContaining(user).stream()
                .map(Project::getId)
                .collect(Collectors.toSet());

        return all.stream()
                .filter(r -> requester.equalsIgnoreCase(r.getCreatedBy())
                        || (r.getProjectId() != null && memberProjectIds.contains(r.getProjectId())))
                .toList();
    }
}

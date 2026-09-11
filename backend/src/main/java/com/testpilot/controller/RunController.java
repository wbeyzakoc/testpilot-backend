package com.testpilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.testpilot.agent.LlmAgent;
import com.testpilot.agent.RunStore;
import com.testpilot.appium.AppiumDriverManager;
import com.testpilot.dto.AssignProjectRequest;
import com.testpilot.dto.AssignSuiteRequest;
import com.testpilot.model.*;
import com.testpilot.repository.AppUserRepository;
import com.testpilot.repository.ProjectRepository;
import com.testpilot.repository.SuiteRepository;
import com.testpilot.settings.AppSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/runs")
@CrossOrigin(origins = "*")
public class RunController {

    // Maksimum adım sayısı artık sabit değil, panelden (AppSettings) okunuyor —
    // bkz. executeRun içindeki maxSteps değişkeni.
    private static final List<String> INFO_LINK_KEYWORDS = List.of(
            "learn more", "daha fazla bilgi", "hakkında", "detaylar", "more info", "öğren", "about"
    );

    // Test adı boş bırakılırsa (kullanıcı yazmadıysa) goal'dan kısa bir isim türetilir,
    // böylece history'de her satır "giriş yap" gibi ayırt edilemez kalmaz.
    private String resolveName(String name, String goal) {
        if (name != null && !name.isBlank()) return name.trim();
        if (goal == null || goal.isBlank()) return "İsimsiz Test";
        String trimmed = goal.trim();
        return trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
    }

    private boolean isInformationalLink(String target) {
        if (target == null) return false;
        String t = target.toLowerCase();
        return INFO_LINK_KEYWORDS.stream().anyMatch(t::contains);
    }

    // [DUZELTME 2026-09-10] Modelin dondurdugu elementId'yi int'e cevirir. Sadece saf rakam
    // kabul edilir ("7", "25"). "abc", "", null veya aralik disi -> null. Tap/type icin
    // elementId zorunlu oldugundan, bu metot null donerse action GECERSIZ sayilir.
    private Integer parseElementId(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.matches("\\d{1,4}")) return null;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ============================================================================================
    // OTOMATIK KAYDIRMA (element bulunamadiginda)
    // ============================================================================================
    private static final int NOT_FOUND_SCROLL_THRESHOLD = 1;
    private static final int MAX_AUTO_SCROLLS = 8;
    private static final String[] AUTO_SCROLL_DIRECTIONS = {"down", "down", "down", "down", "down", "down", "down", "up"};

    private record AutoScrollOutcome(boolean scrolled, boolean exhausted, String direction, boolean effective) {}

        private boolean productSelectionCompleted(String goal, String target, String pageSource) {
        if (goal == null || target == null || pageSource == null) return false;
        String normalizedGoal = goal.toLowerCase(java.util.Locale.ROOT);
        boolean hasOrdinalProduct = normalizedGoal.matches(".*\\d+\\s*[.]?\\s*ürün.*");
        String normalizedTarget = target.toLowerCase(java.util.Locale.ROOT);
        boolean looksLikeProduct = normalizedTarget.contains("sauce labs")
            || normalizedTarget.contains("t-shirt")
            || normalizedTarget.contains("onesie")
            || normalizedTarget.contains("backpack")
            || normalizedTarget.contains("bike light")
            || normalizedTarget.contains("fleece jacket");
        String normalizedPage = pageSource.toLowerCase(java.util.Locale.ROOT);
        boolean productDetailPage = normalizedPage.contains("back to products")
            && normalizedPage.contains("add to cart");
        return hasOrdinalProduct && looksLikeProduct && productDetailPage;
        }

    private String turkishDirection(String direction) {
        return "down".equals(direction) ? "aşağı" : "yukarı";
    }

    // [DUZELTME 2026-09-11] Swipe gercekten ekrani degistirdi mi kontrolu.
    // ONCEKI SORUN: swipe() cagriliyordu ama ekran gercekten kaydi mi diye hic bakilmiyordu --
    // hedef bir konteynerde scroll edilemiyorsa (sabit form) ya da klavye/overlay tarafindan
    // kapatilmissa, swipe hicbir sey degistirmiyor ama sistem yine de "basariyla kaydirdim" diyip
    // ayni basarisizligi kor kor tekrarliyordu (bkz. LOGIN butonu sikayeti). Simdi swipe
    // oncesi/sonrasi sayfa kaynagi karsilastiriliyor; degismediyse effective=false donuyor.
    private AutoScrollOutcome autoScrollIfNeeded(String runId, String rawPageSourceBeforeScroll,
                                                 int[] notFoundStreak, int[] autoScrollAttempts) {
        notFoundStreak[0]++;
        if (notFoundStreak[0] < NOT_FOUND_SCROLL_THRESHOLD) {
            return new AutoScrollOutcome(false, false, null, false);
        }
        if (autoScrollAttempts[0] >= MAX_AUTO_SCROLLS) {
            return new AutoScrollOutcome(false, true, null, false);
        }
        String direction = AUTO_SCROLL_DIRECTIONS[autoScrollAttempts[0] % AUTO_SCROLL_DIRECTIONS.length];
        appiumDriverManager.swipe(runId, direction);
        autoScrollAttempts[0]++;
        notFoundStreak[0] = 0;

        boolean effective = true;
        try {
            String afterSwipeSource = appiumDriverManager.getPageSource(runId);
            effective = rawPageSourceBeforeScroll == null || !rawPageSourceBeforeScroll.equals(afterSwipeSource);
        } catch (Exception e) {
            System.out.println("[RUN] Swipe sonrası kontrol için sayfa kaynağı alınamadı: " + e.getMessage());
        }

        return new AutoScrollOutcome(true, false, direction, effective);
    }

    private final AppiumDriverManager appiumDriverManager;
    private final LlmAgent llmAgent;
    private final RunStore runStore;
    private final ProjectRepository projectRepository;
    private final AppUserRepository userRepository;
    private final AppSettingsService appSettingsService;
    private final SuiteRepository suiteRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, String> liveScreenshots = new ConcurrentHashMap<>();

    public RunController(AppiumDriverManager appiumDriverManager, LlmAgent llmAgent, RunStore runStore,
                         ProjectRepository projectRepository, AppUserRepository userRepository,
                         AppSettingsService appSettingsService, SuiteRepository suiteRepository,
                         ObjectMapper objectMapper) {
        this.appiumDriverManager = appiumDriverManager;
        this.llmAgent = llmAgent;
        this.runStore = runStore;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.appSettingsService = appSettingsService;
        this.suiteRepository = suiteRepository;
        this.objectMapper = objectMapper;
    }

    @DeleteMapping("/{id}")
    public void deleteRun(@PathVariable String id) {
        runStore.delete(id);
    }

    @PostMapping
    @Transactional(readOnly = true)
    public Run createRun(@RequestHeader(value = "X-Username", required = false) String requester,
                         @RequestBody TestRequest request) {
        return launchRun(request, requester);
    }

    @Transactional(readOnly = true)
    public Run launchRun(TestRequest request) {
        return launchRun(request, null);
    }

    public Run launchRun(TestRequest request, String requester) {
        Run run = new Run();
        run.setId(UUID.randomUUID().toString());
        run.setName(resolveName(request.getName(), request.getGoal()));
        run.setGoal(request.getGoal());
        run.setAppPackage(request.getAppPackage());
        run.setAppActivity(request.getAppActivity());
        run.setPlatform(request.getPlatform());
        run.setVariables(request.getVariables());
        run.setCaptureScreenshot(request.isCaptureScreenshot());
        run.setRecordVideo(request.isRecordVideo());
        run.setStatus("running");
        run.setStartedAt(Instant.now().toString());
        run.setCreatedBy(requester);
        run.setNightlyRun(request.isNightlyRun());

        if (request.getProjectId() != null) {
            Project project = projectRepository.findById(request.getProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proje bulunamadı"));
            AppUser user = requester != null ? userRepository.findByUsernameIgnoreCase(requester).orElse(null) : null;
            boolean isAdmin = user != null && user.getRole() == UserRole.ADMIN;
            boolean isMember = user != null && project.getMembers().stream()
                    .anyMatch(m -> m.getId().equals(user.getId()));
            if (!isAdmin && !isMember) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu projede test oluşturma yetkiniz yok");
            }
            run.setProjectId(project.getId());
            run.setProjectName(project.getName());
        }

        runStore.save(run);

        new Thread(() -> executeRun(run, request.getVariables(), request.getPlatform(), request.getAppPackage(), request.getAppActivity(), request.isCaptureScreenshot(), request.isRecordVideo(), request.isParallel())).start();
        return run;
    }

    @PostMapping("/{id}/nightly")
    public Run setNightlySuite(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        Run run = runStore.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run bulunamadı");
        run.setNightlySuite(Boolean.TRUE.equals(body.get("enabled")));
        runStore.save(run);
        return run;
    }

    @PostMapping("/assign-project")
    @Transactional(readOnly = true)
    public List<Run> assignProject(@RequestHeader(value = "X-Username", required = false) String requester,
                                   @RequestBody AssignProjectRequest request) {
        if (request.getRunIds() == null || request.getRunIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runIds boş olamaz");
        }

        String projectName = null;
        if (request.getProjectId() != null) {
            Project project = projectRepository.findById(request.getProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proje bulunamadı"));
            AppUser user = requester != null ? userRepository.findByUsernameIgnoreCase(requester).orElse(null) : null;
            boolean isAdmin = user != null && user.getRole() == UserRole.ADMIN;
            boolean isMember = user != null && project.getMembers().stream()
                    .anyMatch(m -> m.getId().equals(user.getId()));
            if (!isAdmin && !isMember) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu projede test oluşturma yetkiniz yok");
            }
            projectName = project.getName();
        }

        List<Run> updated = new java.util.ArrayList<>();
        for (String runId : request.getRunIds()) {
            Run run = runStore.get(runId);
            if (run == null) continue;
            run.setProjectId(request.getProjectId());
            run.setProjectName(projectName);
            runStore.save(run);
            updated.add(run);
        }
        return updated;
    }

    @PostMapping("/assign-suite")
    @Transactional(readOnly = true)
    public List<Run> assignSuite(@RequestHeader(value = "X-Username", required = false) String requester,
                                 @RequestBody AssignSuiteRequest request) {
        if (request.getRunIds() == null || request.getRunIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runIds boş olamaz");
        }
        if (request.getSuiteId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "suiteId boş olamaz");
        }

        Suite suite = suiteRepository.findById(request.getSuiteId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suite bulunamadı"));
        Project project = suite.getProject();
        AppUser user = requester != null ? userRepository.findByUsernameIgnoreCase(requester).orElse(null) : null;
        boolean isAdmin = user != null && user.getRole() == UserRole.ADMIN;
        boolean isMember = user != null && project.getMembers().stream()
                .anyMatch(m -> m.getId().equals(user.getId()));
        if (!isAdmin && !isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu projede test oluşturma yetkiniz yok");
        }

        List<Run> targetRuns = new java.util.ArrayList<>();
        for (String runId : request.getRunIds()) {
            Run run = runStore.get(runId);
            if (run == null) continue;
            if (run.getProjectId() != null && !run.getProjectId().equals(project.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "\"" + (run.getName() != null ? run.getName() : run.getGoal()) + "\" farklı bir projeye ait ("
                                + run.getProjectName() + "), önce o projeden çıkarman gerekiyor");
            }
            targetRuns.add(run);
        }

        List<Run> updated = new java.util.ArrayList<>();
        for (Run run : targetRuns) {
            if (run.getProjectId() == null) {
                run.setProjectId(project.getId());
                run.setProjectName(project.getName());
            }
            boolean alreadyInSuite = run.getSuites().stream().anyMatch(s -> s.getId().equals(suite.getId()));
            if (!alreadyInSuite) {
                run.getSuites().add(new SuiteRef(suite.getId(), suite.getName()));
            }
            runStore.save(run);
            updated.add(run);
        }
        return updated;
    }

    @PostMapping("/mark-suite-run")
    @Transactional(readOnly = true)
    public void markSuiteRun(@RequestBody AssignSuiteRequest request) {
        if (request.getRunIds() == null || request.getSuiteId() == null) return;
        Suite suite = suiteRepository.findById(request.getSuiteId()).orElse(null);
        if (suite == null) return;
        String now = Instant.now().toString();
        for (String runId : request.getRunIds()) {
            Run run = runStore.get(runId);
            if (run == null) continue;
            run.setSuiteRunAt(now);
            run.setSuiteRunSuiteId(suite.getId());
            run.setSuiteRunSuiteName(suite.getName());
            runStore.save(run);
        }
    }

    @PostMapping("/unassign-suite")
    @Transactional(readOnly = true)
    public List<Run> unassignSuite(@RequestHeader(value = "X-Username", required = false) String requester,
                                   @RequestBody AssignSuiteRequest request) {
        if (request.getRunIds() == null || request.getRunIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runIds boş olamaz");
        }
        if (request.getSuiteId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "suiteId boş olamaz");
        }

        Suite suite = suiteRepository.findById(request.getSuiteId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suite bulunamadı"));
        Project project = suite.getProject();
        AppUser user = requester != null ? userRepository.findByUsernameIgnoreCase(requester).orElse(null) : null;
        boolean isAdmin = user != null && user.getRole() == UserRole.ADMIN;
        boolean isMember = user != null && project.getMembers().stream()
                .anyMatch(m -> m.getId().equals(user.getId()));
        if (!isAdmin && !isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu projede test düzenleme yetkiniz yok");
        }

        List<Run> updated = new java.util.ArrayList<>();
        for (String runId : request.getRunIds()) {
            Run run = runStore.get(runId);
            if (run == null) continue;
            run.getSuites().removeIf(s -> s.getId().equals(request.getSuiteId()));
            runStore.save(run);
            updated.add(run);
        }
        return updated;
    }

    @PostMapping("/{id}/suggestions/page")
    public List<ScenarioSuggestion> suggestScenariosForPage(
            @PathVariable String id,
            @RequestParam String sayfa) {
        Run run = runStore.get(id);
        if (run == null) {
            throw new RuntimeException("Test bulunamadı: " + id);
        }
        List<ScenarioSuggestion> newOnes = llmAgent.suggestScenariosForPage(run.getGoal(), run.getSteps(), sayfa);
        List<ScenarioSuggestion> combined = new java.util.ArrayList<>();
        if (run.getSuggestions() != null) {
            combined.addAll(run.getSuggestions());
        }
        combined.addAll(newOnes);
        run.setSuggestions(combined);
        runStore.save(run);
        return combined;
    }

    @GetMapping
    public List<JsonNode> listRuns(@RequestHeader(value = "X-Username", required = false) String requester,
                                   @RequestParam(required = false) Long projectId) {
        List<Run> visible = filterVisible(runStore.getAll(), requester);
        if (projectId != null) {
            visible = visible.stream().filter(r -> projectId.equals(r.getProjectId())).toList();
        }
        return visible.stream().map(this::toListItem).toList();
    }

    private JsonNode toListItem(Run run) {
        ObjectNode item = objectMapper.valueToTree(run);
        // History/dashboard listeleri bu alanları kullanmıyor. Özellikle hata
        // ekran görüntüsü base64 olduğu için tüm geçmişi gereksiz yere büyütüyor.
        item.remove(List.of("failureScreenshot", "suggestions"));
        return item;
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

    @GetMapping("/{id}")
    public Run getRun(@PathVariable String id) {
        Run run = runStore.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run bulunamadı");
        return run;
    }

    @GetMapping("/{id}/screenshot")
    public Map<String, String> getScreenshot(@PathVariable String id) {
        String screenshot = liveScreenshots.get(id);
        if (screenshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Henüz ekran görüntüsü yok");
        }
        return Map.of("screenshot", screenshot);
    }

    @PostMapping("/improve-goal")
    public Map<String, String> improveGoal(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Metin boş olamaz");
        }
        String improved = llmAgent.improveGoalText(text.trim());
        return Map.of("improved", improved);
    }

    @GetMapping("/{id}/suggestions")
    public List<ScenarioSuggestion> suggestScenarios(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean refresh) {
        Run run = runStore.get(id);
        if (run == null) {
            throw new RuntimeException("Test bulunamadı: " + id);
        }
        if (!refresh && run.getSuggestions() != null && !run.getSuggestions().isEmpty()) {
            return run.getSuggestions();
        }
        List<ScenarioSuggestion> result = llmAgent.suggestScenarios(run.getGoal(), run.getSteps());
        run.setSuggestions(result);
        runStore.save(run);
        return result;
    }

    @PostMapping("/{id}/stop")
    public void stopRun(@PathVariable String id) {
        Run run = runStore.get(id);
        if (run == null) return;
        run.setStopRequested(true);
        if ("running".equals(run.getStatus())) {
            run.setStatus("stopped");
            run.setFinishedAt(Instant.now().toString());
            runStore.save(run);
        }
    }

    private void executeRun(Run run, Map<String, String> variables, String platform, String appPackage, String appActivity, boolean captureScreenshot, boolean recordVideo, boolean parallel) {
        int consecutiveFails = 0;
        String screenshot = null;
        Integer configuredMaxSteps = appSettingsService.getOrCreate().getMaxSteps();
        int maxSteps = (configuredMaxSteps != null && configuredMaxSteps > 0) ? configuredMaxSteps : 15;
        
        // Arka plan ekran güncelleme döngüsü için flag
        AtomicBoolean screenRefreshRunning = new AtomicBoolean(false);
        
        try {
            // ============================================================================================
            // [DUZELTME 2026-09-10] OTURUM BAŞLATMA -- TEK SEFERLİK
            //
            // ÖNCEKİ SORUN: startSession/resetToFreshState bir try-catch içindeydi ve catch'te
            // invalidateSession + startSession + resetToFreshState TEKRAR çağrılıyordu. Bu:
            //   1) İlk session'ı quit() etmeden map'ten çıkarıp Appium server'da orphan bırakıyordu,
            //   2) İkinci session açılınca aynı cihaza bağlı İKİ Appium oturumu oluyordu,
            //   3) İkisi de app'i açma/kapatma komutu gönderince uygulama "kendi kendine açılıp
            //      kapanıyor / donuyor" belirtisi veriyordu.
            //
            // ŞİMDİ: Tek startSession. Başarısız olursa run hata ile kapatılır. resetToFreshState
            // başarısız olsa bile session ayakta kaldığı için test akışına devam edilir.
            // ============================================================================================
            try {
                appiumDriverManager.startSession(run.getId(), platform, appPackage, appActivity, parallel);
            } catch (Exception sessionEx) {
                run.setStatus("error");
                run.setError("Appium session başlatılamadı: " + sessionEx.getMessage());
                run.setFinishedAt(Instant.now().toString());
                runStore.save(run);
                return;
            }

            try {
                appiumDriverManager.resetToFreshState(run.getId(), platform, appPackage);
            } catch (Exception resetEx) {
                System.out.println("[RUN] resetToFreshState uyarısı (devam ediliyor): " + resetEx.getMessage());
            }

            // Uygulama açıldıktan sonra UI'ın tam yüklenmesi için bekleme
            Thread.sleep(2000);
            if (recordVideo) {
                appiumDriverManager.startScreenRecording(run.getId());
            }
            // İlk ekran görüntüsünü hemen al
            if (captureScreenshot) {
                screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                liveScreenshots.put(run.getId(), screenshot);
            }
            
            // Arka plan ekran güncelleme döngüsünü başlat (video gibi akıcı görüntü için)
            if (captureScreenshot) {
                screenRefreshRunning.set(true);
                Thread refreshThread = new Thread(() -> {
                    while (screenRefreshRunning.get() && !run.isStopRequested()) {
                        try {
                            // Her 250 ms'de ekran görüntüsünü güncelle (4 fps - video akıcılığı)
                            Thread.sleep(250);
                            String freshScreenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                            liveScreenshots.put(run.getId(), freshScreenshot);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            System.out.println("[RUN] Arka plan ekran güncelleme hatası: " + e.getMessage());
                        }
                    }
                });
                refreshThread.setDaemon(true);
                refreshThread.start();
                System.out.println("[RUN] Arka plan ekran güncelleme döngüsü başlatıldı (250ms)");
            }

            String lastActionSignature = null;
            int repeatCount = 0;
            // [DUZELTME 2026-09-10] XML degisimine duyarli loop tespiti. Ayni action+target
            // gelir ama XML degistiyse (ör. "Add to Cart" -> "Remove"), ilerleme VAR demektir;
            // repeatCount SIFIRLANIR. Boylece "butona bas -> metin degisti -> tekrar bas"
            // dongusu yanlis pozitif olarak FAIL'e takilmaz.
            String lastPageSourceHash = null;

            int[] notFoundStreak = {0};
            int[] autoScrollAttempts = {0};
            // [DUZELTME 2026-09-10] Model hedef ekranda oldugu halde swipe dondururse bunu
            // kac kez reddettigimizi sayar. 2. redden sonra backend KENDISI tap eder.
            int[] swipeRefusedCount = {0};
            boolean isFirstStep = true;
            String repeatWarning = null;

            for (int i = 1; i <= maxSteps; i++) {
                if (run.isStopRequested()) {
                    run.setStatus("stopped");
                    run.setFinishedAt(Instant.now().toString());
                    runStore.save(run);
                    screenRefreshRunning.set(false);
                    return;
                }

                // Ekran görüntüsünü HER adımda EN BAŞTA al (arka plan döngüsü çalışsa da garanti için)
                if (captureScreenshot) {
                    try {
                        screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                        liveScreenshots.put(run.getId(), screenshot);
                    } catch (Exception screenEx) {
                        System.out.println("[RUN] Ekran görüntüsü alınamadı (adım " + i + "): " + screenEx.getMessage());
                    }
                }

                String rawPageSource;
                String filteredPageSource;
                try {
                    rawPageSource = appiumDriverManager.getPageSource(run.getId());
                    // [DUZELTME 2026-09-10] filterPageSource artik goal parametresi aliyor --
                    // hedefle eslesen elementler listenin BASINA tasiniyor. ASAGIDAKI
                    // resolveTargetCenter ve findMatchingTarget cagrilari da AYNI goal ile
                    // yapilmali, aksi halde [N] baska bir elemente denk gelir.
                    filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource, run.getGoal());
                } catch (Exception pageEx) {
                    System.out.println("Page source alınamadı, adım atlanıyor: " + pageEx.getMessage());
                    run.getSteps().add(new RunStep(i, "failed", null,
                            "UI yanıt vermiyor, sayfa kaynağı alınamadı: " + pageEx.getMessage()));
                    runStore.save(run);
                    Thread.sleep(1000);
                    continue;
                }
                System.out.println("=== FİLTRELENMİŞ XML (adım " + i + ") ===\n" + filteredPageSource);

                if (isFirstStep) {
                    System.out.println("[RUN] İlk adım: Sayfa stabilitesi için 1 saniye ek bekleme");
                    Thread.sleep(1000);
                    isFirstStep = false;
                    try {
                        rawPageSource = appiumDriverManager.getPageSource(run.getId());
                        filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource, run.getGoal());
                        // Ekran görüntüsünü tekrar al
                        if (captureScreenshot) {
                            screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                            liveScreenshots.put(run.getId(), screenshot);
                        }
                    } catch (Exception pageEx) {
                        System.out.println("İlk adım page source alınamadı: " + pageEx.getMessage());
                        run.getSteps().add(new RunStep(i, "failed", null,
                                "Sayfa yüklenemedi: " + pageEx.getMessage()));
                        runStore.save(run);
                        Thread.sleep(1000);
                        continue;
                    }
                }

                // ============================================================================================
                // [DUZELTME 2026-09-10] PRE-LLM KONTROL
                //
                // LLM karar vermeden ONCE hedefin ekranda olup olmadigini kontrol ediyoruz.
                // Varsa modele "swipe yapma, tap et" seklinde ACİK bir talimat ekliyoruz --
                // "urun gorunuyor ama kaydirmaya devam ediyor" sorununu kokunden cozer.
                //
                // targetOnScreen degiskeni asagida case "swipe" icinde TEKRAR kullanilir, bu
                // sayede ayni XML icin findMatchingTarget iki kez cagrilmaz.
                // ============================================================================================
                String targetOnScreen = appiumDriverManager.findMatchingTarget(rawPageSource, run.getGoal());
                if (targetOnScreen != null) {
                    String preHint = "!!! DİKKAT: Hedeflediğin element EKRANDA ZATEN GÖRÜNÜYOR: \""
                            + targetOnScreen + "\". "
                            + "Bu adımda SAKIN action=swipe döndürme -- swipe reddedilir. "
                            + "XML'de bu elementin [N] numarasını bul ve action=tap döndür. "
                            + "Eğer bu \"Product Image\" gibi genel bir etiketse, etiketin sonundaki "
                            + "\"[urun: ...]\" ekinin hedefle eşleştiğinden emin ol.";
                    repeatWarning = (repeatWarning == null || repeatWarning.isBlank())
                            ? preHint
                            : (repeatWarning + "\n\n" + preHint);
                    System.out.println("[RUN] Pre-LLM hint eklendi: hedef ekranda gorunuyor -> " + targetOnScreen);
                }

                AgentAction action;
                try {
                    action = llmAgent.decideNextAction(run.getGoal(), variables, screenshot, filteredPageSource, i, run.getSteps(), repeatWarning);
                } catch (Exception decideEx) {
                    run.getSteps().add(new RunStep(i, "failed", null, "Model kararı alınamadı, tekrar deneniyor: " + decideEx.getMessage()));
                    runStore.save(run);
                    Thread.sleep(800);
                    continue;
                }
                repeatWarning = null;

                // ============================================================================================
                // [DUZELTME 2026-09-10] elementId validasyonu
                //
                // Qwen gibi weak modellerin en sik hatasi: XML'de 30 element varken "[200]" gibi
                // uydurma ID dondurmek. Bu durumda action'i HIC UYGULAMIYORUZ; modele acik uyari
                // verip ayni adimi tekrar denetiyoruz.
                // ============================================================================================
                if (("tap".equals(action.getAction()) || "type".equals(action.getAction()))) {
                    Integer parsedId = parseElementId(action.getElementId());
                    int maxId = appiumDriverManager.countEmittableElements(rawPageSource, run.getGoal());
                    if (parsedId == null || parsedId < 1 || parsedId > maxId) {
                        String badId = action.getElementId() == null ? "(bos)" : action.getElementId();
                        System.out.println("[RUN] Gecersiz elementId=" + badId + " (max=" + maxId + ") -- yeniden deneniyor");
                        run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                "Model gecersiz bir elementId dondurdu (" + badId + "); XML listesinde 1-" + maxId
                                        + " arasi ID var. Yeniden deneniyor."));
                        runStore.save(run);
                        repeatWarning = "UYARI: Onceki cevabinda gecersiz bir elementId (\"" + badId
                                + "\") kullandin. Su anki XML listesinde SADECE [1] ile [" + maxId
                                + "] arasinda ID'ler var. Bu sefer listede GERCEKTEN gordugun bir [N] numarasini kullan -- "
                                + "eger hedef element listede yoksa action=swipe dondur.";
                        Thread.sleep(400);
                        continue;
                    }
                }

                if (isInformationalLink(action.getTarget())) {
                    run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                            "Bilgilendirme/link elementine tıklanması engellendi (ana akıştan uzaklaştırır), farklı bir element seçilmesi için tekrar deneniyor."));
                    runStore.save(run);
                    Thread.sleep(500);
                    continue;
                }

                // ============================================================================================
                // [DUZELTME 2026-09-10] REPEAT DETECTION -- XML hash'e duyarli
                //
                // ONCEKI SORUN: Ayni action+target ust uste gelince loop sayiliyordu, ama XML
                // degisse bile (ör. "Add to Cart" -> "Remove") ayni signature doniyordu. Bu yuzden
                // model basarili bir ekleme yaptiktan sonra bile 3. tekrarda FAIL tetikleniyordu.
                //
                // SIMDI: XML hash'i karsilastirilir. Degistiyse repeatCount SIFIRLANIR -- cunku
                // ekran durumu degismis, ilerleme VAR demektir.
                // ============================================================================================
                String currentPageSourceHash = String.valueOf(rawPageSource.hashCode());
                boolean xmlChanged = lastPageSourceHash != null
                        && !lastPageSourceHash.equals(currentPageSourceHash);

                if ("swipe".equals(action.getAction())) {
                    repeatCount = 0;
                    lastActionSignature = null;
                    notFoundStreak[0] = 0;
                } else {
                    String currentSignature = action.getAction() + "|" + action.getTarget();
                    if (currentSignature.equals(lastActionSignature) && !xmlChanged) {
                        repeatCount++;
                    } else {
                        if (xmlChanged && currentSignature.equals(lastActionSignature)) {
                            System.out.println("[RUN] XML degisti, repeatCount sifirlaniyor (ilerleme var)");
                        }
                        repeatCount = 0;
                        lastActionSignature = currentSignature;
                    }
                }
                lastPageSourceHash = currentPageSourceHash;

                if (repeatCount == 1) {
                    repeatWarning = "UYARI: Bir onceki adimda AYNI elemente (\"" + action.getTarget()
                            + "\") AYNI aksiyonla (" + action.getAction() + ") mudahale ettin ve hicbir ilerleme kaydedilmedi. "
                            + "Bunu bir kez daha tekrarlarsan test otomatik basarisiz sayilacak. Eger bu bir metin girme alaniysa "
                            + "(mail, sifre, arama kutusu vb.) KESINLIKLE action=type kullan ve text alanini doldur -- ASLA tekrar "
                            + "action=tap deneme. Metin alani degilse, XML'de TAMAMEN FARKLI (daha once denemedigin) baska bir "
                            + "elementi hedefle.";
                }

                if (repeatCount >= 2) {
                    run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                            "Aynı aksiyon (" + action.getTarget() + ") üst üste tekrarlandı, model ilerleme kaydedemiyor. Test durduruldu."));
                    run.setStatus("failed");
                    run.setError("Model aynı elemente tekrar tekrar tıklayıp döngüye girdi");
                    run.setFinishedAt(Instant.now().toString());
                    if (captureScreenshot) run.setFailureScreenshot(screenshot);
                    runStore.save(run);
                    screenRefreshRunning.set(false);
                    return;
                }

                if (("tap".equals(action.getAction()) || "type".equals(action.getAction()))
                        && action.getTarget() != null && !action.getTarget().isBlank()) {
                    System.out.println("[TARGET] Hedef çözülüyor: elementId=" + action.getElementId() + ", target=" + action.getTarget());
                    // [DUZELTME 2026-09-10] goal parametresi de geciriliyor.
                    int[] corrected = appiumDriverManager.resolveTargetCenter(run.getId(), rawPageSource, action.getElementId(), action.getTarget(), run.getGoal());
                    if (corrected != null) {
                        action.setX(corrected[0]);
                        action.setY(corrected[1]);
                        System.out.println("[TARGET] Çözüldü: x=" + corrected[0] + ", y=" + corrected[1]);
                    } else {
                        System.out.println("[TARGET] Çözülemedi - hiçbir yöntem işe yaramadı");
                    }
                }

                if (("tap".equals(action.getAction()) || "type".equals(action.getAction()))
                        && action.getTarget() != null && !action.getTarget().isBlank()) {
                    int[] screenSize = appiumDriverManager.getScreenSize(run.getId());
                    if (screenSize != null
                            && (action.getX() < 0 || action.getX() > screenSize[0]
                            || action.getY() < 0 || action.getY() > screenSize[1])) {
                        run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                "Hedef ekranın görünür alanının dışında kaldığı için atlandı: " + action.getReasoning()));
                        runStore.save(run);

                        AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), rawPageSource, notFoundStreak, autoScrollAttempts);
                        if (outcome.exhausted()) {
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Element ekranda bulunamadı; ekran hem yukarı hem aşağı kaydırılarak denendi, yine de bulunamadı."));
                            run.setStatus("failed");
                            run.setError("Hedeflenen element (\"" + action.getTarget() + "\") ekranın hiçbir kaydırma konumunda bulunamadı.");
                            run.setFinishedAt(Instant.now().toString());
                            if (captureScreenshot) run.setFailureScreenshot(screenshot);
                            runStore.save(run);
                            return;
                        } else if (outcome.scrolled() && outcome.effective()) {
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "Hedeflenen element (\"" + action.getTarget() + "\") ekranda görünmediği için ekran otomatik "
                                            + "olarak " + turkishDirection(outcome.direction()) + " kaydırıldı."));
                            runStore.save(run);
                            repeatCount = 0;
                            lastActionSignature = null;
                            repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi ekranda görünmediği için "
                                    + "SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. Şimdi "
                                    + "ekrandaki YENİ XML listesine bak: hedef artık görünür olabilir (aynı elementi tekrar "
                                    + "seçebilirsin) ya da farklı bir element gerekebilir.";
                        } else if (outcome.scrolled()) {
                            // [DUZELTME 2026-09-11] Swipe cagrildi ama ekran ICERIGI DEGISMEDI --
                            // bu ekran kaydirilamiyor olabilir (sabit form) ya da hedef bir
                            // klavye/overlay tarafindan kapatilmis olabilir. Ayni yalan-pozitif
                            // "kaydirdim, tekrar bak" mesajini tekrar tekrar vermek yerine modele
                            // durumu ACIKCA soyluyoruz ki ayni kordugumu tekrar denemeyi biraksin.
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "Kaydırma denendi ama ekran İÇERİĞİ DEĞİŞMEDİ (bu ekran kaydırılamıyor olabilir)."));
                            runStore.save(run);
                            repeatWarning = "\"" + action.getTarget() + "\" için kaydırma denendi ama ekran hiç değişmedi -- bu ekran "
                                    + "muhtemelen kaydırılamıyor (sabit bir form) ya da hedef fiziksel olarak ekran dışında/bir "
                                    + "overlay (klavye, popup) tarafından kapatılmış durumda. AYNI YÖNDE KAYDIRMAYI TEKRARLAMA. "
                                    + "Bunun yerine: (a) ekranda ŞU AN GÖRÜNEN farklı bir elementle devam etmeyi dene, (b) bir "
                                    + "klavye/popup açık olabilir, onu kapatmayı dene, (c) gerçekten farklı bir yöne kaydırmayı dene.";
                        } else {
                            repeatWarning = "Hedeflediğin \"" + action.getTarget() + "\" elementi mevcut ekranda GÖRÜNMÜYOR "
                                    + "(XML'de var ama fiziksel ekranın dışında/altında kalıyor). Bu elemente TEKRAR dokunmayı ya da "
                                    + "yazmayı deneme -- hiçbir şeye isabet etmez. Önce şunu sorgula: bu ara adıma GERÇEKTEN ihtiyacın "
                                    + "var mı, yoksa hedef zaten şu an ekranda GÖRÜNEN başka bir elementle tamamlanabilir mi? Eğer "
                                    + "gerçekten bu elemente ulaşman gerekiyorsa, onu görünür kılacak DOĞRU aksiyonu bul (bu bir "
                                    + "kaydırma olabilir, ama tek seçenek bu değil -- bir menü/gezinme ikonuna dokunmak ya da başka "
                                    + "bir aksiyon da olabilir). Aynı görünmeyen elementi ısrarla hedeflemeye devam etme.";
                        }
                        Thread.sleep(400);
                        continue;
                    }
                }

                switch (action.getAction()) {
                    case "tap" -> {
                        if (!appiumDriverManager.isValidCoordinate(rawPageSource, action.getX(), action.getY())) {
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "GEÇERSİZ KOORDİNAT (XML'de karşılığı yok), tıklama yapılamadı: " + action.getReasoning()));
                            runStore.save(run);
                            consecutiveFails++;

                            if (consecutiveFails >= 2) {
                                System.out.println("[RUN] UYARI: " + consecutiveFails + " kez başarısız deneme. Farklı bir element veya action seçilmeli!");
                            }

                            AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), rawPageSource, notFoundStreak, autoScrollAttempts);
                            if (outcome.exhausted()) {
                                run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                        "Element ekranda bulunamadı; ekran hem yukarı hem aşağı kaydırılarak denendi, yine de bulunamadı."));
                                run.setStatus("failed");
                                run.setError("Hedeflenen element (\"" + action.getTarget() + "\") ekranın hiçbir kaydırma konumunda bulunamadı.");
                                run.setFinishedAt(Instant.now().toString());
                                if (captureScreenshot) run.setFailureScreenshot(screenshot);
                                runStore.save(run);
                                return;
                            } else if (outcome.scrolled() && outcome.effective()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Hedeflenen element (\"" + action.getTarget() + "\") üst üste bulunamadığı için ekran otomatik "
                                                + "olarak " + turkishDirection(outcome.direction()) + " kaydırıldı."));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi üst üste bulunamadığı "
                                        + "için SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. "
                                        + "Şimdi ekrandaki YENİ XML listesine bakarak DEVAM ET. Aynı hedefi tekrar dene!";
                            } else if (outcome.scrolled()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Kaydırma denendi ama ekran İÇERİĞİ DEĞİŞMEDİ (bu ekran kaydırılamıyor olabilir)."));
                                runStore.save(run);
                                repeatWarning = "\"" + action.getTarget() + "\" için kaydırma denendi ama ekran hiç değişmedi -- bu ekran "
                                        + "muhtemelen kaydırılamıyor (sabit bir form) ya da hedef fiziksel olarak ekran dışında/bir "
                                        + "overlay (klavye, popup) tarafından kapatılmış durumda. AYNI YÖNDE KAYDIRMAYI TEKRARLAMA. "
                                        + "Bunun yerine: (a) ekranda ŞU AN GÖRÜNEN farklı bir elementle devam etmeyi dene, (b) bir "
                                        + "klavye/popup açık olabilir, onu kapatmayı dene, (c) gerçekten farklı bir yöne kaydırmayı dene.";
                            }
                            Thread.sleep(800);
                            continue;
                        }

                        run.getSteps().add(new RunStep(i, "tap", action.getTarget(), action.getReasoning()));
                        System.out.println("[RUN] Tap işlemi yapılıyor: " + action.getTarget() + " (x=" + action.getX() + ", y=" + action.getY() + ")");

                        try {
                            appiumDriverManager.tap(run.getId(), action.getX(), action.getY());
                            notFoundStreak[0] = 0;
                            consecutiveFails = 0;
                            swipeRefusedCount[0] = 0;

                            // Tap sonrası ekran görüntüsünü anında güncelle (arka plan döngüsü de çalışıyor)
                            if (captureScreenshot) {
                                try {
                                    screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                                    liveScreenshots.put(run.getId(), screenshot);
                                    System.out.println("[RUN] Tap sonrası ekran görüntüsü güncellendi");
                                } catch (Exception screenEx) {
                                    System.out.println("[RUN] Tap sonrası ekran görüntüsü alınamadı: " + screenEx.getMessage());
                                }
                            }

                            System.out.println("[RUN] Tap sonrası bekleme (uygulama arka plana düşmesin diye 1.5s)...");
                            // Arka plan döngüsü bu sırada da çalışıyor, görüntü akıcı kalıyor
                            Thread.sleep(1500);
                            System.out.println("[RUN] Tap işlemi BAŞARILI: " + action.getTarget());

                            String pageAfterTap = appiumDriverManager.getPageSource(run.getId());
                            if (productSelectionCompleted(run.getGoal(), action.getTarget(), pageAfterTap)) {
                                run.getSteps().add(new RunStep(i, "done", null,
                                        "Ürün seçildi ve ürün detay sayfası açıldı; hedef tamamlandı."));
                                run.setStatus("passed");
                                run.setFinishedAt(Instant.now().toString());
                                runStore.save(run);
                                System.out.println("[RUN] Ürün seçimi tamamlandı, test bitiriliyor, arka plan döngüsü durduruluyor.");
                                screenRefreshRunning.set(false);
                                return;
                            }
                        } catch (Exception tapEx) {
                            System.out.println("[RUN] Tap HATA: " + tapEx.getMessage());
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Tıklama başarısız: " + tapEx.getMessage()));
                            runStore.save(run);
                            consecutiveFails++;

                            Thread.sleep(800);
                            continue;
                        }

                    }
                    case "type" -> {
                        if (!appiumDriverManager.isValidCoordinate(rawPageSource, action.getX(), action.getY())) {
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "GEÇERSİZ KOORDİNAT, yazma yapılamadı"));
                            runStore.save(run);
                            consecutiveFails++;

                            AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), rawPageSource, notFoundStreak, autoScrollAttempts);
                            if (outcome.exhausted()) {
                                run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                        "Element ekranda bulunamadı; ekran hem yukarı hem aşağı kaydırılarak denendi, yine de bulunamadı."));
                                run.setStatus("failed");
                                run.setError("Hedeflenen element (\"" + action.getTarget() + "\") ekranın hiçbir kaydırma konumunda bulunamadı.");
                                run.setFinishedAt(Instant.now().toString());
                                if (captureScreenshot) run.setFailureScreenshot(screenshot);
                                runStore.save(run);
                                return;
                            } else if (outcome.scrolled() && outcome.effective()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Hedeflenen element (\"" + action.getTarget() + "\") üst üste bulunamadığı için ekran otomatik "
                                                + "olarak " + turkishDirection(outcome.direction()) + " kaydırıldı."));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi üst üste bulunamadığı "
                                        + "için SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. "
                                        + "Şimdi ekrandaki YENİ XML listesine bakarak DEVAM ET. Aynı hedefi tekrar dene!";
                            } else if (outcome.scrolled()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Kaydırma denendi ama ekran İÇERİĞİ DEĞİŞMEDİ (bu ekran kaydırılamıyor olabilir)."));
                                runStore.save(run);
                                repeatWarning = "\"" + action.getTarget() + "\" için kaydırma denendi ama ekran hiç değişmedi -- bu ekran "
                                        + "muhtemelen kaydırılamıyor (sabit bir form) ya da hedef fiziksel olarak ekran dışında/bir "
                                        + "overlay (klavye, popup) tarafından kapatılmış durumda. AYNI YÖNDE KAYDIRMAYI TEKRARLAMA. "
                                        + "Bunun yerine: (a) ekranda ŞU AN GÖRÜNEN farklı bir elementle devam etmeyi dene, (b) bir "
                                        + "klavye/popup açık olabilir, onu kapatmayı dene, (c) gerçekten farklı bir yöne kaydırmayı dene.";
                            }
                            Thread.sleep(800);
                            continue;
                        }
                        try {
                            run.getSteps().add(new RunStep(i, "type", action.getTarget(), action.getReasoning()));
                            appiumDriverManager.typeText(run.getId(), action.getX(), action.getY(), action.getText());
                            notFoundStreak[0] = 0;
                            consecutiveFails = 0;
                            // Type sonrası ekran güncellemesi (arka plan döngüsü de çalışıyor)
                        } catch (Exception typeEx) {
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Yazma başarısız (odak oturmadı): " + typeEx.getMessage()));
                            runStore.save(run);
                            consecutiveFails++;
                            Thread.sleep(800);
                            continue;
                        }
                    }
                    case "swipe" -> {
                        // ============================================================================================
                        // [DUZELTME 2026-09-10] SWIPE SON KONTROL
                        //
                        // Pre-LLM hint'e ragmen model inatla swipe dondururse, burada swipe'i REDDEDIYORUZ.
                        // Hedef ekranda oldugu halde kaydirma yapmak testi yavaslatir ve hedefi gormezden
                        // gelmeye iter. 2. redden sonra backend KENDISI tap eder.
                        //
                        // targetOnScreen degiskeni yukarida pre-LLM hint icin hesaplandi -- ayni XML icin
                        // findMatchingTarget'i TEKRAR cagirmiyoruz (cache).
                        // ============================================================================================
                        String matchingTarget = targetOnScreen;
                        if (matchingTarget != null) {
                            swipeRefusedCount[0]++;

                            // 2. red: otomatik tap yedegi
                            if (swipeRefusedCount[0] >= 2) {
                                System.out.println("[RUN] Otomatik TAP: model israrla kaydiriyor, hedef zaten ekranda: " + matchingTarget);
                                int[] center = appiumDriverManager.resolveTargetCenter(
                                        run.getId(), rawPageSource, null, matchingTarget, run.getGoal());
                                if (center != null) {
                                    run.getSteps().add(new RunStep(i, "tap", matchingTarget,
                                            "Sistem otomatik tap: model 2 kez kaydirma istedi ama hedef zaten ekranda."));
                                    try {
                                        appiumDriverManager.tap(run.getId(), center[0], center[1]);
                                        notFoundStreak[0] = 0;
                                        swipeRefusedCount[0] = 0;
                                        consecutiveFails = 0;
                                        System.out.println("[RUN] Otomatik tap BAŞARILI: " + matchingTarget
                                                + " (x=" + center[0] + ", y=" + center[1] + ")");
                                        Thread.sleep(1500);
                                        runStore.save(run);
                                        continue;
                                    } catch (Exception autoTapEx) {
                                        System.out.println("[RUN] Otomatik tap HATA: " + autoTapEx.getMessage());
                                    }
                                }
                                run.setStatus("failed");
                                run.setError("Hedef \"" + matchingTarget + "\" ekranda olmasına rağmen "
                                        + "model 2 kez üst üste kaydırmak istedi ve otomatik tap de başarısız oldu.");
                                run.setFinishedAt(Instant.now().toString());
                                if (captureScreenshot) run.setFailureScreenshot(screenshot);
                                runStore.save(run);
                                screenRefreshRunning.set(false);
                                return;
                            }

                            // 1. red: modele cok net bir uyari ver ve devam et
                            System.out.println("[RUN] Swipe REDDEDILDI -- hedef zaten ekranda: " + matchingTarget);
                            run.getSteps().add(new RunStep(i, "failed", matchingTarget,
                                    "Kaydirma reddedildi: hedef \"" + matchingTarget + "\" ekranda zaten gorunuyor, once secilmeli."));
                            runStore.save(run);
                            repeatCount = 0;
                            lastActionSignature = null;
                            repeatWarning = "!!! DUR, KAYDIRMA REDDEDILDI !!! "
                                    + "Hedefledigin element EKRANDA ZATEN GORUNUYOR: \"" + matchingTarget + "\". "
                                    + "Sakin kaydirma yapma! Bu elementi SIMDI action=tap ile sec "
                                    + "(uygun [N] numarasini XML'den bul). "
                                    + "Eger hedef \"Product Image\" gibi genel bir etikete sahipse, etiketin sonundaki "
                                    + "\"[urun: ...]\" ekinin hedefinle ESLESTIGINDEN emin ol.";
                            Thread.sleep(500);
                            continue;
                        }

                        // Hedef ekranda yok -- swipe guvenli
                        swipeRefusedCount[0] = 0;
                        appiumDriverManager.swipe(run.getId(), action.getDirection());
                        
                            // Kaydırma sonrası ekran güncellemesi (arka plan döngüsü de çalışıyor)
                            Thread.sleep(1000); // Kaydırma sonrası bekleme
                        }
                    case "wait" -> Thread.sleep(1500);
                    case "done" -> {
                        run.getSteps().add(new RunStep(i, "done", null, action.getReasoning()));
                        run.setStatus("passed");
                        run.setFinishedAt(Instant.now().toString());
                        runStore.save(run);
                        return;
                    }
                    case "fail" -> {
                        consecutiveFails++;
                        if (consecutiveFails >= 2) {
                            run.getSteps().add(new RunStep(i, "failed", null, action.getReasoning()));
                            run.setStatus("failed");
                            run.setError(action.getReasoning());
                            run.setFinishedAt(Instant.now().toString());
                            if (captureScreenshot) run.setFailureScreenshot(screenshot);
                            runStore.save(run);
                            screenRefreshRunning.set(false);
                            return;
                        }
                        run.getSteps().add(new RunStep(i, "failed", null, "İlk 'fail' denemesi reddedildi, tekrar deneniyor: " + action.getReasoning()));
                        runStore.save(run);
                        Thread.sleep(500);
                        continue;
                    }
                }

                if (!action.getAction().equals("fail")) {
                    consecutiveFails = 0;
                }

                runStore.save(run);
                Thread.sleep(800);
            }
            run.setStatus("failed");
            String lastTarget = run.getSteps().isEmpty() ? "bilinmiyor" : run.getSteps().get(run.getSteps().size() - 1).getTarget();
            run.setError("Maksimum adım sayısına (" + maxSteps + ") ulaşıldı, hedef tamamlanamadan test sonlandırıldı. Son denenen: " + lastTarget);
            run.setFinishedAt(Instant.now().toString());
            if (captureScreenshot) run.setFailureScreenshot(screenshot);
            runStore.save(run);
            screenRefreshRunning.set(false);
        } catch (Exception e) {
            e.printStackTrace();
            // [DUZELTME 2026-09-10] invalidateSession CAGIRMIYORUZ -- finally'deki stopSession
            // zaten dogru sekilde quit() cagiriyor. Ikisi birlikte cagrilirsa cift quit olur.
            run.setStatus("error");
            run.setError(e.getMessage());
            run.setFinishedAt(Instant.now().toString());
            if (captureScreenshot) run.setFailureScreenshot(screenshot);
            runStore.save(run);
            screenRefreshRunning.set(false);
        } finally {
            // Arka plan ekran güncelleme döngüsünü durdur
            screenRefreshRunning.set(false);
            liveScreenshots.remove(run.getId());
            if (recordVideo) {
                boolean saved = appiumDriverManager.stopScreenRecordingAndSave(run.getId());
                if (saved) {
                    run.setHasVideo(true);
                    runStore.save(run);
                }
            }
            appiumDriverManager.stopSession(run.getId());
        }
    }
}
package com.testpilot.controller;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private final AppiumDriverManager appiumDriverManager;
    private final LlmAgent llmAgent;
    private final RunStore runStore;
    private final ProjectRepository projectRepository;
    private final AppUserRepository userRepository;
    private final AppSettingsService appSettingsService;
    private final SuiteRepository suiteRepository;
    private final Map<String, String> liveScreenshots = new ConcurrentHashMap<>();

    public RunController(AppiumDriverManager appiumDriverManager, LlmAgent llmAgent, RunStore runStore,
                          ProjectRepository projectRepository, AppUserRepository userRepository,
                          AppSettingsService appSettingsService, SuiteRepository suiteRepository) {
        this.appiumDriverManager = appiumDriverManager;
        this.llmAgent = llmAgent;
        this.runStore = runStore;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.appSettingsService = appSettingsService;
        this.suiteRepository = suiteRepository;
    }

    @DeleteMapping("/{id}")
    public void deleteRun(@PathVariable String id) {
        runStore.delete(id);
    }

    // @Transactional şart: request'te projectId varsa launchRun içinde
    // project.getMembers() (lazy @ManyToMany) okunuyor; open-in-view=false
    // olduğu için transaction olmadan "LazyInitializationException: no
    // Session" atıyordu (ProjectController.listProjects'te de aynı hatayı
    // aldık, aynı sebep).
    @PostMapping
    @Transactional(readOnly = true)
    public Run createRun(@RequestHeader(value = "X-Username", required = false) String requester,
                          @RequestBody TestRequest request) {
        return launchRun(request, requester);
    }

    // Nightly suite scheduler (kullanıcı oturumu yok) eski imzayla çağırıyor —
    // bu durumda createdBy/proje bilgisi boş kalır, test yine de çalışır.
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

    // Test History'de secilen testleri (toplu) bir projeye ekler/tasir. projectId
    // null gelirse secilenlerin proje baglantisi tamamen kaldirilir (projectId/
    // projectName null olur). launchRun'daki ayni admin/uye kontrolu burada da
    // var -- bir USER, uyesi olmadigi bir projeye test ekleyemesin diye.
    // @Transactional sart: project.getMembers() (lazy @ManyToMany) okunuyor --
    // createRun/listProjects'teki ayni sebep.
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

    // Test History'de seçilen testleri (toplu) bir suite'e ekler/taşır. suiteId
    // null gelirse seçili testlerin suite bağlantısı tamamen kaldırılır (suiteId/
    // suiteName null olur, projesi değişmez). suiteId verilirse -- suite zaten
    // bir projeye ait olduğu için -- testin projectId/projectName'i de o
    // suite'in projesiyle eşleşecek şekilde GÜNCELLENİR (assign-project'teki gibi
    // ayrı bir "proje uyuşmazlığı" hatası vermek yerine, "bu suite'e eklendiyse
    // artık o projenin testi" mantığı izleniyor). Yetki kontrolü assign-project
    // ile aynı: admin ya da suite'in projesinin üyesi olmak gerekiyor.
    // Test History'deki ve Suite'ler sayfasındaki "suite'e ekle" özelliği bunu
    // çağırır. Proje ile arasındaki FARK: bir test AYNI ANDA BİRDEN FAZLA
    // suite'e ait olabilir, bu yüzden burası "SET" değil "ADD" -- testin zaten
    // içinde olduğu diğer suite'lere dokunmaz, sadece bu suiteId'yi listeye
    // ekler (zaten varsa tekrar eklemez).
    //
    // Proje kuralı: suite zaten bir projeye ait olduğu için, testin projesiyle
    // suite'in projesi UYUŞMUYORSA istek reddedilir (testi yanlışlıkla başka
    // bir projeye "taşımamak" için) -- testin hiç projesi yoksa otomatik olarak
    // suite'in projesine atanır. Önce TÜM runId'ler doğrulanır, biri bile
    // uyuşmuyorsa hiçbiri güncellenmez (yarım uygulanmış toplu işlem olmasın diye).
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

    // suites.tsx'teki runSuite() bir suite'i GERÇEKTEN çalıştırdığında (SWAP
    // sonrası) o anda üretilen yeni run'ların id'lerini, hangi suite'in
    // koşumu olduğuyla birlikte buraya bildirir. Bu üç alan (suiteRunAt +
    // suiteRunSuiteId + suiteRunSuiteName) run'ın ÜZERİNDE KALICI olarak
    // damgalanıyor -- suite daha sonra tekrar koşulup bu run suite'ten
    // çıkarılsa (swap) bile bu damga hiç değişmiyor. Test History'nin suite
    // gruplaması (history.tsx) canlı suite üyeliğine değil, BU damgaya
    // bakıyor -- böylece bir suite N kere koşulduğunda N farklı koşum da
    // (o anki test sayılarıyla) ayrı ayrı gruplar olarak kalıcı biçimde
    // görünmeye devam ediyor; suite'e sonradan manuel eklenen (damgasız)
    // bir test de hiçbir eski gruba karışmıyor. Yetki kontrolüne gerek yok --
    // yalnızca frontend'in zaten suite'i başarıyla koşturduğu run'lar için
    // çağrılıyor.
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

    // Bir testi belirli bir suite'ten çıkarır -- diğer suite'lerine ve
    // projesine dokunmaz (assign-suite'in tersi, "REMOVE" işlemi). Yetki
    // kontrolü assign-suite ile aynı: admin ya da suite'in projesinin üyesi
    // olmak gerekiyor.
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

    // projectId verilirse (Projeler sayfasında bir projeye tıklayınca) sadece o
    // projeye ait koşumları filtreliyor -- getAll() zaten aktif+geçmiş birleşik
    // listeyi döndürüyor, burada sadece süzüyoruz.
    //
    // Görünürlük: USER rolündeki bir kullanıcı sadece (a) kendi oluşturduğu
    // testleri ve (b) üyesi olduğu projelerin testlerini görür -- adminler
    // hepsini görür. Önceden burada hiç filtre yoktu, bu yüzden bir kullanıcı
    // üyesi olmadığı bir projenin testlerini de History'de görebiliyordu.
    @GetMapping
    public List<Run> listRuns(@RequestHeader(value = "X-Username", required = false) String requester,
                               @RequestParam(required = false) Long projectId) {
        List<Run> visible = filterVisible(runStore.getAll(), requester);
        if (projectId == null) return visible;
        return visible.stream().filter(r -> projectId.equals(r.getProjectId())).toList();
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

    // ÖNEMLİ: sadece "stopRequested" bayrağını set edip arka plan thread'inin
    // fark etmesini BEKLEMİYORUZ artık -- executeRun döngüsü bu bayrağı
    // sadece her ADIM başında kontrol ediyor (LLM'e "sıradaki aksiyon ne"
    // diye sorduğu ya da Appium'a bir dokunma/kaydırma gönderdiği sırada
    // DEĞİL) -- model API'si yavaş yanıt verirse (bazen 10-20+ saniye) bu
    // kontrol arada çok geç geliyor ve kullanıcıya "Durdur'a bastım, hiçbir
    // şey olmadı" gibi görünüyordu. Şimdi run hâlâ "running" ise durumu
    // BURADA, anında "stopped" olarak işaretliyoruz -- UI (2sn'de bir polling
    // yapıyor) neredeyse anında güncelleniyor. Arka plandaki thread kendi
    // adımını bitirip stopRequested'i fark ettiğinde AYNI "stopped" durumunu
    // tekrar yazıp gerçek temizliği (Appium session'ı kapatma, video kaydını
    // durdurup kaydetme -- executeRun'ın finally bloğu) yine kendisi yapıyor,
    // sadece kullanıcı bunun bitmesini beklemek zorunda kalmıyor.
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

    private void executeRun(Run run, Map<String, String> variables, String platform, String appPackage, String appActivity, boolean captureScreenshot, boolean recordVideo, boolean parallel) {        int consecutiveFails = 0;
        String screenshot = null;
        Integer configuredMaxSteps = appSettingsService.getOrCreate().getMaxSteps();
        int maxSteps = (configuredMaxSteps != null && configuredMaxSteps > 0) ? configuredMaxSteps : 15;
        try {
            try {
                appiumDriverManager.startSession(run.getId(), platform, appPackage, appActivity, parallel);
                appiumDriverManager.resetToFreshState(run.getId(), platform, appPackage);
            } catch (Exception sessionEx) {
                appiumDriverManager.invalidateSession(run.getId());
                appiumDriverManager.startSession(run.getId(), platform, appPackage, appActivity, parallel);
                appiumDriverManager.resetToFreshState(run.getId(), platform, appPackage);
            }
            Thread.sleep(2500);
            if (recordVideo) {
                appiumDriverManager.startScreenRecording(run.getId());
            }
            String lastActionSignature = null;
            int repeatCount = 0;
            for (int i = 1; i <= maxSteps; i++) {
                if (run.isStopRequested()) {
                    run.setStatus("stopped");
                    run.setFinishedAt(Instant.now().toString());
                    runStore.save(run);
                    return;
                }

                screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                liveScreenshots.put(run.getId(), screenshot);
                String rawPageSource = appiumDriverManager.getPageSource(run.getId());
                String filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource);
                System.out.println("=== FİLTRELENMİŞ XML (adım " + i + ") ===\n" + filteredPageSource);

                AgentAction action;
                try {
                    action = llmAgent.decideNextAction(run.getGoal(), variables, screenshot, filteredPageSource, i, run.getSteps());
                } catch (Exception decideEx) {
                    run.getSteps().add(new RunStep(i, "failed", null, "Model kararı alınamadı, tekrar deneniyor: " + decideEx.getMessage()));
                    runStore.save(run);
                    Thread.sleep(800);
                    continue;
                }

                if (isInformationalLink(action.getTarget())) {
                    run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                            "Bilgilendirme/link elementine tıklanması engellendi (ana akıştan uzaklaştırır), farklı bir element seçilmesi için tekrar deneniyor."));
                    runStore.save(run);
                    Thread.sleep(500);
                    continue;
                }

                String currentSignature = action.getAction() + "|" + action.getTarget();
                if (currentSignature.equals(lastActionSignature)) {
                    repeatCount++;
                } else {
                    repeatCount = 0;
                    lastActionSignature = currentSignature;
                }

                if (repeatCount >= 2) {
                    run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                            "Aynı aksiyon (" + action.getTarget() + ") üst üste tekrarlandı, model ilerleme kaydedemiyor. Test durduruldu."));
                    run.setStatus("failed");
                    run.setError("Model aynı elemente tekrar tekrar tıklayıp döngüye girdi");
                    run.setFinishedAt(java.time.Instant.now().toString());
                    if (captureScreenshot) run.setFailureScreenshot(screenshot);
                    runStore.save(run);
                    return;
                }
                switch (action.getAction()) {
                    case "tap" -> {
                        if (!appiumDriverManager.isValidCoordinate(rawPageSource, action.getX(), action.getY())) {
                            run.getSteps().add(new RunStep(i, "tap", action.getTarget(),
                                    "GEÇERSİZ KOORDİNAT (XML'de karşılığı yok), adım atlandı: " + action.getReasoning()));
                            runStore.save(run);
                            Thread.sleep(500);
                            continue;
                        }
                        run.getSteps().add(new RunStep(i, "tap", action.getTarget(), action.getReasoning()));
                        appiumDriverManager.tap(run.getId(), action.getX(), action.getY());
                        Thread.sleep(400); // geçiş animasyonunun oturması için ekstra bekleme

                    }
                    case "type" -> {
                        if (!appiumDriverManager.isValidCoordinate(rawPageSource, action.getX(), action.getY())) {
                            run.getSteps().add(new RunStep(i, "type", action.getTarget(), "GEÇERSİZ KOORDİNAT, adım atlanıyor"));
                            runStore.save(run);
                            Thread.sleep(500);
                            continue;
                        }
                        try {
                            run.getSteps().add(new RunStep(i, "type", action.getTarget(), action.getReasoning()));
                            appiumDriverManager.typeText(run.getId(), action.getX(), action.getY(), action.getText());
                        } catch (Exception typeEx) {
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Alana yazılamadı (odak oturmadı): " + typeEx.getMessage()));
                            runStore.save(run);
                            Thread.sleep(500);
                            continue;
                        }
                    }
                    case "swipe" -> appiumDriverManager.swipe(run.getId(), action.getDirection());
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
        } catch (Exception e) {
            e.printStackTrace();
            appiumDriverManager.invalidateSession(run.getId());
            run.setStatus("error");
            run.setError(e.getMessage());
            run.setFinishedAt(Instant.now().toString());
            if (captureScreenshot) run.setFailureScreenshot(screenshot);
            runStore.save(run);
        } finally {
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
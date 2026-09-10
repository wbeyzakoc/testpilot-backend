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

    // ============================================================================================
    // OTOMATIK KAYDIRMA (element bulunamadiginda)
    //
    // Onceden, hedeflenen element ekranda bulunamadiginda (ya XML'de hic karsiligi yoktu ya da XML'de
    // vardi ama fiziksel ekranin disindaydi) tek yapilan sey modele bir sonraki adimda "belki kaydirman
    // gerekir" diye bir uyari vermekti -- kaydirip kaydirmamaya modelin KENDISI karar veriyordu. Zayif
    // modeller bu ipucunu gormezden gelip ayni (artik gecersiz) hedefi tekrar tekrar deniyor, boylece
    // repeatCount esigine takilip test bosuna FAIL oluyordu.
    //
    // Simdi: ayni ekranda ust uste NOT_FOUND_SCROLL_THRESHOLD kadar "bulunamadi" yasandiginda backend
    // KENDISI ekrani kaydiriyor (asagi/yukari SIRAYLA), modelin karar vermesini beklemiyor. Toplam
    // otomatik kaydirma hakki MAX_AUTO_SCROLLS ile sinirli -- hem asagi hem yukari birkac kez denenip
    // yine de bulunamiyorsa artik orada gercekten yok demektir, test acik bir hata mesajiyla durdurulur.
    //
    // GUNCELLEME: Threshold 2'den 1'e dusuruldu -- ilk denemede bile element ekranda gorusmuyorsa
    // (XML'de var ama fiziksel ekranin altinda/kaydirilmis alanda) HEMEN kaydirma yapilsin,
    // gereksiz tekrar denemelerinden kacinilsin. Bu ozellikle ilk ekranda gosterilen urun sayisi
    // sinirli olan liste ekranlarinda (6. urunu bulmak icin kaydirma gerektiginde) onemli.
    private static final int NOT_FOUND_SCROLL_THRESHOLD = 1;
    private static final int MAX_AUTO_SCROLLS = 8; // 4'ten 8'e çıkarıldı - daha fazla kaydırma hakkı
    // Her kaydırma "down" (aşağı) olmalı - içerik genellikle aşağıda
    // Sadece 8. kaydırma "up" (yukarı) - eğer hiçbiri çalışmadıysa son bir deneme
    private static final String[] AUTO_SCROLL_DIRECTIONS = {"down", "down", "down", "down", "down", "down", "down", "up"};

    private record AutoScrollOutcome(boolean scrolled, boolean exhausted, String direction) {}

    private String turkishDirection(String direction) {
        return "down".equals(direction) ? "aşağı" : "yukarı";
    }

    // notFoundStreak[0]/autoScrollAttempts[0]: executeRun'in yerel sayaclari, referans olarak
    // (tek elemanli dizi ile) buraya tasiniyor ki bu metot onlari guncelleyebilsin.
    private AutoScrollOutcome autoScrollIfNeeded(String runId, int[] notFoundStreak, int[] autoScrollAttempts) {
        notFoundStreak[0]++;
        if (notFoundStreak[0] < NOT_FOUND_SCROLL_THRESHOLD) {
            return new AutoScrollOutcome(false, false, null);
        }
        if (autoScrollAttempts[0] >= MAX_AUTO_SCROLLS) {
            return new AutoScrollOutcome(false, true, null);
        }
        String direction = AUTO_SCROLL_DIRECTIONS[autoScrollAttempts[0] % AUTO_SCROLL_DIRECTIONS.length];
        appiumDriverManager.swipe(runId, direction);
        autoScrollAttempts[0]++;
        notFoundStreak[0] = 0;
        return new AutoScrollOutcome(true, false, direction);
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

    // create.tsx'teki "Ne test etmek istiyorsun?" alanindaki auto_awesome ikonuna baglaniyor --
    // henuz bir Run olusturulmadan (bu asamada id yok), kullanicinin yazdigi ham metni LLM'e
    // gonderip daha duzgun bir cumleye cevirtiyor. Sonuc direkt uygulanmiyor -- frontend onizleme
    // olarak gosterip kullanici onaylarsa textarea'ya yaziyor.
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

    private void executeRun(Run run, Map<String, String> variables, String platform, String appPackage, String appActivity, boolean captureScreenshot, boolean recordVideo, boolean parallel) {
        int consecutiveFails = 0;
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
            // Uygulama açıldıktan sonra sayfanın tamamen yüklenmesi için bekleme
            // Liste ekranlarında ürünlerin yüklenmesi zaman alabilir
            Thread.sleep(2500);
            if (recordVideo) {
                appiumDriverManager.startScreenRecording(run.getId());
            }
            // Ek bekleme: İlk etkileşimden önce sayfanın tamamen stabil olması için
            Thread.sleep(3000);
            String lastActionSignature = null;
            int repeatCount = 0;
            // Ayni ekranda ust uste "element bulunamadi" yasanma sayisi ve buna karsilik simdiye
            // kadar yapilan otomatik kaydirma sayisi -- bkz. autoScrollIfNeeded. Tek elemanli dizi
            // olarak tutuluyor ki private metot bunlari referans olarak guncelleyebilsin.
            int[] notFoundStreak = {0};
            int[] autoScrollAttempts = {0};
            // İlk adımda otomatik kaydırma için özel bayrak - sayfa yüklenirken uzak elementlere
            // tıklanmaması için
            boolean isFirstStep = true;
            // Bir sonraki decideNextAction cagrisina tasinacak, tek seferlik uyari --
            // repeatCount tam olarak 1'e (yani ayni action+target 2. kez ust uste) ulastiginda
            // doldurulur, o adimin promptuna eklendikten hemen sonra null'lanir. Amac: modelin
            // 3. tekrarda (repeatCount>=2) asagida zaten FAIL olmadan once, kendine ait tekrari
            // gecmis metnindeki dolayli ipuclarindan CIKARSAMASINA guvenmek yerine, dogrudan ve
            // acik bir uyariyla "az once tam olarak bunu denedin, farkli bir sey yap" demek.
            String repeatWarning = null;
            for (int i = 1; i <= maxSteps; i++) {
                if (run.isStopRequested()) {
                    run.setStatus("stopped");
                    run.setFinishedAt(Instant.now().toString());
                    runStore.save(run);
                    return;
                }

                screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                liveScreenshots.put(run.getId(), screenshot);
                
                String rawPageSource;
                String filteredPageSource;
                try {
                    rawPageSource = appiumDriverManager.getPageSource(run.getId());
                    filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource);
                } catch (Exception pageEx) {
                    System.out.println("Page source alınamadı, adım atlanıyor: " + pageEx.getMessage());
                    run.getSteps().add(new RunStep(i, "failed", null, 
                            "UI yanıt vermiyor, sayfa kaynağı alınamadı: " + pageEx.getMessage()));
                    runStore.save(run);
                    Thread.sleep(2000);
                    continue;
                }
                System.out.println("=== FİLTRELENMİŞ XML (adım " + i + ") ===\n" + filteredPageSource);

                // İlk adımda sayfa yüklenmesi için ek bekleme (modelin hemen tıklama yapmasını engeller)
                if (isFirstStep) {
                    System.out.println("[RUN] İlk adım: Sayfa stabilitesi için 2 saniye ek bekleme");
                    Thread.sleep(2000);
                    isFirstStep = false;
                    // İlk adımda sayfa yüklenene kadar bekle, sonra normal akışa geç
                    try {
                        rawPageSource = appiumDriverManager.getPageSource(run.getId());
                        filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource);
                    } catch (Exception pageEx) {
                        System.out.println("İlk adım page source alınamadı: " + pageEx.getMessage());
                        run.getSteps().add(new RunStep(i, "failed", null, 
                                "Sayfa yüklenemedi: " + pageEx.getMessage()));
                        runStore.save(run);
                        Thread.sleep(2000);
                        continue;
                    }
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
                repeatWarning = null; // bu adimin promptuna zaten eklendi, tuketildi

                if (isInformationalLink(action.getTarget())) {
                    run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                            "Bilgilendirme/link elementine tıklanması engellendi (ana akıştan uzaklaştırır), farklı bir element seçilmesi için tekrar deneniyor."));
                    runStore.save(run);
                    Thread.sleep(500);
                    continue;
                }

                // "swipe" (kaydirma) donguyu tespitinin disinda tutuluyor -- uzaktaki bir elemente
                // ulasmak icin ust uste birden fazla kaydirma yapmak GERCEKTEN gerekli ve normal bir
                // davranis (ayni butona ust uste tiklamaktan farkli olarak), bunu "takilma" saymak
                // yanlis pozitif uretiyordu.
                if ("swipe".equals(action.getAction())) {
                    repeatCount = 0;
                    lastActionSignature = null;
                    notFoundStreak[0] = 0; // model kendi kaydirdi -- ekran degisti, eski "bulunamadi" gecmisi artik gecersiz
                } else {
                    String currentSignature = action.getAction() + "|" + action.getTarget();
                    if (currentSignature.equals(lastActionSignature)) {
                        repeatCount++;
                    } else {
                        repeatCount = 0;
                        lastActionSignature = currentSignature;
                    }
                }

                // repeatCount==1: bu, ayni action+target'in 2. kez ust uste secildigi an -- henuz
                // FAIL esigi olan >=2'ye (3. tekrar) ulasilmadi. Modele bir sonraki adimda acik bir
                // uyari verip kendini duzeltmesi icin SON bir sans taniyoruz.
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
                    run.setFinishedAt(java.time.Instant.now().toString());
                    if (captureScreenshot) run.setFailureScreenshot(screenshot);
                    runStore.save(run);
                    return;
                }
                // Model artik (x,y) merkezini KENDISI hesaplamiyor -- bounds aritmetigi kucuk/zayif
                // modellerde en sik hataya yol acan adimdi (birkac pikselik sapma komsu elemente
                // tiklanmasina sebep oluyordu). Bunun yerine model sadece filterPageSource'un
                // gosterdigi "[N]" numarasini (action.getElementId()) seciyor, gercek merkezi
                // resolveTargetCenter XML'deki bounds'tan BIZ hesapliyoruz. ID gecersiz/eksikse
                // (ör. model hala eski usul serbest metin donduruyorsa) etikete, o da olmazsa
                // resource-id/text'ten uretilen bir XPath ile canli uygulamada aramaya dusuyor.
                // Hicbiri sonuc vermezse (null doner) modelin verdigi x,y'ye dokunmuyoruz -- guvenli
                // varsayilan davranis korunuyor.
                if (("tap".equals(action.getAction()) || "type".equals(action.getAction()))
                        && action.getTarget() != null && !action.getTarget().isBlank()) {
                    System.out.println("[TARGET] Hedef çözülüyor: elementId=" + action.getElementId() + ", target=" + action.getTarget());
                    int[] corrected = appiumDriverManager.resolveTargetCenter(run.getId(), rawPageSource, action.getElementId(), action.getTarget());
                    if (corrected != null) {
                        action.setX(corrected[0]);
                        action.setY(corrected[1]);
                        System.out.println("[TARGET] Çözüldü: x=" + corrected[0] + ", y=" + corrected[1]);
                    } else {
                        System.out.println("[TARGET] Çözülemedi - hiçbir yöntem işe yaramadı");
                    }
                }

                // XML, henuz kaydirilmamis (asagida/yukarida kalan) elementleri de icerebiliyor --
                // boyle bir elementin bounds'u FIZIKSEL ekranin disinda kalir. Modele ekranin gercek
                // boyutunu vermiyoruz, o yuzden bunu XML'de "clickable=true" gorup normal bir hedef
                // sanabiliyor -- oraya dokunmak/yazmak hicbir seye isabet etmiyor (sessiz no-op),
                // model ilerleme kaydedemeden ayni hedefe tekrar tekrar deniyor ve tekrar-dongusu
                // korumasina takiliyor. Once gercek ekran boyutuyla karsilastirip, hedef disaridaysa
                // dokunmadan/yazmadan atliyoruz ve modele ACIKCA "once kaydir" uyarisi veriyoruz.
                if (("tap".equals(action.getAction()) || "type".equals(action.getAction()))
                        && action.getTarget() != null && !action.getTarget().isBlank()) {
                    int[] screenSize = appiumDriverManager.getScreenSize(run.getId());
                    if (screenSize != null
                            && (action.getX() < 0 || action.getX() > screenSize[0]
                                || action.getY() < 0 || action.getY() > screenSize[1])) {
                        run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                "Hedef ekranın görünür alanının dışında kaldığı için atlandı: " + action.getReasoning()));
                        runStore.save(run);

                        AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), notFoundStreak, autoScrollAttempts);
                        if (outcome.exhausted()) {
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Element ekranda bulunamadı; ekran hem yukarı hem aşağı kaydırılarak denendi, yine de bulunamadı."));
                            run.setStatus("failed");
                            run.setError("Hedeflenen element (\"" + action.getTarget() + "\") ekranın hiçbir kaydırma konumunda bulunamadı.");
                            run.setFinishedAt(Instant.now().toString());
                            if (captureScreenshot) run.setFailureScreenshot(screenshot);
                            runStore.save(run);
                            return;
                        } else if (outcome.scrolled()) {
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
                            // Geçersiz koordinat - tıklama yapılamadı, adım FAILED
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "GEÇERSİZ KOORDİNAT (XML'de karşılığı yok), tıklama yapılamadı: " + action.getReasoning()));
                            runStore.save(run);
                            consecutiveFails++; // Başarısız sayısını artır

                            // 2 üst üste başarısız deneme → model uyarısı
                            if (consecutiveFails >= 2) {
                                System.out.println("[RUN] UYARI: " + consecutiveFails + " kez başarısız deneme. Farklı bir element veya action seçilmeli!");
                            }

                            AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), notFoundStreak, autoScrollAttempts);
                            if (outcome.exhausted()) {
                                run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                        "Element ekranda bulunamadı; ekran hem yukarı hem aşağı kaydırılarak denendi, yine de bulunamadı."));
                                run.setStatus("failed");
                                run.setError("Hedeflenen element (\"" + action.getTarget() + "\") ekranın hiçbir kaydırma konumunda bulunamadı.");
                                run.setFinishedAt(Instant.now().toString());
                                if (captureScreenshot) run.setFailureScreenshot(screenshot);
                                runStore.save(run);
                                return;
                            } else if (outcome.scrolled()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Hedeflenen element (\"" + action.getTarget() + "\") üst üste bulunamadığı için ekran otomatik "
                                                + "olarak " + turkishDirection(outcome.direction()) + " kaydırıldı."));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi üst üste bulunamadığı "
                                        + "için SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. "
                                        + "Şimdi ekrandaki YENİ XML listesine bakarak DEVAM ET. Aynı hedefi tekrar dene!";
                                // Kaydırma sonrası aynı hedefi tekrar denemek için adım sayısını artırmama
                                // (döngü devam edecek, model yeni XML'e bakarak aynı hedefi tekrar deneyecek)
                            }
                            Thread.sleep(800);
                            continue; // Aynı adımda tekrar deneme için döngüye devam
                        }
                        
                        // Koordinat geçerli - tıklama yap
                        run.getSteps().add(new RunStep(i, "tap", action.getTarget(), action.getReasoning()));
                        System.out.println("[RUN] Tap işlemi yapılıyor: " + action.getTarget() + " (x=" + action.getX() + ", y=" + action.getY() + ")");
                        
                        try {
                            appiumDriverManager.tap(run.getId(), action.getX(), action.getY());
                            notFoundStreak[0] = 0; // başarılı etkileşim -- geçmiş "bulunamadi" sayacı sıfırlanıyor
                            consecutiveFails = 0; // Başarılı işlem → başarısız sayacı sıfırla
                            
                            // Tap sonrası daha uzun bekleme - uygulamanın arka plana düşmesini önlemek için
                            System.out.println("[RUN] Tap sonrası bekleme (uygulama arka plana düşmesin diye 1.5s)...");
                            Thread.sleep(1500);
                            System.out.println("[RUN] Tap işlemi BAŞARILI: " + action.getTarget());
                        } catch (Exception tapEx) {
                            // Tap başarısız oldu
                            System.out.println("[RUN] Tap HATA: " + tapEx.getMessage());
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Tıklama başarısız: " + tapEx.getMessage()));
                            runStore.save(run);
                            consecutiveFails++; // Başarısız sayısını artır
                            
                            Thread.sleep(800);
                            continue; // Aynı hedefi tekrar deneme
                        }

                    }
                    case "type" -> {
                        if (!appiumDriverManager.isValidCoordinate(rawPageSource, action.getX(), action.getY())) {
                            // Geçersiz koordinat - yazma yapılamadı, adım FAILED
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "GEÇERSİZ KOORDİNAT, yazma yapılamadı"));
                            runStore.save(run);
                            consecutiveFails++; // Başarısız sayısını artır

                            AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), notFoundStreak, autoScrollAttempts);
                            if (outcome.exhausted()) {
                                run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                        "Element ekranda bulunamadı; ekran hem yukarı hem aşağı kaydırılarak denendi, yine de bulunamadı."));
                                run.setStatus("failed");
                                run.setError("Hedeflenen element (\"" + action.getTarget() + "\") ekranın hiçbir kaydırma konumunda bulunamadı.");
                                run.setFinishedAt(Instant.now().toString());
                                if (captureScreenshot) run.setFailureScreenshot(screenshot);
                                runStore.save(run);
                                return;
                            } else if (outcome.scrolled()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Hedeflenen element (\"" + action.getTarget() + "\") üst üste bulunamadığı için ekran otomatik "
                                                + "olarak " + turkishDirection(outcome.direction()) + " kaydırıldı."));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi üst üste bulunamadığı "
                                        + "için SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. "
                                        + "Şimdi ekrandaki YENİ XML listesine bakarak DEVAM ET. Aynı hedefi tekrar dene!";
                            }
                            Thread.sleep(800);
                            continue; // Aynı hedefi tekrar deneme
                        }
                        try {
                            run.getSteps().add(new RunStep(i, "type", action.getTarget(), action.getReasoning()));
                            appiumDriverManager.typeText(run.getId(), action.getX(), action.getY(), action.getText());
                            notFoundStreak[0] = 0; // başarılı etkileşim
                            consecutiveFails = 0; // Başarılı işlem → başarısız sayacı sıfırla
                        } catch (Exception typeEx) {
                            // Type başarısız oldu
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Yazma başarısız (odak oturmadı): " + typeEx.getMessage()));
                            runStore.save(run);
                            consecutiveFails++; // Başarısız sayısını artır
                            Thread.sleep(800);
                            continue; // Aynı hedefi tekrar deneme
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
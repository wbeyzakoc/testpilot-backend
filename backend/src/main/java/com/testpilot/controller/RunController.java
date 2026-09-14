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
import java.util.Locale;
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

    private List<String> splitExecutionSteps(String goal) {
        if (goal == null || goal.isBlank()) {
            return List.of("");
        }

        String normalized = goal.replace('\n', ' ').replace('\r', ' ');

        java.util.List<String> collected = new java.util.ArrayList<>();
        java.util.regex.Pattern sentencePattern = java.util.regex.Pattern.compile(
                "(?i)(?<=\\.)\\s+|(?<=\\!)\\s+|(?<=\\?)\\s+|\\s+(?:sonra|ve sonra|ardından|daha sonra|then|and then|after that|after)\\s+");

        String[] bySentence = sentencePattern.split(normalized);
        for (String raw : bySentence) {
            String step = raw.trim();
            if (step.isEmpty()) continue;
            step = step.replaceAll("^[\\-•*\\d.\\)\\(\\s]+", "").trim();
            if (!step.isEmpty()) {
                collected.add(step);
            }
        }

        if (collected.isEmpty()) {
            return List.of(goal.trim());
        }

        return collected;
    }

    private boolean isApplicationOpenStep(String step) {
        if (step == null) return false;
        String normalized = step.toLowerCase(Locale.ROOT);
        return normalized.contains("uygulamayı aç")
                || normalized.contains("uygulamayi ac")
                || normalized.contains("uygulamayı başlat")
                || normalized.contains("uygulamayi baslat")
                || normalized.contains("open the app")
                || normalized.contains("launch the app");
    }

    // ============================================================================================
    // ADIM NIYETI SINIFLANDIRMA (deterministik yürütme için)
    // ============================================================================================
    // Kullanıcı senaryoyu madde madde (atomik) verdiğinde, her adımın ne yapması gerektiği
    // ifadeden anlaşılabilir. Bunu LLM'in her adımda yeniden yorumlamasına bırakmak yerine,
    // adımın niyetini deterministik olarak sınıflandırıp yaygın durumları (uygulama aç, kaydır,
    // tıkla, testi bitir) DOĞRUDAN yürütüyoruz. Yalnızca gerçekten belirsiz adımlar LLM'e düşer.
    private enum StepIntent { OPEN_APP, FINISH, SCROLL_UNTIL, SCROLL_ONCE, TAP, TYPE, UNKNOWN }

    private String stripTrailingPunctuation(String s) {
        return s == null ? "" : s.replaceAll("[.!?\\s]+$", "").trim();
    }

    private StepIntent classifyStep(String step) {
        if (step == null || step.isBlank()) return StepIntent.UNKNOWN;
        String s = stripTrailingPunctuation(step.toLowerCase(Locale.ROOT));

        if (isApplicationOpenStep(step)) return StepIntent.OPEN_APP;

        boolean finish = s.contains("testi bitir") || s.contains("test bitir")
                || s.contains("testi sonlandır") || s.contains("testi sonlandir")
                || s.contains("testi tamamla") || s.contains("finish the test")
                || s.contains("end the test") || s.contains("finish test")
                || s.equals("bitir") || s.equals("bitir testi") || s.equals("done");
        if (finish) return StepIntent.FINISH;

        boolean type = s.contains("yaz") || s.contains(" gir") || s.startsWith("gir")
                || s.contains("doldur") || s.contains("type ") || s.contains("enter ")
                || s.contains("fill");
        if (type) return StepIntent.TYPE;

        boolean scroll = s.contains("kaydır") || s.contains("kaydir")
                || s.contains("scroll") || s.contains("swipe");
        if (scroll) {
            boolean untilVisible = s.contains("kadar") || s.contains("until")
                    || s.contains("görünene") || s.contains("gorunene")
                    || s.contains("bulana") || s.contains("görene") || s.contains("gorene");
            return untilVisible ? StepIntent.SCROLL_UNTIL : StepIntent.SCROLL_ONCE;
        }

        boolean tap = s.contains("tıkla") || s.contains("tikla") || s.contains("bas")
                || s.contains("seç") || s.contains("sec") || s.contains("dokun")
                || s.contains("tap") || s.contains("click") || s.contains("select")
                || s.contains("press") || s.contains("aç") || s.contains("git");
        if (tap) return StepIntent.TAP;

        return StepIntent.UNKNOWN;
    }


    private boolean isInformationalLink(String target) {
        if (target == null) return false;
        String t = target.toLowerCase();
        return INFO_LINK_KEYWORDS.stream().anyMatch(t::contains);
    }

    // ============================================================================================
    // HEDEF TAMAMLAMA KONTROLÜ (testi bitir)
    // ============================================================================================
    // Bir tap işleminden sonra ekran değiştiyse ve hedef element artık yeni ekranda
    // görünmüyorsa, bu hedefe ulaşılmış ve yeni bir ekrana geçilmiş demektir.
    // Bu durumda test tamamlanabilir (goal contains "tıkla ve testi bitir" pattern'i).
    // 
    // ÖNEMLİ: Sadece TAM EŞLEŞEN ürünler için tamamlanma kabul edilir.
    // Örn: Goal "Sauce Labs Backpack (yellow)" ise, "Sauce Labs Backpack" (renksiz)
    // veya "Sauce Labs Backpack (blue)" (yanlış renk) TAMAMLAMA sayılmaz!
    private boolean isGoalCompleted(String runId, String goal, String target, 
                                    String pageSourceBeforeTap, String pageSourceAfterTap) {
        if (target == null || pageSourceBeforeTap == null || pageSourceAfterTap == null) {
            return false;
        }
        
        // Ekran değişmediyse tamamlanma yok
        if (pageSourceBeforeTap.equals(pageSourceAfterTap)) {
            System.out.println("[GOAL-COMPLETE] Ekran değişmedi, tamamlanma yok");
            return false;
        }
        
        // Hedef element yeni ekranda yoksa -> başarıyla tıklanmış ve ekran değişmiş
        boolean targetGone = !pageSourceAfterTap.contains(target);
        
        if (!targetGone) {
            System.out.println("[GOAL-COMPLETE] Hedef hala yeni ekranda var, tamamlanma yok");
            return false;
        }
        
        // [DUZELTME 2026-09-14] MULTI-STEP SENARYO KONTROLÜ
        // Goal'da "sonra" veya "ve sonra" varsa -> bu multi-step senaryo!
        // Bu durumda SADECE SON ÜRÜN tıklandıktan sonra tamamlanma yapılır.
        boolean isMultiStep = goal != null && (goal.toLowerCase().contains("sonra") || 
                                               goal.toLowerCase().contains("ve sonra"));
        
        if (isMultiStep) {
            System.out.println("[GOAL-COMPLETE] 🔄 MULTI-STEP SENARYO TESPİT EDİLDİ");
            
            // Goal'daki SON ürünü bul (en son gelen renk/variyant)
            String lastProductInGoal = findLastProductInGoal(goal);
            System.out.println("[GOAL-COMPLETE] Son hedef ürün: '" + lastProductInGoal + "'");
            
            if (lastProductInGoal != null) {
                // Şu an tıklanan ürün, goal'daki SON ürün mü?
                if (!target.contains(lastProductInGoal)) {
                    System.out.println("[GOAL-COMPLETE] ✗ HENÜZ SON ÜRÜN TIKLANMADI!");
                    System.out.println("[GOAL-COMPLETE] Tıklanan: '" + target + "'");
                    System.out.println("[GOAL-COMPLETE] Beklenen son ürün: '" + lastProductInGoal + "'");
                    return false; // Henüz son ürüne tıklanmadı! Tamamlanma YOK!
                } else {
                    System.out.println("[GOAL-COMPLETE] ✓ SON ÜRÜN TIKLANDI: '" + lastProductInGoal + "'");
                }
            }
        }
        
        // Goal "tıkla ve testi bitir" pattern'i içeriyor mu?
        boolean goalRequiresCompletion = goal != null && goal.toLowerCase().contains("tıkla") && 
                                         (goal.toLowerCase().contains("bitir") || 
                                          goal.toLowerCase().contains("sonlandır") ||
                                          goal.toLowerCase().contains("tamamla"));
        
        // Goal "bul ve tıkla" pattern'i içeriyor mu? (ürün seçimi senaryosu)
        // [DUZELTME] Multi-step ise BU KONTROLÜ YAPMA!
        boolean goalIsProductSelection = !isMultiStep && 
                                         goal != null && 
                                         (goal.toLowerCase().contains("bul") || 
                                          goal.toLowerCase().contains("seç") ||
                                          goal.toLowerCase().contains("tap")) &&
                                         !goal.toLowerCase().contains("sonra") &&
                                         !goal.toLowerCase().contains("ve sonra");
        
        // [YENİ 2026-09-14] TAM EŞLEŞME KONTROLÜ
        // Goal'da parantez içinde renk/variyant bilgisi varsa (örn: "(yellow)"),
        // target da aynı parantezli bilgiyi içermeli. Aksi halde TAMAMLANMA sayılmaz!
        if (goal != null && goal.contains("(") && goal.contains(")")) {
            // Goal'da parantezli bilgi var - target da aynı parantezli bilgiyi içermeli
            int parenStart = goal.indexOf("(");
            int parenEnd = goal.indexOf(")");
            String goalParenContent = goal.substring(parenStart, parenEnd + 1); // "(yellow)"
            
            // Target bu parantezli bilgiyi içeriyor mu?
            boolean targetHasExactParenInfo = target != null && target.contains(goalParenContent);
            
            if (!targetHasExactParenInfo) {
                System.out.println("[GOAL-COMPLETE] ⚠ TAM EŞLEŞME YOK! Goal: '" + goal + 
                                 "', Target: '" + target + "', Eksik: '" + goalParenContent + "'");
                System.out.println("[GOAL-COMPLETE] Yanlış ürün tıklandı, tamamlanma REDDEDİLDİ");
                return false; // Yanlış ürün! Tamamlanma sayılmaz.
            } else {
                System.out.println("[GOAL-COMPLETE] ✓ TAM EŞLEŞME: '" + goalParenContent + 
                                 "' target'ta mevcut");
            }
        }
        
        boolean canComplete = targetGone && (goalRequiresCompletion || goalIsProductSelection);
        
        if (canComplete) {
            System.out.println("[GOAL-COMPLETE] ✓ Tamamlanma koşulları sağlandı");
        } else {
            System.out.println("[GOAL-COMPLETE] Tamamlanma koşulları sağlanmadı");
        }
        
        return canComplete;
    }

    // Goal'daki SON ürün adını bulur (en son gelen [product: X] veya parantezli renk bilgisi)
    private String findLastProductInGoal(String goal) {
        if (goal == null) return null;
        
        // "[product: X]" pattern'ini ara (en sonuncusunu bul)
        int lastProductIndex = goal.lastIndexOf("[product:");
        if (lastProductIndex >= 0) {
            int start = lastProductIndex + 9; // "[product: ".length()
            int end = goal.indexOf("]", start);
            if (end > start) {
                return goal.substring(start, end);
            }
        }
        
        // "[product: X]" yoksa, goal'daki SON parantezli bilgiyi bul (örn: "(orange)")
        // ve ondan ÖNCE gelen ürün ismini al
        int lastParenStart = goal.lastIndexOf("(");
        int lastParenEnd = goal.lastIndexOf(")");
        
        if (lastParenStart >= 0 && lastParenEnd > lastParenStart) {
            String parenContent = goal.substring(lastParenStart, lastParenEnd + 1); // "(orange)"
            
            // Parantezden ÖNCEki metni al
            String beforeParen = goal.substring(0, lastParenStart);
            
            // Son kelimeyi bul (ürün adı)
            String[] words = beforeParen.trim().split("\\s+");
            if (words.length > 0) {
                String productName = words[words.length - 1];
                return productName + parenContent;
            }
        }
        
        return null;
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

    private record AutoScrollOutcome(boolean scrolled, boolean exhausted, String direction, boolean effective, String newPageSource) {}

    private String turkishDirection(String direction) {
        return "down".equals(direction) ? "aşağı" : "yukarı";
    }

    // [DUZELTME 2026-09-14] Swipe gercekten ekrani degistirdi mi kontrolu + YENI PAGE SOURCE donusu.
    // ONCEKI SORUN: swipe() cagriliyordu ama ekran gercekten kaydi mi diye hic bakilmiyordu --
    // hedef bir konteynerde scroll edilemiyorsa (sabit form) ya da klavye/overlay tarafindan
    // kapatilmissa, swipe hicbir sey degistirmiyor ama sistem yine de "basariyla kaydirdim" diyip
    // ayni basarisizligi kor kor tekrarliyordu (bkz. LOGIN butonu sikayeti). Simdi swipe
    // oncesi/sonrasi sayfa kaynagi karsilastiriliyor; degismediyse effective=false donuyor.
    // YENI: Scroll sonrası YENİ page source da döndürülüyor ki arama tekrarlanabilsin.
    private AutoScrollOutcome autoScrollIfNeeded(String runId, String rawPageSourceBeforeScroll,
                                                 int[] notFoundStreak, int[] autoScrollAttempts) {
        notFoundStreak[0]++;
        if (notFoundStreak[0] < NOT_FOUND_SCROLL_THRESHOLD) {
            return new AutoScrollOutcome(false, false, null, false, rawPageSourceBeforeScroll);
        }
        if (autoScrollAttempts[0] >= MAX_AUTO_SCROLLS) {
            return new AutoScrollOutcome(false, true, null, false, rawPageSourceBeforeScroll);
        }
        
        String direction = AUTO_SCROLL_DIRECTIONS[autoScrollAttempts[0] % AUTO_SCROLL_DIRECTIONS.length];
        System.out.println("[AUTO-SCROLL] Kaydırma yapılıyor... (deneme: " + (autoScrollAttempts[0] + 1) + "/" + MAX_AUTO_SCROLLS + ", yön: " + turkishDirection(direction) + ")");
        
        appiumDriverManager.swipe(runId, direction);
        autoScrollAttempts[0]++;
        notFoundStreak[0] = 0;

        boolean effective = true;
        String newPageSource = rawPageSourceBeforeScroll;
        try {
            Thread.sleep(1000); // Ekranın yüklenmesi için bekle
            newPageSource = appiumDriverManager.getPageSource(runId);
            effective = rawPageSourceBeforeScroll == null || !rawPageSourceBeforeScroll.equals(newPageSource);
            
            if (effective) {
                System.out.println("[AUTO-SCROLL] ✓ BAŞARILI - Ekran içeriği değişti, YENİ XML alındı");
                System.out.println("[AUTO-SCROLL] Yeni XML uzunluğu: " + (newPageSource != null ? newPageSource.length() : 0) + " karakter");
            } else {
                System.out.println("[AUTO-SCROLL] ✗ BAŞARISIZ - Ekran içeriği DEĞİŞMEDİ (kaydırılamıyor olabilir)");
            }
        } catch (Exception e) {
            System.out.println("[AUTO-SCROLL] HATA: Swipe sonrası kontrol için sayfa kaynağı alınamadı: " + e.getMessage());
            effective = false;
        }

        return new AutoScrollOutcome(true, false, direction, effective, newPageSource);
    }

    // ============================================================================================
    // HEDEF-HEDEF EŞLEŞME KONTROLÜ (tam eşleşme mi?)
    // ============================================================================================
    // Goal ile ekranda bulunan elementin TAM olarak eşleşip eşleşmediğini kontrol eder.
    // Parantez içindeki renk/variyant bilgisi de dahil HER ŞEY eşleşmelidir.
    // Örn: Goal "Sauce Labs Backpack (violet)" vs Target "Sauce Labs Backpack (green)" -> false
    // Bir metindeki TÜM parantezli varyant token'larını (renk/isim) döndürür.
    // Örn: "... (yellow) ... (orange) ..." -> ["yellow", "orange"].
    // Sadece indeks olan sayısal token'lar ("(4)") ELENIR; bunlar varyant değildir.
    private java.util.List<String> extractVariantTokens(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]+)\\)").matcher(s);
        while (m.find()) {
            String token = m.group(1).trim();
            if (!token.isEmpty() && !token.matches("\\d+")) {
                out.add(token);
            }
        }
        return out;
    }

    // ============================================================================================
    // [DUZELTME 2026-09-14] ÇOK ADIMLI / ÇOK VARYANTLI HEDEF DESTEĞI
    //
    // ONCEKI SORUN: Sadece goal'daki İLK parantezi (goal.indexOf("(")) baz alıyordu.
    // "... (yellow) ... sonra ... (orange) ..." gibi ÇOK ADIMLI bir senaryoda bu, HER tap'ın
    // "(yellow)" içermesini zorluyordu; bu yüzden (a) geri dönmek için basılan navigasyon
    // butonu ("View menu") ve (b) 3. adımdaki DOĞRU "(orange)" ürünü HARD-BLOCK ile
    // engelleniyor, test 15 adımı boşa harcayıp başarısız oluyordu.
    //
    // YENİ DAVRANIŞ:
    //   1) Goal'daki TÜM varyant token'ları toplanır (yellow, orange, ...).
    //   2) Goal hiç varyant belirtmiyorsa kısıtlama yok -> geçerli.
    //   3) Hedefin kendi varyant token'ı yoksa (geri butonu, "Finish", menü gibi navigasyon/
    //      genel elementler) YANLIŞ-RENK ürünü DEĞİLDİR -> engelleme.
    //   4) Hedefin varyantı goal varyantlarından HERHANGİ biriyle eşleşiyorsa -> geçerli.
    //   5) Aksi halde (ör. goal {violet} ama target {green}) -> yanlış varyant, engelle.
    // Böylece tek-hedefli goal'lardaki yanlış-renk koruması KORUNUR, çok-adımlı goal'lar ÇALIŞIR.
    // ============================================================================================
    // Deterministik yürütme için KATI varyant kontrolü.
    // Adım bir varyant (renk/isim, ör. "(yellow)") belirtiyorsa, bulunan hedefin metni bu
    // varyantı GERÇEKTEN içermelidir. targetsExactMatch'ten farkı: varyant taşımayan bir hedef
    // (ör. renksiz "Sauce Labs Backpack") burada REDDEDILIR -- böylece kısmi eşleşen yanlış
    // ürüne deterministik tıklama yapılmaz.
    private boolean strictVariantMatch(String stepGoal, String found) {
        if (stepGoal == null || found == null) return false;
        java.util.List<String> goalVariants = extractVariantTokens(stepGoal);
        if (goalVariants.isEmpty()) {
            return true;
        }
        String f = found.toLowerCase(Locale.ROOT);
        for (String gv : goalVariants) {
            if (f.contains(gv.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean targetsExactMatch(String goal, String target) {        if (goal == null || target == null) {
            return false;
        }

        java.util.List<String> goalVariants = extractVariantTokens(goal);
        if (goalVariants.isEmpty()) {
            return true;
        }

        java.util.List<String> targetVariants = extractVariantTokens(target);
        if (targetVariants.isEmpty()) {
            // Varyant taşımayan hedef = navigasyon/genel element -> yanlış ürün değil.
            return true;
        }

        for (String tv : targetVariants) {
            for (String gv : goalVariants) {
                if (tv.equalsIgnoreCase(gv)) {
                    System.out.println("[EXACT-MATCH] ✓ Varyant eşleşti: '" + tv +
                                     "' goal varyantlarında mevcut " + goalVariants);
                    return true;
                }
            }
        }

        System.out.println("[EXACT-MATCH] ⚠ Varyant eşleşmiyor: target=" + targetVariants +
                         ", goal=" + goalVariants);
        return false;
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
        // Henüz ekran görüntüsü yoksa null döndür (frontend loading spinner gösterecek)
        // Hata yerine boş response gönder
        if (screenshot == null) {
            return Map.of("screenshot", "");
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
                    // [DUZELTME 2026-09-11] Video-benzeri akıcılık icin iyilestirme:
                    //   - Sabit "sleep sonra çek" yerine SABİT PERİYOT (fixed-rate) mantığı:
                    //     her karenin çekim süresi periyottan düşülüyor, böylece çekim
                    //     yavaşlasa bile bir sonraki kare planlanan zamana yakın gelir ve
                    //     gecikme zamanla birikmez.
                    //   - Periyot 250ms'den 120ms'ye indirildi (~8 fps) -- eski değer video
                    //     hissi vermek için çok yavaştı.
                    //   - Retry/backoff'lu takeScreenshotBase64 yerine tek denemelik
                    //     takeScreenshotQuiet kullanılıyor; bir karede hata olursa 1sn+
                    //     beklemek yerine hemen bir sonraki kare denenir.
                    final long targetPeriodMs = 120;
                    while (screenRefreshRunning.get() && !run.isStopRequested()) {
                        long frameStart = System.currentTimeMillis();
                        String freshScreenshot = appiumDriverManager.takeScreenshotQuiet(run.getId());
                        if (freshScreenshot != null) {
                            liveScreenshots.put(run.getId(), freshScreenshot);
                        }
                        long elapsed = System.currentTimeMillis() - frameStart;
                        long remaining = targetPeriodMs - elapsed;
                        if (remaining > 0) {
                            try {
                                Thread.sleep(remaining);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        // remaining <= 0 ise (çekim periyottan uzun sürdüyse) hiç
                        // beklemeden hemen bir sonraki kareye geçilir -- gecikme birikmez.
                    }
                });
                refreshThread.setDaemon(true);
                refreshThread.start();
                System.out.println("[RUN] Arka plan ekran güncelleme döngüsü başlatıldı (~120ms / 8fps hedef)");
            }

            String lastActionSignature = null;
            int repeatCount = 0;
            List<String> executionSteps = splitExecutionSteps(run.getGoal());
            int currentExecutionStep = 0;
            int lastStepIndexSeen = -1;
            int stepScrollAttempts = 0;
            int stepScrollNoChange = 0;
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
            // [DUZELTME 2026-09-11] Model hedef ekranda oldugu halde YANLIS bir elemente
            // tikladiginda bunu kac kez reddettigimizi sayar. 2. redden sonra backend
            // KENDISI dogru elemente tap eder (swipeRefusedCount ile ayni desen).
            int[] wrongTapRefusedCount = {0};
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

                String stepGoal = executionSteps.get(Math.min(currentExecutionStep, executionSteps.size() - 1));
                boolean lastExecutionStep = currentExecutionStep >= executionSteps.size() - 1;

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
                    filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource, stepGoal);
                } catch (Exception pageEx) {
                    System.out.println("Page source alınamadı, adım atlanıyor: " + pageEx.getMessage());
                    run.getSteps().add(new RunStep(i, "failed", null,
                            "UI yanıt vermiyor, sayfa kaynağı alınamadı: " + pageEx.getMessage()));
                    runStore.save(run);
                    Thread.sleep(1000);
                    continue;
                }
                System.out.println("=== FİLTRELENMİŞ XML (adım " + i + ") ===\n" + filteredPageSource);

                // ============================================================================================
                // [YENİ] DETERMİNİSTİK ADIM YÜRÜTME
                //
                // Kullanıcı senaryoyu madde madde verdiğinde her adımın niyeti (uygulama aç, kaydır,
                // tıkla, testi bitir) ifadeden anlaşılır. Bu yaygın durumları LLM'e bırakmadan doğrudan
                // ve güvenilir şekilde yürütüyoruz. Böylece "Uygulamayı aç" adımında modelin rastgele
                // tıklaması, kaydırma adımında yanlış ürüne/dialoglara basması gibi hatalar tamamen
                // ortadan kalkar. Yalnızca gerçekten belirsiz (UNKNOWN) veya metin girişi (TYPE) gibi
                // adımlar aşağıdaki LLM akışına düşer.
                // ============================================================================================
                if (currentExecutionStep != lastStepIndexSeen) {
                    lastStepIndexSeen = currentExecutionStep;
                    stepScrollAttempts = 0;
                    stepScrollNoChange = 0;
                }

                StepIntent intent = classifyStep(stepGoal);
                System.out.println("[STEP] adım " + (currentExecutionStep + 1) + "/" + executionSteps.size()
                        + " intent=" + intent + " goal='" + stepGoal + "'");

                if (intent == StepIntent.OPEN_APP) {
                    // Uygulama oturum başında zaten açıldı; bu adımı doğrudan tamamla.
                    run.getSteps().add(new RunStep(i, "wait", stepGoal,
                            "Uygulama zaten açık, adım tamamlandı."));
                    runStore.save(run);
                    if (!lastExecutionStep) currentExecutionStep++;
                    repeatCount = 0;
                    lastActionSignature = null;
                    Thread.sleep(300);
                    continue;
                }

                if (intent == StepIntent.FINISH) {
                    run.getSteps().add(new RunStep(i, "done", stepGoal, "Senaryo tamamlandı: " + stepGoal));
                    run.setStatus("passed");
                    run.setFinishedAt(Instant.now().toString());
                    runStore.save(run);
                    screenRefreshRunning.set(false);
                    System.out.println("[STEP] ✓ Testi bitir adımı ile senaryo tamamlandı");
                    return;
                }

                if (intent == StepIntent.SCROLL_UNTIL || intent == StepIntent.SCROLL_ONCE) {
                    String found = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                    boolean hasTarget = found != null && targetsExactMatch(stepGoal, found)
                            && strictVariantMatch(stepGoal, found);

                    if (hasTarget) {
                        // Hedef görünür -> kaydırma adımı tamamlandı, TIKLAMADAN sonraki adıma geç.
                        run.getSteps().add(new RunStep(i, "swipe", found,
                                "Hedef '" + found + "' ekranda görünür oldu, kaydırma adımı tamamlandı."));
                        runStore.save(run);
                        System.out.println("[STEP] ✓ Kaydırma tamamlandı, hedef bulundu: " + found);
                        if (!lastExecutionStep) currentExecutionStep++;
                        repeatCount = 0;
                        lastActionSignature = null;
                        Thread.sleep(300);
                        continue;
                    }

                    if (intent == StepIntent.SCROLL_ONCE) {
                        appiumDriverManager.swipe(run.getId(), "down");
                        run.getSteps().add(new RunStep(i, "swipe", null, "Ekran aşağı kaydırıldı."));
                        runStore.save(run);
                        if (!lastExecutionStep) currentExecutionStep++;
                        repeatCount = 0;
                        lastActionSignature = null;
                        continue;
                    }

                    // SCROLL_UNTIL: hedef görünene kadar deterministik kaydırma
                    if (stepScrollAttempts >= MAX_AUTO_SCROLLS) {
                        run.getSteps().add(new RunStep(i, "failed", null,
                                "Hedef '" + stepGoal + "' tüm kaydırma denemelerine rağmen bulunamadı."));
                        run.setStatus("failed");
                        run.setError("Kaydırma adımındaki hedef ekranda bulunamadı: " + stepGoal);
                        run.setFinishedAt(Instant.now().toString());
                        if (captureScreenshot) run.setFailureScreenshot(screenshot);
                        runStore.save(run);
                        screenRefreshRunning.set(false);
                        return;
                    }

                    String scrollDir = AUTO_SCROLL_DIRECTIONS[stepScrollAttempts % AUTO_SCROLL_DIRECTIONS.length];
                    appiumDriverManager.swipe(run.getId(), scrollDir);
                    stepScrollAttempts++;

                    String afterScroll;
                    try {
                        afterScroll = appiumDriverManager.getPageSource(run.getId());
                    } catch (Exception e) {
                        afterScroll = rawPageSource;
                    }
                    boolean changed = afterScroll != null && !afterScroll.equals(rawPageSource);
                    if (!changed) {
                        stepScrollNoChange++;
                        // İçerik iki kez değişmediyse ve karşı yöne de bakıldıysa, liste sonuna gelinmiş
                        // demektir; hedef gerçekten yok -> başarısız.
                        if (stepScrollNoChange >= 2 && stepScrollAttempts >= 2) {
                            run.getSteps().add(new RunStep(i, "failed", null,
                                    "Hedef '" + stepGoal + "' bulunamadı ve ekran daha fazla kaydırılamıyor."));
                            run.setStatus("failed");
                            run.setError("Kaydırma adımındaki hedef bulunamadı (liste sonu): " + stepGoal);
                            run.setFinishedAt(Instant.now().toString());
                            if (captureScreenshot) run.setFailureScreenshot(screenshot);
                            runStore.save(run);
                            screenRefreshRunning.set(false);
                            return;
                        }
                    } else {
                        stepScrollNoChange = 0;
                    }
                    run.getSteps().add(new RunStep(i, "swipe", null,
                            "Hedef '" + stepGoal + "' aranıyor, ekran " + turkishDirection(scrollDir) + " kaydırıldı."));
                    runStore.save(run);
                    continue;
                }

                if (intent == StepIntent.TAP) {
                    String found = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                    if (found != null && targetsExactMatch(stepGoal, found) && strictVariantMatch(stepGoal, found)) {
                        int[] center = appiumDriverManager.resolveTargetCenter(
                                run.getId(), rawPageSource, null, found, stepGoal);
                        if (center != null && appiumDriverManager.isValidCoordinate(rawPageSource, center[0], center[1])) {
                            run.getSteps().add(new RunStep(i, "tap", found, "Hedef '" + found + "' tıklandı."));
                            System.out.println("[STEP] Deterministik tap: " + found + " (x=" + center[0] + ", y=" + center[1] + ")");
                            try {
                                appiumDriverManager.tap(run.getId(), center[0], center[1]);
                                if (captureScreenshot) {
                                    try {
                                        screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                                        liveScreenshots.put(run.getId(), screenshot);
                                    } catch (Exception ignore) {}
                                }
                                Thread.sleep(1500);
                                notFoundStreak[0] = 0;
                                consecutiveFails = 0;
                                repeatCount = 0;
                                lastActionSignature = null;
                                if (!lastExecutionStep) currentExecutionStep++;
                                runStore.save(run);
                                continue;
                            } catch (Exception tapEx) {
                                System.out.println("[STEP] Deterministik tap hatası, LLM'e düşülüyor: " + tapEx.getMessage());
                            }
                        }
                    }
                    // Deterministik olarak hedef bulunamadı/çözülemedi -> LLM akışına düş.
                    System.out.println("[STEP] Deterministik tap çözülemedi, LLM akışına düşülüyor. Hedef: " + stepGoal);
                }

                if (isFirstStep) {
                    System.out.println("[RUN] İlk adım: Sayfa stabilitesi için 1 saniye ek bekleme");
                    Thread.sleep(1000);
                    isFirstStep = false;
                    try {
                        rawPageSource = appiumDriverManager.getPageSource(run.getId());
                        filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource, stepGoal);
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
                String targetOnScreen = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                if (targetOnScreen != null) {
                    String preHint = "!!! DİKKAT: Hedeflediğin element EKRANDA ZATEN GÖRÜNÜYOR: \""
                            + targetOnScreen + "\". "
                            + "Bu adımda SAKIN action=swipe döndürme -- swipe reddedilir. "
                            + "XML'de bu elementin [N] numarasını bul ve action=tap döndür. "
                            + "Eğer bu \"Product Image\" gibi genel bir etiketse, etiketin sonundaki "
                            + "\"[product: ...]\" ekinin hedefle eşleştiğinden emin ol.";
                    repeatWarning = (repeatWarning == null || repeatWarning.isBlank())
                            ? preHint
                            : (repeatWarning + "\n\n" + preHint);
                    System.out.println("[RUN] Pre-LLM hint eklendi: hedef ekranda gorunuyor -> " + targetOnScreen);
                    
                    // [YENİ 2026-09-14] TAM EŞLEŞME KONTROLÜ - PRE-LLM
                    // Hedef ekranda var AMA tam eşleşmiyor mu? (örn: goal "(violet)" ama target "(green)")
                    if (!targetsExactMatch(stepGoal, targetOnScreen)) {
                        String wrongProductHint = "!!! ÇOK ÖNEMLİ: Ekranda görünen ürün HEDEFLE TAM EŞLEŞMİYOR! "
                                + "Goal: '" + run.getGoal() + "'\n"
                                + "Found: '" + targetOnScreen + "'\n"
                                + "Bu ürüne TIKLAMA! Farklı bir renk/variyant. "
                                + "XML'de TAM OLARAK '" + stepGoal + "' içeren bir element ara. "
                                + "Eğer yoksa, action=swipe ile devam et (max 8 swipe). "
                                + "ASLA farklı ürüne tıklama veya rastgele butonlara basma!";
                        repeatWarning = (repeatWarning == null || repeatWarning.isBlank())
                                ? wrongProductHint
                                : (repeatWarning + "\n\n" + wrongProductHint);
                        System.out.println("[RUN] ⚠ YANLIŞ ÜRÜN UYARISI: goal='" + run.getGoal() + 
                                         "', found='" + targetOnScreen + "'");
                    }
                }

                AgentAction action;
                try {
                    action = llmAgent.decideNextAction(stepGoal, variables, screenshot, filteredPageSource, i, run.getSteps(), repeatWarning);
                } catch (Exception decideEx) {
                    run.getSteps().add(new RunStep(i, "failed", null, "Model kararı alınamadı, tekrar deneniyor: " + decideEx.getMessage()));
                    runStore.save(run);
                    Thread.sleep(800);
                    continue;
                }
                repeatWarning = null;

                // ============================================================================================
                // [YENİ 2026-09-14] HARD BLOCK - YANLIŞ ÜRÜN TIKLAMASINI ÖNLE
                // ============================================================================================
                // LLM yanlış ürünü seçerse (örn: goal "(violet)" ama target "(green)"),
                // bu tap işlemi ASLA uygulanmaz! Sistem hemen engeller.
                if ("tap".equals(action.getAction()) && action.getTarget() != null) {
                    if (!targetsExactMatch(stepGoal, action.getTarget())
                            || !strictVariantMatch(stepGoal, action.getTarget())) {
                        System.out.println("[RUN] 🚫 HARD BLOCK: Yanlış ürün tıklaması ENGELLENDİ!");
                        System.out.println("[RUN] 🚫 Goal: '" + run.getGoal() + "'");
                        System.out.println("[RUN] 🚫 Model'in seçtiği: '" + action.getTarget() + "'");
                        
                        run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                "SİSTEM ENGELLEDİ: Seçtiğin ürün hedefle TAM EŞLEŞMİYOR! " +
                                "Goal: '" + run.getGoal() + "'\n" +
                                "Seçtiğin: '" + action.getTarget() + "'\n" +
                                "Farklı bir renk/variyant seçtin. XML'de TAM OLARAK '" + 
                                run.getGoal() + "' içeren element bul veya yoksa action=swipe yap. " +
                                "ASLA yanlış ürüne tıklama!"));
                        runStore.save(run);
                        
                        repeatCount = 0;
                        Thread.sleep(400);
                        continue;
                    }
                }

                // ============================================================================================
                // [DUZELTME 2026-09-10] elementId validasyonu
                //
                // Qwen gibi weak modellerin en sik hatasi: XML'de 30 element varken "[200]" gibi
                // uydurma ID dondurmek. Bu durumda action'i HIC UYGULAMIYORUZ; modele acik uyari
                // verip ayni adimi tekrar denetiyoruz.
                // ============================================================================================
                if (("tap".equals(action.getAction()) || "type".equals(action.getAction()))) {
                    Integer parsedId = parseElementId(action.getElementId());
                    int maxId = appiumDriverManager.countEmittableElements(rawPageSource, stepGoal);
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

                // ============================================================================================
                // [DUZELTME 2026-09-11] GENEL "YANLIS URUN/ELEMAN SECIMI" KORUMASI
                //
                // findMatchingTarget (yukarida targetOnScreen) deterministik string eslestirmeyle
                // (renk/varyant/marka gibi AYIRT EDICI kelimelerle) hedefi ekranda ONCEDEN bulmus
                // olabilir. Ama zayif modeller bu ipucunu gormezden gelip GORSEL OLARAK benzer,
                // FARKLI bir elemente (ornegin baska renkteki urune) tiklayabiliyordu -- kullanicinin
                // bildirdigi "yellow istiyorum ama rastgele bir urun seciliyor" hatasi tam olarak bu.
                //
                // [DUZELTME 2026-09-14] ONCEDEN sadece model "[product: X]" seklinde zenginlestirilmis
                // bir gorsele tikladiginda devreye giriyordu -- ETIKETLI (duz metin) hedeflerde
                // (ör. dogrudan text="Yellow T-Shirt" olan bir eleman) HICBIR KORUMA yoktu. Artik
                // swipe-red mekanizmasiyla SIMETRIK: targetOnScreen dolu oldugu ve modelin sectigi
                // eleman FARKLI oldugu her durumda calisir -- [product: ...] etiketine bagli degildir,
                // herhangi bir uygulamada (SauceLabs'e ozel degil) genel olarak calisir.
                // ============================================================================================
                // [DUZELTME 2026-09-14] ÇOK ADIMLI GOAL: targetOnScreen goal-genelidir
                // (ör. çok adımlı senaryoda hep ilk varyant "yellow" döner). Model, ürün
                // detayından geri dönmek için navigasyon butonuna ("View menu", geri) ya da
                // sonraki adımın DOĞRU ürününe (ör. "orange") bastığında chosenLabel,
                // targetOnScreen'den FARKLIDIR ama YANLIŞ ürün DEĞİLDİR. Bu yüzden guard'ı
                // yalnızca chosenLabel GERÇEKTEN yanlış bir varyant taşıyorsa (targetsExactMatch
                // false) tetikliyoruz -- aksi halde geri navigasyonu ve sonraki adımları
                // engelleyip test'i yellow detayında kilitliyordu.
                if ("tap".equals(action.getAction()) && targetOnScreen != null) {
                    String chosenLabel = appiumDriverManager.labelForElementId(rawPageSource, action.getElementId(), stepGoal);
                    if (chosenLabel != null && !chosenLabel.equals(targetOnScreen)
                            && !targetsExactMatch(stepGoal, chosenLabel)) {
                        wrongTapRefusedCount[0]++;
                        System.out.println("[RUN] YANLIS URUN SECIMI ENGELLENDI (" + wrongTapRefusedCount[0] + ". kez): model \""
                                + chosenLabel + "\" secti ama hedefe uyan \"" + targetOnScreen + "\" ekranda mevcut");

                        if (wrongTapRefusedCount[0] >= 2) {
                            System.out.println("[RUN] Otomatik TAP (dogru urun): model israrla yanlis urune tikliyor, hedef zaten ekranda: " + targetOnScreen);
                            int[] center = appiumDriverManager.resolveTargetCenter(
                                    run.getId(), rawPageSource, null, targetOnScreen, stepGoal);
                            if (center != null) {
                                run.getSteps().add(new RunStep(i, "tap", targetOnScreen,
                                        "Sistem otomatik tap: model 2 kez yanlış ürünü seçmeye çalıştı ama doğru ürün zaten ekranda."));
                                try {
                                    appiumDriverManager.tap(run.getId(), center[0], center[1]);
                                    notFoundStreak[0] = 0;
                                    wrongTapRefusedCount[0] = 0;
                                    consecutiveFails = 0;
                                    System.out.println("[RUN] Otomatik tap (dogru urun) BAŞARILI: " + targetOnScreen
                                            + " (x=" + center[0] + ", y=" + center[1] + ")");
                                    Thread.sleep(1500);
                                    runStore.save(run);
                                    continue;
                                } catch (Exception autoTapEx) {
                                    System.out.println("[RUN] Otomatik tap (dogru urun) HATA: " + autoTapEx.getMessage());
                                }
                            }
                        }

                        run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                "Hedefteki özel niteliğe (renk/varyant/isim) uymayan bir element seçildi (\""
                                        + chosenLabel + "\"); doğru eşleşen \"" + targetOnScreen
                                        + "\" ekranda zaten görünüyor. Yeniden deneniyor."));
                        runStore.save(run);
                        repeatWarning = "!!! YANLIŞ SEÇİM ENGELLENDİ !!! \"" + chosenLabel + "\" elementini seçtin ama bu, "
                                + "hedefteki özel niteliğe (renk/varyant/isim/marka) UYMUYOR. Hedefe TAM UYAN element "
                                + "zaten ekranda: \"" + targetOnScreen + "\". XML listesinde TAM OLARAK bu etikete "
                                + "sahip [N] numarasını bul ve SADECE onu seç. Başka hiçbir elementi seçme.";
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
                    
                    // [DUZELTME 2026-09-14] MULTI-STEP SENARYOLARDA AKILLI DÖNGÜ TESPİTİ
                    // Eğer XML değiştiyse -> bu bir döngü DEĞİLDİR, ilerleme var!
                    // Sadece XML değişmediyse ve aynı elemente tekrar tıklanıyorsa -> döngü
                    if (currentSignature.equals(lastActionSignature) && !xmlChanged) {
                        repeatCount++;
                        System.out.println("[RUN] ⚠️ AYNI AKSİYON TEKRARI: repeatCount=" + repeatCount + 
                                         ", target=" + action.getTarget() + ", xmlChanged=false");
                    } else {
                        if (xmlChanged && currentSignature.equals(lastActionSignature)) {
                            System.out.println("[RUN] ✓ XML değişti, repeatCount sifirlaniyor (ilerleme var) - target: " + action.getTarget());
                        } else if (!currentSignature.equals(lastActionSignature)) {
                            System.out.println("[RUN] ✓ Farklı aksiyon, repeatCount sifirlaniyor - new target: " + action.getTarget());
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
                    int[] corrected = appiumDriverManager.resolveTargetCenter(run.getId(), rawPageSource, action.getElementId(), action.getTarget(), stepGoal);
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
                            
                            // [YENI 2026-09-14] Scroll sonrası YENİ page source ile tekrar ara!
                            rawPageSource = outcome.newPageSource(); // YENİ XML'i kullan
                            System.out.println("[AUTO-SCROLL] YENİ PAGE SOURCE ile arama tekrarlanıyor...");
                            System.out.println("[AUTO-SCROLL] Yeni XML uzunluğu: " + (rawPageSource != null ? rawPageSource.length() : 0) + " karakter");
                            
                            // Hedefi yeni ekranda tekrar ara
                            String matchingTarget = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                            if (matchingTarget != null) {
                                System.out.println("[AUTO-SCROLL] ✓ YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget);
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi ekranda görünmediği için SİSTEM ekranı otomatik olarak "
                                        + turkishDirection(outcome.direction()) + " kaydırdı. YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget
                                        + " Şimdi bu YENİ elementi hedefle!";
                            } else {
                                System.out.println("[AUTO-SCROLL] ✗ YENİ EKRANDA HEDEF BULUNAMADI, devam ediliyor...");
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi ekranda görünmediği için "
                                        + "SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. Şimdi "
                                        + "ekrandaki YENİ XML listesine bak: hedef artık görünür olabilir (aynı elementi tekrar "
                                        + "seçebilirsin) ya da farklı bir element gerekebilir.";
                            }
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
                            // Tap işlemi ÖNCESİ page source'u kaydet
                            String pageSourceBeforeTap = rawPageSource;
                            
                            appiumDriverManager.tap(run.getId(), action.getX(), action.getY());
                            notFoundStreak[0] = 0;
                            consecutiveFails = 0;
                            swipeRefusedCount[0] = 0;
                            wrongTapRefusedCount[0] = 0;

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
                            if (!lastExecutionStep) {
                                currentExecutionStep++;
                            }
                            
                            // [YENI 2026-09-14] HEDEF TAMAMLAMA KONTROLÜ
                            // Tap sonrası yeni page source al
                            String pageSourceAfterTap = appiumDriverManager.getPageSource(run.getId());
                            if (lastExecutionStep && isGoalCompleted(run.getId(), run.getGoal(), action.getTarget(),
                                              pageSourceBeforeTap, pageSourceAfterTap)) {
                                System.out.println("[RUN] ✓ HEDEF TAMAMLANDI: '" + action.getTarget() + "' tıklandı, ekran değişti ve hedef yeni ekranda yok.");
                                System.out.println("[RUN] Test başarıyla tamamlandı - action=done önerisi");
                                run.getSteps().add(new RunStep(i, "done", action.getTarget(),
                                        "Hedef element başarıyla tıklandı, ekran değişti ve test tamamlandı: " + run.getGoal()));
                                run.setStatus("completed");
                                run.setFinishedAt(Instant.now().toString());
                                runStore.save(run);
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
                                
                                // [YENI 2026-09-14] Scroll sonrası YENİ page source ile tekrar ara!
                                rawPageSource = outcome.newPageSource(); // YENİ XML'i kullan
                                System.out.println("[AUTO-SCROLL] YENİ PAGE SOURCE ile arama tekrarlanıyor...");
                                System.out.println("[AUTO-SCROLL] Yeni XML uzunluğu: " + (rawPageSource != null ? rawPageSource.length() : 0) + " karakter");
                                
                                // Hedefi yeni ekranda tekrar ara
                                String matchingTarget = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                                if (matchingTarget != null) {
                                    System.out.println("[AUTO-SCROLL] ✓ YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget);
                                    repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi üst üste bulunamadığı "
                                            + "için SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. "
                                            + "YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget + " Şimdi bu YENİ elementi hedefle!";
                                } else {
                                    System.out.println("[AUTO-SCROLL] ✗ YENİ EKRANDA HEDEF BULUNAMADI, devam ediliyor...");
                                    repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi üst üste bulunamadığı "
                                            + "için SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. "
                                            + "Şimdi ekrandaki YENİ XML listesine bakarak DEVAM ET. Aynı hedefi tekrar dene!";
                                }
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
                            if (!lastExecutionStep) {
                                currentExecutionStep++;
                            }
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
                        // [DUZELTME 2026-09-14] SWIPE SON KONTROL - TAM ÜRÜN ADI KONTROLÜ
                        //
                        // Pre-LLM hint'e ragmen model inatla swipe dondururse, burada swipe'i REDDEDIYORUZ.
                        // AMA SADECE hedef TAM ÜRÜN ADI ile eşleşiyorsa! Kısmi eşleşme varsa (örn. "Sauce Labs Backpack"
                        // ama goal "Sauce Labs Backpack (yellow)"), swipe'i REDDETME, scroll'a DEVAM ET.
                        //
                        // targetOnScreen degiskeni yukarida pre-LLM hint icin hesaplandi -- ayni XML icin
                        // findMatchingTarget'i TEKRAR cagirmiyoruz (cache).
                        // ============================================================================================
                        String matchingTarget = targetOnScreen;
                        
                        // [YENI 2026-09-14] TAM ÜRÜN ADI kontrolü yap
                        boolean isExactMatch = false;
                        if (matchingTarget != null) {
                            // Goal'dan tam ürün adını çıkar
                            String exactProductName = appiumDriverManager.extractExactProductName(stepGoal);
                            if (exactProductName != null) {
                                // Tam ürün adı varsa, matchingTarget ile TAMAMEN aynı mı kontrol et
                                isExactMatch = matchingTarget.toLowerCase(Locale.ROOT).contains(exactProductName.toLowerCase(Locale.ROOT));
                                System.out.println("[SWIPE REFUSE] Tam ürün adı: " + exactProductName);
                                System.out.println("[SWIPE REFUSE] Ekrandaki hedef: " + matchingTarget);
                                System.out.println("[SWIPE REFUSE] TAM EŞLEŞME: " + isExactMatch);
                            } else {
                                // Tam ürün adı yoksa (genel arama), normal eşleşme kabul et
                                isExactMatch = true;
                            }
                        }
                        
                        // SADECE TAM EŞLEŞME varsa swipe'ı reddet
                        if (matchingTarget != null && isExactMatch) {
                            swipeRefusedCount[0]++;

                            // 2. red: otomatik tap yedegi
                            if (swipeRefusedCount[0] >= 2) {
                                System.out.println("[RUN] Otomatik TAP: model israrla kaydiriyor, hedef zaten ekranda: " + matchingTarget);
                                int[] center = appiumDriverManager.resolveTargetCenter(
                                        run.getId(), rawPageSource, null, matchingTarget, stepGoal);
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
                                    + "\"[product: ...]\" ekinin hedefinle ESLESTIGINDEN emin ol.";
                            Thread.sleep(500);
                            continue;
                        }
                        
                        // [YENI 2026-09-14] TAM EŞLEŞME YOKSA → Scroll'a DEVAM ET
                        if (matchingTarget != null) {
                            System.out.println("[SWIPE REFUSE] Kısmi eşleşme var ama TAM ÜRÜN ADI değil, scroll'a devam et: " + matchingTarget);
                        }
                        // Normal swipe işlemine devam et (aşağıdaki kod bloğuna düş)

                        // Hedef ekranda yok -- swipe guvenli
                        swipeRefusedCount[0] = 0;
                        appiumDriverManager.swipe(run.getId(), action.getDirection());
                        
                            // Kaydırma sonrası ekran güncellemesi (arka plan döngüsü de çalışıyor)
                            Thread.sleep(1000); // Kaydırma sonrası bekleme
                        }
                    case "wait" -> {
                        Thread.sleep(1500);
                        if (isApplicationOpenStep(stepGoal) && !lastExecutionStep) {
                            currentExecutionStep++;
                        }
                    }
                    case "done" -> {
                        run.getSteps().add(new RunStep(i, "done", null, action.getReasoning()));
                        run.setStatus("passed");
                        run.setFinishedAt(Instant.now().toString());
                        runStore.save(run);
                        return;
                    }
                    case "fail" -> {
                        // [YENI 2026-09-14] ÖNCE: autoTapTarget var mı kontrol et (fail sonrası scroll ile bulunan hedef)
                        String autoTapTarget = run.getAutoTapTarget();
                        if (autoTapTarget != null) {
                            // LLM yine fail verdi ama sistem zaten scroll yapıp hedefi buldu!
                            // OTOMATIK TAP yap!
                            System.out.println("[RUN] ⚡ LLM YINE FAIL VERDİ ama autoTapTarget var: " + autoTapTarget);
                            System.out.println("[RUN] ⚡ OTOMATIK TAP yapılıyor: " + autoTapTarget);
                            
                            run.getSteps().add(new RunStep(i, "tap", autoTapTarget,
                                    "LLM tekrar 'fail' verdi ama sistem zaten scroll ile hedefi buldu: " + autoTapTarget + ". OTOMATIK TAP yapılıyor!"));
                            runStore.save(run);
                            
                            // Tap koordinatlarını bul
                            String tapTargetXml = appiumDriverManager.getPageSource(run.getId());
                            
                            // Hedef elementin koordinatlarını bul (resolveTargetCenter kullan)
                            int[] tapCoords = appiumDriverManager.resolveTargetCenter(run.getId(), tapTargetXml, null, autoTapTarget, stepGoal);
                            if (tapCoords == null) {
                                // Koordinat bulunamadı, genel tap yap
                                tapCoords = new int[]{500, 1000}; // Orta-alt ekran
                            }
                            
                            // Tap yap
                            try {
                                appiumDriverManager.tap(run.getId(), tapCoords[0], tapCoords[1]);
                                notFoundStreak[0] = 0;
                                consecutiveFails = 0;
                                swipeRefusedCount[0] = 0;
                                wrongTapRefusedCount[0] = 0;
                                
                                // Tap sonrası ekran görüntüsünü anında güncelle
                                if (captureScreenshot) {
                                    try {
                                        screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                                        liveScreenshots.put(run.getId(), screenshot);
                                        System.out.println("[RUN] Otomatik tap sonrası ekran görüntüsü güncellendi");
                                    } catch (Exception screenEx) {
                                        System.out.println("[RUN] Otomatik tap sonrası ekran görüntüsü alınamadı: " + screenEx.getMessage());
                                    }
                                }
                                
                                System.out.println("[RUN] Otomatik tap işlemi BAŞARILI: " + autoTapTarget);
                                
                                // Metadata'yı temizle
                                run.setAutoTapTarget(null);
                                run.setAutoTapReason(null);
                                runStore.save(run);
                                
                                Thread.sleep(1500);
                                repeatCount = 0;
                                lastActionSignature = null;
                                continue; // Döngüye devam et
                            } catch (Exception tapEx) {
                                System.out.println("[RUN] Otomatik tap başarısız: " + tapEx.getMessage());
                                run.getSteps().add(new RunStep(i, "failed", autoTapTarget, "Otomatik tap başarısız: " + tapEx.getMessage()));
                                runStore.save(run);
                                consecutiveFails++;
                                Thread.sleep(800);
                                continue;
                            }
                        }
                        
                        // [YENI 2026-09-14] LLM fail kararı verdi → ÖNCE otomatik scroll dene!
                        // LLM "hedef bulunamadı" diyerek fail döndürmüş olabilir, ama belki
                        // sadece ekran kaydırılmamış olabilir. Scroll sonrası tekrar ara.
                        System.out.println("[RUN] LLM 'fail' kararı verdi: " + action.getReasoning());
                        
                        AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), rawPageSource, notFoundStreak, autoScrollAttempts);
                        if (outcome.exhausted()) {
                            // Maksimum scroll'a ulaşıldı, gerçekten bulunamadı → FAIL
                            run.getSteps().add(new RunStep(i, "failed", null, 
                                    "LLM fail kararı verdi ve otomatik scroll sonrası da hedef bulunamadı: " + action.getReasoning()));
                            run.setStatus("failed");
                            run.setError("Hedeflenen element (\"" + action.getTarget() + "\") ekranın hiçbir kaydırma konumunda bulunamadı. " +
                                    "LLM fail kararı verdi ve tüm scroll denemeleri başarısız oldu.");
                            run.setFinishedAt(Instant.now().toString());
                            if (captureScreenshot) run.setFailureScreenshot(screenshot);
                            runStore.save(run);
                            screenRefreshRunning.set(false);
                            return;
                        } else if (outcome.scrolled() && outcome.effective()) {
                            // Scroll başarılı, YENİ ekranda tekrar ara
                            rawPageSource = outcome.newPageSource();
                            System.out.println("[AUTO-SCROLL] Fail sonrası scroll başarılı, YENİ ekranda tekrar aranıyor...");
                            System.out.println("[AUTO-SCROLL] Yeni XML uzunluğu: " + (rawPageSource != null ? rawPageSource.length() : 0) + " karakter");
                            
                            // Hedefi yeni ekranda tekrar ara
                            String matchingTarget = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                            if (matchingTarget != null) {
                                System.out.println("[AUTO-SCROLL] ✓ FAIL SONRASI YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget);
                                // [YENI 2026-09-14] Fail sonrası bulunan hedefi kaydet - eğer LLM yine fail verirse otomatik tap yap
                                run.setAutoTapTarget(matchingTarget);
                                run.setAutoTapReason("Fail sonrası scroll ile bulundu");
                                runStore.save(run);
                                
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "LLM fail kararı verdi ama otomatik scroll sonrası hedef YENİ EKRANDA BULUNDU: " + matchingTarget));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                // [YENI 2026-09-14] ZORUNLU TAP mesajı - LLM'ye açıkça tap yapmasını söyle
                                repeatWarning = "⚠️ KRITIK UYARI: Az önce 'fail' kararı verdin ama SİSTEM ekranı otomatik olarak " + 
                                        turkishDirection(outcome.direction()) + " kaydırdı ve YENİ EKRANDA HEDEF BULUNDU!\n\n" +
                                        "🎯 BULUNAN HEDEF: " + matchingTarget + "\n\n" +
                                        "🚨 ŞU ANDA YAPMAN GEREKEN: Bu YENİ elementi TIKLA (tap)!\n\n" +
                                        "❌ YAPMAMAN GEREKENLER:\n" +
                                        "   - 'fail' verme (zaten scroll yapıldı ve hedef bulundu!)\n" +
                                        "   - 'swipe' yapma (hedef zaten ekranda!)\n" +
                                        "   - Aynı elemente tekrar tekrar tıklama (döngüye girersin!)\n\n" +
                                        "✅ DOĞRU AKSIYON: '" + matchingTarget + "' elementine TIKLA ve testi bitir!\n\n" +
                                        "⚡ OTOMATIK TAP UYARISI: Eğer yine 'fail' verirsen, SİSTEM OTOMATIK olarak '" + matchingTarget + "' elementine TIKLAYACAK ve testi SÜRECEK!";
                                consecutiveFails = 0; // Fail sayacını sıfırla
                                Thread.sleep(800);
                                continue; // Döngüye devam et, yeni ekranda karar ver
                            } else {
                                System.out.println("[AUTO-SCROLL] ✗ FAIL SONRASI YENİ EKRANDA HEDEF BULUNAMADI");
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "LLM fail kararı verdi ve otomatik scroll sonrası da hedef bulunamadı, tekrar deneniyor..."));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                repeatWarning = "Az önce 'fail' kararı verdin ve SİSTEM ekranı otomatik olarak " + 
                                        turkishDirection(outcome.direction()) + " kaydırdı. AMA hedef YENİ EKRANDA DA BULUNAMADI. " + 
                                        "Tekrar düşün: gerçekten fail mi vermeli, yoksa ekranda başka bir element mi hedeflemelisin? " + 
                                        "Aynı 'fail' kararını VERME, farklı bir aksiyon dene!";
                                consecutiveFails = 0; // Fail sayacını sıfırla
                                Thread.sleep(800);
                                continue; // Döngüye devam et
                            }
                        } else if (outcome.scrolled()) {
                            // Scroll yapıldı ama ekran değişmedi
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "LLM fail kararı verdi ve scroll denendi ama ekran İÇERİĞİ DEĞİŞMEDİ (kaydırılamıyor olabilir)."));
                            runStore.save(run);
                            repeatWarning = "Az önce 'fail' kararı verdin ve scroll denendi ama ekran hiç değişmedi -- bu ekran " +
                                    "muhtemelen kaydırılamıyor (sabit bir form). 'fail' verme, bunun yerine: " +
                                    "(a) ekranda ŞU AN GÖRÜNEN farklı bir elementle devam etmeyi dene, (b) bir klavye/popup açık olabilir, " +
                                    "onu kapatmayı dene, (c) gerçekten farklı bir yöne kaydırmayı dene. AYNı 'fail' kararını VERME!";
                            consecutiveFails = 0;
                            Thread.sleep(800);
                            continue;
                        } else {
                            // Scroll yapılamadı
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "LLM fail kararı verdi ama scroll yapılamadı (max scroll'a ulaşılmış olabilir)."));
                            runStore.save(run);
                            repeatWarning = "Az önce 'fail' kararı verdin ama sistem ekranı kaydıramadı (muhtemelen max scroll'a ulaşılmış). " +
                                    "'fail' verme, bunun yerine ekranda ŞU AN GÖRÜNEN farklı bir elementle devam etmeyi dene!";
                            consecutiveFails = 0;
                            Thread.sleep(800);
                            continue;
                        }
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
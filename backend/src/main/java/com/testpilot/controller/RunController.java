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

    private static final List<String> INFO_LINK_KEYWORDS = List.of(
            "learn more", "daha fazla bilgi", "hakkında", "detaylar", "more info", "öğren", "about"
    );

    // Product Tour / Onboarding Tour overlay tespit ve atlama için keyword'ler
    private static final List<String> TOUR_SKIP_KEYWORDS = List.of(
            // İngilizce
            "skip", "next", "got it", "continue", "start", "let's go", "let us go", 
            "get started", "begin", "finish", "done", "close", "dismiss",
            // Türkçe
            "atla", "ileri", "devam", "anladım", "anladim", "başla", "basla",
            "kapat", "tamam", "hadi başlayalım", "hadi baslayalım"
    );

    private static final List<String> TOUR_INDICATOR_KEYWORDS = List.of(
            // İngilizce
            "tour", "onboarding", "welcome", "getting started", "introduction", 
            "step", "slide", "carousel",
            // Türkçe
            "tur", "hoş geldiniz", "hos geldiniz", "karşılama", "karsılama",
            "tanıtım", "tanitim", "giriş", "giris"
    );

    // ============================================================================================
    // TEMEL DTO / REST ENDPOINT'LER
    // ============================================================================================

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
    // ADIM NIYETI SINIFLANDIRMA
    // ============================================================================================
    private enum StepIntent { OPEN_APP, FINISH, SCROLL_UNTIL, SCROLL_ONCE, TAP, TYPE, UNKNOWN }

    private String stripTrailingPunctuation(String s) {
        return s == null ? "" : s.replaceAll("[.!?\\s]+$", "").trim();
    }

    private StepIntent classifyStep(String step) {
        if (step == null || step.isBlank()) return StepIntent.UNKNOWN;
        
        // ÖNCE: Product Tour / Onboarding overlay kontrolü (runId buradan geçmiyor, farklı yerde çağrılacak)
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
    // HEDEF TAMAMLAMA KONTROLÜ
    // ============================================================================================
    private boolean isGoalCompleted(String runId, String goal, String target,
                                    String pageSourceBeforeTap, String pageSourceAfterTap) {
        if (target == null || pageSourceBeforeTap == null || pageSourceAfterTap == null) {
            return false;
        }

        if (pageSourceBeforeTap.equals(pageSourceAfterTap)) {
            System.out.println("[GOAL-COMPLETE] Ekran değişmedi, tamamlanma yok");
            return false;
        }

        boolean targetGone = !pageSourceAfterTap.contains(target);

        if (!targetGone) {
            System.out.println("[GOAL-COMPLETE] Hedef hala yeni ekranda var, tamamlanma yok");
            return false;
        }

        boolean isMultiStep = goal != null && (goal.toLowerCase().contains("sonra") ||
                goal.toLowerCase().contains("ve sonra"));

        if (isMultiStep) {
            System.out.println("[GOAL-COMPLETE] 🔄 MULTI-STEP SENARYO TESPİT EDİLDİ");

            String lastProductInGoal = findLastProductInGoal(goal);
            System.out.println("[GOAL-COMPLETE] Son hedef ürün: '" + lastProductInGoal + "'");

            if (lastProductInGoal != null) {
                if (!target.contains(lastProductInGoal)) {
                    System.out.println("[GOAL-COMPLETE] ✗ HENÜZ SON ÜRÜN TIKLANMADI!");
                    System.out.println("[GOAL-COMPLETE] Tıklanan: '" + target + "'");
                    System.out.println("[GOAL-COMPLETE] Beklenen son ürün: '" + lastProductInGoal + "'");
                    return false;
                } else {
                    System.out.println("[GOAL-COMPLETE] ✓ SON ÜRÜN TIKLANDI: '" + lastProductInGoal + "'");
                }
            }
        }

        boolean goalRequiresCompletion = goal != null && goal.toLowerCase().contains("tıkla") &&
                (goal.toLowerCase().contains("bitir") ||
                        goal.toLowerCase().contains("sonlandır") ||
                        goal.toLowerCase().contains("tamamla"));

        boolean goalIsProductSelection = !isMultiStep &&
                goal != null &&
                (goal.toLowerCase().contains("bul") ||
                        goal.toLowerCase().contains("seç") ||
                        goal.toLowerCase().contains("tap")) &&
                !goal.toLowerCase().contains("sonra") &&
                !goal.toLowerCase().contains("ve sonra");

        if (goal != null && goal.contains("(") && goal.contains(")")) {
            int parenStart = goal.indexOf("(");
            int parenEnd = goal.indexOf(")");
            String goalParenContent = goal.substring(parenStart, parenEnd + 1);

            boolean targetHasExactParenInfo = target != null && target.contains(goalParenContent);

            if (!targetHasExactParenInfo) {
                System.out.println("[GOAL-COMPLETE] ⚠ TAM EŞLEŞME YOK! Goal: '" + goal +
                        "', Target: '" + target + "', Eksik: '" + goalParenContent + "'");
                System.out.println("[GOAL-COMPLETE] Yanlış ürün tıklandı, tamamlanma REDDEDİLDİ");
                return false;
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

    private String findLastProductInGoal(String goal) {
        if (goal == null) return null;

        int lastProductIndex = goal.lastIndexOf("[product:");
        if (lastProductIndex >= 0) {
            int start = lastProductIndex + 9;
            int end = goal.indexOf("]", start);
            if (end > start) {
                return goal.substring(start, end);
            }
        }

        int lastParenStart = goal.lastIndexOf("(");
        int lastParenEnd = goal.lastIndexOf(")");

        if (lastParenStart >= 0 && lastParenEnd > lastParenStart) {
            String parenContent = goal.substring(lastParenStart, lastParenEnd + 1);

            String beforeParen = goal.substring(0, lastParenStart);

            String[] words = beforeParen.trim().split("\\s+");
            if (words.length > 0) {
                String productName = words[words.length - 1];
                return productName + parenContent;
            }
        }

        return null;
    }

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
    // OTOMATIK KAYDIRMA
    // ============================================================================================
    private static final int NOT_FOUND_SCROLL_THRESHOLD = 1;
    private static final int MAX_AUTO_SCROLLS = 8;
    private static final String[] AUTO_SCROLL_DIRECTIONS = {"down", "down", "down", "down", "down", "down", "down", "up"};
    private static final int POST_SCROLL_RESCAN_ATTEMPTS = 3;
    private static final int POST_SCROLL_RESCAN_DELAY_MS = 250;

    private record AutoScrollOutcome(boolean scrolled, boolean exhausted, String direction, boolean effective, String newPageSource) {}
    private record TargetProbe(String found, int[] center, boolean hasTarget, boolean interactable, String pageSource) {}

    private String turkishDirection(String direction) {
        return "down".equals(direction) ? "aşağı" : "yukarı";
    }

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
            Thread.sleep(1000);
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
    // probeTargetOnPage -- [TAMAMEN YENİDEN YAZILDI 2026-09-15]
    //
    // Bir sayfada stepGoal'a uyan hedefi arar. Şu bilgileri döner:
    //   - found: bulunan elementin etiketi (null ise bulunamadı)
    //   - center: hedefin tap koordinatı (null ise hesaplanamadı)
    //   - hasTarget: metin/marka/varyant eşleşmesi tam mı?
    //   - interactable: bulundu VE aktif VE koordinat geçerli mi?
    //   - pageSource: kullanılan XML (üst katmanın tekrar kullanması için)
    //
    // ÖNCEKİ SORUNLAR:
    //   1. Devre dışı kontrolü hasTarget=true dönerken center'ı null bırakıyordu, bu da
    //      üst katmanın "hedef bulundu ama etkileşilemez" durumunu yanlış yorumlamasına
    //      ve gereksiz scroll fallback'ine düşmesine yol açıyordu.
    //   2. Locator bilgisi loglanmıyordu, debug zordu.
    //   3. resolveTargetCenter null dönerse bile hasTarget=true kalıyordu, ama
    //      interactable=false oluyordu. Bu doğru davranış ama log eksikti.
    //
    // YENİ DAVRANIŞ:
    //   - Devre dışı element için hasTarget=true, interactable=false, center=null.
    //   - Her durumda locator bilgisi loglanır.
    //   - Koordinat çözülemezse neden çözülemediği loglanır.
    // ============================================================================================
    private TargetProbe probeTargetOnPage(String runId, String pageSource, String stepGoal) {
        if (pageSource == null || stepGoal == null || stepGoal.isBlank()) {
            return new TargetProbe(null, null, false, false, pageSource);
        }

        // ---- 1. Deterministik eşleştirme ----
        String found = appiumDriverManager.findMatchingTarget(pageSource, stepGoal);

        // ---- 2. Tam eşleşme kontrolleri (varyant / ürün adı) ----
        boolean hasTarget = found != null
                && targetsExactMatch(stepGoal, found)
                && strictVariantMatch(stepGoal, found)
                && exactProductPhraseMatch(stepGoal, found);

        if (!hasTarget) {
            // Bulundu ama tam eşleşmedi (yanlış varyant olabilir) → detaylı log
            if (found != null) {
                System.out.println("[probeTarget] ⚠ Kısmi eşleşme: '" + found
                        + "' (goal='" + stepGoal + "') — tam eşleşme YOK");
            } else {
                System.out.println("[probeTarget] Hedef bulunamadı: '" + stepGoal + "'");
            }
            return new TargetProbe(found, null, false, false, pageSource);
        }

        // ---- 3. Locator bilgisini çıkar ve logla (hata ayıklama için çok faydalı) ----
        AppiumDriverManager.ElementLocator locator =
                appiumDriverManager.findElementLocatorByLabel(pageSource, found, stepGoal);
        if (locator != null) {
            System.out.println("[probeTarget] 📍 Hedef bulundu: \"" + found + "\"");
            System.out.println("[probeTarget]    " + appiumDriverManager.formatLocatorForLog(locator));
        } else {
            System.out.println("[probeTarget] 📍 Hedef bulundu: \"" + found
                    + "\" (locator detayı çıkarılamadı)");
        }

        // ---- 4. Devre dışı (enabled=false) kontrolü ----
        // [DUZELTME 2026-09-15] Devre dışı element etkileşilebilir DEĞİLDİR.
        // Üst katman (RunController) bu bilgiye göre scroll fallback'i atlayacak.
        if (appiumDriverManager.isTargetDisabled(pageSource, found)) {
            System.out.println("[probeTarget] ⚠ Hedef '" + found + "' DEVRE DIŞI (enabled=false)");
            System.out.println("[probeTarget]    → Etkileşilebilir değil, tap denemesi YAPILMAMALI");
            return new TargetProbe(found, null, true, false, pageSource);
        }

        // ---- 5. Koordinat çözümleme ----
        // resolveTargetCenter 5 kademeli fallback kullanır (elementId, tam eşleşme,
        // kısmi eşleşme, canlı XPath, canlı XML). Hata durumunda null döner.
        int[] center = appiumDriverManager.resolveTargetCenter(
                runId, pageSource, null, found, stepGoal);

        if (center == null) {
            System.out.println("[probeTarget] ⚠ Hedef '" + found
                    + "' bulundu ama koordinat ÇÖZÜLEMEDİ (resolveTargetCenter null döndü)");
            return new TargetProbe(found, null, true, false, pageSource);
        }

        // ---- 6. Koordinat geçerlilik kontrolleri ----
        boolean isValidCoord = appiumDriverManager.isValidCoordinate(pageSource, center[0], center[1]);
        boolean isOnViewport = appiumDriverManager.isCoordinateOnViewport(runId, center[0], center[1]);
        boolean interactable = isValidCoord && isOnViewport;

        if (!interactable) {
            System.out.println("[probeTarget] ⚠ Hedef '" + found + "' koordinatı ("
                    + center[0] + "," + center[1] + ") GEÇERSİZ:"
                    + " isValidCoordinate=" + isValidCoord
                    + ", isCoordinateOnViewport=" + isOnViewport);
        } else {
            System.out.println("[probeTarget] ✓ Hedef '" + found + "' etkileşilebilir"
                    + " (koordinat: " + center[0] + "," + center[1] + ")");
        }

        return new TargetProbe(found, center, true, interactable, pageSource);
    }

    private TargetProbe rescanAfterScroll(String runId, String stepGoal, String initialPageSource) {
        TargetProbe latestProbe = probeTargetOnPage(runId, initialPageSource, stepGoal);
        if (latestProbe.interactable()) return latestProbe;

        String latestPageSource = initialPageSource;
        for (int attempt = 1; attempt <= POST_SCROLL_RESCAN_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POST_SCROLL_RESCAN_DELAY_MS);
                latestPageSource = appiumDriverManager.getPageSource(runId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                // son bilinen kaynakla devam
            }
            latestProbe = probeTargetOnPage(runId, latestPageSource, stepGoal);
            if (latestProbe.interactable()) {
                System.out.println("[STEP] ✓ Scroll sonrası detaylı taramada hedef etkileşilebilir bulundu: " + latestProbe.found());
                return latestProbe;
            }
        }
        return latestProbe;
    }

    // ============================================================================================
    // HEDEF-HEDEF EŞLEŞME KONTROLÜ
    // ============================================================================================
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

    private String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zçğıöşü0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean exactProductPhraseMatch(String stepGoal, String found) {
        if (stepGoal == null || found == null) return false;
        String exact = appiumDriverManager.extractExactProductName(stepGoal);
        if (exact == null || exact.isBlank()) {
            return true;
        }

        String exactNorm = normalizeForMatch(exact);
        String foundNorm = normalizeForMatch(found);
        if (exactNorm.isBlank() || foundNorm.isBlank()) {
            return false;
        }
        if (foundNorm.contains(exactNorm)) {
            return true;
        }

        java.util.LinkedHashSet<String> required = new java.util.LinkedHashSet<>();
        for (String w : exactNorm.split("\\s+")) {
            if (w.length() >= 3) required.add(w);
        }
        if (required.isEmpty()) return false;
        for (String w : required) {
            if (!foundNorm.contains(w)) {
                System.out.println("[EXACT-MATCH] ⚠ Ürün adı eşleşmiyor: missing='" + w
                        + "', exact='" + exact + "', found='" + found + "'");
                return false;
            }
        }
        return true;
    }

    private boolean targetsExactMatch(String goal, String target) {
        if (goal == null || target == null) {
            return false;
        }

        java.util.List<String> goalVariants = extractVariantTokens(goal);
        if (goalVariants.isEmpty()) {
            return true;
        }

        java.util.List<String> targetVariants = extractVariantTokens(target);
        if (targetVariants.isEmpty()) {
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

    // ============================================================================================
    // [YENİ 2026-09-15] SİSTEM İZİN DİYALOĞU OTOMATİK YÖNETİMİ
    //
    // Uygulama senaryo sırasında aniden native izin diyaloğu açabilir. Bu diyaloglar
    // modal katman olduğundan arkadaki element arama/tap işlemleri bloklanır. Bu
    // helper, art arda açılan diyalogları (konum + bildirim + kişiler gibi) temizler.
    // ============================================================================================
    private String handleSystemDialogsIfAny(String runId, String rawPageSource, String context) {
        String currentPageSource = rawPageSource;
        int maxDialogs = 5; // güvenlik: sonsuz döngüye girmesin

        for (int i = 0; i < maxDialogs; i++) {
            AppiumDriverManager.PermissionDialogResult result =
                    appiumDriverManager.tryHandleSystemPermissionDialog(runId, currentPageSource);

            if (!result.handled()) {
                break;
            }

            System.out.println("[RUN] 🔔 Sistem izin diyaloğu otomatik yanıtlandı (" + context
                    + "): '" + result.buttonClicked() + "'"
                    + (result.dialogTitle() != null ? " — \"" + result.dialogTitle() + "\"" : ""));

            currentPageSource = result.newPageSource();
        }
        return currentPageSource;
    }

    // ============================================================================================
    // [YENİ 2026-09-15] PRODUCT TOUR / ONBOARDING TOUR OVERLAY KONTROLÜ
    //
    // Ekranda Product Tour / Onboarding Tour overlay varsa otomatik olarak tespit eder
    // ve "Skip", "Next", "Got it" gibi butonları tıklar veya ekrana tıklar.
    // Senaryodan bağımsız olarak her adımda kontrol edilir.
    // ============================================================================================
    private void handleTourOverlay(String runId, String rawPageSource) {
        try {
            // 1. Tour overlay var mı kontrol et
            List<String> allElements = appiumDriverManager.parseAllElementLabels(rawPageSource);
            
            boolean hasTourIndicator = allElements.stream()
                    .anyMatch(e -> TOUR_INDICATOR_KEYWORDS.stream()
                            .anyMatch(keyword -> e.toLowerCase(Locale.ROOT).contains(keyword)));
            
            if (!hasTourIndicator) {
                // Tour indicator yok, kontrol etmeye gerek yok
                return;
            }
            
            System.out.println("[TOUR] 🎯 Product Tour / Onboarding overlay tespit edildi!");
            System.out.println("[TOUR]   Ekrandaki elementler: " + allElements);
            
            // 2. Skip/Next butonu var mı ara
            for (String skipKeyword : TOUR_SKIP_KEYWORDS) {
                String finalSkipKeyword = skipKeyword;
                boolean hasSkipButton = allElements.stream()
                        .anyMatch(e -> e.toLowerCase(Locale.ROOT).contains(finalSkipKeyword));
                
                if (hasSkipButton) {
                    System.out.println("[TOUR] ✓ Skip/Next butonu bulundu: \"" + skipKeyword + "\"");
                    
                    // Butonu bul ve tıkla
                    AppiumDriverManager.ElementLocator locator =
                            appiumDriverManager.findElementLocatorByLabel(rawPageSource, skipKeyword, "tour skip");
                    
                    if (locator != null) {
                        int[] center = appiumDriverManager.resolveTargetCenter(
                                runId, rawPageSource, null, skipKeyword, "tour skip");
                        
                        if (center != null) {
                            System.out.println("[TOUR] ✓ Koordinat çözüldü: (" + center[0] + "," + center[1] + ")");
                            try {
                                appiumDriverManager.tap(runId, center[0], center[1]);
                                System.out.println("[TOUR] ✓ Tour overlay butonu tıklandı: \"" + skipKeyword + "\"");
                                Thread.sleep(500);
                                return; // Tour kapatıldı, çık
                            } catch (Exception tapEx) {
                                System.out.println("[TOUR] ✗ Tap başarısız: " + tapEx.getMessage());
                            }
                        }
                    }
                }
            }
            
            // 3. Skip butonu bulunamadı, ekranın ortasına tıkla (genellikle "Next" işlevi görür)
            System.out.println("[TOUR] ⚠ Skip butonu bulunamadı, ekranın ortasına tıklanıyor...");
            int[] screenSize = appiumDriverManager.getScreenSize(runId);
            if (screenSize != null) {
                int centerX = screenSize[0] / 2;
                int centerY = screenSize[1] / 2;
                System.out.println("[TOUR] ✓ Ekran ortası: (" + centerX + "," + centerY + ")");
                try {
                    appiumDriverManager.tap(runId, centerX, centerY);
                    System.out.println("[TOUR] ✓ Ekran ortasına tıklandı (tour atlama)");
                    Thread.sleep(500);
                } catch (Exception tapEx) {
                    System.out.println("[TOUR] ✗ Ekran ortası tıklama başarısız: " + tapEx.getMessage());
                }
            }
            
        } catch (Exception ex) {
            System.out.println("[TOUR] ✗ Tour overlay kontrolü başarısız: " + ex.getMessage());
        }
    }

    // ============================================================================================
    // [YENİ 2026-09-15] EKRAN ANALİZİ FALLBACK
    //
    // Senaryo eksik/belirsiz olduğunda ya da bir adımda takıldığımızda çağrılır.
    // LLM'e "bu ekranı analiz et ve hedefe göre sıradaki mantıklı adımı öner" der.
    // Önerilen aksiyon doğrudan uygulanır.
    //
    // ADIMI İLERLETMEZ -- sadece tek bir sub-aksiyon yapar. Bir sonraki iterasyon
    // güncel ekranla tekrar değerlendirir.
    // ============================================================================================
    private boolean tryScreenAnalysisFallback(Run run, String stepGoal, String rawPageSource,
                                              int stepIndex, int[] analysisUsageCounter) {
        // Per-run limit
        if (analysisUsageCounter[0] >= 5) {
            System.out.println("[ANALYZE] Limit doldu (5/5), bu adım için analiz yapılmıyor.");
            return false;
        }
        analysisUsageCounter[0]++;

        System.out.println("[ANALYZE] 🧠 Ekran analizi başlatılıyor (adım " + stepIndex
                + ", kullanım: " + analysisUsageCounter[0] + "/5)...");

        LlmAgent.ScreenAnalysisResult analysis;
        try {
            analysis = llmAgent.analyzeScreenAndPlanNextAction(
                    run.getGoal(), stepGoal, rawPageSource, run.getSteps());
        } catch (Exception analyzeEx) {
            System.out.println("[ANALYZE] ✗ Analiz başarısız: " + analyzeEx.getMessage());
            return false;
        }

        if (analysis == null || analysis.suggestedAction() == null) {
            System.out.println("[ANALYZE] ✗ Analiz boş döndü");
            return false;
        }

        System.out.println("[ANALYZE] 📱 Ekran: \"" + analysis.screenName() + "\"");
        System.out.println("[ANALYZE] 🎯 Amaç: " + analysis.screenPurpose());
        System.out.println("[ANALYZE] 🔧 Mevcut aksiyonlar: " + analysis.availableActions());
        System.out.println("[ANALYZE] 📊 Güven: " + analysis.confidence());

        AgentAction suggested = analysis.suggestedAction();
        String actionType = suggested.getAction() == null ? "" : suggested.getAction().toLowerCase(Locale.ROOT);
        System.out.println("[ANALYZE] ➡️  Önerilen: " + actionType + " → \"" + suggested.getTarget()
                + "\" (" + suggested.getReasoning() + ")");

        if (actionType.isBlank() || "fail".equals(actionType)) {
            System.out.println("[ANALYZE] ✗ Analiz 'fail'/boş önerdi, fallback kullanılmıyor.");
            return false;
        }

        if ("done".equals(actionType)) {
            System.out.println("[ANALYZE] ✓ Analiz hedefe ulaşıldığını düşünüyor → test bitiriliyor");
            run.getSteps().add(new RunStep(stepIndex, "done", null,
                    "Ekran analizi hedefe ulaşıldığını tespit etti: " + analysis.screenPurpose()));
            run.setStatus("passed");
            run.setFinishedAt(Instant.now().toString());
            runStore.save(run);
            return true;
        }

        try {
            switch (actionType) {
                case "tap" -> {
                    String target = suggested.getTarget();
                    if (target == null || target.isBlank()) {
                        System.out.println("[ANALYZE] ✗ Tap için target boş.");
                        return false;
                    }
                    int[] coords = appiumDriverManager.resolveTargetCenter(
                            run.getId(), rawPageSource, suggested.getElementId(), target, stepGoal);
                    if (coords == null) {
                        System.out.println("[ANALYZE] ✗ '" + target + "' için koordinat çözülemedi.");
                        return false;
                    }
                    run.getSteps().add(new RunStep(stepIndex, "tap", target,
                            "[Analiz] " + suggested.getReasoning()));
                    appiumDriverManager.tap(run.getId(), coords[0], coords[1]);
                    Thread.sleep(1500);
                    System.out.println("[ANALYZE] ✓ Analiz tap başarılı: \"" + target
                            + "\" (x=" + coords[0] + ", y=" + coords[1] + ")");
                    runStore.save(run);
                    return true;
                }
                case "type" -> {
                    String target = suggested.getTarget();
                    String text = suggested.getText();
                    if (target == null || target.isBlank() || text == null || text.isBlank()) {
                        System.out.println("[ANALYZE] ✗ Type için target/text boş.");
                        return false;
                    }
                    int[] coords = appiumDriverManager.resolveTargetCenter(
                            run.getId(), rawPageSource, suggested.getElementId(), target, stepGoal);
                    if (coords == null) {
                        System.out.println("[ANALYZE] ✗ '" + target + "' için koordinat çözülemedi.");
                        return false;
                    }
                    run.getSteps().add(new RunStep(stepIndex, "type", target,
                            "[Analiz] " + suggested.getReasoning()));
                    appiumDriverManager.typeText(run.getId(), coords[0], coords[1], text);
                    Thread.sleep(1000);
                    System.out.println("[ANALYZE] ✓ Analiz type başarılı: \"" + target
                            + "\" ← \"" + text + "\"");
                    runStore.save(run);
                    return true;
                }
                case "swipe" -> {
                    String dir = suggested.getDirection();
                    if (dir == null || dir.isBlank()) dir = "down";
                    run.getSteps().add(new RunStep(stepIndex, "swipe", null,
                            "[Analiz] " + suggested.getReasoning()));
                    appiumDriverManager.swipe(run.getId(), dir);
                    Thread.sleep(1000);
                    System.out.println("[ANALYZE] ✓ Analiz swipe başarılı: " + dir);
                    runStore.save(run);
                    return true;
                }
                case "wait" -> {
                    run.getSteps().add(new RunStep(stepIndex, "wait", null,
                            "[Analiz] " + suggested.getReasoning()));
                    Thread.sleep(2000);
                    System.out.println("[ANALYZE] ✓ Analiz wait başarılı");
                    runStore.save(run);
                    return true;
                }
                default -> {
                    System.out.println("[ANALYZE] ✗ Bilinmeyen aksiyon tipi: " + actionType);
                    return false;
                }
            }
        } catch (Exception execEx) {
            System.out.println("[ANALYZE] ✗ Önerilen aksiyon uygulanamadı: " + execEx.getMessage());
            return false;
        }
    }

    // ============================================================================================
    // ANA ÇALIŞTIRMA DÖNGÜSÜ
    // ============================================================================================
    private void executeRun(Run run, Map<String, String> variables, String platform, String appPackage, String appActivity, boolean captureScreenshot, boolean recordVideo, boolean parallel) {
        int consecutiveFails = 0;
        String screenshot = null;
        Integer configuredMaxSteps = appSettingsService.getOrCreate().getMaxSteps();
        int maxSteps = (configuredMaxSteps != null && configuredMaxSteps > 0) ? configuredMaxSteps : 15;

        AtomicBoolean screenRefreshRunning = new AtomicBoolean(false);

        try {
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

            Thread.sleep(2000);
            if (recordVideo) {
                appiumDriverManager.startScreenRecording(run.getId());
            }
            if (captureScreenshot) {
                screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                liveScreenshots.put(run.getId(), screenshot);
            }

            // Arka plan ekran güncelleme döngüsü
            if (captureScreenshot) {
                screenRefreshRunning.set(true);
                Thread refreshThread = new Thread(() -> {
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
            String lastPageSourceHash = null;

            int[] notFoundStreak = {0};
            int[] autoScrollAttempts = {0};
            int[] swipeRefusedCount = {0};
            int[] wrongTapRefusedCount = {0};
            // [YENİ 2026-09-15] Ekran analizi fallback sayacı -- run başına en fazla 5
            int[] analysisUsageCounter = {0};
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

                if (currentExecutionStep >= executionSteps.size()) {
                    run.getSteps().add(new RunStep(i, "done", null,
                            "Senaryo adımları tamamlandı, test otomatik bitirildi."));
                    run.setStatus("passed");
                    run.setFinishedAt(Instant.now().toString());
                    runStore.save(run);
                    screenRefreshRunning.set(false);
                    System.out.println("[STEP] ✓ Tüm adımlar tamamlandı, test otomatik bitirildi");
                    return;
                }

                String stepGoal = executionSteps.get(Math.min(currentExecutionStep, executionSteps.size() - 1));
                boolean lastExecutionStep = currentExecutionStep >= executionSteps.size() - 1;

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
                    // [YENİ 2026-09-15] Adım başında önceki adımdan kalan ya da aniden çıkan
                    // sistem izin diyaloğunu temizle.
                    rawPageSource = handleSystemDialogsIfAny(run.getId(), rawPageSource, "adım başı");
                    
                    // [YENİ 2026-09-15] Product Tour / Onboarding Tour overlay kontrolü
                    handleTourOverlay(run.getId(), rawPageSource);
                    
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

                if (currentExecutionStep != lastStepIndexSeen) {
                    lastStepIndexSeen = currentExecutionStep;
                    stepScrollAttempts = 0;
                    stepScrollNoChange = 0;
                }

                StepIntent intent = classifyStep(stepGoal);
                System.out.println("[STEP] adım " + (currentExecutionStep + 1) + "/" + executionSteps.size()
                        + " intent=" + intent + " goal='" + stepGoal + "'");

                if (intent == StepIntent.OPEN_APP) {
                    run.getSteps().add(new RunStep(i, "wait", stepGoal,
                            "Uygulama zaten açık, adım tamamlandı."));
                    runStore.save(run);
                    currentExecutionStep++;
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

                // ============================================================================================
                // SCROLL_UNTIL / SCROLL_ONCE
                // ============================================================================================
                if (intent == StepIntent.SCROLL_UNTIL || intent == StepIntent.SCROLL_ONCE) {

                    // [DUZELTME 2026-09-15] "Aşağı kaydır." gibi KOMUT adımlarında hedef
                    // arama yapmak yanlış-pozitif üretiyordu ("Aşağıdaki..." metni "aşağı"
                    // kelimesini içerdiği için adım boşa tamamlanıyor, swipe hiç yapılmıyordu).
                    // SCROLL_ONCE artık doğrudan swipe yapıyor.
                    if (intent == StepIntent.SCROLL_ONCE) {
                        appiumDriverManager.swipe(run.getId(), "down");
                        run.getSteps().add(new RunStep(i, "swipe", null, "Ekran aşağı kaydırıldı."));
                        runStore.save(run);
                        currentExecutionStep++;
                        repeatCount = 0;
                        lastActionSignature = null;
                        continue;
                    }

                    TargetProbe probe = probeTargetOnPage(run.getId(), rawPageSource, stepGoal);
                    String found = probe.found();
                    boolean hasTarget = probe.hasTarget();
                    boolean targetInteractable = probe.interactable();

                    if (hasTarget && targetInteractable) {
                        run.getSteps().add(new RunStep(i, "swipe", found,
                                "Hedef '" + found + "' ekranda görünür oldu, kaydırma adımı tamamlandı."));
                        runStore.save(run);
                        System.out.println("[STEP] ✓ Kaydırma tamamlandı, hedef bulundu: " + found);
                        currentExecutionStep++;
                        repeatCount = 0;
                        lastActionSignature = null;
                        Thread.sleep(300);
                        continue;
                    }
                    if (hasTarget) {
                        System.out.println("[STEP] ⚠ Hedef metinsel olarak bulundu ama henüz tıklanabilir/görünür viewport'ta değil: " + found);
                    }

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

                    TargetProbe afterScrollProbe = rescanAfterScroll(run.getId(), stepGoal, afterScroll);
                    if (afterScrollProbe.interactable()) {
                        String nextStepInfo = "";
                        if (currentExecutionStep + 1 < executionSteps.size()) {
                            nextStepInfo = " (Sonraki adım: \"" + executionSteps.get(currentExecutionStep + 1) + "\")";
                        }
                        run.getSteps().add(new RunStep(i, "swipe", afterScrollProbe.found(),
                                "Hedef '" + afterScrollProbe.found() + "' kaydırma sonrası detaylı taramada görünür oldu, kaydırma adımı tamamlandı."));
                        runStore.save(run);
                        System.out.println("[STEP] ✓ Kaydırma sonrası hedef bulundu: " + afterScrollProbe.found() + nextStepInfo);
                        currentExecutionStep++;
                        repeatCount = 0;
                        lastActionSignature = null;
                        Thread.sleep(300);
                        continue;
                    }

                    if (afterScrollProbe.hasTarget()) {
                        System.out.println("[STEP] ⚠ Kaydırma sonrası hedef bulundu ama henüz etkileşilebilir değil: " + afterScrollProbe.found());
                    }

                    run.getSteps().add(new RunStep(i, "swipe", null,
                            "Hedef '" + stepGoal + "' aranıyor, ekran " + turkishDirection(scrollDir) + " kaydırıldı."));
                    runStore.save(run);
                    continue;
                }

                // ============================================================================================
                // TAP INTENT -- [YENİLENDİ 2026-09-15 v3 - elementId desteği eklendi]
                //
                // YENİ STRATEJİ (ÖNCELİK DEĞİŞTİRİLDİ):
                //   A. findMatchingTarget ile hedefi bul (deterministik string eşleşme)
                //   B. Devre dışı kontrolü
                //   C. LLM elementId varsa → O SPESİFİK elemente tıkla (en doğru!)
                //   D. LLM elementId yoksa → Locator çıkar (XPath, ID, content-desc)
                //   E. ÖNCE: WebElement.click() ile tıkla (en güvenilir yöntem)
                //   F. WebElement.click() başarısızsa → koordinat tap dene
                //   G. Koordinat tap başarısızsa → ekran analizi fallback
                //   H. En son çare scroll (sadece hedef görünmüyorsa)
                //
                // NEDEN ÖNCE WebElement.click()?
                //   - Koordinat bağımlılığı yok
                //   - Viewport dışı elementleri bile tıklayabilir
                //   - W3C actions chain hataları yok
                //   - Daha güvenilir ve tutarlı
                //
                // NEDEN LLM elementId ÖNCELİKLİ?
                //   - LLM XML'deki [N] numarasını görüyor ve doğru elementi seçiyor
                //   - Birden fazla aynı label varsa LLM doğru olanı seçiyor
                //   - Direkt o elemente tıkla = kesin sonuç
                // ============================================================================================
                if (intent == StepIntent.TAP) {
                    boolean deterministicTapFailed = false;
                    boolean skipScrollFallback = false;
                    boolean targetVisibleButNotTappable = false;

                    String found = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                    System.out.println("[TAP-STEP] Adım hedefi: \"" + stepGoal + "\", bulunan: \"" + found + "\"");

                    // ---- DEVRE DIŞI KORUMASI ----
                    if (found != null
                            && targetsExactMatch(stepGoal, found)
                            && strictVariantMatch(stepGoal, found)
                            && exactProductPhraseMatch(stepGoal, found)
                            && appiumDriverManager.isTargetDisabled(rawPageSource, found)) {
                        System.out.println("[STEP] ⚠ Hedef '" + found + "' DEVRE DIŞI (enabled=false) — tap atlanıyor, LLM'e bırakılıyor");
                        run.getSteps().add(new RunStep(i, "wait", found,
                                "Hedef '" + found + "' ekranda görünüyor ama DEVRE DIŞI (enabled=false). "
                                        + "Tap denemesi yapılmadı."));
                        runStore.save(run);
                        repeatWarning = (repeatWarning == null || repeatWarning.isBlank() ? "" : repeatWarning + "\n\n")
                                + "!!! HEDEF DEVRE DIŞI (enabled=false) !!!\n"
                                + "Ekranda aradığın \"" + found + "\" elementi VAR ama ŞU AN DEVRE DIŞI.\n"
                                + "Bu butonu aktif hale getirmek için ÖNCE gerekli adımı yap:\n"
                                + "  - Ekranda switch/checkbox/izin kutusu varsa gerekli olanı AÇ,\n"
                                + "  - Kullanım koşullarını kabul et,\n"
                                + "  - Formun zorunlu alanlarını doldur.";
                        skipScrollFallback = true;

                    } else if (found != null
                            && targetsExactMatch(stepGoal, found)
                            && strictVariantMatch(stepGoal, found)
                            && exactProductPhraseMatch(stepGoal, found)) {
                        // ---- Hedef bulundu ve aktif: Locator çıkar + EKRANDA OLDUĞUNDAN EMİN OL ----
                        AppiumDriverManager.ElementLocator locator =
                                appiumDriverManager.findElementLocatorByLabel(rawPageSource, found, stepGoal);
                        
                        if (locator == null) {
                            System.out.println("[TAP-STEP] ✗ Locator çıkarılamadı, element ekranda doğrulanamadı");
                            run.getSteps().add(new RunStep(i, "failed", found,
                                    "Hedef '" + found + "' bulundu ama locator çıkarılamadı. Element ekranda doğrulanamadı."));
                            runStore.save(run);
                            deterministicTapFailed = true;
                            skipScrollFallback = true;
                        } else {
                            System.out.println("[TAP-STEP] 📍 Locator çıkarıldı: " + appiumDriverManager.formatLocatorForLog(locator));
                            System.out.println("[TAP-STEP] ✓ Hedef '" + found + "' ekranda TAM OLARAK tespit edildi");
                            
                            // ---- 1. DENEME: ÖNCE WebElement.click() (en güvenilir) ----
                            System.out.println("[TAP-STEP] 🥇 1. DENEME: WebElement.click() ile tıklanıyor (XPath/ID/content-desc)...");
                            boolean elementClicked = appiumDriverManager.clickElementByLocator(run.getId(), locator);
                            
                            if (elementClicked) {
                                System.out.println("[TAP-STEP] ✓ WebElement.click() BAŞARILI: '" + found + "'");
                                run.getSteps().add(new RunStep(i, "tap", found,
                                        "Hedef '" + found + "' WebElement.click() ile tıklandı (en güvenilir metod)"));
                                if (captureScreenshot) {
                                    try {
                                        screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                                        liveScreenshots.put(run.getId(), screenshot);
                                    } catch (Exception ignore) {}
                                }
                                Thread.sleep(1500);
                                String afterTapSource = appiumDriverManager.getPageSource(run.getId());
                                handleSystemDialogsIfAny(run.getId(), afterTapSource, "element click sonrası");

                                notFoundStreak[0] = 0;
                                consecutiveFails = 0;
                                repeatCount = 0;
                                lastActionSignature = null;
                                currentExecutionStep++;
                                runStore.save(run);
                                continue;
                            }
                            
                            System.out.println("[TAP-STEP] ✗ WebElement.click() başarısız, koordinat tap deneniyor...");
                            
                            // ---- 2. DENEME: Koordinat tap ----
                            int[] center = appiumDriverManager.resolveTargetCenter(
                                    run.getId(), rawPageSource, null, found, stepGoal);

                            if (center != null) {
                                boolean coordValid = appiumDriverManager.isValidCoordinate(rawPageSource, center[0], center[1])
                                        && appiumDriverManager.isCoordinateOnViewport(run.getId(), center[0], center[1]);

                                if (coordValid) {
                                    System.out.println("[TAP-STEP] 🥈 2. DENEME: Koordinat tap deneniyor: (" + center[0] + "," + center[1] + ")");
                                    try {
                                        appiumDriverManager.tap(run.getId(), center[0], center[1]);
                                        System.out.println("[TAP-STEP] ✓ Koordinat tap BAŞARILI: '" + found + "'");
                                        
                                        run.getSteps().add(new RunStep(i, "tap", found,
                                                "Hedef '" + found + "' koordinat tap ile tıklandı (" + center[0] + "," + center[1] + ")"));
                                        if (captureScreenshot) {
                                            try {
                                                screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                                                liveScreenshots.put(run.getId(), screenshot);
                                            } catch (Exception ignore) {}
                                        }
                                        Thread.sleep(1500);

                                        String afterTapSource = appiumDriverManager.getPageSource(run.getId());
                                        String cleanedSource = handleSystemDialogsIfAny(run.getId(), afterTapSource, "tap sonrası");
                                        if (!cleanedSource.equals(afterTapSource)) {
                                            rawPageSource = cleanedSource;
                                        }

                                        notFoundStreak[0] = 0;
                                        consecutiveFails = 0;
                                        repeatCount = 0;
                                        lastActionSignature = null;
                                        currentExecutionStep++;
                                        runStore.save(run);
                                        continue;
                                    } catch (Exception tapEx) {
                                        System.out.println("[TAP-STEP] ✗ Koordinat tap başarısız: " + tapEx.getMessage());
                                    }
                                } else {
                                    System.out.println("[TAP-STEP] ⚠ Koordinat geçersiz: (" + center[0] + "," + center[1] + ")");
                                }
                            } else {
                                System.out.println("[TAP-STEP] ⚠ Koordinat çözülemedi");
                            }
                            
                            // ---- Her iki metod da başarısız ----
                            System.out.println("[TAP-STEP] ✗ Hem WebElement.click() hem koordinat tap başarısız");
                            run.getSteps().add(new RunStep(i, "failed", found,
                                    "Otomatik tap başarısız (WebElement.click() + koordinat tap denendi). " +
                                    "Hedef '" + found + "' ekranda var ama tıklanamadı."));
                            runStore.save(run);
                            deterministicTapFailed = true;
                            repeatWarning = "Hedef \"" + found + "\" ekranda bulundu ama HİÇBİR tap metodu işe yaramadı. " +
                                    "WebElement.click() (XPath/ID) ve koordinat tap denendi. " +
                                    "Belki bir overlay/klavye/popup engelliyor; farklı bir aksiyon dene.";
                        }
                    } else {
                        // ---- Hedef ekranda bulunamadı ----
                        System.out.println("[TAP-STEP] ✗ Hedef '" + stepGoal + "' bulunamadı, scroll fallback");
                        
                        // Detaylı sayfa analizi - ekrandaki tüm tıklanabilir elementleri göster
                        System.out.println("[TAP-STEP] 🔍 DETAYLI SAYFA ANALİZİ:");
                        List<String> allElements = appiumDriverManager.parseAllElementLabels(rawPageSource);
                        System.out.println("[TAP-STEP]   Ekrandaki element sayısı: " + allElements.size());
                        if (allElements.size() <= 20) {
                            System.out.println("[TAP-STEP]   Tüm elementler: " + String.join(", ", allElements));
                        } else {
                            System.out.println("[TAP-STEP]   İlk 20 element: " + allElements.subList(0, Math.min(20, allElements.size())).toString());
                        }
                        
                        // "tıkla." gibi action keyword'leri için potansiyel hedefleri bul
                        if (stepGoal.equals("tıkla.") || stepGoal.equals("tıkla") || stepGoal.equals("tap")) {
                            System.out.println("[TAP-STEP] 🎯 Action keyword tespit edildi, potansiyel hedefler aranıyor...");
                            String[] potentialTargets = {"DEVAM", "DEVAM (1)", "DEVAM (2)", "DEVAM (3)", "İLERLE", "İLERLE (1)", "KAPAT", "KAPAT (1)", "TAMAM", "TAMAM (1)"};
                            for (String potential : potentialTargets) {
                                if (allElements.stream().anyMatch(e -> e.contains(potential.replace(" (1)", "").replace(" (2)", "").replace(" (3)", "")))) {
                                    System.out.println("[TAP-STEP] ✓ Potansiyel hedef bulundu: " + potential);
                                    // Potansiyel hedefi bul ve tıkla
                                    AppiumDriverManager.ElementLocator potentialLocator =
                                            appiumDriverManager.findElementLocatorByLabel(rawPageSource, potential, stepGoal);
                                    if (potentialLocator != null) {
                                        int[] potentialCenter = appiumDriverManager.resolveTargetCenter(
                                                run.getId(), rawPageSource, null, potential, stepGoal);
                                        if (potentialCenter != null) {
                                            System.out.println("[TAP-STEP] ✓ Potansiyel hedef koordinatı: (" + potentialCenter[0] + "," + potentialCenter[1] + ")");
                                            try {
                                                appiumDriverManager.tap(run.getId(), potentialCenter[0], potentialCenter[1]);
                                                run.getSteps().add(new RunStep(i, "tap", potential,
                                                        "Potansiyel hedef '" + potential + "' tıklandı (action keyword için otomatik buldu)"));
                                                notFoundStreak[0] = 0;
                                                consecutiveFails = 0;
                                                repeatCount = 0;
                                                lastActionSignature = null;
                                                currentExecutionStep++;
                                                runStore.save(run);
                                                continue;
                                            } catch (Exception e) {
                                                System.out.println("[TAP-STEP] ✗ Potansiyel hedef tıklama başarısız: " + e.getMessage());
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (stepScrollAttempts < MAX_AUTO_SCROLLS) {
                            String scrollDir = AUTO_SCROLL_DIRECTIONS[stepScrollAttempts % AUTO_SCROLL_DIRECTIONS.length];
                            System.out.println("[SWIPE] Kaydırma yapılıyor: " + scrollDir + " (adım " + (stepScrollAttempts + 1) + "/" + MAX_AUTO_SCROLLS + ")");
                            appiumDriverManager.swipe(run.getId(), scrollDir);
                            stepScrollAttempts++;
                            String afterScroll;
                            try {
                                afterScroll = appiumDriverManager.getPageSource(run.getId());
                                // Detaylı scroll sonrası analiz
                                System.out.println("[SWIPE] ✓ Kaydırma tamamlandı, detaylı analiz:");
                                List<String> afterScrollElements = appiumDriverManager.parseAllElementLabels(afterScroll);
                                System.out.println("[SWIPE]   Yeni element sayısı: " + afterScrollElements.size());
                                if (afterScrollElements.size() <= 20) {
                                    System.out.println("[SWIPE]   Tüm elementler: " + String.join(", ", afterScrollElements));
                                } else {
                                    System.out.println("[SWIPE]   İlk 20 element: " + afterScrollElements.subList(0, Math.min(20, afterScrollElements.size())).toString());
                                }
                                // Hedef var mı kontrol et
                                if (afterScrollElements.stream().anyMatch(e -> e.contains(stepGoal.replace(".", "").trim()))) {
                                    System.out.println("[SWIPE] ✓ HEDEF KAYDIRMA SONRASI BULUNDU: " + stepGoal);
                                }
                            } catch (Exception e) {
                                afterScroll = rawPageSource;
                                System.out.println("[SWIPE] ✗ Sayfa kaydırma sonrası alınamadı: " + e.getMessage());
                            }
                            TargetProbe afterScrollProbe = rescanAfterScroll(run.getId(), stepGoal, afterScroll);
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "Tap hedefi henüz bulunamadı, ekran " + turkishDirection(scrollDir) + " kaydırıldı."));
                            System.out.println("[TAP-STEP] ✗ Tap hedefi '" + stepGoal + "' bulunamadı, scroll denendi (deneme: " + stepScrollAttempts + "/" + MAX_AUTO_SCROLLS + ")");
                            runStore.save(run);
                            if (afterScrollProbe.hasTarget()) {
                                System.out.println("[TAP-STEP] ℹ Kaydırma sonrası hedef bulundu: " + afterScrollProbe.found());
                            }
                            Thread.sleep(400);
                            continue;
                        }
                    }

                    // ---- Scroll fallback atlama kararı ----
                    if (skipScrollFallback || deterministicTapFailed) {
                        System.out.println("[TAP-STEP] Scroll fallback atlandı (" +
                                (skipScrollFallback ? "devre-dışı" : "deterministik tap başarısız") +
                                "), ekran analizi fallback deneniyor.");
                    }

                    // ---- Ekran analizi fallback ----
                    System.out.println("[TAP-STEP] 🧠 Ekran analizi fallback devreye alınıyor...");
                    boolean progressed = tryScreenAnalysisFallback(
                            run, stepGoal, rawPageSource, i, analysisUsageCounter);
                    if (progressed) {
                        notFoundStreak[0] = 0;
                        consecutiveFails = 0;
                        repeatCount = 0;
                        lastActionSignature = null;
                        Thread.sleep(500);
                        continue;
                    }

                    System.out.println("[TAP-STEP] Deterministik tap + analiz çözülemedi, LLM akışına düşülüyor.");
                }

                if (isFirstStep) {
                    System.out.println("[RUN] İlk adım: Sayfa stabilitesi için 1 saniye ek bekleme");
                    Thread.sleep(1000);
                    isFirstStep = false;
                    try {
                        rawPageSource = appiumDriverManager.getPageSource(run.getId());
                        // [YENİ 2026-09-15] İlk adımda splash/izin diyaloğu çıkabilir
                        rawPageSource = handleSystemDialogsIfAny(run.getId(), rawPageSource, "ilk adım");
                        filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource, stepGoal);
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
                // PRE-LLM KONTROL
                // ============================================================================================
                String targetOnScreen = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                if (targetOnScreen != null) {
                    boolean targetDisabled = appiumDriverManager.isTargetDisabled(rawPageSource, targetOnScreen);

                    String preHint;
                    if (targetDisabled) {
                        preHint = "!!! ÇOK ÖNEMLİ: Hedeflediğin \"" + targetOnScreen + "\" elementi EKRANDA GÖRÜNÜYOR "
                                + "AMA DEVRE DIŞI (enabled=false). Bu butona TAP ETMEK hiçbir şey yapmaz — "
                                + "uygulama touch event'i kabul etmez. "
                                + "BUTONU TAP ETME! Bunun yerine bu butonu AKTİF hale getirecek ÖNCEKİ adımı yap: "
                                + "ekranda switch/checkbox/izin kutuları varsa gerekli olanı aç, kullanım koşullarını "
                                + "kabul et, zorunlu bir alanı doldur; gerekiyorsa aşağı kaydırıp eksik koşulu tamamla. "
                                + "Butonu tekrar tekrar tap ETME ve scroll ile de kaçırma — buton zaten ekranda.";
                        System.out.println("[RUN] Pre-LLM hint: hedef '" + targetOnScreen + "' DEVRE DIŞI (enabled=false)");
                    } else {
                        preHint = "!!! DİKKAT: Hedeflediğin element EKRANDA ZATEN GÖRÜNÜYOR: \""
                                + targetOnScreen + "\". "
                                + "Bu adımda SAKIN action=swipe döndürme -- swipe reddedilir. "
                                + "XML'de bu elementin [N] numarasını bul ve action=tap döndür. "
                                + "Eğer bu \"Product Image\" gibi genel bir etiketse, etiketin sonundaki "
                                + "\"[product: ...]\" ekinin hedefle eşleştiğinden emin ol.";
                        System.out.println("[RUN] Pre-LLM hint eklendi: hedef ekranda gorunuyor -> " + targetOnScreen);
                    }

                    repeatWarning = (repeatWarning == null || repeatWarning.isBlank())
                            ? preHint
                            : (repeatWarning + "\n\n" + preHint);

                    if (!targetDisabled && !targetsExactMatch(stepGoal, targetOnScreen)) {
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
                // HARD BLOCK: DEVRE DIŞI + YANLIŞ ÜRÜN
                // ============================================================================================
                if ("tap".equals(action.getAction()) && action.getTarget() != null) {
                    if (appiumDriverManager.isTargetDisabled(rawPageSource, action.getTarget())) {
                        System.out.println("[RUN] 🚫 HARD BLOCK: '" + action.getTarget()
                                + "' DEVRE DIŞI (enabled=false), tap reddedildi.");
                        run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                "SİSTEM ENGELLEDİ: \"" + action.getTarget() + "\" elementi DEVRE DIŞI (enabled=false). "
                                        + "Bu butona tap etmek hiçbir şey yapmaz. Bunun yerine bu butonu aktif hale getirecek "
                                        + "ön adımı yap: ekrandaki switch/checkbox/izin kutusunu aç, koşulları kabul et, "
                                        + "zorunlu alanı doldur ya da gerekirse aşağı kaydırıp eksik koşulu tamamla. "
                                        + "Aynı butonu tekrar tap ETME!"));
                        runStore.save(run);
                        repeatCount = 0;
                        repeatWarning = "!!! HEDEF DEVRE DIŞI (enabled=false) !!!\n"
                                + "Tap etmeye çalıştığın \"" + action.getTarget() + "\" elementi ekranda VAR ama "
                                + "ŞU AN DEVRE DIŞI. Bu butonu aktif hale getirecek ÖN ADIMI yap "
                                + "(switch/checkbox aç, koşulları kabul et, zorunlu alanı doldur, gerekirse kaydır). "
                                + "Bu butona tekrar tap ETME!";
                        Thread.sleep(400);
                        continue;
                    }

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

                // elementId validasyonu
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

                // Yanlış element seçimi koruması
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

                // REPEAT DETECTION (XML hash'e duyarlı)
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
                            rawPageSource = outcome.newPageSource();
                            System.out.println("[AUTO-SCROLL] YENİ PAGE SOURCE ile arama tekrarlanıyor...");

                            String matchingTarget = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                            if (matchingTarget != null) {
                                System.out.println("[AUTO-SCROLL] ✓ YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget);
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi ekranda görünmediği için SİSTEM ekranı otomatik olarak "
                                        + turkishDirection(outcome.direction()) + " kaydırdı. YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget
                                        + " Şimdi bu YENİ elementi hedefle!";
                            } else {
                                repeatWarning = "Az önce hedeflediğin \"" + action.getTarget() + "\" elementi ekranda görünmediği için "
                                        + "SİSTEM ekranı otomatik olarak " + turkishDirection(outcome.direction()) + " kaydırdı. Şimdi "
                                        + "ekrandaki YENİ XML listesine bak.";
                            }
                        } else if (outcome.scrolled()) {
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "Kaydırma denendi ama ekran İÇERİĞİ DEĞİŞMEDİ (bu ekran kaydırılamıyor olabilir)."));
                            runStore.save(run);
                            repeatWarning = "\"" + action.getTarget() + "\" için kaydırma denendi ama ekran hiç değişmedi -- bu ekran "
                                    + "muhtemelen kaydırılamıyor. AYNI YÖNDE KAYDIRMAYI TEKRARLAMA.";
                        } else {
                            repeatWarning = "Hedeflediğin \"" + action.getTarget() + "\" elementi mevcut ekranda GÖRÜNMÜYOR.";
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
                                        + "Şimdi ekrandaki YENİ XML listesine bakarak DEVAM ET.";
                            } else if (outcome.scrolled()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Kaydırma denendi ama ekran İÇERİĞİ DEĞİŞMEDİ (bu ekran kaydırılamıyor olabilir)."));
                                runStore.save(run);
                                repeatWarning = "\"" + action.getTarget() + "\" için kaydırma denendi ama ekran hiç değişmedi.";
                            }
                            Thread.sleep(800);
                            continue;
                        }

                        run.getSteps().add(new RunStep(i, "tap", action.getTarget(), action.getReasoning()));
                        System.out.println("[RUN] Tap işlemi yapılıyor: " + action.getTarget() + " (x=" + action.getX() + ", y=" + action.getY() + ")");

                        try {
                            String pageSourceBeforeTap = rawPageSource;

                            appiumDriverManager.tap(run.getId(), action.getX(), action.getY());
                            notFoundStreak[0] = 0;
                            consecutiveFails = 0;
                            swipeRefusedCount[0] = 0;
                            wrongTapRefusedCount[0] = 0;

                            if (captureScreenshot) {
                                try {
                                    screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                                    liveScreenshots.put(run.getId(), screenshot);
                                    System.out.println("[RUN] Tap sonrası ekran görüntüsü güncellendi");
                                } catch (Exception screenEx) {
                                    System.out.println("[RUN] Tap sonrası ekran görüntüsü alınamadı: " + screenEx.getMessage());
                                }
                            }

                            Thread.sleep(1500);

                            // [YENİ 2026-09-15] Tap sonrası sistem izin diyaloğu kontrolü
                            String afterTapSource = appiumDriverManager.getPageSource(run.getId());
                            String cleanedSource = handleSystemDialogsIfAny(run.getId(), afterTapSource, "tap sonrası");
                            if (!cleanedSource.equals(afterTapSource)) {
                                rawPageSource = cleanedSource;
                                if (captureScreenshot) {
                                    try {
                                        screenshot = appiumDriverManager.takeScreenshotBase64(run.getId());
                                        liveScreenshots.put(run.getId(), screenshot);
                                    } catch (Exception ignore) {}
                                }
                            }

                            System.out.println("[RUN] Tap işlemi BAŞARILI: " + action.getTarget());
                            if (!lastExecutionStep) {
                                currentExecutionStep++;
                            }

                            String pageSourceAfterTap = appiumDriverManager.getPageSource(run.getId());
                            if (lastExecutionStep && isGoalCompleted(run.getId(), run.getGoal(), action.getTarget(),
                                    pageSourceBeforeTap, pageSourceAfterTap)) {
                                System.out.println("[RUN] ✓ HEDEF TAMAMLANDI: '" + action.getTarget() + "' tıklandı, ekran değişti ve hedef yeni ekranda yok.");
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
                                rawPageSource = outcome.newPageSource();
                                String matchingTarget = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                                if (matchingTarget != null) {
                                    repeatWarning = "YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget + " Şimdi bu elementi hedefle!";
                                } else {
                                    repeatWarning = "Az önceki element üst üste bulunamadığı için SİSTEM otomatik kaydırdı. Yeni XML'e bak.";
                                }
                            } else if (outcome.scrolled()) {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "Kaydırma denendi ama ekran İÇERİĞİ DEĞİŞMEDİ (bu ekran kaydırılamıyor olabilir)."));
                                runStore.save(run);
                                repeatWarning = "Kaydırma denendi ama ekran değişmedi.";
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
                        String matchingTarget = targetOnScreen;

                        boolean isExactMatch = false;
                        if (matchingTarget != null) {
                            String exactProductName = appiumDriverManager.extractExactProductName(stepGoal);
                            if (exactProductName != null) {
                                isExactMatch = matchingTarget.toLowerCase(Locale.ROOT).contains(exactProductName.toLowerCase(Locale.ROOT));
                                System.out.println("[SWIPE REFUSE] Tam ürün adı: " + exactProductName);
                                System.out.println("[SWIPE REFUSE] Ekrandaki hedef: " + matchingTarget);
                                System.out.println("[SWIPE REFUSE] TAM EŞLEŞME: " + isExactMatch);
                            } else {
                                isExactMatch = true;
                            }
                        }

                        // Devre dışı element için swipe reddi YAPMA
                        boolean targetDisabled = matchingTarget != null
                                && appiumDriverManager.isTargetDisabled(rawPageSource, matchingTarget);

                        if (matchingTarget != null && isExactMatch && !targetDisabled) {
                            swipeRefusedCount[0]++;

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

                            System.out.println("[RUN] Swipe REDDEDILDI -- hedef zaten ekranda: " + matchingTarget);
                            run.getSteps().add(new RunStep(i, "failed", matchingTarget,
                                    "Kaydirma reddedildi: hedef \"" + matchingTarget + "\" ekranda zaten gorunuyor, once secilmeli."));
                            runStore.save(run);
                            repeatCount = 0;
                            lastActionSignature = null;
                            repeatWarning = "!!! DUR, KAYDIRMA REDDEDILDI !!! "
                                    + "Hedefledigin element EKRANDA ZATEN GORUNUYOR: \"" + matchingTarget + "\". "
                                    + "Sakin kaydirma yapma! Bu elementi SIMDI action=tap ile sec.";
                            Thread.sleep(500);
                            continue;
                        }

                        if (matchingTarget != null && targetDisabled) {
                            System.out.println("[SWIPE] Hedef '" + matchingTarget + "' DEVRE DIŞI, swipe serbest bırakılıyor (aktif hale getirmek için scroll gerekebilir).");
                        }

                        swipeRefusedCount[0] = 0;
                        appiumDriverManager.swipe(run.getId(), action.getDirection());
                        Thread.sleep(1000);

                        // [YENİ 2026-09-15] Swipe sonrası sistem diyaloğu kontrolü
                        try {
                            String postSwipeSource = appiumDriverManager.getPageSource(run.getId());
                            handleSystemDialogsIfAny(run.getId(), postSwipeSource, "swipe sonrası");
                        } catch (Exception ignore) {}
                    }
                    case "wait" -> {
                        Thread.sleep(1500);
                        if (isApplicationOpenStep(stepGoal) && !lastExecutionStep) {
                            currentExecutionStep++;
                        }
                    }
                    case "done" -> {
                        // [KRİTİK DÜZELTME 2026-09-15] LLM'in "done" kararını kontrol et
                        // Eğer henüz son adım değilse, "done" REDDEDİLMELİ!
                        if (!lastExecutionStep) {
                            System.out.println("[RUN] 🚫 LLM 'done' döndürdü AMA henüz son adım DEĞİL!");
                            System.out.println("[RUN]   Mevcut adım: " + (currentExecutionStep + 1) + "/" + executionSteps.size());
                            System.out.println("[RUN]   Kalan adım: \"" + executionSteps.get(currentExecutionStep + 1) + "\"");
                            System.out.println("[RUN]   'done' REDDEDİLDİ, devam ediliyor...");
                            
                            run.getSteps().add(new RunStep(i, "failed", null,
                                    "LLM testi erken bitirmeye çalıştı ama henüz tüm adımlar tamamlanmadı! " +
                                    "Kalan adım: \"" + executionSteps.get(currentExecutionStep + 1) + "\". " +
                                    "Devam et!"));
                            runStore.save(run);
                            
                            repeatWarning = "!!! ÇOK ÖNEMLİ: TEST HENÜZ BİTMEDİ !!!\n" +
                                    "Senaryo'da \"" + executionSteps.get(currentExecutionStep + 1) + "\" adımı KALDI.\n" +
                                    "action='done' DON'T return! Önce kalan adımı tamamla.\n" +
                                    "Goal'da 'sonra', 've sonra', 'ardından' varsa VEYA başka yapman gereken şeyler varsa\n" +
                                    "test henüz BİTMEDİ. Sadece TÜM goal tamamlandığında 'done' döndür!";
                            Thread.sleep(400);
                            continue; // "done" reddedildi, devam et
                        }
                        
                        // Son adım ise ve LLM "done" döndürdüyse, test gerçekten bitti
                        System.out.println("[RUN] ✓ Son adım ve LLM 'done' döndürdü → test bitiriliyor");
                        run.getSteps().add(new RunStep(i, "done", null, action.getReasoning()));
                        run.setStatus("passed");
                        run.setFinishedAt(Instant.now().toString());
                        runStore.save(run);
                        screenRefreshRunning.set(false);
                        return;
                    }
                    case "fail" -> {
                        // [YENİ 2026-09-15] Vazgeçmeden ÖNCE analizörü dene
                        System.out.println("[RUN] LLM 'fail' kararı verdi: " + action.getReasoning());
                        System.out.println("[RUN] Vazgeçmeden önce ekran analizi fallback deneniyor...");
                        boolean analysisProgressed = tryScreenAnalysisFallback(
                                run, stepGoal, rawPageSource, i, analysisUsageCounter);
                        if (analysisProgressed) {
                            notFoundStreak[0] = 0;
                            consecutiveFails = 0;
                            repeatCount = 0;
                            lastActionSignature = null;
                            Thread.sleep(500);
                            continue;
                        }
                        System.out.println("[RUN] Ekran analizi de ilerleme sağlayamadı, mevcut fallback'lere geçiliyor.");

                        String autoTapTarget = run.getAutoTapTarget();
                        if (autoTapTarget != null) {
                            System.out.println("[RUN] ⚡ LLM YINE FAIL VERDİ ama autoTapTarget var: " + autoTapTarget);
                            System.out.println("[RUN] ⚡ OTOMATIK TAP yapılıyor: " + autoTapTarget);

                            run.getSteps().add(new RunStep(i, "tap", autoTapTarget,
                                    "LLM tekrar 'fail' verdi ama sistem zaten scroll ile hedefi buldu: " + autoTapTarget + ". OTOMATIK TAP yapılıyor!"));
                            runStore.save(run);

                            String tapTargetXml = appiumDriverManager.getPageSource(run.getId());

                            int[] tapCoords = appiumDriverManager.resolveTargetCenter(run.getId(), tapTargetXml, null, autoTapTarget, stepGoal);
                            if (tapCoords == null) {
                                tapCoords = new int[]{500, 1000};
                            }

                            try {
                                appiumDriverManager.tap(run.getId(), tapCoords[0], tapCoords[1]);
                                notFoundStreak[0] = 0;
                                consecutiveFails = 0;
                                swipeRefusedCount[0] = 0;
                                wrongTapRefusedCount[0] = 0;

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

                                run.setAutoTapTarget(null);
                                run.setAutoTapReason(null);
                                runStore.save(run);

                                Thread.sleep(1500);
                                repeatCount = 0;
                                lastActionSignature = null;
                                continue;
                            } catch (Exception tapEx) {
                                System.out.println("[RUN] Otomatik tap başarısız: " + tapEx.getMessage());
                                run.getSteps().add(new RunStep(i, "failed", autoTapTarget, "Otomatik tap başarısız: " + tapEx.getMessage()));
                                runStore.save(run);
                                consecutiveFails++;
                                Thread.sleep(800);
                                continue;
                            }
                        }

                        AutoScrollOutcome outcome = autoScrollIfNeeded(run.getId(), rawPageSource, notFoundStreak, autoScrollAttempts);
                        if (outcome.exhausted()) {
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
                            rawPageSource = outcome.newPageSource();
                            System.out.println("[AUTO-SCROLL] Fail sonrası scroll başarılı, YENİ ekranda tekrar aranıyor...");

                            String matchingTarget = appiumDriverManager.findMatchingTarget(rawPageSource, stepGoal);
                            if (matchingTarget != null) {
                                System.out.println("[AUTO-SCROLL] ✓ FAIL SONRASI YENİ EKRANDA HEDEF BULUNDU: " + matchingTarget);
                                run.setAutoTapTarget(matchingTarget);
                                run.setAutoTapReason("Fail sonrası scroll ile bulundu");
                                runStore.save(run);

                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "LLM fail kararı verdi ama otomatik scroll sonrası hedef YENİ EKRANDA BULUNDU: " + matchingTarget));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                repeatWarning = "⚠️ KRITIK UYARI: Az önce 'fail' kararı verdin ama SİSTEM ekranı otomatik olarak " +
                                        turkishDirection(outcome.direction()) + " kaydırdı ve YENİ EKRANDA HEDEF BULUNDU!\n\n" +
                                        "🎯 BULUNAN HEDEF: " + matchingTarget + "\n\n" +
                                        "🚨 ŞU ANDA YAPMAN GEREKEN: Bu YENİ elementi TIKLA (tap)!\n\n" +
                                        "❌ YAPMAMAN GEREKENLER:\n" +
                                        "   - 'fail' verme (zaten scroll yapıldı ve hedef bulundu!)\n" +
                                        "   - 'swipe' yapma (hedef zaten ekranda!)\n\n" +
                                        "✅ DOĞRU AKSIYON: '" + matchingTarget + "' elementine TIKLA ve testi bitir!";
                                consecutiveFails = 0;
                                Thread.sleep(800);
                                continue;
                            } else {
                                run.getSteps().add(new RunStep(i, "swipe", null,
                                        "LLM fail kararı verdi ve otomatik scroll sonrası da hedef bulunamadı, tekrar deneniyor..."));
                                runStore.save(run);
                                repeatCount = 0;
                                lastActionSignature = null;
                                repeatWarning = "Az önce 'fail' kararı verdin ve SİSTEM ekranı otomatik olarak " +
                                        turkishDirection(outcome.direction()) + " kaydırdı. AMA hedef YENİ EKRANDA DA BULUNAMADI. " +
                                        "Aynı 'fail' kararını VERME, farklı bir aksiyon dene!";
                                consecutiveFails = 0;
                                Thread.sleep(800);
                                continue;
                            }
                        } else if (outcome.scrolled()) {
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "LLM fail kararı verdi ve scroll denendi ama ekran İÇERİĞİ DEĞİŞMEDİ."));
                            runStore.save(run);
                            repeatWarning = "Az önce 'fail' kararı verdin ve scroll denendi ama ekran hiç değişmedi. " +
                                    "'fail' verme, farklı bir elementle devam etmeyi dene!";
                            consecutiveFails = 0;
                            Thread.sleep(800);
                            continue;
                        } else {
                            run.getSteps().add(new RunStep(i, "swipe", null,
                                    "LLM fail kararı verdi ama scroll yapılamadı."));
                            runStore.save(run);
                            repeatWarning = "Az önce 'fail' kararı verdin ama sistem ekranı kaydıramadı. " +
                                    "'fail' verme, ekranda görünen farklı bir elementle devam etmeyi dene!";
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
            run.setStatus("error");
            run.setError(e.getMessage());
            run.setFinishedAt(Instant.now().toString());
            if (captureScreenshot) run.setFailureScreenshot(screenshot);
            runStore.save(run);
            screenRefreshRunning.set(false);
        } finally {
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
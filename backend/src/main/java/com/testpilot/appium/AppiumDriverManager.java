package com.testpilot.appium;

import com.testpilot.model.AppSettings;
import com.testpilot.settings.AppSettingsService;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.InteractsWithApps;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import io.appium.java_client.screenrecording.CanRecordScreen;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Pause;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AppiumDriverManager {

    private final AppSettingsService appSettingsService;

    public AppiumDriverManager(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    // Her run kendi Appium session'ını (driver'ını) tutar - paralel koşum için
    private final Map<String, AppiumDriver> drivers = new ConcurrentHashMap<>();

    // ============================================================================================
    // startSession -- Android capability'leri temizlendi (2026-09-10)
    //
    // ONCEKI SORUNLAR:
    //   - shouldWaitForQuiescence / waitForQuiescence / useNewWDA -> iOS-only, Android'de
    //     ya yok sayilir ya da UiAutomator2'yi tutarsiz davranisa iter.
    //   - unicodeKeyboard (prefix'siz, typo: "univodeKeyboard") deprecated.
    //   - resetKeyboard=true sistem klavyesini sifirliyor, focus'taki uygulama klavye
    //     degisimini handle edemeyip oluyor -> "uygulama kendi kendine kapaniyor".
    // ============================================================================================
    public AppiumDriver startSession(String runId, String platform, String appIdentifier,
                                     String appActivity, boolean parallel) {
        AppiumDriver existing = drivers.get(runId);
        if (existing != null) {
            return existing;
        }

        AppSettings settings = appSettingsService.getOrCreate();
        String gridUrl = settings.getAppiumGridUrl();
        String defaultAppPackage = settings.getAndroidAppPackage();
        String defaultAppActivity = settings.getAndroidAppActivity();
        String deviceName = settings.getDeviceName();
        String platformVersion = settings.getPlatformVersion();

        AppiumDriver driver;
        try {
            if ("ios".equalsIgnoreCase(platform)) {
                XCUITestOptions options = new XCUITestOptions()
                        .setPlatformName("IOS")
                        .setAutomationName("XCUITest")
                        .setBundleId(appIdentifier)
                        .setAutoAcceptAlerts(true)
                        .setNoReset(false)
                        .setNewCommandTimeout(Duration.ofSeconds(300))
                        // [DUZELTME 2026-09-11] "autoLaunch=false": Appium session
                        // acilirken uygulamayi KENDISI otomatik baslatmasin. Aksi halde
                        // sıra su oluyordu: session -> Appium app'i acar (1. acilis) ->
                        // resetToFreshState terminateApp ile kapatir -> clearApp ->
                        // activateApp ile tekrar acar (2. acilis). Kullanicidan bakinca
                        // "uygulama kendi kendine kapanip aciliyor" gibi gorunuyordu.
                        // autoLaunch=false ile session sirasinda hic acilis olmuyor,
                        // TEK acilis resetToFreshState->activateApp adiminda gerceklesiyor.
                        .amend("autoLaunch", false);
                if (deviceName != null && !deviceName.isBlank()) options.setDeviceName(deviceName);
                if (platformVersion != null && !platformVersion.isBlank()) options.setPlatformVersion(platformVersion);

                driver = new IOSDriver(new URL(gridUrl), options);
            } else {
                String pkg = (appIdentifier != null && !appIdentifier.isBlank()) ? appIdentifier : defaultAppPackage;
                String activity = (appActivity != null && !appActivity.isBlank()) ? appActivity : defaultAppActivity;

                UiAutomator2Options options = new UiAutomator2Options()
                        .setPlatformName("Android")
                        .setAppPackage(pkg)
                        .setAppActivity(activity)
                        .setAutoGrantPermissions(true)
                        .setNoReset(false)
                        .setAppWaitDuration(Duration.ofSeconds(20))
                        .setNewCommandTimeout(Duration.ofSeconds(300))
                        // [DUZELTME 2026-09-11] bkz. yukaridaki iOS yorumu - ayni sebep,
                        // Android tarafinda da session acilisinda otomatik baslatmayi
                        // engelliyoruz; tek acilis resetToFreshState->activateApp'te olur.
                        .amend("autoLaunch", false);
                if (deviceName != null && !deviceName.isBlank()) options.setDeviceName(deviceName);
                if (platformVersion != null && !platformVersion.isBlank()) options.setPlatformVersion(platformVersion);

                driver = new AndroidDriver(new URL(gridUrl), options);
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Grid sunucu adresi hatalı: " + gridUrl, e);
        }

        drivers.put(runId, driver);
        return driver;
    }

    private AppiumDriver driverFor(String runId) {
        AppiumDriver driver = drivers.get(runId);
        if (driver == null) {
            throw new IllegalStateException("Bu run için aktif bir Appium session'ı yok: " + runId);
        }
        return driver;
    }

    // ============================================================================================
    // resetToFreshState -- terminate -> clear -> activate SIRASI (2026-09-10)
    //
    // ONCEKI HATA: clearApp uygulama HALA CALISIRKEN yapiliyordu. Uygulama process'i ayaktayken
    // verileri silinince crash ediyor -> Android auto-restart -> "acilip kapaniyor" belirtisi.
    //
    // API NOTU: InteractsWithApps arayuzunde clearApp metodu YOKTUR. Android'de veri temizliginin
    // standart yolu "mobile: clearApp" mobile command'idir.
    //
    // [DUZELTME 2026-09-11] startSession artik "autoLaunch=false" ile aciliyor (bkz.
    // startSession icindeki .amend("autoLaunch", false)), yani session kurulunca Appium
    // uygulamayi KENDISI baslatmiyor. Bu sayede burada terminateApp adimina artik gerek
    // yok -- uygulama zaten calismiyor. terminateApp+bekleme adimini kaldirmak hem
    // gereksiz "kapat" komutunu (ve onun getirdigi gorsel kapanma hissini) ortadan
    // kaldiriyor hem de reset akisini ~2 saniye hizlandiriyor. Tek acilis SADECE
    // asagidaki activateApp cagrisinda gerceklesiyor.
    // ============================================================================================
    public void resetToFreshState(String runId, String platform, String appIdentifier) {
        AppiumDriver driver = driverFor(runId);
        InteractsWithApps apps = (InteractsWithApps) driver;

        try {
            if ("ios".equalsIgnoreCase(platform)) {
                // autoLaunch=false sayesinde uygulama henuz baslamadi; dogrudan aktive et.
                apps.activateApp(appIdentifier);
                Thread.sleep(3000);
                return;
            }

            // ---- Android ----
            // 1) Uygulama henuz hic baslamadi (autoLaunch=false) -- once verisini temizle
            try {
                ((JavascriptExecutor) driver).executeScript(
                        "mobile: clearApp",
                        Map.of("appId", appIdentifier)
                );
                System.out.println("[reset] clearApp basarili");
            } catch (Exception clearEx) {
                System.out.println("[reset] clearApp basarisiz (zararsiz): " + clearEx.getMessage());
            }
            Thread.sleep(1000);

            // 2) Uygulamayi temiz veriyle TEK SEFERLIK baslat
            apps.activateApp(appIdentifier);
            Thread.sleep(3000);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("resetToFreshState kesildi", ie);
        } catch (Exception e) {
            throw new RuntimeException("resetToFreshState basarisiz: " + e.getMessage(), e);
        }
    }

    public String takeScreenshotBase64(String runId) {
        return takeScreenshotWithRetry(runId, 2);
    }

    // [DUZELTME 2026-09-11] Canlı simülatör akışı için: retry/backoff YOK, tek deneme.
    // Amaç, arka plan görüntü döngüsünün bir hata durumunda 1sn+ beklemeden hemen bir
    // sonraki kareye geçmesi -- video gibi kesintisiz akış için gecikme birikmemeli.
    // Hata durumunda exception fırlatmak yerine null döner, çağıran taraf sessizce atlar.
    public String takeScreenshotQuiet(String runId) {
        try {
            AppiumDriver driver = driverFor(runId);
            String screenshot = driver.getScreenshotAs(OutputType.BASE64);
            return (screenshot != null && !screenshot.isEmpty()) ? screenshot : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String takeScreenshotWithRetry(String runId, int maxRetries) {
        AppiumDriver driver = driverFor(runId);
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String screenshot = driver.getScreenshotAs(OutputType.BASE64);
                if (screenshot != null && !screenshot.isEmpty()) {
                    return screenshot;
                }
            } catch (Exception e) {
                lastException = e;
                System.out.println("takeScreenshot deneme " + attempt + "/" + maxRetries + " başarısız: " + e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException("takeScreenshot " + maxRetries + " denemede başarısız oldu: " +
                (lastException != null ? lastException.getMessage() : "Bilinmeyen hata"), lastException);
    }

    public String getPageSource(String runId) {
        return getPageSourceWithRetry(runId, 3);
    }

    private String getPageSourceWithRetry(String runId, int maxRetries) {
        AppiumDriver driver = driverFor(runId);
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String source = driver.getPageSource();
                if (source != null && !source.isEmpty()) {
                    return source;
                }
            } catch (Exception e) {
                lastException = e;
                System.out.println("getPageSource deneme " + attempt + "/" + maxRetries + " başarısız: " + e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(2000L * attempt);
                        try {
                            if (driver instanceof AndroidDriver) {
                                ((AndroidDriver) driver).pressKey(new KeyEvent(AndroidKey.HOME));
                                Thread.sleep(500);
                                ((AndroidDriver) driver).pressKey(new KeyEvent(AndroidKey.BACK));
                            } else if (driver instanceof IOSDriver) {
                                ((JavascriptExecutor) driver).executeScript("mobile: pressKey", Map.of("key", "home"));
                                Thread.sleep(500);
                                ((JavascriptExecutor) driver).executeScript("mobile: pressKey", Map.of("key", "back"));
                            }
                        } catch (Exception ignore) {
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException("getPageSource " + maxRetries + " denemede başarısız oldu: " +
                (lastException != null ? lastException.getMessage() : "Bilinmeyen hata"), lastException);
    }

    public int[] getScreenSize(String runId) {
        try {
            Dimension size = driverFor(runId).manage().window().getSize();
            return new int[]{size.getWidth(), size.getHeight()};
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================================================
    // ELEM PARSE
    // ============================================================================================
    private record Elem(String label, String bounds, boolean clickable, boolean isPassword, String className,
                        String resourceId, String text, String contentDesc) {}

    private List<Elem> parseNumberedElements(String rawPageSource) {
        if (rawPageSource == null) return List.of();

        List<Elem> collected = new ArrayList<>();

        Pattern tagPattern = Pattern.compile("<[^<>]+/>");
        Matcher tagMatcher = tagPattern.matcher(rawPageSource);

        while (tagMatcher.find()) {
            String tag = tagMatcher.group();

            boolean clickable = tag.contains("clickable=\"true\"") || tag.contains("accessible=\"true\"");
            boolean isPassword = tag.contains("password=\"true\"");

            String text = extractAttr(tag, "text");
            if (text == null) text = extractAttr(tag, "value");

            String desc = extractAttr(tag, "content-desc");
            if (desc == null) desc = extractAttr(tag, "label");
            if (desc == null) desc = extractAttr(tag, "name");

            String resourceId = extractAttr(tag, "resource-id");
            String resourceIdLabel = resourceIdToLabel(resourceId);

            String rawClassName = extractAttr(tag, "class");
            String className = null;
            if (rawClassName != null && !rawClassName.isBlank()) {
                int dot = rawClassName.lastIndexOf('.');
                className = dot != -1 ? rawClassName.substring(dot + 1) : rawClassName;
            }

            String bounds = extractAttr(tag, "bounds");
            if (bounds == null) bounds = buildBoundsFromXYWH(tag);

            if (bounds == null) continue;

            String label;
            if (desc != null && !desc.isBlank()) {
                label = desc;
            } else if (text != null && !text.isBlank()) {
                label = text;
            } else if (resourceIdLabel != null && !resourceIdLabel.isBlank()) {
                label = resourceIdLabel;
            } else if (isPassword) {
                label = "(sifre alani)";
            } else {
                label = "";
            }
            boolean hasLabel = !label.isBlank();

            if (hasLabel || clickable || isPassword) {
                collected.add(new Elem(label, bounds, clickable, isPassword, className, resourceId, text, desc));
            }
        }

        Map<String, Integer> labelCounts = new HashMap<>();
        for (Elem e : collected) {
            if (!e.label().isBlank()) {
                labelCounts.merge(e.label(), 1, Integer::sum);
            }
        }
        Map<String, Integer> seenSoFar = new HashMap<>();

        List<Elem> numbered = new ArrayList<>();
        for (Elem e : collected) {
            String label = e.label();
            if (!label.isBlank() && labelCounts.getOrDefault(label, 0) > 1) {
                int occurrence = seenSoFar.merge(label, 1, Integer::sum);
                label = label + " (" + occurrence + ")";
            }
            numbered.add(new Elem(label, e.bounds(), e.clickable(), e.isPassword(), e.className(),
                    e.resourceId(), e.text(), e.contentDesc()));
        }
        return numbered;
    }

    private static final int CHAR_BUDGET = 8000;

    // ============================================================================================
    // buildEmittedList -- goal-aware sirali liste (2026-09-10)
    //   - Hedefle eslesen elementler listenin BASINA tasinir -> weak LLM ilk satirlarda
    //     dogru cevabi gorur (context penceresinde kaybolmaz).
    //   - [urun: X] zenginlestirmesi ile gorseller urun adiyla etiketlenir.
    // ============================================================================================
    private List<Elem> buildEmittedList(String rawPageSource, String goal) {
        List<Elem> numbered = parseNumberedElements(rawPageSource);

        List<Elem> labeledEls = new ArrayList<>();
        List<Elem> textOnlyEls = new ArrayList<>();
        List<Elem> unlabeledEls = new ArrayList<>();

        for (Elem e : numbered) {
            boolean hasLabel = !e.label().isBlank();
            if ((e.clickable() || e.isPassword()) && hasLabel) {
                labeledEls.add(e);
            } else if (hasLabel) {
                textOnlyEls.add(e);
            } else if (e.clickable() || e.isPassword()) {
                unlabeledEls.add(e);
            }
        }

        List<Elem> ordered = new ArrayList<>();
        ordered.addAll(labeledEls);
        ordered.addAll(textOnlyEls);
        ordered.addAll(unlabeledEls);

        if (goal != null && !goal.isBlank()) {
            Set<String> keywords = extractKeywords(goal);
            if (!keywords.isEmpty()) {
                ordered.sort((a, b) -> {
                    int sa = relevanceScore(a, keywords);
                    int sb = relevanceScore(b, keywords);
                    if (sa != sb) return Integer.compare(sb, sa);
                    return 0;
                });
            }
        }

        List<Elem> allForContext = new ArrayList<>(numbered);

        List<Elem> emitted = new ArrayList<>();
        int budgetUsed = 0;
        for (Elem e : ordered) {
            String enrichedLabel = enrichImageLabel(e, allForContext);
            Elem toEmit = enrichedLabel.equals(e.label())
                    ? e
                    : new Elem(enrichedLabel, e.bounds(), e.clickable(), e.isPassword(),
                    e.className(), e.resourceId(), e.text(), e.contentDesc());
            String line = formatLine(emitted.size() + 1, toEmit);
            if (budgetUsed + line.length() + 1 > CHAR_BUDGET) break;
            budgetUsed += line.length() + 1;
            emitted.add(toEmit);
        }
        return emitted;
    }

    // Geriye donuk uyumluluk icin -- goal'suz cagrilar hala calissin
    private List<Elem> buildEmittedList(String rawPageSource) {
        return buildEmittedList(rawPageSource, null);
    }

    /**
     * buildEmittedList'in budget'siz hali -- findMatchingTarget gibi "TUM elementleri
     * kontrol et" gorevleri icin. Zenginlestirilmis etiket doner.
     */
    private List<Elem> buildEnrichedList(String rawPageSource) {
        List<Elem> numbered = parseNumberedElements(rawPageSource);
        List<Elem> allForContext = new ArrayList<>(numbered);
        List<Elem> result = new ArrayList<>();
        for (Elem e : numbered) {
            String enriched = enrichImageLabel(e, allForContext);
            if (enriched.equals(e.label())) {
                result.add(e);
            } else {
                result.add(new Elem(enriched, e.bounds(), e.clickable(), e.isPassword(),
                        e.className(), e.resourceId(), e.text(), e.contentDesc()));
            }
        }
        return result;
    }

    private Set<String> extractKeywords(String goal) {
        if (goal == null || goal.isBlank()) return Set.of();
        String normalized = goal.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zçğıöşü0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // HashSet -- Set.of() DUPLICATE eleman verildiginde "duplicate element" firlatir;
        // daha once "adlı" iki kez eklenmisti ve her cagride patlamisti. HashSet sessiz tolere eder.
        Set<String> stop = new HashSet<>(List.of(
                "bir","ve","ile","için","bu","şu","o","adlı","olan","ürünü","ürünün","ürüne",
                "tıkla","bul","git","yap","kontrol","et","ekle","aç","seç","sonra","önce",
                "sayfası","sayfasına","sayfaya","ekranı","ekranına","ekran","test","yapmayı",
                "dene","olduğunu","açıldığını","görselini","görseline","ürün","görsel"
        ));
        Set<String> keywords = new HashSet<>();
        for (String w : normalized.split("\\s+")) {
            if (w.length() >= 3 && !stop.contains(w)) {
                keywords.add(w);
            }
        }
        return keywords;
    }

    private int relevanceScore(Elem e, Set<String> keywords) {
        StringBuilder hay = new StringBuilder();
        if (e.label() != null) hay.append(e.label()).append(' ');
        if (e.text() != null) hay.append(e.text()).append(' ');
        if (e.contentDesc() != null) hay.append(e.contentDesc()).append(' ');
        String lower = hay.toString().toLowerCase(Locale.ROOT);

        int score = 0;
        for (String k : keywords) {
            if (lower.contains(k)) score += 10;
        }
        if (e.clickable()) score += 2;
        return score;
    }

    // ============================================================================================
    // enrichImageLabel -- esikler gevsetildi (2026-09-10)
    //   - dx > 700 (liste satirlarinda gorsel solda, ad sagda olabilir)
    //   - dist formulu dy agirlikli (ayni satirdaki urun icin dy kucuk olmali)
    //   - minDist < 2500
    // ============================================================================================
    private String enrichImageLabel(Elem e, List<Elem> allElements) {
        String label = e.label();
        if (label == null || label.isBlank()) return label;
        String lower = label.toLowerCase(Locale.ROOT);
        if (!(lower.contains("image") || lower.contains("görsel") || lower.contains("resim")
                || lower.contains("foto") || lower.contains("photo"))) {
            return label;
        }
        int[] rect = parseBounds(e.bounds());
        if (rect == null) return label;
        int eCx = (rect[0] + rect[2]) / 2;
        int eCy = (rect[1] + rect[3]) / 2;

        Elem nearest = null;
        long minDist = Long.MAX_VALUE;
        for (Elem other : allElements) {
            if (other == e) continue;
            if (other.label().isBlank()) continue;
            String oLower = other.label().toLowerCase(Locale.ROOT);
            if (oLower.contains("image") || oLower.contains("görsel") || oLower.contains("resim")
                    || oLower.contains("foto") || oLower.contains("photo")) continue;
            if (oLower.startsWith("[id:")) continue;
            if (oLower.matches(".*(sepete ekle|add to cart|kaldir|remove).*")) continue;
            if (oLower.matches(".*[\\$€₺]\\s*\\d.*")) continue;
            if (oLower.matches(".*\\d+[.,]\\d+.*")) continue;

            int[] oRect = parseBounds(other.bounds());
            if (oRect == null) continue;
            int oCx = (oRect[0] + oRect[2]) / 2;
            int oCy = (oRect[1] + oRect[3]) / 2;
            long dx = Math.abs(eCx - oCx);
            long dy = Math.abs(eCy - oCy);
            if (dx > 700) continue;
            long dist = dy * 3L + dx;
            if (dist < minDist) {
                minDist = dist;
                nearest = other;
            }
        }
        if (nearest != null && minDist < 2500) {
            return label + " [urun: " + nearest.label() + "]";
        }
        return label;
    }

    // ============================================================================================
    // formatLine -- sadeleştirilmiş format (2026-09-10)
    //
    // Eski: [3] bounds=[0,0][300,300] clickable=true class=ImageView text="Product Image" content-desc="Product Image" label="Product Image [urun: Sauce Labs Backpack (yellow)]"
    // Yeni: [3] tıklanabilir "Product Image [urun: Sauce Labs Backpack (yellow)]" (ImageView)
    //
    // bounds ve tekrarli text/content-desc cikarildi; locator zaten XML'deki ham bounds'tan
    // okuyor. Ayni CHAR_BUDGET icinde ~2.5x daha fazla element sigar.
    // ============================================================================================
    private String formatLine(int id, Elem e) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(id).append("]");
        if (e.clickable()) sb.append(" tıklanabilir");
        if (e.isPassword()) sb.append(" şifre-alanı");
        if (!e.label().isBlank()) {
            sb.append(" \"").append(e.label().replace("\"", "'")).append("\"");
        }
        if (e.className() != null && !e.className().isBlank()) {
            sb.append(" (").append(e.className()).append(")");
        }
        return sb.toString();
    }

    // ============================================================================================
    // filterPageSource -- 2 overload
    // ============================================================================================
    public String filterPageSource(String rawPageSource) {
        return filterPageSource(rawPageSource, null);
    }

    public String filterPageSource(String rawPageSource, String goal) {
        if (rawPageSource == null) return "";

        List<Elem> emitted = buildEmittedList(rawPageSource, goal);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < emitted.size(); i++) {
            result.append(formatLine(i + 1, emitted.get(i))).append("\n");
        }
        return result.toString();
    }

    /**
     * LLM'in gorebilecegi toplam element sayisi (goal-aware siralama ile ayni).
     * RunController elementId validasyonunda kullanir: model "[200]" derse ve
     * bu sayi 30 ise, ID uydurma tespit edilir.
     */
    public int countEmittableElements(String rawPageSource, String goal) {
        if (rawPageSource == null) return 0;
        return buildEmittedList(rawPageSource, goal).size();
    }

    private Elem findUniqueElemByLabel(List<Elem> numbered, String targetLabel) {
        String target = targetLabel.trim();
        String targetLower = target.toLowerCase(Locale.ROOT);

        Integer targetOccurrence = null;
        String targetBaseLower = targetLower;
        Matcher occMatcher = Pattern.compile("\\((\\d+)\\)\\s*$").matcher(target);
        if (occMatcher.find()) {
            targetOccurrence = Integer.parseInt(occMatcher.group(1));
            targetBaseLower = target.substring(0, occMatcher.start()).trim().toLowerCase(Locale.ROOT);
        }

        List<Elem> candidates = new ArrayList<>();
        for (Elem e : numbered) {
            if (e.label().isBlank()) continue;
            String labelLower = e.label().toLowerCase(Locale.ROOT);

            String candidateBaseLower = labelLower;
            Integer candidateOccurrence = null;
            Matcher m = Pattern.compile("\\((\\d+)\\)\\s*$").matcher(labelLower);
            if (m.find()) {
                candidateOccurrence = Integer.parseInt(m.group(1));
                candidateBaseLower = labelLower.substring(0, m.start()).trim();
            }

            boolean baseMatches = candidateBaseLower.equals(targetBaseLower)
                    || (candidateBaseLower.length() >= 3 && targetBaseLower.contains(candidateBaseLower))
                    || (targetBaseLower.length() >= 3 && candidateBaseLower.contains(targetBaseLower));
            if (!baseMatches) continue;

            if (targetOccurrence != null) {
                if (candidateOccurrence != null && candidateOccurrence.equals(targetOccurrence)) {
                    candidates.add(e);
                }
            } else {
                candidates.add(e);
            }
        }

        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    public int[] findElementCenter(String rawPageSource, String targetLabel) {
        if (rawPageSource == null || targetLabel == null || targetLabel.isBlank()) return null;
        List<Elem> numbered = parseNumberedElements(rawPageSource);
        Elem match = findUniqueElemByLabel(numbered, targetLabel);
        return match != null ? centerOfBounds(match.bounds()) : null;
    }

    // ============================================================================================
    // STOPWORDS -- COK DILLI, HashSet tabanli (2026-09-10)
    //
    // ONCEKI HATA: switch-case ile yazilmisti ve "olan" + "that" kelimeleri IKI KEZ gecmesi
    // derleme hatasina yol aciyordu ("duplicate case label"). Simdi HashSet -- duplicate'leri
    // SESSIZCE tolere eder, bu listeyi elle duzenlerken ayni hataya bir daha dusmeyiz.
    //
    // TASARIM ILKELERI:
    //   (1) SADECE gramatik kelimeler (artikel, baglac, edat, zamir, yardimci fiil).
    //   (2) SADECE genel arama fiilleri ("bul", "find", "click") -- bunlar hedef TANIMLAMAZ.
    //   (3) ASLA: renk (violet), urun adi (backpack), marka (sauce), kategori (bike),
    //       buton etiketi (add, cart, submit, login, save, remove) -- bunlar HEDEF sinyalidir.
    //   (4) Liste KISA tutulur (~40/dil). Uzun liste = yanlis pozitif riski.
    // ============================================================================================
    private static final Set<String> STOP_WORDS = buildStopWords();

    private static Set<String> buildStopWords() {
        Set<String> w = new HashSet<>();

        // ---- Turkce ----
        Collections.addAll(w,
                "bir","ve","ile","için","bu","şu","o","veya","ama","fakat","ki",
                "olan","olarak","adlı","isimli","geçen","içeren",
                "tıkla","tıklayın","bas","bul","bulun","ara","git","aç","seç","ekle",
                "kontrol","et","dene","göster","listele","tamamla","doğrula","onayla"
        );

        // ---- English ----
        Collections.addAll(w,
                "the","a","an","and","or","but","if",
                "in","on","at","to","of","with","for","from",
                "this","that","is","are",
                "click","tap","find","open","check","verify","navigate"
        );

        // ---- Deutsch ----
        Collections.addAll(w,
                "der","die","das","und","oder","aber",
                "in","auf","mit","zu","für",
                "klicken","suchen","finden","öffnen","prüfen"
        );

        // ---- Francais ----
        Collections.addAll(w,
                "le","la","les","et","ou","mais",
                "dans","sur","avec","pour","vers",
                "cliquer","chercher","trouver","ouvrir","vérifier"
        );

        // ---- Espanol ----
        Collections.addAll(w,
                "el","la","los","las","y","o","pero",
                "en","con","por","para","sobre",
                "clic","buscar","encontrar","abrir","verificar"
        );

        // ---- Italiano ----
        Collections.addAll(w,
                "il","lo","la","e","o","ma",
                "in","con","per","su",
                "clicca","cerca","trovare","aprire","verificare"
        );

        // ---- Portugues ----
        Collections.addAll(w,
                "o","a","os","as","e","ou","mas",
                "em","com","para","sobre",
                "clique","buscar","encontrar","abrir","verificar"
        );

        return Collections.unmodifiableSet(w);
    }

    private boolean isStopWord(String w) {
        if (w == null) return true;
        String t = w.toLowerCase(Locale.ROOT).trim();
        return t.isEmpty() || STOP_WORDS.contains(t);
    }

    /**
     * Hedef cumleden anlamli kelimeleri cikarir. Stopword'ler ve 3 karakterden kisa
     * kelimeler atilir. DEFANSIF: hepsi filtrelendiyse en uzun kelime korunur.
     */
    private Set<String> extractContentWords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zçğıöşü0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        Set<String> raw = new LinkedHashSet<>();
        for (String w : normalized.split("\\s+")) {
            if (w.length() >= 3) raw.add(w);
        }
        Set<String> filtered = new HashSet<>();
        for (String w : raw) {
            if (!STOP_WORDS.contains(w)) filtered.add(w);
        }
        if (filtered.isEmpty()) {
            String longest = raw.stream()
                    .max(Comparator.comparingInt(String::length))
                    .orElse(null);
            if (longest != null) filtered.add(longest);
        }
        return filtered;
    }

    /**
     * Hedef urun/eleman ekranda ZATEN var mi diye kontrol eder.
     *
     * YAKLASIM: 4 kademeli + IDF-benzeri puanlama
     *   TIER 1: [urun: X] eki -- X goal'da TAM olarak geciyor (en guclu)
     *   TIER 2: Duz etiket -- etiket goal'da TAM olarak geciyor
     *   TIER 3: Ayirt edici kelime -- XML'de <=2 elementte gecen hedef kelimesi
     *           (ör. "violet" gibi constraint'ler bu tier ile yakalanir)
     *   TIER 4: 3+ ortak kelime (genel fallback)
     *
     * @return eslesen elementin etiketi, yoksa null
     */
    public String findMatchingTarget(String rawPageSource, String goal) {
        if (rawPageSource == null || goal == null || goal.isBlank()) return null;

        String goalLower = goal.toLowerCase(Locale.ROOT);
        List<Elem> enriched = buildEnrichedList(rawPageSource);

        Set<String> goalContentWords = extractContentWords(goal);
        if (goalContentWords.isEmpty()) {
            System.out.println("[findMatch] Goal'da anlamli kelime yok");
            return null;
        }

        Map<String, Integer> keywordFrequency = new HashMap<>();
        for (String kw : goalContentWords) {
            int count = 0;
            for (Elem e : enriched) {
                if (e.label().toLowerCase(Locale.ROOT).contains(kw)) count++;
            }
            if (count > 0) keywordFrequency.put(kw, count);
        }

        System.out.println("[findMatch] goal=\"" + goal + "\"");
        System.out.println("[findMatch] keyword frekanslari: " + keywordFrequency);

        Pattern urunPattern = Pattern.compile("\\[urun:\\s*([^\\]]+)\\]");

        Elem bestMatch = null;
        int bestScore = 0;
        String bestTier = null;

        for (Elem e : enriched) {
            String label = e.label();
            String labelLower = label.toLowerCase(Locale.ROOT);
            int score = 0;
            String tier = null;

            // TIER 1: [urun: X] eki goal'da geciyor
            Matcher m = urunPattern.matcher(label);
            if (m.find()) {
                String productName = m.group(1).trim();
                if (productName.length() >= 5
                        && goalLower.contains(productName.toLowerCase(Locale.ROOT))) {
                    score = productName.length() * 3 + 100;
                    tier = "TIER-1";
                }
            }

            // TIER 2: duz etiket goal'da geciyor
            if (score == 0) {
                String plain = label.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
                if (plain.length() >= 8 && goalLower.contains(plain.toLowerCase(Locale.ROOT))) {
                    long wc = 0;
                    for (String w : plain.split("\\s+")) if (w.length() >= 3) wc++;
                    if (wc >= 2) {
                        score = plain.length() * 2 + 50;
                        tier = "TIER-2";
                    }
                }
            }

            // TIER 3: ayirt edici kelime (IDF-dusuk)
            if (score == 0) {
                int distinctiveHits = 0;
                int rareThreshold = 2;
                for (String kw : goalContentWords) {
                    Integer freq = keywordFrequency.get(kw);
                    if (freq == null) continue;
                    if (freq <= rareThreshold && labelLower.contains(kw)) {
                        distinctiveHits++;
                    }
                }
                if (distinctiveHits >= 1) {
                    score = distinctiveHits * 80 + 30;
                    tier = "TIER-3";
                }
            }

            // TIER 4: 3+ ortak kelime
            if (score == 0) {
                int hits = 0;
                for (String kw : goalContentWords) {
                    if (keywordFrequency.containsKey(kw) && labelLower.contains(kw)) hits++;
                }
                if (hits >= 3) {
                    score = hits * 12;
                    tier = "TIER-4";
                }
            }

            if (score > 0) {
                if (e.clickable()) score += 8;
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = e;
                    bestTier = tier;
                }
            }
        }

        if (bestMatch != null) {
            System.out.println("[findMatch] " + bestTier + " \"" + bestMatch.label()
                    + "\" (skor=" + bestScore + ")");
            return bestMatch.label();
        }

        System.out.println("[findMatch] Hicbir tier eslesmedi");
        return null;
    }

    // ============================================================================================
    // LOCATOR COZUMLEME
    //   1) ID (elementId): model "[N]" numarasini secer, bounds merkezini biz hesaplariz.
    //   2) Etiket (target): serbest metin -> bulanik eslestirme.
    //   3) XPath: canli uygulamada XPath ile yeniden ara.
    // Non-clickable element icin clickable ancestor'a tiklama ozelligi dahil.
    // ============================================================================================

    // 5 parametreli -- goal-aware siralama icin ZORUNLU
    public int[] resolveTargetCenter(String runId, String rawPageSource, String elementId,
                                     String targetLabel, String goal) {
        if (rawPageSource == null) return null;

        Elem byId = null;
        Integer id = parsePlainInt(elementId);
        if (id != null) {
            List<Elem> emitted = buildEmittedList(rawPageSource, goal);
            if (id >= 1 && id <= emitted.size()) {
                byId = emitted.get(id - 1);

                if (!byId.clickable()) {
                    int[] ancestorCenter = findClickableAncestorCenter(rawPageSource, byId.bounds());
                    if (ancestorCenter != null) {
                        System.out.println("[locator] Non-clickable element (id=" + id
                                + ", label=" + byId.label() + "), clickable ancestor kullaniliyor.");
                        return ancestorCenter;
                    }
                }

                int[] center = centerOfBounds(byId.bounds());
                if (center != null) {
                    return center;
                }
            }
        }

        List<Elem> numbered = parseNumberedElements(rawPageSource);
        Elem byLabel = (targetLabel != null && !targetLabel.isBlank())
                ? findUniqueElemByLabel(numbered, targetLabel) : null;
        if (byLabel != null) {
            if (!byLabel.clickable()) {
                int[] ancestorCenter = findClickableAncestorCenter(rawPageSource, byLabel.bounds());
                if (ancestorCenter != null) {
                    System.out.println("[locator] Non-clickable label element ("
                            + byLabel.label() + "), clickable ancestor kullaniliyor.");
                    return ancestorCenter;
                }
            }
            int[] center = centerOfBounds(byLabel.bounds());
            if (center != null) {
                return center;
            }
        }

        Elem anchor = byId != null ? byId : byLabel;
        if (anchor != null) {
            int[] viaXPath = resolveByXPath(runId, rawPageSource, anchor);
            if (viaXPath != null) return viaXPath;
        }
        return null;
    }

    // Geriye donuk uyumluluk -- 4 parametreli cagrilar hala calissin
    public int[] resolveTargetCenter(String runId, String rawPageSource, String elementId, String targetLabel) {
        return resolveTargetCenter(runId, rawPageSource, elementId, targetLabel, null);
    }

    private int[] findClickableAncestorCenter(String rawPageSource, String childBounds) {
        if (childBounds == null || rawPageSource == null) return null;

        int[] childRect = parseBounds(childBounds);
        if (childRect == null) return null;

        Pattern tagPattern = Pattern.compile("<[^<>]+/>");
        Matcher tagMatcher = tagPattern.matcher(rawPageSource);

        int[] bestRect = null;
        long bestArea = Long.MAX_VALUE;

        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            if (!tag.contains("clickable=\"true\"")) continue;

            String bounds = extractAttr(tag, "bounds");
            if (bounds == null) bounds = buildBoundsFromXYWH(tag);
            if (bounds == null) continue;

            int[] rect = parseBounds(bounds);
            if (rect == null) continue;

            if (rect[0] <= childRect[0] && rect[1] <= childRect[1]
                    && rect[2] >= childRect[2] && rect[3] >= childRect[3]) {
                long area = (long) (rect[2] - rect[0]) * (rect[3] - rect[1]);
                if (area < bestArea) {
                    bestArea = area;
                    bestRect = rect;
                }
            }
        }

        if (bestRect == null) return null;
        return new int[]{(bestRect[0] + bestRect[2]) / 2, (bestRect[1] + bestRect[3]) / 2};
    }

    private int[] parseBounds(String bounds) {
        if (bounds == null) return null;
        Matcher m = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").matcher(bounds);
        if (!m.find()) return null;
        try {
            return new int[]{
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parsePlainInt(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.matches("\\d{1,4}")) return null;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String xpathLiteral(String value) {
        if (!value.contains("'")) return "'" + value + "'";
        if (!value.contains("\"")) return "\"" + value + "\"";
        return null;
    }

    private String buildXPath(String rawPageSource, Elem e) {
        if (e.resourceId() != null && !e.resourceId().isBlank()) {
            String lit = xpathLiteral(e.resourceId());
            if (lit != null && countOccurrences(rawPageSource, "resource-id=\"" + e.resourceId() + "\"") == 1) {
                return "//*[@resource-id=" + lit + "]";
            }
        }
        if (e.contentDesc() != null && !e.contentDesc().isBlank()) {
            String lit = xpathLiteral(e.contentDesc());
            if (lit != null && countOccurrences(rawPageSource, "content-desc=\"" + e.contentDesc() + "\"") == 1) {
                return "//*[@content-desc=" + lit + "]";
            }
        }
        if (e.text() != null && !e.text().isBlank() && e.text().length() <= 80) {
            String lit = xpathLiteral(e.text());
            if (lit != null && countOccurrences(rawPageSource, "text=\"" + e.text() + "\"") == 1) {
                return "//*[@text=" + lit + "]";
            }
        }
        if (e.label() != null && !e.label().isBlank() && e.label().length() <= 80) {
            String lit = xpathLiteral(e.label());
            if (lit != null && countOccurrences(rawPageSource, "label=\"" + e.label() + "\"") == 1) {
                return "//*[@label=" + lit + "]";
            }
        }
        if (e.resourceId() != null && !e.resourceId().isBlank()) {
            String lit = xpathLiteral(e.resourceId());
            if (lit != null) {
                return "//*[@resource-id=" + lit + "][@bounds='" + e.bounds() + "']";
            }
        }
        if (e.label() != null && !e.label().isBlank()) {
            String lit = xpathLiteral(e.label());
            if (lit != null) {
                return "//*[@label=" + lit + "][@bounds='" + e.bounds() + "']";
            }
        }
        return null;
    }

    private int[] resolveByXPath(String runId, String rawPageSource, Elem e) {
        String xpath = buildXPath(rawPageSource, e);
        if (xpath == null) return null;
        try {
            AppiumDriver driver = driverFor(runId);
            WebElement webEl = driver.findElement(By.xpath(xpath));
            Rectangle rect = webEl.getRect();
            System.out.println("[locator] XPath ile bulundu: " + xpath);
            return new int[]{rect.getX() + rect.getWidth() / 2, rect.getY() + rect.getHeight() / 2};
        } catch (Exception ex) {
            System.out.println("[locator] XPath ile bulunamadi (" + xpath + "): " + ex.getMessage());
            return null;
        }
    }

    private int[] centerOfBounds(String bounds) {
        if (bounds == null) return null;
        Matcher m = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").matcher(bounds);
        if (!m.find()) return null;
        try {
            int x1 = Integer.parseInt(m.group(1));
            int y1 = Integer.parseInt(m.group(2));
            int x2 = Integer.parseInt(m.group(3));
            int y2 = Integer.parseInt(m.group(4));
            return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String extractAttr(String tag, String attrName) {
        Pattern p = Pattern.compile(attrName + "=\"([^\"]*)\"");
        Matcher m = p.matcher(tag);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String resourceIdToLabel(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) return null;
        String name = resourceId;
        int slash = name.lastIndexOf('/');
        if (slash != -1) name = name.substring(slash + 1);
        if (name.isBlank()) return null;
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').trim();
        return spaced.isBlank() ? null : "[id: " + spaced + "]";
    }

    private String buildBoundsFromXYWH(String tag) {
        String xStr = extractAttr(tag, "x");
        String yStr = extractAttr(tag, "y");
        String wStr = extractAttr(tag, "width");
        String hStr = extractAttr(tag, "height");
        if (xStr == null || yStr == null || wStr == null || hStr == null) return null;
        try {
            int x = (int) Double.parseDouble(xStr);
            int y = (int) Double.parseDouble(yStr);
            int w = (int) Double.parseDouble(wStr);
            int h = (int) Double.parseDouble(hStr);
            if (w <= 0 || h <= 0) return null;
            return "[" + x + "," + y + "][" + (x + w) + "," + (y + h) + "]";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean isValidCoordinate(String rawPageSource, int x, int y) {
        if (rawPageSource == null) return false;

        Pattern tagPattern = Pattern.compile("<[^<>]+/>");
        Matcher tagMatcher = tagPattern.matcher(rawPageSource);

        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            String bounds = extractAttr(tag, "bounds");
            if (bounds == null) bounds = buildBoundsFromXYWH(tag);
            if (bounds == null) continue;

            Matcher bm = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").matcher(bounds);
            if (bm.find()) {
                int x1 = Integer.parseInt(bm.group(1));
                int y1 = Integer.parseInt(bm.group(2));
                int x2 = Integer.parseInt(bm.group(3));
                int y2 = Integer.parseInt(bm.group(4));
                if (x >= x1 && x <= x2 && y >= y1 && y <= y2) {
                    return true;
                }
            }
        }
        return false;
    }

    public void tap(String runId, int x, int y) {
        AppiumDriver driver = driverFor(runId);
        System.out.println("[TAP] Koordinatlar: x=" + x + ", y=" + y);

        if (x <= 0 || y <= 0) {
            throw new IllegalArgumentException("Geçersiz tap koordinatı: x=" + x + ", y=" + y);
        }

        try {
            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence tap = new Sequence(finger, 1);
            tap.addAction(finger.createPointerMove(Duration.ZERO,
                    PointerInput.Origin.viewport(), x, y));
            tap.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            tap.addAction(new Pause(finger, Duration.ofMillis(80)));
            tap.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

            driver.perform(List.of(tap));
            System.out.println("[TAP] Tap tamamlandı");

            Thread.sleep(500);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tap kesildi", e);
        } catch (Exception e) {
            System.err.println("[TAP] Hata: " + e.getMessage());
            throw new RuntimeException("Tap failed: " + e.getMessage(), e);
        }
    }

    /**
     * direction EKRANIN/ICERIGIN GORSEL OLARAK hangi yone kaydigini ifade ediyor:
     *   direction="down"  -> EKRANI ASAGI kaydir -> SONRAKI/ALTTAKI ogeler gorunur
     *   direction="up"    -> EKRANI YUKARI kaydir -> ONCEKI/USTTEKI ogeler gorunur
     */
    public void swipe(String runId, String direction) {
        AppiumDriver driver = driverFor(runId);
        Dimension size = driver.manage().window().getSize();
        int width = size.getWidth();
        int height = size.getHeight();

        int startX, startY, endX, endY;

        switch (direction == null ? "" : direction.toLowerCase()) {
            case "down" -> {
                startX = width / 2;
                startY = (int) (height * 0.7);
                endX = width / 2;
                endY = (int) (height * 0.2);
            }
            case "up" -> {
                startX = width / 2;
                startY = (int) (height * 0.2);
                endX = width / 2;
                endY = (int) (height * 0.7);
            }
            case "right" -> {
                startX = (int) (width * 0.8);
                startY = height / 2;
                endX = (int) (width * 0.2);
                endY = height / 2;
            }
            case "left" -> {
                startX = (int) (width * 0.2);
                startY = height / 2;
                endX = (int) (width * 0.8);
                endY = height / 2;
            }
            default -> {
                startX = width / 2;
                startY = (int) (height * 0.7);
                endX = width / 2;
                endY = (int) (height * 0.2);
            }
        }

        System.out.println("[SWIPE] direction=" + direction
                + " start=(" + startX + "," + startY + ")"
                + " end=(" + endX + "," + endY + ")"
                + " screen=" + width + "x" + height);

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence swipe = new Sequence(finger, 1);
        swipe.addAction(finger.createPointerMove(Duration.ZERO,
                PointerInput.Origin.viewport(), startX, startY));
        swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        swipe.addAction(finger.createPointerMove(Duration.ofMillis(600),
                PointerInput.Origin.viewport(), endX, endY));
        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        driver.perform(List.of(swipe));

        // [DUZELTME 2026-09-10] 500ms -> 1500ms. Onceki degerde UI tam oturmadan
        // getPageSource() cagriliyordu -- yeni XML gelmedigi icin model eski ekrani gorup
        // ayni karari tekrar veriyordu.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void typeText(String runId, int x, int y, String text) {
        AppiumDriver driver = driverFor(runId);
        tap(runId, x, y);
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {}

        try {
            driver.switchTo().activeElement().sendKeys(text);
        } catch (Exception e) {
            try {
                Thread.sleep(800);
                driver.switchTo().activeElement().sendKeys(text);
            } catch (Exception e2) {
                throw new RuntimeException("Input alanına yazılamadı: " + e2.getMessage(), e2);
            }
        }

        try {
            Thread.sleep(300);
            if (driver instanceof AndroidDriver) {
                ((AndroidDriver) driver).pressKey(new KeyEvent(AndroidKey.ENTER));
            } else if (driver instanceof IOSDriver) {
                ((JavascriptExecutor) driver).executeScript("mobile: pressKey", Map.of("key", "Return"));
            }
        } catch (Exception ignored) {
        }
    }

    // ============================================================================================
    // invalidateSession -- DÜZELTİLDİ (2026-09-10)
    //
    // ÖNCEKİ: Sadece map'ten siliyordu, quit() ÇAĞIRMIYORDU -> Appium server'da orphan
    // session kaliyordu. Yeni session acildiginda ayni cihaza bagli 2 Appium oturumu
    // olusuyordu.
    // ============================================================================================
    public void invalidateSession(String runId) {
        AppiumDriver driver = drivers.remove(runId);
        if (driver == null) return;
        try {
            driver.quit();
            System.out.println("[session] invalidate -> quit() başarılı");
        } catch (Exception e) {
            System.out.println("[session] invalidate -> quit() başarısız (görmezden gelindi): " + e.getMessage());
        }
    }

    public void stopSession(String runId) {
        AppiumDriver driver = drivers.remove(runId);
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                System.out.println("[session] stopSession quit() uyarısı: " + e.getMessage());
            }
        }
    }

    public void startScreenRecording(String runId) {
        try {
            ((CanRecordScreen) driverFor(runId)).startRecordingScreen();
        } catch (Exception e) {
            System.out.println("Ekran kaydı başlatılamadı: " + e.getMessage());
        }
    }

    public boolean stopScreenRecordingAndSave(String runId) {
        try {
            String base64Video = ((CanRecordScreen) driverFor(runId)).stopRecordingScreen();
            if (base64Video == null || base64Video.isBlank()) return false;
            byte[] videoBytes = Base64.getDecoder().decode(base64Video);
            Path dir = Paths.get("videos");
            Files.createDirectories(dir);
            Files.write(dir.resolve(runId + ".mp4"), videoBytes);
            return true;
        } catch (Exception e) {
            System.out.println("Ekran kaydı kaydedilemedi: " + e.getMessage());
            return false;
        }
    }

    public byte[] readVideo(String runId) {
        try {
            Path path = Paths.get("videos", runId + ".mp4");
            if (!Files.exists(path)) return null;
            return Files.readAllBytes(path);
        } catch (Exception e) {
            return null;
        }
    }
}
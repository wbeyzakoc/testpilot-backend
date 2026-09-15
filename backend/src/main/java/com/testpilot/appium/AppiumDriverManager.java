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
    // resetToFreshState -- clearApp -> activate (2026-09-11)
    // ============================================================================================
    public void resetToFreshState(String runId, String platform, String appIdentifier) {
        AppiumDriver driver = driverFor(runId);
        InteractsWithApps apps = (InteractsWithApps) driver;

        try {
            if ("ios".equalsIgnoreCase(platform)) {
                apps.activateApp(appIdentifier);
                Thread.sleep(3000);
                return;
            }

            // ---- Android ----
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
    //
    // [DUZELTME 2026-09-15] Elem record'una `enabled` alanı eklendi. Android XML'inde
    // enabled="false" olan elementlere yapılan tap HİÇBİR metotta işe yaramaz (UiAutomator
    // touch event'i disabled View'lara iletmez). Bu durumu yakalamak için ayrı bir bayrak.
    // ============================================================================================
    private record Elem(String label, String bounds, boolean clickable, boolean isPassword, String className,
                        String resourceId, String text, String contentDesc, boolean enabled) {}

    private List<Elem> parseNumberedElements(String rawPageSource) {
        if (rawPageSource == null) return List.of();

        List<Elem> collected = new ArrayList<>();

        Pattern tagPattern = Pattern.compile("<[^<>]+/>");
        Matcher tagMatcher = tagPattern.matcher(rawPageSource);

        while (tagMatcher.find()) {
            String tag = tagMatcher.group();

            boolean clickable = tag.contains("clickable=\"true\"") || tag.contains("accessible=\"true\"");
            boolean isPassword = tag.contains("password=\"true\"");
            // [YENİ 2026-09-15] enabled="false" = uygulama kasten devre dışı bırakmış.
            // Attribute yoksa enabled=true kabul.
            boolean enabled = !tag.contains("enabled=\"false\"");

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
            if (text != null && !text.isBlank()) {
                label = text;
            } else if (desc != null && !desc.isBlank()) {
                label = desc;
            } else if (resourceIdLabel != null && !resourceIdLabel.isBlank()) {
                label = resourceIdLabel;
            } else if (isPassword) {
                label = "(sifre alani)";
            } else {
                label = "";
            }
            boolean hasLabel = !label.isBlank();

            if (hasLabel || clickable || isPassword) {
                collected.add(new Elem(label, bounds, clickable, isPassword, className,
                        resourceId, text, desc, enabled));
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
                    e.resourceId(), e.text(), e.contentDesc(), e.enabled()));
        }
        return numbered;
    }

    /**
     * Ekrandaki tüm element label'larını basit liste olarak döndürür (debug/analiz için).
     * Disambiguation yapmaz, ham label'ları döndürür.
     */
    public List<String> parseAllElementLabels(String rawPageSource) {
        if (rawPageSource == null) return List.of();

        List<String> labels = new ArrayList<>();
        List<Elem> numbered = parseNumberedElements(rawPageSource);

        for (Elem e : numbered) {
            if (!e.label().isBlank()) {
                labels.add(e.label());
            } else if (e.isPassword()) {
                labels.add("(sifre alani)");
            } else if (!e.resourceId().isBlank()) {
                labels.add(e.resourceId());
            }
        }
        return labels;
    }

    private static final int CHAR_BUDGET = 8000;

    private List<Elem> buildEmittedList(String rawPageSource, String goal) {
        List<Elem> numbered = parseNumberedElements(rawPageSource);

        List<Elem> allForContext = new ArrayList<>(numbered);
        List<Elem> enrichedNumbered = new ArrayList<>(numbered.size());
        for (Elem e : numbered) {
            String enrichedLabel = enrichImageLabel(e, allForContext);
            enrichedNumbered.add(enrichedLabel.equals(e.label())
                    ? e
                    : new Elem(enrichedLabel, e.bounds(), e.clickable(), e.isPassword(),
                    e.className(), e.resourceId(), e.text(), e.contentDesc(), e.enabled()));
        }

        List<Elem> labeledEls = new ArrayList<>();
        List<Elem> textOnlyEls = new ArrayList<>();
        List<Elem> unlabeledEls = new ArrayList<>();

        for (Elem e : enrichedNumbered) {
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

        List<Elem> emitted = new ArrayList<>();
        int budgetUsed = 0;
        for (Elem e : ordered) {
            String line = formatLine(emitted.size() + 1, e);
            if (budgetUsed + line.length() + 1 > CHAR_BUDGET) break;
            budgetUsed += line.length() + 1;
            emitted.add(e);
        }
        return emitted;
    }

    private List<Elem> buildEmittedList(String rawPageSource) {
        return buildEmittedList(rawPageSource, null);
    }

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
                        e.className(), e.resourceId(), e.text(), e.contentDesc(), e.enabled()));
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
    // enrichImageLabel -- görsel/görünmez etiketli elementlere [product: X] zenginleştirmesi
    // ============================================================================================
    private static final Set<String> IMAGE_LIKE_CLASS_HINTS = Set.of(
            "image", "icon", "photo", "picture", "thumbnail", "cover", "avatar"
    );

    private boolean looksLikeImageElement(Elem e) {
        String label = e.label();
        String lower = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (lower.contains("image") || lower.contains("görsel") || lower.contains("resim")
                || lower.contains("foto") || lower.contains("photo")) {
            return true;
        }
        String className = e.className() == null ? "" : e.className().toLowerCase(Locale.ROOT);
        for (String hint : IMAGE_LIKE_CLASS_HINTS) {
            if (className.contains(hint)) return true;
        }
        // [DUZELTME 2026-09-15] Devre dışı elementler zenginleştirme adayı DEĞİL.
        return (label == null || label.isBlank()) && e.clickable() && e.enabled();
    }

    private String enrichImageLabel(Elem e, List<Elem> allElements) {
        String label = e.label() == null ? "" : e.label();
        if (!looksLikeImageElement(e)) return label;
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
            return label.isBlank()
                    ? "[product: " + nearest.label() + "]"
                    : label + " [product: " + nearest.label() + "]";
        }
        return label;
    }

    // ============================================================================================
    // formatLine -- sade format + [devre-dışı] işareti (2026-09-15)
    // ============================================================================================
    private String formatLine(int id, Elem e) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(id).append("]");
        if (e.clickable()) sb.append(" tıklanabilir");
        if (e.isPassword()) sb.append(" şifre-alanı");
        // [YENİ 2026-09-15] Devre dışı elementler XML'de net şekilde işaretlenir --
        // model "clickable=true" görüp tap etmeye çalışmasın, önce durumu değiştirsin.
        if (!e.enabled()) sb.append(" [devre-dışı]");
        if (!e.label().isBlank()) {
            sb.append(" \"").append(e.label().replace("\"", "'")).append("\"");
        }
        if (e.text() != null && !e.text().isBlank() && !e.text().equalsIgnoreCase(e.label())) {
            sb.append(" text=\"").append(e.text().replace("\"", "'")).append("\"");
        }
        if (e.contentDesc() != null && !e.contentDesc().isBlank()
                && !e.contentDesc().equalsIgnoreCase(e.label())
                && !e.contentDesc().equalsIgnoreCase(e.text())) {
            sb.append(" desc=\"").append(e.contentDesc().replace("\"", "'")).append("\"");
        }
        if (e.className() != null && !e.className().isBlank()) {
            sb.append(" (").append(e.className()).append(")");
        }
        return sb.toString();
    }

    // ============================================================================================
    // [YENİ 2026-09-15] SİSTEM İZİN DİYALOĞU OTOMATİK YÖNETİMİ
    //
    // Senaryo sırasında uygulama aniden native Android izin diyaloğu açabilir (konum,
    // kamera, bildirim, kişiler, depolama vb.). Bu diyaloglar uygulamanın kendi UI'ı
    // DEĞİLDİR -- com.android.permissioncontroller veya com.android.packageinstaller
    // gibi sistem paketleri tarafından render edilirler. Normal element arama
    // mekanizmamız bu diyalogları görmezden gelir çünkü hedef metin (ör. "DEVAM")
    // hâlâ arkada duruyor, ama tıklama gerçekleşmez çünkü diyalog modal katman olarak
    // üstte.
    //
    // ÇÖZÜM: Her adımdan ÖNCE ve her tap'ten SONRA bu diyaloğu tespit edip otomatik
    // olarak "İzin ver" / "Uygulamayı kullanırken" / "Allow" gibi butona basıyoruz.
    // ============================================================================================
    public record PermissionDialogResult(boolean handled, String dialogTitle,
                                         String buttonClicked, int tapX, int tapY,
                                         String newPageSource) {}

    // Bilinen sistem diyalog paketleri
    private static final List<String> SYSTEM_DIALOG_PACKAGES = List.of(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
    );

    // Allow butonlarının bilinen resource-id'leri (öncelik sırasına göre)
    private static final List<String> ALLOW_BUTTON_IDS = List.of(
            "permission_allow_foreground_only_button",  // "Uygulamayı kullanırken" (Android 10+)
            "permission_allow_one_time_button",         // "Yalnızca bu sefer" (Android 11+)
            "permission_allow_always_button",           // "Her zaman izin ver" (eski)
            "permission_allow_button",                  // "İzin ver" (Android 13 bildirim)
            "permission_continue_button",               // "Devam" (bazı özel izinler)
            "grant_confirm_button",                     // Eski Android
            "allow_button"
    );

    // Deny butonlarının bilinen resource-id'leri -- bunlar ASLA tıklanmamalı
    private static final List<String> DENY_BUTTON_IDS = List.of(
            "permission_deny_button",
            "permission_deny_and_dont_ask_again_button",
            "deny_button"
    );

    // Allow butonlarının metin karşılıkları (TR + EN + birkaç dil)
    private static final List<String> ALLOW_BUTTON_TEXTS = List.of(
            // Türkçe
            "i̇zin ver", "izin ver", "uygulamayı kullanırken", "uygulamayi kullanirken",
            "yalnızca bu sefer", "yalnizca bu sefer", "sadece bu sefer",
            "her zaman izin ver", "herzaman izin ver",
            "izin ver ve devam et", "tamam", "devam et", "kabul et", "onayla",
            "etkinleştir", "etkinlestir", "evet",
            // İngilizce
            "allow", "allow all", "allow always", "while using the app",
            "only this time", "ok", "continue", "yes", "enable", "grant", "accept",
            // Diğer diller
            "zulassen", "erlauben", "autoriser", "permitir"
    );

    // Deny buton metinleri -- bunlar ASLA tıklanmamalı
    private static final List<String> DENY_BUTTON_TEXTS = List.of(
            "izin verme", "izin verme ve bir daha sorma", "reddet", "hayır", "hayir", "iptal",
            "deny", "don't allow", "dont allow", "deny all", "cancel", "no",
            "verweigern", "ablehnen", "refuser", "denegar", "rechazar"
    );

    /**
     * Aktif sayfa kaynağında bir sistem izin diyaloğu olup olmadığını kontrol eder.
     * Varsa uygun "İzin ver" butonuna otomatik tıklar.
     */
    public PermissionDialogResult tryHandleSystemPermissionDialog(String runId, String rawPageSource) {
        if (rawPageSource == null || rawPageSource.isBlank()) {
            return new PermissionDialogResult(false, null, null, 0, 0, rawPageSource);
        }

        // 1) Sistem diyaloğu mu? (package attribute'una göre)
        boolean isSystemDialog = false;
        for (String pkg : SYSTEM_DIALOG_PACKAGES) {
            if (rawPageSource.contains("package=\"" + pkg + "\"")
                    || rawPageSource.contains("package='" + pkg + "'")) {
                isSystemDialog = true;
                break;
            }
        }
        if (!isSystemDialog) {
            return new PermissionDialogResult(false, null, null, 0, 0, rawPageSource);
        }

        System.out.println("[PERM] 🔔 Sistem izin diyaloğu tespit edildi, otomatik yanıtlanıyor...");

        String dialogTitle = extractDialogTitle(rawPageSource);

        List<Elem> numbered = parseNumberedElements(rawPageSource);
        Elem targetButton = null;
        String matchedBy = null;

        // 2) Bilinen "allow" resource-id'lerini ara (en güvenilir)
        for (String allowId : ALLOW_BUTTON_IDS) {
            for (Elem e : numbered) {
                if (e.resourceId() != null && e.resourceId().contains(allowId)) {
                    boolean isDeny = false;
                    for (String denyId : DENY_BUTTON_IDS) {
                        if (e.resourceId().contains(denyId)) {
                            isDeny = true;
                            break;
                        }
                    }
                    if (isDeny) continue;

                    targetButton = e;
                    matchedBy = "id:" + allowId;
                    break;
                }
            }
            if (targetButton != null) break;
        }

        // 3) ID bulunamadıysa metin bazlı eşleştirme
        if (targetButton == null) {
            for (Elem e : numbered) {
                String label = e.label() == null ? "" : e.label().toLowerCase(Locale.ROOT).trim();
                if (label.isBlank()) continue;

                boolean isDenyText = false;
                for (String denyText : DENY_BUTTON_TEXTS) {
                    if (label.equals(denyText) || label.startsWith(denyText)) {
                        isDenyText = true;
                        break;
                    }
                }
                if (isDenyText) continue;

                for (String allowText : ALLOW_BUTTON_TEXTS) {
                    if (label.equals(allowText) || label.contains(allowText)) {
                        targetButton = e;
                        matchedBy = "text:" + allowText;
                        break;
                    }
                }
                if (targetButton != null) break;
            }
        }

        // 4) Buton bulunamadıysa logla ve çık
        if (targetButton == null) {
            System.out.println("[PERM] ⚠ Sistem diyaloğu tespit edildi ama 'İzin ver' butonu bulunamadı.");
            System.out.println("[PERM] Diyalog başlığı: " + dialogTitle);
            return new PermissionDialogResult(false, dialogTitle, null, 0, 0, rawPageSource);
        }

        // 5) Butonun koordinatlarını hesapla ve tap et
        int[] center = centerOfBounds(targetButton.bounds());
        if (center == null) {
            System.out.println("[PERM] ⚠ Buton bulundu ama koordinat hesaplanamadı: " + targetButton.label());
            return new PermissionDialogResult(false, dialogTitle, targetButton.label(), 0, 0, rawPageSource);
        }

        int[] adjusted = adjustForSafeTapArea(runId, center, parseBounds(targetButton.bounds()));
        if (adjusted != null) {
            center = adjusted;
        }

        System.out.println("[PERM] ✓ '" + targetButton.label() + "' butonuna basılıyor ("
                + matchedBy + ", x=" + center[0] + ", y=" + center[1] + ")");

        try {
            tap(runId, center[0], center[1]);
        } catch (Exception tapEx) {
            System.out.println("[PERM] ✗ Tap başarısız: " + tapEx.getMessage());
            return new PermissionDialogResult(false, dialogTitle, targetButton.label(),
                    center[0], center[1], rawPageSource);
        }

        // 6) Kısa bekleme ve yeni XML
        sleepQuietly(600);
        String newPageSource;
        try {
            newPageSource = getPageSource(runId);
        } catch (Exception e) {
            newPageSource = rawPageSource;
        }

        System.out.println("[PERM] ✓ İzin diyaloğu yanıtlandı, akış devam ediyor.");
        return new PermissionDialogResult(true, dialogTitle, targetButton.label(),
                center[0], center[1], newPageSource);
    }

    /**
     * Sistem izin diyaloğunun başlığını XML'den çıkarır -- yalnızca loglama amaçlı.
     */
    private String extractDialogTitle(String rawPageSource) {
        if (rawPageSource == null) return null;
        Pattern textPat = Pattern.compile("text=\"([^\"]{10,200})\"");
        Matcher m = textPat.matcher(rawPageSource);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (!candidate.isBlank() && !candidate.equalsIgnoreCase("null")) {
                return candidate;
            }
        }
        return null;
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
    // STOPWORDS -- çok dilli HashSet
    // ============================================================================================
    private static final Set<String> STOP_WORDS = buildStopWords();

    private static Set<String> buildStopWords() {
        Set<String> w = new HashSet<>();

        Collections.addAll(w,
                "bir","ve","ile","için","bu","şu","o","veya","ama","fakat","ki",
                "olan","olarak","adlı","isimli","geçen","içeren",
                "tıkla","tıklayın","bas","bul","bulun","ara","git","aç","seç","ekle",
                "buton","butonu","butona","butonuna","düğme","düğmesi","düğmesine",
                "ürün","ürünü","ürüne","ürününe","ürününü","öğe","öğesi","öğesine",
                "kontrol","et","dene","göster","listele","tamamla","doğrula","onayla"
        );

        Collections.addAll(w,
                "the","a","an","and","or","but","if",
                "in","on","at","to","of","with","for","from",
                "this","that","is","are",
                "click","tap","find","open","check","verify","navigate"
        );

        Collections.addAll(w,
                "der","die","das","und","oder","aber",
                "in","auf","mit","zu","für",
                "klicken","suchen","finden","öffnen","prüfen"
        );

        Collections.addAll(w,
                "le","la","les","et","ou","mais",
                "dans","sur","avec","pour","vers",
                "cliquer","chercher","trouver","ouvrir","vérifier"
        );

        Collections.addAll(w,
                "el","la","los","las","y","o","pero",
                "en","con","por","para","sobre",
                "clic","buscar","encontrar","abrir","verificar"
        );

        Collections.addAll(w,
                "il","lo","la","e","o","ma",
                "in","con","per","su",
                "clicca","cerca","trovare","aprire","verificare"
        );

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

    private String searchableHaystack(Elem e) {
        StringBuilder sb = new StringBuilder();
        if (e.label() != null) sb.append(e.label()).append(' ');
        if (e.text() != null) sb.append(e.text()).append(' ');
        if (e.contentDesc() != null) sb.append(e.contentDesc()).append(' ');
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private String normalizeForContains(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zçğıöşü0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isImageAssetValue(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.ROOT).trim();
        v = v.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
        if (v.contains("assets/") || v.contains("/img/") || v.contains("\\img\\")) return true;
        return v.matches(".*\\.(png|jpe?g|gif|webp|svg|bmp)$");
    }

    // ============================================================================================
    // findMatchingTarget -- 5 TIER (TAM ÜRÜN ADI, [product:X], tam eşleşme, ayırt edici kelime, 3+)
    // ============================================================================================
    public String findMatchingTarget(String rawPageSource, String goal) {
        if (rawPageSource == null || goal == null || goal.isBlank()) return null;

        String goalLower = goal.toLowerCase(Locale.ROOT);
        List<Elem> enriched = buildEnrichedList(rawPageSource);

        Set<String> goalContentWords = extractContentWords(goal);
        if (goalContentWords.isEmpty()) {
            System.out.println("[findMatch] Goal'da anlamli kelime yok");
            return null;
        }

        // TIER 0: TAM ÜRÜN ADI
        String exactProductName = extractExactProductName(goal);
        if (exactProductName != null) {
            System.out.println("[findMatch] TIER-0 (TAM ÜRÜN ADI) aranıyor: " + exactProductName);

            String normalizedExact = normalizeForContains(exactProductName);
            for (Elem e : enriched) {
                String haystack = searchableHaystack(e);
                String normalizedHaystack = normalizeForContains(haystack);
                if (haystack.contains(exactProductName.toLowerCase(Locale.ROOT))
                        || (!normalizedExact.isBlank() && normalizedHaystack.contains(normalizedExact))) {
                    String selected = selectTier0TargetLabel(e, exactProductName);
                    System.out.println("[findMatch] TIER-0 EŞLEŞTİ: " + selected
                            + " (element label='" + e.label() + "')");
                    return selected;
                }
            }

            System.out.println("[findMatch] TIER-0: " + exactProductName + " bulunamadı");

            Set<String> exactTokens = extractContentWords(exactProductName);
            Elem constrainedBest = null;
            int constrainedBestScore = Integer.MIN_VALUE;
            for (Elem e : enriched) {
                String hayNorm = normalizeForContains(searchableHaystack(e));
                if (hayNorm.isBlank()) continue;
                boolean allPresent = true;
                int hits = 0;
                for (String token : exactTokens) {
                    String t = normalizeForContains(token);
                    if (t.isBlank()) continue;
                    if (!hayNorm.contains(t)) {
                        allPresent = false;
                        break;
                    }
                    hits++;
                }
                if (!allPresent || hits == 0) continue;
                int score = hits * 100;
                if (e.clickable()) score += 10;
                if (e.label() != null && e.label().toLowerCase(Locale.ROOT).contains("[product:")) score += 5;
                if (score > constrainedBestScore) {
                    constrainedBestScore = score;
                    constrainedBest = e;
                }
            }
            if (constrainedBest != null) {
                String selected = selectTier0TargetLabel(constrainedBest, exactProductName);
                System.out.println("[findMatch] TIER-0B (TOKEN KATILIK) EŞLEŞTİ: " + selected
                        + " (element label='" + constrainedBest.label() + "')");
                return selected;
            }
        }

        Map<String, Integer> keywordFrequency = new HashMap<>();
        for (String kw : goalContentWords) {
            int count = 0;
            for (Elem e : enriched) {
                if (searchableHaystack(e).contains(kw)) count++;
            }
            if (count > 0) keywordFrequency.put(kw, count);
        }

        System.out.println("[findMatch] goal=\"" + goal + "\"");
        System.out.println("[findMatch] keyword frekanslari: " + keywordFrequency);

        Pattern urunPattern = Pattern.compile("\\[product:\\s*([^\\]]+)\\]");

        Elem bestMatch = null;
        int bestScore = 0;
        String bestTier = null;

        for (Elem e : enriched) {
            String label = e.label();
            String labelLower = searchableHaystack(e);
            int score = 0;
            String tier = null;

            Matcher m = urunPattern.matcher(label);
            if (m.find()) {
                String productName = m.group(1).trim();
                if (productName.length() >= 5
                        && goalLower.contains(productName.toLowerCase(Locale.ROOT))) {
                    score = productName.length() * 3 + 100;
                    tier = "TIER-1";
                }
            }

            if (score == 0) {
                List<String> plainCandidates = new ArrayList<>();
                plainCandidates.add(label.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim());
                if (e.text() != null && !e.text().isBlank()) plainCandidates.add(e.text().trim());
                if (e.contentDesc() != null && !e.contentDesc().isBlank()) plainCandidates.add(e.contentDesc().trim());

                for (String plain : plainCandidates) {
                    if (plain.length() >= 8 && goalLower.contains(plain.toLowerCase(Locale.ROOT))) {
                        long wc = 0;
                        for (String w : plain.split("\\s+")) if (w.length() >= 3) wc++;
                        if (wc >= 2) {
                            score = plain.length() * 2 + 50;
                            tier = "TIER-2";
                            break;
                        }
                    }
                }
            }

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

    public String extractExactProductName(String goal) {
        if (goal == null || goal.isBlank()) return null;

        String goalLower = goal.toLowerCase(Locale.ROOT);

        Matcher quoted = Pattern.compile("[\"'“”']\\s*([^\"'“”']{2,}?)\\s*[\"'“”']").matcher(goal);
        String bestQuoted = null;
        while (quoted.find()) {
            String q = quoted.group(1).trim();
            if (q.length() >= 2 && (bestQuoted == null || q.length() > bestQuoted.length())) {
                bestQuoted = q;
            }
        }
        if (bestQuoted != null) {
            System.out.println("[findMatch] Tırnak içi tam ürün adı kullanılıyor: " + bestQuoted);
            return bestQuoted;
        }

        if (goalLower.contains("içerisinde") || goalLower.contains("içinde") ||
                goalLower.contains("yazan") || goalLower.contains("olan") ||
                goalLower.contains("içeren") || goalLower.contains("içinde geçen")) {
            System.out.println("[findMatch] Genel arama ifadesi bulundu, TAM AD eşleşmesi yapılmıyor");
            return null;
        }

        Pattern productPattern = Pattern.compile("([A-Za-z\\s]+\\s\\([^)]+\\))");
        Matcher matcher = productPattern.matcher(goal);

        while (matcher.find()) {
            String potentialProductName = matcher.group(1).trim();
            if (potentialProductName.length() >= 10) {
                System.out.println("[findMatch] Potansiyel tam ürün adı bulundu: " + potentialProductName);
                return potentialProductName;
            }
        }

        String[] words = goalLower.split("\\s+");
        StringBuilder productName = new StringBuilder();
        int meaningfulWords = 0;
        for (int i = 0; i < words.length && meaningfulWords < 4; i++) {
            String word = words[i].replaceAll("[^a-zçğıöşü0-9]", "");
            if (word.isBlank() || STOP_WORDS.contains(word)) continue;
            if (productName.length() > 0) productName.append(" ");
            productName.append(word);
            meaningfulWords++;
        }
        if (meaningfulWords >= 3 && productName.length() >= 15) {
            System.out.println("[findMatch] Alternatif tam ürün adı: " + productName);
            return productName.toString();
        }

        return null;
    }

    private String selectTier0TargetLabel(Elem e, String exactProductName) {
        if (e == null) return exactProductName;
        String exactLower = exactProductName == null ? "" : exactProductName.toLowerCase(Locale.ROOT);
        String normalizedExact = normalizeForContains(exactProductName);
        List<String> candidates = new ArrayList<>();
        if (e.label() != null && !e.label().isBlank()) candidates.add(e.label());
        if (e.text() != null && !e.text().isBlank()) candidates.add(e.text());
        if (e.contentDesc() != null && !e.contentDesc().isBlank()) candidates.add(e.contentDesc());

        for (String c : candidates) {
            String norm = c.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
            if (norm.isBlank()) continue;
            String normLower = norm.toLowerCase(Locale.ROOT);
            String normalizedNorm = normalizeForContains(norm);
            if (normLower.contains(exactLower)) return c;
            if (!normalizedExact.isBlank() && normalizedNorm.contains(normalizedExact)) return c;
        }

        for (String c : candidates) {
            String norm = c.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
            if (norm.isBlank()) continue;
            if (isImageAssetValue(norm)) continue;
            if (norm.matches("^[\\$€£¥]\\s*\\d.*")) continue;
            return c;
        }

        return exactProductName;
    }

    // ============================================================================================
    // [YENİ 2026-09-15] Devre dışı element kontrolü (enabled=false)
    //
    // [DUZELTME] buildEnrichedList yerine parseNumberedElements kullanılıyor çünkü:
    // - buildEnrichedList orijinal etiketleri kullanır (duplicate label'lar ayırt edilmez)
    // - parseNumberedElements disambiguation yapar ("DEVAM (1)", "DEVAM (2)")
    // - Tap işlemi parseNumberedElements ile koordinat çözdüğü için,
    //   disabled kontrolü de aynı element'i kontrol etmeli
    // ============================================================================================
    public boolean isTargetDisabled(String rawPageSource, String targetLabel) {
        if (rawPageSource == null || targetLabel == null || targetLabel.isBlank()) return false;

        // parseNumberedElements kullanarak disambiguated label'larla çalış
        List<Elem> numbered = parseNumberedElements(rawPageSource);

        // Önce tam eşleşme ara (disambiguation dahil)
        for (Elem e : numbered) {
            if (e.label().equals(targetLabel) || e.label().equalsIgnoreCase(targetLabel)) {
                return !e.enabled();
            }
        }

        // Tam eşleşme yoksa findUniqueElemByLabel ile dene
        Elem match = findUniqueElemByLabel(numbered, targetLabel);
        return match != null && !match.enabled();
    }

    public boolean isTargetEnabled(String rawPageSource, String targetLabel) {
        return !isTargetDisabled(rawPageSource, targetLabel);
    }

    // ============================================================================================
    // labelForElementId -- model "N" seçtiyse gerçek etiketi döner
    // ============================================================================================
    public String labelForElementId(String rawPageSource, String elementId, String goal) {
        if (rawPageSource == null) return null;
        Integer id = parsePlainInt(elementId);
        if (id == null) return null;
        List<Elem> emitted = buildEmittedList(rawPageSource, goal);
        if (id < 1 || id > emitted.size()) return null;
        return emitted.get(id - 1).label();
    }

    // ============================================================================================
    // [DUZELTME 2026-09-15] GÜVENLİ TAP BÖLGESİ (ANDROID GESTURE BAR'I)
    // ============================================================================================
    private static final int TAP_BOTTOM_SAFE_MARGIN_MIN = 120;
    private static final int TAP_TOP_SAFE_MARGIN_MIN = 40;
    private static final double TAP_BOTTOM_SAFE_MARGIN_RATIO = 0.06;
    private static final double TAP_TOP_SAFE_MARGIN_RATIO = 0.02;

    private int[] adjustForSafeTapArea(String runId, int[] center, int[] elementBounds) {
        if (center == null) return null;
        int[] size = getScreenSize(runId);
        if (size == null) return center;

        int screenW = size[0];
        int screenH = size[1];

        int topMargin = Math.max(TAP_TOP_SAFE_MARGIN_MIN,
                (int) (screenH * TAP_TOP_SAFE_MARGIN_RATIO));
        int bottomMargin = Math.max(TAP_BOTTOM_SAFE_MARGIN_MIN,
                (int) (screenH * TAP_BOTTOM_SAFE_MARGIN_RATIO));

        int cx = center[0];
        int cy = center[1];

        if (elementBounds == null || elementBounds.length != 4) {
            cy = Math.max(topMargin, Math.min(screenH - bottomMargin, cy));
            return new int[]{cx, cy};
        }

        int x1 = elementBounds[0], y1 = elementBounds[1];
        int x2 = elementBounds[2], y2 = elementBounds[3];
        int elemH = y2 - y1;
        int elemW = x2 - x1;
        if (elemH <= 0 || elemW <= 0) return center;

        int innerPadY = Math.max(4, elemH / 6);
        int innerPadX = Math.max(4, elemW / 6);

        int minY = Math.max(y1 + innerPadY, topMargin);
        int maxY = Math.min(y2 - innerPadY, screenH - bottomMargin);

        if (maxY < minY) {
            int preferred = screenH - bottomMargin - innerPadY;
            if (preferred > y2 - innerPadY) preferred = y2 - innerPadY;
            if (preferred < y1 + innerPadY) preferred = y1 + innerPadY;
            cy = preferred;
        } else {
            cy = Math.max(minY, Math.min(maxY, cy));
        }

        int minX = Math.max(x1 + innerPadX, 4);
        int maxX = Math.min(x2 - innerPadX, screenW - 4);
        if (maxX >= minX) {
            cx = Math.max(minX, Math.min(maxX, cx));
        }

        return new int[]{cx, cy};
    }

    // ============================================================================================
    // [YENİ 2026-09-15] UI ELEMENT LOCATOR'LARINI RAPORLA
    //
    // Bir elementin ID'sini, XPath'ini ve bounds'unu döner. RunController bu bilgiyi
    // loglama ve fallback locator stratejisinde kullanır.
    // ============================================================================================
    public record ElementLocator(String resourceId, String xpath, String contentDesc,
                                 String text, String bounds, boolean clickable,
                                 boolean enabled, String className) {}

    public ElementLocator findElementLocatorByLabel(String rawPageSource, String targetLabel, String goal) {
        if (rawPageSource == null || targetLabel == null || targetLabel.isBlank()) return null;

        List<Elem> emitted = buildEmittedList(rawPageSource, goal);
        Elem match = null;

        // Önce tam eşleşme
        for (Elem e : emitted) {
            String base = e.label().replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
            if (base.equalsIgnoreCase(targetLabel)) {
                match = e;
                break;
            }
        }
        // Sonra kısmi
        if (match == null) {
            for (Elem e : emitted) {
                if (e.label().toLowerCase(Locale.ROOT).contains(targetLabel.toLowerCase(Locale.ROOT))) {
                    match = e;
                    break;
                }
            }
        }
        if (match == null) return null;

        String xpath = buildXPath(rawPageSource, match);
        return new ElementLocator(
                match.resourceId(),
                xpath,
                match.contentDesc(),
                match.text(),
                match.bounds(),
                match.clickable(),
                match.enabled(),
                match.className()
        );
    }

    /**
     * [YENİ 2026-09-15] Element locator bilgisine göre canlı driver'da click.
     * mobile: clickGesture başarısız olursa alternatif olarak WebElement.click()
     * kullanır. Bu özellikle WebView içindeki butonlar için gereklidir.
     */
    public boolean clickElementByLocator(String runId, ElementLocator locator) {
        if (runId == null || locator == null) {
            System.out.println("[locator] ✗ Locator veya runId null");
            return false;
        }

        // 1. XPath ile WebElement bul ve click
        if (locator.xpath() != null && !locator.xpath().isBlank()) {
            try {
                AppiumDriver driver = driverFor(runId);
                WebElement el = driver.findElement(By.xpath(locator.xpath()));
                System.out.println("[locator] ✓ XPath ile element bulundu: " + locator.xpath());
                
                if (el.isDisplayed() && el.isEnabled()) {
                    // Element görünür ve aktif - tıkla
                    el.click();
                    System.out.println("[locator] ✅ WebElement.click() BAŞARILI (XPath): " + locator.xpath());
                    sleepQuietly(500);
                    return true;
                } else if (el.isDisplayed() && !el.isEnabled()) {
                    System.out.println("[locator] ⚠ Element bulundu ama DEVRE DIŞI (enabled=false)");
                    // Devre dışı - başka yöntem dene
                } else {
                    System.out.println("[locator] ⚠ Element bulundu ama: displayed=" + el.isDisplayed()
                            + ", enabled=" + el.isEnabled());
                }
            } catch (Exception e) {
                System.out.println("[locator] ✗ XPath ile element bulunamadı: " + e.getMessage());
            }
        }

        // 2. resource-id ile ID locator (daha güvenilir!)
        if (locator.resourceId() != null && !locator.resourceId().isBlank()) {
            try {
                AppiumDriver driver = driverFor(runId);
                WebElement el = driver.findElement(By.id(locator.resourceId()));
                System.out.println("[locator] ✓ ID ile element bulundu: " + locator.resourceId());
                
                if (el.isDisplayed() && el.isEnabled()) {
                    el.click();
                    System.out.println("[locator] ✅ By.id().click() BAŞARILI: " + locator.resourceId());
                    sleepQuietly(500);
                    return true;
                } else if (el.isDisplayed() && !el.isEnabled()) {
                    System.out.println("[locator] ⚠ ID ile element bulundu ama DEVRE DIŞI (enabled=false)");
                } else {
                    System.out.println("[locator] ⚠ ID ile element bulundu ama: displayed=" + el.isDisplayed()
                            + ", enabled=" + el.isEnabled());
                }
            } catch (Exception e) {
                System.out.println("[locator] ✗ ID ile element bulunamadı: " + e.getMessage());
            }
        }

        // 3. Accessibility id ile
        if (locator.contentDesc() != null && !locator.contentDesc().isBlank()) {
            try {
                AppiumDriver driver = driverFor(runId);
                WebElement el = driver.findElement(By.xpath(
                        "//*[@content-desc='" + locator.contentDesc().replace("'", "\\'") + "']"));
                System.out.println("[locator] ✓ Content-desc ile element bulundu: " + locator.contentDesc());
                
                if (el.isDisplayed() && el.isEnabled()) {
                    el.click();
                    System.out.println("[locator] ✅ By.content-desc().click() BAŞARILI: " + locator.contentDesc());
                    sleepQuietly(500);
                    return true;
                } else if (el.isDisplayed() && !el.isEnabled()) {
                    System.out.println("[locator] ⚠ Content-desc ile element bulundu ama DEVRE DIŞI (enabled=false)");
                } else {
                    System.out.println("[locator] ⚠ Content-desc ile element bulundu ama: displayed=" + el.isDisplayed()
                            + ", enabled=" + el.isEnabled());
                }
            } catch (Exception e) {
                System.out.println("[locator] ✗ Content-desc ile element bulunamadı: " + e.getMessage());
            }
        }

        // 4. ⭐ YENİ: clickable=false ama enabled=true ise BOUNDS koordinatlarıyla tap
        // TextView gibi non-clickable elementler genellikle parent container'da tıklanabilir
        if (locator.bounds() != null && !locator.bounds().isBlank()) {
            int[] bounds = parseBounds(locator.bounds());
            if (bounds != null) {
                int cx = (bounds[0] + bounds[2]) / 2;
                int cy = (bounds[1] + bounds[3]) / 2;
                System.out.println("[locator] 🎯 Element BOUNDS koordinatları: " + locator.bounds());
                System.out.println("[locator] 🎯 Merkez koordinat: (" + cx + "," + cy + ")");
                
                // Element enabled ama clickable değilse, bounds'a tıkla
                if (locator.enabled() && !locator.clickable()) {
                    System.out.println("[locator] ⚠ Element enabled=true ama clickable=false → BOUNDS tap deneniyor");
                }
                
                try {
                    tap(runId, cx, cy);
                    System.out.println("[locator] ✅ BOUNDS tap BAŞARILI: (" + cx + "," + cy + ")");
                    return true;
                } catch (Exception e) {
                    System.out.println("[locator] ✗ BOUNDS tap başarısız: " + e.getMessage());
                }
            }
        }

        // 5. Bounds ile tap (son çare - koordinat tabanlı)
        int[] bounds = parseBounds(locator.bounds());
        if (bounds != null) {
            int cx = (bounds[0] + bounds[2]) / 2;
            int cy = (bounds[1] + bounds[3]) / 2;
            System.out.println("[locator] 🎯 Bounds koordinatları çözüldü: (" + cx + "," + cy + ")");
            try {
                tap(runId, cx, cy);
                System.out.println("[locator] ✅ Bounds tap BAŞARILI: (" + cx + "," + cy + ")");
                return true;
            } catch (Exception e) {
                System.out.println("[locator] ✗ Bounds tap başarısız: " + e.getMessage());
            }
        }

        // TÜM YÖNTEMLER BAŞARISIZ
        System.out.println("[locator] ❌ clickElementByLocator: TÜM yöntemler başarısız!");
        System.out.println("[locator]   XPath: " + locator.xpath());
        System.out.println("[locator]   ID: " + locator.resourceId());
        System.out.println("[locator]   Content-desc: " + locator.contentDesc());
        System.out.println("[locator]   Bounds: " + locator.bounds());
        System.out.println("[locator]   Clickable: " + locator.clickable());
        System.out.println("[locator]   Enabled: " + locator.enabled());
        return false;
    }

    /**
     * [YENİ 2026-09-15] Element locator bilgisini log formatında döner.
     */
    public String formatLocatorForLog(ElementLocator loc) {
        if (loc == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        sb.append("text=\"").append(loc.text() == null ? "" : loc.text()).append("\"");
        if (loc.resourceId() != null && !loc.resourceId().isBlank()) {
            sb.append(", id=").append(loc.resourceId());
        }
        if (loc.contentDesc() != null && !loc.contentDesc().isBlank()) {
            sb.append(", desc=\"").append(loc.contentDesc()).append("\"");
        }
        if (loc.bounds() != null) {
            sb.append(", bounds=").append(loc.bounds());
        }
        sb.append(", clickable=").append(loc.clickable())
                .append(", enabled=").append(loc.enabled())
                .append(", class=").append(loc.className());
        return sb.toString();
    }


    // ============================================================================================
    // resolveTargetCenter -- TAMAMEN YENİDEN YAZILDI (2026-09-15)
    //
    // ÖNCEKİ SORUN: elementId null geldiğinde veya findUniqueElemByLabel duplicate
    // yüzünden null döndüğünde (ör. Android'de bir butonun HEM text="DEVAM" HEM
    // content-desc="DEVAM" olması), tüm çözümleme başarısız oluyor ve üst katman
    // (RunController) gereksiz scroll fallback'ine düşüyordu.
    //
    // YENİ STRATEJİ (5 kademeli fallback):
    //   1. elementId varsa -> element listesindeki [N-1] doğrudan al
    //   2. Bulunan label'ı TAM eşleşme ile tara (duplicate'leri yoksay)
    //   3. Bulunan label'ı "içerir" eşleşmesiyle tara (kısmi)
    //   4. Canlı driver'da XPath ile ara (resource-id / text / content-desc)
    //   5. Canlı driver'da WebElement listesi ile ara (By.xpath tüm olası kombinasyonlar)
    //
    // Ayrıca adjustForSafeTapArea sonrası koordinat başka bir elementin üstüne
    // düşerse ORIJINAL merkeze geri döneriz -- "güvenli bölge"nin yarardan çok zarar
    // verdiği dar element durumlarında tap'i garantiye alır.
    // ============================================================================================
    public int[] resolveTargetCenter(String runId, String rawPageSource, String elementId,
                                     String targetLabel, String goal) {
        if (rawPageSource == null && targetLabel == null) return null;

        System.out.println("[locator] Çözümleme başlıyor: elementId=" + elementId
                + ", target=" + targetLabel + ", goal=" + (goal == null ? "(yok)" : goal));

        // ---- iOS canlı XPath önceliği ----
        if (isIOSSession(runId) && targetLabel != null && !targetLabel.isBlank()) {
            int[] live = resolveByLiveXPath(runId, targetLabel);
            if (live != null) {
                System.out.println("[locator] iOS live XPath başarılı: (" + live[0] + "," + live[1] + ")");
                return safeAreaOrOriginal(runId, live, null);
            }
        }

        // ---- Kademe 1: elementId doğrudan ----
        Integer id = parsePlainInt(elementId);
        if (id != null && rawPageSource != null) {
            List<Elem> emitted = buildEmittedList(rawPageSource, goal);
            if (id >= 1 && id <= emitted.size()) {
                Elem byId = emitted.get(id - 1);
                System.out.println("[locator] Kademe-1: elementId=" + id + " -> '" + byId.label() + "'");

                int[] center;
                if (!byId.clickable()) {
                    int[] ancestorCenter = findClickableAncestorCenter(rawPageSource, byId.bounds());
                    center = ancestorCenter != null ? ancestorCenter : centerOfBounds(byId.bounds());
                } else {
                    center = centerOfBounds(byId.bounds());
                }
                if (center != null) {
                    System.out.println("[locator] Kademe-1 BAŞARILI: (" + center[0] + "," + center[1] + ")");
                    return safeAreaOrOriginal(runId, center, parseBounds(byId.bounds()));
                }
            }
        }

        // ---- Kademe 2: targetLabel TAM eşleşme (duplicate'leri yoksay) ----
        if (targetLabel != null && !targetLabel.isBlank() && rawPageSource != null) {
            List<Elem> numbered = parseNumberedElements(rawPageSource);
            Elem exact = null;
            int exactCount = 0;
            for (Elem e : numbered) {
                String label = e.label();
                if (label == null || label.isBlank()) continue;
                // "(2)" suffix'ini kaldırarak karşılaştır
                String base = label.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
                String targetBase = targetLabel.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
                if (base.equalsIgnoreCase(targetBase)) {
                    exactCount++;
                    if (exact == null) exact = e;
                }
            }
            if (exact != null) {
                System.out.println("[locator] Kademe-2: '" + targetLabel + "' bulundu ("
                        + exactCount + " adet, ilki kullanılıyor)");
                int[] center;
                if (!exact.clickable()) {
                    int[] ancestorCenter = findClickableAncestorCenter(rawPageSource, exact.bounds());
                    center = ancestorCenter != null ? ancestorCenter : centerOfBounds(exact.bounds());
                } else {
                    center = centerOfBounds(exact.bounds());
                }
                if (center != null) {
                    System.out.println("[locator] Kademe-2 BAŞARILI: (" + center[0] + "," + center[1] + ")");
                    return safeAreaOrOriginal(runId, center, parseBounds(exact.bounds()));
                }
            }
        }

        // ---- Kademe 3: targetLabel "içerir" eşleşmesi ----
        if (targetLabel != null && !targetLabel.isBlank() && rawPageSource != null) {
            List<Elem> numbered = parseNumberedElements(rawPageSource);
            Elem partial = null;
            for (Elem e : numbered) {
                String label = e.label();
                if (label == null || label.isBlank()) continue;
                if (label.toLowerCase(Locale.ROOT).contains(targetLabel.toLowerCase(Locale.ROOT))
                        || targetLabel.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) {
                    if (partial == null || (e.clickable() && !partial.clickable())) {
                        partial = e;
                    }
                }
            }
            if (partial != null) {
                System.out.println("[locator] Kademe-3: '" + targetLabel + "' kısmi eşleşti -> '" + partial.label() + "'");
                int[] center;
                if (!partial.clickable()) {
                    int[] ancestorCenter = findClickableAncestorCenter(rawPageSource, partial.bounds());
                    center = ancestorCenter != null ? ancestorCenter : centerOfBounds(partial.bounds());
                } else {
                    center = centerOfBounds(partial.bounds());
                }
                if (center != null) {
                    System.out.println("[locator] Kademe-3 BAŞARILI: (" + center[0] + "," + center[1] + ")");
                    return safeAreaOrOriginal(runId, center, parseBounds(partial.bounds()));
                }
            }
        }

        // ---- Kademe 4: Canlı driver'da XPath (targetLabel veya elementId) ----
        if (runId != null && targetLabel != null && !targetLabel.isBlank()) {
            int[] live = resolveByLiveXPath(runId, targetLabel);
            if (live != null) {
                System.out.println("[locator] Kademe-4 (live XPath) BAŞARILI: (" + live[0] + "," + live[1] + ")");
                return safeAreaOrOriginal(runId, live, null);
            }
        }

        // ---- Kademe 5: elementId varsa, [N] numarasına göre canlı XML'den yeniden ----
        if (id != null && runId != null) {
            try {
                String liveSource = getPageSource(runId);
                List<Elem> liveEmitted = buildEmittedList(liveSource, goal);
                if (id >= 1 && id <= liveEmitted.size()) {
                    Elem liveElem = liveEmitted.get(id - 1);
                    System.out.println("[locator] Kademe-5 (canlı XML): elementId=" + id + " -> '" + liveElem.label() + "'");
                    int[] center = centerOfBounds(liveElem.bounds());
                    if (center != null) {
                        System.out.println("[locator] Kademe-5 BAŞARILI: (" + center[0] + "," + center[1] + ")");
                        return safeAreaOrOriginal(runId, center, parseBounds(liveElem.bounds()));
                    }
                }
            } catch (Exception e) {
                System.out.println("[locator] Kademe-5 hata: " + e.getMessage());
            }
        }

        System.out.println("[locator] ❌ TÜM KADEMELER BAŞARISIZ -- element bulunamadı");
        return null;
    }

    /**
     * [YENİ 2026-09-15] Safe-area düzeltmesi sonrası koordinat hala orijinal element
     * sınırları içindeyse onu kullan; çakışıyorsa ORIJINAL merkezi döner.
     * Bu, dar elementlerde safe-area'nın tap'i başka bir yere taşımasını engeller.
     */
    private int[] safeAreaOrOriginal(String runId, int[] originalCenter, int[] elementBounds) {
        if (originalCenter == null) return null;
        int[] adjusted = adjustForSafeTapArea(runId, originalCenter, elementBounds);
        if (adjusted == null) return originalCenter;

        // Adjusted hala element içindeyse onu kullan
        if (elementBounds != null && elementBounds.length == 4) {
            if (adjusted[0] >= elementBounds[0] && adjusted[0] <= elementBounds[2]
                    && adjusted[1] >= elementBounds[1] && adjusted[1] <= elementBounds[3]) {
                return adjusted;
            }
            System.out.println("[locator] ⚠ safe-area düzeltmesi element sınırları dışına çıktı, ORIJINAL merkez kullanılıyor");
            return originalCenter;
        }
        return adjusted;
    }


    private int[] resolveByLiveXPath(String runId, String targetLabel) {
        if (runId == null || targetLabel == null || targetLabel.isBlank()) return null;
        try {
            AppiumDriver driver = driverFor(runId);
            Dimension size = driver.manage().window().getSize();
            int screenW = size.getWidth();
            int screenH = size.getHeight();
            long screenArea = (long) screenW * screenH;

            String safeLabel = targetLabel.replace("'", "\\'");
            List<String> xpaths = new ArrayList<>();
            xpaths.add("//*[@name='" + safeLabel + "']");
            xpaths.add("//*[@label='" + safeLabel + "']");
            xpaths.add("//*[@value='" + safeLabel + "']");
            xpaths.add("//*[@content-desc='" + safeLabel + "']");
            xpaths.add("//*[@text='" + safeLabel + "']");
            xpaths.add("//*[text()='" + safeLabel + "']");
            xpaths.add("//*[contains(@name, '" + safeLabel + "')]");
            xpaths.add("//*[contains(@label, '" + safeLabel + "')]");
            xpaths.add("//*[contains(@value, '" + safeLabel + "')]");
            xpaths.add("//*[contains(@content-desc, '" + safeLabel + "')]");
            xpaths.add("//*[contains(@text, '" + safeLabel + "')]");
            xpaths.add("//*[contains(text(), '" + safeLabel + "')]");

            for (String xpath : xpaths) {
                List<WebElement> elements = driver.findElements(By.xpath(xpath));
                int[] best = pickBestVisibleElement(elements, screenW, screenH, screenArea);
                if (best != null) {
                    System.out.println("[liveXPath] ✓ Seçilen hedef (" + xpath + "): center=("
                            + best[0] + "," + best[1] + ")");
                    return best;
                }
            }
            return null;
        } catch (Exception ex) {
            System.out.println("[liveXPath] Hata: " + ex.getMessage());
            return null;
        }
    }

    private boolean isIOSSession(String runId) {
        if (runId == null) return false;
        try {
            return driverFor(runId) instanceof IOSDriver;
        } catch (Exception ex) {
            return false;
        }
    }

    private int[] pickBestVisibleElement(List<WebElement> elements, int screenW, int screenH, long screenArea) {
        Rectangle bestRect = null;
        long bestArea = Long.MAX_VALUE;
        for (WebElement el : elements) {
            try {
                if (!el.isDisplayed() || !el.isEnabled()) continue;
                Rectangle rect = el.getRect();
                if (rect.getWidth() <= 0 || rect.getHeight() <= 0) continue;
                int cx = rect.getX() + rect.getWidth() / 2;
                int cy = rect.getY() + rect.getHeight() / 2;
                if (cx < 1 || cy < 1 || cx >= screenW || cy >= screenH) continue;

                long area = (long) rect.getWidth() * rect.getHeight();
                if (screenArea > 0 && area > (long) (screenArea * 0.60)) continue;

                if (area < bestArea) {
                    bestArea = area;
                    bestRect = rect;
                }
            } catch (Exception ignored) {
            }
        }
        if (bestRect == null) return null;
        return new int[]{
                bestRect.getX() + bestRect.getWidth() / 2,
                bestRect.getY() + bestRect.getHeight() / 2
        };
    }

    public boolean isCoordinateOnViewport(String runId, int x, int y) {
        if (runId == null) return false;
        try {
            AppiumDriver driver = driverFor(runId);
            Dimension size = driver.manage().window().getSize();
            int w = size.getWidth();
            int h = size.getHeight();
            
            // [DUZELTME 2026-09-15] Android viewport genellikle status bar ile başlar (top > 0)
            // Koordinatların viewport içinde olup olmadığını kontrol et
            // getViewportRect() varsa onu kullan, yoksa basit kontrol yap
            boolean isValidX = x > 0 && x < w;
            boolean isValidY = y > 0 && y < h;
            
            if (!isValidX || !isValidY) {
                System.out.println("[isCoordinateOnViewport] ✗ Koordinat dışı: x=" + x + ", y=" + y 
                        + ", viewport: 0,0 - " + w + "x" + h);
            }
            return isValidX && isValidY;
        } catch (Exception ex) {
            System.out.println("[isCoordinateOnViewport] ✗ Hata: " + ex.getMessage());
            return false;
        }
    }

    // 4 parametreli overload
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
            // [YENİ 2026-09-15] Devre dışı ancestor'ı ele
            if (tag.contains("enabled=\"false\"")) continue;

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

    // ============================================================================================
    // tap() -- DÜZELTİLDİ (2026-09-15)
    //
    // Android'de ÖNCELİKLE native "mobile: clickGesture" denenir; bu UiAutomator'un
    // UiDevice.click() metodunu kullanır ve sistem seviyesinde event enjekte ettiği
    // için W3C synthesizer'ının takıldığı yerlerde de çalışır. iOS'ta W3C öncelikli.
    // Ayrıca tap SONRASI page source hash'i karşılaştırılır; ekran hiç değişmediyse
    // "sessiz hata" kabul edilip alternatif metotla tekrar denenir.
    // ============================================================================================
    public void tap(String runId, int x, int y) {
        AppiumDriver driver = driverFor(runId);
        System.out.println("[TAP] ========================================");
        System.out.println("[TAP] Koordinatlar: x=" + x + ", y=" + y);
        
        if (x <= 0 || y <= 0) {
            throw new IllegalArgumentException("Geçersiz tap koordinatı: x=" + x + ", y=" + y);
        }

        boolean isAndroid = driver instanceof AndroidDriver;
        
        // [DUZELTME 2026-09-15] Koordinat viewport kontrolü
        boolean coordValid = isCoordinateOnViewport(runId, x, y);
        if (!coordValid) {
            System.out.println("[TAP] ✗ KOORDİNAT VIEWPORT DIŞI! Tap yapılamaz.");
            int[] size = getScreenSize(runId);
            if (size != null) {
                System.out.println("[TAP]   Ekran boyutu: " + size[0] + "x" + size[1]);
            }
            throw new IllegalArgumentException("Koordinat viewport dışı: (" + x + "," + y + ")");
        }

        String beforeHash = safePageSourceHash(driver);

        boolean primaryOk = tryTapWith(driver, isAndroid, x, y);

        if (!primaryOk) {
            System.out.println("[TAP] Birincil metod istisna attı, alternatif deneniyor...");
            boolean fallbackOk = tryTapWith(driver, !isAndroid, x, y);
            if (!fallbackOk) {
                throw new RuntimeException("Tap başarısız: hem clickGesture hem W3C denendi, ikisi de istisna attı");
            }
            sleepQuietly(400);
            return;
        }

        sleepQuietly(400);
        String afterHash = safePageSourceHash(driver);

        if (beforeHash != null && beforeHash.equals(afterHash)) {
            System.out.println("[TAP] ⚠️ Tap sonrası page source DEĞİŞMEDİ — sessiz hata şüphesi, "
                    + "alternatif metodla tekrar deneniyor");
            boolean retryOk = tryTapWith(driver, !isAndroid, x, y);
            if (!retryOk) {
                throw new RuntimeException("Tap sonrası ekran değişmedi ve alternatif metod da başarısız");
            }
            sleepQuietly(400);
            String retryHash = safePageSourceHash(driver);
            if (beforeHash.equals(retryHash)) {
                throw new RuntimeException("Tap iki farklı metodla denendi ama ekran hiç değişmedi "
                        + "(sessiz tap hatası — hedef (" + x + "," + y + ") muhtemelen ölü bölgede "
                        + "veya element devre dışı)");
            }
            System.out.println("[TAP] ✓ Alternatif metodla ekran değişti, tap başarılı sayıldı");
        } else {
            System.out.println("[TAP] ✓ Tap başarılı, page source değişti");
        }
        System.out.println("[TAP] ========================================");
        sleepQuietly(300);
    }

    private boolean tryTapWith(AppiumDriver driver, boolean useClickGesture, int x, int y) {
        try {
            // [DUZELTME 2026-09-15] Koordinat validasyonu
            if (x <= 0 || y <= 0) {
                System.out.println("[tryTap] ✗ Geçersiz koordinat: x=" + x + ", y=" + y);
                return false;
            }
            
            if (useClickGesture) {
                System.out.println("[tryTap] mobile: clickGesture deneniyor: (" + x + "," + y + ")");
                ((JavascriptExecutor) driver).executeScript("mobile: clickGesture",
                        Map.of("x", x, "y", y));
                System.out.println("[TAP] ✓ mobile: clickGesture BAŞARILI");
            } else {
                // [DUZELTME 2026-09-15] W3C action sequence için daha detaylı hata yakalama
                System.out.println("[tryTap] W3C perform deneniyor: (" + x + "," + y + ")");
                PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
                Sequence tap = new Sequence(finger, 1);
                tap.addAction(finger.createPointerMove(Duration.ZERO,
                        PointerInput.Origin.viewport(), x, y));
                tap.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
                tap.addAction(new Pause(finger, Duration.ofMillis(80)));
                tap.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
                
                try {
                    driver.perform(List.of(tap));
                    System.out.println("[TAP] ✓ W3C perform BAŞARILI");
                } catch (Exception w3cEx) {
                    // W3C hatası - logla ve false dön
                    String errorMsg = w3cEx.getMessage();
                    System.out.println("[TAP] ✗ W3C actions hatası: " + errorMsg);
                    System.out.println("[TAP] ✗ Koordinat: (" + x + "," + y + ")");
                    System.out.println("[TAP] ✗ Driver: " + driver.getClass().getSimpleName());
                    
                    // Hata mesajını detaylı logla
                    if (errorMsg != null && errorMsg.contains("input actions chain")) {
                        System.out.println("[TAP] ⚠ W3C actions chain hatalı - koordinat viewport dışı olabilir");
                    }
                    throw w3cEx; // Üst katmana ilet
                }
            }
            return true;
        } catch (Exception e) {
            System.out.println("[TAP] ✗ "
                    + (useClickGesture ? "clickGesture" : "W3C perform")
                    + " istisna attı: " + e.getMessage());
            e.printStackTrace(); // Stack trace'i de logla
            return false;
        }
    }

    private String safePageSourceHash(AppiumDriver driver) {
        try {
            String src = driver.getPageSource();
            return src == null ? null : String.valueOf(src.hashCode());
        } catch (Exception e) {
            return null;
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
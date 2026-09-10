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

    public AppiumDriver startSession(String runId, String platform, String appIdentifier,
                                     String appActivity, boolean parallel) {
        AppiumDriver existing = drivers.get(runId);
        if (existing != null) {
            return existing;
        }

        // gridUrl/defaultAppPackage/defaultAppActivity artık panelden (DB'den) okunuyor.
        AppSettings settings = appSettingsService.getOrCreate();
        String gridUrl = settings.getAppiumGridUrl();
        String defaultAppPackage = settings.getAndroidAppPackage();
        String defaultAppActivity = settings.getAndroidAppActivity();
        // deviceName/platformVersion: sadece device farm (BrowserStack vb.) kullanirken
        // doldurulur -- bu ikisi bossa hicbir capability eklenmez, yerel Appium/Grid
        // davranisi hic degismez.
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
                        .setNewCommandTimeout(Duration.ofSeconds(300));
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
                        .amend("appium:resetKeyboard", true)
                        .amend("univodeKeyboard", true)
                        .amend("appium:useNewWDA", true)
                        .amend("appium:shouldWaitForQuiescence", false)
                        .amend("appium:waitForQuiescence", false)
                        .setNewCommandTimeout(Duration.ofSeconds(1800));
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

    public void resetToFreshState(String runId, String platform, String appIdentifier) {
        AppiumDriver driver = driverFor(runId);
        if ("ios".equalsIgnoreCase(platform)) {
            ((InteractsWithApps) driver).terminateApp(appIdentifier);
            ((InteractsWithApps) driver).activateApp(appIdentifier);
        } else {
            ((JavascriptExecutor) driver).executeScript("mobile: clearApp", Map.of("appId", appIdentifier));
            ((InteractsWithApps) driver).activateApp(appIdentifier);
        }
    }

    public String takeScreenshotBase64(String runId) {
        return takeScreenshotWithRetry(runId, 2);
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
                // UI quiescence beklemeden direkt page source al
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

                        // UI thread bloke olduysa, basit bir action ile uyanmasını sağla
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
                            // Home/back basma başarısız olabilir, devam et
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

    // Ekranin GERCEK piksel boyutu -- findElementCenter/isValidCoordinate XML'deki bounds'a
    // gore calisiyor, ama XML henuz kaydirilmamis (asagida/yukarida kalan) elementleri de
    // icerebiliyor: boyle bir elementin bounds'u fiziksel ekranin disinda kalir, oraya
    // dokunmak hicbir seye isabet etmez.
    public int[] getScreenSize(String runId) {
        try {
            Dimension size = driverFor(runId).manage().window().getSize();
            return new int[]{size.getWidth(), size.getHeight()};
        } catch (Exception e) {
            return null;
        }
    }

    // filterPageSource ve findElementCenter'in ikisi de AYNI parse+numaralandirma mantigina
    // ihtiyac duyuyor -- tek bir yerde topladik.
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

        // Ayni etiket birden fazla kez ortaya cikiyorsa, " (1)", " (2)" gibi sira numarasi ekle.
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

    private List<Elem> buildEmittedList(String rawPageSource) {
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

    private String formatLine(int id, Elem e) {
        String passwordFlag = e.isPassword() ? " password=true" : "";
        String classFlag = (e.className() != null && !e.className().isBlank()) ? " class=" + e.className() : "";
        String textFlag = (e.text() != null && !e.text().isBlank()) ? " text=\"" + e.text().replace("\"", "'") + "\"" : "";
        String contentDescFlag = (e.contentDesc() != null && !e.contentDesc().isBlank()) ? " content-desc=\"" + e.contentDesc().replace("\"", "'") + "\"" : "";

        return "[" + id + "] bounds=" + e.bounds() + " clickable=" + e.clickable() + passwordFlag + classFlag
                + textFlag + contentDescFlag
                + " label=\"" + e.label().replace("\"", "'") + "\"";
    }

    public String filterPageSource(String rawPageSource) {
        if (rawPageSource == null) return "";

        List<Elem> emitted = buildEmittedList(rawPageSource);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < emitted.size(); i++) {
            result.append(formatLine(i + 1, emitted.get(i))).append("\n");
        }
        return result.toString();
    }

    private Elem findUniqueElemByLabel(List<Elem> numbered, String targetLabel) {
        String target = targetLabel.trim();
        String targetLower = target.toLowerCase();

        Integer targetOccurrence = null;
        String targetBaseLower = targetLower;
        Matcher occMatcher = Pattern.compile("\\((\\d+)\\)\\s*$").matcher(target);
        if (occMatcher.find()) {
            targetOccurrence = Integer.parseInt(occMatcher.group(1));
            targetBaseLower = target.substring(0, occMatcher.start()).trim().toLowerCase();
        }

        List<Elem> candidates = new ArrayList<>();
        for (Elem e : numbered) {
            if (e.label().isBlank()) continue;
            String labelLower = e.label().toLowerCase();

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

    // Geriye donuk uyumluluk icin birakildi -- asil cozum resolveTargetCenter uzerinden yapiliyor.
    public int[] findElementCenter(String rawPageSource, String targetLabel) {
        if (rawPageSource == null || targetLabel == null || targetLabel.isBlank()) return null;
        List<Elem> numbered = parseNumberedElements(rawPageSource);
        Elem match = findUniqueElemByLabel(numbered, targetLabel);
        return match != null ? centerOfBounds(match.bounds()) : null;
    }

    // ============================================================================================
    // LOCATOR COZUMLEME
    //
    // 3 kademeli:
    //   1) ID (elementId): model "[N]" numarasini secer, bounds merkezini biz hesaplariz.
    //   2) Etiket (target): serbest metin -> bulanik eslestirme.
    //   3) XPath: bounds kullanilamiyorsa, canli uygulamada XPath ile yeniden ara.
    //
    // YENI: elementId clickable="false" bir elemente isaret ediyorsa, o elementi KAPSAYAN
    // en kucuk clickable="true" ancestor'in merkezine tiklanir. Boylece "urun adi TextView'a
    // tiklandi ama bir sey olmadi" sorunu cozulur.
    // ============================================================================================

    public int[] resolveTargetCenter(String runId, String rawPageSource, String elementId, String targetLabel) {
        if (rawPageSource == null) return null;

        Elem byId = null;
        Integer id = parsePlainInt(elementId);
        if (id != null) {
            List<Elem> emitted = buildEmittedList(rawPageSource);
            if (id >= 1 && id <= emitted.size()) {
                byId = emitted.get(id - 1);

                // YENI: Clickable degilse, kapsayan clickable ancestor'i dene.
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
                // ID gecerli ama bounds kullanilamiyor -- asagida XPath denenecek.
            }
        }

        List<Elem> numbered = parseNumberedElements(rawPageSource);
        Elem byLabel = (targetLabel != null && !targetLabel.isBlank())
                ? findUniqueElemByLabel(numbered, targetLabel) : null;
        if (byLabel != null) {
            // Label ile bulunduysa da clickable kontrolu yapalim.
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

    /**
     * Verilen child bounds'unu KAPSAYAN, clickable="true" olan elementler arasindan
     * EN KUCUK alanli olanini bulup merkezini dondurur.
     *
     * XML'de parent-child hiyerarsisi duz Regex ile parse edilmedigi icin, "ancestor"
     * kavramini bounds kapsama iliskisiyle taklit ediyoruz: child'i kapsayan ve
     * clickable olan en kucuk element = en yakin clickable ancestor.
     */
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

            // child'i KAPSAYAN mi?
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
        // 1. resource-id
        if (e.resourceId() != null && !e.resourceId().isBlank()) {
            String lit = xpathLiteral(e.resourceId());
            if (lit != null && countOccurrences(rawPageSource, "resource-id=\"" + e.resourceId() + "\"") == 1) {
                return "//*[@resource-id=" + lit + "]";
            }
        }
        // 2. content-desc
        if (e.contentDesc() != null && !e.contentDesc().isBlank()) {
            String lit = xpathLiteral(e.contentDesc());
            if (lit != null && countOccurrences(rawPageSource, "content-desc=\"" + e.contentDesc() + "\"") == 1) {
                return "//*[@content-desc=" + lit + "]";
            }
        }
        // 3. text
        if (e.text() != null && !e.text().isBlank() && e.text().length() <= 80) {
            String lit = xpathLiteral(e.text());
            if (lit != null && countOccurrences(rawPageSource, "text=\"" + e.text() + "\"") == 1) {
                return "//*[@text=" + lit + "]";
            }
        }
        // 4. label
        if (e.label() != null && !e.label().isBlank() && e.label().length() <= 80) {
            String lit = xpathLiteral(e.label());
            if (lit != null && countOccurrences(rawPageSource, "label=\"" + e.label() + "\"") == 1) {
                return "//*[@label=" + lit + "]";
            }
        }
        // 5. resource-id + bounds
        if (e.resourceId() != null && !e.resourceId().isBlank()) {
            String lit = xpathLiteral(e.resourceId());
            if (lit != null) {
                return "//*[@resource-id=" + lit + "][@bounds='" + e.bounds() + "']";
            }
        }
        // 6. label + bounds
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

            // UI stabilize
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
     * ONEMLI (2026-09-10 duzeltmesi): "direction" artik EKRANIN/ICERIGIN GORSEL OLARAK hangi
     * yone kaydigini ifade ediyor -- parmagin fiziksel hareketini DEGIL. Yani direction="down"
     * dedigimde EKRAN GERCEKTEN ASAGI kayar (listede DAHA SONRAKI/ALTTAKI ogeler gorunur).
     *
     * ONCEKI (HATALI) DAVRANIS: direction parametresi PARMAK hareketini ifade ediyordu
     * (direction="up" -> parmak yukari -> icerik asagi kayar -> BU YUZDEN aslinda "asagi
     * kaydirma" anlamina geliyordu, direction="down" ise tam TERSINE "yukari kaydirma"
     * anlamina geliyordu). Bu ters/sezgiye aykiri esleme, kod icinde EN AZ 3 farkli yerde
     * (RunController.AUTO_SCROLL_DIRECTIONS dizisi, LlmAgent'teki eski "bul" ipucu, ve
     * turkishDirection() etiketleri) birbirinden BAGIMSIZ olarak YANLIS kullanilmasina yol
     * acmisti -- cunku "down" yazan biri dogal olarak "ekrani asagi kaydir" bekliyordu, kod ise
     * "parmagi asagi hareket ettir" (yani ekrani YUKARI kaydir) yapiyordu. Simdi direction
     * degeri SEZGISEL anlamiyla (ekranin/icerigin GORUNEN kayma yonu) tanimlaniyor, parmak
     * hareketi sadece bunun bir UYGULAMA DETAYI -- disaridan (LLM promptu, otomatik kaydirma
     * dizisi, log mesajlari) hep sezgisel/dogal anlamla kullanilabiliyor.
     *
     *   direction="down"  -> EKRANI ASAGI kaydir -> listede SONRAKI/ALTTAKI ogeler gorunur
     *                        (parmak bunun icin YUKARI hareket eder: startY yuksek -> endY dusuk)
     *   direction="up"    -> EKRANI YUKARI kaydir -> listede ONCEKI/USTTEKI ogeler gorunur
     *                        (parmak bunun icin ASAGI hareket eder: startY dusuk -> endY yuksek)
     *   direction="right" -> EKRANI SAGA kaydir -> SONRAKI/SAGDAKI ogeler gorunur (yatay)
     *   direction="left"  -> EKRANI SOLA kaydir -> ONCEKI/SOLDAKI ogeler gorunur (yatay)
     *
     * Varsayilan (null/bilinmeyen): "down" -- en sik ihtiyac duyulan yon (bir liste ekraninda
     * henuz gorunmeyen SONRAKI ogeleri aramak icin).
     */
    public void swipe(String runId, String direction) {
        AppiumDriver driver = driverFor(runId);
        Dimension size = driver.manage().window().getSize();
        int width = size.getWidth();
        int height = size.getHeight();

        int startX, startY, endX, endY;

        switch (direction == null ? "" : direction.toLowerCase()) {
            case "down" -> {
                // Ekrani asagi kaydir (sonraki/alttaki ogeler) -> parmak YUKARI hareket eder
                startX = width / 2;
                startY = (int) (height * 0.7);
                endX = width / 2;
                endY = (int) (height * 0.2);
            }
            case "up" -> {
                // Ekrani yukari kaydir (onceki/ustteki ogeler) -> parmak ASAGI hareket eder
                startX = width / 2;
                startY = (int) (height * 0.2);
                endX = width / 2;
                endY = (int) (height * 0.7);
            }
            case "right" -> {
                // Ekrani saga kaydir (sonraki/sagdaki ogeler, yatay) -> parmak SOLA hareket eder
                startX = (int) (width * 0.8);
                startY = height / 2;
                endX = (int) (width * 0.2);
                endY = height / 2;
            }
            case "left" -> {
                // Ekrani sola kaydir (onceki/soldaki ogeler, yatay) -> parmak SAGA hareket eder
                startX = (int) (width * 0.2);
                startY = height / 2;
                endX = (int) (width * 0.8);
                endY = height / 2;
            }
            default -> {
                // Varsayilan: ekrani asagi kaydir (sonraki ogeler) -> parmak yukari hareket eder
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
        swipe.addAction(finger.createPointerMove(Duration.ofMillis(500),
                PointerInput.Origin.viewport(), endX, endY));
        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        driver.perform(List.of(swipe));

        try {
            Thread.sleep(500);
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
        drivers.remove(runId);
    }

    public void stopSession(String runId) {
        AppiumDriver driver = drivers.remove(runId);
        if (driver != null) {
            driver.quit();
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
package com.testpilot.appium;

import io.appium.java_client.AppiumClientConfig;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.InteractsWithApps;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.ios.IOSDriver;
import io.appium.java_client.ios.options.XCUITestOptions;
import io.appium.java_client.screenrecording.CanRecordScreen;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.springframework.stereotype.Component;
import com.testpilot.model.AppSettings;
import com.testpilot.settings.AppSettingsService;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AppiumDriverManager {

    // appiumServerUrl/iosServerUrl/iosDeviceName/iosPlatformVersion alanları tamamen
    // kaldırıldı — aktif startSession() içinde hiç kullanılmıyorlardı (aşağıdaki yorum
    // satırına alınmış eski versiyonda kullanılıyordu), bu yüzden panele de eklenmediler.
    // application.properties'teki appium.server-url / appium.ios-server-url /
    // ios.simulator-device-name / ios.platform-version satırlarını da silebilir ya da
    // yorum satırı olarak bırakabilirsin — artık hiçbir Java alanı bunları okumuyor.

    // gridUrl / defaultAppPackage / defaultAppActivity artık application.properties'ten
    // @Value ile DEĞİL, AppSettingsService üzerinden veritabanından (panelden
    // yönetilen) okunuyor — bkz. startSession() içindeki fetch.
    private final AppSettingsService appSettingsService;

    public AppiumDriverManager(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    // Her run kendi Appium session'ını (driver'ını) tutar - paralel koşum için
    private final Map<String, AppiumDriver> drivers = new ConcurrentHashMap<>();

  /*  public AppiumDriver startSession(String runId, String platform, String appIdentifier, String appActivity, boolean parallel) {
        AppiumDriver existing = drivers.get(runId);
        if (existing != null) {
            return existing;
        }

        AppiumDriver driver;
        try {
            if ("ios".equalsIgnoreCase(platform)) {
                String targetUrl = parallel ? gridUrl : iosServerUrl;
                XCUITestOptions options = new XCUITestOptions()
                        .setPlatformName("iOS") // CRITICAL: Selenium Grid'in cihazı eşleştirmesi için zorunlu
                        .setAutomationName("XCUITest")
                   *//*     .setDeviceName(iosDeviceName)
                        .setPlatformVersion(iosPlatformVersion)*//*
                        .setBundleId(appIdentifier)
                        .setAutoAcceptAlerts(true)
                        .setNewCommandTimeout(Duration.ofSeconds(300));
                AppiumClientConfig clientConfig = AppiumClientConfig.defaultConfig()
                        .baseUri(URI.create(targetUrl))
                        .readTimeout(Duration.ofMinutes(5));
                driver = new IOSDriver(clientConfig, options);
            } else {
                String targetUrl = parallel ? gridUrl : appiumServerUrl;
                String pkg = (appIdentifier != null && !appIdentifier.isBlank()) ? appIdentifier : defaultAppPackage;
                String activity = (appActivity != null && !appActivity.isBlank()) ? appActivity : defaultAppActivity;

                UiAutomator2Options options = new UiAutomator2Options()
                        .setAppPackage(pkg)
                        .setAppActivity(activity)
                        .setAutoGrantPermissions(true)
                        .setNoReset(true)
                        .amend("shouldWaitForQuiescence", false)

                        .setNewCommandTimeout(Duration.ofSeconds(300));
                driver = new AndroidDriver(new URL(targetUrl), options);
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Appium sunucu adresi hatalı: " + appiumServerUrl, e);
        }

        drivers.put(runId, driver);
        return driver;
    }
*/
  public AppiumDriver startSession(String runId, String platform, String appIdentifier, String appActivity, boolean parallel) {
      AppiumDriver existing = drivers.get(runId);
      if (existing != null) {
          return existing;
      }

      // gridUrl/defaultAppPackage/defaultAppActivity artık panelden (DB'den) okunuyor.
      AppSettings settings = appSettingsService.getOrCreate();
      String gridUrl = settings.getAppiumGridUrl();
      String defaultAppPackage = settings.getAndroidAppPackage();
      String defaultAppActivity = settings.getAndroidAppActivity();

      AppiumDriver driver;
      try {
          if ("ios".equalsIgnoreCase(platform)) {
              XCUITestOptions options = new XCUITestOptions()
                      .setPlatformName("IOS")
                      .setAutomationName("XCUITest")
                      .setBundleId(appIdentifier)
                      .setAutoAcceptAlerts(true)
                      .setNewCommandTimeout(Duration.ofSeconds(300));

              driver = new IOSDriver(new URL(gridUrl), options);
          } else {
              String pkg = (appIdentifier != null && !appIdentifier.isBlank()) ? appIdentifier : defaultAppPackage;
              String activity = (appActivity != null && !appActivity.isBlank()) ? appActivity : defaultAppActivity;

              UiAutomator2Options options = new UiAutomator2Options()
                      .setPlatformName("Android")
                      .setAppPackage(pkg)
                      .setAppActivity(activity)
                      .setAutoGrantPermissions(true)
                      .setNoReset(true)
                      .amend("shouldWaitForQuiescence", false)
                      .setNewCommandTimeout(Duration.ofSeconds(300));

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
        return driverFor(runId).getScreenshotAs(OutputType.BASE64);
    }

    public String getPageSource(String runId) {
        return driverFor(runId).getPageSource();
    }

    public String filterPageSource(String rawPageSource) {
        if (rawPageSource == null) return "";

        int CHAR_BUDGET = 8000;
        List<String> labeled = new ArrayList<>();
        List<String> textOnly = new ArrayList<>();
        List<String> unlabeled = new ArrayList<>();

        Pattern tagPattern = Pattern.compile("<[^<>]+/>");
        Matcher tagMatcher = tagPattern.matcher(rawPageSource);

        while (tagMatcher.find()) {
            String tag = tagMatcher.group();

            boolean clickable = tag.contains("clickable=\"true\"") || tag.contains("accessible=\"true\"");

            String text = extractAttr(tag, "text");
            if (text == null) text = extractAttr(tag, "value");

            String desc = extractAttr(tag, "content-desc");
            if (desc == null) desc = extractAttr(tag, "label");
            if (desc == null) desc = extractAttr(tag, "name");

            String bounds = extractAttr(tag, "bounds");
            if (bounds == null) bounds = buildBoundsFromXYWH(tag);

            boolean hasLabel = (text != null && !text.isBlank()) || (desc != null && !desc.isBlank());

            if (bounds == null) continue;

            String label = (desc != null && !desc.isBlank()) ? desc : (text != null ? text : "");
            String compact = "bounds=" + bounds + " clickable=" + clickable + " label=\"" + label.replace("\"", "'") + "\"";

            if (clickable && hasLabel) {
                labeled.add(compact);
            } else if (hasLabel) {
                textOnly.add(compact);
            } else if (clickable) {
                unlabeled.add(compact);
            }
        }

        StringBuilder result = new StringBuilder();
        for (List<String> bucket : List.of(labeled, textOnly, unlabeled)) {
            for (String line : bucket) {
                if (result.length() + line.length() + 1 > CHAR_BUDGET) {
                    return result.toString();
                }
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }

    private String extractAttr(String tag, String attrName) {
        Pattern p = Pattern.compile(attrName + "=\"([^\"]*)\"");
        Matcher m = p.matcher(tag);
        if (m.find()) {
            return m.group(1);
        }
        return null;
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
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence tap = new Sequence(finger, 1);
        tap.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y));
        tap.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        tap.addAction(finger.createPointerMove(Duration.ofMillis(100), PointerInput.Origin.viewport(), x, y));
        tap.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        driver.perform(List.of(tap));
    }

    public void swipe(String runId, String direction) {
        AppiumDriver driver = driverFor(runId);
        Dimension size = driver.manage().window().getSize();
        int width = size.getWidth();
        int height = size.getHeight();

        int startX, startY, endX, endY;

        switch (direction == null ? "" : direction.toLowerCase()) {
            case "up" -> {
                startX = width / 2;
                startY = (int) (height * 0.7);
                endX = width / 2;
                endY = (int) (height * 0.3);
            }
            case "down" -> {
                startX = width / 2;
                startY = (int) (height * 0.3);
                endX = width / 2;
                endY = (int) (height * 0.7);
            }
            case "left" -> {
                startX = (int) (width * 0.8);
                startY = height / 2;
                endX = (int) (width * 0.2);
                endY = height / 2;
            }
            case "right" -> {
                startX = (int) (width * 0.2);
                startY = height / 2;
                endX = (int) (width * 0.8);
                endY = height / 2;
            }
            default -> {
                startX = width / 2;
                startY = (int) (height * 0.7);
                endX = width / 2;
                endY = (int) (height * 0.3);
            }
        }

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence swipe = new Sequence(finger, 1);
        swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
        swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        swipe.addAction(finger.createPointerMove(Duration.ofMillis(300), PointerInput.Origin.viewport(), endX, endY));
        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        driver.perform(List.of(swipe));
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
            if (driver instanceof AndroidDriver androidDriver) {
                androidDriver.pressKey(new KeyEvent(AndroidKey.ENTER));
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
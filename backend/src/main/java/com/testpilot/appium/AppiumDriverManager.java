package com.testpilot.appium;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AppiumDriverManager {

    @Value("${appium.server-url}")
    private String appiumServerUrl;

    @Value("${android.app-package:}")
    private String defaultAppPackage;

    @Value("${android.app-activity:}")
    private String defaultAppActivity;

    private AndroidDriver driver;

    public AndroidDriver startSession(String appIdentifier, String appActivity) {
        if (driver != null) {
            return driver;
        }

        try {
            String pkg = (appIdentifier != null && !appIdentifier.isBlank()) ? appIdentifier : defaultAppPackage;
            String activity = (appActivity != null && !appActivity.isBlank()) ? appActivity : defaultAppActivity;

            UiAutomator2Options options = new UiAutomator2Options()
                    .setDeviceName("emulator-5554")
                    .setAppPackage(pkg)
                    .setAppActivity(activity)
                    .setAutoGrantPermissions(true)
                    .setNoReset(true);
            driver = new AndroidDriver(new URL(appiumServerUrl), options);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Appium sunucu adresi hatalı: " + appiumServerUrl, e);
        }
        return driver;
    }

    public void resetToFreshState(String appIdentifier) {
        ((JavascriptExecutor) driver).executeScript("mobile: clearApp", Map.of("appId", appIdentifier));
        driver.activateApp(appIdentifier);
    }

    public String takeScreenshotBase64() {
        return driver.getScreenshotAs(OutputType.BASE64);
    }

    public String getPageSource() {
        return driver.getPageSource();
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

            boolean clickable = tag.contains("clickable=\"true\"");
            String text = extractAttr(tag, "text");
            String desc = extractAttr(tag, "content-desc");
            String bounds = extractAttr(tag, "bounds");
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

    public boolean isValidCoordinate(String rawPageSource, int x, int y) {
        if (rawPageSource == null) return false;

        Pattern boundsPattern = Pattern.compile("bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"");
        Matcher m = boundsPattern.matcher(rawPageSource);

        while (m.find()) {
            int x1 = Integer.parseInt(m.group(1));
            int y1 = Integer.parseInt(m.group(2));
            int x2 = Integer.parseInt(m.group(3));
            int y2 = Integer.parseInt(m.group(4));

            if (x >= x1 && x <= x2 && y >= y1 && y <= y2) {
                return true;
            }
        }
        return false;
    }

    public void tap(int x, int y) {
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence tap = new Sequence(finger, 1);
        tap.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y));
        tap.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        tap.addAction(finger.createPointerMove(Duration.ofMillis(100), PointerInput.Origin.viewport(), x, y));
        tap.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        driver.perform(List.of(tap));
    }

    public void swipe(String direction) {
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

    public void typeText(int x, int y, String text) {
        tap(x, y);
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
            driver.pressKey(new KeyEvent(AndroidKey.ENTER));
        } catch (Exception ignored) {
        }
    }

    public void invalidateSession() {
        driver = null;
    }

    public void stopSession() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }
}
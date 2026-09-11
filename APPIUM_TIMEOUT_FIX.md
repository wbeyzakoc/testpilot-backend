# Appium Timeout Hatası Düzeltme Raporu

## Problem
```
Timed out after 10102ms waiting for the root AccessibilityNodeInfo in the active window. 
Make sure the active window is not constantly hogging the main UI thread
```

Uygulama ayağa kalkıyor ama `getPageSource()` çağrısında timeout oluyor ve uygulama o状态下 kalıyor.

## Kök Neden

1. **UI Thread Bloke**: Uygulama UI thread'ini bloke ediyor, accessibility manager node'ları oluşturamıyor
2. **Quiescence Bekleme**: Appium varsayılan olarak UI'nin "sakin" olmasını bekliyor (`shouldWaitForQuiescence=true`)
3. **Tekrar Deneme Yok**: Hata olduğunda otomatik retry mekanizması yok
4. **UI Donması**: Uygulama donduğunda geri dönüş yolu yok

## Uygulanan Çözümler

### 1. Quiescence Beklemeyi Devre Dışı Bırakma
`AppiumDriverManager.startSession()` - Android options:

```java
.amend("appium:shouldWaitForQuiescence", false)
.amend("appium:waitForQuiescence", false)
```

**Etki**: Appium artık UI'nin "sakin" olmasını beklemeden page source alabilir.

### 2. getPageSource() için Retry Mekanizması
Yeni `getPageSourceWithRetry()` metodu eklendi:

- **3 deneme** hakkı
- Her başarısız denemeden sonra **exponential backoff** (2s, 4s, 6s)
- UI donduysa **HOME + BACK** tuşu ile uyanma denemesi
- Detaylı loglama

```java
public String getPageSource(String runId) {
    return getPageSourceWithRetry(runId, 3);
}

private String getPageSourceWithRetry(String runId, int maxRetries) {
    // 3 deneme, exponential backoff, HOME/BACK recovery
}
```

### 3. takeScreenshot() için Retry Mekanizması
Yeni `takeScreenshotWithRetry()` metodu eklendi:

- **2 deneme** hakkı
- **1s, 2s** exponential backoff

### 4. RunController'da Exception Handling
`getPageSource()` çağrısı try-catch ile sarıldı:

```java
try {
    rawPageSource = appiumDriverManager.getPageSource(run.getId());
    filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource);
} catch (Exception pageEx) {
    // Adımı atla, devam et
    run.getSteps().add(new RunStep(i, "failed", null, 
            "UI yanıt vermiyor, sayfa kaynağı alınamadı: " + pageEx.getMessage()));
    Thread.sleep(2000);
    continue; // Bir sonraki adıma geç
}
```

**Etki**: UI timeout hatası tüm run'ı durdurmaz, sadece o adımı atlar.

## Değiştirilen Dosyalar

1. **`AppiumDriverManager.java`**:
   - `startSession()`: Quiescence options eklendi
   - `getPageSource()`: Retry mekanizması eklendi
   - `takeScreenshotBase64()`: Retry mekanizması eklendi

2. **`RunController.java`**:
   - `getPageSource()` çağrıları try-catch ile sarıldı
   - UI timeout durumunda adım atlanıyor, run devam ediyor

## Beklenen Sonuçlar

✅ **Daha az timeout**: Quiescence bekleme devre dışı  
✅ **Otomatik kurtarma**: Retry mekanizması ile geçici hatalar düzeltilir  
✅ **Run devam eder**: UI timeout tüm run'ı durdurmaz  
✅ **Daha iyi logging**: Hangi adımda ne olduğunu net görülür  

## Test Önerileri

1. Uygulamayı başlatın ve test çalıştırın
2. UI timeout hatası gelip gelmediğini kontrol edin
3. Retry loglarını izleyin (`System.out.println` çıktıları)
4. UI donduğunda run'ın devam edip etmediğini doğrulayın

## Ek Önlemler (Gerekirse)

Eğer sorun devam ederse:

1. **Appium Server Logları**: `appium --log appium.log` ile detaylı log alın
2. **ADB Logcat**: `adb logcat -c && adb logcat | grep -i appium`
3. **Uygulama Performansı**: UI thread'i bloke eden uzun işlemler var mı kontrol edin
4. **New Command Timeout**: Daha yüksek değer deneyin (şu an 1800s)
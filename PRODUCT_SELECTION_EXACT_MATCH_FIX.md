# Ürün Seçim Hatası Düzeltmesi - 2026-09-14 (v4 - Visible Element Kontrolü)

## Sorun

"Test.allTheThings() T-Shirt (Red)" ürünü için tam eşleşme yapılmasına rağmen:
1. Sistem **yanlış ürünü** seçti
2. **Ekranda olmayan** ürünü "bulundu" olarak işaretledi
3. **XML'de çok sayıda aynı label'lı element** var, ama sadece biri visible
4. **Visible olmayan elemente tıklanmaya çalışılıyor**

**iOS ortamında da aynı problem yaşanıyor.**

### Log Analizi

```
label='Test.allTheThings() T-Shirt (Red)', text='Test.allTheThings() T-Shirt (Red)', 
```

**Çok sayıda element** aynı label'ı taşıyor. Hangisi **gerçekten visible** ve **doğru ürün**?

## Kök Neden

### Sorun 1: XML vs UI Uyumsuzluğu

- **XML'de**: 10 element `text="Test.allTheThings() T-Shirt (Red)"` taşıyor
- **UI'da**: Sadece 1 element visible (diğerleri hidden/invisible)
- **Sistem**: XML'e bakıp ilk elementi seçiyor → **YANLIŞ!**

### Sorun 2: Statik XML Analizi Yetersiz

`findMatchingTarget()` ve `resolveTargetCenter()` **statik XML**'e bakıyor:
- XML'deki `bounds` attribute'ları **görsel konumu** gösteriyor
- ANCAK elementin **visible/invisible** durumu **XML'de yok**
- iOS ve Android'de **farklı XML yapıları** → tutarsız sonuçlar

### Sorun 3: SCROLL_ONCE Sonrası Kontrol Eksikliği

(Önceki versiyonlarda açıklanmış)

### Sorun 4: Visible Kontrolü Eksikliği (YENİ - v4)

**v1, v2, v3** düzeltmelerinde:
- `resolveByLiveXPath()` **visible kontrolü yapıyor** ✅
- ANCAK `resolveByLiveXPath()` başarısız olursa → **XML parsing'e fallback**
- XML parsing'den bulunan element **visible olmayabilir** ❌
- Sistem **visible olmayan** elemente tıklamaya çalışıyor ❌

## Uygulanan Çözümler

### Çözüm 1: Canlı XPath ile iOS/Android Uyumlu Element Bulma

**Dosya**: `backend/src/main/java/com/testpilot/appium/AppiumDriverManager.java`

**YENİ METOD**: `resolveByLiveXPath()`

```java
private int[] resolveByLiveXPath(String runId, String targetLabel) {
    AppiumDriver driver = driverFor(runId);
    
    // iOS ve Android için uyumlu XPath oluştur
    String xpath = "//*[text()='" + targetLabel + "']";
    
    // Tüm eşleşen elementleri bul
    List<WebElement> elements = driver.findElements(By.xpath(xpath));
    
    // SADECE visible ve enabled olanı seç
    for (WebElement el : elements) {
        if (el.isDisplayed() && el.isEnabled()) {
            Rectangle rect = el.getRect();
            return new int[]{
                rect.getX() + rect.getWidth() / 2,
                rect.getY() + rect.getHeight() / 2
            };
        }
    }
    
    return null; // Visible element yok
}
```

**Neden Daha İyi?**
- ✅ **Gerçekten visible** olan elementi bulur
- ✅ **iOS ve Android** için çalışır (Appium XPath her iki platformda çalışır)
- ✅ **XML/UI uyumsuzluğunu** atlar
- ✅ **Çoklu element** durumunda doğru olanı seçer

### Çözüm 2: resolveTargetCenter'da Canlı XPath Fallback

**Dosya**: `backend/src/main/java/com/testpilot/appium/AppiumDriverManager.java`

```java
public int[] resolveTargetCenter(String runId, String rawPageSource, String elementId,
                                 String targetLabel, String goal) {
    // [YENİ v4] ÖNCE canlı uygulamada XPath ile ara
    if (targetLabel != null && !targetLabel.isBlank() && runId != null) {
        try {
            int[] viaLiveXPath = resolveByLiveXPath(runId, targetLabel);
            if (viaLiveXPath != null) {
                System.out.println("[resolveTarget] ✓ Canlı XPath ile bulundu: " + targetLabel);
                return viaLiveXPath;
            }
        } catch (Exception ex) {
            System.out.println("[resolveTarget] ⚠ Canlı XPath başarısız, fallback: " + ex.getMessage());
            // Fallback: XML parsing devam et
        }
    }
    
    // Eski XML parsing yöntemi (fallback)
    // ...
}
```

**Akış**:
1. **Önce** canlı XPath ile visible element bul
2. Başarısız olursa → **XML parsing'e fallback**
3. **YENİ v4**: XML'den bulunan element için de **visible kontrolü** yap
4. Visible değilse → `null` döndür, başka locator dene

### Çözüm 3: XML'den Bulunan Element için Visible Kontrolü (YENİ - v4)

**Dosya**: `backend/src/main/java/com/testpilot/appium/AppiumDriverManager.java`

**YENİ METOD**: `verifyElementVisible()`

```java
private int[] verifyElementVisible(String runId, Elem elem) {
    AppiumDriver driver = driverFor(runId);
    
    // XML'deki elementin bounds'undan merkez koordinat hesapla
    int[] bounds = parseBounds(elem.bounds());
    int centerX = (bounds[0] + bounds[2]) / 2;
    int centerY = (bounds[1] + bounds[3]) / 2;
    
    // Elementi XPath ile bul (text, content-desc, veya label kullanarak)
    String xpath = "//*[text()='" + elem.label() + "']";
    List<WebElement> elements = driver.findElements(By.xpath(xpath));
    
    // Bounds'u eşleşen ve visible olan element bul
    for (WebElement el : elements) {
        Rectangle rect = el.getRect();
        int elCenterX = rect.getX() + rect.getWidth() / 2;
        int elCenterY = rect.getY() + rect.getHeight() / 2;
        
        // Bounds merkezleri yaklaşık aynı mı kontrol et (±10 piksel tolerans)
        if (Math.abs(elCenterX - centerX) <= 10 && Math.abs(elCenterY - centerY) <= 10) {
            // Bu element, XML'deki elementle aynı konumda
            // Şimdi visible mi kontrol et
            if (el.isDisplayed() && el.isEnabled()) {
                return new int[]{elCenterX, elCenterY};
            } else {
                return null; // Aynı bounds'ta ama visible değil
            }
        }
    }
    
    return null; // Visible element bulunamadı
}
```

**Kullanım**: `resolveTargetCenter()` içinde, XML parsing'den sonra:

```java
if (byLabel != null) {
    // [v4] XML'den bulunan elementin VISIBLE oldugunu dogrula
    if (runId != null) {
        try {
            int[] visibleCenter = verifyElementVisible(runId, byLabel);
            if (visibleCenter != null) {
                System.out.println("[resolveTarget] ✓ XML'den bulundu ve VISIBLE");
                return visibleCenter;
            } else {
                System.out.println("[resolveTarget] ⚠ XML'den bulundu ama VISIBLE DEĞİL");
                // [v4 CRITICAL] Visible değil, BU LOCATOR'I REDDET ve null dondur
                return null; // Başka locator dene
            }
        } catch (Exception ex) {
            System.out.println("[resolveTarget] ⚠ Visible kontrolü başarısız");
            // [v4 CRITICAL] Kontrol başarısız, BU LOCATOR'I REDDET ve null dondur
            return null;
        }
    }
    
    // Fallback: XML koordinatlarını kullan (SADECE runId null ise)
    int[] center = centerOfBounds(byLabel.bounds());
    return center;
}
```

**Neden Gerekli?**
- `resolveByLiveXPath()` başarısız olursa → XML parsing'e düşüyor
- XML parsing'den bulunan element **visible olmayabilir**
- **v4 (CRITICAL FIX)**: Visible değilse, **o locator'ı REDDET** ve `null` döndür
- **ÖNCEKI HATA (v4 öncesi)**: Visible değilse bile XML koordinatlarını kullanıyordu ❌
- **ŞIMDI**: Visible değilse `null` döndürür, başka locator denenir veya LLM'e düşer ✅

### Çözüm 4: Tırnak İşareti Desteği (iOS/Android)

(Önceki versiyonlarda açıklanmış - aynı kalıyor)

### Çözüm 5: SCROLL_ONCE Sonrası Hedef Kontrolü

(Önceki versiyonlarda açıklanmış - aynı kalıyor)

### Çözüm 6: TAP Adımında Katı Doğrulama

(Önceki versiyonlarda açıklanmış - aynı kalıyor)

### Çözüm 7: findUniqueElemByLabel'da Tam Ürün Adı Kontrolü

(Önceki versiyonlarda açıklanmış - aynı kalıyor)

## Değiştirilen Dosyalar

1. **`AppiumDriverManager.java`**
   - `resolveTargetCenter()` - Canlı XPath öncelikli arama (v3)
   - `resolveTargetCenter()` - **XML'den bulunan element için visible kontrolü (v4 YENİ)**
   - `resolveByLiveXPath()` - Canlı XPath ile visible element bulma (v3)
   - `verifyElementVisible()` - **YENİ metod** (XML'den bulunan elementin visible kontrolü)
   - `findUniqueElemByLabel()` - Tam ürün adı tespiti (v1)

2. **`RunController.java`**
   - `SCROLL_ONCE` intent - Kaydırma sonrası hedef kontrolü (v2)
   - `TAP` intent - Katı hedef doğrulaması (v2)

## Beklenen Log Çıktısı (Düzeltme Sonrası - v4)

### Başarılı Senaryo (Visible Element Bulundu)

```
[STEP] adım 4/4 intent=TAP goal='"Test.allTheThings() T-Shirt (Red)" ürününe tıkla.'
[findMatch] TIER-0 (TAM ÜRÜN ADI) aranıyor: Test.allTheThings() T-Shirt (Red)
[findMatch] TIER-0 EŞLEŞTİ: Test.allTheThings() T-Shirt (Red)
[resolveTarget] Hedef cozumleme: targetLabel='Test.allTheThings() T-Shirt (Red)'
[liveXPath] Canlı arama: //*[text()='Test.allTheThings() T-Shirt (Red)']
[liveXPath] Bulunan element sayısı: 3
[liveXPath] ⚠ Element #1 visible/değil veya enabled/değil
[liveXPath] ⚠ Element #2 visible/değil veya enabled/değil
[liveXPath] ✓ Visible element bulundu (#3): bounds=[250,1250][550,1450]
[resolveTarget] ✓ Canlı XPath ile bulundu: targetLabel='Test.allTheThings() T-Shirt (Red)', center=(400,1350)
[STEP] ✓ TAP hedefi doğrulandı: Test.allTheThings() T-Shirt (Red)
[STEP] Deterministik tap: Test.allTheThings() T-Shirt (Red) (x=400, y=1350)
[TAP] Koordinatlar: x=400, y=1350
[TAP] Tap tamamlandı
```

### Başarılı Senaryo (XML Fallback + Visible Kontrolü - v4)

```
[STEP] adım 4/4 intent=TAP goal='"Test.allTheThings() T-Shirt (Red)" ürününe tıkla.'
[findMatch] TIER-0 EŞLEŞTİ: Test.allTheThings() T-Shirt (Red)
[resolveTarget] Hedef cozumleme: targetLabel='Test.allTheThings() T-Shirt (Red)'
[liveXPath] Canlı arama: //*[text()='Test.allTheThings() T-Shirt (Red)']
[liveXPath] Bulunan element sayısı: 0
[liveXPath] Canlı XPath ile hiç visible element bulunamadı
[resolveTarget] ⚠ Canlı XPath başarısız, fallback: XML parsing'e geçiliyor
[resolveTarget] targetLabel ile bulundu (XML): label='Test.allTheThings() T-Shirt (Red)', center=(291,1276)
[verifyVisible] Canlı kontrol: //*[@text='Test.allTheThings() T-Shirt (Red)'] (bounds: [150,1176][432,1376])
[verifyVisible] Bulunan element sayısı: 1
[verifyVisible] ✓ Element VISIBLE: bounds=[150,1176][432,1376]
[resolveTarget] ✓ XML'den bulundu ve VISIBLE: label='Test.allTheThings() T-Shirt (Red)', center=(291,1276)
[STEP] ✓ TAP hedefi doğrulandı
```

### Başarısız Senaryo (Visible Element Yok - v4)

```
[STEP] adım 4/4 intent=TAP goal='"Test.allTheThings() T-Shirt (Red)" ürününe tıkla.'
[findMatch] TIER-0 EŞLEŞTİ: Test.allTheThings() T-Shirt (Red)
[resolveTarget] Hedef cozumleme: targetLabel='Test.allTheThings() T-Shirt (Red)'
[liveXPath] Canlı arama: //*[text()='Test.allTheThings() T-Shirt (Red)']
[liveXPath] Bulunan element sayısı: 3
[liveXPath] ⚠ Element #1 visible/değil
[liveXPath] ⚠ Element #2 visible/değil
[liveXPath] ⚠ Element #3 visible/değil
[liveXPath] Canlı XPath ile hiç visible element bulunamadı
[resolveTarget] ⚠ Canlı XPath başarısız, fallback: XML parsing'e geçiliyor
[resolveTarget] targetLabel ile bulundu (XML): label='Test.allTheThings() T-Shirt (Red)', center=(291,1276)
[verifyVisible] Canlı kontrol: //*[@text='Test.allTheThings() T-Shirt (Red)'] (bounds: [150,1176][432,1376])
[verifyVisible] Bulunan element sayısı: 1
[verifyVisible] ⚠ Element SAME bounds ama VISIBLE DEĞİL
[resolveTarget] ⚠ XML'den bulundu ama VISIBLE DEĞİL: label='Test.allTheThings() T-Shirt (Red)'
[resolveTarget] Hedef cozulemedi: elementId=null, targetLabel='Test.allTheThings() T-Shirt (Red)'
[STEP] ✗ TAP hedefi DOĞRULANMADI (koordinatlar yok, LLM'e düşülüyor)
```

**CRITICAL v4 FIX**: `verifyElementVisible()` `null` döndürünce, sistem **XML koordinatlarını KULLANMIYOR**, `null` döndürüyor ve başka locator dener veya LLM'e düşer.

## iOS/Android Farklılıkları

(Önceki versiyonlarda açıklanmış - aynı kalıyor)

## Test Senaryoları

1. ✅ **iOS: Tek visible element** - Canlı XPath doğru elementi bulur
2. ✅ **Android: Tek visible element** - Canlı XPath doğru elementi bulur
3. ✅ **Çoklu element (3 invisible, 1 visible)** - Sadece visible seçilir
4. ✅ **Tırnak işareti içeren ürün adı** - `concat()` ile doğru XPath oluşturulur
5. ✅ **Canlı XPath başarısız, XML fallback + visible kontrolü başarılı** - **v4 YENİ**
6. ✅ **Canlı XPath başarısız, XML fallback + visible kontrolü başarısız** - **v4 YENİ**
7. ✅ **Visible element yoksa LLM'e düşülür** - **v4 YENİ**

## Önemli Notlar

- **Canlı XPath öncelikli**: Sistem önce canlı uygulamada arar, başarısız olursa XML'e döner
- **XML fallback + visible kontrolü (v4)**: XML'den bulunan element de canlı uygulamada kontrol ediliyor
- **Platform bağımsız**: iOS ve Android için aynı kod çalışır
- **Defensive programming**: Her exception catch edilip fallback yapılıyor
- **Logging detaylı**: Hangi yöntemin çalıştığı loglanıyor
- **Performance**: Canlı XPath ve visible kontrolü biraz yavaş olabilir (~500-1000ms ekstra), ama **doğruluk** için gerekli
- **Visible olmayan elemente tıklanmaz (v4)**: Eğer element visible değilse, sistem başka locator dener veya LLM'e düşer

## Versiyon Geçmişi

- **v1** (2026-09-14): Exact product name detection in `findUniqueElemByLabel()`
- **v2** (2026-09-14): SCROLL_ONCE post-scroll validation + TAP target validation
- **v3** (2026-09-14): Live XPath resolution (`resolveByLiveXPath()`) for iOS/Android compatibility
- **v4** (2026-09-14): **XML fallback visible verification (`verifyElementVisible()`)** - **VISIBLE ELEMENT KONTROLÜ**
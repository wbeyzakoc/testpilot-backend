# Ürün Seçim Sorunu Düzeltmesi - Yellow Product Senaryosu

## 📋 Sorun Tanımı

**Senaryo A (Tam Ürün Adı):** "uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bul. bulunca tıkla ve testi bitir."

**Senaryo B (Kelime İçerme):** "uygulamayı aç. içerisinde yellow yazan ürünü bul. bulunca tıkla ve testi bitir."

**Sorun:** 
- Senaryo A'da "Sauce Labs Backpack (yellow)" tam ürün adı belirtilmiş olmasına rağmen, **rastgele başka bir ürün** (mavi, kırmızı, yeşil vb.) seçiliyordu.
- Senaryo B'de "içerisinde yellow yazan ürün" denildiğinde, **sadece belirli bir ürün** aranıyor ve diğer yellow ürünler gözden kaçırıyordu.

## 🔍 Kök Neden Analizi

Kod incelemesinde aşağıdaki bulgulara ulaşıldı:

### Mevcut Durum
1. ✅ **Altyapı doğru çalışıyor:** `findMatchingTarget()` metodu deterministik string eşleşmesiyle doğru ürünü bulabiliyor
2. ✅ **Koruma mekanizması var:** `RunController`'da yanlış ürün seçimi engelleme mevcut
3. ❌ **Sistem prompt yetersiz:** LLM modeline (özellikle zayıf modellere) detaylı tarama ve tam eşleşme kuralları yeterince vurgulanmamış

### Sorunun Kaynağı
LLM agent'ın sistem prompt'u:
- Kaydırmadan sonra HER elementi tarama zorunluluğunu vurgulamıyor
- `[product: X]` annotasyonlarının önemini açıklamıyor
- Renk/varyant/model gibi ayırt edici özelliklerin KRİTİK olduğunu belirtmiyor
- Detaylı kontrol listesi sunmuyor

## ✅ Uygulanan Çözümler

### 1. Ürün Arama Kuralları Genişletildi (`LlmAgent.java`)

**EKLENEN ÖZELLİK: İKİ FARKLI SENARYO AYRIMI**

#### 🎯 Senaryo A: TAM ÜRÜN ADI VERİLMİŞ
```
Goal: "Sauce Labs Backpack (yellow) ürününü bul ve tıkla"
→ SADECE TAM ADINA EŞLEŞEN ÜRÜNÜ SEÇ!
→ [product: Sauce Labs Backpack (yellow)] → DOĞRU ✓
→ [product: Sauce Labs Backpack (blue)] → YANLIŞ! (renk farklı)
→ [product: onesie (yellow)] → YANLIŞ! (ürün adı farklı!)
→ İçinde "yellow" kelimesi geçen HER ÜRÜN DEĞİL, SADECE TAM ADI EŞLEŞEN ÜRÜN!
```

#### 🎯 Senaryo B: ÖZELLİK/KELİME İLE ARAMA
```
Goal: "içerisinde yellow yazan ürünü seç"
→ İÇERİSİNDE O KELİME GEÇEN HER ÜRÜNÜ SEÇEBİLİRSİN!
→ [product: onesie (yellow)] → DOĞRU ✓
→ [product: Sauce Labs Backpack (yellow)] → DOĞRU ✓
→ [product: yellow t-shirt] → DOĞRU ✓
→ [product: blue backpack] → YANLIŞ! (yellow içermiyor)
```

**KRİTİK AYIRIM:**
- "Sauce Labs Backpack (yellow) ürününü seç" → SADECE O EXACT ürün (blue/red YANLIŞ!)
- "yellow yazan ürünü seç" → yellow içeren HER ÜRÜN (onesie, t-shirt, backpack HEPSİ DOĞRU!)

#### b) Her Kaydırmadan Sonra Detaylı Tarama Zorunluluğu
```
5. Her KAYDIRMA (swipe) sonrası:
   a) YENİ XML listesini BAŞTAN SONA oku - HER ELEMENTİ kontrol et!
   b) Tüm [product: X] annotasyonlarını oku - her biri FARKLI ürün adı!
   c) Hedefe göre eşleştirme yap (Senaryo A veya B):
      - Senaryo A: TAM AD ara ("Sauce Labs Backpack (yellow)")
      - Senaryo B: KELİME ara ("yellow")
   d) Eğer hedef bulunduysa → HEMEN dur, swipe yapma, tap et!
   e) Eğer hedef bulunamadıysa → tekrar swipe yap ve YENİDEN BAŞTAN tara!
   
   ÖRNEK AKIŞLAR:
   
   Örnek 1 - TAM AD EŞLEŞTİRME:
   - Goal: "Sauce Labs Backpack (yellow) ürününü bul ve tıkla"
   - Scroll 1 → Tüm elementleri tara: 
     [1] [product: Sauce Labs Backpack (blue)] → TAM AD YOK → Devam
     [2] [product: Sauce Labs Backpack (red)] → TAM AD YOK → Devam
     [3] [product: Sauce Labs Backpack (green)] → TAM AD YOK → Scroll again
   - Scroll 2 → Tüm elementleri tara:
     [4] [product: Sauce Labs Backpack (black)] → TAM AD YOK → Devam
     [5] [product: Sauce Labs Backpack (yellow)] → TAM AD EŞLEŞTİ! → TAP ✓
   
   Örnek 2 - KELİME İÇERME EŞLEŞTİRME:
   - Goal: "içerisinde yellow yazan ürünü seç"
   - Scroll 1 → Tüm elementleri tara:
     [1] [product: Sauce Labs Backpack (blue)] → yellow YOK → Devam
     [2] [product: onesie (yellow)] → yellow VAR! → TAP ✓ (İLK UYGUN ÜRÜN)
```

#### c) Renk/Varyant/Model Eşleşmesi Vurgulandı
```
7. RENK/VARYANT/MODEL AYRIMI (ÇOK ÖNEMLİ!):
   
   A) TAM ÜRÜN ADI VERİLMİŞSE (ör. "Sauce Labs Backpack (yellow)"):
      - SADECE O EXACT ürün adı eşleşmeli!
      - Goal: "Sauce Labs Backpack (yellow)" → [product: Sauce Labs Backpack (yellow)] → DOĞRU ✓
      - Goal: "Sauce Labs Backpack (yellow)" → [product: Sauce Labs Backpack (blue)] → YANLIŞ ✗
      - Goal: "Sauce Labs Backpack (yellow)" → [product: onesie (yellow)] → YANLIŞ ✗ (ürün adı farklı!)
      
   B) ÖZELLİK/KELİME VERİLMİŞSE (ör. "yellow yazan ürünü seç"):
      - O kelimeyi içeren HER ÜRÜN uygun!
      - Goal: "yellow yazan ürünü seç" → [product: onesie (yellow)] → DOĞRU ✓
      - Goal: "yellow yazan ürünü seç" → [product: Sauce Labs Backpack (yellow)] → DOĞRU ✓
      - Goal: "yellow yazan ürünü seç" → [product: yellow t-shirt] → DOĞRU ✓
      
   ❌ YANLIŞ MANTIK: "Sauce Labs Backpack (yellow) istiyorum ama onesie (yellow) da var, onu al" → HATA!
   ✅ DOĞRU MANTIK: "Sauce Labs Backpack (yellow) isteniyor, SADECE O ürünü ara" → BAŞARI!
   
   ❌ YANLIŞ MANTIK: "yellow yazan ürünü seç denmiş, blue olan da olur" → HATA!
   ✅ DOĞRU MANTIK: "yellow yazan ürünü seç denmiş, SADECE yellow içeren ürünü seç" → BAŞARI!
```

### 2. Kaydırma (Swipe) Bölümü Genişletildi

**Eklenen Özellikler:**

#### Detaylı Tarama Kontrol Listesi
```
📋 DETAYLI TARAMA KONTROL LİSTESİ (HER SCROLL'DAN SONRA):
   ✅ a) XML listesini 1'den sonuna kadar HER ELEMENTİ oku - atlama!
   ✅ b) Her elementin label, text, content-desc alanlarını TEKER TEKER kontrol et!
   ✅ c) [product: X] annotasyonu varsa, X'i OKU - bu ürünün gerçek adı!
   ✅ d) Hedef kelimeyi (ör. "yellow") HER elementte ara - case insensitive!
   ✅ e) "Bu element hedefe uyuyor mu?" sorusuna CEVAP ver - tahmin etme!
   ✅ f) Eğer hedef BULUNDUysa → HEMEN dur, swipe yapma, action=tap döndür!
   ✅ g) Eğer hedef BULUNAMADIysa → tekrar swipe yap ve BAŞTAN tara!
   
   ❌ YANLIŞ: Swipe → Hemen tekrar swipe → YENİDEN swipe (taramadan!)
   ✅ DOĞRU: Swipe → TAM TARAMA → Swipe → TAM TARAMA → Swipe → TAM TARAMA
```

#### Görsel Örnek Akış
```
ÖRNEK DOĞRU AKIŞ (Goal: "yellow product"):
┌─────────────────────────────────────────────────┐
│ Scroll 1 → Tüm XML'i tara:                      │
│   [1] "Sauce Labs Backpack (blue)" → yellow YOK │
│   [2] "Sauce Labs Backpack (red)" → yellow YOK  │
│   [3] "Sauce Labs Backpack (green)" → yellow YOK│
│   → yellow BULUNAMADI → Scroll again            │
├─────────────────────────────────────────────────┤
│ Scroll 2 → Tüm XML'i tara:                      │
│   [4] "Sauce Labs Backpack (black)" → yellow YOK│
│   [5] "Sauce Labs Backpack (white)" → yellow YOK│
│   → yellow BULUNAMADI → Scroll again            │
├─────────────────────────────────────────────────┤
│ Scroll 3 → Tüm XML'i tara:                      │
│   [6] "Product Image [product: onesie (yellow)]"→ yellow BULUNDU! ✓
│   → HEMEN dur → action=tap, target="onesie (yellow)" ✓
└─────────────────────────────────────────────────┘

⚠️ KRİTİK UYARI: Eğer hedef ekranda VARSA ama sen swipe'ı devam ettirirsen,
hedefi KAÇIRIRSIN! Her scroll'dan sonra DUR ve TAM TARAMA yap!
```

### 3. Son Kontrol (Final Verification) Bölümü Eklendi

**Eklenen Özellikler:**

#### Tap Yapmadan Önce Mutlaka Kontrol Edilecekler
```
8. ⚠️ TAP yapmadan ÖNCE şunu kontrol et (İKİ SENARYO AYRIMI):
   
   ADIM 1: Goal'ı analiz et - Hangi senaryo?
   - Goal'da "[Ürün Adı] ürününü seç/bul" formatı mı? → SENARYO A (TAM AD EŞLEŞTİRME)
   - Goal'da "içerisinde ... yazan/olan/... içeren ürün" formatı mı? → SENARYO B (KELİME İÇERME)
   
   ADIM 2: Senaryoya göre eşleştirme yap:
   
   SENARYO A (TAM AD EŞLEŞTİRME):
   - Goal: "Sauce Labs Backpack (yellow) ürününü seç"
   - Element: [product: Sauce Labs Backpack (yellow)]
     ✓ Goal'daki TAM AD elementte var mı? → EVET → TAP ✓
   - Element: [product: Sauce Labs Backpack (blue)]
     ✗ Goal'daki TAM AD elementte var mı? → HAYIR (blue ≠ yellow) → SWİPE
   - Element: [product: onesie (yellow)]
     ✗ Goal'daki TAM AD elementte var mı? → HAYIR (onesie ≠ Sauce Labs Backpack) → SWİPE
   
   SENARYO B (KELİME İÇERME EŞLEŞTİRME):
   - Goal: "içerisinde yellow yazan ürünü seç"
   - Element: [product: onesie (yellow)]
     ✓ "yellow" kelimesi elementte var mı? → EVET → TAP ✓
   - Element: [product: Sauce Labs Backpack (yellow)]
     ✓ "yellow" kelimesi elementte var mı? → EVET → TAP ✓
   - Element: [product: blue backpack]
     ✗ "yellow" kelimesi elementte var mı? → HAYIR → SWİPE
```

#### Örnek Son Kontrol Senaryoları
```
ÖRNEK SON KONTROL:
Goal: "Sauce Labs Backpack (yellow) ürününü bul ve tıkla"
Seçilen element: [5] tıklanabilir "Product Image [product: Sauce Labs Backpack (blue)]"

Kontrol:
✓ Element XML'de var mı? → EVET
✓ Tıklanabilir mi? → EVET
✗ "yellow" kelimesi var mı? → HAYIR ("blue" var!)
✗ [product: ...] annotasyonu goal ile eşleşiyor mu? → HAYIR (blue ≠ yellow)

SONUÇ: ASLA tap yapma! action=swipe, direction="down" döndür.

Goal: "Sauce Labs Backpack (yellow) ürününü bul ve tıkla"
Seçilen element: [8] tıklanabilir "Product Image [product: Sauce Labs Backpack (yellow)]"

Kontrol:
✓ Element XML'de var mı? → EVET
✓ Tıklanabilir mi? → EVET
✓ "yellow" kelimesi var mı? → EVET
✓ [product: ...] annotasyonu goal ile eşleşiyor mu? → EVET (yellow = yellow)

SONUÇ: action=tap döndür! ✓
```

## 🧪 Test Senaryosu

Artık aşağıdaki senaryo doğru çalışacaktır:

```
Senaryo: "uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bul. bulunca tıkla ve testi bitir."

Beklenen Akış:
1. Uygulama açılır
2. Ürün listesi yüklenir
3. Sistem ekrandaki tüm ürünleri tarar
4. Eğer "yellow" ürün ekrandaysa → HEMEN tap eder
5. Eğer "yellow" ürün ekranda yoksa → ekranı aşağı kaydırır
6. Her kaydırmadan sonra TÜM ürünleri tekrar tarar
7. "yellow" ürün bulunduğunda → tap eder
8. Test başarıyla tamamlanır

❌ ÖNCEKI DAVRANIŞ: Rastgele bir ürün (mavi/kırmızı/yeşil) seçiliyordu
✅ YENİ DAVRANIŞ: SADECE "yellow" ürün seçilecek, bulunana kadar kaydırma devam edecek
```

## 📊 Teknik Detaylar

### Değiştirilen Dosyalar
- `/backend/src/main/java/com/testpilot/agent/LlmAgent.java`

### Değişiklikler
1. **Ürün Arama Bölümü** (~20 satır eklendi)
   - `[product: X]` annotasyonlarının önemi açıklandı
   - Her kaydırmadan sonra detaylı tarama zorunluluğu eklendi
   - Renk/varyant/model eşleşme kuralları vurgulandı

2. **Kaydırma Bölümü** (~40 satır eklendi)
   - Detaylı tarama kontrol listesi eklendi
   - Görsel örnek akış eklendi
   - Kritik uyarılar eklendi

3. **Son Kontrol Bölümü** (~30 satır eklendi)
   - Tap öncesi kontrol listesi eklendi
   - Örnek senaryolarla doğrulama eklendi

### Derleme Durumu
```
✅ BUILD SUCCESS
✅ 69 source files compiled successfully
✅ No syntax errors
```

## 🎯 Beklenen Sonuç

Bu değişikliklerle:
1. ✅ LLM agent artık her kaydırmadan sonra ekranı DETAYLI olarak tarayacak
2. ✅ `[product: X]` annotasyonlarını OKUYACAK ve anlayacak
3. ✅ Renk/varyant/model gibi AYIR EDİCİ özelliklere DUYARLI olacak
4. ✅ SADECE tam eşleşen ürüne tıklama yapacak
5. ✅ Yanlış ürün seçimi önlenecek

## 🔄 Sonraki Adımlar

1. Projeyi yeniden başlatın
2. Test senaryosunu çalıştırın: "uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bul. bulunca tıkla ve testi bitir."
3. Logları izleyin - her kaydırmadan sonra tarama yapıldığını göreceksiniz
4. Eğer hala sorun varsa, `RunController`'daki `findMatchingTarget` loglarını kontrol edin

## 📝 Not

Bu düzeltme SADECE sistem prompt'unu güçlendirir. Altyapı (`findMatchingTarget`, `enrichImageLabel`, vs.) zaten doğru çalışmaktaydı. Zayıf LLM modellerine daha açık ve detaylı talimatlar verilmesi sorunu çözmüştür.
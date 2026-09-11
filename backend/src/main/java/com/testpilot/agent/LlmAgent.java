package com.testpilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.model.AgentAction;
import com.testpilot.model.RunStep;
import com.testpilot.model.ScenarioSuggestion;
import com.testpilot.settings.AppSettingsService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

@Component
public class LlmAgent {

    private static final Pattern EXPLICIT_TEXT_INPUT = Pattern.compile(
            "(?iu)(^|[^\\p{L}])(yaz|yazın|yazınız|gir|girin|doldur|doldurun|type|enter)([^\\p{L}]|$)"
    );

    // api-key ve model artık application.properties'ten @Value ile DEĞİL,
    // AppSettingsService üzerinden veritabanından (panelden yönetilen) okunuyor.
    private final AppSettingsService appSettingsService;

    public LlmAgent(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    // Panelde "LLM API URL" alanı boş bırakılırsa (ya da hiç ayarlanmamışsa) bu
    // varsayılan kullanılır -- yani mevcut kurulumlar hiçbir şey yapmadan eskisi
    // gibi OpenRouter'a devam eder. Doldurulursa (örn. Ollama'nın OpenAI-uyumlu
    // "http://<ip>:11434/v1/chat/completions" adresi) istekler oraya gider --
    // istek/yanıt şekli aynı olduğu için ("/choices/0/message/content") başka
    // hiçbir kod değişikliği gerekmiyor.
    private static final String DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    private String resolveApiUrl() {
        String configured = appSettingsService.getOrCreate().getLlmApiUrl();
        return (configured != null && !configured.isBlank()) ? configured.trim() : DEFAULT_OPENROUTER_URL;
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            
                        ==================================================
                        EN KRİTİK KURAL: HER CEVAPTAN ÖNCE HEDEF TAMAMLAMA KONTROLÜ
                        ==================================================
                        Her cevap vermeden ÖNCE şu 5 adımı MUTLAKA uygula. Bunu ATLAMAK testi bozar.
            
                        ADIM 1 — Hedefi parçala:
                          Kullanıcının hedef cümlesini nokta/virgül/"ve" ile AYRI alt görevlere böl.
                          Örnek: "standard_user seç. login butonuna bas. bekle. ürün ekle. sepete tıkla. bitir."
                          → alt görevler: [user seç] [login bas] [bekle] [ürün ekle] [sepete tıkla] [bitir]
                          NOT: Hedef zaten numaralanmış bir liste olarak verildiyse (örn: "1. ... 2. ... 3. ..."),
                          bunu YENİDEN BÖLME — her numaralı satırı doğrudan bir alt görev olarak kullan.
            
                        ADIM 2 — Her alt görevi "Önceki adımların" listesiyle eşleştir:
                          Alt görevlerin HER BİRİ listede karşılığı var mı?
            
                        ADIM 3 — KARAR:
                          (a) TÜM alt görevler listede görünüyorsa:
                              → action="done" döndür.
                              → reasoning: "Hedefin tüm adımları tamamlandı."
                              → BAŞKA HİÇBİR ŞEY YAPMA. Yeni element arama, tap denemesi yapma.
            
                          (b) Bir alt görev EKSİKSE:
                              → SADECE o eksik alt göreve odaklan.
                              → TAMAMLANMIŞ alt görevlere GERİ DÖNME.
                              → Örneğin login zaten yapıldıysa, bir daha "login butonu" ARAMA.
            
                        ADIM 4 — EKRAN FARKINDALIĞI:
                          Mevcut XML'de login ekranı/ürün listesi/sepet ekranından hangisi var?
                          Login ekranı YOKSA login butonu da YOKTUR. Arama, boşuna uğraşma.
                          "Login butonuna tıklanmalı" gibi eski bir niyeti, mevcut ekranda login
                          butonu olmadığı halde TEKRAR hedefleme -- bu bir tutarsızlıktır.
            
                        ADIM 5 — TAMAMLANMIŞ İŞLEMİ TEKRAR YAPMA:
                          "Önceki adımların" listesinde gördüğün her şey YAPILDI. Onları tekrar yapma.
                          Yapılmamış olan SADECE hedefte kalan kısımdır.
            
                        --------------------------------------------------
                        ÖRNEK (bu kuralı öğrenmek için):
                        --------------------------------------------------
                        Hedef: "standard_user seç. login bas. bekle. ürün ekle. sepete tıkla. bitir."
                        Önceki adımlar:
                          1. wait
                          2. tap -> "standard_user" (kullanıcı seçildi)
                          3. tap -> "Login butonu" (giriş yapıldı)
                          4. wait (ekran yüklendi)
                          5. tap -> "Add to Cart" (ürün eklendi)
                          6. tap -> "Sepet ikonu" (sepete gidildi)
                        Mevcut XML: sepet ekranı görünüyor (login butonu YOK)
                        → Alt görevler: user✓ login✓ bekle✓ ürün✓ sepet✓ bitir=SON
                        → KARAR: action="done", reasoning="Tüm hedef adımları tamamlandı."
            
                        YANLIŞ davranış (bu senaryoda yapılmaması gereken):
                          {"reasoning": "login butonuna tıklanmalı", "action": "tap", "target": "Login butonu"}
                          → ÇÜNKÜ: login zaten yapıldı VE mevcut ekranda login butonu yok. Bu HATA'dır.
            
                        ==================================================
                        (aşağıda diğer kurallar devam ediyor)
                        ==================================================
            Sen bir mobil test otomasyon ajanısın. Verilen Türkçe hedefi, XML ağacına
            (accessibility tree) bakarak adım adım gerçekleştirirsin.

            ==================================================
            ÇIKTI FORMATI
            ==================================================
            SADECE şu JSON'u döndür (başka açıklama/markdown YOK):
            {"reasoning": "", "target": "", "elementId": "", "action": "tap|type|swipe|wait|done|fail", "x": 0, "y": 0, "text": "", "direction": ""}

            - reasoning: en fazla 12 kelimelik gerekçe
            - target: elementin kısa açıklaması ("Login butonu", "Sepet ikonu")
            - elementId: XML'deki [N] numarası, SADECE rakam, GERÇEK olmalı (uydurma YASAK)
            - action=tap: elementId zorunlu. Buton/ikon/kart gibi metin almayan elementler için
                        - action=type: elementId + text zorunlu. SADECE hedef açıkça metin girme istiyorsa kullan.
                            Hedefte "yaz", "gir", "doldur", "type" veya "enter" gibi açık bir talimat yoksa
                            action=type KULLANMA.
            - action=swipe: direction zorunlu (down|up|left|right)
            - action=wait: yüklemeyi bekle
            - action=done: hedef TAMAMEN bittiğinde
            - action=fail: gerçekten çıkmaz sokakta

            İlk adımda (adım 1): her zaman action="wait" döndür.

            ==================================================
            🔴 KURAL 0: SENARYO DIŞINA ÇIKMA VE AKSİYON FİİLİNE UY
            ==================================================
            Her aksiyonun anlamını hedef cümlesindeki fiilden çıkar:
            - "tıkla", "bas", "seç", "dokun", "aç" → SADECE action="tap"
            - "yaz", "gir", "doldur", "type", "enter" → action="type"
            - "kaydır", "scroll" → action="swipe"
            - "bekle" → action="wait"

            Hedef "standart_user yazısına tıkla" ise standart_user bir seçimdir:
            input alanı gibi görünse bile action="tap" döndür. Kullanıcı adı veya şifre
            alanına kendiliğinden yazma, alanları kendiliğinden doldurma, login akışını
            varsayma ve hedefte olmayan hiçbir aksiyonu ekleme.
            Hedef açıkça metin girme istemiyorsa, test değişkenleri mevcut olsa bile
            onları forma yazma. Her turda yalnızca hedefteki İLK TAMAMLANMAMIŞ adıma odaklan.

            ==================================================
            🔴 KURAL 1: BUTON METNİ DEĞİŞİKLİĞİ = BAŞARI KANITI
            ==================================================
            Bir butona tıkladıktan sonra butonun metni değiştiyse, o aksiyon BAŞARILI olmuştur.
            AYNI butona TEKRAR tıklama. Aşağıdaki geçişler KESİN başarı kanıtıdır:

            • "Add to Cart"  →  "Remove" / "Kaldır" / "Sepette" / "In Cart"  = BAŞARILI
            • "Sepete Ekle"  →  "Kaldır" / "Sepette" / "Eklendi"             = BAŞARILI
            • Boş sepet (0)  →  Dolu sepet (1, 2, ...)                       = BAŞARILI
            • "Giriş Yap"    →  Ana ekran/ürün listesi görünüyor              = BAŞARILI

            Böyle bir değişiklik gördüğünde:
              1. Bu adım için "reasoning" alanına "buton metni değişti, başarılı" yaz.
              2. Bu işlemi TEKRARLAMA. Sıradaki hedefe geç.
              3. Eğer bu hedefin SON adımıysa → action="done".
              4. Değilse → hedefin SIRADAKİ kısmına odaklan (ör. sepet ikonuna tıkla).

            ==================================================
            🔴 KURAL 2: HEDEF ANALİZİ VE DETAYLI ARAMA STRATEJİSİ
            ============================================================
            HER hedef için şu analizi yap:

            ADIM 1 - HEDEF KATEGORİSİ:
            (A) SPESİFİK hedef — belirli ürün/element adı, renk, model verilmiş:
                "Sauce Labs Backpack (yellow) adlı ürünü sepete ekle"
                "yellow yazan ürünü seç"
                "iPhone 15 modelini bul"
                "mavi renkli ürünü ekle"
                → SADECE o ürünü bul ve seç, başka ürüne dokunma
                → DETAYLI ARAMA ve KAYDIRMA ZORUNLUDUR

            (B) JENERİK hedef — ürün adı verilmemiş, genel işlem:
                "ürünü sepete ekle", "bir ürün ekle", "herhangi bir ürün"
                → Ekranda GÖRÜNEN İLK uygun ürünü kullan
                → Kaydırma YAPMA (ama yine de detaylı kontrol et)

            ADIM 2 - EKRANI DETAYLI İNCELE (HER İKİ DURUMDA DA):
            - XML'i BAŞTAN SONUNA tara (sadece ilk elemente bakma)
            - Her elementin text, content-desc, [urun: ...] uzantılarını kontrol et
            - Popup, banner, onboarding var mı? (varsa önce bunları kapat)
            - "Acaba başka element var mı?" diye sor
            - Hızlı karar verme, tam tarama yap

            ADIM 3 - HEDEF EKRANDA VAR MI?
            - Eğer hedef ekranda VAR → Doğru element seç, işlemi yap
            - Eğer hedef ekranda YOKSA → KAYDIRMA bölümüne git (sayfa 10)
              * Spesifik hedef: MUTLAKA kaydırma yap, detaylı arama devam et
              * Jenerik hedef: 1-2 kaydırma dene, sonra ilk bulunan uygun ürünü kullan

            ADIM 4 - DOĞRU ELEMENTİ SEÇ:
            - Spesifik hedef: SADECE hedefe tam uyan elementi seç (renk, model, isim eşleşmesi)
            - Jenerik hedef: İlk uygun elementi seç (ama tıklanabilir mi kontrol et)
            - clickable=false ise → kapsayıcı clickable element bul
            - [urun: X] varsa → X ile hedefi karşılaştır

            ÖNEMLİ KURALLAR:
            1. HER zaman detaylı kontrol yap, XML'i tam tara
            2. Spesifik hedefte KAYDIRMA ZORUNLUDUR (max 8 kez)
            3. HER kaydırmadan SONRA XML'i BAŞTAN detaylı tara
            4. Sadece ilk görünen ürüne güvenme, TÜM listeyi incele
            5. Jenerik hedefte gereksiz kaydırma yapma, görünen ilk uygun ürünü kullan

            ==================================================
            🔴 KURAL 3: ÇOK ADIMLI HEDEF TAKİBİ
            ==================================================
            Hedef birden çok cümle içeriyorsa (ör. "seç. bas. bekle. ekle. tıkla. bitir"),
            bunlar SIRAYLA tamamlanacak alt adımlardır. "Önceki adımların" listesine bakarak:

            - Hangi alt adımlar TAMAMLANDI? (tarihe bak, tekrar yapma)
            - Şu an HANGİ alt adımdasın?
            - Sıradaki alt adım ne?

            ASLA tamamlanmış bir alt adıma geri dönme. Örnek:
              Hedef: "user seç. login bas. bekle. ürün ekle. sepet ikonuna tıkla. bitir."
              Adımlar: 1.user seçildi ✓  2.login basıldı ✓  3.beklendi ✓  4.ürün eklendi ✓
              ŞİMDİ: 5. sepete tıkla → sıradaki aksiyon bu olmalı.

            ==================================================
            KARAR SIRASI
            ==================================================
            1. Adım 1? → action="wait"
            2. "Önceki adımlar"ı oku. Şu an yapmak istediğin işlem zaten yapıldı mı?
               Yapıldıysa TEKRARLAMA, sıradaki alt adıma geç.
            3. Ekranda engelleyici popup/dialog/onboarding var mı? Varsa ÖNCE onu kapat
               ("Skip"/"Atla" > "Continue"/"Devam"/"İleri" > "Kapat"/"Close"/"OK").
            4. Hedefin tamamlandığına dair POZİTİF kanıt var mı? → action="done"
            5. Hedefteki elementi XML'de ara: label, text, content-desc, [urun: ...] UZANTISI,
               resourceId — HEPSİNE bak.
            6. Bulundu? → yazılabilir alan: "type", diğer: "tap".
            7. XML'de yok? → action="swipe" (yön aşağıda).
            8. Ekran kararsız/animasyonda? → action="wait" (art arda EN FAZLA 1).
            9. 2-3 farklı element+kaydırma denendi, ilerleme yok? → action="fail".

            ==================================================
            EŞLEŞTİRME
            ==================================================
            Öncelik: tam > güçlü kısmi > anahtar kelime > yakın anlam > gezinme elementi.

            - "clickable=false" elementi KULLANILAMAZ yapmaz. Content-desc/text olan her elementi
              aday say. Tıklanamayan metin varsa onu KAPSAYAN clickable üst öğeyi seç.
            - [urun: X] uzantısı: "Product Image" gibi genel etiketli görsellerin hangi ürüne
              ait olduğunu gösterir. Görsel seçerken MUTLAKA bu X ile hedefi karşılaştır.
            - Yazım toleransı: "standart_user" ~ "standard_user" gibi 1-2 karakter farkı OK.
            - RENK/VARYANT önemli: "yellow" belirtilmişse SADECE o varyantı seç.

            ==================================================
            KAYDIRMA (SWIPE) - ÜRÜN ARAMA İÇİN KRİTİK KURALLAR
            ============================================================
            KAYDIRMA (SWIPE) - GENEL KURALLAR
            ==================================================
            direction="down" → EKRANI AŞAĞI kaydır → listede SONRAKİ öğeler görünür.
            direction="up"   → EKRANI YUKARI kaydır → listede ÖNCEKİ öğeler görünür.

            NE ZAMAN KAYDIRMA YAPILIR?

            A) SPESİFİK HEDEFTE (örn: "yellow yazan ürünü seç", "iPhone 15 bul"):
               1. ÖNCE XML'i DETAYLI Tara:
                  - Her elementin text/content-desc'ini kontrol et
                  - [urun: ...] uzantılarını kontrol et
                  - Renk/varyant isimlerini ara ("yellow", "blue", "red" vb.)
                  - Eğer hedef BULUNDU → SADECE o elemente tıkla, kaydırma YAPMA

               2. Hedef BULUNAMAZSA kaydırma başlat:
                  - direction="down" ile başla (liste genelde en üstten başlar)
                  - HER kaydırmadan SONRA:
                    a) XML'i BAŞTAN detaylı tara
                    b) Her görünen elementin ismini/varyantını KONTROL ET
                    c) Hedef kelimeyi özellikle ara
                    d) Eğer bulundu → dur, o elemente tıkla

               3. Kaydırma Stratejisi:
                  - İlk 3 kaydırma: direction="down"
                  - Hala bulunamadıysa: direction="up" dene
                  - Max 8 kaydırma yap
                  - HER kaydırma FARKLI elementler göstermeli

               4. KRİTİK: HER KAYDIRMA SONRASI DETAYLI ARAMA:
                  - "Acaba hedef element var mı?" diye XML'i baştan sonuna tara
                  - Sadece ilk elemente bakma, TÜM listeyi kontrol et
                  - [urun: ...] uzantısı olan her görselin yanındaki text'i oku
                  - Renk isimlerini (yellow, blue, red, green, black, white) özellikle ara

               5. Hedef bulunduğunda:
                  - O ELEMENTİN clickable parent'ını bul
                  - SADECE o elemente tıkla
                  - Başka elemente dokunma

            B) JENERİK HEDEFTE (örn: "bir ürün ekle", "ürünü sepete ekle"):
               - KAYDIRMA YAPMA — ekranda görünen İLK uygun elementi kullan
               - "Acaba başka element var mı?" diye arama
               - Gereksiz kaydırma yapma, hedefe odaklan

            GENEL KURALLAR (her iki durum için):
            1. İlk 3 kaydırma: direction="down"
            2. 3 kez aynı yönde kaydır, XML değişmiyorsa → "up" dene
            3. Max 8 kaydırma. Bunlar LOOP DEĞİL, devam et.
            4. HER kaydırmadan sonra XML'i baştan tara.
            5. Ekran kararsız/animasyonda ise önce wait yap, sonra kaydır.

            ==================================================
            POPUP ÖNCELİĞİ
            ==================================================
            Popup/dialog/izin/onboarding varsa önce onu geç:
            1. "Skip"/"Atla"         2. "Continue"/"Devam"/"Next"/"İleri"         3. "Close"/"Kapat"/"OK"
            ASLA tıklama: "Learn more", "Daha fazla bilgi", "About", "Detaylar" (seni uzaklaştırır).

            ==================================================
            DÖNGÜ TANIMI
            ==================================================
            SADECE şu LOOP sayılır: Aynı elementId'ye art arda 3+ kez tıklandı VE XML hiç değişmedi.
            Bu durumda: farklı bir elementId dene (üst öğe), en fazla 1 wait, sonra fail.

            LOOP SAYILMAYAN (devam et):
            - Hedef ararken art arda swipe yapmak (NORMAL).
            - Farklı elementId'lere tıklamak.
            - Buton metni değiştiyse (başarı kanıtı, tekrar tıklama YASAK).

            ==================================================
            DONE KURALI
            ==================================================
            action="done" SADECE şu kanıtlarla:
            - Sepet sayacı/rozeti arttı
            - "Add to Cart" → "Remove"/"Kaldır" oldu
            - "Sepete eklendi" toast/bildirim göründü
            - Giriş sonrası ana ekran geldi VE giriş formu kayboldu
            - Hedefin son adımı için POZİTİF görsel kanıt var

            Sadece bir elementi bulmak tamamlanma DEĞİLDİR. Belirsiz durumda done döndürme.

            ==================================================
            FAIL KURALI
            ==================================================
            action="fail" öncesi: label/text/content-desc/resourceId tekrar kontrol, kısmi eşleşme
            dene, clickable üst öğe dene, her iki yönde kaydır (max 8). Sadece şu yüzden fail YOK:
            element ekran dışında, clickable=false, yazım farkı, ya da bir swipe çıkarabilir.

            ==================================================
            GEREKSİZ GEZİNME YAPMA
            ==================================================
            Hedef mevcut ekranda ZATEN tamamlanıyorsa menü/sekme/ara ekran açma.

            Test değişkenleri verilirse (mail/şifre vb.) onları kullan.

            ==================================================
            SON KONTROL
            ==================================================
            1. Tek geçerli JSON mu? Başka metin yok mu?
            2. action geçerli mi? (tap|type|swipe|wait|done|fail)
            3. tap/type için elementId XML'de var mı?
            4. type için text dolu mu?
            5. swipe için direction doğru mu?
            6. wait/done/fail için elementId="" mi?
            7. Aynı elementId'ye XML değişmeden 3+ kez mi tıklıyorsun? Farklı strateji dene.
            8. Buton metni zaten değişti mi (başarı kanıtı)? Tekrar tıklama!
            """;


    public AgentAction decideNextAction(String goal, Map<String, String> variables, String screenshotBase64, String pageSource, int stepNumber, List<RunStep> previousSteps, String repeatWarning) {
        int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                AgentAction result = makeLlmRequest(goal, variables, screenshotBase64, pageSource, stepNumber, previousSteps, repeatWarning);
                if (result != null && result.getAction() != null) {
                    enforceScenarioAction(result, goal);
                    return result;
                }
                System.out.println("Model boş/geçersiz aksiyon döndürdü, tekrar deneniyor (deneme: " + attempt + ")");
            } catch (Exception e) {
                System.out.println("Deneme " + attempt + "/" + maxRetries + " başarısız: " + e.getMessage());
                lastException = e;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000 * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("Model " + maxRetries + " denemede geçerli JSON döndürmedi: " +
                (lastException != null ? lastException.getMessage() : "Bilinmeyen hata"), lastException);
    }

    private void enforceScenarioAction(AgentAction action, String goal) {
        if (!"type".equalsIgnoreCase(action.getAction()) || allowsExplicitTextInput(goal)) {
            return;
        }

        action.setAction("tap");
        action.setText("");
        action.setReasoning("Senaryoda metin girme talimatı yok; hedefe dokunuluyor");
        System.out.println("[AGENT-GUARD] Açık metin girme talimatı olmadığı için type -> tap dönüştürüldü");
    }

    private boolean allowsExplicitTextInput(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        return EXPLICIT_TEXT_INPUT.matcher(goal.toLowerCase(Locale.ROOT)).find();
    }

    private AgentAction makeLlmRequest(String goal, Map<String, String> variables, String screenshotBase64, String pageSource, int stepNumber, List<RunStep> previousSteps, String repeatWarning) throws IOException {
        try {
            var userContent = mapper.createArrayNode();

            String context = buildVariablesContext(variables);

            StringBuilder historyText = new StringBuilder();
            if (previousSteps != null && !previousSteps.isEmpty()) {
                historyText.append("Önceki adımlarda yaptıkların (en yenisi en altta):\n");
                // Daha once son 3 adimla siniirliydi -- ayni elemente aradaki baska bir
                // tiklamadan (orn. bir popup/Devam et) sonra tekrar donuldugunde model bu
                // eski denemeyi artik goremiyor, "ilk kez goruyormus" gibi tekrar deniyordu.
                // 6'ya cikarilarak bu pencere genisletildi.
                int start = Math.max(0, previousSteps.size() - 6);
                for (int i = start; i < previousSteps.size(); i++) {
                    RunStep s = previousSteps.get(i);
                    historyText.append("- ").append(s.getStep()).append(". adım: ")
                            .append(s.getAction()).append(" -> ").append(s.getTarget())
                            .append(" (").append(s.getReasoning()).append(")\n");
                }
                historyText.append("\n");
            }

            // RunController'daki tekrar-tespiti (repeatCount==1, henuz FAIL esigi olan 2'ye
            // ulasmadan) tetiklendiginde buraya dolu bir uyari metni geliyor -- promptun EN
            // ONUNE, hedef/XML'den once koyuyoruz ki model gormezden gelmesi zor olsun.
            String repeatWarningBlock = (repeatWarning != null && !repeatWarning.isBlank())
                    ? ("!!! " + repeatWarning + " !!!\n\n")
                    : "";

            var textNode = mapper.createObjectNode();
            textNode.put("type", "text");
            String baseText = repeatWarningBlock + context + historyText + "Hedef: " + goal + "\nBu " + stepNumber + ". adım. "
                    + "Ekrandaki XML ağacı:\n" + pageSource;

            // NOT (2026-09-10): Burada eskiden hedef metni "bul" kelimesini iceriyorsa ozel bir
            // arama/kaydirma ipucu ekleniyordu. Bu ipucu SYSTEM_PROMPT'taki genel KAYDIRMA/URUN
            // ARAMA kurallariyla CAKISIYORDU ve -- "yukari"/"asagi" kelimeleri icin -- direction
            // degerini SYSTEM_PROMPT'un/gercek swipe() davranisinin TERSI sekilde aciklayarak modeli
            // yanlis yone kaydirmaya yonlendiriyordu (bkz. AppiumDriverManager.swipe() ustundeki
            // 2026-09-10 duzeltme notu). Artik tum arama/kaydirma rehberligi TEK bir yerde,
            // SYSTEM_PROMPT icinde (KAYDIRMA / URUN ARAMA bolumleri) veriliyor -- iki yerin
            // birbirinden sapip celiskili talimat vermesini onlemek icin bu ozel-durum kodu
            // kaldirildi.

            textNode.put("text", baseText + "\n\nYukarıdaki XML'e bakarak bir sonraki aksiyonu belirle.");
            userContent.add(textNode);



            var messages = mapper.createArrayNode();
            var systemMsg = mapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", SYSTEM_PROMPT);
            messages.add(systemMsg);

            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.set("content", userContent);
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", appSettingsService.getOrCreate().getOpenrouterModel());
            body.set("messages", messages);
            body.put("max_tokens", 1024);
            // Bu bir "hangi elemente tiklamaliyim" gibi TEK dogru cevabi olan bir karar --
            // yaraticilik degil tutarlilik istiyoruz. Varsayilan temperature (~0.7-1.0) modelin
            // ayni ekranda farkli seferlerde farkli/kararsiz secimler yapmasina katkida bulunuyordu.
            // Dusuk bir deger (0.15) ayni durumda neredeyse hep ayni karari vermesini sagliyor.
            body.put("temperature", 0.15);

            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(body),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(resolveApiUrl())
                    .addHeader("Authorization", "Bearer " + appSettingsService.getOpenrouterApiKeyDecrypted())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorBody = response.body() != null ? response.body().string() : "(boş yanıt)";
                    throw new RuntimeException("LLM isteği başarısız: " + response.code() + " " + response.message() + " -> " + errorBody);
                }
                String responseBody = response.body().string();
                JsonNode root = mapper.readTree(responseBody);
                String content = root.at("/choices/0/message/content").asText();

                String jsonOnly = extractJson(content);
                AgentAction result;
                try {
                    result = mapper.readValue(jsonOnly, AgentAction.class);
                } catch (Exception parseEx) {
                    System.out.println("JSON parse edilemedi, modelin ham cevabı:\n" + content);
                    System.out.println("JSON temizlenmiş hali:\n" + jsonOnly);
                    // JSON'ı temizlemeyi dene - kaçış dizeleri sorun olabilir
                    String cleanedJson = cleanJsonResponse(jsonOnly);
                    try {
                        result = mapper.readValue(cleanedJson, AgentAction.class);
                        System.out.println("JSON temizleme ile başarıyla parse edildi");
                    } catch (Exception cleanEx) {
                        throw new RuntimeException("Model geçerli JSON döndürmedi: " + parseEx.getMessage() +
                                " (Temizlenmiş JSON da parse edilemedi: " + cleanEx.getMessage() + ")");
                    }
                }
                if (result == null || result.getAction() == null) {
                    System.out.println("Model boş/geçersiz aksiyon döndürdü, ham cevap:\n" + content);
                    throw new RuntimeException("Model boş veya geçersiz bir aksiyon döndürdü (muhtemelen API boş content döndü)");
                }
                return result;
            }
        } finally {
            // IOException'ı yeniden fırlat, wrapper olarak değil
            // Dış retry döngüsü bunu yakalayacak
        }
    }

    private String buildVariablesContext(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Kullanılabilecek test değişkenleri:\n");
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String extractJson(String content) {
        if (content == null) return "{}";
        String cleaned = content.trim();
        cleaned = cleaned.replaceAll("```json", "").replaceAll("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    /**
     * JSON yanıtını temizler - özellikle kaçış dizeleri ve geçersiz karakterleri düzeltir.
     * LLM'ler bazen string alanlarda tırnak işaretlerini kaçış dizesi olmadan kullanır.
     */
    private String cleanJsonResponse(String json) {
        if (json == null || json.isBlank()) return json;

        try {
            // Önce geçerli JSON olup olmadığını kontrol et
            mapper.readTree(json);
            return json; // Zaten geçerli
        } catch (Exception e) {
            // Geçersiz JSON, temizlemeyi dene
        }

        String cleaned = json;

        // reasoning ve target alanlarındaki kaçışsız tırnak işaretlerini düzelt
        // Örnek: "reasoning": "Bu "butona" tıkla" → "reasoning": "Bu \"butona\" tıkla"
        // Basit yaklaşım: string içindeki çift tırnakları kaçışlı hale getir
        // reasoning alanını bul ve içindeki kaçışsız tırnakları düzelt
        if (cleaned.contains("\"reasoning\":")) {
            int reasoningStart = cleaned.indexOf("\"reasoning\":");
            int reasoningValueStart = cleaned.indexOf("\"", reasoningStart + 12);
            if (reasoningValueStart >= 0) {
                reasoningValueStart++; // açılış tırnağından sonra
                int reasoningValueEnd = cleaned.indexOf("\"", reasoningValueStart);
                if (reasoningValueEnd > reasoningValueStart) {
                    String reasoningValue = cleaned.substring(reasoningValueStart, reasoningValueEnd);
                    String cleanedReasoning = reasoningValue.replace("\"", "\\\"");
                    cleaned = cleaned.substring(0, reasoningValueStart) +
                              cleanedReasoning +
                              cleaned.substring(reasoningValueEnd);
                }
            }
        }

        // target alanı için de aynı işlem
        if (cleaned.contains("\"target\":")) {
            int targetStart = cleaned.indexOf("\"target\":");
            int targetValueStart = cleaned.indexOf("\"", targetStart + 9);
            if (targetValueStart >= 0) {
                targetValueStart++;
                int targetValueEnd = cleaned.indexOf("\"", targetValueStart);
                if (targetValueEnd > targetValueStart) {
                    String targetValue = cleaned.substring(targetValueStart, targetValueEnd);
                    String cleanedTarget = targetValue.replace("\"", "\\\"");
                    cleaned = cleaned.substring(0, targetValueStart) +
                              cleanedTarget +
                              cleaned.substring(targetValueEnd);
                }
            }
        }

        // text alanı için de aynı işlem
        if (cleaned.contains("\"text\":")) {
            int textStart = cleaned.indexOf("\"text\":");
            int textValueStart = cleaned.indexOf("\"", textStart + 7);
            if (textValueStart >= 0) {
                textValueStart++;
                int textValueEnd = cleaned.indexOf("\"", textValueStart);
                if (textValueEnd > textValueStart) {
                    String textValue = cleaned.substring(textValueStart, textValueEnd);
                    String cleanedText = textValue.replace("\"", "\\\"");
                    cleaned = cleaned.substring(0, textValueStart) +
                              cleanedText +
                              cleaned.substring(textValueEnd);
                }
            }
        }

        return cleaned.trim();
    }

    // "Ne test etmek istiyorsun?" alanindaki auto_awesome ikonuna baglaniyor -- kullanicinin
    // yazdigi ham/dagimik metni analiz edip NUMARASIZ, ATOMIK adimlar halinde yeniden yaziyor.
    // Amac: calisma zamaninda zayif bir modelin (Qwen gibi) hedefi her adimda yeniden parcalamak
    // zorunda kalmadan, onceden ayristirilmis net bir adim listesi kullanabilmesi (bkz. SYSTEM_PROMPT
    // ADIM 1 notu). HALUSINASYON KORUMASI: prompt, kullanicinin belirtmedigi buton/alan/element
    // isimlerini UYDURMAMASI icin acikca kisitlanmis; kullanicinin verdigi urun/kullanici adi/
    // degisken gibi somut degerler DEGISTIRILMEDEN aynen korunuyor. callForSuggestions'tan farkli
    // olarak JSON degil DUZ METIN donuyor.
    public String improveGoalText(String rawText) {
        if (rawText == null || rawText.isBlank()) return rawText;
        try {
                String prompt = "Asagidaki metin bir mobil uygulama test senaryosunun hedefini tanimliyor "
                    + "(bir kullanici bunu serbest metin olarak yazdi). Bu metni NUMARASIZ, ATOMIK "
                    + "adimlar halinde yeniden yaz. Kurallar:\n"
                    + "1) Metindeki her ayri eylemi (nokta/virgul/\"ve\"/\"sonra\" ile ayrilan kisimlari) "
                    + "AYRI bir satir yap. Satirlarin basina numara veya madde imi koyma.\n"
                    + "2) Her adim KISA olmali ve TEK bir eylem icermeli (orn: \"Login butonuna bas\").\n"
                    + "3) AYNI anlami ve AYNI hedefi KORU; yeni bir eylem/adim EKLEME ve mevcut bir "
                    + "adimi ATLAMA.\n"
                    + "4) Kullanicinin metninde gecen somut degerleri (urun adi, kullanici adi, sifre, "
                    + "sayi, degisken vb.) OLDUGU GIBI, harf/yazim degistirmeden AYNEN koru.\n"
                    + "5) Kullanicinin metninde ACIKCA belirtilmeyen hicbir buton/alan/ekran/element "
                    + "ismi UYDURMA; kullanici hangi ifadeyi kullandiysa adimlarda da ayni/benzer genel "
                    + "ifadeyi kullan.\n"
                    + "6) SADECE adimlari alt alta dondur, baska hicbir aciklama, baslik, etiket "
                    + "veya tirnak ekleme.\n\n"
                    + "Metin: " + rawText;

            var messages = mapper.createArrayNode();
            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", appSettingsService.getOrCreate().getOpenrouterModel());
            body.set("messages", messages);
            body.put("max_tokens", 500);
            body.put("temperature", 0.3);

            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(body),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(resolveApiUrl())
                    .addHeader("Authorization", "Bearer " + appSettingsService.getOpenrouterApiKeyDecrypted())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorBody = response.body() != null ? response.body().string() : "(boş yanıt)";
                    throw new RuntimeException("LLM isteği başarısız: " + response.code() + " -> " + errorBody);
                }
                String responseBody = response.body().string();
                JsonNode root = mapper.readTree(responseBody);
                String content = root.at("/choices/0/message/content").asText();
                if (content == null || content.isBlank()) return rawText;
                return removeStepNumbering(content.trim().replaceAll("^\"|\"$", ""));
            }
        } catch (IOException e) {
            throw new RuntimeException("Metin iyileştirilirken hata oluştu", e);
        }
    }

    private String removeStepNumbering(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replaceFirst("^(?:\\d+[.)]|[-*•])\\s*", ""))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<ScenarioSuggestion> callForSuggestions(String prompt, int maxTokens) {
        try {
            var messages = mapper.createArrayNode();
            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", appSettingsService.getOrCreate().getOpenrouterModel());
            body.set("messages", messages);
            body.put("max_tokens", maxTokens);

            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(body),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(resolveApiUrl())
                    .addHeader("Authorization", "Bearer " + appSettingsService.getOpenrouterApiKeyDecrypted())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String errorBody = response.body() != null ? response.body().string() : "(boş yanıt)";
                    throw new RuntimeException("LLM isteği başarısız: " + response.code() + " -> " + errorBody);
                }
                String responseBody = response.body().string();
                JsonNode root = mapper.readTree(responseBody);
                String content = root.at("/choices/0/message/content").asText();

                String jsonOnly = extractJsonArray(content);
                return mapper.readValue(jsonOnly, mapper.getTypeFactory().constructCollectionType(List.class, ScenarioSuggestion.class));
            }
        } catch (IOException e) {
            throw new RuntimeException("Senaryo önerisi alınırken hata oluştu", e);
        }
    }
    public List<ScenarioSuggestion> suggestScenarios(String goal, List<RunStep> steps) {
        StringBuilder visitedPages = new StringBuilder();
        if (steps != null && !steps.isEmpty()) {
            visitedPages.append("Test sırasında gerçekten gidilen sayfalar/elementler:\n");
            for (RunStep s : steps) {
                if (s.getTarget() != null && !s.getTarget().isBlank()) {
                    visitedPages.append("- ").append(s.getTarget()).append("\n");
                }
            }
            visitedPages.append("\n");
        }

        String prompt = """
            Sen bir mobil QA test uzmanısın. Aşağıdaki test senaryosuna VE testin gerçekten uygulama
            içinde gezindiği sayfalara/elementlere bakarak, bu akışla İLİŞKİLİ, test edilmesi faydalı
            olacak 15 farklı EK senaryo öner. Sadece kalıp/genel senaryolar üretme - gördüğün sayfadaki HER
            somut elemente (menü öğeleri, butonlar, alanlar, form kuralları) tek tek bakarak, bu akışta
            gerçekten oluşabilecek TÜM olası senaryoları sistemli ve kapsamlı şekilde tara. Amacın sadece
            ilk akla gelen birkaç fikri değil, bu akışın kapsayabileceği farklı durumların tamamını
            (her sayfa için en az bir tane olacak şekilde) ortaya çıkarmak.

            Her öneri şu kategorilerden birine ait olmalı: "Negatif Test" (yanlış/hatalı girdi),
            "Sınır Durumu" (edge case, aşırı uzun metin, özel karakter vb.), "Gezinme Çeşitliliği"
            (farklı bir yoldan aynı hedefe ulaşma), "UX/Durum Kontrolü" (yükleme durumu, hata mesajı
            doğruluğu vb.), "Performans" (yavaş bağlantı, arka arkaya hızlı tıklama vb.) veya
            "Erişilebilirlik" (ekran okuyucu, büyük yazı tipi vb.).

            Her öneri için:
            - "senaryo": kısa Türkçe bir test cümlesi (mevcut format: "hesabıma git. yanlış şifre ile giriş yapmayı dene." gibi)
            - "kategori": yukarıdaki kategorilerden biri
            - "sayfa": bu senaryonun hangi ekran/sayfa ile ilgili olduğu (gezinme geçmişindeki gerçek sayfa isimlerine göre belirle)
            - "neden": bu senaryonun neden test edilmeye değer olduğuna dair kısa bir gerekçe

            SADECE aşağıdaki JSON formatında bir dizi döndür, başka hiçbir açıklama ekleme:
            [{"senaryo": "...", "kategori": "...", "sayfa": "...", "neden": "..."}, ...]

            Orijinal test senaryosu: "%s"

            %s
            """.formatted(goal, visitedPages);

        return callForSuggestions(prompt, 6000);
    }
    public List<ScenarioSuggestion> suggestScenariosForPage(String goal, List<RunStep> steps, String pageName) {
        StringBuilder visitedPages = new StringBuilder();
        if (steps != null && !steps.isEmpty()) {
            visitedPages.append("Test sırasında gerçekten gidilen sayfalar/elementler:\n");
            for (RunStep s : steps) {
                if (s.getTarget() != null && !s.getTarget().isBlank()) {
                    visitedPages.append("- ").append(s.getTarget()).append("\n");
                }
            }
            visitedPages.append("\n");
        }

        String prompt = """
            Sen bir mobil QA test uzmanısın. Kullanıcı özellikle "%s" sayfası/ekranı için EK test
            senaryoları istiyor. Aşağıdaki orijinal test akışına ve gezinme geçmişine bakarak, "%s"
            sayfasıyla ilgili 3 farklı, birbirinden farklı senaryo öner. Sadece kalıp senaryolar
            üretme - bu sayfada gerçekten karşılaşılabilecek somut durumları düşün.

            Her öneri şu kategorilerden birine ait olmalı: "Negatif Test", "Sınır Durumu",
            "Gezinme Çeşitliliği", "UX/Durum Kontrolü", "Performans", "Erişilebilirlik".

            Her öneri için:
            - "senaryo": kısa Türkçe bir test cümlesi
            - "kategori": yukarıdaki kategorilerden biri
            - "sayfa": her zaman "%s" olarak doldur
            - "neden": bu senaryonun neden test edilmeye değer olduğuna dair kısa bir gerekçe

            SADECE aşağıdaki JSON formatında bir dizi döndür, başka hiçbir açıklama ekleme:
            [{"senaryo": "...", "kategori": "...", "sayfa": "%s", "neden": "..."}, ...]

            Orijinal test senaryosu: "%s"

            %s
            """.formatted(pageName, pageName, pageName, pageName, goal, visitedPages);

        return callForSuggestions(prompt, 3000);
    }
    private String extractJsonArray(String content) {
        if (content == null) return "[]";
        String cleaned = content.trim();
        cleaned = cleaned.replaceAll("```json", "").replaceAll("```", "").trim();
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }
}
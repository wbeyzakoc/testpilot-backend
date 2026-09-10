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
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class LlmAgent {

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
            Sen bir mobil test otomasyon ajanısın. Görevin, kullanıcının Türkçe olarak verdiği bir hedefi,
            sana verilen ekran görüntüsüne ve numaralandırılmış XML ağacına (accessibility tree) bakarak
            adım adım gerçekleştirmek.

            ==============================================
            ÇIKTI FORMATI
            ==============================================
            SADECE aşağıdaki JSON formatında cevap ver, başka hiçbir açıklama, yorum, markdown ekleme:
            {"reasoning": "", "target": "", "elementId": "", "action": "tap|type|swipe|wait|done|fail", "x": 0, "y": 0, "text": "", "direction": ""}

            Alanları YUKARIDAKİ SIRAYLA doldur (önce reasoning'e karar ver, sonra diğerlerini).
            - reasoning: kararının en fazla 1 kısa cümlelik gerekçesi, tırnak içermesin.
            - target: hedeflediğin elementin kısa insan-okunabilir açıklaması (ör: "Giriş yap butonu") -
              HER ZAMAN elementin ANLAMINI yaz, sayı değil.
            - elementId: XML listesindeki [N] numarası, SADECE rakam (ör: "7"). Gerçekte XML'de var olan
              bir numara olmalı - ASLA tahmin etme/uydurma. wait/done/fail için elementId="".
            - x,y: elementId'den bulunacak gerçek koordinatın kabaca tahmini (yedek/referans amaçlı,
              birkaç piksel yanılman SORUN DEĞİL). wait/done/fail için 0.
            - action=tap: elementId zorunlu. SADECE buton/sekme/menü/kart gibi metin girilmeyen elementler
              için kullan.
            - action=type: elementId VE text zorunlu (önce elementId'nin işaret ettiği alana dokunulur,
              sonra text yazılır). Mail/şifre/arama/isim gibi HERHANGİ bir yazılabilir alanla etkileşimde
              SADECE type kullan, ASLA tap kullanma. text'i hedefe göre belirle: bir test değişkenine
              işaret ediyorsa o değişkenin değerini yaz, değilse hedeften çıkardığın metni yaz.
            - action=swipe: direction zorunlu (down|up|left|right, aşağıda tanımlı).
            - action=wait: ekranın yüklenmesini beklemek için, ekstra alan gerekmez.
            - action=done: hedef POZİTİF kanıtla tamamlandığında.
            - action=fail: gerçekten hiçbir ilerleme kaydedilemiyorsa.

            İLK ADIM: Bu 1. adımsa, uygulama/ekran henüz yükleniyor olabilir - action="wait" döndür,
            tap/type deneme.

            ==============================================
            KARAR SIRASI (sırayla uygula)
            ==============================================
            1. Adım 1 ise → wait.
            2. "Önceki adımların" listesine bak - tam olarak şimdi yapmak üzere olduğun işlem zaten
               yapıldıysa TEKRARLAMA, sıradaki adıma geç (bkz. GEÇMİŞİNE BAK).
            3. Ekranda hedefe ulaşmanı engelleyen bir popup/dialog/bildirim izni/onboarding varsa ÖNCE
               onu geç (bkz. POPUP ÖNCELİĞİ).
            4. Hedefin POZİTİF kanıtla tamamlandığını görüyorsan → done.
            5. Hedefteki elementi CURRENT XML'de ara: label, text, content-desc, resourceId alanlarının
               HEPSİNE bak (typo toleranslı, bkz. EŞLEŞTİRME).
            6. Element bulunduysa: yazılabilir alansa → type; değilse → tap (bkz. TIKLANABİLİR KURALI).
            7. Element XML'de yoksa veya ekranın görünür alanının dışındaysa → swipe (bkz. KAYDIRMA).
            8. Ekran geçiş/animasyon yüzünden kararsızsa → wait (art arda en fazla 1 kez).
            9. En az 2-3 farklı element/kaydırma denemiş ve hâlâ hiçbir ilerleme yoksa → fail.

            ==============================================
            EŞLEŞTİRME VE TIKLANABİLİR ELEMENT KURALI
            ==============================================
            - Öncelik sırası: tam eşleşme > güçlü kısmi eşleşme > anlamca yakın eşleşme > hedefe
              götürecek gezinme elementi.
            - clickable="false" olması elementin kullanılamaz olduğu anlamına GELMEZ - sadece
              clickable="true" olanlara güvenme, content-desc/text'i olan her elementi aday say.
            - Hedef metin, tıklanamayan (clickable="false") bir öğede görünüyorsa: aynı içeriği
              KAPSAYAN, clickable="true" olan EN YAKIN üst/kapsayan elementi seç ve elementId olarak
              ONU kullan (ör: [45] clickable="false" text="Ürün adı" ise ve [43] onu saran
              clickable="true" bir kapsayıcıysa, elementId="43" yaz, "45" değil).
            - Yazım hatası toleransı: hedef metinde küçük bir yazım farkı olabilir (ör. "standart_user"
              ~ "standard_user", "giris" ~ "giriş"/"login"). 1-2 karakterlik farkları göz ardı ederek en
              yakın eşleşmeyi bul.

            ==============================================
            GEÇMİŞİNE BAK, KENDİNİ TEKRAR ETME (KRİTİK)
            ==============================================
            Sana her adımda "Önceki adımlarda yaptıkların" listesi veriliyor. Yeni bir karar vermeden
            ÖNCE bu listeye MUTLAKA bak. Tam olarak şimdi seçmek üzere olduğun elementle (aynı target)
            ilgili bir adım listede zaten varsa, o işlem ZATEN YAPILDI demektir - AYNI ELEMENTE TEKRAR
            TIKLAMA/YAZMA. Bunun yerine:
            1. Az önce seçtiğin değerin artık ekranda dolu bir alan/etiket olarak göründüğünü fark
               edebilirsin - bu SEÇİMİN BAŞARILI olduğunun kanıtıdır, o alana tekrar tıklamak GEREKSİZ.
            2. Hedefe götürecek BAŞKA/SONRAKİ bir elementi dene (bir sonraki form alanı, "Giriş"/
               "Devam"/"Ekle" gibi bir aksiyon butonu).
            3. Hedefte birden fazla adım varsa (örn. "X'i seç VE Y yap"), X tamamlandıysa şimdi Y'ye
               odaklan - X'e bir daha dönme.

            DÖNGÜ (LOOP) TANIMI - SADECE ŞU DURUM LOOP SAYILIR:
            Aynı elementId'ye art arda 3+ kez TIKLADIYSAN (action=tap) VE bu tıklamalar arasında XML
            HİÇ DEĞİŞMEDİYSE → bir daha aynı elementId'ye tıklama. Önce XML'de bir durum değişikliği
            ara; varsa done; yoksa en fazla 1 kez wait dene; hâlâ değişiklik yoksa farklı bir elementId
            (ör. tıklanabilir üst öğe) dene; hiç alternatif yoksa fail.

            LOOP SAYILMAYAN DURUMLAR (bunlar için DURMA, devam et):
            - Bir hedefi ararken art arda yapılan kaydırmalar (swipe) - bu NORMAL ve GEREKLİDİR.
            - Farklı elementId'lere yapılan tıklamalar.
            - Tıklamalar arasındaki wait adımları.
            - "Sepete Ekle" gibi bir butonun tıklama sonrası "Kaldır"/"Sepette" gibi bir metne dönmesi:
              bu tıklamanın BAŞARILI olduğunun kanıtıdır, tekrar tıklama.

            ==============================================
            KAYDIRMA (SWIPE) YÖNÜ
            ==============================================
            direction="down" → EKRANI AŞAĞI kaydırır → listede SONRAKİ/henüz görünmeyen AŞAĞIDAKİ
                                öğeler görünür.
            direction="up"   → EKRANI YUKARI kaydırır → listede ÖNCEKİ/daha önce geçtiğin YUKARIDAKİ
                                öğeler görünür.
            (direction="left"/"right" yatay listeler için aynı mantık: "left" öncekini, "right"
            sonrakini gösterir.)

            Hedef ekranda YOKSA:
            1. İlk tercih HER ZAMAN direction="down" olsun - çoğu liste ekranı en üstten başlar,
               aranan öğe genelde henüz yüklenmemiş/aşağıdaki bölümdedir.
            2. Art arda 3 kez aynı yönde kaydırdın ve XML hâlâ değişmiyorsa (listenin sonuna geldin
               demektir): bir kez TERS yönü (direction="up") dene.
            3. Maksimum 8 arama amaçlı kaydırma. Bu tekrarlar LOOP DEĞİLDİR, DURMA.
            4. Her kaydırmadan sonra YENİ XML'i baştan tara, hedefi tekrar ara.
            5. Kullanıcının hedefinde açıkça "en üstteki"/"en baştaki" gibi bir ifade varsa, önce
               direction="up" dene (listenin başına doğru).

            ==============================================
            POPUP / DIALOG ÖNCELİĞİ
            ==============================================
            Ekranda hedefe ulaşmanı engelleyen bir popup, dialog, bildirim izni, onboarding/tanıtım
            ekranı ya da örtü (overlay) varsa, asıl hedefe yönelik HİÇBİR aksiyon denemeden önce bunu
            kapatmayı/geçmeyi dene. BUNU HER ADIMDA yeniden değerlendir: önceki adımda bir onboarding/
            tanıtım ekranını geçmiş olsan bile, şu anki ekran hâlâ tanıtım/izin/onboarding görünümündeyse
            (büyük illüstrasyon, sayfa noktaları/ilerleme göstergesi, "Skip"/"Continue" gibi butonlar
            hâlâ varsa) hedefe yönelik aksiyona GEÇME, önce bu ekranı da geçmeye devam et.

            Öncelik sırası:
            1. "Skip" / "Atla" - varsa en önce bunu tercih et, en hızlı geçiş yoludur.
            2. "Continue" / "Devam et" / "Next" / "İleri" / "Forward" - ana akış butonu.
            3. "Kapat" / "Close" / "X" işareti / "İptal" / "Tamam" / "Got it" / "Allow"/"Don't Allow".

            DİKKAT: "Learn more", "Daha fazla bilgi", "Hakkında", "Detaylar" gibi bilgilendirme/link
            elementlerine ASLA tıklama - bunlar popup'ı kapatmaz, seni ana akıştan uzaklaştırıp farklı
            bir ekrana/tarayıcıya götürebilir.

            ==============================================
            ÜRÜN / ELEMENT ARAMA (ör. "X ürününü sepete ekle")
            ==============================================
            1. CURRENT XML'de X'in TAM adını ara (label/text/content-desc alanlarının HEPSİNDE), sonra
               ana kelimelerini kısmi eşleşme olarak ara (ör. "sarı sırt çantası" için "sırt çantası"
               veya "sarı").
            2. X (veya X'e ait "Sepete Ekle"/"Ekle" butonu) XML'de VARSA → o EXACT elemente/butona tap.
            3. X XML'de YOKSA → BAŞKA bir ürüne/butona ASLA tıklama, action=swipe ile ara (bkz.
               KAYDIRMA). Yanlış ürüne tıklamak veya yanlış ürünün "Sepete Ekle" butonuna tıklamak
               HATALI kabul edilir.
            4. X bulunana kadar (max 8 kaydırma) aramaya devam et, hâlâ bulunamazsa fail.
            5. Arama kutusu varsa ve X uzun süre bulunamıyorsa, action=type ile arama kutusuna X'i
               yazmayı dene.

            ==============================================
            DONE KURALI
            ==============================================
            Sadece hedefin GERÇEKTEN tamamlandığına dair XML'de/ekranda POZİTİF bir kanıt varsa
            action=done döndür. Örnek kanıtlar:
            - Sepet sayacı/rozeti arttı.
            - "Sepete Ekle" butonu "Kaldır"/"Sepette" gibi bir metne döndü.
            - "Sepete eklendi" gibi bir bildirim/toast göründü.
            - Giriş sonrası ana ekran/ürün listesi/profil görünüyor VE giriş formu artık yok.
            Sadece bir elementi BULMAK tamamlanma sayılmaz. Belirsiz, tahmine dayalı gerekçelerle ASLA
            action=done döndürme - emin değilsen ya farklı bir element dene ya da action=fail döndür.

            ==============================================
            FAIL KURALI
            ==============================================
            action=fail döndürmeden önce: label/text/content-desc/resourceId'yi tekrar kontrol et,
            kısmi eşleşmeleri dene, tıklanabilir üst öğeleri dene, gezinmeyi dene, HER İKİ yönde de
            kaydırmayı dene (max 8, bkz. KAYDIRMA). Sadece şu yüzden fail VERME: element ekranın
            dışında, clickable="false", metinde küçük yazım farkı, ya da bir kaydırma onu ortaya
            çıkarabilir.

            ==============================================
            GEREKSİZ GEZİNME YAPMA
            ==============================================
            Bir sonraki adıma karar vermeden önce, hedefin şu anki ekranda ZATEN mevcut elementlerle
            tamamlanıp tamamlanamayacağını kontrol et. Menüye girmek, sekme değiştirmek, "tümünü gör"
            gibi bir ara ekrana geçmek gibi EK bir gezinme adımına SADECE gerçekten gerekiyorsa başvur -
            hedefte açıkça istenmiyorsa ve mevcut ekranda hedefe uygun bir element zaten varsa, var
            olmayan bir ihtiyaç uydurup gereksiz bir menü/sekme/element aramaya başlama.

            Kullanıcının tanımladığı test değişkenleri (varsa) sana ayrıca verilecek; bir giriş formunda
            mail/şifre gibi bir alan doldurman gerekiyorsa bu değişkenleri kullan.

            ==============================================
            SON KONTROL (cevap vermeden önce)
            ==============================================
            1. Tek, geçerli JSON nesnesi mi? Başka hiçbir metin yok mu?
            2. action tap/type/swipe/wait/done/fail değerlerinden biri mi?
            3. tap/type için elementId, CURRENT XML'de gerçekten var mı (uydurma değil)?
            4. type için text dolu mu?
            5. swipe için direction down/up/left/right değerlerinden biri mi?
            6. wait/done/fail için elementId="" mi?
            7. Aynı elementId'ye art arda 3+ kez, XML değişmeden tıklamayı mı tekrarlıyorsun? Öyleyse
               farklı bir strateji dene (bkz. DÖNGÜ TANIMI).
            """;


    public AgentAction decideNextAction(String goal, Map<String, String> variables, String screenshotBase64, String pageSource, int stepNumber, List<RunStep> previousSteps, String repeatWarning) {
        int maxRetries = 3;
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                AgentAction result = makeLlmRequest(goal, variables, screenshotBase64, pageSource, stepNumber, previousSteps, repeatWarning);
                if (result != null && result.getAction() != null) {
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
    // yazdigi ham/dagimik metni, AYNI anlami ve hedefi koruyarak daha net ve duzgun bir Turkce
    // cumleye ceviriyor. callForSuggestions'tan farkli olarak JSON degil DUZ METIN donuyor --
    // basit bir "yeniden yaz" gorevi icin JSON parse riskine/overhead'ine gerek yok.
    public String improveGoalText(String rawText) {
        if (rawText == null || rawText.isBlank()) return rawText;
        try {
            String prompt = "Asagidaki metin, bir mobil uygulama test senaryosunun hedefini tanimliyor "
                    + "(bir kullanici bunu serbest metin olarak yazdi). AYNI anlami ve AYNI hedefi KORUYARAK, "
                    + "daha net, duzgun ve anlasilir bir Turkce cumleye/cumlelere cevir. Yeni bilgi/adim EKLEME, "
                    + "sadece ifadeyi duzelt. SADECE duzeltilmis metni dondur, baska hicbir aciklama/etiket/tirnak ekleme.\n\n"
                    + "Metin: " + rawText;

            var messages = mapper.createArrayNode();
            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", appSettingsService.getOrCreate().getOpenrouterModel());
            body.set("messages", messages);
            body.put("max_tokens", 300);
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
                return content.trim().replaceAll("^\"|\"$", "");
            }
        } catch (IOException e) {
            throw new RuntimeException("Metin iyileştirilirken hata oluştu", e);
        }
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
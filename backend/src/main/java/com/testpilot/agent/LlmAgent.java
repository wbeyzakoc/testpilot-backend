package com.testpilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.model.AgentAction;
import com.testpilot.model.RunStep;
import com.testpilot.model.ScenarioSuggestion;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class LlmAgent {

    @Value("${openrouter.api-key}")
    private String apiKey;

    @Value("${openrouter.model}")
    private String model;

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            Sen bir mobil test otomasyon ajanısın. Görevin, kullanıcının Türkçe olarak verdiği bir hedefi,
            sana verilen ekran görüntüsüne ve XML ağacına (accessibility tree) bakarak adım adım gerçekleştirmek.

            SADECE aşağıdaki JSON formatında cevap ver, başka hiçbir açıklama, yorum veya metin ekleme:
            {"action": "tap|type|swipe|wait|done|fail", "x": 0, "y": 0, "text": "", "direction": "", "target": "", "reasoning": ""}

            Alanların anlamı:
            - action=tap için x,y zorunlu (XML'deki bounds'tan hesaplanan orta nokta) - SADECE buton, sekme,
              menü gibi metin girilmeyen elementler için kullan.
            - action=type için x,y VE text zorunlu (önce XML'deki bounds'tan hesaplanan x,y konumundaki input
              alanına dokunulur, sonra text yazılır). Bir metin girilecek alanla (mail, şifre, arama kutusu,
              ürün adı vb. HERHANGİ bir yazılabilir alan) etkileşimde SADECE action=type kullan, ASLA action=tap
              kullanma. text'i hedefe göre sen belirle: hedef bir test değişkenine işaret ediyorsa o değişkenin
              değerini yaz, değilse hedeften çıkardığın uygun metni yaz.
            - action=swipe için direction zorunlu (up/down/left/right)
            - action=wait: ekranın yüklenmesini beklemek için, ekstra alan gerekmez
            - action=done: hedef tamamlandığında
            - action=fail: gerçekten hiçbir ilerleme kaydedilemiyorsa
            - target: hangi elementi hedeflediğinin kısa açıklaması
            - reasoning: bu kararı neden verdiğinin kısa gerekçesi

            ÖRNEK:
            XML'de şu satır var: bounds=[650,2205][849,2336] clickable=false label="Hesabım"
            Hedef: "hesabıma git"
            Doğru cevap: {"action": "tap", "x": 749, "y": 2270, "text": "", "direction": "", "target": "Hesabım", "reasoning": "XML'de label=Hesabım olan elementin bounds ortası hesaplandı"}

            KESİN KURAL: Koordinatları SADECE sana verilen XML'deki gerçek bounds değerlerinden hesapla.
            ASLA tahmin etme, uydurma. Eğer XML'de hedefe uygun bir bounds bulamıyorsan, action=fail döndür.
 

            KARAR ALGORİTMASI (sırayla dene):
            ÖNEMLİ - AYNI SEKMEYE TEKRAR GİTME: Bir önceki adımda bir gezinme/menü elementine (sekme, alt
            navigasyon barındaki bir öğe vb.) tıkladıysan ve şu anki ekran hâlâ o bölümün içeriğini
            gösteriyorsa, sen zaten oradasın - aynı gezinme elementine tekrar tıklama. Bunun yerine ekrandaki
            İÇERİKLE (buton, link, form alanı gibi somut bir aksiyon elementi) etkileşime geç.

            1. Hedefle doğrudan eşleşen bir content-desc veya text içeren element var mı? Varsa ona tıkla/yaz.
            2. Yoksa, hedefe ulaştırabilecek bir gezinme elementi var mı? Varsa ona tıkla.
            3. Hiçbiri yoksa VE en az 2-3 farklı elementi denemiş olmalısın, ancak o zaman action=fail döndür.
            clickable="false" olan elementler de tıklanabilir olabilir - sadece clickable="true" olanlara
            güvenme, content-desc/text'i olan her elementi aday say.

            ÖNEMLİ - action=done KURALI: Sadece hedefin GERÇEKTEN tamamlandığına dair XML'de veya ekran
            görüntüsünde POZİTİF bir kanıt varsa action=done döndür. Belirsiz, tahmine dayalı gerekçelerle
            ASLA action=done döndürme. Emin değilsen ya farklı bir element dene ya da action=fail döndür.

            ÖNEMLİ - POPUP/DIALOG ÖNCELİĞİ: Ekranda hedefe ulaşmanı engelleyen bir popup, dialog, bildirim izni,
            onboarding/tanıtım ekranı ya da örtü (overlay) varsa, ÖNCE bunu kapatmayı dene - asıl hedefe yönelik
            başka hiçbir aksiyon denemeden önce bunu yap. Bu tür ekranlarda genellikle "Kapat", "Close", "İptal",
            "Tamam", "Devam et", "Skip", "Continue", "Got it", "Allow"/"Don't Allow", "Forward", "Next", "X"
            işareti gibi elementler bulunur - bunlardan ilerlemeyi sağlayacak en mantıklı olanı seç ve ona tıkla.

            Kullanıcının tanımladığı test değişkenleri (varsa) sana ayrıca verilecek; bir giriş formunda
            mail/şifre gibi bir alan doldurman gerekiyorsa bu değişkenleri kullan.
            """;

    public AgentAction decideNextAction(String goal, Map<String, String> variables, String screenshotBase64, String pageSource, int stepNumber, List<RunStep> previousSteps) {
        try {
            var userContent = mapper.createArrayNode();

            String context = buildVariablesContext(variables);

            StringBuilder historyText = new StringBuilder();
            if (previousSteps != null && !previousSteps.isEmpty()) {
                historyText.append("Önceki adımlarda yaptıkların (en yenisi en altta):\n");
                int start = Math.max(0, previousSteps.size() - 3);
                for (int i = start; i < previousSteps.size(); i++) {
                    RunStep s = previousSteps.get(i);
                    historyText.append("- ").append(s.getStep()).append(". adım: ")
                            .append(s.getAction()).append(" -> ").append(s.getTarget())
                            .append(" (").append(s.getReasoning()).append(")\n");
                }
                historyText.append("\n");
            }

            var textNode = mapper.createObjectNode();
            textNode.put("type", "text");
            textNode.put("text", context + historyText + "Hedef: " + goal + "\nBu " + stepNumber + ". adım. "
                    + "Ekrandaki XML ağacı:\n" + pageSource
                    +"\n\nYukarıdaki XML'e bakarak bir sonraki aksiyonu belirle.");
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
            body.put("model", model);
            body.set("messages", messages);
            body.put("max_tokens", 1024);

            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(body),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(OPENROUTER_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
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
                try {
                    return mapper.readValue(jsonOnly, AgentAction.class);
                } catch (Exception parseEx) {
                    System.out.println("JSON parse edilemedi, modelin ham cevabı:\n" + content);
                    throw new RuntimeException("Model geçerli JSON döndürmedi: " + parseEx.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("LLM aksiyon kararı alınırken hata oluştu", e);
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

    private List<ScenarioSuggestion> callForSuggestions(String prompt, int maxTokens) {
        try {
            var messages = mapper.createArrayNode();
            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            body.put("max_tokens", maxTokens);

            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(body),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(OPENROUTER_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
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
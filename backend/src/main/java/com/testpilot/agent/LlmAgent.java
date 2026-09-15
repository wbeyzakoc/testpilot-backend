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

    private final AppSettingsService appSettingsService;

    public LlmAgent(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

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
        You are a mobile UI test automation agent. You control an Android/iOS app by
        reading its XML accessibility tree and returning ONE action as JSON. Follow the
        rules below EXACTLY, in order, every single step. Keep reasoning short.

        ==================================================
        OUTPUT FORMAT (ALWAYS return ONLY this JSON, nothing else)
        ==================================================
        {"reasoning": "", "target": "", "elementId": "", "action": "tap|type|swipe|wait|done|fail", "x": 0, "y": 0, "text": "", "direction": ""}

        - action=tap: requires elementId (the [N] number from the XML list).
        - action=type: requires elementId + text. ONLY use when the goal explicitly says
          "type", "enter", "write", "fill" or "input". Otherwise use action=tap.
        - action=swipe: requires direction = "down" | "up" | "left" | "right".
        - action=wait: use to let the screen load (max 1 time in a row).
        - action=done: goal is fully finished, with real proof (see DONE below).
        - action=fail: truly stuck after trying everything (see FAIL below).
        - elementId MUST be a real [N] you can see in the CURRENT XML list. NEVER invent one.
        - Step 1 is always action="wait" (let the app finish loading first).

        ==================================================
        EVERY ELEMENT LINE CAN HAVE UP TO 4 PIECES OF TEXT -- READ ALL OF THEM
        ==================================================
        Example line: [7] tappable "Yellow Running Shoes" text="Yellow Running Shoes" desc="item card" [product: Yellow Running Shoes] (ImageView)
        - The quoted part right after [N] is the MAIN label.
        - text="..." and desc="..." are the element's raw text / accessibility description.
          They are only shown when different from the main label -- READ THEM TOO, the
          value you need (a color, size, name, price...) may only be inside one of them.
        - [product: X] is an automatic hint attached to pictures/icons that have no text
          of their own: X is the nearest real text (often the actual product name/color).
          READ every "[product: X]" tag and compare X to the goal, exactly like normal text.
        - When matching against the goal, treat ALL of these (label, text=, desc=,
          [product: X]) as one combined pool of text to search in. Use "contains" style
          matching: the goal's key word (e.g. "yellow") just needs to appear ANYWHERE in
          that combined text, not be an exact full match.
        - IMPORTANT: An element line may be marked "[devre-dışı]" (disabled). Disabled
          elements CANNOT be tapped -- the OS will silently swallow the touch event.
          If the target you want to tap is marked "[devre-dışı]", you MUST first do
          whatever is needed to make it active (fill a required field, toggle a switch,
          accept terms...) instead of trying to tap it.

        ==================================================
        STEP-BY-STEP DECISION PROCESS (do this every single time)
        ==================================================
        1. Look at "Previous steps". Anything you already did, don't do again. Find the
           FIRST part of the goal that is NOT done yet -- that is your only job right now.
        2. Is there a popup / dialog / banner / permission / onboarding on screen blocking
           you? If yes, close it first: prefer "Skip" > "Close/OK/Cancel" > "Continue/Next".
        3. Is the goal now fully complete, with real proof? -> action="done" (see DONE below).
           ⚠️ CRITICAL: "reklam sayfalarını atla" veya "ad pages are skipped" gibi ara hedefler
           TAMAMLANDI diye düşünme! Bu sadece bir ara adım. Goal'da "sonra", "ve sonra", "ardından"
           varsa VEYA goal'da başka yapmanız gereken şeyler varsa (ürün bul, tıkla, sepete ekle vb.)
           action="done" DON'T return! Sadece TÜM goal tamamlandığında "done" döndür.
        4. Otherwise, decide if your current mini-goal is SPECIFIC or GENERIC:
           - SPECIFIC = the goal names an exact attribute: a color, size, brand, model,
             price, or exact name (e.g. "yellow", "iPhone 15", "size XL", "cheapest").
           - GENERIC = no such attribute is given (e.g. "select a product", "tap a button").
        5. Read the ENTIRE numbered XML list, top to bottom, EVERY element, including every
           text=, desc= and "[product: X]" value (see section above). Do not stop at the
           first item.
        6. SPECIFIC mini-goal:
           - Search for an element whose combined text (label + text= + desc= + [product: X])
             CONTAINS the exact attribute word from the goal (e.g. contains "yellow"). Case
             does not matter.
           - Found it? -> action="tap" on that exact element only (if not [devre-dışı]).
           - Not found on this screen? -> action="swipe" (see SCROLL below). Do NOT guess,
             do NOT tap a similar/close item ("blue" is not "yellow"). Never tap without an
             exact attribute match for a SPECIFIC mini-goal.
        7. GENERIC mini-goal:
           - Use the first reasonable, tappable element that fits the goal. No need to
             scroll first, but still check the whole screen quickly.
        8. Screen still loading/animating? -> action="wait" (never twice in a row).
        9. Tried real alternatives and truly stuck? -> action="fail" (see FAIL).

        ==================================================
        SCROLL (SWIPE) RULES
        ==================================================
        - direction="down" reveals the NEXT items in the list (scroll further).
        - direction="up" reveals the PREVIOUS items (scroll back).
        - For a SPECIFIC mini-goal not yet found: swipe "down" first. After up to 6 "down"
          swipes with no match, try "up" a couple of times in case you scrolled past it.
        - Maximum 8 total swipes for a SPECIFIC mini-goal, then action="fail".
        - After EVERY swipe you get a brand new XML list -- you MUST read it completely
          again (step 5 above) before deciding the next action. Never swipe twice in a row
          without checking the new screen for your target first.
        - If 2 swipes in the same direction show no new/changed content, you reached the
          end of the list -- stop swiping that direction.
        - Swiping while searching is normal and is NOT a mistake or a loop.

        ==================================================
        MATCHING RULES (ÇOK ÖNEMLİ - BU KURALLARI ASLA BOZMA)
        ==================================================
        - **EXACT MATCH REQUIRED for SPECIFIC attributes**: If goal says "yellow", "blue", "red", "XL", "iPhone 15", etc.
          you MUST find an element that CONTAINS that exact word in its combined text (label + text= + desc= + [product: X]).
          - "Sauce Labs Backpack (yellow)" → ONLY matches [product: Sauce Labs Backpack (yellow)] ✓
          - "Sauce Labs Backpack (yellow)" → does NOT match [product: Sauce Labs Backpack] ✗ (missing color)
          - "Sauce Labs Backpack (yellow)" → does NOT match [product: Sauce Labs Backpack (blue)] ✗ (wrong color)
          - "Sauce Labs Backpack (yellow)" → does NOT match [product: onesie (yellow)] ✗ (wrong product name)
        
        - **PARTIAL MATCH FORBIDDEN**: Every word in the goal's product name MUST match exactly.
          Parentheses, colors, model numbers - EVERYTHING must match.
        
        - **WHEN TO SCROLL vs WHEN TO TAP**:
          A) Goal = "Sauce Labs Backpack (yellow) ürününü bul/tıkla/seç" → EXACT name match required
             - If you see [product: Sauce Labs Backpack] WITHOUT "(yellow)" → KEEP SCROLLING, do NOT tap!
             - If you see [product: Sauce Labs Backpack (blue)] → WRONG COLOR, KEEP SCROLLING!
             - ONLY tap when you see [product: Sauce Labs Backpack (yellow)] EXACTLY!
          
          B) Goal = "içerisinde yellow yazan ürünü seç" → ANY product containing "yellow" is OK
             - [product: onesie (yellow)] → OK ✓
             - [product: Sauce Labs Backpack (yellow)] → OK ✓
             - [product: yellow t-shirt] → OK ✓
          
          C) How to distinguish A vs B:
             - If goal contains "içerisinde", "içinde", "yazan", "olan", "içeren" → Scenario B (partial OK)
             - Otherwise → Scenario A (EXACT match required)
        
        - clickable="false" does not mean unusable -- if the readable text/image sits
          inside a bigger tappable area, tap the [N] of that readable element anyway; the
          system will find the right tappable parent automatically.
        - Small typos are fine (1-2 letters difference, e.g. "standart_user" ~
          "standard_user").
        - Always read label, text=, desc= AND "[product: X]" -- see the section above.
          The value you are looking for can be in any one of them.
        - If an element is marked "[devre-dışı]" (disabled), the tap will NOT work.
          NEVER try to tap it. Instead, do the REQUIRED prior step to enable it.

        ==================================================
        SUCCESS SIGNALS (an action already worked -- do not repeat it)
        ==================================================
        - A button's text changed after you tapped it (e.g. "Add to Cart" -> "Remove",
          "Added") = success, move to the next mini-goal, never tap it again.
        - A cart/badge counter increased = success.
        - The screen changed to a new screen (e.g. login form disappeared) = success.

        ==================================================
        DONE (testi bitir)
        ==================================================
        Return action="done" when ANY of these conditions is met:

        1. PRODUCT SELECTION SCENARIO (en yaygın senaryo):
           - Goal says "find and click/select [product name]" OR "bul ve tıkla/seç"
           - You successfully TAPPED the exact target product (with matching name/color/attribute)
           - After the tap, the screen CHANGED (new XML, new screen, product detail page opened)
           → action="done" immediately. DO NOT tap the same product again!

        2. GENERAL COMPLETION:
           - EVERY part of the goal is complete with real evidence (from success signals above,
             or from "Previous steps").
           - If unsure, keep working instead of guessing "done".
           - ⚠️ CRITICAL: "reklam sayfalarını atla", "ad pages to skip", "tour atla" gibi
             ARA HEDEFLER tamamlanması, TÜM goal'ın bittiği anlamına gelmez!
             Goal'da "sonra", "ve sonra", "ardından" varsa VEYA başka yapmanız gereken
             şeyler varsa (ürün bul, tıkla, sepete ekle, form doldur vb.) action="done" DON'T!
             Sadece TÜM senaryo tamamlandığında "done" döndür.

        3. PROOF REQUIRED:
           - Screen change AFTER your tap = proof the action worked → done
           - Button text changed (e.g., "Add to Cart" → "Remove") = proof → move to next step or done
           - Navigation to a new screen = proof → done if goal is satisfied
           - ⚠️ "reklam sayfalarını atla" için proof: Ana banking app ekranına ulaştın,
             reklam/tour overlay yok. AMA goal'da "sonra X ürünü bul" varsa henüz BİTMEDİ!

        EXAMPLE: Goal = "uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bulana kadar kaydır. bulunca tıkla ve testi bitir."
        - Step N: swipe down (scrolling to find product)
        - Step N+1: found [product: Sauce Labs Backpack (yellow)] → action="tap"
        - Step N+2: XML changed (new screen/product detail page) → action="done" (TEST COMPLETE!)
        - WRONG: Tapping the same product again on the new screen → this is a loop!

        EXAMPLE MULTI-STEP: Goal = "reklam sayfalarını atla. sonra X ürününü bul ve tıkla."
        - Step N: Skip ad pages (found "DEVAM" buttons, tapped them)
        - Step N+1: Main app screen visible → ads skipped ✓
        - Step N+2: ⚠️ DO NOT return "done"! Goal'da "sonra X ürününü bul ve tıkla" VAR!
        - Step N+2: action="swipe" or action="tap" to find X product
        - ONLY after X product is tapped → action="done"

        ==================================================
        MULTI-STEP SCENARIOS (CRITICAL - READ CAREFULLY)
        ==================================================
        If goal contains "sonra" or "ve sonra" or "ardından" → this is a MULTI-STEP scenario!
        Example: "uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bul ve tıkla. sonra products ekranına geri dön. Sauce Labs Backpack (orange) ürününü bul ve tıkla. sonra testi bitir."
        
        MULTI-STEP EXECUTION RULES:
        1. ✅ Find and tap FIRST product (yellow) → screen changes → this is NOT the end!
        2. ✅ After first product tapped, look for "geri dön" or "back" button → tap it
        3. ✅ Return to products screen → find SECOND product (orange) → tap it
        4. ✅ ONLY after LAST product is tapped → action="done"
        
        ⚠️ CRITICAL: After tapping a product in multi-step scenario:
        - If XML changed (new screen) → DO NOT tap the same product again!
        - Check if there are more products to find (look for "sonra" in goal)
        - If more steps exist → find "back" button or "products" navigation
        - If this was the LAST product → action="done"
        
        EXAMPLE MULTI-STEP FLOW:
        Goal: "yellow ürünü tıkla. sonra geri dön. orange ürünü bul ve tıkla. sonra testi bitir."
        - Step 1: swipe → find yellow → action="tap"
        - Step 2: XML changed (product detail) → look for "Back" or "←" or "Products" → action="tap"
        - Step 3: XML changed (products list) → swipe → find orange → action="tap"
        - Step 4: XML changed (product detail) → action="done" (LAST product clicked!)
        
        ⚠️ WRONG: After tapping yellow, tapping yellow again → this is a loop!
        ⚠️ WRONG: After tapping yellow, immediately action="done" → incomplete!
        ✓ CORRECT: After tapping yellow, find back button → navigate back → find orange

        ==================================================
        FAIL (takıldığında)
        ==================================================
        Before giving up, make sure you actually: read the full XML at least once, tried
        the max allowed swipes for a SPECIFIC mini-goal (or checked the screen for a
        GENERIC one), and checked for blocking popups. Only return action="fail" when
        truly stuck with no options left (e.g. max swipes reached and target never found).

        **CRITICAL: WHEN TO FAIL for PRODUCT SELECTION**:
        - Goal = "Sauce Labs Backpack (violet) bul/tıkla"
        - You scrolled max 8 times (or scanned entire screen)
        - You NEVER found [product: Sauce Labs Backpack (violet)]
        → action="fail" IMMEDIATELY. DO NOT tap other products! DO NOT tap random buttons!
        
        **FORBIDDEN ACTIONS when target not found**:
        - ✗ DO NOT tap a DIFFERENT product (e.g., "Sauce Labs Backpack (green)")
        - ✗ DO NOT tap a PARTIAL match (e.g., "Sauce Labs Backpack" without color)
        - ✗ DO NOT tap random buttons like "Add to cart", "Continue", "Skip"
        - ✗ DO NOT navigate to other screens
        - ✓ ONLY action = "fail" when target truly doesn't exist after max swipes

        ==================================================
        HARD RULES (never break these)
        ==================================================
        - NEVER type text unless the goal explicitly asks for it (type/enter/write/fill).
        - NEVER navigate to menus/screens the goal did not ask for.
        - NEVER tap the exact same element 2+ times in a row if nothing changed -- try a
          different element or action instead.
        - NEVER invent an elementId that is not in the current XML list.
        - **CRITICAL: EXACT PRODUCT NAME MATCHING** -- If goal says "Sauce Labs Backpack (yellow)",
          you MUST find element with [product: Sauce Labs Backpack (yellow)] EXACTLY.
          - [product: Sauce Labs Backpack] is WRONG (missing color) ✗
          - [product: Sauce Labs Backpack (blue)] is WRONG (wrong color) ✗
          - [product: onesie (yellow)] is WRONG (wrong product name) ✗
          - ONLY [product: Sauce Labs Backpack (yellow)] is CORRECT ✓
        - **CRITICAL: PARTIAL MATCH FORBIDDEN** -- Goal "Sauce Labs Backpack (yellow)" does NOT
          match "Sauce Labs Backpack". Every word including parentheses and color MUST match.
        - **CRITICAL: TWO SCENARIOS** -- Distinguish between:
          A) EXACT NAME: "Sauce Labs Backpack (yellow) ürününü bul" → ONLY exact match
          B) KEYWORD: "içerisinde yellow yazan ürünü seç" → any product containing "yellow"
          - If goal contains "içerisinde", "içinde", "yazan", "olan", "içeren" → Scenario B
          - Otherwise → Scenario A (exact name required)
        - **CRITICAL: SCROLL & SCAN CHECKLIST** -- After EVERY swipe, read ALL elements:
          1. List all visible elements with their [N] index numbers
          2. Read every [product: X] annotation completely
          3. Compare goal's EXACT product name (including parentheses) with each [product: X]
          4. If no match, continue to next element -- never skip!
          5. If still no match after full screen scan → swipe again (max 3 swipes)
          6. When found → verify [product: X] matches goal EXACTLY before tapping
        - **CRITICAL: 3-STEP TAP VALIDATION** -- Before saying "Tıkla":
          1. Re-read goal's exact product name (e.g., "Sauce Labs Backpack (yellow)")
          2. Re-read target element's [product: X] annotation
          3. Are they IDENTICAL? (every word, parentheses, color) → YES = tap, NO = continue
        - **CRITICAL: DISABLED ELEMENTS** -- An element marked "[devre-dışı]" CANNOT be tapped.
          If the goal asks to tap it, you MUST first do whatever enables it (fill form,
          accept terms, toggle switch). Only ever return action="tap" on an ENABLED element.
        - Only ever return the JSON object, nothing before or after it.
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
                        Thread.sleep(1000 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("Model " + maxRetries + " denemede geçerli JSON döndürmedi: " +
                (lastException != null ? lastException.getMessage() : "Bilinmeyen hata"), lastException);
    }

    // ============================================================================================
    // [YENİ 2026-09-15] EKRAN ANALİZİ ve AKIŞ PLANLAMA
    //
    // SENARYO EKSİK veya BELİRSİZ olduğunda ya da bir adımda takıldığımızda çağrılır.
    // LLM'e "bu ekranı DETAYLI analiz et, uygulamanın yapısını anla, hedefe ulaşmak
    // için EN MANTIKLI sonraki adımı öner" der. Aşağıdaki durumlarda kullanılır:
    //   (1) Adım niyeti sınıflandırılamadı (UNKNOWN intent),
    //   (2) Deterministik tap tüm scroll denemelerine rağmen hedefi bulamadı,
    //   (3) LLM "fail" kararı verdi (vazgeçmeden ÖNCE son bir şans).
    // ============================================================================================
    public record ScreenAnalysisResult(
            String screenName,
            String screenPurpose,
            List<String> availableActions,
            String confidence,
            AgentAction suggestedAction
    ) {}

    public ScreenAnalysisResult analyzeScreenAndPlanNextAction(
            String goal,
            String currentStep,
            String pageSource,
            List<RunStep> previousSteps) {

        String prompt = buildScreenAnalysisPrompt(goal, currentStep, pageSource, previousSteps);

        try {
            var messages = mapper.createArrayNode();
            var userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            var body = mapper.createObjectNode();
            body.put("model", appSettingsService.getOrCreate().getOpenrouterModel());
            body.set("messages", messages);
            body.put("max_tokens", 900);
            body.put("temperature", 0.2);

            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(body),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(resolveApiUrl())
                    .addHeader("Authorization", "Bearer " + appSettingsService.getOpenrouterApiKeyDecrypted())
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String err = response.body() != null ? response.body().string() : "(boş)";
                    throw new RuntimeException("Ekran analizi isteği başarısız: "
                            + response.code() + " -> " + err);
                }
                String responseBody = response.body().string();
                JsonNode root = mapper.readTree(responseBody);
                String content = root.at("/choices/0/message/content").asText();

                String jsonOnly = extractJson(content);

                JsonNode parsed;
                try {
                    parsed = mapper.readTree(jsonOnly);
                } catch (Exception parseEx) {
                    System.out.println("[ANALYZE] JSON parse edilemedi, ham cevap:\n" + content);
                    String cleaned = cleanJsonResponse(jsonOnly);
                    parsed = mapper.readTree(cleaned);
                }

                String screenName = parsed.path("screenName").asText("");
                String screenPurpose = parsed.path("screenPurpose").asText("");
                String confidence = parsed.path("confidence").asText("medium");

                List<String> actions = new java.util.ArrayList<>();
                JsonNode actionsNode = parsed.path("availableActions");
                if (actionsNode.isArray()) {
                    for (JsonNode a : actionsNode) {
                        String s = a.asText("");
                        if (!s.isBlank()) actions.add(s);
                    }
                }

                JsonNode suggestedNode = parsed.path("suggestedAction");
                AgentAction suggested = new AgentAction();
                suggested.setAction(suggestedNode.path("action").asText("wait").toLowerCase(Locale.ROOT));
                suggested.setTarget(suggestedNode.path("target").asText(""));
                suggested.setElementId(suggestedNode.path("elementId").asText(""));
                suggested.setX(suggestedNode.path("x").asInt(0));
                suggested.setY(suggestedNode.path("y").asInt(0));
                suggested.setText(suggestedNode.path("text").asText(""));
                suggested.setDirection(suggestedNode.path("direction").asText(""));
                suggested.setReasoning(suggestedNode.path("reasoning").asText(""));

                return new ScreenAnalysisResult(screenName, screenPurpose, actions, confidence, suggested);
            }
        } catch (IOException e) {
            throw new RuntimeException("Ekran analizi sırasında IO hatası: " + e.getMessage(), e);
        }
    }

    private String buildScreenAnalysisPrompt(String goal, String currentStep,
                                             String pageSource, List<RunStep> previousSteps) {
        StringBuilder history = new StringBuilder();
        if (previousSteps != null && !previousSteps.isEmpty()) {
            history.append("Önceki adımlar (kronolojik, en yenisi en altta):\n");
            int start = Math.max(0, previousSteps.size() - 12);
            for (int i = start; i < previousSteps.size(); i++) {
                RunStep s = previousSteps.get(i);
                boolean failed = "failed".equalsIgnoreCase(s.getAction());
                history.append("- ").append(failed ? "❌" : "✅").append(" ")
                        .append(s.getStep()).append(". adım: ")
                        .append(s.getAction() != null ? s.getAction() : "?").append(" → ")
                        .append(s.getTarget() != null && !s.getTarget().isBlank() ? s.getTarget() : "(hedef yok)")
                        .append("\n");
            }
            history.append("\n");
        }

        return """
            Sen kıdemli bir mobil QA otomasyon danışmanısın. Kullanıcı bir test senaryosu
            yazdı ama senaryo EKSİK veya BELİRSİZ olabilir. Görevin: şu anki EKRANI
            DETAYLI analiz etmek, uygulamanın yapısını anlamak ve hedefe ulaşmak için
            EN MANTIKLI sonraki TEK aksiyonu önermek.

            ═══════════════════════════════════════════════════════════════
            BAĞLAM
            ═══════════════════════════════════════════════════════════════
            Kullanıcının hedefi (kısaltılmış veya eksik olabilir):
            "%s"

            Şu an üzerinde çalıştığımız adım:
            "%s"

            %s

            ═══════════════════════════════════════════════════════════════
            GÖREVİN (üç aşamalı)
            ═══════════════════════════════════════════════════════════════
            1. EKRANI TANIMLA
               - Bu hangi ekran? (ör. "Giriş", "Ürün Listesi", "Sepet", "Onay")
               - Amacı ne? (ör. "kullanıcı adı ve şifre ile oturum açma")

            2. MEVCUT AKSİYONLARI LİSTELE
               - Bu ekranda kullanıcı ne yapabilir? (form doldur, butona bas, kaydır, sekme değiştir)
               - Sadece gerçekten GÖRÜNEN ve ETKİLEŞİLEBİLİR elementleri say

            3. HEDEFE GÖRE SIRADAKİ AKSİYONU BELİRLE
               - Genel hedefe ulaşmak için BURADA şu an yapılması gereken TEK aksiyon ne?
               - Eksik adımı otomatik tamamla: örn. hedef sadece "giriş yap" diyorsa ve
                 şu an Giriş ekranındaysan, ilk mantıklı adım kullanıcı adı alanına dokunmaktır.

            ═══════════════════════════════════════════════════════════════
            KURALLAR
            ═══════════════════════════════════════════════════════════════
            - EKRANDA MODAL/POPUP/DIALOG varsa ÖNCE onu kapat veya yanıtla
              (ör. izin diyaloğu → "İzin ver", bilgilendirme → "Tamam")
            - FORM varsa DOĞAL SIRAYLA ilerle: alanı doldur → sonraki alan → buton
            - ANA EYLEM BUTONU (genelde ekranın en altındaki birincil buton) varsa ve
              hedef o ekranı geçmekse → o butona öncelik ver
            - Hedefe ZATEN ULAŞILDIYSA ve ekran hedefi tamamladıysa → action="done"
            - Mevcut ekran hedefle ALAKASIZ görünüyorsa → action="swipe" (kaydır) veya
              action="wait" (yükleniyor olabilir) öner
            - ASLA ilerleme sağlamayan aksiyon önerme (rastgele butona basmak, aynı
              elemente tekrar tekrar tap etmek gibi)
            - elementId: XML listesinde GERÇEKTEN VAR OLAN bir [N] numarası olmalı.
              Uydurma ID döndürme.
            - Emin değilsen confidence="low", eminsen "high"
            - Yanıt SADECE JSON olsun, öncesinde/sonrasında hiçbir metin olmasın

            ═══════════════════════════════════════════════════════════════
            ÇIKTI FORMATI (örnek)
            ═══════════════════════════════════════════════════════════════
            {
              "screenName": "Giriş Ekranı",
              "screenPurpose": "Kullanıcı adı ve şifre ile oturum açma",
              "availableActions": [
                "Kullanıcı adı alanına dokun",
                "Şifre alanına dokun",
                "Giriş butonuna bas",
                "Şifremi unuttum linkine bas"
              ],
              "confidence": "high",
              "suggestedAction": {
                "action": "tap",
                "target": "Kullanıcı Adı Alanı",
                "elementId": "3",
                "x": 0,
                "y": 0,
                "text": "",
                "direction": "",
                "reasoning": "Hedef oturum açmak; formun ilk adımı kullanıcı adı alanına dokunmak"
              }
            }

            ═══════════════════════════════════════════════════════════════
            EKRAN XML'İ (şu an ekranda görünen her şey)
            ═══════════════════════════════════════════════════════════════
            %s
            """.formatted(goal, currentStep, history.toString(), pageSource);
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

    // [YENİ 2026-09-14] MULTI-STEP SENARYO DURUM ANALİZİ
    private String analyzeMultiStepProgress(String goal, List<RunStep> previousSteps) {
        if (goal == null || goal.isBlank()) return null;

        String lowerGoal = goal.toLowerCase();
        boolean isMultiStep = lowerGoal.contains("sonra") || lowerGoal.contains("ve sonra") || lowerGoal.contains("ardından");

        if (!isMultiStep) return null;

        String[] steps = splitMultiStepGoal(goal);

        StringBuilder analysis = new StringBuilder();
        int completedCount = 0;

        for (int i = 0; i < steps.length; i++) {
            String step = steps[i].trim();
            boolean isCompleted = isStepCompleted(step, previousSteps);

            if (isCompleted) {
                analysis.append("✅ ").append(i + 1).append(". adım TAMAMLANDI: ").append(step).append("\n");
                completedCount++;
            } else {
                analysis.append("⏳ ").append(i + 1).append(". adım BEKLİYOR: ").append(step).append("\n");
            }
        }

        analysis.append("\n📊 İLERLEME: ").append(completedCount).append("/").append(steps.length).append(" adım tamamlandı\n");

        if (completedCount < steps.length) {
            analysis.append("🎯 ŞU AN YAPILMASI GEREKEN: ").append(steps[completedCount].trim()).append("\n");

            String nextStep = steps[completedCount].toLowerCase();
            if (nextStep.contains("geri dön") || nextStep.contains("back") || nextStep.contains("menü")) {
                analysis.append("💡 İPUCU: 'Geri' veya 'Back' veya '←' butonunu ara. Aynı ürüne tekrar tıklama!\n");
            } else if (nextStep.contains("bul") || nextStep.contains("seç") || nextStep.contains("tap")) {
                String nextProduct = extractProductName(nextStep);
                if (nextProduct != null) {
                    analysis.append("💡 İPUCU: '").append(nextProduct).append("' ürününü XML'de ara. [product: ").append(nextProduct).append("] etiketini ara!\n");
                }
            }
        }

        return analysis.toString();
    }

    private String[] splitMultiStepGoal(String goal) {
        return goal.toLowerCase()
                .replaceAll("sonra\\s+", "")
                .replaceAll("ve sonra\\s+", "")
                .replaceAll("ardından\\s+", "")
                .split("\\.\\s*(?=uygulamayı|bul|tıkla|tap|seç|geri dön|bitir|tamamla)");
    }

    private boolean isStepCompleted(String step, List<RunStep> previousSteps) {
        if (previousSteps == null || previousSteps.isEmpty()) return false;

        String lowerStep = step.toLowerCase();

        for (RunStep runStep : previousSteps) {
            if ("failed".equalsIgnoreCase(runStep.getAction())) {
                continue;
            }
            String target = runStep.getTarget() != null ? runStep.getTarget().toLowerCase() : "";
            String action = runStep.getAction() != null ? runStep.getAction().toLowerCase() : "";

            if (lowerStep.contains("tıkla") || lowerStep.contains("tap")) {
                String productName = extractProductName(lowerStep);
                if (productName != null && target.contains(productName)) {
                    return true;
                }
            }

            if (lowerStep.contains("geri dön") || lowerStep.contains("back")) {
                if (target.contains("geri") || target.contains("back") || target.contains("menu")) {
                    return true;
                }
            }
        }

        return false;
    }

    private String extractProductName(String text) {
        if (text == null) return null;

        int productStart = text.indexOf("[product:");
        if (productStart >= 0) {
            int start = productStart + 9;
            int end = text.indexOf("]", start);
            if (end > start) {
                return text.substring(start, end);
            }
        }

        int lastParen = text.lastIndexOf("(");
        if (lastParen > 0) {
            String beforeParen = text.substring(0, lastParen);
            String[] words = beforeParen.trim().split("\\s+");
            if (words.length > 0) {
                int startIdx = Math.max(0, words.length - 3);
                StringBuilder productName = new StringBuilder();
                for (int i = startIdx; i < words.length; i++) {
                    if (productName.length() > 0) productName.append(" ");
                    productName.append(words[i]);
                }
                if (productName.length() > 0) {
                    return productName.toString();
                }
            }
        }

        return null;
    }

    private AgentAction makeLlmRequest(String goal, Map<String, String> variables, String screenshotBase64, String pageSource, int stepNumber, List<RunStep> previousSteps, String repeatWarning) throws IOException {
        var userContent = mapper.createArrayNode();

        String context = buildVariablesContext(variables);

        StringBuilder historyText = new StringBuilder();
        if (previousSteps != null && !previousSteps.isEmpty()) {
            historyText.append("Önceki adımlarda yaptıkların (en yenisi en altta):\n");
            int start = Math.max(0, previousSteps.size() - 10);
            for (int i = start; i < previousSteps.size(); i++) {
                RunStep s = previousSteps.get(i);
                boolean failed = "failed".equalsIgnoreCase(s.getAction());
                historyText.append("- ").append(failed ? "❌ BAŞARISIZ" : "✅ BAŞARILI")
                        .append(" ").append(s.getStep()).append(". adım: ")
                        .append(s.getAction()).append(" -> ").append(s.getTarget())
                        .append(" (").append(s.getReasoning()).append(")\n");
            }
            historyText.append("\n");
            historyText.append("Yalnızca ✅ BAŞARILI kayıtları tamamlanmış kabul et; ❌ BAŞARISIZ kayıtları tekrar etme, ")
                    .append("bunlar sistemin reddettiği veya gerçekleşmeyen denemelerdir.\n\n");

            String multiStepAnalysis = analyzeMultiStepProgress(goal, previousSteps);
            if (multiStepAnalysis != null && !multiStepAnalysis.isBlank()) {
                historyText.append("🔍 MULTI-STEP DURUM ANALİZİ:\n").append(multiStepAnalysis).append("\n\n");
            }
        }

        String repeatWarningBlock = (repeatWarning != null && !repeatWarning.isBlank())
                ? ("!!! " + repeatWarning + " !!!\n\n")
                : "";

        var textNode = mapper.createObjectNode();
        textNode.put("type", "text");
        String baseText = repeatWarningBlock + context + historyText + "Hedef: " + goal + "\nBu " + stepNumber + ". adım. "
                + "Ekrandaki XML ağacı:\n" + pageSource;

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

    private String cleanJsonResponse(String json) {
        if (json == null || json.isBlank()) return json;

        try {
            mapper.readTree(json);
            return json;
        } catch (Exception e) {
        }

        String cleaned = json;

        if (cleaned.contains("\"reasoning\":")) {
            int reasoningStart = cleaned.indexOf("\"reasoning\":");
            int reasoningValueStart = cleaned.indexOf("\"", reasoningStart + 12);
            if (reasoningValueStart >= 0) {
                reasoningValueStart++;
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

    public String improveGoalText(String rawText) {
        if (rawText == null || rawText.isBlank()) return rawText;
        try {
            String prompt = "Asagidaki metin bir mobil uygulama test senaryosunun hedefini tanimliyor "
                    + "(bir kullanici bunu serbest metin olarak yazdi). Bu metni NUMARASIZ, ATOMIK "
                    + "adimlar halinde yeniden yaz. Kurallar:\n"
                    + "1) Metindeki her ayri eylemi (nokta/virgul/\"ve\"/\"sonra\" ile ayrilan kisimlari) "
                    + "AYRI bir satir yap. Satirlarin basina numara veya madde imi koyma. \"Sonra\" gibi "
                    + "baglaclari satir basinda tekrarlama.\n"
                    + "2) Her adim KISA olmali ve SADECE TEK bir eylem icermeli. Bir cumlede birden fazla "
                    + "eylem varsa mutlaka bol. Genel kural olarak \"[X] menusunden/menuden [Y] secenegine "
                    + "tikla\" ifadesini \"[X] menu butonuna tikla\" ve \"[Y] secenegine tikla\" olarak "
                    + "iki ayri adima ayir; X ve Y yerine metindeki ifadeleri aynen kullan. "
                    + "\"bulana kadar kaydir\" veya \"gorunene kadar kaydir\" tek bir kaydirma adimidir; "
                    + "ardindan gelen \"bulunca tikla\" veya \"bul ve tikla\" ifadesini, bulunan hedefe "
                    + "tiklama adimi olarak ayir. Ayni kural urun, buton, kart, link, sekme ve ekran "
                    + "adlari icin de gecerlidir.\n"
                    + "3) AYNI anlami ve AYNI hedefi KORU; kullanicinin tek bir eylem olarak tarif ettigi "
                    + "eylemi ikiye bolme, ancak ayni cumlede acikca bulunan iki farkli tiklama/navigasyon "
                    + "eylemini ayir. Yeni bir hedef, urun, buton veya ekran UYDURMA ve adim ATLAMA.\n"
                    + "4) Kullanicinin metninde gecen somut degerleri (urun adi, kullanici adi, sifre, "
                    + "sayi, degisken vb.) OLDUGU GIBI, harf/yazim degistirmeden AYNEN koru.\n"
                    + "5) Kullanicinin metninde ACIKCA belirtilmeyen hicbir buton/alan/ekran/element "
                    + "ismi UYDURMA; kullanici hangi ifadeyi kullandiysa adimlarda da ayni/benzer genel "
                    + "ifadeyi kullan.\n"
                    + "6) Her adimi ayri satira yaz ve satirlar arasinda mutlaka yeni satir karakteri kullan; "
                    + "iki ayri adimi ayni satirda birlestirme. SADECE adimlari alt alta dondur, baska hicbir aciklama, baslik, etiket "
                    + "veya tirnak ekleme.\n\n"
                    + "Genel donusum ornegi:\n"
                    + "Girdi: uygulamayı aç. Sauce Labs Backpack (yellow) ürünü bulana kadar kaydır. "
                    + "bulunca tıkla. sonra sol menuden catalog seçeneğine tıkla. "
                    + "Sauce Labs Backpack (orange) ürününü bul ve tıkla. sonra testi bitir.\n"
                    + "Cikti:\n"
                    + "Uygulamayı aç\n"
                    + "Sauce Labs Backpack (yellow) ürünü görünene kadar ekranı kaydır\n"
                    + "Sauce Labs Backpack (yellow) ürününe tıkla\n"
                    + "Sol menü butonuna tıkla\n"
                    + "Catalog seçeneğine tıkla\n"
                    + "Sauce Labs Backpack (orange) ürünü görünene kadar ekranı kaydır\n"
                    + "Sauce Labs Backpack (orange) ürününe tıkla\n"
                    + "Testi bitir\n\n"
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
                return formatImprovedSteps(removeStepNumbering(content.trim().replaceAll("^\"|\"$", "")));
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

    private String formatImprovedSteps(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> line.replaceFirst("[.!?]+$", "") + ".")
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
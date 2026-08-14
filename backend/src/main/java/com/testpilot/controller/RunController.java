package com.testpilot.controller;

import com.testpilot.agent.LlmAgent;
import com.testpilot.agent.RunStore;
import com.testpilot.appium.AppiumDriverManager;
import com.testpilot.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/runs")
@CrossOrigin(origins = "*")
public class RunController {

    private static final int MAX_STEPS = 15;
    private static final List<String> INFO_LINK_KEYWORDS = List.of(
            "learn more", "daha fazla bilgi", "hakkında", "detaylar", "more info", "öğren", "about"
    );

    private boolean isInformationalLink(String target) {
        if (target == null) return false;
        String t = target.toLowerCase();
        return INFO_LINK_KEYWORDS.stream().anyMatch(t::contains);
    }

    private final AppiumDriverManager appiumDriverManager;
    private final LlmAgent llmAgent;
    private final RunStore runStore;

    public RunController(AppiumDriverManager appiumDriverManager, LlmAgent llmAgent, RunStore runStore) {
        this.appiumDriverManager = appiumDriverManager;
        this.llmAgent = llmAgent;
        this.runStore = runStore;
    }
    @DeleteMapping("/{id}")
    public void deleteRun(@PathVariable String id) {
        runStore.delete(id);
    }
    @PostMapping
    public Run createRun(@RequestBody TestRequest request) {
        Run run = new Run();
        run.setId(UUID.randomUUID().toString());
        run.setGoal(request.getGoal());
        run.setAppPackage(request.getAppPackage());
        run.setAppActivity(request.getAppActivity());
        run.setStatus("running");
        run.setStartedAt(Instant.now().toString());
        runStore.save(run);

        new Thread(() -> executeRun(run, request.getVariables(), request.getAppPackage(), request.getAppActivity())).start();

        return run;
    }
    @PostMapping("/{id}/suggestions/page")
    public List<ScenarioSuggestion> suggestScenariosForPage(
            @PathVariable String id,
            @RequestParam String sayfa) {
        Run run = runStore.get(id);
        if (run == null) {
            throw new RuntimeException("Test bulunamadı: " + id);
        }
        List<ScenarioSuggestion> newOnes = llmAgent.suggestScenariosForPage(run.getGoal(), run.getSteps(), sayfa);
        List<ScenarioSuggestion> combined = new java.util.ArrayList<>();
        if (run.getSuggestions() != null) {
            combined.addAll(run.getSuggestions());
        }
        combined.addAll(newOnes);
        run.setSuggestions(combined);
        runStore.save(run);
        return combined;
    }
    @GetMapping
    public List<Run> listRuns() {
        return runStore.getAll();
    }

    @GetMapping("/{id}")
    public Run getRun(@PathVariable String id) {
        Run run = runStore.get(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run bulunamadı");
        return run;
    }
    @GetMapping("/{id}/suggestions")
    public List<ScenarioSuggestion> suggestScenarios(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean refresh) {
        Run run = runStore.get(id);
        if (run == null) {
            throw new RuntimeException("Test bulunamadı: " + id);
        }
        if (!refresh && run.getSuggestions() != null && !run.getSuggestions().isEmpty()) {
            return run.getSuggestions();
        }
        List<ScenarioSuggestion> result = llmAgent.suggestScenarios(run.getGoal(), run.getSteps());
        run.setSuggestions(result);
        runStore.save(run);
        return result;
    }

    @PostMapping("/{id}/stop")
    public void stopRun(@PathVariable String id) {
        Run run = runStore.get(id);
        if (run != null) run.setStopRequested(true);
    }

    private void executeRun(Run run, Map<String, String> variables, String appPackage, String appActivity) {
        int consecutiveFails = 0;
        try {
            appiumDriverManager.startSession(appPackage, appActivity);
            appiumDriverManager.resetToFreshState(appPackage);
            String lastActionSignature = null;
            int repeatCount = 0;
            for (int i = 1; i <= MAX_STEPS; i++) {
                if (run.isStopRequested()) {
                    run.setStatus("stopped");
                    run.setFinishedAt(Instant.now().toString());
                    runStore.save(run);
                    return;
                }

                String screenshot = appiumDriverManager.takeScreenshotBase64();
                String rawPageSource = appiumDriverManager.getPageSource();
                String filteredPageSource = appiumDriverManager.filterPageSource(rawPageSource);
                System.out.println("=== FİLTRELENMİŞ XML (adım " + i + ") ===\n" + filteredPageSource);

                AgentAction action;
                try {
                    action = llmAgent.decideNextAction(run.getGoal(), variables, screenshot, filteredPageSource, i, run.getSteps());
                } catch (Exception decideEx) {
                    run.getSteps().add(new RunStep(i, "failed", null, "Model kararı alınamadı, tekrar deneniyor: " + decideEx.getMessage()));
                    runStore.save(run);
                    Thread.sleep(800);
                    continue;
                }

                if (isInformationalLink(action.getTarget())) {
                    run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                            "Bilgilendirme/link elementine tıklanması engellendi (ana akıştan uzaklaştırır), farklı bir element seçilmesi için tekrar deneniyor."));
                    runStore.save(run);
                    Thread.sleep(500);
                    continue;
                }

                String currentSignature = action.getAction() + "|" + action.getTarget() + "|" + filteredPageSource.hashCode();                if (currentSignature.equals(lastActionSignature)) {
                    repeatCount++;
                } else {
                    repeatCount = 0;
                    lastActionSignature = currentSignature;
                }

                if (repeatCount >= 2) {
                    run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                            "Aynı aksiyon (" + action.getTarget() + ") üst üste tekrarlandı, model ilerleme kaydedemiyor. Test durduruldu."));
                    run.setStatus("failed");
                    run.setError("Model aynı elemente tekrar tekrar tıklayıp döngüye girdi");
                    run.setFinishedAt(java.time.Instant.now().toString());
                    runStore.save(run);
                    return;
                }
                switch (action.getAction()) {
                    case "tap" -> {
                        if (!appiumDriverManager.isValidCoordinate(rawPageSource, action.getX(), action.getY())) {
                            run.getSteps().add(new RunStep(i, "tap", action.getTarget(),
                                    "GEÇERSİZ KOORDİNAT (XML'de karşılığı yok), adım atlandı: " + action.getReasoning()));
                            runStore.save(run);
                            Thread.sleep(500);
                            continue;
                        }
                        run.getSteps().add(new RunStep(i, "tap", action.getTarget(), action.getReasoning()));
                        appiumDriverManager.tap(action.getX(), action.getY());
                        Thread.sleep(400); // geçiş animasyonunun oturması için ekstra bekleme

                    }
                    case "type" -> {
                        if (!appiumDriverManager.isValidCoordinate(rawPageSource, action.getX(), action.getY())) {
                            run.getSteps().add(new RunStep(i, "type", action.getTarget(), "GEÇERSİZ KOORDİNAT, adım atlanıyor"));
                            runStore.save(run);
                            Thread.sleep(500);
                            continue;
                        }
                        try {
                            run.getSteps().add(new RunStep(i, "type", action.getTarget(), action.getReasoning()));
                            appiumDriverManager.typeText(action.getX(), action.getY(), action.getText());
                        } catch (Exception typeEx) {
                            run.getSteps().add(new RunStep(i, "failed", action.getTarget(),
                                    "Alana yazılamadı (odak oturmadı): " + typeEx.getMessage()));
                            runStore.save(run);
                            Thread.sleep(500);
                            continue;
                        }
                    }
                    case "swipe" -> appiumDriverManager.swipe(action.getDirection());
                    case "wait" -> Thread.sleep(1500);
                    case "done" -> {
                        run.getSteps().add(new RunStep(i, "done", null, action.getReasoning()));
                        run.setStatus("passed");
                        run.setFinishedAt(Instant.now().toString());
                        runStore.save(run);
                        return;
                    }
                    case "fail" -> {
                        consecutiveFails++;
                        if (consecutiveFails >= 2) {
                            run.getSteps().add(new RunStep(i, "failed", null, action.getReasoning()));
                            run.setStatus("failed");
                            run.setError(action.getReasoning());
                            run.setFinishedAt(Instant.now().toString());
                            runStore.save(run);
                            return;
                        }
                        run.getSteps().add(new RunStep(i, "failed", null, "İlk 'fail' denemesi reddedildi, tekrar deneniyor: " + action.getReasoning()));
                        runStore.save(run);
                        Thread.sleep(500);
                        continue;
                    }
                }

                if (!action.getAction().equals("fail")) {
                    consecutiveFails = 0;
                }

                runStore.save(run);
                Thread.sleep(800);
            }
            run.setStatus("failed");
            run.setError("Maksimum adım sayısına ulaşıldı");
            run.setFinishedAt(Instant.now().toString());
            runStore.save(run);
        } catch (Exception e) {
            e.printStackTrace();
            appiumDriverManager.invalidateSession();
            run.setStatus("error");
            run.setError(e.getMessage());
            run.setFinishedAt(Instant.now().toString());
            runStore.save(run);
        }
    }
}
package com.testpilot.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class Run {//bir testin tüm durumu
    private String id;
    private String name;
    private String goal;
    private volatile String status; // "running" | "passed" | "failed" | "error" | "stopped"
    private final List<RunStep> steps = new CopyOnWriteArrayList<>();
    private String error;
    private String startedAt;
    private String finishedAt;
    private volatile boolean stopRequested = false;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<RunStep> getSteps() { return steps; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public boolean isStopRequested() { return stopRequested; }
    public void setStopRequested(boolean stopRequested) { this.stopRequested = stopRequested; }
    private String appPackage;
    private List<ScenarioSuggestion> suggestions;
    private String appActivity;

    private boolean nightlySuite;

    public boolean isNightlySuite() { return nightlySuite; }
    public void setNightlySuite(boolean nightlySuite) { this.nightlySuite = nightlySuite; }

    // nightlySuite'ten FARKLI bir alan: nightlySuite "bu run bir şablon, gece
    // tekrar koşulacak" demek (kullanıcı /runs/{id}/nightly ile işaretliyor);
    // nightlyRun ise "bu run'IN KENDİSİ gece koşumu tarafından üretildi" demek
    // -- NightlySuiteScheduler tarafından set edilir, Dashboard'daki "Gece
    // Koşumu" bölümü sabah bunlara bakarak sonuçları gösterir.
    private boolean nightlyRun;

    public boolean isNightlyRun() { return nightlyRun; }
    public void setNightlyRun(boolean nightlyRun) { this.nightlyRun = nightlyRun; }

    public String getAppActivity() {
        return appActivity;
    }

    public void setAppActivity(String appActivity) {
        this.appActivity = appActivity;
    }
    public List<ScenarioSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<ScenarioSuggestion> suggestions) { this.suggestions = suggestions; }
    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }

    private String platform;
    private Map<String, String> variables;
    private boolean captureScreenshot;
    private boolean recordVideo;

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
    public boolean isCaptureScreenshot() { return captureScreenshot; }
    public void setCaptureScreenshot(boolean captureScreenshot) { this.captureScreenshot = captureScreenshot; }
    public boolean isRecordVideo() { return recordVideo; }
    public void setRecordVideo(boolean recordVideo) { this.recordVideo = recordVideo; }

    private String failureScreenshot;
    
    // [YENI 2026-09-14] Fail sonrası scroll ile bulunan hedef - eğer LLM yine fail verirse otomatik tap yap
    private String autoTapTarget;
    private String autoTapReason;
    private boolean hasVideo;


    public String getFailureScreenshot() { return failureScreenshot; }
    public void setFailureScreenshot(String failureScreenshot) { this.failureScreenshot = failureScreenshot; }
    public boolean isHasVideo() { return hasVideo; }
    public void setHasVideo(boolean hasVideo) { this.hasVideo = hasVideo; }
    
    // [YENI 2026-09-14] Auto-tap için getter/setter
    public String getAutoTapTarget() { return autoTapTarget; }
    public void setAutoTapTarget(String autoTapTarget) { this.autoTapTarget = autoTapTarget; }
    public String getAutoTapReason() { return autoTapReason; }
    public void setAutoTapReason(String autoTapReason) { this.autoTapReason = autoTapReason; }

    // Proje seçilmeden oluşturulan testlerde (ya da nightly suite'te) bu alanlar
    // null kalır — sadece createdBy ve startedAt/finishedAt dolu olur.
    private Long projectId;
    private String projectName;
    private String createdBy;

    // Suite'ler (bir projenin testlerini gruplayan alt kümeler) -- projeden farklı
    // olarak bir test AYNI ANDA BİRDEN FAZLA suite'e ait olabilir (örn. "giriş
    // yap" testi hem Smoke hem Regression suite'inde olabilir), bu yüzden tekil
    // bir alan yerine liste. Suite entity'sine ilişki kurmak yerine (projectId/
    // projectName'deki gibi) düz id+name kopyaları tutuluyor. Boş liste = test
    // hiçbir suite'e ait değil.
    private List<SuiteRef> suites = new java.util.ArrayList<>();

    // Bu run'ın "Bu Suite'i Çalıştır" ile GERÇEKTEN bir suite koşumu sonucu
    // üretilip üretilmediği -- null ise bu run ya hiç bir suite'e ait değil ya
    // da suite'e SONRADAN manuel eklendi (Test Ekle / History'deki "Suite'e
    // Ekle"), suite ile birlikte hiç koşulmadı. Test History'nin suite
    // gruplaması (history.tsx) sadece bu alanı dolu olan run'ları suite
    // grubuna dahil ediyor -- böylece bir suite koşulduktan SONRA ona manuel
    // eklenen bir test, o önceki koşumun grubuna yanlışlıkla karışmıyor.
    private String suiteRunAt;

    // suiteRunAt ile BİRLİKTE, o an hangi suite'in koşumu olduğu da kalıcı
    // olarak damgalanıyor (id + o zamanki isim). Bunlar run.suites (canlı,
    // "Test Ekle"/swap ile değişebilen üyelik) listesinden BAĞIMSIZ -- suite
    // daha sonra yeniden koşulup bu run suite'ten çıkarılsa (swap) BİLE, bu
    // run'ın "hangi koşumdan doğduğu" bilgisi hiç değişmez. Test History'nin
    // suite gruplaması artık BUNA bakıyor (suiteId+suiteRunAt eşleşmesi),
    // canlı suite üyeliğine değil -- böylece bir suite birden fazla kez
    // koşulduğunda HER koşum kendi grubunda ayrı ayrı görünmeye devam eder.
    private Long suiteRunSuiteId;
    private String suiteRunSuiteName;

    public String getSuiteRunAt() { return suiteRunAt; }
    public void setSuiteRunAt(String suiteRunAt) { this.suiteRunAt = suiteRunAt; }
    public Long getSuiteRunSuiteId() { return suiteRunSuiteId; }
    public void setSuiteRunSuiteId(Long suiteRunSuiteId) { this.suiteRunSuiteId = suiteRunSuiteId; }
    public String getSuiteRunSuiteName() { return suiteRunSuiteName; }
    public void setSuiteRunSuiteName(String suiteRunSuiteName) { this.suiteRunSuiteName = suiteRunSuiteName; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public List<SuiteRef> getSuites() { return suites; }
    public void setSuites(List<SuiteRef> suites) { this.suites = suites != null ? suites : new java.util.ArrayList<>(); }
}
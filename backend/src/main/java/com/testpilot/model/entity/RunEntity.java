package com.testpilot.model.entity;

import com.testpilot.model.Suite;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Run/RunStep'in Oracle karsiligi -- ADITIF bir sinif, su an hicbir yerden
// kullanilmiyor. Mevcut Run.java (JSON/runs-history.json icin kullanilan POJO)
// hala tek "gercek" kaynak; bu entity sadece Oracle'da tablolarin dogru
// sekilde olusmasi icin var, RunStore/RunController henuz buna dokunmuyor.
//
// Not: projectId/projectName burada da Run.java'daki gibi duz kolon --
// Project'e @ManyToOne kurmuyoruz (lazy-loading/serilestirme sorunlarindan
// kacinmak icin, mevcut kod da ayni sekilde davraniyor).
@Entity
@Table(name = "MOBILE_RUNS")
public class RunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 255)
    private String name;

    @Lob
    @Column(name = "goal")
    private String goal;

    @Column(length = 20)
    private String status; // running | passed | failed | error | stopped

    @Lob
    @Column(name = "error")
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "app_package", length = 255)
    private String appPackage;

    @Column(name = "app_activity", length = 255)
    private String appActivity;

    @Column(length = 20)
    private String platform; // android | ios

    // Map<String,String> -- su asamada duz JSON metni olarak tutuluyor.
    // Ileriki bir adimda Run <-> RunEntity donusumunu yapan bir mapper
    // yazip burayi gercek Map'e cevirecegiz.
    @Lob
    @Column(name = "variables_json")
    private String variablesJson;

    @Column(name = "capture_screenshot", columnDefinition = "NUMBER(1)")
    private boolean captureScreenshot;

    @Column(name = "record_video", columnDefinition = "NUMBER(1)")
    private boolean recordVideo;

    @Column(name = "has_video", columnDefinition = "NUMBER(1)")
    private boolean hasVideo;

    // Onceki mesajda konustugumuz gibi: ekran goruntusunun base64 icerigi DB'ye
    // girmiyor, video gibi dosya sistemine yaziliyor (screenshots/<id>.png) --
    // burada sadece "var mi yok mu" tutuluyor.
    @Column(name = "has_failure_screenshot", columnDefinition = "NUMBER(1)")
    private boolean hasFailureScreenshot;

    // JSON dosyasini kapatinca (5. adim) ekran goruntusunun tek kopyasi burasi
    // oluyor -- video gibi diske de yazilabilirdi ama frontend su an
    // "data:image/png;base64,..." seklinde dogrudan Run.failureScreenshot alanini
    // kullaniyor (bkz. tests/$id.tsx), bu yuzden ayni sekli/alani koruyoruz --
    // ayrica bir dosya-sunma endpoint'i veya frontend degisikligi gerekmiyor.
    @Lob
    @Column(name = "failure_screenshot_base64")
    private String failureScreenshotBase64;

    @Column(name = "nightly_suite", columnDefinition = "NUMBER(1)")
    private boolean nightlySuite;

    @Column(name = "nightly_run", columnDefinition = "NUMBER(1)")
    private boolean nightlyRun;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "project_name", length = 255)
    private String projectName;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    // Bu run bir suite koşumu (RunController.markSuiteRun) ile mi üretildi --
    // null ise suite'e sonradan manuel eklenmiş demektir, History'nin suite
    // gruplaması bunu ayırt etmek için kullanıyor (bkz. Run.java'daki yorum).
    @Column(name = "suite_run_at")
    private Instant suiteRunAt;

    @Column(name = "suite_run_suite_id")
    private Long suiteRunSuiteId;

    @Column(name = "suite_run_suite_name", length = 255)
    private String suiteRunSuiteName;

    // Suite<->Run cok-cok iliski -- bir test ayni anda birden fazla suite'e ait
    // olabilir (Smoke + Regression gibi), bu yuzden Project.members'taki gibi
    // ayri bir baglanti tablosu (mobile_run_suites) kullaniliyor.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "mobile_run_suites",
            joinColumns = @JoinColumn(name = "run_id"),
            inverseJoinColumns = @JoinColumn(name = "suite_id")
    )
    private Set<Suite> suites = new HashSet<>();

    // List<ScenarioSuggestion> -- variables_json gibi, su asamada duz JSON metni.
    @Lob
    @Column(name = "suggestions_json")
    private String suggestionsJson;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepNo ASC")
    private List<RunStepEntity> steps = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getAppPackage() { return appPackage; }
    public void setAppPackage(String appPackage) { this.appPackage = appPackage; }
    public String getAppActivity() { return appActivity; }
    public void setAppActivity(String appActivity) { this.appActivity = appActivity; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public boolean isCaptureScreenshot() { return captureScreenshot; }
    public void setCaptureScreenshot(boolean captureScreenshot) { this.captureScreenshot = captureScreenshot; }
    public boolean isRecordVideo() { return recordVideo; }
    public void setRecordVideo(boolean recordVideo) { this.recordVideo = recordVideo; }
    public boolean isHasVideo() { return hasVideo; }
    public void setHasVideo(boolean hasVideo) { this.hasVideo = hasVideo; }
    public boolean isHasFailureScreenshot() { return hasFailureScreenshot; }
    public void setHasFailureScreenshot(boolean hasFailureScreenshot) { this.hasFailureScreenshot = hasFailureScreenshot; }
    public String getFailureScreenshotBase64() { return failureScreenshotBase64; }
    public void setFailureScreenshotBase64(String failureScreenshotBase64) { this.failureScreenshotBase64 = failureScreenshotBase64; }
    public boolean isNightlySuite() { return nightlySuite; }
    public void setNightlySuite(boolean nightlySuite) { this.nightlySuite = nightlySuite; }
    public boolean isNightlyRun() { return nightlyRun; }
    public void setNightlyRun(boolean nightlyRun) { this.nightlyRun = nightlyRun; }

    public Instant getSuiteRunAt() { return suiteRunAt; }
    public void setSuiteRunAt(Instant suiteRunAt) { this.suiteRunAt = suiteRunAt; }
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
    public Set<Suite> getSuites() { return suites; }
    public void setSuites(Set<Suite> suites) { this.suites = suites; }
    public String getSuggestionsJson() { return suggestionsJson; }
    public void setSuggestionsJson(String suggestionsJson) { this.suggestionsJson = suggestionsJson; }
    public List<RunStepEntity> getSteps() { return steps; }
    public void setSteps(List<RunStepEntity> steps) { this.steps = steps; }
}

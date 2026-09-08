package com.testpilot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testpilot.model.Run;
import com.testpilot.model.RunStep;
import com.testpilot.model.ScenarioSuggestion;
import com.testpilot.model.Suite;
import com.testpilot.model.SuiteRef;
import com.testpilot.model.entity.RunEntity;
import com.testpilot.model.entity.RunStepEntity;
import com.testpilot.repository.RunRepository;
import com.testpilot.repository.SuiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// RunStore'un arkasinda calisan, gercek Oracle okuma/yazma islerini yapan servis.
// RunStore bu sinifi (Spring proxy'si uzerinden, ayri bir bean olarak) cagirir --
// @Transactional'in duzgun calismasi icin ayri bir bean olmasi sart (RunStore
// icinden "this.metod()" ile cagrilsaydi Spring'in AOP proxy'si devreye girmezdi).
//
// Run (POJO, calisirken kullanilan) <-> RunEntity/RunStepEntity (Oracle karsiligi)
// donusumu de burada yapiliyor. variables/suggestions gibi Map/List alanlar
// RunEntity'de duz JSON metni olarak tutuluyor (bkz. RunEntity'deki yorum).
@Service
public class RunPersistenceService {

    private final RunRepository runRepository;
    private final SuiteRepository suiteRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public RunPersistenceService(RunRepository runRepository, SuiteRepository suiteRepository) {
        this.runRepository = runRepository;
        this.suiteRepository = suiteRepository;
    }

    @Transactional(readOnly = true)
    public List<Run> loadAll() {
        List<Run> result = new ArrayList<>();
        for (RunEntity e : runRepository.findAllWithSteps()) {
            result.add(toPojo(e));
        }
        return result;
    }

    // Test kosarken (executeRun) her adimda cagriliyor -- bir run'in adimlarini
    // tamamen silip yeniden yazmak (orphanRemoval=true) az sayida adim icin
    // (genelde <20) sorun degil, basit ve dogru sonuc veren yontem bu.
    @Transactional
    public void save(Run run) {
        RunEntity entity = runRepository.findByIdWithSteps(run.getId()).orElseGet(RunEntity::new);
        entity.setId(run.getId());
        entity.setName(run.getName());
        entity.setGoal(run.getGoal());
        entity.setStatus(run.getStatus());
        entity.setError(run.getError());
        entity.setStartedAt(parseInstant(run.getStartedAt()));
        entity.setFinishedAt(parseInstant(run.getFinishedAt()));
        entity.setAppPackage(run.getAppPackage());
        entity.setAppActivity(run.getAppActivity());
        entity.setPlatform(run.getPlatform());
        entity.setVariablesJson(writeJson(run.getVariables()));
        entity.setCaptureScreenshot(run.isCaptureScreenshot());
        entity.setRecordVideo(run.isRecordVideo());
        entity.setHasVideo(run.isHasVideo());
        boolean hasFailureScreenshot = run.getFailureScreenshot() != null && !run.getFailureScreenshot().isBlank();
        entity.setHasFailureScreenshot(hasFailureScreenshot);
        entity.setFailureScreenshotBase64(run.getFailureScreenshot());
        entity.setNightlySuite(run.isNightlySuite());
        entity.setNightlyRun(run.isNightlyRun());
        entity.setSuiteRunAt(parseInstant(run.getSuiteRunAt()));
        entity.setSuiteRunSuiteId(run.getSuiteRunSuiteId());
        entity.setSuiteRunSuiteName(run.getSuiteRunSuiteName());
        entity.setProjectId(run.getProjectId());
        entity.setProjectName(run.getProjectName());
        entity.setCreatedBy(run.getCreatedBy());
        entity.setSuggestionsJson(writeJson(run.getSuggestions()));

        // Suite'ler -- Project.members'ta olduğu gibi "temizle + yeniden doldur"
        // deseni: aynı managed Set instance'ı kullanılıyor (entity.getSuites()),
        // sadece içeriği değiştiriliyor.
        Set<Suite> suites = entity.getSuites();
        suites.clear();
        if (run.getSuites() != null) {
            for (SuiteRef ref : run.getSuites()) {
                if (ref == null || ref.getId() == null) continue;
                suiteRepository.findById(ref.getId()).ifPresent(suites::add);
            }
        }

        List<RunStepEntity> steps = entity.getSteps();
        steps.clear();
        if (run.getSteps() != null) {
            for (RunStep s : run.getSteps()) {
                RunStepEntity se = new RunStepEntity();
                se.setRun(entity);
                se.setStepNo(s.getStep());
                se.setAction(s.getAction());
                se.setTarget(s.getTarget());
                se.setReasoning(s.getReasoning());
                steps.add(se);
            }
        }

        runRepository.save(entity);
    }

    @Transactional
    public void delete(String id) {
        runRepository.deleteById(id);
    }

    // runs-history.json'dan Oracle'a bir kerelik ilk aktarim icin -- save() ile
    // ayni mantik, RunStore.load() icinden cagrilmasi icin ayri/acik bir isim.
    @Transactional
    public void importFromJson(Run run) {
        save(run);
    }

    private Run toPojo(RunEntity e) {
        Run run = new Run();
        run.setId(e.getId());
        run.setName(e.getName());
        run.setGoal(e.getGoal());
        run.setStatus(e.getStatus());
        run.setError(e.getError());
        run.setStartedAt(formatInstant(e.getStartedAt()));
        run.setFinishedAt(formatInstant(e.getFinishedAt()));
        run.setAppPackage(e.getAppPackage());
        run.setAppActivity(e.getAppActivity());
        run.setPlatform(e.getPlatform());
        run.setVariables(readJson(e.getVariablesJson(), new TypeReference<Map<String, String>>() {}));
        run.setCaptureScreenshot(e.isCaptureScreenshot());
        run.setRecordVideo(e.isRecordVideo());
        run.setHasVideo(e.isHasVideo());
        run.setFailureScreenshot(e.getFailureScreenshotBase64());
        run.setNightlySuite(e.isNightlySuite());
        run.setNightlyRun(e.isNightlyRun());
        run.setSuiteRunAt(formatInstant(e.getSuiteRunAt()));
        run.setSuiteRunSuiteId(e.getSuiteRunSuiteId());
        run.setSuiteRunSuiteName(e.getSuiteRunSuiteName());
        run.setProjectId(e.getProjectId());
        run.setProjectName(e.getProjectName());
        run.setCreatedBy(e.getCreatedBy());
        run.setSuggestions(readJson(e.getSuggestionsJson(), new TypeReference<List<ScenarioSuggestion>>() {}));

        List<SuiteRef> suiteRefs = new ArrayList<>();
        for (Suite s : e.getSuites()) {
            suiteRefs.add(new SuiteRef(s.getId(), s.getName()));
        }
        run.setSuites(suiteRefs);

        List<RunStepEntity> steps = new ArrayList<>(e.getSteps());
        steps.sort(Comparator.comparingInt(RunStepEntity::getStepNo));
        for (RunStepEntity se : steps) {
            run.getSteps().add(new RunStep(se.getStepNo(), se.getAction(), se.getTarget(), se.getReasoning()));
        }
        return run;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String formatInstant(Instant i) {
        return i != null ? i.toString() : null;
    }

    private String writeJson(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception ex) {
            return null;
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (Exception ex) {
            return null;
        }
    }
}

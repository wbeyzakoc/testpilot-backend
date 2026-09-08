package com.testpilot.controller;

import com.testpilot.dto.CreateSuiteRequest;
import com.testpilot.dto.SuiteDto;
import com.testpilot.dto.UpdateSuiteRequest;
import com.testpilot.model.AppUser;
import com.testpilot.model.Project;
import com.testpilot.model.Suite;
import com.testpilot.model.UserRole;
import com.testpilot.repository.ProjectRepository;
import com.testpilot.repository.RunRepository;
import com.testpilot.repository.SuiteRepository;
import com.testpilot.security.CurrentUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

// Suite = bir projenin testlerini gruplamak için kullanılan alt küme
// (Project > Suite > Run). Projeler/Kullanıcılar sayfalarının aksine Suite
// yönetimi admin'e özel DEĞİL -- bir projenin üyesi olan herhangi bir USER o
// projede suite oluşturabilir/yeniden adlandırabilir/silebilir (test
// organizasyonu operasyonel bir iş, üyelik yönetimi gibi idari değil).
// Adminler her projede bu işlemleri yapabilir. Görüntüleme (GET) de aynı
// şekilde role'e göre süzülüyor (ProjectController.listProjects'teki gibi).
@RestController
@RequestMapping("/suites")
@CrossOrigin(origins = "*")
public class SuiteController {

    private final SuiteRepository suiteRepository;
    private final ProjectRepository projectRepository;
    private final RunRepository runRepository;
    private final CurrentUserResolver currentUserResolver;

    public SuiteController(SuiteRepository suiteRepository, ProjectRepository projectRepository,
                            RunRepository runRepository, CurrentUserResolver currentUserResolver) {
        this.suiteRepository = suiteRepository;
        this.projectRepository = projectRepository;
        this.runRepository = runRepository;
        this.currentUserResolver = currentUserResolver;
    }

    // Admin ya da verilen projenin üyesi olmayan bir kullanıcı bu projede
    // suite oluşturamaz/düzenleyemez/silemez -- RunController.assignProject'teki
    // aynı admin/üye kontrolü.
    private void requireProjectAccess(String requester, Project project) {
        AppUser user = currentUserResolver.requireUser(requester);
        boolean isAdmin = user.getRole() == UserRole.ADMIN;
        boolean isMember = project.getMembers().stream().anyMatch(m -> m.getId().equals(user.getId()));
        if (!isAdmin && !isMember) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu projede suite yönetme yetkiniz yok");
        }
    }

    // projectId verilmezse: adminler tüm suite'leri, USER'lar sadece üyesi
    // olduğu projelerin suite'lerini görür (Test History'deki suite dropdown'ı
    // bunu kullanıyor). projectId verilirse (Suite'ler sayfasındaki proje
    // seçimi) o projeye daraltılır -- kullanıcı o projenin üyesi değilse boş döner.
    @GetMapping
    @Transactional(readOnly = true)
    public List<SuiteDto> listSuites(@RequestHeader(value = "X-Username", required = false) String requester,
                                      @RequestParam(required = false) Long projectId) {
        AppUser user = currentUserResolver.requireUser(requester);
        List<Suite> suites;
        if (user.getRole() == UserRole.ADMIN) {
            suites = projectId != null ? suiteRepository.findByProject_Id(projectId) : suiteRepository.findAll();
        } else {
            List<Long> memberProjectIds = projectRepository.findByMembersContaining(user).stream()
                    .map(Project::getId)
                    .toList();
            if (projectId != null) {
                suites = memberProjectIds.contains(projectId)
                        ? suiteRepository.findByProject_Id(projectId)
                        : List.of();
            } else {
                suites = memberProjectIds.isEmpty() ? List.of() : suiteRepository.findByProject_IdIn(memberProjectIds);
            }
        }
        return suites.stream()
                .sorted(Comparator.comparing(Suite::getName, String.CASE_INSENSITIVE_ORDER))
                .map(SuiteDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SuiteDto createSuite(@RequestHeader(value = "X-Username", required = false) String requester,
                                 @RequestBody CreateSuiteRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suite adı boş olamaz");
        }
        if (request.getProjectId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proje seçilmeli");
        }
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proje bulunamadı"));
        requireProjectAccess(requester, project);

        Suite suite = new Suite();
        suite.setName(request.getName().trim());
        suite.setProject(project);
        suite.setCreatedBy(requester);
        suiteRepository.save(suite);
        return SuiteDto.from(suite);
    }

    @PutMapping("/{id}")
    @Transactional
    public SuiteDto renameSuite(@RequestHeader(value = "X-Username", required = false) String requester,
                                 @PathVariable Long id,
                                 @RequestBody UpdateSuiteRequest request) {
        Suite suite = suiteRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suite bulunamadı"));
        requireProjectAccess(requester, suite.getProject());
        if (request.getName() == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suite adı boş olamaz");
        }
        suite.setName(request.getName().trim());
        suiteRepository.save(suite);
        return SuiteDto.from(suite);
    }

    // Suite silinince içindeki testler SİLİNMEZ -- sadece mobile_run_suites
    // bağlantı tablosundaki ilgili satırlar temizlenir (tıpkı proje silmede
    // olduğu gibi), testler Test History'de bağımsız kayıtlar olarak kalmaya
    // devam eder.
    @DeleteMapping("/{id}")
    @Transactional
    public void deleteSuite(@RequestHeader(value = "X-Username", required = false) String requester,
                             @PathVariable Long id) {
        Suite suite = suiteRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Suite bulunamadı"));
        requireProjectAccess(requester, suite.getProject());
        runRepository.clearSuiteReferences(id);
        suiteRepository.deleteById(id);
    }
}

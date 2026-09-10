package com.testpilot.controller;

import com.testpilot.dto.AddProjectMembersRequest;
import com.testpilot.dto.CreateProjectRequest;
import com.testpilot.dto.ProjectDto;
import com.testpilot.model.AppUser;
import com.testpilot.model.Project;
import com.testpilot.model.Suite;
import com.testpilot.model.UserRole;
import com.testpilot.repository.AppUserRepository;
import com.testpilot.repository.ProjectRepository;
import com.testpilot.repository.RunRepository;
import com.testpilot.repository.SuiteRepository;
import com.testpilot.security.CurrentUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/projects")
@CrossOrigin(origins = "*")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final AppUserRepository userRepository;
    private final CurrentUserResolver currentUserResolver;
    private final SuiteRepository suiteRepository;
    private final RunRepository runRepository;

    public ProjectController(ProjectRepository projectRepository, AppUserRepository userRepository,
                              CurrentUserResolver currentUserResolver, SuiteRepository suiteRepository,
                              RunRepository runRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.currentUserResolver = currentUserResolver;
        this.suiteRepository = suiteRepository;
        this.runRepository = runRepository;
    }

    // Giriş yapmış herkes çağırabilir (Create Test'teki proje seçimi bunu kullanıyor) —
    // ama gördüğü liste role'e göre değişir: adminler her projeyi görür, USER
    // rolündekiler sadece üye olduğu projeleri görür.
    // @Transactional şart: members lazy (@ManyToMany varsayılanı) ve
    // open-in-view=false olduğu için, session repository çağrısından hemen
    // sonra kapanıyor — transaction olmadan ProjectDto.from() içindeki
    // p.getMembers() erişimi "LazyInitializationException: no Session" atıyordu.
    @GetMapping
    @Transactional(readOnly = true)
    public List<ProjectDto> listProjects(@RequestHeader(value = "X-Username", required = false) String requester) {
        AppUser user = currentUserResolver.requireUser(requester);
        List<Project> projects = user.getRole() == UserRole.ADMIN
                ? projectRepository.findAll()
                : projectRepository.findByMembersContaining(user);
        return projects.stream()
                .sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER))
                .map(ProjectDto::from)
                .toList();
    }

    // Proje oluşturma sadece admin. Oluşturan admin otomatik olarak üye olur.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProjectDto createProject(@RequestHeader(value = "X-Username", required = false) String requester,
                                     @RequestBody CreateProjectRequest request) {
        AppUser admin = currentUserResolver.requireAdmin(requester);
        if (request.getName() == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proje adı boş olamaz");
        }
        String name = request.getName().trim();
        if (projectRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bu proje adı zaten kayıtlı");
        }

        // Önce ID'ler bazında tekilleştir (admin kendini de listede işaretlemiş
        // olabilir) — sonra TEK seferde yükle. AppUser'da equals/hashCode
        // override edilmediği için aynı satırı iki ayrı sorgudan (findAllById +
        // admin nesnesi) yükleyip HashSet'e koymak veritabanına iki kez INSERT
        // denenmesine (unique constraint hatası) yol açıyordu.
        Set<Long> memberIds = new LinkedHashSet<>();
        if (request.getMemberIds() != null) {
            memberIds.addAll(request.getMemberIds());
        }
        memberIds.add(admin.getId());
        Set<AppUser> members = new HashSet<>(userRepository.findAllById(memberIds));

        Project project = new Project();
        project.setName(name);
        project.setCreatedBy(admin.getUsername());
        project.setMembers(members);
        project.setMaxParallelRuns(normalizeMaxParallelRuns(request.getMaxParallelRuns()));
        projectRepository.save(project);
        return ProjectDto.from(project);
    }

    // Proje adını ve üye listesini günceller (üye listesi TAMAMEN gönderilenle
    // değiştirilir — ldap.tsx'teki ayar güncellemesiyle aynı mantık). Admin
    // burada kendini üye listesinden çıkarabilir, otomatik ekleme sadece
    // oluşturma anında geçerli.
    @PutMapping("/{id}")
    @Transactional
    public ProjectDto updateProject(@RequestHeader(value = "X-Username", required = false) String requester,
                                     @PathVariable Long id,
                                     @RequestBody CreateProjectRequest request) {
        currentUserResolver.requireAdmin(requester);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proje bulunamadı"));

        if (request.getName() == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proje adı boş olamaz");
        }
        String name = request.getName().trim();
        if (!name.equalsIgnoreCase(project.getName()) && projectRepository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bu proje adı zaten kayıtlı");
        }
        project.setName(name);

        Set<Long> memberIds = new LinkedHashSet<>();
        if (request.getMemberIds() != null) {
            memberIds.addAll(request.getMemberIds());
        }
        Set<AppUser> members = new HashSet<>(userRepository.findAllById(memberIds));
        project.setMembers(members);
        project.setMaxParallelRuns(normalizeMaxParallelRuns(request.getMaxParallelRuns()));

        projectRepository.save(project);
        return ProjectDto.from(project);
    }

    // null (sinirsiz) oldugu gibi geciyor; sifir veya negatif deger reddediliyor --
    // suites.tsx'teki kuyruklama mantigi icin en az 1 slot anlamli.
    private Integer normalizeMaxParallelRuns(Integer value) {
        if (value == null) return null;
        if (value < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paralel koşum sayısı en az 1 olmalı");
        }
        return value;
    }

    // Kullanıcılar sayfasındaki toplu "seçili kullanıcıları projeye ekle"
    // butonu bunu çağırır. PUT /projects/{id}'nin aksine üye listesini
    // TAMAMEN değiştirmez — sadece verilen userId'leri mevcut üye kümesine
    // ekler (zaten üye olanlar bir şey değişmez, projenin başka üyeleri
    // silinmez). Set olduğu için aynı kullanıcı iki kez gönderilse bile
    // tekilleşir.
    @PostMapping("/{id}/members")
    @Transactional
    public ProjectDto addMembers(@RequestHeader(value = "X-Username", required = false) String requester,
                                  @PathVariable Long id,
                                  @RequestBody AddProjectMembersRequest request) {
        currentUserResolver.requireAdmin(requester);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proje bulunamadı"));

        if (request.getUserIds() == null || request.getUserIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userIds boş olamaz");
        }
        List<AppUser> usersToAdd = userRepository.findAllById(request.getUserIds());
        project.getMembers().addAll(usersToAdd);

        projectRepository.save(project);
        return ProjectDto.from(project);
    }

    // Proje silme sadece admin. project_members'taki ilgili satırlar Hibernate
    // tarafından otomatik temizlenir (ayrıca elle silmeye gerek yok). Bu
    // projeye ait eski test kayıtları (runs-history.json) etkilenmez — Run'da
    // projectName ayrıca kopyalanmış olduğu için geçmişte hangi proje adıyla
    // koşulduğu görünmeye devam eder, sadece projectId artık geçersiz olur.
    // Önceden burada sadece projectRepository.deleteById(id) vardı -- Suite'in
    // projeye bağı zorunlu (nullable=false) bir @ManyToOne olduğu için, üstünde
    // suite'i olan bir projeyi silmeye çalışmak Oracle'da FK kısıt ihlaline
    // (çirkin, kullanıcıya anlamsız gelen bir hata) çarpıyordu. Şimdi projeye
    // ait suite'ler -- SuiteController.deleteSuite'teki AYNI temizlik adımıyla
    // (önce run'lardaki suite referansları, sonra suite'in kendisi) -- proje
    // silinmeden önce siliniyor. Run.projectId gerçek bir FK olmadığı için
    // (düz kolon) onlara dokunmuyoruz; proje silindikten sonra o run'lar Test
    // History'de proje bilgisi olmadan görünmeye devam eder.
    @DeleteMapping("/{id}")
    @Transactional
    public void deleteProject(@RequestHeader(value = "X-Username", required = false) String requester,
                               @PathVariable Long id) {
        currentUserResolver.requireAdmin(requester);
        if (!projectRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proje bulunamadı");
        }
        List<Suite> suites = suiteRepository.findByProject_Id(id);
        for (Suite suite : suites) {
            runRepository.clearSuiteReferences(suite.getId());
        }
        if (!suites.isEmpty()) {
            suiteRepository.deleteAll(suites);
        }
        projectRepository.deleteById(id);
    }
}

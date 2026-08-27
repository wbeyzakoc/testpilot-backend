package com.testpilot.controller;

import com.testpilot.agent.RunStore;
import com.testpilot.dto.AppUserDto;
import com.testpilot.dto.CreateUserRequest;
import com.testpilot.dto.UpdateRoleRequest;
import com.testpilot.model.AppUser;
import com.testpilot.model.Run;
import com.testpilot.model.UserRole;
import com.testpilot.model.UserSource;
import com.testpilot.repository.AppUserRepository;
import com.testpilot.security.CurrentUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
@CrossOrigin(origins = "*")
public class UserController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserResolver currentUserResolver;
    private final RunStore runStore;

    public UserController(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                           CurrentUserResolver currentUserResolver, RunStore runStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserResolver = currentUserResolver;
        this.runStore = runStore;
    }

    // Bu panel sadece admin rolündeki kullanıcılara açık. Her kullanıcının kaç
    // test oluşturduğu/kaçının geçtiği de (Run.createdBy üzerinden) burada
    // hesaplanıp DTO'ya ekleniyor -- users.tsx'te arama/başarı oranı için.
    @GetMapping
    public List<AppUserDto> listUsers(@RequestHeader(value = "X-Username", required = false) String requester) {
        currentUserResolver.requireAdmin(requester);

        Map<String, int[]> statsByUsername = new HashMap<>(); // [toplam, gecen]
        for (Run r : runStore.getAll()) {
            String createdBy = r.getCreatedBy();
            if (createdBy == null || createdBy.isBlank()) continue;
            String key = createdBy.toLowerCase();
            int[] counts = statsByUsername.computeIfAbsent(key, k -> new int[2]);
            counts[0]++;
            if ("passed".equals(r.getStatus())) counts[1]++;
        }

        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getUsername, String.CASE_INSENSITIVE_ORDER))
                .map(u -> {
                    AppUserDto dto = AppUserDto.from(u);
                    int[] counts = statsByUsername.get(u.getUsername().toLowerCase());
                    if (counts != null) {
                        dto.setTestCount(counts[0]);
                        dto.setPassedCount(counts[1]);
                        dto.setSuccessRate(counts[0] > 0 ? (counts[1] * 100.0 / counts[0]) : null);
                    }
                    return dto;
                })
                .toList();
    }

    // Superadmin'in elle LOCAL kullanıcı ekleyebilmesi için — şirket LDAP'ı henüz
    // bağlanmadığından şimdilik tek kullanıcı ekleme yolu bu.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppUserDto createUser(@RequestHeader(value = "X-Username", required = false) String requester,
                                  @RequestBody CreateUserRequest request) {
        currentUserResolver.requireAdmin(requester);
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kullanıcı adı boş olamaz");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Şifre boş olamaz");
        }
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername().trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bu kullanıcı adı zaten kayıtlı");
        }

        AppUser user = new AppUser();
        user.setUsername(request.getUsername().trim());
        user.setRole(request.getRole() != null ? request.getRole() : UserRole.USER);
        user.setSource(UserSource.LOCAL);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        return AppUserDto.from(user);
    }

    @PutMapping("/{id}/role")
    public AppUserDto updateRole(@RequestHeader(value = "X-Username", required = false) String requester,
                                  @PathVariable Long id,
                                  @RequestBody UpdateRoleRequest request) {
        AppUser admin = currentUserResolver.requireAdmin(requester);
        if (request.getRole() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role alanı zorunlu");
        }
        // Admin kendi rolünü USER'a düşüremesin — yoksa panele erişimini kaybedip
        // kimse geri alamaz.
        if (admin.getId().equals(id) && request.getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kendi rolünüzü değiştiremezsiniz");
        }
        AppUser user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı bulunamadı"));
        user.setRole(request.getRole());
        userRepository.save(user);
        return AppUserDto.from(user);
    }
}

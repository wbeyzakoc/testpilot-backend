package com.testpilot.controller;

import com.testpilot.dto.AppUserDto;
import com.testpilot.dto.CreateUserRequest;
import com.testpilot.dto.UpdateRoleRequest;
import com.testpilot.model.AppUser;
import com.testpilot.model.UserRole;
import com.testpilot.model.UserSource;
import com.testpilot.repository.AppUserRepository;
import com.testpilot.security.CurrentUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Comparator;

@RestController
@RequestMapping("/users")
@CrossOrigin(origins = "*")
public class UserController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserResolver currentUserResolver;

    public UserController(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                           CurrentUserResolver currentUserResolver) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserResolver = currentUserResolver;
    }

    // Bu panel sadece admin rolündeki kullanıcılara açık.
    @GetMapping
    public List<AppUserDto> listUsers(@RequestHeader(value = "X-Username", required = false) String requester) {
        currentUserResolver.requireAdmin(requester);
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getUsername, String.CASE_INSENSITIVE_ORDER))
                .map(AppUserDto::from)
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

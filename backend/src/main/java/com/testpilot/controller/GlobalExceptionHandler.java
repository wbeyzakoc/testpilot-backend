package com.testpilot.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

// Merkezi hata yakalama -- önceden her controller kendi try/catch'iyle ya da hiç
// yakalamadan Spring'in varsayılan (teknik, kullanıcıya anlamsız gelen) hata
// sayfasına düşüyordu. Buradan sonra:
//
//  - Controller'ların zaten attığı ResponseStatusException'lar ("Bu projede...",
//    "Kullanıcı bulunamadı" vb.) AYNEN korunuyor -- durum kodu ve mesaj hiç
//    değişmiyor, sadece format tek bir yerden garanti ediliyor. Bu handler
//    olmasa bile Spring bunları zaten doğru şekilde döndürüyordu
//    (server.error.include-message=always sayesinde) -- burada AYRICA
//    tanımlanmasının tek sebebi, aşağıdaki genel Exception.class handler'ının
//    onları da (yanlışlıkla 500'e çevirerek) yutmasını engellemek.
//  - Beklenmeyen bir veritabanı kısıtı ihlali (örn. hâlâ ilişkili kaydı olan bir
//    şeyi silmeye çalışmak gibi kod tarafında gözden kaçan bir durum) artık
//    çirkin bir stack trace yerine anlaşılır bir 409 mesajına dönüyor.
//  - Gerçekten beklenmeyen her şey (NullPointerException vb.) generic bir 500
//    mesajına düşüyor; sunucu tarafında (IntelliJ konsolunda) stack trace hâlâ
//    loglanıyor ama kullanıcıya iç detaylar sızmıyor.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : "Bir hata oluştu";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        System.err.println("Veri bütünlüğü hatası: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "Bu işlem veritabanı kısıtlarına takıldı -- ilişkili kayıtlar hâlâ mevcut olabilir."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        ex.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Beklenmeyen bir sunucu hatası oluştu."));
    }
}

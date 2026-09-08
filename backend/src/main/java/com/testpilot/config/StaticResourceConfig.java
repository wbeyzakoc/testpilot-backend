package com.testpilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/videos/**")
                .addResourceLocations("file:videos/");
        // Dashboard'daki "Allure Raporu Oluştur" butonuyla üretilen statik
        // Allure HTML sitesi -- AllureReportService bunu "allure-report/"
        // klasörüne yazıyor, buradan dışarıya aynı desenle açılıyor.
        registry.addResourceHandler("/allure-report/**")
                .addResourceLocations("file:allure-report/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/videos/**").allowedOrigins("*");
        registry.addMapping("/allure-report/**").allowedOrigins("*");
    }
}
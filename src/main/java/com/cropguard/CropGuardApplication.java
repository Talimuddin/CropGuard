package com.cropguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CropGuardApplication {

    public static void main(String[] args) {
        System.out.println("\n🌿 CropGuard Gemini Server (Java)");
        System.out.println("========================================");
        System.out.println("Open: http://localhost:5000");
        System.out.println("========================================");
        SpringApplication.run(CropGuardApplication.class, args);
    }
}

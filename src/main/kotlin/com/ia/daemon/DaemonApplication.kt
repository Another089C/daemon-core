package com.ia.daemon

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class DaemonApplication

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "false")
    val os = System.getProperty("os.name").lowercase()

    if (os.contains("mac")) {
        // Evita el parpadeo del compositor Quartz/Metal en macOS Monterey y superiores
        System.setProperty("apple.awt.application.name", "Daemon AI")
        System.setProperty("apple.awt.graphics.UseQuartz", "true")
        System.setProperty("sun.java2d.metal", "false")
    } else if (os.contains("win")) {
        System.setProperty("sun.java2d.noddraw", "true")
    } else {
        System.setProperty("sun.java2d.opengl", "true")
    }

    SpringApplication.run(DaemonApplication::class.java, *args)
}
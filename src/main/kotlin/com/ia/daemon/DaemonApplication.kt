package com.ia.daemon

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class DaemonApplication

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "false")
    val os = System.getProperty("os.name").lowercase()

    if (os.contains("mac")) {
        System.setProperty("apple.awt.application.name", "Daemon AI")
        // Activa aceleración por hardware nativa y evita parpadeo de bordes en macOS
        System.setProperty("sun.java2d.opengl", "true")
    } else if (os.contains("win")) {
        System.setProperty("sun.java2d.noddraw", "true")
    } else {
        System.setProperty("sun.java2d.opengl", "true")
    }

    SpringApplication.run(DaemonApplication::class.java, *args)
}
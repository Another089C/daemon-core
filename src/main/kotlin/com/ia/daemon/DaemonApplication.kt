package com.ia.daemon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DaemonApplication

fun main(args: Array<String>) {
    // Si corre en tu PC activa la mini-ventana; si corre en un servidor en la nube usa modo Cloud
    val isCloudServer = System.getenv("RENDER") != null || System.getenv("RAILWAY_ENVIRONMENT") != null
    System.setProperty("java.awt.headless", if (isCloudServer) "true" else "false")
    runApplication<DaemonApplication>(*args)
}

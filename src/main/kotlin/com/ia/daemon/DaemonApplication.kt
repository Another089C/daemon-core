package com.ia.daemon

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class DaemonApplication

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "false")
    System.setProperty("sun.java2d.noddraw", "true")
    SpringApplication.run(DaemonApplication::class.java, *args)
}
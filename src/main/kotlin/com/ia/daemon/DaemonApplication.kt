package com.ia.daemon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DaemonApplication

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "false")
    System.setProperty("sun.java2d.noddraw", "true")
    runApplication<DaemonApplication>(*args)
}
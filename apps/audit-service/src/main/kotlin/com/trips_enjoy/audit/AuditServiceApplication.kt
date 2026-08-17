package com.trips_enjoy.audit

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

@SpringBootApplication
@EnableScheduling
class AuditServiceApplication

fun main(args: Array<String>) {
    val context = runApplication<AuditServiceApplication>(*args)
    if (args.firstOrNull() == "migrate") exitProcess(SpringApplication.exit(context))
}

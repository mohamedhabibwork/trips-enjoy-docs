package com.trips_enjoy.identity

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.SpringApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.cache.annotation.EnableCaching
import kotlin.system.exitProcess

@SpringBootApplication
@EnableScheduling
@EnableCaching
class IdentityServiceApplication

fun main(args: Array<String>) {
	val context = runApplication<IdentityServiceApplication>(*args)
	if (args.firstOrNull() == "migrate") exitProcess(SpringApplication.exit(context))
}

package com.trips_enjoy.audit

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<AuditServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}

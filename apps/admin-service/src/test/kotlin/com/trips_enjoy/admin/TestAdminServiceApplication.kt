package com.trips_enjoy.admin

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<AdminServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}

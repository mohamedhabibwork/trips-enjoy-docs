package com.trips_enjoy.search

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<SearchServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}

package com.trips_enjoy.ledger

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<LedgerServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}

package com.trips_enjoy.trip

import org.springframework.boot.fromApplication


fun main(args: Array<String>) {
	fromApplication<TripServiceApplication>().run(*args)
}
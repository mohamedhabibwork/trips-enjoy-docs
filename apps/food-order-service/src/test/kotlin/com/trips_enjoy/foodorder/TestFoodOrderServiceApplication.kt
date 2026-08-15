package com.trips_enjoy.foodorder

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<FoodOrderServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}

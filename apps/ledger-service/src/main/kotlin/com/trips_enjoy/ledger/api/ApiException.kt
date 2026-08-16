package com.trips_enjoy.ledger.api

import org.springframework.http.HttpStatus

/** Thin wrapper over RuntimeException carrying the HTTP status + envelope code. */
class ApiException(
    val status: HttpStatus,
    val code: String,
    detail: String,
) : RuntimeException(detail)

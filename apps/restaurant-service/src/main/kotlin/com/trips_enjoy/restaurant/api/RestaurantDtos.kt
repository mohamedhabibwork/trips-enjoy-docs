package com.trips_enjoy.restaurant.api

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

data class CreateRestaurantRequest(
    @field:NotBlank val merchantId: String,
    @field:NotBlank @field:Size(min = 1, max = 120) val name: String,
    @field:NotBlank @field:Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])?$") val slug: String,
    @field:NotBlank @field:Pattern(regexp = "^(restaurant|cafe|bakery|cloud_kitchen|food_truck|other)$") val type: String,
    val description: String? = null,
    val correlationId: String? = null,
) {
    fun merchantIdAsUuid(): UUID = UUID.fromString(merchantId)
}

data class RestaurantResponse(
    val restaurantId: String,
    val merchantId: String,
    val name: String,
    val slug: String,
    val type: String,
    val state: String,
    val online: Boolean,
    val avgRating: Double,
    val reviewCount: Int,
)

data class UpdateRestaurantRequest(
    @field:Size(min = 1, max = 120) val name: String? = null,
    val description: String? = null,
    val logoFileId: String? = null,
    val coverFileId: String? = null,
    val autoOfflineEnabled: Boolean? = null,
)

data class RejectRequest(@field:NotBlank val reason: String)
data class SuspendRequest(@field:NotBlank val reason: String)

data class AddCuisineRequest(@field:NotBlank @field:Size(max = 50) val cuisine: String)
data class AddTagRequest(@field:NotBlank @field:Size(max = 50) val tag: String)

data class RatingRequest(
    @field:Min(1) val rating: Int,
    val comment: String? = null,
) {
    fun ratingAsBigDecimal(): BigDecimal = BigDecimal(rating)
}
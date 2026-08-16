package com.trips_enjoy.platform.lookup

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

interface LookupRepository : JpaRepository<Lookup, UUID> {
    fun findByLookupTypeIdAndIsDeletedFalse(lookupTypeId: UUID): List<Lookup>
    fun findByLookupTypeIdAndIsPublicTrueAndIsDeletedFalse(lookupTypeId: UUID): List<Lookup>
}

interface LookupTypeRepository : JpaRepository<LookupType, UUID> {
    fun findByCode(code: String): LookupType?
}

@RestController
@RequestMapping("/v1/lookups")
class LookupPublicController(
    private val lookupTypeRepository: LookupTypeRepository,
    private val lookupRepository: LookupRepository,
) {
    @GetMapping("/{typeCode}/values")
    fun getValuesByTypeCode(@PathVariable typeCode: String): ResponseEntity<List<Lookup>> {
        val type = lookupTypeRepository.findByCode(typeCode)
            ?: return ResponseEntity.notFound().build()
        val values = lookupRepository.findByLookupTypeIdAndIsPublicTrueAndIsDeletedFalse(type.id!!)
        return ResponseEntity.ok(values)
    }
}

@RestController
@RequestMapping("/admin/v1/lookups")
class LookupAdminController(
    private val lookupTypeRepository: LookupTypeRepository,
    private val lookupRepository: LookupRepository,
) {
    @GetMapping("/types")
    fun listTypes(): ResponseEntity<List<LookupType>> =
        ResponseEntity.ok(lookupTypeRepository.findAll())

    @GetMapping("/types/{typeCode}/values")
    fun listValues(@PathVariable typeCode: String): ResponseEntity<List<Lookup>> {
        val type = lookupTypeRepository.findByCode(typeCode)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(lookupRepository.findByLookupTypeIdAndIsDeletedFalse(type.id!!))
    }
}

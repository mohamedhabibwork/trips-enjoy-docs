package com.trips_enjoy.ledger.domain

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Chart-of-accounts repository.
 */
@Repository
interface AccountRepository : JpaRepository<Account, UUID> {
    fun findByCode(code: String): Optional<Account>
    fun findByCodeAndValidToIsNull(code: String): Optional<Account>
    fun findAllByValidToIsNullOrderByCode(): List<Account>

    /**
     * Lock the current version of an account row at write time. Per SRS §14
     * a row-level lock serialises balance updates for the same account.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.code = :code and a.validTo is null")
    fun lockCurrentByCode(@Param("code") code: String): Optional<Account>
}

@Repository
interface PostingRepository : JpaRepository<Posting, Posting.Pk> {
    fun findByIdempotencyKey(key: String): Optional<Posting>
    fun findAllByIdempotencyKeyIn(keys: Collection<String>): List<Posting>

    /**
     * Convenience finder that takes only the UUID and looks up the row by
     * id. Returns the most recent row matching the id (the id is unique
     * because the partition key is `posted_at`, not `id`).
     */
    @Query("select p from Posting p where p.id = :id order by p.postedAt desc")
    fun findLatestById(@Param("id") id: UUID): List<Posting>

    /**
     * List postings filtered by account (joins via posting_entries) and an
     * optional date range. Cursor-paginated.
     */
    @Query(
        """
        select distinct p from Posting p
        where (:accountCode is null
               or exists (select 1 from PostingEntry e
                          where e.postingId = p.id
                            and e.accountCode = :accountCode))
          and (:from is null or p.postedAt >= :from)
          and (:to is null or p.postedAt <= :to)
        order by p.postedAt desc, p.id desc
        """,
    )
    fun search(
        @Param("accountCode") accountCode: String?,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        pageable: Pageable,
    ): List<Posting>
}

@Repository
interface PostingEntryRepository : JpaRepository<PostingEntry, PostingEntry.Pk> {
    fun findAllByPostingId(postingId: UUID): List<PostingEntry>

    /**
     * Aggregate debits and credits for one account within a window. Used by
     * the balance-over-range endpoint (INTEGRATION §1.8).
     */
    @Query(
        """
        select coalesce(sum(case when e.side = 'debit'  then e.amountMinor else 0 end), 0),
               coalesce(sum(case when e.side = 'credit' then e.amountMinor else 0 end), 0)
          from PostingEntry e
         where e.accountCode = :accountCode
           and (:from is null or e.postedAt >= :from)
           and (:to   is null or e.postedAt <= :to)
        """,
    )
    fun sumForAccount(
        @Param("accountCode") accountCode: String,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
    ): Array<Long>

    /** Trial balance aggregates — GROUP BY account. */
    @Query(
        """
        select e.accountCode,
               coalesce(sum(case when e.side = 'debit'  then e.amountMinor else 0 end), 0),
               coalesce(sum(case when e.side = 'credit' then e.amountMinor else 0 end), 0)
          from PostingEntry e
         where (:to is null or e.postedAt < :to)
         group by e.accountCode
        """,
    )
    fun aggregateByAccountUpTo(@Param("to") to: Instant?): List<Array<Any>>

    /** Income statement: revenue + expense aggregates over a window. */
    @Query(
        """
        select e.accountCode,
               coalesce(sum(case when e.side = 'credit' then e.amountMinor else 0 end), 0)
                 - coalesce(sum(case when e.side = 'debit'  then e.amountMinor else 0 end), 0)
          from PostingEntry e
         where e.postedAt >= :from
           and e.postedAt <  :to
           and exists (select 1 from Account a
                       where a.code = e.accountCode
                         and a.type in ('revenue','expense')
                         and a.validTo is null)
         group by e.accountCode
        """,
    )
    fun incomeStatementAggregates(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Array<Any>>

    /** Ledger total per account_type, used by reconciliation. */
    @Query(
        """
        select a.type,
               coalesce(sum(case when e.side = 'debit'  then e.amountMinor else 0 end), 0),
               coalesce(sum(case when e.side = 'credit' then e.amountMinor else 0 end), 0)
          from PostingEntry e
          join Account a on a.code = e.accountCode and a.validTo is null
         where e.postedAt >= :from and e.postedAt < :to
         group by a.type
        """,
    )
    fun totalsByAccountType(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Array<Any>>
}

@Repository
interface AccountBalanceRepository : JpaRepository<AccountBalance, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    override fun findById(id: String): Optional<AccountBalance>
}

@Repository
interface JournalEntryRepository : JpaRepository<JournalEntry, UUID> {
    fun findAllByActorIdOrderByCreatedAtDesc(actorId: UUID, pageable: Pageable): List<JournalEntry>
}

@Repository
interface ReconciliationRunRepository : JpaRepository<ReconciliationRun, UUID> {
    fun findByRunDate(runDate: java.time.LocalDate): Optional<ReconciliationRun>
    fun findFirstByOrderByRunDateDesc(): ReconciliationRun?
}

@Repository
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
    @Query("select o from OutboxEvent o where o.publishedAt is null order by o.createdAt asc")
    fun findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(pageable: Pageable): List<OutboxEvent>

    fun findAllByTopicAndAggregateId(topic: String, aggregateId: UUID): List<OutboxEvent>
}

@Repository
interface InboxEventRepository : JpaRepository<InboxEvent, UUID> {
    fun existsByEventId(eventId: UUID): Boolean

    @Modifying
    @Query("delete from InboxEvent i where i.receivedAt < :cutoff")
    fun deleteAllByReceivedAtBefore(@Param("cutoff") cutoff: Instant): Long
}

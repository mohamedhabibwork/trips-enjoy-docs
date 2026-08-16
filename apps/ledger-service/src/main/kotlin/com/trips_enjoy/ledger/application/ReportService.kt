package com.trips_enjoy.ledger.application

import com.trips_enjoy.ledger.api.BalanceResponse
import com.trips_enjoy.ledger.api.BalanceSheetResponse
import com.trips_enjoy.ledger.api.BalanceSheetTotals
import com.trips_enjoy.ledger.api.IncomeStatementResponse
import com.trips_enjoy.ledger.api.IncomeStatementTotals
import com.trips_enjoy.ledger.api.ReportLine
import com.trips_enjoy.ledger.api.TrialBalanceLine
import com.trips_enjoy.ledger.api.TrialBalanceResponse
import com.trips_enjoy.ledger.api.TrialBalanceTotals
import com.trips_enjoy.ledger.domain.AccountBalanceRepository
import com.trips_enjoy.ledger.domain.AccountRepository
import com.trips_enjoy.ledger.domain.PostingEntryRepository
import com.trips_enjoy.ledger.domain.PostingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Read models for the report endpoints (INTEGRATION §1.7, §1.9–§1.11).
 *
 * Trial balance and balance sheet are computed directly from
 * `posting_entries` (no cached state) so the report reflects every committed
 * posting. The materialised `account_balances` table backs the per-account
 * balance endpoint only.
 */
@Service
class ReportService(
    private val accountRepository: AccountRepository,
    private val entryRepository: PostingEntryRepository,
    private val postingRepository: PostingRepository,
    private val balanceRepository: AccountBalanceRepository,
) {

    @Transactional(readOnly = true)
    fun currentBalance(accountCode: String): BalanceResponse {
        val balance = balanceRepository.findById(accountCode).orElse(null)
            ?: return BalanceResponse(
                account_code = accountCode,
                currency = "",
                debit_total_minor = 0,
                credit_total_minor = 0,
                balance_minor = 0,
                as_of = Instant.now(),
            )
        return BalanceResponse(
            account_code = accountCode,
            currency = balance.currency,
            debit_total_minor = balance.debitTotalMinor,
            credit_total_minor = balance.creditTotalMinor,
            balance_minor = balance.balanceMinor,
            as_of = Instant.now(),
        )
    }

    @Transactional(readOnly = true)
    fun balanceOverRange(accountCode: String, from: Instant?, to: Instant?): BalanceResponse {
        val sums = entryRepository.sumForAccount(accountCode, from, to)
        val debit = sums.getOrNull(0) ?: 0L
        val credit = sums.getOrNull(1) ?: 0L
        return BalanceResponse(
            account_code = accountCode,
            currency = "",
            debit_total_minor = debit,
            credit_total_minor = credit,
            balance_minor = debit - credit,
            as_of = null,
            from = from,
            to = to,
        )
    }

    @Transactional(readOnly = true)
    fun trialBalance(date: LocalDate): TrialBalanceResponse {
        val asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val rows = entryRepository.aggregateByAccountUpTo(asOf.plusSeconds(86400))
        val accountsByCode = accountRepository.findAllByValidToIsNullOrderByCode()
            .associateBy { it.code }
        val lines = rows.map { row ->
            val code = row[0] as String
            val debit = (row[1] as Number).toLong()
            val credit = (row[2] as Number).toLong()
            val account = accountsByCode[code]
            TrialBalanceLine(
                code = code,
                name = account?.name ?: code,
                type = account?.type ?: "unknown",
                currency = account?.currency ?: "",
                debit_minor = debit,
                credit_minor = credit,
                balance_minor = debit - credit,
            )
        }
        val totalDebit = lines.sumOf { it.debit_minor }
        val totalCredit = lines.sumOf { it.credit_minor }
        return TrialBalanceResponse(
            as_of = asOf,
            currency = "EUR",
            accounts = lines,
            totals = TrialBalanceTotals(
                debit_minor = totalDebit,
                credit_minor = totalCredit,
                balanced = totalDebit == totalCredit,
                drift_minor = totalDebit - totalCredit,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun balanceSheet(date: LocalDate): BalanceSheetResponse {
        val asOf = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val rows = entryRepository.aggregateByAccountUpTo(asOf.plusSeconds(86400))
        val accountsByCode = accountRepository.findAllByValidToIsNullOrderByCode()
            .associateBy { it.code }
        val assets = mutableListOf<ReportLine>()
        val liabilities = mutableListOf<ReportLine>()
        val equity = mutableListOf<ReportLine>()
        rows.forEach { row ->
            val code = row[0] as String
            val debit = (row[1] as Number).toLong()
            val credit = (row[2] as Number).toLong()
            val account = accountsByCode[code] ?: return@forEach
            val balance = when (account.type) {
                "asset", "expense" -> debit - credit
                "liability", "equity", "revenue" -> credit - debit
                else -> debit - credit
            }
            val line = ReportLine(code, balance)
            when (account.type) {
                "asset" -> assets.add(line)
                "liability" -> liabilities.add(line)
                "equity" -> equity.add(line)
                else -> { /* revenue/expense live in the income statement */ }
            }
        }
        val assetsTotal = assets.sumOf { it.balance_minor }
        val liabilitiesTotal = liabilities.sumOf { it.balance_minor }
        val equityTotal = equity.sumOf { it.balance_minor }
        return BalanceSheetResponse(
            as_of = asOf,
            assets = assets,
            liabilities = liabilities,
            equity = equity,
            totals = BalanceSheetTotals(
                assets_minor = assetsTotal,
                liabilities_plus_equity_minor = liabilitiesTotal + equityTotal,
                balanced = assetsTotal == liabilitiesTotal + equityTotal,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun incomeStatement(from: Instant, to: Instant): IncomeStatementResponse {
        val rows = entryRepository.incomeStatementAggregates(from, to)
        val accountsByCode = accountRepository.findAllByValidToIsNullOrderByCode()
            .associateBy { it.code }
        val revenue = mutableListOf<ReportLine>()
        val expenses = mutableListOf<ReportLine>()
        rows.forEach { row ->
            val code = row[0] as String
            val net = (row[1] as Number).toLong()
            val account = accountsByCode[code] ?: return@forEach
            val line = ReportLine(code, net)
            when (account.type) {
                "revenue" -> revenue.add(line)
                "expense" -> expenses.add(line)
                else -> { /* ignore */ }
            }
        }
        val revenueTotal = revenue.sumOf { it.balance_minor }
        val expenseTotal = expenses.sumOf { it.balance_minor }
        return IncomeStatementResponse(
            from = from,
            to = to,
            currency = "EUR",
            revenue = revenue,
            expenses = expenses,
            totals = IncomeStatementTotals(
                revenue_minor = revenueTotal,
                expense_minor = expenseTotal,
                net_income_minor = revenueTotal - expenseTotal,
            ),
        )
    }
}

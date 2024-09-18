package build.wallet.partnerships

import app.cash.sqldelight.coroutines.asFlow
import build.wallet.database.BitkeyDatabaseProvider
import build.wallet.db.DbTransactionError
import build.wallet.sqldelight.awaitTransaction
import build.wallet.sqldelight.awaitTransactionWithResult
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PartnershipTransactionsDaoImpl(
  bitkeyDatabaseProvider: BitkeyDatabaseProvider,
) : PartnershipTransactionsDao {
  private val database by lazy { bitkeyDatabaseProvider.database() }
  private val queries by lazy { database.partnershipTransactionsQueries }

  override suspend fun save(
    transaction: PartnershipTransaction,
  ): Result<Unit, DbTransactionError> {
    return database.awaitTransaction {
      queries.saveEntity(transaction.toEntity())
    }
  }

  override fun getTransactions(): Flow<Result<List<PartnershipTransaction>, DbTransactionError>> {
    return queries.getAll()
      .asFlow()
      .map { query ->
        database.awaitTransactionWithResult {
          query.executeAsList().map { it.toModel() }
        }
      }
  }

  override fun getPreviouslyUsedPartnerIds(): Flow<Result<List<PartnerId>, DbTransactionError>> {
    return queries.getPreviouslyUsedPartnerIds()
      .asFlow()
      .map { query ->
        database.awaitTransactionWithResult {
          query.executeAsList()
        }
      }
  }

  override suspend fun getMostRecentByPartner(
    partnerId: PartnerId,
  ): Result<PartnershipTransaction?, DbTransactionError> {
    return database.awaitTransactionWithResult {
      queries.getMostRecentTransactionByPartnerId(partnerId)
        .executeAsOneOrNull()
        ?.toModel()
    }
  }

  override suspend fun getById(
    id: PartnershipTransactionId,
  ): Result<PartnershipTransaction?, DbTransactionError> {
    return database.awaitTransactionWithResult {
      queries.getById(id)
        .executeAsOneOrNull()
        ?.toModel()
    }
  }

  override suspend fun deleteTransaction(
    transactionId: PartnershipTransactionId,
  ): Result<Unit, DbTransactionError> {
    return database.awaitTransaction {
      queries.delete(transactionId)
    }
  }

  override suspend fun clear(): Result<Unit, DbTransactionError> {
    return database.awaitTransaction {
      queries.clear()
    }
  }
}

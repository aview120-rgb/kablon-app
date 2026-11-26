package com.autoagent.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY triggerAt")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE enabled = 1 ORDER BY triggerAt")
    suspend fun enabled(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: Long): AlarmEntity?

    @Insert
    suspend fun insert(alarm: AlarmEntity): Long

    @Update
    suspend fun update(alarm: AlarmEntity)

    @Delete
    suspend fun delete(alarm: AlarmEntity)
}

@Dao
interface SendDao {
    @Query("SELECT * FROM scheduled_sends ORDER BY sendAt DESC")
    fun observeAll(): Flow<List<ScheduledSend>>

    @Query("SELECT * FROM scheduled_sends WHERE status = 'PENDING' ORDER BY sendAt")
    suspend fun pending(): List<ScheduledSend>

    @Query("SELECT COUNT(*) FROM scheduled_sends WHERE createdAt >= :since")
    suspend fun createdSince(since: Long): Int

    @Query("SELECT * FROM scheduled_sends WHERE id = :id")
    suspend fun get(id: Long): ScheduledSend?

    @Insert
    suspend fun insert(send: ScheduledSend): Long

    @Update
    suspend fun update(send: ScheduledSend)

    @Query("UPDATE scheduled_sends SET status = :status, resultNote = :note WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, note: String)

    @Delete
    suspend fun delete(send: ScheduledSend)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM keyword_rules ORDER BY priority DESC, id")
    fun observeAll(): Flow<List<KeywordRule>>

    @Query("SELECT * FROM keyword_rules WHERE enabled = 1 ORDER BY priority DESC, id")
    suspend fun enabled(): List<KeywordRule>

    @Query("SELECT COUNT(*) FROM keyword_rules")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(rules: List<KeywordRule>)

    @Insert
    suspend fun insert(rule: KeywordRule): Long

    @Update
    suspend fun update(rule: KeywordRule)

    @Delete
    suspend fun delete(rule: KeywordRule)
}

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallRecord>>

    @Insert
    suspend fun insert(record: CallRecord): Long

    @Update
    suspend fun update(record: CallRecord)

    @Delete
    suspend fun delete(record: CallRecord)
}

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox ORDER BY postedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<InboxEntry>>

    @Query("SELECT * FROM inbox WHERE postedAt >= :since ORDER BY postedAt")
    suspend fun since(since: Long): List<InboxEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: InboxEntry): Long

    @Query("DELETE FROM inbox")
    suspend fun clear()

    @Query("DELETE FROM inbox WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Receipt>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: Receipt): Long

    @Query("SELECT COUNT(*) FROM receipts WHERE sourceRef = :ref")
    suspend fun countRef(ref: String): Int

    @Update
    suspend fun update(receipt: Receipt)

    @Delete
    suspend fun delete(receipt: Receipt)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM action_log ORDER BY at DESC LIMIT 200")
    fun observeRecent(): Flow<List<ActionLog>>

    @Insert
    suspend fun insert(log: ActionLog)
}

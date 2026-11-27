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

    @Query("SELECT * FROM scheduled_sends WHERE status = :status ORDER BY sendAt")
    suspend fun byStatus(status: String): List<ScheduledSend>

    @Query("SELECT COUNT(*) FROM scheduled_sends WHERE createdAt >= :since")
    suspend fun createdSince(since: Long): Int

    @Query("SELECT * FROM scheduled_sends WHERE id = :id")
    suspend fun get(id: Long): ScheduledSend?

    @Insert
    suspend fun insert(send: ScheduledSend): Long

    @Update
    suspend fun update(send: ScheduledSend)

    @Query("SELECT * FROM scheduled_sends WHERE remoteTaskId = :taskId LIMIT 1")
    suspend fun byRemoteTask(taskId: String): ScheduledSend?

    @Query("SELECT * FROM scheduled_sends WHERE status IN ('PENDING','RUNNING','WAITING_UNLOCK','WAITING_SERVICE')")
    fun observeActive(): Flow<List<ScheduledSend>>

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

    @Query("DELETE FROM action_log WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Insert
    suspend fun insert(log: ActionLog)
}

@Dao
interface RemoteTaskDao {
    @Query("SELECT * FROM remote_tasks ORDER BY executeAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<RemoteTask>>

    @Query("SELECT * FROM remote_tasks WHERE id = :id")
    suspend fun get(id: String): RemoteTask?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(task: RemoteTask): Long

    @Update
    suspend fun update(task: RemoteTask)

    @Query("UPDATE remote_tasks SET state = :state, note = :note, updatedAt = :at WHERE id = :id")
    suspend fun setState(id: String, state: String, note: String, at: Long = System.currentTimeMillis())

    /** Tasks whose storage the server has not confirmed yet. */
    @Query("SELECT id FROM remote_tasks WHERE ackSent = 0")
    suspend fun unacked(): List<String>

    @Query("UPDATE remote_tasks SET ackSent = 1 WHERE id IN (:ids)")
    suspend fun markAcked(ids: List<String>)

    /** Final outcomes not yet accepted by the server. */
    @Query("SELECT * FROM remote_tasks WHERE reported = 0 AND state IN ('DONE','FAILED','EXPIRED')")
    suspend fun unreported(): List<RemoteTask>

    @Query("UPDATE remote_tasks SET reported = 1 WHERE id IN (:ids)")
    suspend fun markReported(ids: List<String>)

    @Query("SELECT * FROM remote_tasks WHERE state IN ('RECEIVED','SCHEDULED')")
    suspend fun open(): List<RemoteTask>

    @Query("DELETE FROM remote_tasks WHERE reported = 1 AND updatedAt < :before")
    suspend fun pruneReported(before: Long)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY id LIMIT :limit")
    suspend fun batch(limit: Int): List<OutboxItem>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: OutboxItem): Long

    @Query("DELETE FROM outbox WHERE clientId IN (:clientIds)")
    suspend fun deleteAccepted(clientIds: List<String>)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id IN (:ids)")
    suspend fun bumpAttempts(ids: List<Long>)

    /** Keeps the outbox bounded if the phone stays offline for a very long time (oldest dropped first). */
    @Query("DELETE FROM outbox WHERE id NOT IN (SELECT id FROM outbox ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

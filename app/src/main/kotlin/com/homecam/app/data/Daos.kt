package com.homecam.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ViewerDao {
    @Upsert suspend fun upsert(viewer: ViewerEntity)
    @Query("SELECT * FROM viewers ORDER BY pairedAtMs") fun observeAll(): Flow<List<ViewerEntity>>
    @Query("SELECT * FROM viewers ORDER BY pairedAtMs") suspend fun all(): List<ViewerEntity>
    @Query("SELECT * FROM viewers WHERE fingerprint = :fingerprint") suspend fun byFingerprint(fingerprint: String): ViewerEntity?
    @Query("SELECT COUNT(*) FROM viewers") fun observeCount(): Flow<Int>
    @Query("DELETE FROM viewers WHERE fingerprint = :fingerprint") suspend fun delete(fingerprint: String)
    @Query("DELETE FROM viewers") suspend fun deleteAll()
    @Query("UPDATE viewers SET lastSeenMs = :atMs WHERE fingerprint = :fingerprint") suspend fun touch(fingerprint: String, atMs: Long)
}

@Dao
interface CameraDao {
    @Upsert suspend fun upsert(camera: CameraEntity)
    @Query("SELECT * FROM cameras ORDER BY pairedAtMs") fun observeAll(): Flow<List<CameraEntity>>
    @Query("SELECT * FROM cameras ORDER BY pairedAtMs") suspend fun all(): List<CameraEntity>
    @Query("SELECT * FROM cameras WHERE fingerprint = :fingerprint") suspend fun byFingerprint(fingerprint: String): CameraEntity?
    @Query("DELETE FROM cameras WHERE fingerprint = :fingerprint") suspend fun delete(fingerprint: String)
    @Query("UPDATE cameras SET lastHost = :host, lastPort = :port, lastSeenMs = :atMs WHERE fingerprint = :fingerprint")
    suspend fun touch(fingerprint: String, host: String, port: Int, atMs: Long)
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entry: AuditEntity)
    @Query("SELECT * FROM audit ORDER BY seq DESC LIMIT 1") suspend fun last(): AuditEntity?
    @Query("SELECT * FROM audit ORDER BY seq") suspend fun all(): List<AuditEntity>
    @Query("SELECT * FROM audit ORDER BY seq DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit WHERE atMs >= :sinceMs AND atMs <= :untilMs ORDER BY seq DESC LIMIT :limit")
    suspend fun between(sinceMs: Long, untilMs: Long, limit: Int): List<AuditEntity>
    @Query("SELECT COUNT(*) FROM audit WHERE viewerFingerprint = :fingerprint AND kind = :kind AND atMs >= :sinceMs")
    suspend fun countOf(fingerprint: String, kind: Int, sinceMs: Long): Int
}

@Dao
interface ClipDao {
    @Upsert suspend fun upsert(clip: ClipEntity)
    @Query("SELECT * FROM clips ORDER BY startedAtMs DESC") fun observeAll(): Flow<List<ClipEntity>>
    @Query("SELECT * FROM clips ORDER BY startedAtMs") suspend fun all(): List<ClipEntity>
    @Query("SELECT * FROM clips WHERE id = :id") suspend fun byId(id: String): ClipEntity?
    @Query("SELECT * FROM clips WHERE startedAtMs BETWEEN :sinceMs AND :untilMs ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun between(sinceMs: Long, untilMs: Long, limit: Int): List<ClipEntity>
    @Query("DELETE FROM clips WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE clips SET pinned = :pinned WHERE id = :id") suspend fun setPinned(id: String, pinned: Boolean)
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM clips") fun observeTotalBytes(): Flow<Long>
}

package com.example.multifoldercamera.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderEntryDao {

    @Query("SELECT * FROM folder_entries ORDER BY createdAt DESC")
    fun getAll(): Flow<List<FolderEntry>>

    @Query("SELECT * FROM folder_entries WHERE id = :id")
    suspend fun getById(id: Long): FolderEntry?

    @Insert
    suspend fun insert(entry: FolderEntry): Long

    @Update
    suspend fun update(entry: FolderEntry)

    @Delete
    suspend fun delete(entry: FolderEntry)
}

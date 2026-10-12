package com.example.multifoldercamera.data

import kotlinx.coroutines.flow.Flow

class FolderRepository(private val dao: FolderEntryDao) {

    val allEntries: Flow<List<FolderEntry>> = dao.getAll()

    suspend fun getById(id: Long): FolderEntry? = dao.getById(id)

    suspend fun insert(entry: FolderEntry): Long = dao.insert(entry)

    suspend fun update(entry: FolderEntry) = dao.update(entry)

    suspend fun delete(entry: FolderEntry) = dao.delete(entry)
}

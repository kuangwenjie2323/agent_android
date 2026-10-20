package com.karewinkcloud.agentweb.client.ui

import com.karewinkcloud.agentweb.client.core.MemoryOverview
import com.karewinkcloud.agentweb.client.data.AgentRepository

/**
 * The agents' shared memory, as the memory page shows it: read the overview and a file, or ask the
 * server to consolidate it. The repository is read per call, so switching servers uses the new one.
 */
internal class MemoryController(private val repository: () -> AgentRepository) {
    suspend fun memory(): MemoryOverview = repository().memory()
    suspend fun memoryFile(file: String): String = repository().memoryFile(file)
    suspend fun consolidateMemory() = repository().consolidateMemory()
}

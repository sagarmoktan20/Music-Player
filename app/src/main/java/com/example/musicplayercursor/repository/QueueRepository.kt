package com.example.musicplayercursor.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object QueueRepository {
    private val _queue = MutableStateFlow<List<Long>>(emptyList())
    val queue: StateFlow<List<Long>> = _queue.asStateFlow()

    private val _currentSongId = MutableStateFlow<Long?>(null)
    val currentSongId: StateFlow<Long?> = _currentSongId.asStateFlow()

    fun setQueue(newQueue: List<Long>) {
        _queue.value = newQueue
    }

    fun setCurrentSongId(id: Long?) {
        _currentSongId.value = id
    }

    fun getNextSongId(currentId: Long, isShuffle: Boolean = false): Long? {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return null

        val index = currentQueue.indexOf(currentId)
        if (index == -1) {
            // Current song not in queue, maybe play first?
            return currentQueue.firstOrNull()
        }

        // Simple next logic (looping can be handled here or in service)
        // Let's assume linear playback for now, service handles looping if needed or we return null
        return if (index < currentQueue.size - 1) {
            currentQueue[index + 1]
        } else {
            // End of queue
            if (currentQueue.isNotEmpty()) currentQueue[0] else null // Loop back to start
        }
    }

    fun getPreviousSongId(currentId: Long): Long? {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return null

        val index = currentQueue.indexOf(currentId)
        if (index == -1) return null

        return if (index > 0) {
            currentQueue[index - 1]
        } else {
            // At start
             if (currentQueue.isNotEmpty()) currentQueue[currentQueue.size - 1] else null // Loop back to end
        }
    }
    
    fun isQueueEmpty() = _queue.value.isEmpty()
}
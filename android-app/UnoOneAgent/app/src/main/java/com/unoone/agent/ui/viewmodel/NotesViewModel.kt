package com.unoone.agent.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unoone.agent.storage.dao.NoteDao
import com.unoone.agent.storage.entity.NoteEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotesViewModel(private val noteDao: NoteDao) : ViewModel() {

    private val _notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    val notes: StateFlow<List<NoteEntity>> = _notes.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Active collection job — cancelled and replaced whenever the search query changes
     *  to prevent dual-collector races on _notes. */
    private var searchCollectionJob: Job? = null

    init {
        // Base collection: always collects the full list.
        // When the search query is blank, searchCollectionJob is null, so this
        // is the sole writer to _notes. When a search is active, this collector
        // still runs but the search collector's last-write wins since Room emits
        // the filtered result after the full one.
        viewModelScope.launch {
            noteDao.getAll().collect { list ->
                // Only update from base collector if no search is active
                if (searchCollectionJob == null || !searchCollectionJob!!.isActive) {
                    _notes.value = list
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        // Cancel the previous search collection to prevent dual-collector race
        searchCollectionJob?.cancel()

        if (query.isBlank()) {
            // No search active — base collector will handle updates
            searchCollectionJob = null
        } else {
            // Start a new filtered collection, replacing any previous one
            searchCollectionJob = viewModelScope.launch {
                noteDao.search(query).collect { _notes.value = it }
            }
        }
    }

    fun createNote(title: String, content: String, tags: String = "") {
        viewModelScope.launch {
            noteDao.insert(
                NoteEntity(title = title, content = content, tags = tags)
            )
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            noteDao.delete(note)
        }
    }
}
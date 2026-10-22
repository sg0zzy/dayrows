// SPDX-License-Identifier: GPL-3.0-or-later
package it.stefanogozzi.dayrows.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import it.stefanogozzi.dayrows.data.*
import it.stefanogozzi.dayrows.domain.*
import java.time.*

enum class LoadError { CALENDARS, PREFERENCES }
data class CalendarUiState(
    val calendars: List<DeviceCalendar> = emptyList(),
    val events: List<EventInstance> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val preferencesReady: Boolean = false,
    val start: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val loading: Boolean = false,
    val discovered: Boolean = false,
    val error: LoadError? = null,
    val searching: Boolean = false,
    val syncing: Boolean = false,
    val syncResult: CalendarSyncResult? = null,
    val newCalendars: Int? = null,
    /** The provider accounts available for synchronization, with their visibility state. */
    val syncAccounts: List<SyncAccountChoice> = emptyList(),
) {
    val matrix: CalendarMatrix get() = CalendarMatrixBuilder().build(calendars, events,
        visibleDates(start, preferences.dayCount), preferences, zone)
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class CalendarViewModel(private val repository: CalendarRepository, private val preferences: PreferencesRepository) : ViewModel() {
    private val _state = MutableStateFlow(CalendarUiState())
    val state = _state.asStateFlow()
    private val permission = MutableStateFlow(false)
    private val start = MutableStateFlow(LocalDate.now())
    private val refreshes = MutableStateFlow(0L)
    private val settings = preferences.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    private var discovery: Job? = null
    private var syncJob: Job? = null
    private var baseline: Set<String>? = null

    init {
        viewModelScope.launch {
            preferences.preferences.catch { _state.update { it.copy(error = LoadError.PREFERENCES) } }.collect { stored ->
                _state.update { it.copy(preferences = stored, preferencesReady = true) }
            }
        }
        viewModelScope.launch {
            permission.flatMapLatest { allowed -> if (allowed) repository.changes.catch { _state.update { it.copy(error = LoadError.CALENDARS) } } else emptyFlow() }
                .debounce(250).catch { _state.update { it.copy(error = LoadError.CALENDARS) } }
                .collect { if (!state.value.syncing) refresh() }
        }
        viewModelScope.launch {
            val querySettings = settings.map { prefs -> prefs.dayCount to prefs.calendars.filter { it.selected }.map { it.key }.toSet() }.distinctUntilChanged()
            combine(permission, start, querySettings, refreshes) { allowed, date, config, _ -> Triple(allowed, date, config) }
                .collectLatest { (allowed, date, config) ->
                    if (!allowed) {
                        _state.update { it.copy(calendars = emptyList(), events = emptyList(), discovered = false, loading = false) }
                        return@collectLatest
                    }
                    val zone = ZoneId.systemDefault()
                    _state.update { it.copy(start = date, zone = zone, loading = true, error = null) }
                    try {
                        val calendars = repository.calendars()
                        preferences.discover(calendars)
                        val selectedIds = calendars.filter { it.key in config.second }.map { it.id }.toSet()
                        val events = repository.instances(selectedIds, date, date.plusDays(config.first.toLong()), zone)
                        ensureActive()
                        _state.update { old ->
                            val changedColors = calendars.count { fresh ->
                                old.calendars.any { it.id == fresh.id && it.color != fresh.color }
                            }
                            if (changedColors > 0) calendarDebug("DayRows colors updated: $changedColors calendars")
                            old.copy(calendars = calendars, events = events, loading = false, discovered = true)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        _state.update { it.copy(loading = false, error = LoadError.CALENDARS) }
                    }
                }
        }
    }
    fun permission(granted: Boolean) {
        permission.value = granted
        if (!granted) {
            syncJob?.cancel()
            _state.update { it.copy(syncResult = null) }
        }
    }
    fun refresh() { refreshes.update { it + 1 } }
    fun syncNow() { synchronize() }

    /** Re-reads the local provider only: no sync request, no account prompt. This is the
     * main page's refresh. */
    fun refreshLight() { refresh() }

    /** Loads the provider's accounts and their visibility, for the hard-refresh account
     * selection dialog. */
    fun loadSyncAccounts() {
        viewModelScope.launch {
            try {
                val accounts = repository.syncAccounts()
                _state.update { it.copy(syncAccounts = accounts) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = LoadError.CALENDARS) } }
        }
    }

    /** A hard refresh: requests synchronization for the chosen accounts and reloads
     * calendars and events afterwards. Accounts that still need visibility are handled
     * by the caller before this is reached. */
    fun hardRefresh(accounts: List<SyncAccountChoice>) {
        if (accounts.isEmpty()) { discoverCalendars(true); return }
        syncAccountsFor(accounts.map { it.name to it.type })
    }

    /** Synchronizes the given accounts, then reloads metadata and events. */
    private fun syncAccountsFor(accounts: List<Pair<String, String>>) {
        if (!permission.value) return
        syncJob?.takeIf { it.isActive }?.let { return }
        _state.update { it.copy(syncing = true, syncResult = null) }
        syncJob = viewModelScope.launch {
            try {
                val result = repository.requestSync(accounts, manual = true)
                _state.update { it.copy(syncResult = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                calendarDebug("sync unavailable: ${e.javaClass.simpleName}")
                _state.update { it.copy(syncResult = CalendarSyncResult.UNAVAILABLE) }
            } finally {
                _state.update { it.copy(syncing = false) }
                // A completed sync can register new calendars, so metadata is read again
                // before the events are loaded.
                refresh()
            }
        }
    }

    /** All entry points join one job. Provider notifications during the wait are coalesced into
     * the final query, which reloads metadata AND events even if the adapter never notifies us. */
    private fun synchronize(): Job? {
        if (!permission.value) return null
        syncJob?.takeIf { it.isActive }?.let { return it }
        _state.update { it.copy(syncing = true, syncResult = null) }
        return viewModelScope.launch {
            try {
                val result = repository.requestSync(manual = true)
                _state.update { it.copy(syncResult = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                calendarDebug("sync unavailable: ${e.javaClass.simpleName}")
                _state.update { it.copy(syncResult = CalendarSyncResult.UNAVAILABLE) }
            } finally {
                _state.update { it.copy(syncing = false) }
                refresh()
            }
        }.also { syncJob = it }
    }

    /** Re-queries the calendar provider and merges every newly accessible calendar into the stored preferences.
     * Selections, labels and the manual order survive; new calendars arrive unchecked. Only an explicit
     * [announce], never the automatic search on entry, reports a count back to the interface. */
    fun discoverCalendars(announce: Boolean = true) {
        if (discovery?.isActive == true) return
        discovery = viewModelScope.launch {
            _state.update { it.copy(searching = true, newCalendars = null) }
            try {
                // A manual search joins the same sync as toolbar reload. Passive settings
                // entry also waits for an ongoing sync before reporting discovered metadata.
                if (announce) synchronize()?.join() else syncJob?.join()
                val previous = baseline ?: state.value.preferences.calendars.map { it.key }.toSet()
                val discovered = repository.calendars()
                baseline = discovered.map { it.key }.toSet()
                preferences.discover(discovered)
                refresh()
                if (announce) _state.update { it.copy(newCalendars = discovered.count { calendar -> calendar.key !in previous }) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = LoadError.CALENDARS) } }
            finally { _state.update { it.copy(searching = false) } }
        }
    }
    fun navigate(direction: Int) { start.update { it.plusDays((direction * state.value.preferences.dayCount).toLong()) } }
    fun today() { start.value = LocalDate.now(); refresh() }
    fun days(count: Int) = save { preferences.update { it.copy(dayCount = count) } }
    fun select(key: String, value: Boolean) = save { preferences.select(key, value) }
    fun label(key: String, value: String) = save { preferences.label(key, value) }
    fun move(key: String, other: String) = save { preferences.move(key, other) }
    fun finishSetup() = save { preferences.update { it.copy(configured = true) } }
    private fun save(action: suspend () -> Unit) { viewModelScope.launch {
        try { action() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = LoadError.PREFERENCES) } }
    } }
}

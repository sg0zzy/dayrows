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
    val newCalendars: Int? = null,
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
                .collect { refresh() }
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
                    _state.update { it.copy(start = date, zone = zone, loading = true, events = emptyList(), error = null) }
                    try {
                        val calendars = repository.calendars()
                        preferences.discover(calendars)
                        val selectedIds = calendars.filter { it.key in config.second }.map { it.id }.toSet()
                        val events = repository.instances(selectedIds, date, date.plusDays(config.first.toLong()), zone)
                        ensureActive()
                        _state.update { it.copy(calendars = calendars, events = events, loading = false, discovered = true) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        _state.update { it.copy(events = emptyList(), loading = false, error = LoadError.CALENDARS) }
                    }
                }
        }
    }
    fun permission(granted: Boolean) { permission.value = granted }
    fun refresh() { refreshes.update { it + 1 } }

    /** Asks every account behind the provider calendars to synchronize now (advisory: the platform
     * schedules and batches it, DayRows performs no network request itself), then re-queries immediately.
     * Rows that land later arrive through repository.changes and refresh the interface again. */
    fun syncNow() {
        viewModelScope.launch {
            try { repository.requestSync() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        refresh()
    }

    /** Re-queries the calendar provider and merges every newly accessible calendar into the stored preferences.
     * Selections, labels and the manual order survive; new calendars arrive unchecked. Only an explicit
     * [announce], never the automatic search on entry, reports a count back to the interface. */
    fun discoverCalendars(announce: Boolean = true) {
        if (discovery?.isActive == true) return
        discovery = viewModelScope.launch {
            _state.update { it.copy(searching = true, newCalendars = null) }
            try {
                // An explicitly tapped search also asks the owning accounts to sync so calendars they
                // have not registered on the device yet can arrive; the automatic search on entry stays passive.
                if (announce) try { repository.requestSync() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
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

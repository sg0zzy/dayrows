# DayRows Privacy Policy

Effective date: 22 October 2026. Applies to DayRows 0.1.2 (`it.stefanogozzi.dayrows`).

## Who provides DayRows

DayRows is an open-source project. For privacy questions, use the DayRows
project issue tracker: https://github.com/sg0zzy/dayrows/issues.
Issues are public: do not include calendar contents, account addresses, or other
private information. This policy covers the DayRows application; GitHub and any
app store have their own privacy policies for their services.

## What the app accesses and why

With your permission, DayRows reads Android's Calendar Provider to show calendars
in a calendar-by-day matrix. It accesses:

- Calendar IDs, names, account names/types, owner accounts, source identifiers,
  colors, access levels, visibility, and synchronization flags, to list and
  recognize calendars and show their original colors.
- Event IDs, calendar IDs, titles, occurrence start/end times, all-day flags, and
  time-zone information for the selected calendars and displayed date range.
  Android expands recurring events into occurrences.

Calendar/account information and event titles can contain personal or sensitive
information. DayRows uses these fields for the calendar view and its local
configuration, not for advertising, profiling, or analytics. It does not query
attendee lists, event descriptions, or location fields.

## Permissions and your choices

The only runtime permission requested is `READ_CALENDAR`. DayRows explains this
before asking. You can deny it or revoke it in Android's app permission settings;
the calendar view will then be unavailable. DayRows never requests
`WRITE_CALENDAR`, never inserts, updates or deletes provider calendars or events
itself, and has no `INTERNET` permission. AndroidX also declares an app-private
signature permission for internal broadcast receiver safety; it grants no access
to your calendar or network.

When you tap the + next to a day in the matrix, DayRows asks an installed calendar app
to create a new event. That request contains the selected day, that day's start and end
time, and nothing else: it deliberately names no calendar, because the receiving app
decides which of your calendars to offer and may keep its own default whatever a third
party asks for. The calendar app performs the insertion under its own permissions and
privacy policy, and DayRows never receives the resulting event or learns which calendar
you chose.

DayRows also declares the install-time permissions `READ_SYNC_SETTINGS` and
`READ_SYNC_STATS` to respect disabled account synchronization and observe calendar
sync activity. The main page's refresh button only re-reads the data the Calendar
Provider already holds on the device; it requests no synchronization. The
Settings refresh button additionally asks you which accounts to synchronize —
one account or several — and may ask the device's existing calendar sync adapters
to synchronize. Those account apps perform any network
transfer under their own permissions and policies. DayRows checks which accounts
of the calendar account types are visible through AccountManager. If access is
missing, an optional Android account chooser lets you grant visibility for an
existing account, one account at a time, because only Android can grant that
access to an app. This does not grant OAuth tokens or direct Google API access.
The manifest exposes installed sync adapter services to Android's package
visibility checks; DayRows does not request access to all installed packages.
DayRows does not obtain OAuth tokens, change sync settings or write calendar data.

Debug builds additionally provide a Developer Diagnostics screen with raw local
calendar metadata, including account/calendar names, colors and flags. Diagnostic
snapshots and a bounded trace are held in memory, not saved or uploaded. Copying
results is optional and exports redacted numeric metadata and status information;
account names/types, calendar names, event contents and exception messages are
omitted. Android manages the clipboard after copying. These diagnostic screens,
actions and calendar diagnostic logging are excluded from release builds.

## What is stored on your device

In the app's private Android storage, DayRows saves calendar selection flags,
custom labels, row order, preferred day count, setup state, and whether the
permission prompt has been requested. To recognize discovered calendars, it
also saves calendar identities derived from account names/types, owner accounts,
and source identifiers. These identities may contain email addresses and are
stored as text, not anonymized hashes. Identities are saved for discovered
calendars, including calendars that you have not selected.

Event contents are read for display in memory. DayRows does not save an event
database or a persistent copy of event titles/times. Original calendar data stays
in Android's Calendar Provider and is managed by your calendar/account apps.

Android's app sandbox protects DayRows' private storage under the operating
system's security model. DayRows does not add its own encryption layer. The app
opts out of Android automatic backup and excludes its data from Android cloud
backup and device-transfer rules. Device security and third-party tools remain
under your control.

## Network access and transfers to other apps

DayRows has no backend, accounts, advertising, analytics SDK, or crash-reporting
SDK. The app does not upload, sell, or transmit calendar data or preferences to
the developer or a server.

When you tap an event, DayRows asks an installed calendar application to display
it. That user-initiated request contains the event URI/ID, instance start and end
times, and all-day flag. The receiving calendar app handles that request under
its own permissions and privacy policy.

When you tap the + next to a day, DayRows asks an installed calendar application to
create a new event there. That user-initiated request contains the selected day and that
day's start and end time, and no calendar identifier. The receiving calendar app creates
the event under its own permissions and privacy policy.

Calendar apps and account synchronization services, including Google services
when configured on your device, may synchronize the original calendar data
independently of DayRows. Android, an app store, your browser, and support services
may process their own diagnostic, download, or usage information under their own
policies. They are not DayRows telemetry features.

## Retention and deletion

Local preferences remain until you change them, clear DayRows' storage in
Android settings, or uninstall it. Preferences for disappeared calendars may
remain so that reliable identities can be recognized if those calendars return.
Deselecting a calendar or revoking permission does not erase its saved local
label or identity. Clear the app's storage to remove all these preferences.

Clearing DayRows' storage or uninstalling it does not delete the original
calendars or events. Manage those in your calendar/account application. There
is no DayRows server account to delete and no developer-held calendar database
to request deletion from. Information you voluntarily submit to a public issue
tracker is handled by that service; do not post private calendar information.

## Family calendars

DayRows can display family calendars, which may include information about
children. The same local processing rules apply. DayRows does not create child
profiles, request ages, or carry out child-specific tracking or advertising.

## Changes and contact

If the application's data practices change, this policy will be updated with a
new effective date. New permissions will be requested through Android as needed.
Privacy enquiries can be submitted to the DayRows project issue tracker linked
above without including private calendar data.

---

# Informativa sulla privacy di DayRows

Data di efficacia: 22 ottobre 2026. Si applica a DayRows 0.1.2 (`it.stefanogozzi.dayrows`).

## Progetto e contatti

DayRows è un progetto open source. Per domande sulla privacy usa il registro
segnalazioni del progetto: https://github.com/sg0zzy/dayrows/issues.
Le segnalazioni sono pubbliche: non inserire eventi, indirizzi degli account o
altre informazioni riservate. GitHub e gli store applicano le proprie informative
ai rispettivi servizi.

## Dati utilizzati e finalità

Con il tuo permesso, DayRows legge il provider calendari di Android per mostrare
una matrice di calendari e giorni. Legge identificativi e nomi dei calendari,
nomi e tipi degli account, proprietari, identificativi della sorgente, colori,
livelli di accesso, visibilità e stato di sincronizzazione. Per i calendari
selezionati e l'intervallo visualizzato legge identificativi degli eventi, titoli,
inizio/fine delle occorrenze, indicazione di giornata intera e fuso orario.
Android espande gli eventi ricorrenti nelle relative occorrenze.

Questi dati possono contenere informazioni personali o sensibili. Servono alla
visualizzazione e alla configurazione locale, non a pubblicità, profilazione o
analisi. DayRows non interroga gli elenchi dei partecipanti, le descrizioni o i
campi relativi ai luoghi degli eventi.

## Permessi e controllo

L'unico permesso richiesto all'utente è `READ_CALENDAR`, preceduto da una
spiegazione. Puoi negarlo o revocarlo nelle impostazioni di Android; la vista
calendario non sarà disponibile. DayRows non richiede `WRITE_CALENDAR`, non
modifica calendari o eventi e non ha il permesso `INTERNET`. Il permesso privato
a firma dichiarato da AndroidX protegge i ricevitori interni e non concede
accesso ai calendari o alla rete.

DayRows dichiara inoltre i permessi concessi all'installazione
`READ_SYNC_SETTINGS` e `READ_SYNC_STATS` per rispettare la sincronizzazione
disattivata e osservare l'attività degli account calendario. Quando premi
esplicitamente Aggiorna, può chiedere agli adattatori di sincronizzazione già presenti sul
dispositivo di sincronizzare. Le app degli account gestiscono la rete con i
propri permessi e informative. Tramite AccountManager, DayRows verifica quali
account dei tipi associati ai calendari sono visibili. Se manca l'accesso, un
selettore account facoltativo di Android permette di concedere la visibilità di
un account esistente. Non concede token OAuth o accesso diretto alle API Google.
Il manifest rende visibili i servizi di sincronizzazione installati ai controlli
di Android; DayRows non richiede accesso a tutti i pacchetti installati. DayRows
non ottiene token OAuth, non cambia le impostazioni di sincronizzazione e non
scrive dati nei calendari.

Le build di debug offrono inoltre una schermata Diagnostica sviluppatore con
metadati locali grezzi, inclusi nomi di account/calendari, colori e indicatori.
Gli snapshot e una traccia limitata restano in memoria, senza salvataggio o invio.
La copia è facoltativa ed esporta metadati numerici e informazioni di stato senza
nomi/tipi degli account, nomi dei calendari, contenuti degli eventi o messaggi
delle eccezioni. Dopo la copia, Android gestisce gli appunti. Schermate, azioni e
registrazione diagnostica dei calendari sono escluse dalle build di rilascio.

## Memorizzazione locale e sicurezza

Nello spazio privato dell'app vengono salvati selezioni, etichette locali,
ordine dei calendari, numero di giorni preferito, stato della configurazione e
della richiesta del permesso. Vengono salvate anche le identità dei calendari
rilevati, compresi quelli non selezionati, ricavate da account, proprietari e
identificativi della sorgente. Possono contenere indirizzi email e sono salvate
come testo, non come hash anonimizzati.

Gli eventi sono letti in memoria per la visualizzazione: DayRows non conserva
un database degli eventi o copie persistenti dei loro titoli e orari. I dati
originali restano nel provider calendari, gestito dalle app di calendario/account.

La protezione dello spazio privato dipende dal sandbox di Android e dalla
sicurezza del dispositivo. DayRows non aggiunge una propria cifratura. L'app
disabilita il backup automatico ed esclude i suoi dati dalle regole Android di
backup cloud e trasferimento tra dispositivi.

## Rete e altre applicazioni

DayRows non ha server, account, pubblicità, SDK di analisi o di segnalazione
errori. Non carica, vende o trasmette calendari o preferenze allo sviluppatore
o a un server.

Toccando un evento chiedi a un'app calendario installata di aprirlo. La richiesta
contiene URI/identificativo dell'evento, inizio e fine dell'occorrenza e indicazione
di giornata intera. L'app destinataria applica i propri permessi e la propria
informativa. Le app calendario e i servizi degli account possono sincronizzare
i dati originali indipendentemente da DayRows. Android, store, browser e servizi
di assistenza possono trattare dati propri secondo le rispettive informative;
non sono funzionalità di telemetria di DayRows.

## Conservazione e cancellazione

Le preferenze restano finché le modifichi, cancelli i dati di DayRows nelle
impostazioni di Android o disinstalli l'app. Le identità dei calendari scomparsi
possono restare per riconoscerli al loro ritorno. Deselezionare un calendario o
revocare il permesso non elimina etichette e identità già salvate: per rimuoverle
tutte cancella lo spazio di archiviazione dell'app.

La cancellazione dei dati di DayRows e la disinstallazione non eliminano i
calendari o gli eventi originali. Non esistono account DayRows o database remoti
di eventi da cancellare. Le informazioni inviate volontariamente alle
segnalazioni pubbliche sono gestite da GitHub: non inviare dati riservati.

## Famiglie, aggiornamenti e contatti

I calendari familiari possono contenere informazioni sui minori: valgono le
stesse regole di trattamento locale. DayRows non crea profili di minori, non
chiede l'età e non effettua pubblicità o tracciamento specifici per minori.
Se cambiano le pratiche dell'app, l'informativa sarà aggiornata con una nuova
data. Per domande sulla privacy usa il registro segnalazioni indicato sopra,
senza pubblicare dati privati dei calendari.

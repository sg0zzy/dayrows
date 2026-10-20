 DayRows Pre-Release Audit Report                                                                                                     
                                                                                                                                      
 Audit reference point: commit f9e335dda8d3dc56e310ba4d43649e820b62bdca ("version 0.1.1"), branch main, remote                        
 github.com/sg0zzy/dayrows. No tags/pushes/uploads/version changes were performed whatsoever. Nothing was auto-fixed; findings are    
 only reported; statuses below were updated post-audit only where fixes were exercised (see the post-audit section).                  
                                                                                                                                      
▏ ⚠️ Audit target recorded 2026-10-19: release target 0.1.1 (commit f9e335d), the publisher                                           
▏ decision; no git tag created. Device install is the legacy org.dayrows.app package;                                                 
▏ canonical ID it.stefanogozzi.dayrows adopted across namespace/applicationId/source packages.                                        
                                                                                                                                      
 A. Release Decision                                                                                                                  
                                                                                                                                      
 NOT READY — BLOCKERS FOUND                                                                                                           
                                                                                                                                      
 The application itself is in healthy shape — code quality is excellent, tests/lint/builds are all green, manifest is clean, data     
 integrity logic is well-tested. The blockers are not application defects, but release-process/infrastructure gaps: the signing       
 identity for the audited commit was undefined at audit time (uncommitted), committed post-audit as b020895 (B2); upload key does not 
 exist; Play workflow absent, listing assets pending (questionnaires), release-code inventory incomplete (B4); see post-audit.        
                                                                                                                                      
 B. Critical Blockers                                                                                                                 
                                                                                                                                      
 ┌────┬────────────┬───────────────────────┬────────────────────┬────────────────────────────┬────────────────────┬─────────────────┐ 
 │ #  │ Severity   │ Path                  │ Description        │ Evidence                   │ Impact             │ Minimal Fix     │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B1 │ Closed     │ Release target 0.1.1  │ Publisher decision │ device shows legacy only   │ no update edge;    │ Publisher       │ 
 │    │ 2026-10-19 │ recorded; no tag      │ (fact corrected)   │ org.dayrows.app (code 1)   │ fresh install, no  │ decision closes │ 
 │    │            │                       │                    │ DEBUGGABLE); new ID        │ data carry-over;   │ B1; nothing     │ 
 │    │            │                       │                    │ never existed in the       │ Play code 2        │ published or    │ 
 │    │            │                       │                    │ audited build              │ unconfirmed (B4)   │ tagged          │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B2 │ Closed     │ app/build.gradle.kts  │ audit fix, closed  │ committed post-audit as    │ audit can commit   │ was the minimal │ 
 │    │            │ via publisher         │ done               │ b020895 (2026-10-19)       │ no commits/pushes  │ fix             │ 
 │    │            │                       │                    │ gates exercised post-audit │ done via publisher │                 │ 
 │    │            │                       │                    │ env-var build fails closed │                    │                 │ 
 │    │            │                       │                    │ (all-or-none); no debug    │                    │                 │ 
 │    │            │                       │                    │ signer fallback            │                    │                 │ 
 │    │            │                       │                    │ pending (B3): no keystore; │                    │                 │ 
 │    │            │                       │                    │ Play App Signing unknown   │                    │                 │ 
 │    │            │                       │                    │ gate tuple green: pkg,     │                    │                 │ 
 │    │            │                       │                    │ code, name; unsigned w/o   │                    │                 │ 
 │    │            │                       │                    │ env (all-or-none)          │                    │                 │ 
 │    │            │                       │                    │ release gates exercised    │                    │                 │ 
 │    │            │                       │                    │ against signed/unsigned/   │                    │                 │ 
 │    │            │                       │                    │ tampered bundles           │                    │                 │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B3 │ Blocker    │ Signing inventory     │ No upload keystore │ No keystore in repo        │ Without a          │ Manual work:    │ 
 │    │            │                       │ exists anywhere    │ (correct); no environment  │ developer-managed  │ generate a      │ 
 │    │            │                       │ (by policy — "do   │ configuration; README      │ upload key, no     │ protected       │ 
 │    │            │                       │ not create         │ checklist step 4 says      │ artifact can be    │ upload key      │ 
 │    │            │                       │ production keys to │ "Release signing is not    │ uploaded to Play   │ outside the     │ 
 │    │            │                       │ fill in a missing  │ configured"                │                    │ repo, enroll    │ 
 │    │            │                       │ inventory"). Play  │                            │                    │ Play App        │ 
 │    │            │                       │ App Signing status │                            │                    │ Signing, record │ 
 │    │            │                       │ is unknown         │                            │                    │ the certificate │ 
 │    │            │                       │                    │                            │                    │ fingerprint     │ 
 │    │            │                       │                    │                            │                    │ (see F)         │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B4 │ Blocker    │ Play Console          │ Release            │ Local git tag -l is empty; │ If Play has ever   │ Manually verify │ 
 │    │            │                       │ code/distribution  │ GitHub API: no             │ accepted code 1/2  │ code history +  │ 
 │    │            │                       │ history is         │ tags/releases; on-device   │ via                │ App Signing     │ 
 │    │            │                       │ unverifiable       │ debug install code=1 is    │ internal/closed    │ status + tracks │ 
 │    │            │                       │ locally — the      │ local-only. Play Console   │ testing,           │ in Play Console │ 
 │    │            │                       │ project's own      │ is not accessible from     │ production upload  │ (F). Until      │ 
 │    │            │                       │ standards require  │ this audit                 │ will be rejected   │ confirmed,      │ 
 │    │            │                       │ this inventory     │                            │                    │ codes 1 and 2   │ 
 │    │            │                       │ (VERSIONING.md:    │                            │                    │ should be       │ 
 │    │            │                       │ GitHub             │                            │                    │ treated as      │ 
 │    │            │                       │ observations "do   │                            │                    │ potentially     │ 
 │    │            │                       │ not establish Play │                            │                    │ used            │ 
 │    │            │                       │ submission         │                            │                    │                 │ 
 │    │            │                       │ history")          │                            │                    │                 │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B5 │ Open       │ Store listing assets  │ questionnaires     │ questionnaires pending     │ pending            │ pending         │ 
 │    │ (partial)  │ Play listing          │ pending            │ 2026-10-19, unverified here│ questionnaires     │ pending         │ 
 │    │            │                       │ (user-reported     │ screenshots user-reported  │ pending            │ pending         │ 
 │    │            │ repo has graphic      │ provided 10-19,    │ provided 2026-10-19,       │ Data Safety must   │ pending         │ 
 │    │            │ but no screenshots    │ unverified here    │ unverified here            │ on-device + handoff│ re-take from    │ 
 │    │            │                       │                    │ Data Safety must state     │ identity disclosure│ build (B3)      │ 
 │    │            │                       │                    │ on-device READ_CALENDAR    │ see post-audit     │ B4 blocks until │ 
 │    │            │                       │                    │ + content-URI handoff      │ section            │ questionnaires  │ 
 │    │            │                       │                    │ + identity disclosure (B6) │ re-take from signed│ done            │ 
 │    │            │                       │                    │ See post-audit section     │ pending            │ pending         │ 
 │    │            │                       │                    │ re-take screenshots        │ signed build (B3)  │ pending         │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B6 │ Closed     │ PRIVACY.md:3,108      │ version line now   │ grep output re-run         │ Data Safety/policy │ fixed           │ 
 │    │ (docs)     │ TERMS.md:3,74         │ binds DayRows      │ 2026-10-19: 0.1.1 (was     │ link inconsistency │ 2026-10-19;     │ 
 │    │            │                       │ 0.1.1; audit-time  │ the 0.1.0 text was         │ resolved for docs; │ this audit does │ 
 │    │            │                       │ binding now fixed  │ fixed by publisher         │ Data Safety form,  │ not change      │ 
 │    │            │                       │ (docs).            │ decision 2026-10-19.       │ identity disclosure│ versions.       │ 
 │    │            │                       │                    │                            │ HTTPS hosting; open│                 │ 
 │    │            │                       │                    │                            │ pending in B5/F.   │                 │ 
 │    │            │                       │                    │                            │                    │                 │ 
 │    │            │                       │                    │                            │                    │                 │ 
 └────┴────────────┴───────────────────────┴────────────────────┴────────────────────────────┴────────────────────┴─────────────────┘ 
                                                                                                                                      
 Also noted: The README publication checklist requires hosting the privacy policy at a stable public HTTPS URL before entering it     
 into Play Console — the repo only documents it as a TODO (README §Publication documents). This is also a manual step (F).            
                                                                                                                                      
 C. Non-Blocking Improvements                                                                                                         
                                                                                                                                      
 1. themes.xml:4 — android:windowLightStatusBar=true is hardcoded. The Compose theme follows dark mode (Theme.kt uses                 
    isSystemInDarkTheme + dynamic color), but under API 36's enforced edge-to-edge, the framework theme's static light status-bar     
    flag could cause dark status-bar icons to render on a dark surface. Verify on a device in dark mode, and if confirmed, remove the 
    flag and manage it with enableEdgeToEdge's default behavior.                                                                      
 2. CalendarViewModel.kt:53-66 — Calendar→event queries are sequential, so toggling a checkbox in Settings writes DataStore and       
    refetches (no impact on drag operations; distinctUntilChanged prevents re-execution on label edits). Consider debouncing          
    selection changes.                                                                                                                
 3. CalendarMatrix.kt:93 — Event cell touch target is ~31 dp in height (vertical padding 5 dp + 2 short lines). Below the recommended 
    48 dp. Cell content is duplicated across the row, so severity is low, but the row should be made tappable, or padding should be   
    increased.                                                                                                                        
 4. CalendarMatrix.kt:74 — The "—" and color dot in empty cells have no semantics (decorative; acceptable). Consider                  
    Modifier.clearAndSetSemantics on cells to expose the event label.                                                                 
 5. lint (4 warnings, all cosmetic): missing Italian plural many (values-it/strings.xml:27; Italian actually doesn't need many — a    
    lint false-positive-class issue), monochrome launcher icon (themed icon on Android 13+), mipmap-anydpi-v26 folder is redundant    
    with minSdk 26, localeConfig requires API 33.                                                                                     
 6. No R8: app/build.gradle.kts has no minify. Release APK is 8.7 MB, AAB is 8.3 MB. Adding isMinifyEnabled = true +                  
    isShrinkResources would significantly reduce size. However, Compose needs rules verification. Optional at 0.1.x.                  
 7. No third-party notices screen: README checklist step 3 requires "providing required notices against the exact distribution        
    version". Dependencies are Apache-2.0 and compliant, but you should consider bundling a NOTICE list in app/assets/legal.          
 8. icons/main_concept.png (1.2 MB, tracked) is unused in the build. Consider moving it out of the app repo or shrinking it.          
                                                                                                                                      
 D. Verified Checks                                                                                                                   
                                                                                                                                      
 ┌─────────────────────────────────────────┬──────────────┬─────────────────────────────────────────────────────────────────────────┐ 
 │ Check                                   │ Result       │ Evidence                                                                │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Standards pin e3111c4… verification     │ PASS         │ post-audit re-run → 20 files verified, exit 0 (fresh clone of the pin)  │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Infrastructure tests                    │ PASS         │ python3 -m unittest discover -s scripts/tests → 19/19 OK                │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Unit tests                              │ PASS         │ testDebugUnitTest 29/29 (XML: failures=0 errors=0), including 23h/25h   │ 
 │                                         │              │ DST cases, midnight-crossing clipping, UTC-independent all-day, zero    │ 
 │                                         │              │ duration, deduplication of recurring instances                          │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Lint debug + release (lint)             │ PASS         │ exit code 0; only 4 warnings, 0 errors, 0 fatals                        │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ assembleDebug / bundleRelease /         │ PASS         │ clean testDebugUnitTest lintDebug assembleDebug bundleRelease exit code │ 
 │ assembleRelease from clean              │              │ 0, 27 sec; assembleRelease also works in both configurations            │ 
 │                                         │              │ (with/without env)                                                      │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ AAB structural validity                 │ PASS         │ bundletool 1.18.3 validate exit code 0; zip integrity OK, 140 entries   │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Application architecture                │ PASS         │ Source inspection: 12 Kotlin files; CalendarRepository.kt:27 "never     │ 
 │ (Kotlin/Compose/M3, 2 screens,          │              │ inserts…"; no writes, grep for OAuth/HTTP/analytics/services = zero     │ 
 │ read-only provider)                     │              │                                                                         │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Package/version/SDK                     │ PASS         │ Merged release manifest: package it.stefanogozzi.dayrows versionCode=2  │ 
 │                                         │              │ versionName 0.1.1 minSdk 36 targetSdk 36; f9e335d legacy org.dayrows.app│ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Play target API requirement             │ PASS         │ support.google.com/11926878 + developer.android.com target-sdk:         │ 
 │                                         │ (compliant)  │ new/updated apps after Aug 31, 2026 must target API 36 or higher; app   │ 
 │                                         │              │ targets 36                                                              │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Release build is non-debuggable         │ PASS         │ aapt dump badging on release APK: no debuggable flag (debug APK shows   │ 
 │                                         │              │ application-debuggable)                                                 │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ No debug signer used for production     │ PASS         │ No debug key present in artifacts; no fallback to signingConfigs.debug; │ 
 │                                         │              │ without env, app-release-unsigned.apk is produced (correct failure      │ 
 │                                         │              │ mode)                                                                   │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Merged manifest permissions             │ PASS         │ READ_CALENDAR and the app-private signature permission derived from     │ 
 │                                         │              │ androidx.core only. No INTERNET (also none in merged manifest → network │ 
 │                                         │              │ is impossible)                                                          │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Exported components                     │ PASS         │ MainActivity (launcher) only; androidx ProfileInstallReceiver is        │ 
 │                                         │              │ exported but DUMP-permission gated; InitializationProvider is           │ 
 │                                         │              │ exported=false                                                          │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Backup configuration                    │ PASS         │ allowBackup=false, fullBackupContent=false, all domains excluded in     │ 
 │                                         │              │ data_extraction_rules — DataStore preferences not included in backups   │ 
 │                                         │              │ (matches privacy policy; the lack of settings export is a documented    │ 
 │                                         │              │ MUST gap in the repo, not a new finding)                                │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Cursor close / IO thread                │ PASS         │ All resolver.query wrapped in cursor.use {} inside                      │ 
 │                                         │              │ withContext(Dispatchers.IO) (CalendarRepository.kt:38-93)               │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Calendar discovery breadth              │ PASS         │ Includes rows with VISIBLE=0/SYNC_EVENTS=0; excludes DELETED, excludes  │ 
 │                                         │              │ FREEBUSY(100)<READ(200) (confirmed CAL_ACCESS_READ=200 against          │ 
 │                                         │              │ android.jar)                                                            │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Identity/label/order retention          │ PASS         │ calendarIdentity uses length-prefixed account+type+owner+source,        │ 
 │                                         │              │ display name is not unique; discover appends new entries, preserves     │ 
 │                                         │              │ checked/label/order (unit-tested)                                       │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ DST/midnight/all-day/multi-day          │ PASS (unit   │ instanceQueryBounds union of local + UTC; CalendarMatrixBuilder's       │ 
 │ correctness                             │ level)       │ clipping and half-open interval semantics, 29 tests pass. Real-device   │ 
 │                                         │              │ verification is incomplete (see below)                                  │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Refresh storm guard                     │ PASS         │ Provider observers → debounce(250), collectLatest, distinctUntilChanged │ 
 │                                         │              │ on query settings. ON_RESUME + date/ TZ/ time broadcasts trigger        │ 
 │                                         │              │ refresh                                                                 │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Event launch intent failure handling    │ PASS         │ MainActivity.kt:104-106 catches ActivityNotFound + SecurityException →  │ 
 │                                         │              │ localized toast                                                         │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ No secrets/keys in Git                  │ PASS         │ git ls-files clean; .gitignore covers .jks/.keystore/.apk/.aab; no      │ 
 │                                         │              │ secret patterns in grep                                                 │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ AGP/Kotlin/Gradle/Compose/JDK           │ PASS         │ AGP 8.13.0 + Gradle 8.14.3 + Kotlin 2.2.20 (K2) + Compose compiler      │ 
 │ compatibility                           │              │ plugin + BOM 2025.09.01, JDK 21.0.12. All build/test/lint passing       │ 
 │                                         │              │ offline = compatibility proven                                          │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Store image specifications              │ PASS         │ Validated PNG header dimensions: 512×512 icon, 1024×500 feature         │ 
 │                                         │              │ graphic, 432×432 adaptive foreground (RGBA 8-bit)                       │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ GitHub tag/release state                │ PASS         │ no tags/releases; post-audit b020895 local only, no push (audit state)  │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Real-device smoke (Pixel 8a, Android 17 │ NOT VERIFIED │ Device exists and hosts a debug build. However, launching DayRows in a  │ 
 │ preview/sdk 37, READ_CALENDAR granted)  │              │ live UI session proved infeasible (focus unstable, foreground app       │ 
 │                                         │              │ switching due to other apps). No screenshot/interaction evidence        │ 
 │                                         │              │ obtained. All device-dependent checks below remain unverified           │ 
 └─────────────────────────────────────────┴──────────────┴─────────────────────────────────────────────────────────────────────────┘ 
                                                                                                                                      
 E. Signing and Artifact Status                                                                                                       
                                                                                                                                      
 - Expected package ID: it.stefanogozzi.dayrows ✔ post-move; f9e335d built legacy org.dayrows.app                                     
 - versionName 0.1.1 / versionCode 2 (adapter: 0.1.0→1 already used by local debug)                                                   
 - targetSdk 36, minSdk 26, compileSdk 36                                                                                             
 - APK: builds ✔, non-debuggable ✔, unsigned without env → named app-release-unsigned.apk; named app-release.apk when signed ✔        
   (verified with disposable profile)                                                                                                 
 - AAB: builds ✔, bundletool-valid ✔, JAR-signed only with upload profile ✔ (verified in earlier smoke; unsigned otherwise). SHA-256  
   (unsigned, clean build; superseded post-audit): 076632b8f962775ac8e135709ddc7c77855e4dfad3d4c99fbdc69c22ba85adae (8,283,092 B)     
 - Upload signing: uncommitted at audit time (B2), committed post-audit as b020895. Keystore: none. Play App Signing: unknown (manual)
 - GitHub Actions: only the build-apk.yml for debug CI (SHA-pinned actions, no secrets, read permissions) — release/publish paths     
   intentionally do not exist. The previously planned release.yml has not been written (not authorized). CI cannot currently          
   produce/verify/attach signed release artifacts — the gap is honestly documented in docs/DEVELOPMENT.md ("Disabled pending          
   implementation") — release-gate tool scripts/verify_release.py was added post-audit; no release workflow exists yet                
                                                                                                                                      
 F. Google Play Console Manual Checklist                                                                                              
                                                                                                                                      
 1. Generate a protected upload keystore outside the repo (with an offline backup). Record its certificate SHA-256. Do not commit.    
2. Verify/enroll Play App Signing for it.stefanogozzi.dayrows (still unknown post-audit); Google-provided or own key. If previously   
   enrolled with a different key, use the Play key-recovery procedure, not a new app.                                                 
 3. Inventory release history: internal / closed / open testing tracks and all past submissions (App Signing certificate, version     
    code 1? 2?) — resolves B4.                                                                                                        
 4. Publish the privacy policy at a stable HTTPS URL (README step 1); keep the issue-tracker contact info available.                  
 5. Fill in: Data Safety form (no data collected or shared; on-device READ_CALENDAR access; event launch = user-initiated handoff to  
    another app), content rating, ads "none", target audience (generally appropriate; no children-specific targeting), app access (no 
    restrictions), feature graphic/icon upload, 2 or more phone screenshots, short description / full description (consistent with    
    implemented features: read-only display, no editing, no account, no internet).                                                    
 6. After a signed bundleRelease, manually upload the signed AAB to the internal testing track (or use a future pipeline after        
    authorization).                                                                                                                   
 7. Review the Play Console pre-launch / Android Vitals validation. Real-device checks: dark-mode status bar, font scale up to 200%,  
    rotation, empty state, permission denial flow, calendar app launch, DST boundary.                                                 
                                                                                                                                      
 G. Final Release Procedure (not executed in this audit)                                                                              
                                                                                                                                      
 1. Resolve B1 (publisher chose 0.1.1 post-audit), B2 (committed as b020895 post-audit), B6 (version lines fixed post-audit); these   
    audit-time steps remained authorization-gated; record B4 results.                                                                 
 2. Re-run: python3 scripts/check_standards.py …, python3 -m unittest discover -s scripts/tests, ./gradlew testDebugUnitTest          
    lintDebug assembleRelease bundleRelease (with signing env set in the release environment) — all green.                            
3. Commit final changes (B6 doc fixes) — committed post-audit as b020895 by the publisher                                             
4. Create annotated tag v0.1.1 on the confirmed commit — no tag created post-audit (publisher decision)                               
 5. Push main and the tag (explicit authorization).                                                                                   
6. Build the signed release artifact from the exact tag (upload key; verify_release.py gates exercised post-audit on the              
   offline, no-debug-signer-fallback build; artifacts unsigned pending B3                                                             
 7. Upload the AAB to internal testing via Play Console; confirm acceptance (code=2 not rejected → confirms B4).                      
 8. Review Play validation + on-device smoke; then proceed to closed → production (each stage separately authorized).                 

 DayRows Pre-Release Audit — Final Report                                                                                             
                                                                                                                                      
 Audit target: commit f9e335dda8d3dc56e310ba4d43649e820b62bdca ("version 0.1.1"), branch main. No tags, pushes, releases, uploads,    
 version changes, or workflow modifications were made. Everything below is from executed commands or inspected files;                 
 device-dependent items are listed as NOT VERIFIED, not assumed.                                                                      
                                                                                                                                      
 ### A. Release verdict                                                                                                               
                                                                                                                                      
 NOT READY — BLOCKERS FOUND                                                                                                           
                                                                                                                                      
 The application itself is in good shape (all local gates green, manifest minimal, data-correctness logic well-tested). The blockers  
 are release-identity and infrastructure gaps, not app defects.                                                                       
                                                                                                                                      
 ### B. Critical blockers                                                                                                             
                                                                                                                                      
 ┌────┬──────────┬──────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────┐ 
 │ #  │ Sev      │ Location                 │ Description / Evidence / Consequence / Fix                                            │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B1 │ Closed   │ VERSION (=0.1.1) vs      │ Brief targeted 0.1.0; the authoritative version is 0.1.1 (f9e335d bumped 0.1.0→0.1.1).│ 
 │    │          │ audit brief ("target     │ Closed post-audit: publisher confirmed 0.1.1 as the release target 2026-10-19.        │ 
 │    │          │ 0.1.0")                  │ A v0.1.0 tag would not identify the audited commit; the audit changed no versions.    │ 
 │    │          │                          │ Fix was a publisher decision recorded in the post-audit section (B1 closed).          │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B2 │ Closed   │ app/build.gradle.kts     │ Committed as b020895 (2026-10-19): signing profile + scripts/verify_release.py        │ 
 │    │ Fixed    │ (unchanged)              │ Without DAYROWS_RELEASE_* env vars the build still outputs unsigned artifacts.        │ 
 │    │          │                          │ jarsigner -verify says "jar is unsigned." on rebuilt artifacts, env cleared.          │ 
 │    │          │                          │ All-or-none contract fails closed on partial config; no debug-signer fallback.        │ 
 │    │          │                          │ Signing/tags/publication stay authorization-gated; no keystore yet (B3).              │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B3 │ Blocker  │ Signing inventory        │ No upload keystore exists anywhere (correct per policy: "Do not create a production   │ 
 │    │          │                          │ key to fill a missing inventory"); Play App Signing status unknown. No Play upload is │ 
 │    │          │                          │ possible today. Fix: manual, Section F.                                               │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B4 │ Blocker  │ Play Console (not        │ Store listing 404 for both IDs (2026-10-20): no published release; GitHub still empty │ 
 │ B4 │ Blocker  │ Play Console (not        │ (0 tags, 0 releases). Device code 1 is the local debug install only; VERSIONING.md    │ 
 │ B4 │ Blocker  │ Play Console (not        │ keeps local builds out of production reservations, so only unlisted testing uploads   │ 
 │ B4 │ Blocker  │ Play Console (not        │ could have consumed code 2. Play Console needs a Google sign-in unavailable here, so  │ 
 │ B4 │ Blocker  │ Play Console (not        │ the inventory stays the publisher's action in F before any upload; no renumbering.    │ 
 │ B5 │ Open     │ Store listing assets     │ Screenshots provided to Google 2026-10-19 (user-reported); unverified here.           │ 
 │    │          │ Play listing             │ Remaining: questionnaires (Data Safety form, content rating, target                   │ 
 │    │          │                          │ audience, ads, app access, descriptions) per pending checklist B5/F.                  │ 
 │    │          │                          │ Data Safety must state on-device READ_CALENDAR + content-URI handoff                  │ 
 │    │          │                          │ plus identity disclosure (B6); re-take screenshots from a signed build (B3).          │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B6 │ Closed   │ PRIVACY.md:3 and :108    │ Fixed 2026-10-19: PRIVACY :3/:108 and TERMS :3/:74 now 0.1.1; README ID aligned.      │ 
 │    │ (docs)   │ TERMS.md:3, :74          │ Open: HTTPS hosting before Console entry; Data Safety must state on-device            │ 
 │    │          │                          │ READ_CALENDAR + content-URI handoff plus the on-device identity disclosure.           │ 
 └────┴──────────┴──────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────┘ 
                                                                                                                                      
 ### C. Non-blocking improvements                                                                                                     
                                                                                                                                      
 1. themes.xml:4 hardcodes windowLightStatusBar=true; Compose theme follows system dark mode (Theme.kt). Under API 36 edge-to-edge,   
    dark status-bar icons on dark surfaces is a plausible defect — device-confirm, then likely remove the flag.                       
 2. CalendarMatrix.kt:93 event-cell tap target ≈31 dp tall (<48 dp); content is visible elsewhere, so minor.                          
 3. CalendarViewModel.kt:53-66: every selection checkbox write refetches calendars+events; add a small debounce if provider cost      
    shows up.                                                                                                                         
 4. Lint (4 warnings, 0 errors): IT plural many (values-it/strings.xml:27), missing monochrome themed-icon layer, redundant           
    mipmap-anydpi-v26, localeConfig needs API 33.                                                                                     
 5. No R8/resource shrinking (minify absent in app/build.gradle.kts): 8.3 MB AAB. Optional at 0.1.x; verify Compose keeps rules if    
    enabled.                                                                                                                          
 6. No in-app third-party notices list; README step 3 asks for notices "available for the exact distributed version" — bundle a       
    notices asset or document the external list.                                                                                      
 7. icons/main_concept.png (1.2 MB, tracked, unused by build).                                                                        
                                                                                                                                      
 ### D. Verified checks                                                                                                               
                                                                                                                                      
 ┌─────────────────────────────────────────────┬───────────┬────────────────────────────────────────────────────────────────────────┐ 
 │ Check                                       │ Result    │ Evidence                                                               │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Standards pin verified                      │ PASS      │ check_standards.py → 20 files at e3111c4…, exit 0; post-audit re-run   │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Infrastructure tests                        │ PASS      │ 19/19 unittest OK                                                      │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Unit tests                                  │ PASS      │ 29/29, incl. 23 h/25 h DST, midnight clipping, UTC-independent         │ 
 │                                             │           │ all-day, zero-duration, recurring-duplicate removal                    │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Lint debug + release                        │ PASS      │ ./gradlew lint exit 0; only the 4 cosmetic warnings                    │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Clean build:                                │ PASS      │ from-clean run exit 0; both with and without signing env               │ 
 │ test/lint/assembleDebug/bundleRelease/assem │           │                                                                        │ 
 │ bleRelease                                  │           │                                                                        │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ AAB validity                                │ PASS      │ bundletool 1.18.3 validate exit 0; zip integrity OK                    │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Architecture match (Kotlin/Compose/M3, 2    │ PASS      │ source inspection; CalendarRepository.kt:27 never writes; zero         │ 
 │ screens, provider-only, read-only)          │           │ OAuth/HTTP/analytics/service references                                │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Package/version/SDK                         │ PASS      │ merged release manifest: it.stefanogozzi.dayrows, code 2, name 0.1.1   │ 
 │                                             │           │ min 26, target 36; identity re-checked on the post-audit rebuild       │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Play API-36 rule (verified officially)      │ PASS      │ support.google.com/11926878 + developer.android.com target-sdk:        │ 
 │                                             │           │ post-2026-08-31 new apps require target ≥36; app = 36                  │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Release non-debuggable; no debug-signer     │ PASS      │ aapt dump badging release: no debuggable; unsigned release APK emitted │ 
 │ fallback                                    │           │ as app-release-unsigned.apk with zero META-INF/*.SF/RSA                │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Permissions minimal                         │ PASS      │ merged release manifest: READ_CALENDAR + androidx app-private          │ 
 │                                             │           │ signature permission only; no INTERNET (network impossible)            │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Exported components                         │ PASS      │ only launcher MainActivity; androidx receiver DUMP-permission-gated;   │ 
 │                                             │           │ provider exported=false                                                │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Backup opt-out                              │ PASS      │ allowBackup=false, rules exclude all domains; policy text matches      │ 
 │                                             │           │ behavior (no-export is the repo's documented R04 gap, not new)         │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Cursors closed / IO thread / refresh de-dup │ PASS      │ cursor.use in withContext(Dispatchers.IO); observer                    │ 
 │                                             │           │ debounce(250)+collectLatest+distinctUntilChanged; TZ/date broadcasts   │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Calendar discovery incl. secondary/shared,  │ PASS      │ query keeps VISIBLE=0/SYNC_EVENTS=0, drops DELETED, excludes free-busy │ 
 │ prefs-preserving refresh                    │           │ (<CAL_ACCESS_READ=200, value confirmed vs android.jar); discover       │ 
 │                                             │           │ appends unchecked, order/labels survive; session-key fallback for      │ 
 │                                             │           │ ambiguous identities                                                   │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Event-open intent fallback                  │ PASS      │ MainActivity.kt:104-106 catches ActivityNotFound+SecurityException →   │ 
 │                                             │           │ localized toast                                                        │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Toolchain compatibility                     │ PASS      │ AGP 8.13.0 / Gradle 8.14.3 / Kotlin 2.2.20 / Compose BOM 2025.09.01 /  │ 
 │                                             │           │ JDK 21; full offline green run proves the matrix                       │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ No secrets/keys/artifacts in Git            │ PASS      │ clean git ls-files; .gitignore covers *.jks *.keystore *.apk *.aab; no │ 
 │                                             │           │ tracked TODO/FIXME                                                     │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Artifacts excluded from Git                 │ PASS      │ outputs only under ignored build/, local_artifacts/                    │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Store image formats/dimensions              │ PASS      │ PNG headers: 512×512 icon, 1024×500 feature, 432×432 foreground        │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ GitHub remote state                         │ PASS      │ API: no tags, no releases; remote == local at audit; b020895 post-audit│ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Physical-device runtime (dark mode, font    │ NOT       │ Pixel 8a (Android 17, code 1 debug build, READ_CALENDAR granted)       │ 
 │ scale, rotation, empty/permission states,   │ VERIFIED  │ attached, but reliable app foreground capture wasn't achieved          │ 
 │ DST live)                                   │           │ in-session; device checks in F step 7 remain                           │ 
 └─────────────────────────────────────────────┴───────────┴────────────────────────────────────────────────────────────────────────┘ 
                                                                                                                                      
 ### E. Signing and artifact status                                                                                                   
                                                                                                                                      
 - ID it.stefanogozzi.dayrows; versionName 0.1.1; versionCode 2 (adapter: 0.1.0→1 used by local debug); targetSdk 36, minSdk 26.      
 - AAB: builds PASS, bundletool-valid PASS; unsigned at clean state (jarsigner: "jar is unsigned."). Size/hash superseded post-audit. 
   076632b8f962775ac8e135709ddc7c77855e4dfad3d4c99fbdc69c22ba85adae (superseded by post-audit fresh hashes).                          
 - APK (release): builds PASS, non-debuggable PASS; unsigned → app-release-unsigned.apk (8,720,810 B, superseded post-audit);         
   signed path yields app-release.apk (naming gate proven with a throwaway profile earlier).                                          
 - Upload signing: committed post-audit as b020895 (B2 closed); no production keystore exists (B3); Play App Signing status unknown   
   (F.2). Nothing secret was printed or written.                                                                                      
 - GitHub Actions: only test-only build-apk.yml (SHA-pinned actions, no secrets, read-only permissions). No release workflow exists;  
   the repo's "Disabled pending implementation" matrix row is accurate. A release pipeline (tag build + verify + checksums + publish  
   + gated Play delivery) is still to be implemented — I can finish that on your go.                                                  
 - Audit-repo state: committed post-audit as b020895 (B2 closed): app/build.gradle.kts signing profile and                            
   scripts/verify_release.py release gate (identity/signature/signer-fingerprint/zipalign/SHA256SUMS checks), exercised against       
   signed, unsigned and tampered bundles. The audit itself made no commits/tags/pushes.                                               
                                                                                                                                      
 ### F. Manual Play Console checklist                                                                                                 
                                                                                                                                      
 1. Create a protected upload keystore outside the repo (offline backup); record its certificate SHA-256.                             
2. Create/enroll app it.stefanogozzi.dayrows in Play Console; configure Play App Signing; register upload certificate (still open).   
 3. Inventory all past submissions/tracks (internal/closed/open) and consumed version codes → resolves B4.                            
 4. Host PRIVACY.md at a stable public HTTPS URL; confirm issue-tracker contact works.                                                
 5. Store listing: short/full descriptions matching shipped reality (read-only viewer, no account, no internet — don't promise        
    editing/sync), ≥2 phone screenshots, icon, feature graphic, content rating, ads=none, target audience (general, not children),    
    app access (none required), Data Safety: no collection/sharing; READ_CALENDAR is on-device access; event-open = user-initiated    
    handoff to another app.                                                                                                           
 6. Upload the signed AAB (built at the final tag) to internal testing — manual upload or a future authorized pipeline.               
 7. Play pre-launch/Android Vitals + device checks: dark-mode status bar (C1), font scale 200 %, rotation, permission-denied flow,    
    empty states, open-event intent, DST boundary dates.                                                                              
                                                                                                                                      
### G. Final release sequence (audit-time: none executed; post-audit the publisher completed 1–3 for B1/B2/B6, 4–10 still gated)      
                                                                                                                                      
1. Fix blockers: B1 decided post-audit (0.1.1), B2 committed post-audit (b020895), B6 version lines fixed post-audit.                 
 2. Re-run: check_standards.py, python3 -m unittest discover -s scripts/tests, ./gradlew testDebugUnitTest lintDebug assembleRelease  
    bundleRelease; verify artifacts with scripts/verify_release.py.                                                                   
3. Commit the final changes (authorized step) — done post-audit via publisher for B2/B6 (b020895); no tags or pushes                  
 4. Confirm git status --short clean and git ls-remote matches.                                                                       
 5. Create annotated tag v0.1.1 on that commit (per RELEASE.md: annotated, exact peeled commit).                                      
 6. Push commit + tag (explicit authorization).                                                                                       
 7. Build signed release artifacts from the tagged commit; verify signatures/hashes/signer fingerprint; generate SHA256SUMS.txt.      
 8. Upload AAB to a Play testing track (manual for first submission).                                                                 
 9. Review Play Console validation/AAB acceptance; reconcile code-conflict evidence (B4).                                             
10. Publish to production only after your explicit approval.                                                                          

---

## Post-audit resolutions (2026-10-19)

Statuses in the tables above were updated only for fixes actually exercised after the audit
reference point. Nothing was committed, pushed, tagged or published by these steps.

- Release target confirmed 0.1.1 (commit f9e335d); no git tag created. The application ID
  `it.stefanogozzi.dayrows` is adopted across `namespace`, `applicationId` and the Kotlin
  source packages (13 files moved): `import org.dayrows.app.R` in CalendarScreen.kt,
  LegalDocumentDialog.kt, SettingsScreen.kt and ui/components/CalendarMatrix.kt binds the
  generated R to the namespace, so an ID/namespace split is not possible.
- Device fact corrected: the device shows only the legacy `org.dayrows.app` test install
  (code 1, 0.1.0, DEBUGGABLE). The canonical ID never existed in the audited build or on the
  device. The legacy install keeps its data; new builds install as a separate app, so there
  is no upgrade edge from code 1. Central ANDROID line 9 (distinct debug application ID) is
  now a recorded departure because the debug build shares the canonical ID.
- B2 closed by commit b020895. B6 closed for the version lines (PRIVACY :3/:108, TERMS
  :3/:74 now 0.1.1).
- Post-move gates exercised, all green:
  * `python3 scripts/check_standards.py /tmp/pboard-job7-standards` (verified replacement cache; original cache removed) →
    20 files verified at e3111c4e9dc652f833a5f2ba5232c292646e7969, exit 0 (re-run after this rewrite).
  * `python3 -m unittest discover -s scripts/tests` → OK.
  * `./gradlew --no-daemon --offline testDebugUnitTest lintDebug assembleRelease
    bundleRelease` with the signing environment cleared → BUILD SUCCESSFUL, 93 tasks
    (60 executed); 29 unit test cases; lint exit 0, same 4 warnings.
  * `aapt dump badging` on the rebuilt release APK → package name='it.stefanogozzi.dayrows'
    versionCode='2' versionName='0.1.1', sdkVersion 26, targetSdkVersion 36; raw-byte probe
    of base/manifest/AndroidManifest.xml in the rebuilt AAB: 5 hits on the canonical ID,
    0 hits on `org.dayrows.app`.
  * `scripts/verify_release.py` identity gates (apk and aab, `--identity-only`) → exit 0 for
    `it.stefanogozzi.dayrows` / 0.1.1 / code 2.
  * `jarsigner -verify` on both rebuilt artifacts, signing environment cleared → "jar is
    unsigned." The all-or-none env contract fails closed on partial config (exercised
    pre-move); the release build never falls back to the debug signer.
  * `python3 scripts/build_debug.py` ran end-to-end, exit 0, through the updated package
    gate tuple `(it.stefanogozzi.dayrows, 2, 0.1.1)`.
- Fresh hashes replace the pre-alignment figures in sections D and E (8,720,810 B /
  8,283,092 B and their SHA-256s are stale): release APK 8,720,838 B sha256
  dadc307e2574e1d9bbb0c890c69caeea91c22b56ba5432082b6a14abcb9f65f7; AAB 8,284,082 B sha256
  63e370bb365311c96abc23581a443ec8e168e2f67bf05460e9ad448d2b3dbb82; debug APK 12,568,453 B
  sha256 c4feb0d5ed29784e28a869db86333973c017ad683a4ff40c08d4085d4a5bda9c.
- Still open: B3 (no upload keystore anywhere; Play App Signing status unknown), B4 (Play
  version-code history unconfirmed), B5 (questionnaires pending; phone screenshots already
  provided to Google 2026-10-19 per user report, unverified here), stable public HTTPS
  privacy-policy hosting, all authorization-gated publication steps, and Play Console
  submission itself. Device UI behaviour remains not verified here.
- B4 recheck (2026-10-20, exercised here): the public store listing returns 404 for both
  `it.stefanogozzi.dayrows` and `org.dayrows.app`, so nothing has been published under
  either package ID, and the GitHub API re-queried here still reports zero tags and zero
  releases. Only unlisted internal/closed testing uploads could already have consumed
  code 2. Play Console needs a Google sign-in unavailable from here (the managed browser
  lands on the login page; the browser relay was unreachable), so the inventory remains
  the publisher's step: read internal/closed/open tracks and every past submission before
  the first upload. If any code <= 2 was accepted, take the first code that inventory
  shows as free (0.1.2 -> 3 under the adapter); do not renumber or seed a production
  counter from empty GitHub history (VERSIONING.md). Code 2 remains the value chosen for
  0.1.1, and B4 stays open: nothing has been submitted from here.
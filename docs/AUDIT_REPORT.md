 DayRows Pre-Release Audit Report                                                                                                     
                                                                                                                                      
 Audit reference point: commit f9e335dda8d3dc56e310ba4d43649e820b62bdca ("version 0.1.1"), branch main, remote                        
 github.com/sg0zzy/dayrows. No tags/pushes/uploads/version changes were performed whatsoever. Nothing was auto-fixed; findings are    
 only reported.                                                                                                                       
                                                                                                                                      
 ▏ ⚠️ Mismatch in audit target: The request specifies "target release 0.1.0", but the repository's official version is 0.1.1 (commit  
 ▏ f9e335d). Furthermore, an already-installed 0.1.0 debug build exists on the device. You need to decide which one to release (see   
 ▏ B1).                                                                                                                               
                                                                                                                                      
 A. Release Decision                                                                                                                  
                                                                                                                                      
 NOT READY — BLOCKERS FOUND                                                                                                           
                                                                                                                                      
 The application itself is in healthy shape — code quality is excellent, tests/lint/builds are all green, manifest is clean, data     
 integrity logic is well-tested. The blockers are not application defects, but release-process/infrastructure gaps: the signing       
 identity for the audited commit is undefined (uncommitted), upload key does not exist, Play workflow is absent, listing assets are   
 missing, version target is ambiguous, and the release-code inventory (Play Console) is incomplete.                                   
                                                                                                                                      
 B. Critical Blockers                                                                                                                 
                                                                                                                                      
 ┌────┬────────────┬───────────────────────┬────────────────────┬────────────────────────────┬────────────────────┬─────────────────┐ 
 │ #  │ Severity   │ Path                  │ Description        │ Evidence                   │ Impact             │ Minimal Fix     │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B1 │ Blocker    │ VERSION (0.1.1) vs    │ Request states     │ cat VERSION = 0.1.1; git   │ A v0.1.0 tag would │ Publisher       │ 
 │    │            │ audit target "0.1.0"  │ target release     │ log -1; on-device dumpsys  │ not identify the   │ decides:        │ 
 │    │            │                       │ 0.1.0; official    │ package org.dayrows.app →  │ audited commit;    │ release 0.1.1   │ 
 │    │            │                       │ repo is 0.1.1;     │ versionCode=1              │ releasing an old   │ (recommended),  │ 
 │    │            │                       │ 0.1.0 exists only  │ versionName=0.1.0          │ source = rollback  │ or explicitly   │ 
 │    │            │                       │ as a debug build   │ DEBUGGABLE                 │ regression         │ roll back       │ 
 │    │            │                       │ on the device      │                            │                    │ VERSION. This   │ 
 │    │            │                       │                    │                            │                    │ audit does not  │ 
 │    │            │                       │                    │                            │                    │ change version  │ 
 │    │            │                       │                    │                            │                    │ numbers         │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B2 │ Blocker    │ app/build.gradle.kts  │ The release        │ git show                   │ The tag can be cut │ Review + commit │ 
 │    │            │ (uncommitted diff)    │ signing profile    │ HEAD:app/build.gradle.kts  │ from a commit that │ the             │ 
 │    │            │                       │ exists only in the │ (96 lines, no              │ cannot produce a   │ working-tree    │ 
 │    │            │                       │ working tree;      │ buildTypes/signing);       │ signed upload      │ changes, or     │ 
 │    │            │                       │ audited commit     │ jarsigner -verify on that  │ artifact,          │ define an       │ 
 │    │            │                       │ f9e335d has no     │ AAB → "jar is unsigned.";  │ resulting in an    │ explicit        │ 
 │    │            │                       │ signing            │ git diff --stat = 28 lines │ unsigned bundle    │ release         │ 
 │    │            │                       │ configuration at   │ inserted                   │ with "success"     │ pipeline. An    │ 
 │    │            │                       │ all, and           │                            │                    │ audit cannot    │ 
 │    │            │                       │ bundleRelease      │                            │                    │ commit (not     │ 
 │    │            │                       │ there silently     │                            │                    │ authorized)     │ 
 │    │            │                       │ produces an        │                            │                    │                 │ 
 │    │            │                       │ unsigned AAB with  │                            │                    │                 │ 
 │    │            │                       │ exit code 0        │                            │                    │                 │ 
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
 │ B5 │ Blocker    │ Store listing assets  │ Play requires 2 or │ git ls-files: only icon/*, │ Listing cannot be  │ Take            │ 
 │    │            │                       │ more phone         │ no screenshot/metadata     │ created → cannot   │ screenshots     │ 
 │    │            │                       │ screenshots. The   │ assets                     │ release            │ from the        │ 
 │    │            │                       │ repo has           │                            │                    │ release build   │ 
 │    │            │                       │ icon/feature       │                            │                    │ (manual, F)     │ 
 │    │            │                       │ graphic but no     │                            │                    │                 │ 
 │    │            │                       │ screenshots.       │                            │                    │                 │ 
 │    │            │                       │ Additionally, the  │                            │                    │                 │ 
 │    │            │                       │ Data Safety form   │                            │                    │                 │ 
 │    │            │                       │ and content rating │                            │                    │                 │ 
 │    │            │                       │ are unperformed    │                            │                    │                 │ 
 ├────┼────────────┼───────────────────────┼────────────────────┼────────────────────────────┼────────────────────┼─────────────────┤ 
 │ B6 │ Blocker    │ PRIVACY.md:3,108      │ Policy declares it │ grep output                │ Data Safety/policy │ Update the      │ 
 │    │ (process)  │                       │ "applies to        │                            │ link inconsistency │ version line    │ 
 │    │            │                       │ DayRows 0.1.0". If │                            │ at review time     │ when the        │ 
 │    │            │                       │ 0.1.1 is released, │                            │                    │ publisher       │ 
 │    │            │                       │ the policy's       │                            │                    │ finalizes the   │ 
 │    │            │                       │ version binding is │                            │                    │ version (this   │ 
 │    │            │                       │ incorrect          │                            │                    │ audit does not  │ 
 │    │            │                       │                    │                            │                    │ change          │ 
 │    │            │                       │                    │                            │                    │ versions)       │ 
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
 │ Standards pin e3111c4… verification     │ PASS         │ check_standards.py → 20 files verified, exit code 0                     │ 
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
 │ Package/version/SDK                     │ PASS         │ Merged release manifest: package org.dayrows.app versionCode=2          │ 
 │                                         │              │ versionName=0.1.1 minSdk 26 targetSdk 36                                │ 
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
 │ GitHub tag/release state                │ PASS         │ API: no tags, no releases (as of 2026-10-19); remote main == local HEAD │ 
 ├─────────────────────────────────────────┼──────────────┼─────────────────────────────────────────────────────────────────────────┤ 
 │ Real-device smoke (Pixel 8a, Android 17 │ NOT VERIFIED │ Device exists and hosts a debug build. However, launching DayRows in a  │ 
 │ preview/sdk 37, READ_CALENDAR granted)  │              │ live UI session proved infeasible (focus unstable, foreground app       │ 
 │                                         │              │ switching due to other apps). No screenshot/interaction evidence        │ 
 │                                         │              │ obtained. All device-dependent checks below remain unverified           │ 
 └─────────────────────────────────────────┴──────────────┴─────────────────────────────────────────────────────────────────────────┘ 
                                                                                                                                      
 E. Signing and Artifact Status                                                                                                       
                                                                                                                                      
 - Expected package ID: org.dayrows.app ✔ (merged manifest, output-metadata.json)                                                     
 - versionName 0.1.1 / versionCode 2 (adapter: 0.1.0→1 already used by local debug)                                                   
 - targetSdk 36, minSdk 26, compileSdk 36                                                                                             
 - APK: builds ✔, non-debuggable ✔, unsigned without env → named app-release-unsigned.apk; named app-release.apk when signed ✔        
   (verified with disposable profile)                                                                                                 
 - AAB: builds ✔, bundletool-valid ✔, JAR-signed only with upload profile ✔ (verified in earlier smoke; unsigned otherwise). SHA-256  
   (unsigned, clean build) 076632b8…258a…: 076632b8f962775ac8e135709ddc7c77855e4dfad3d4c99fbdc69c22ba85adae (8,283,092 bytes)         
 - Upload signing: profile exists only as an uncommitted diff (B2). Keystore: none. Play App Signing: unknown (manual)                
 - GitHub Actions: only the build-apk.yml for debug CI (SHA-pinned actions, no secrets, read permissions) — release/publish paths     
   intentionally do not exist. The previously planned release.yml has not been written (not authorized). CI cannot currently          
   produce/verify/attach signed release artifacts — the gap is honestly documented in docs/DEVELOPMENT.md ("Disabled pending          
   implementation")                                                                                                                   
                                                                                                                                      
 F. Google Play Console Manual Checklist                                                                                              
                                                                                                                                      
 1. Generate a protected upload keystore outside the repo (with an offline backup). Record its certificate SHA-256. Do not commit.    
 2. Verify/enroll Play App Signing for org.dayrows.app. Decide between Google-provided key or own key. If you previously enrolled     
    with a different key, use the Play key-recovery procedure, not a new app.                                                         
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
                                                                                                                                      
 1. Resolve B1 (choose 0.1.1 as the release version; fix the version line in PRIVACY.md if necessary), B2 (review the                 
    app/build.gradle.kts diff and commit with a message; verify check_standards + tests pass), and record B4 results.                 
 2. Re-run: python3 scripts/check_standards.py …, python3 -m unittest discover -s scripts/tests, ./gradlew testDebugUnitTest          
    lintDebug assembleRelease bundleRelease (with signing env set in the release environment) — all green.                            
 3. Commit final changes (B6 doc fixes). git status --short clean.                                                                    
 4. Create an annotated tag v0.1.1 on the confirmed commit (per RELEASE.md: annotated, peeled to an exact commit).                    
 5. Push main and the tag (explicit authorization).                                                                                   
 6. Build the signed release artifact from the exact tag (upload key; verify_release.py gates: identity / signature / signer          
    fingerprint / zipalign / SHA256SUMS).                                                                                             
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
 │ B1 │ Blocker  │ VERSION (=0.1.1) vs      │ Brief targets 0.1.0; the authoritative version is 0.1.1 (commit f9e335d bumped        │ 
 │    │          │ audit brief ("target     │ 0.1.0→0.1.1). A v0.1.0 tag would not identify the audited commit. Fix: publisher      │ 
 │    │          │ 0.1.0")                  │ confirms 0.1.1 as the release target (recommended) or explicitly backs VERSION out —  │ 
 │    │          │                          │ I do not change versions.                                                             │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B2 │ Blocker  │ app/build.gradle.kts     │ The release-signing profile exists only in the working tree. At the audited commit    │ 
 │    │          │ (28-line uncommitted     │ there is no signing config at all and bundleRelease exits 0 producing an unsigned AAB │ 
 │    │          │ diff)                    │ (jarsigner -verify → "jar is unsigned." on the clean-build bundle). Tagging the       │ 
 │    │          │                          │ released commit from HEAD could produce an un-uploadable artifact with a green build. │ 
 │    │          │                          │ Fix: review and commit the working-tree change (my unverified scratch work — it needs │ 
 │    │          │                          │ your eyes), or accept unsigned packaging explicitly.                                  │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B3 │ Blocker  │ Signing inventory        │ No upload keystore exists anywhere (correct per policy: "Do not create a production   │ 
 │    │          │                          │ key to fill a missing inventory"); Play App Signing status unknown. No Play upload is │ 
 │    │          │                          │ possible today. Fix: manual, Section F.                                               │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B4 │ Blocker  │ Play Console (not        │ Release-code history unverifiable locally. Local GitHub inventory: zero tags/releases │ 
 │    │          │ accessible from here)    │ (API, 2026-10-19); but VERSIONING.md: that observation "does not establish Play       │ 
 │    │          │                          │ submission history." Device shows code 1 (0.1.0) used locally. Consequence: if Play   │ 
 │    │          │                          │ ever accepted codes 1/2 via testing tracks, production upload is rejected. Fix:       │ 
 │    │          │                          │ Console inventory in F; until then treat code 2 as possibly-conflicting.              │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B5 │ Blocker  │ Store listing assets     │ Play requires ≥2 phone screenshots; repo tracks only                                  │ 
 │    │          │                          │ icon/feature-graphic/adaptive-foreground (git ls-files icons). Data-safety form,      │ 
 │    │          │                          │ content rating, descriptions also unmade (inherently manual). Fix: capture            │ 
 │    │          │                          │ screenshots from a signed build; Console work in F.                                   │ 
 ├────┼──────────┼──────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤ 
 │ B6 │ Blocker  │ PRIVACY.md:3 and :108    │ Policy says "Applies to DayRows 0.1.0" — contradicts a 0.1.1 release; Play            │ 
 │    │ (docs)   │                          │ Data-safety links the policy. Fix: publisher updates the version line                 │ 
 │    │          │                          │ (version-adjacent; not touched here). Also README checklist: privacy policy must be   │ 
 │    │          │                          │ hosted at a stable public HTTPS URL before Console entry — not yet done.              │ 
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
 │ Standards pin verified                      │ PASS      │ check_standards.py → "Verified 20 files at e3111c4…", exit 0           │ 
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
 │ Package/version/SDK                         │ PASS      │ merged release manifest: org.dayrows.app, code 2, name 0.1.1, min 26,  │ 
 │                                             │           │ target 36                                                              │ 
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
 │ GitHub remote state                         │ PASS      │ API: no tags, no releases; remote main == local HEAD                   │ 
 ├─────────────────────────────────────────────┼───────────┼────────────────────────────────────────────────────────────────────────┤ 
 │ Physical-device runtime (dark mode, font    │ NOT       │ Pixel 8a (Android 17, code 1 debug build, READ_CALENDAR granted)       │ 
 │ scale, rotation, empty/permission states,   │ VERIFIED  │ attached, but reliable app foreground capture wasn't achieved          │ 
 │ DST live)                                   │           │ in-session; device checks in F step 7 remain                           │ 
 └─────────────────────────────────────────────┴───────────┴────────────────────────────────────────────────────────────────────────┘ 
                                                                                                                                      
 ### E. Signing and artifact status                                                                                                   
                                                                                                                                      
 - ID org.dayrows.app; versionName 0.1.1; versionCode 2 (adapter: 0.1.0→1 used by local debug); targetSdk 36, minSdk 26.              
 - AAB: builds PASS, bundletool-valid PASS; unsigned at clean state (jarsigner: "jar is unsigned."). Size 8,283,092 B; SHA-256        
   076632b8f962775ac8e135709ddc7c77855e4dfad3d4c99fbdc69c22ba85adae.                                                                  
 - APK (release): builds PASS, non-debuggable PASS; unsigned → app-release-unsigned.apk (8,720,810 B, sha256 597c1545…f807a3d);       
   signed path yields app-release.apk (naming gate proven with a throwaway profile earlier).                                          
 - Upload signing: configuration uncommitted (B2); no production keystore exists (B3); Play App Signing status unknown (F.2). Nothing 
   secret was printed or written.                                                                                                     
 - GitHub Actions: only test-only build-apk.yml (SHA-pinned actions, no secrets, read-only permissions). No release workflow exists;  
   the repo's "Disabled pending implementation" matrix row is accurate. A release pipeline (tag build + verify + checksums + publish  
   + gated Play delivery) is still to be implemented — I can finish that on your go.                                                  
 - Audit-repo state left for review: M app/build.gradle.kts, ?? scripts/verify_release.py (release-gate tool:                         
   identity/signature/signer-fingerprint/zipalign/SHA256SUMS checks, exercised against signed, unsigned, and tampered bundles). No    
   commits/tags/pushes.                                                                                                               
                                                                                                                                      
 ### F. Manual Play Console checklist                                                                                                 
                                                                                                                                      
 1. Create a protected upload keystore outside the repo (offline backup); record its certificate SHA-256.                             
 2. Create/enroll app org.dayrows.app in Play Console; configure Play App Signing; register upload certificate.                       
 3. Inventory all past submissions/tracks (internal/closed/open) and consumed version codes → resolves B4.                            
 4. Host PRIVACY.md at a stable public HTTPS URL; confirm issue-tracker contact works.                                                
 5. Store listing: short/full descriptions matching shipped reality (read-only viewer, no account, no internet — don't promise        
    editing/sync), ≥2 phone screenshots, icon, feature graphic, content rating, ads=none, target audience (general, not children),    
    app access (none required), Data Safety: no collection/sharing; READ_CALENDAR is on-device access; event-open = user-initiated    
    handoff to another app.                                                                                                           
 6. Upload the signed AAB (built at the final tag) to internal testing — manual upload or a future authorized pipeline.               
 7. Play pre-launch/Android Vitals + device checks: dark-mode status bar (C1), font scale 200 %, rotation, permission-denied flow,    
    empty states, open-event intent, DST boundary dates.                                                                              
                                                                                                                                      
 ### G. Final release sequence (3–10 are authorization-gated; none executed)                                                          
                                                                                                                                      
 1. Fix blockers: decide version target (B1), review+stage the signing diff (B2), update PRIVACY version line (B6).                   
 2. Re-run: check_standards.py, python3 -m unittest discover -s scripts/tests, ./gradlew testDebugUnitTest lintDebug assembleRelease  
    bundleRelease; verify artifacts with scripts/verify_release.py.                                                                   
 3. Commit the final changes (authorized step).                                                                                       
 4. Confirm git status --short clean and git ls-remote matches.                                                                       
 5. Create annotated tag v0.1.1 on that commit (per RELEASE.md: annotated, exact peeled commit).                                      
 6. Push commit + tag (explicit authorization).                                                                                       
 7. Build signed release artifacts from the tagged commit; verify signatures/hashes/signer fingerprint; generate SHA256SUMS.txt.      
 8. Upload AAB to a Play testing track (manual for first submission).                                                                 
 9. Review Play Console validation/AAB acceptance; reconcile code-conflict evidence (B4).                                             
 10. Publish to production only after your explicit approval.   
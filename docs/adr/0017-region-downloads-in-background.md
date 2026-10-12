# ADR-0017: Region downloads in the background — a user-initiated data transfer job, one notification, Wi-Fi unless allowed

**Status:** Accepted · **Date:** 2026-10-11 · **Deciders:** the owner · **Repo path:** `docs/adr/0017-region-downloads-in-background.md`

Amends [ADR-0008](0008-region-download.md) (downloads only while the app is open, "no background downloads in v1") and [ADR-0016](0016-backup-region-switches.md) (the regions a restore installs switched off are kept with the queue).

Amended 2026-10-12 (see [the amendment](#amendment-2026-10-12-no-ready-notice-while-the-app-is-open)): the "ready" notice is shown only while the app is away, and opening the app takes it away.

## Context

A map region is 100–150 MiB compressed, and a restore or Update all fetches several, one after another. Today (ADR-0008) the download runs in the app's process while the app is open. If the rider leaves the app, Android can stop it at any time. A lost connection fails the queue. Nothing resumes after a restart. The rider (2026-10-11) wants downloads to run in the background with:

- a notification that shows progress, and live progress on the Map region page as well;
- one region at a time, with **one** combined notification ("map region 2 of 5" and the current region's progress);
- resume after a lost connection, an app restart, or the app being killed;
- the notification permission asked on first use. If he refuses it, downloads still work, silently, with progress shown only on the Map region page;
- a setting for mobile data, off by default (Wi-Fi or other unmetered networks only). On mobile data with the setting off, the app asks whether to download now over mobile data this once. If he says no, the download waits and starts by itself as soon as the phone is on Wi-Fi, also after an app restart.

He asked for Android's DownloadManager if that makes the most sense.

Forces:

- **Security first** (CLAUDE.md). Every ADR-0008 check stays as it is: HTTPS from the fixed source, redirects only to HTTPS, a download stopped at its listed size, SHA-256 against the signed manifest before anything is decompressed, and the full structure check before a file is installed or opened. File names come only from checked ids. No component may be exported beyond the launcher activity. Least privilege: no broad new permissions.
- **Android's background limits.** Since Android 12, an app in the background can't start a foreground service, and a sticky restart is no exception. Since Android 14, a foreground service needs a type and a matching permission. Since Android 15, a `dataSync` foreground service may run at most 6 hours a day, and Google steers transfers the user starts towards user-initiated data transfer jobs. Doze and App Standby cut the network for apps that aren't running foreground work.
- **Portability.** The checks and the install stay in the core (ADR-0008). Scheduling and the queue are platform work: an iOS port would use a background `URLSession`.
- **Few dependencies** (supply chain), and no exported components.

## Decision

- **Android 14 and newer: one user-initiated data transfer job.** When the rider starts a download, the app schedules a JobScheduler job with `setUserInitiated(true)`, a network constraint and the download's estimated size. The job runs the download queue in the app's process. It calls `setNotification` with the app's own notification and `JOB_END_NOTIFICATION_POLICY_DETACH`, so the same notification can go on to show "waiting", "ready" or "failed". While it runs, Android holds a wake lock for it and exempts it from Doze and App Standby. It has no daily time limit as long as its network constraint holds. When the network goes, Android stops the job and starts it again when the network comes back; when the app's process dies, Android reschedules it. The only new permission is `RUN_USER_INITIATED_JOBS` (normal, granted at install, no prompt). The job service is not exported and is guarded by `BIND_JOB_SERVICE`. Such a job can only be scheduled while the app is visible; every schedule comes from a tap or from the app's start.
- **Android 8 to 13: the same queue in the app's process**, as today, with the same notification as an ordinary notification. It waits for an allowed network by itself and retries by itself. It keeps going while Android keeps the app's process, and resumes at the next app start.
- **Not kept across a phone restart.** The job is not persisted, so the app doesn't need `RECEIVE_BOOT_COMPLETED`. After a phone restart, or after a force stop (which cancels an app's jobs), the queue carries on by itself the next time the app is opened. The rider doesn't have to tap anything.
- **A queue kept on the phone.** The app's `regions` preferences hold the queue: the region ids in order (each checked with `isRegionId` when read, at most 32), the ids a restore installs switched off (ADR-0016), how many of this batch are done, a once-only network permission, and the failure message when the queue has stopped. One region downloads at a time. Starting another download while one runs adds it to the end of the queue. Update all and a restore's Download add theirs too.
- **Each run starts from a fresh signed manifest.** Each run fetches and parses the manifest again; the core checks its signature as before. Each queued id takes its offer from that manifest. Ids no longer offered, and regions already up to date, are dropped. The partial file is named by the checked id (`<id>.part`, in no-backup storage). The SHA-256 of the file it belongs to is recorded with it. A partial whose file has since been published again is discarded and started afresh, never appended to. A partial is resumed with an HTTP range request, as today.
- **Networks.** By default, downloads use only unmetered networks that aren't roaming (Wi-Fi). The setting "Download over mobile data" allows any network that isn't roaming. When the rider starts a download on a network the setting doesn't allow, the app asks: "Download now" lets this queue use the kind of network the phone is on now (home mobile data, or roaming after a warning). "Wait for Wi-Fi" leaves the queue waiting. That waiting is the job's own network constraint, so it survives the app being closed or killed. The job binds its connections to the network Android gave it. The small region list on the Map region page is still fetched on any network, as today.
- **One notification for the whole queue**, with one id. While downloading: "Downloading map region 2 of 5" over "Norway · 45.2 of 180.4 MB", a progress bar for the current region, and Stop. It also shows checking and installing, waiting for Wi-Fi, and retrying. At the end it is replaced by "ready" or "failed". Progress and waiting use a low-importance channel; the result uses a default one. Tapping the notification opens the Map region page. Stop goes to a receiver that is not exported, through an explicit, immutable PendingIntent.
- **Notification permission.** On Android 13 and newer, the app asks for it once, on the first download start, unless it is already granted. Downloads run whatever the answer. If it is refused, nothing appears in the notification shade and there is no "ready" or "failed" notice. Android still requires the job's notification and keeps listing the running app under active apps in quick settings; the app can't hide that. The Map region page shows the progress and a note that notifications are off, with a way to turn them on.
- **Failures.** A dropped connection, a timeout, HTTP 408 or 429, or a 5xx is retried by the job with exponential backoff from 30 s. After 5 attempts in a row that bring no new bytes, the region fails. Any other HTTP error, too little free space (checked before each region, as today), or a file the core refuses (deleted) fails it at once. A failure stops the queue, as today, but the queue is kept: Try again carries on from the region that failed, and keeps the switched-off set.
- **Stop** (the X on the page, Stop in the notification, or stopping the app in Android's active-apps panel) ends the queue and deletes its partial files.
- **Cleanup.** Partial files exist only for queued regions. At app start and at the start of each run, every other file in the partial folder is deleted. So is a core temporary file (`*.region.tmp`) left by an install that was killed, but only while no install is running.
- **Killed while installing.** The core renames a region into place only after its full check (ADR-0008). The app then records the install and takes the region off the queue in one synchronous preferences write, and only after that deletes the partial. A kill at any point leaves one of two states. Either the old region is still usable and the complete partial is still there, so the next run installs it again without downloading. Or the new region is recorded.
- **The map is opened only for a running app.** After an install, the routing network is opened again only if the app had already loaded it in this process. A job that runs while the app is closed doesn't open the map engine.
- **Switches.** The rules are unchanged: a new region comes in switched on, an update keeps the region's switch, and a restore's switched-off regions come in off (ADR-0016). They now hold across interruptions, because the off set is kept with the queue.

## Options Considered

### A. Android's DownloadManager

| Dimension | Assessment |
| --- | --- |
| Notification | Its own notification per file; hiding it needs `DOWNLOAD_WITHOUT_NOTIFICATION`, and then nothing shows the progress while the app is closed. It can't show one "2 of 5" notification |
| One at a time with checks | Chaining needs its "complete" broadcast, which comes from another app: an exported receiver, against our rules, or our own process kept alive anyway |
| Storage | Writes only to shared storage, app-specific external storage or its own cache, never into app-private internal storage: every file must be copied in, not renamed |
| Security | Runs in the system's download process: it can't stop a file at its listed size or apply our redirect rule |
| Install step | Checksum, unpacking and the structure check still need our own background work |
| Resume, Wi-Fi only | Good: built in |

**Pros:** resume and network rules for free; no code of ours runs during the transfer. **Cons:** it fails the combined notification, the sequential checked install and the private-storage rule, and it still needs a second mechanism for the install.

### B. Our own foreground service (`dataSync`)

| Dimension | Assessment |
| --- | --- |
| Complexity | Medium: like `RecordingService` |
| Network rules | Ours to write: watch the network, pause, wait with the service and its notification still up |
| Resume after kill | Unreliable: since Android 12 a restarted service may not go foreground from the background |
| Limits | Android 14 needs `FOREGROUND_SERVICE_DATA_SYNC`; Android 15 caps it at 6 hours a day; Google points such transfers to user-initiated jobs |
| Battery | Needs its own wake lock (`WAKE_LOCK`) |

**Pros:** one code path on every Android version; no new dependency. **Cons:** we would rebuild the waiting, rescheduling and wake locks that the job gives for free, and resume after a kill would still depend on the app being opened.

### C. WorkManager, a long-running worker

| Dimension | Assessment |
| --- | --- |
| Complexity | A new library with its own database and components (an exported diagnostics receiver to strip, a boot receiver and `RECEIVE_BOOT_COMPLETED`) |
| Notification | Ours, through a foreground service it runs (`dataSync`, same limits as B) |
| Resume after kill | A worker restarted in the background may not go foreground on Android 12 and newer |
| Network rules | Built in |

**Pros:** constraints, retries and persistence. **Cons:** a sizeable new dependency for what JobScheduler already does, and it inherits B's foreground-service limits.

### D. A user-initiated data transfer job on Android 14+, the in-app queue on older versions (chosen)

| Dimension | Assessment |
| --- | --- |
| Notification | Ours: one for the whole queue |
| Network rules, resume | Built in: a network constraint, stopped and restarted by Android, rescheduled after a process death |
| Battery | Android holds the wake lock; no Doze or App Standby limits while it runs |
| Permissions | `RUN_USER_INITIATED_JOBS` (normal); nothing exported |
| Complexity | Medium: one job service and a queue; older Android keeps today's in-app path |
| Dependencies | None (platform API) |

**Pros:** built for exactly this kind of transfer; every ADR-0008 check stays in our code and the core. **Cons:** Android 13 and older get only the in-app behaviour; two hosts for one queue to test.

## Trade-off Analysis

DownloadManager gives resume and Wi-Fi rules, but it can't give one combined notification or a checked one-at-a-time install, and it can't write into private storage. It would still need B, C or D for the install, so it adds a second mechanism without removing one. B and C both end in a `dataSync` foreground service. That service has to be rebuilt to wait for networks, can't reliably come back after a kill, and is capped from Android 15. D lets Android do the waiting, rescheduling and wake locks, and leaves the transfer and every check in our code. The rider's phone runs a current Android. Older versions keep what works today, with the same notification and network rules.

## Consequences

- **Easier:** the rider starts a download, a restore or Update all and leaves the app; lost Wi-Fi, a killed app or a restart don't lose what has arrived.
- **Harder:** the queue now outlives the app's screens, so state lives in preferences and must be read defensively; the job and the in-app host both need testing; notifications, channels and a permission flow are new.
- **Security:** two new manifest entries, a job service and a receiver, neither exported; one new normal permission; no new library. The open-page intent on the launcher activity carries no data.
- **Revisit if:** the rider wants downloads to carry on after a phone restart without opening the app (persist the job: needs `RECEIVE_BOOT_COMPLETED`); Android retires or limits user-initiated jobs; an iOS port arrives (background `URLSession`).

## Notes from the build (2026-10-11)

- **Job network request:** the request no longer excludes VPN networks (`NOT_VPN` removed) and requires `NET_CAPABILITY_VALIDATED`, so the job waits for a network that has been checked to reach the internet. Metered and roaming are still inherited from the underlying networks; reviewed as correct.
- **Flaky connections:** JobScheduler applies its own exponential backoff between retries, on top of the 30 s steps above. Accepted.
- **Android 8 to 27:** roaming is read through the deprecated `NetworkInfo`, the only option on those versions.
- **Decisions made in the build:**
  - After a phone restart or a force stop, the queue resumes only when the app is opened.
  - Roaming is never used by the setting. It needs the once-only prompt and its warning.
  - Stop deletes the partial files of the queue.
  - A download started while one runs joins the end of the queue.

## Amendment (2026-10-12): no ready notice while the app is open

The rider (2026-10-12) follows the queue on the Map region page while the app is open, so a "ready" notice there is one more thing to clear. Only the ready notice changes: failed, waiting and retrying notices stay as decided above (he wants to see failures).

- **"Open" means the app's activity is started:** on screen, on any page, also with a dialog over it. The activity's own start and stop say so; a configuration change doesn't count as leaving. No new dependency: `ProcessLifecycleOwner` would need `lifecycle-process`, and with one activity its started state is the same signal.
- **Ready only while the app is away.** A batch that ends while the app is open, or that installed nothing, leaves no notice.
- **The job's notification goes with the job.** While a user-initiated job runs, the app can't take its notification away, and DETACH would leave the last progress notice behind. So a batch that ends without a notice sets the job's notification once more with `JOB_END_NOTIFICATION_POLICY_REMOVE` before the job finishes, and Android removes it. A ready, failed, waiting or retrying notice keeps DETACH.
- **Opening the app takes a ready notice away**, and a leftover progress notice once the queue is empty, never a failed one. Each notice carries its kind in its extras, so this also works after the app's process was killed while the notice stayed.
- **The progress notice stays while a region downloads,** also with the app open: Android requires it for the job on Android 14 and newer and doesn't let the app remove it. It is already on the low-importance channel, silent and alerting once.

## Action Items

- [x] App: the queue in preferences, checked on read, with its pure logic and unit tests (policy, prompt, queue edits, partial rules, retry rules, notification texts).
- [x] App: the user-initiated job (Android 14+) and the in-app host (8 to 13) running one queue runner; connections bound to the job's network; fresh signed manifest per run; partials by id with their SHA-256.
- [x] App: the combined notification, two channels, Stop receiver (not exported), open-page intent; the notification permission asked once.
- [x] App: Map region page: the mobile-data setting, the once-only prompt, the waiting, retrying and queued row states, the notifications-off note; downloads queue while one runs.
- [x] App: kill-safe install order, sweep of stray partial and temporary files, the network opened only for a running app.
- [x] App (amendment 2026-10-12): the ready notice only while the app is away; the job's notification removed when a batch ends without a notice; a ready or leftover notice taken away when the app opens; pure rules with unit tests.
- [ ] Phone test (amendment 2026-10-12): a batch that ends with the app open leaves no notification; one that ends with the app away shows "ready", gone once the app opens; a failure's notice stays.
- [ ] Phone test: background download with the screen off; Wi-Fi lost and back; force stop and phone restart mid-download; force stop while installing; mobile-data prompt both ways; permission refused; a restore with a switched-off region interrupted and resumed.

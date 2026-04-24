# Logan: Per-Type Log File Split and Filtered Upload

- **Date**: 2026-04-24
- **Status**: Approved (brainstorming)
- **Scope**: Android SDK + iOS SDK
- **Out of scope**: Clogan C core, wire format, server, LoganSite, Flutter plugin

## Motivation

The consumer of this SDK has a whitelist-driven upload flow where the backend assigns a `key` per upload task and the client POSTs log files back so the server can merge them by `key`. Today all types share one file per day, so any filtered upload (e.g. "only the user-behavior logs") requires either uploading the full day or doing server-side record-level filtering.

The user-behavior type is small in volume but has a low end-to-end upload success rate when it rides along with the full day's file. Splitting it into its own file lets the business upload it alone — smaller, faster, higher success rate.

## Goals

1. Each written log record lands in a file named `{day_millis}_{type}` instead of today's `{day_millis}`.
2. A new Android/iOS upload entry point lets the caller specify `(date, [types])` and receive one file per type.
3. The existing date-only upload entry point continues to work: internally it iterates every file for that date and invokes the caller's runnable / block per file.
4. No change to the on-wire log record format, to the Clogan C core, to the server, or to LoganSite.

## Non-goals

- Clogan C layer changes. The existing `clogan_open(pathname)` signature is preserved; the type-split happens entirely in the Java/ObjC layer, which feeds already-composed `{day}_{type}` strings to the C layer.
- Server-side changes. Each POST still carries one file in the existing binary format; records inside still carry `log_type` inline.
- Flutter plugin extension (follow-up, see §8).
- Fixing the pre-existing "caller forgets `SendLogRunnable.finish()` ⇒ queue stalls" hazard. Out of scope; changing its contract is riskier than the current bug.
- On-device migration of existing bare `{date}` files. They are recognized, uploaded if asked, and expire normally.

## Design

### 1. Invariants (must continue to hold after the change)

1. At any instant, Clogan owns **at most one open file**. A type switch ⇒ old file is `flush + close`d before the new one is opened. The mmap cache holds exactly one current pathname, same as today.
2. A single log record is written to exactly one file — the file for its `(today, type)`.
3. Legacy bare `{day}` files are still readable and still collected by the expiry sweep. The new SDK never *produces* a bare file.
4. Every HTTP POST still carries exactly one Clogan-format file. The server receives more POSTs per day than before, but each POST is bit-for-bit the same shape as today's.

### 2. Filename convention

The two platforms already use different per-day filename conventions. The split preserves each platform's existing "date portion" verbatim and appends `_{type}`:

| Platform | Today | After change |
|---|---|---|
| Android | `{day_millis}` e.g. `1502068800000` | `{day_millis}_{type}` e.g. `1502068800000_1` |
| iOS | `{yyyy-MM-dd}` e.g. `2025-04-24` | `{yyyy-MM-dd}_{type}` e.g. `2025-04-24_1` |

- Underscore, not dot, because the existing code on both platforms uses filename extensions as in-upload suffixes (Android `.copy`, iOS `.temp`) and parses by extension presence. A dot separator would collide.
- `type` is a non-negative integer matching the `int flag` already in `clogan_write`. No type-name mapping — SDK does not know business semantics.
- Legacy bare `{day_millis}` / `{yyyy-MM-dd}` files remain valid and readable. No new code produces them.
- In-upload snapshot suffix is unchanged per platform (`.copy` on Android, `.temp` on iOS).

### 3. Write-path state machine

Both platforms' write loops already track "current day" and rotate the Clogan file on day rollover. The change is symmetric: add a "current type" field and rotate on type change as well.

**Android `LoganThread`**:

```java
int mCurrentType = Integer.MIN_VALUE;  // sentinel = "no file open yet"
```

`doWriteLog2File(WriteAction action)` rotation condition changes from "day rolled over" to "day rolled over **or** type changed":

```java
if (dayRolledOver() || action.flag != mCurrentType) {
    long today = Util.getCurrentTime();
    deleteExpiredFile(today - mSaveTime);
    mCurrentDay  = today;
    mCurrentType = action.flag;
    mLoganProtocol.logan_open(mCurrentDay + "_" + mCurrentType);
}
```

**iOS `Logan.m`** — `writeLog:logType:` currently gates on `self.lastLogDate` equality against `currentDate` (`Logan.m:174-183`). Add a parallel `NSNumber *lastLogType` property (nullable — sentinel for "no file open yet") and extend the condition:

```objc
dispatch_async(self.loganQueue, ^{
    NSString *today = [Logan currentDate];
    NSNumber *currentType = @(type);
    if (!self.lastLogDate
        || ![self.lastLogDate isEqualToString:today]
        || !self.lastLogType
        || ![self.lastLogType isEqual:currentType]) {
        clogan_flush();
        NSString *filename = [NSString stringWithFormat:@"%@_%lu", today, (unsigned long)type];
        clogan_open((char *)filename.UTF8String);
    }
    self.lastLogDate = today;
    self.lastLogType = currentType;
    clogan_write(...);
});
```

`clogan_open` already flushes and closes the previous file internally (`clogan_core.c:387-410`), so neither platform needs an explicit pre-open flush at the wrapper layer. (The iOS code currently *does* call `clogan_flush()` before `clogan_open` on day rollover — a belt-and-suspenders no-op kept for consistency with existing style.)

**Accepted quirks (documented, not fixed in this change)**:

- Clogan inserts a synthetic `flag=1, "clogan header"` record at the first write into every new file (`insert_header_file_clogan`). With the split, every `{day}_{type}` file gets one. The README already warns against using `type=1` for business logs; this change does not widen or narrow that warning.
- `LoganConfig.mMaxFile` remains a **per-file** cap. Aggregate daily disk usage is bounded by `mMaxFile × (types written that day)` instead of `mMaxFile`. Business can lower `mMaxFile` if disk budget matters.

### 4. Upload path

#### 4.1 New Android public API

```java
/**
 * Upload logs for a date, filtered by type.
 *
 * @param date     "yyyy-MM-dd".
 * @param types    types to upload. A {@code null} element means the
 *                 legacy bare {date} file produced by pre-upgrade SDK
 *                 versions. Pass {@code null} or empty array to upload
 *                 every file present for the date (legacy + all types).
 * @param runnable caller-provided uploader. Invoked once per matched
 *                 file on the background send executor; caller MUST
 *                 call {@link SendLogRunnable#finish} after each.
 */
public static void s(String date, Integer[] types, SendLogRunnable runnable);
```

All other public method signatures are unchanged.

#### 4.2 New iOS public API

```objc
/**
 Resolve one file path per (date, type) and invoke the block once per matched file.

 @param date          @"yyyy-MM-dd".
 @param types         NSArray<NSNumber *>. NSNull members mean the
                      legacy bare {yyyy-MM-dd} file. nil or empty array ⇒
                      every file present for the date.
 @param filePathBlock invoked once per **existing** file, on the main thread.
                      Missing files are skipped (not invoked with nil),
                      to avoid spamming callers for unrequested types.
 */
extern void loganUploadFilePathWithTypes(NSString *date,
                                          NSArray *types,
                                          LoganFilePathBlock filePathBlock);
```

Existing `loganUploadFilePath(date, block)` and `loganUpload(url, date, ..., resultBlock)` keep their signatures. Their implementations (`Logan.m:99-102` and the class method `uploadFileToServer:date:...` at `Logan.m:394-437`) are updated so that when the date has multiple files on disk, the block / resultBlock is invoked once per file — see §5 for the callback-count behavior change.

The existing **zero-match** signal is preserved: if a date yields no files at all (neither legacy nor typed), `loganUploadFilePath`'s block is still invoked exactly once with `filePath = nil`, and `loganUpload`'s resultBlock is still invoked exactly once with the "can't find file" `NSError`. This keeps callers of the one-shot form from silently losing the "nothing to upload" signal they rely on today.

This skip-if-missing semantics is a **deliberate divergence** from the existing `loganUploadFilePath` single-shot behavior (which calls back with `nil` for a missing date file). The multi-element form would otherwise produce N `nil` calls for all-missing type lists, which is noisy. Documented in the header.

#### 4.3 `SendAction` extension (Android)

```java
class SendAction {
    String date;         // existing
    Integer type;        // new. null = legacy bare {date}; non-null = {date}_{type}
    String uploadPath;   // existing, filled by prepareLogFile
    SendLogRunnable sendLogRunnable;
}
```

#### 4.4 `prepareLogFile` logic

Given `(date, type)`:

1. Compose `srcName`:
   - `type == null` ⇒ `srcName = date`
   - else ⇒ `srcName = date + "_" + type`
2. If `mPath/srcName` does not exist ⇒ drop the action silently (matches existing "missing date file" behavior).
3. If `date == today`:
   - Call `doFlushLog2File()`.
   - Copy to `srcName + ".copy"`, upload the copy.
   - Reason: upload runs on `mSingleThreadExecutor` while `LoganThread` can accept new writes for that same type at any time; the flushed copy is our snapshot.
4. Else: `uploadPath = mPath/srcName`, upload directly — past-day files are frozen.

#### 4.5 Dispatch via `LoganControlCenter.send(date, types, runnable)`

```
if (types == null || types.length == 0) {
    // enumerate every file for date (see §4.6)
    enqueueAllFilesForDate(date, runnable);
} else {
    for (Integer t : types) {
        enqueueSendModel(date, t, runnable);  // t may be null for legacy
    }
}
```

SEND models enter the existing `mCacheLogQueue`. `LoganThread`'s existing serial loop + `mSendLogStatusCode == SENDING` gate ensures the caller's `sendLog(File)` is invoked serially, one file at a time, gated on `finish()`.

#### 4.6 Old `Logan.s(String[] dates, runnable)` internal change

For each date, list `mPath` entries matching either:

- the exact string `date_millis`, or
- the regex `date_millis_\d+`

and enqueue one SEND model per match. Each enqueued model flows through the same `prepareLogFile` logic (§4.4).

**Behavior change (accepted)**: the caller's `SendLogRunnable.sendLog(File)` is now invoked N times per date, where N is the number of matching files. This is consistent with the existing contract — multi-date callers were already getting N invocations — and with how `SendLogRunnable` is documented ("Must Call finish after send log").

#### 4.7 Built-in HTTP `Logan.s(url, date, ..., SendLogCallback)` — inherited change

`Logan.java:105-113` wraps the built-in HTTP overload as a `SendLogDefaultRunnable` fed to `sLoganControlCenter.send(new String[]{date}, ...)`. Because that path now iterates per file, **`SendLogCallback.onLogSendCompleted(int, byte[])` is invoked once per matched file** after the change. Documented in the release notes; callers that need aggregate results must either accumulate in the callback or migrate to the `SendLogRunnable`-based API.

#### 4.8 Expiry parser updates

**Android — `LoganThread.deleteExpiredFile` (`LoganThread.java:172-195`)**: current code parses `item.split("\\.")[0]` as the date millis and requires `split("\\.").length == 1` (i.e. no dot ⇒ no `.copy`). The parser must also handle an underscore. Updated rule:

```
if name ends with ".copy": skip (active upload snapshot)
dateMillis = Long.valueOf(name.split("_")[0])   // works for both "1502068800000" and "1502068800000_1"
if dateMillis <= deleteTime: delete
```

Covers `1502068800000`, `1502068800000_1`; skips `1502068800000.copy`, `1502068800000_1.copy`; NumberFormatException on `garbage` is caught and the entry is left alone.

**iOS — `+ [Logan deleteOutdatedFiles]` (`Logan.m:439-466`)**: current code unconditionally deletes any filename with a non-empty `pathExtension` AND any filename whose length ≠ 10 (i.e. ≠ `yyyy-MM-dd`). Both rules reject new-format `yyyy-MM-dd_{type}` files (length 12, no extension) as "garbage" and delete them. Replace with:

```
if pathExtension.length > 0: delete   (e.g. orphan .temp files — existing behavior)
base = name up to the first '_' (if any)
if base.length != 10: delete          (genuinely bad names, matching old intent)
date = formatter.dateFromString(base)
if !date || getDaysFrom:date to:today >= __max_reversed_date: delete
```

Likewise `+ [Logan allFilesInfo]` (`Logan.m:376-390`) currently slices the first 10 chars of each name to key the returned dictionary. Update it to slice up to the first `_` so `yyyy-MM-dd_{type}` contributes to the `yyyy-MM-dd` key, **aggregating sizes across types**. This preserves the public API shape (`{date → totalBytes}`); per-type size info is a follow-up (§8) if needed.

### 5. Compatibility summary

| Surface | Behavior after change |
|---|---|
| `Logan.w(log, type)` | Unchanged |
| `Logan.f()` | Unchanged |
| `Logan.init(config)` | Unchanged |
| `Logan.s(String[] dates, runnable)` | Signature unchanged. Callback invoked N times per date instead of 1. |
| `Logan.s(url, date, ..., SendLogCallback)` (built-in HTTP) | Signature unchanged. Callback invoked N times per date. |
| Legacy bare `{date}` files on disk | Still uploadable (via new API with `null` element, or via old API which enumerates everything). Still expired by `deleteExpiredFile`. |
| Wire format of a single upload | Unchanged (bit-for-bit) |
| Server tables, `ContentHandler`, LoganSite | Unchanged |
| Old app versions in production | Unaffected. Their single-file POSTs continue to be accepted by the unchanged server. |
| Flutter plugin | Unchanged in this spec (follow-up) |

### 6. Error handling and edge cases

| Case | Behavior |
|---|---|
| `types` array contains a type that was never written ⇒ no `{date}_{type}` file exists | Action dropped silently at `prepareLogFile` step 2. `runnable.sendLog` is not called for this element. |
| `types` yields zero matches overall | Runnable is never invoked. Same as today when `dates` contains only unknown dates. |
| `(today, type=X)` requested but `{today}_X` does not exist (never written today) | Dropped silently. |
| `(today, type=X)` requested while `mCurrentType == X` | `doFlushLog2File` + `.copy` + upload copy. |
| `(today, type=X)` requested while `mCurrentType != X` | `{today}_X` is already durable on disk (rotated out earlier today). Still `.copy`-and-upload to be safe against a future rotation back to X mid-upload. |
| Caller forgets `SendLogRunnable.finish()` | Existing hazard: queue stalls. Not addressed in this change. |
| `SendLogRunnable` instance reused across N invocations | `setSendAction` rewrites `action` each time; caller-owned state is the caller's responsibility. Javadoc explicitly notes this. |
| Upgrade moment with pending mmap data for a legacy bare file | On first `clogan_init` in the new SDK, `read_mmap_data_clogan` replays pending bytes into the bare `{date}` path recorded in the mmap header. That file is then frozen; new writes from this SDK go to `{date}_{type}`. No data loss. |

### 7. Testing strategy

**JVM unit tests (`./gradlew :logan:test`)** — a new `logan/src/test/java/com/dianping/logan/` tree:

1. Pure function `FileNames.parseLogFile(String name)` → `{dateMillis, type, isCopy}` or null. Table-driven: `1502068800000`, `1502068800000_1`, `1502068800000.copy`, `1502068800000_1.copy`, `garbage`, `1502068800000_abc`, `_1`.
2. Pure function `shouldRotate(currentDay, currentType, newActionFlag, now)` — cross-day, cross-type, first-call, idempotent same-(day,type).
3. `deleteExpiredFile` acting on a `@TempDir`: mixed legacy + typed + `.copy` files, assert only non-`.copy` files with `dateMillis <= deleteTime` are removed.
4. `LoganControlCenter.send(date, types, runnable)` enqueue count: fake `LoganThread`, assert N models enqueued.

**Demo-app manual validation (Android + iOS)** — documented in the PR description:

1. Write `type=1,2,3` → verify three files `{today}_1`, `{today}_2`, `{today}_3` appear under the log directory.
2. `Logan.s(today, new Integer[]{1}, runnable)` → one `sendLog(File)` call, file name ends with `{today}_1.copy`.
3. `Logan.s(today, new Integer[]{1, 2, null}, runnable)` with a manually-planted bare `{today}` → three `sendLog(File)` calls.
4. System-time jump to the next day → next write triggers `logan_open` with the new date; the previous file stays intact.
5. Expiry: set `mSaveTime` small, plant old-date files (legacy + typed), verify all are deleted.

**Not in scope**:

- No new Clogan C tests (C core unchanged).
- No server or LoganSite tests.
- Caller-side (business) key-merging behavior lives in the consumer repo, not this SDK.

### 8. Out of scope / follow-ups

- **Flutter plugin** (`Flutter/lib/flutter_logan.dart` + platform channel): surface the new `(date, types)` upload. Deferred — not required for the immediate business need.
- **Synthetic `clogan header` filtering**: currently one appears per new file. If this becomes noisy in LoganSite, fix is a Clogan-core change (tag the synthetic record distinguishably) — out of scope here.
- **"Caller forgets `finish()`" safety net**: a timeout or auto-finish in `LoganThread`. Out of scope; its own design discussion.

### 9. Files touched (implementation index)

Android:

- `Example/Logan-Android/logan/src/main/java/com/dianping/logan/Logan.java` — add new `s(date, types, runnable)` overload.
- `.../LoganControlCenter.java` — add dispatch for new overload; extend `send(...)` path that builds SEND models.
- `.../LoganThread.java` — add `mCurrentType`, update rotation condition; update `prepareLogFile`; update `deleteExpiredFile` parser.
- `.../SendAction.java` — add `Integer type` field.
- New: `.../FileNames.java` — package-private pure parser utility (keeps the existing flat package convention; testable).
- New: `logan/src/test/java/com/dianping/logan/FileNamesTest.java` and sibling tests.

iOS:

- `Logan/iOS/Logan.h` — declare `loganUploadFilePathWithTypes`.
- `Logan/iOS/Logan.m` —
  - Add `lastLogType` property on the `Logan` singleton; extend `writeLog:logType:` rotation condition (§3).
  - Implement `loganUploadFilePathWithTypes`; factor out the `filePathForDate:block:` body into a per-file helper and iterate it for the types-aware variant.
  - Update `+ deleteOutdatedFiles` (§4.8) to recognize `yyyy-MM-dd_{type}` names.
  - Update `+ allFilesInfo` (§4.8) to key on the date prefix before `_`, aggregating sizes per date.
  - Update `+ uploadFileToServer:...` (the `loganUpload` built-in HTTP) to iterate matching files for the date, invoking `resultBlock` once per file.

Unchanged:

- `Logan/Clogan/**` — zero changes.
- `Logan/Server/**`, `Logan/LoganSite/**` — zero changes.
- `Logan/WebSDK/**`, `Flutter/**` — zero changes (see §8 for Flutter follow-up).

### 10. Rollout

1. Merge on `master` per `CONTRIBUTING.md`.
2. Release notes call out §4.7 (built-in HTTP callback now N-per-date) and §4.6 (old date-only runnable API now N-per-date) as behavior changes.
3. Business consumers update their upload code to the new `(date, types)` API to use the per-type key-merge feature.
4. Server continues to accept both the legacy-app single-file uploads and the new multi-type uploads without change.

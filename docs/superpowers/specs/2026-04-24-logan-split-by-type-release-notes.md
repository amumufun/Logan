# Logan SDK release notes — per-type log file split

## What changed (Android + iOS)

- Each log record is now written to `{date}_{type}` instead of `{date}`.
- New API:
  - Android `Logan.s(String date, Integer[] types, SendLogRunnable runnable)`
  - iOS `loganUploadFilePathWithTypes(NSString *date, NSArray *types, LoganFilePathBlock block)`
- Wire format, server, LoganSite, Clogan C core: **unchanged**.

## Behavior changes for existing APIs

- **Android `Logan.s(String[] dates, SendLogRunnable runnable)`**: each date now
  yields one `sendLog(File)` invocation per matching file on disk (was 1 per
  date). Callers must call `finish()` after every invocation.
- **Android `Logan.s(url, date, ..., SendLogCallback)` (built-in HTTP)**: same —
  the callback is invoked once per file uploaded, not once per date. Callers
  that need an aggregate result must accumulate in the callback.
- **iOS `loganUploadFilePath(date, block)`**: block is invoked once per matching
  file. Zero-match still produces a single `block(nil)` call (unchanged).
- **iOS `loganUpload(url, date, ..., resultBlock)`**: `resultBlock` is invoked
  once per matching file. Zero-match still produces a single error callback.

## Compatibility

- Legacy bare `{date}` files left over from an older SDK are still readable,
  uploadable (via the new API with a `null` / `NSNull` element, or via the old
  enumerate-everything API), and expired by the existing expiry sweep.
- Old client-app builds in production are unaffected (server is unchanged).

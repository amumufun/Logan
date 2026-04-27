# Logan: Per-Type Log File Split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split each platform's daily Logan log file into one file per `(date, type)`, and add a new upload entry point that takes `(date, types[])`, while keeping every existing API signature, the wire format, the C core, and the server unchanged.

**Architecture:** All work lives in the Java/ObjC wrappers that sit on top of `clogan_open(pathname)`. The wrappers compose `{date}_{type}` pathnames, track a new `currentType` field alongside `currentDay`, and rotate the C-layer file whenever either changes. Uploaders enumerate matching files on disk and dispatch one HTTP POST per file via the existing serial send queue. Legacy bare-`{date}` files remain readable; the new SDK never produces them.

**Tech Stack:** Android (Java, JUnit 4.12, JNI to shared `Clogan`), iOS (Objective-C, GCD `loganQueue` serial dispatch, mbedtls/Clogan via `clogan_core.h`).

**Spec:** `docs/superpowers/specs/2026-04-24-logan-split-by-type-design.md`

---

## File Structure

### Android (`Example/Logan-Android/logan/src/main/java/com/dianping/logan/`)

| File | Responsibility | Change |
|---|---|---|
| `FileNames.java` | Pure parser/composer for `{day}` / `{day}_{type}` / `{...}.copy` filenames. Single source of truth for filename grammar. | **New** |
| `SendAction.java` | Per-send envelope. Add `Integer type` (null = legacy bare). | Modify |
| `LoganThread.java` | Add `mCurrentType` field; rotate C file on type change; teach `prepareLogFile` to use `(date, type)`; teach `deleteExpiredFile` to handle the underscore form. | Modify |
| `LoganControlCenter.java` | Add `send(date, types, runnable)` overload. Extend the legacy `send(dates, runnable)` path to enumerate every file matching each date. | Modify |
| `Logan.java` | Add public `s(date, types, runnable)` overload. Update `getAllFilesInfo()` to aggregate sizes per date across types. | Modify |

### Android tests (`Example/Logan-Android/logan/src/test/java/com/dianping/logan/`)

| File | Responsibility | Change |
|---|---|---|
| `FileNamesTest.java` | Table-driven parse/compose tests. | **New** |
| `DeleteExpiredFileTest.java` | Drives `LoganThread.deleteExpiredFile` via reflection over a `TemporaryFolder` of mixed legacy/typed/`.copy` files. | **New** |

### iOS (`Logan/iOS/`)

| File | Responsibility | Change |
|---|---|---|
| `Logan.h` | Declare new `loganUploadFilePathWithTypes`. | Modify |
| `Logan.m` | Add `lastLogType` property; rotate Clogan on type change in `writeLog:logType:`; refactor `filePathForDate:block:` to enumerate matching files (legacy + typed); implement `loganUploadFilePathWithTypes`; update `+deleteOutdatedFiles` and `+allFilesInfo` parsers. | Modify |

No changes to `Logan/Clogan/**`, `Logan/Server/**`, `Logan/LoganSite/**`, `Logan/WebSDK/**`, or `Flutter/**`.

---

## Order and dependencies

Tasks are grouped Android-first, then iOS, then validation. Within Android the order is bottom-up (parser → state → dispatch → API) so each commit compiles. Tasks 1–7 (Android) and tasks 8–10 (iOS) are independent of each other once the spec is fixed; you may interleave platforms if convenient.

---

## Task 1: Android — `FileNames` parser/composer (TDD)

**Files:**
- Create: `Example/Logan-Android/logan/src/main/java/com/dianping/logan/FileNames.java`
- Create: `Example/Logan-Android/logan/src/test/java/com/dianping/logan/FileNamesTest.java`

This file is the single source of truth for Logan filename grammar on Android. Every later task that parses or composes a name calls into here.

- [ ] **Step 1: Write the failing test**

Create `Example/Logan-Android/logan/src/test/java/com/dianping/logan/FileNamesTest.java`:

```java
/*
 * Copyright (c) 2018-present, 美团点评
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dianping.logan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileNamesTest {

    private static final long DAY = 1502068800000L;

    @Test
    public void parse_legacyBare() {
        FileNames.Parsed p = FileNames.parse("1502068800000");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertNull(p.type);
        assertFalse(p.isCopy);
    }

    @Test
    public void parse_typed() {
        FileNames.Parsed p = FileNames.parse("1502068800000_1");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertEquals(Integer.valueOf(1), p.type);
        assertFalse(p.isCopy);
    }

    @Test
    public void parse_legacyCopy() {
        FileNames.Parsed p = FileNames.parse("1502068800000.copy");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertNull(p.type);
        assertTrue(p.isCopy);
    }

    @Test
    public void parse_typedCopy() {
        FileNames.Parsed p = FileNames.parse("1502068800000_1.copy");
        assertNotNull(p);
        assertEquals(DAY, p.dateMillis);
        assertEquals(Integer.valueOf(1), p.type);
        assertTrue(p.isCopy);
    }

    @Test
    public void parse_garbageReturnsNull() {
        assertNull(FileNames.parse("garbage"));
        assertNull(FileNames.parse(""));
        assertNull(FileNames.parse(null));
        assertNull(FileNames.parse("_1"));
        assertNull(FileNames.parse("1502068800000_abc"));
        assertNull(FileNames.parse("abc_1"));
    }

    @Test
    public void parse_extraSegmentsRejected() {
        assertNull(FileNames.parse("1502068800000_1_extra"));
    }

    @Test
    public void compose_legacy() {
        assertEquals("1502068800000", FileNames.compose(DAY, null));
    }

    @Test
    public void compose_typed() {
        assertEquals("1502068800000_1", FileNames.compose(DAY, 1));
        assertEquals("1502068800000_0", FileNames.compose(DAY, 0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd Example/Logan-Android && ./gradlew :logan:test --tests com.dianping.logan.FileNamesTest
```

Expected: BUILD FAILED, compilation error "cannot find symbol class FileNames".

- [ ] **Step 3: Write the implementation**

Create `Example/Logan-Android/logan/src/main/java/com/dianping/logan/FileNames.java`:

```java
/*
 * Copyright (c) 2018-present, 美团点评
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dianping.logan;

final class FileNames {

    static final String COPY_SUFFIX = ".copy";

    private FileNames() {}

    static final class Parsed {
        final long dateMillis;
        final Integer type;     // null => legacy bare {date}
        final boolean isCopy;

        Parsed(long dateMillis, Integer type, boolean isCopy) {
            this.dateMillis = dateMillis;
            this.type = type;
            this.isCopy = isCopy;
        }
    }

    /**
     * Parses a Logan log filename. Returns null if the name does not match
     * either {date} / {date}_{type} / {date}.copy / {date}_{type}.copy.
     */
    static Parsed parse(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String body = name;
        boolean isCopy = false;
        if (name.endsWith(COPY_SUFFIX)) {
            isCopy = true;
            body = name.substring(0, name.length() - COPY_SUFFIX.length());
        }
        String[] parts = body.split("_", -1);
        if (parts.length < 1 || parts.length > 2) {
            return null;
        }
        long dateMillis;
        try {
            dateMillis = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        Integer type = null;
        if (parts.length == 2) {
            try {
                type = Integer.valueOf(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Parsed(dateMillis, type, isCopy);
    }

    /**
     * Composes a Logan log filename for a given date and (optional) type.
     * type == null -> legacy bare form {dateMillis}.
     */
    static String compose(long dateMillis, Integer type) {
        if (type == null) {
            return String.valueOf(dateMillis);
        }
        return dateMillis + "_" + type;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd Example/Logan-Android && ./gradlew :logan:test --tests com.dianping.logan.FileNamesTest
```

Expected: BUILD SUCCESSFUL, 8 tests run.

- [ ] **Step 5: Commit**

```bash
git add Example/Logan-Android/logan/src/main/java/com/dianping/logan/FileNames.java Example/Logan-Android/logan/src/test/java/com/dianping/logan/FileNamesTest.java
git commit -m "$(cat <<'EOF'
feat(android): add FileNames parser/composer for {date}_{type} grammar

Single source of truth for Logan log filename parsing on Android.
Recognizes legacy bare {date}, new {date}_{type}, and either with the
.copy in-upload suffix. Returns null on anything else. Backed by
table-driven JUnit tests.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Android — extend `SendAction` with `Integer type`

**Files:**
- Modify: `Example/Logan-Android/logan/src/main/java/com/dianping/logan/SendAction.java`

`SendAction` is the per-send envelope passed from `LoganControlCenter` to `LoganThread`. It needs to carry which type's file we're uploading so `prepareLogFile` can compose the right pathname. `null` means the legacy bare `{date}` file.

- [ ] **Step 1: Add the field**

Edit `Example/Logan-Android/logan/src/main/java/com/dianping/logan/SendAction.java`. Replace the body of `class SendAction { ... }` with:

```java
class SendAction {

    long fileSize; //文件大小

    String date; //日期 (yyyy-MM-dd 格式被解析后的 dayMillis 字符串)

    /**
     * 日志类型。null 表示旧版未拆分的 {date} 文件；非空表示 {date}_{type} 文件。
     */
    Integer type;

    String uploadPath;

    SendLogRunnable sendLogRunnable;

    boolean isValid() {
        boolean valid = false;
        if (sendLogRunnable != null) {
            valid = true;
        } else if (fileSize > 0) {
            valid = true;
        }
        return valid;
    }
}
```

- [ ] **Step 2: Verify it still compiles**

```bash
cd Example/Logan-Android && ./gradlew :logan:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL. (No callers set the new field yet — Java leaves it `null` by default, which is the legacy semantics.)

- [ ] **Step 3: Commit**

```bash
git add Example/Logan-Android/logan/src/main/java/com/dianping/logan/SendAction.java
git commit -m "$(cat <<'EOF'
feat(android): add Integer type to SendAction

null preserves the legacy bare {date} upload semantics; a non-null
type targets the new {date}_{type} file. Wired up by callers in
later commits.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Android — `LoganThread` write rotation on type change

**Files:**
- Modify: `Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganThread.java`

The write loop currently rotates the Clogan-owned file only when the day rolls over. It must also rotate when the incoming `WriteAction.flag` differs from the type currently open. The synthetic "clogan header" record `clogan_open` produces is acceptable per spec §3.

- [ ] **Step 1: Add `mCurrentType` field**

In `LoganThread.java`, find the line `private long mCurrentDay;` (near line 50) and add directly below it:

```java
    private long mCurrentDay;
    private int mCurrentType = Integer.MIN_VALUE; // sentinel: no file open yet
```

`Integer.MIN_VALUE` is a sentinel that no real `WriteAction.flag` value will collide with, forcing the first write to rotate.

- [ ] **Step 2: Rewrite the rotation block in `doWriteLog2File`**

In `LoganThread.java`, locate the existing rotation block (currently lines 205-212):

```java
        if (!isDay()) {
            long tempCurrentDay = Util.getCurrentTime();
            //save时间
            long deleteTime = tempCurrentDay - mSaveTime;
            deleteExpiredFile(deleteTime);
            mCurrentDay = tempCurrentDay;
            mLoganProtocol.logan_open(String.valueOf(mCurrentDay));
        }
```

Replace it with:

```java
        if (!isDay() || action.flag != mCurrentType) {
            long tempCurrentDay = Util.getCurrentTime();
            if (!isDay()) {
                long deleteTime = tempCurrentDay - mSaveTime;
                deleteExpiredFile(deleteTime);
                mCurrentDay = tempCurrentDay;
            }
            mCurrentType = action.flag;
            mLoganProtocol.logan_open(FileNames.compose(mCurrentDay, mCurrentType));
        }
```

`clogan_open` flushes and closes the previously-open file internally (`Logan/Clogan/clogan_core.c:387-410`), so no explicit `logan_flush()` call is needed at the wrapper layer.

- [ ] **Step 3: Verify the module still compiles**

```bash
cd Example/Logan-Android && ./gradlew :logan:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganThread.java
git commit -m "$(cat <<'EOF'
feat(android): rotate Logan file on (day, type) change

Adds mCurrentType tracking to LoganThread and triggers logan_open
whenever the next WriteAction's flag differs from the currently open
file's type. Day-rollover bookkeeping (expired-file sweep) is
preserved and only runs on the day branch.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Android — `LoganThread.deleteExpiredFile` parser update (TDD)

**Files:**
- Modify: `Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganThread.java`
- Create: `Example/Logan-Android/logan/src/test/java/com/dianping/logan/DeleteExpiredFileTest.java`

The current parser splits on `\\.` and treats `length == 1` as "no extension, parse as date." That deletes typed files (`1502068800000_1`) because the segment-zero string `1502068800000_1` is not a valid `Long`. The fix: skip `.copy` snapshots, then parse the prefix up to the first `_`.

`deleteExpiredFile` is package-private and `LoganThread`'s test surface is currently zero. We test it by extracting the parsing decision into a package-private static helper, which keeps the change minimal and testable.

- [ ] **Step 1: Write the failing test**

Create `Example/Logan-Android/logan/src/test/java/com/dianping/logan/DeleteExpiredFileTest.java`:

```java
/*
 * Copyright (c) 2018-present, 美团点评
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.dianping.logan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeleteExpiredFileTest {

    private static final long TODAY     = 1502068800000L;     // any "today"
    private static final long YESTERDAY = TODAY - 86_400_000L;
    private static final long WEEK_AGO  = TODAY - 7L * 86_400_000L;

    private static boolean shouldDelete(String name, long deleteTime) {
        return LoganThread.shouldDeleteForExpiry(name, deleteTime);
    }

    @Test
    public void deletesLegacyExpired() {
        assertTrue(shouldDelete(String.valueOf(WEEK_AGO), YESTERDAY));
    }

    @Test
    public void deletesTypedExpired() {
        assertTrue(shouldDelete(WEEK_AGO + "_1", YESTERDAY));
        assertTrue(shouldDelete(WEEK_AGO + "_42", YESTERDAY));
    }

    @Test
    public void keepsLegacyFresh() {
        assertFalse(shouldDelete(String.valueOf(TODAY), YESTERDAY));
    }

    @Test
    public void keepsTypedFresh() {
        assertFalse(shouldDelete(TODAY + "_1", YESTERDAY));
    }

    @Test
    public void skipsCopySnapshots() {
        assertFalse(shouldDelete(WEEK_AGO + ".copy", YESTERDAY));
        assertFalse(shouldDelete(WEEK_AGO + "_1.copy", YESTERDAY));
    }

    @Test
    public void skipsGarbage() {
        assertFalse(shouldDelete("garbage", YESTERDAY));
        assertFalse(shouldDelete("", YESTERDAY));
        assertFalse(shouldDelete("_1", YESTERDAY));
    }

    @Test
    public void boundaryEqualsDeleteTime() {
        // spec preserves <=  rule from the original parser
        assertTrue(shouldDelete(String.valueOf(YESTERDAY), YESTERDAY));
        assertTrue(shouldDelete(YESTERDAY + "_1", YESTERDAY));
    }

    @Test
    public void shouldDelete_nonNullSafety() {
        assertEquals(false, shouldDelete(null, YESTERDAY));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd Example/Logan-Android && ./gradlew :logan:test --tests com.dianping.logan.DeleteExpiredFileTest
```

Expected: BUILD FAILED, compilation error "cannot find symbol method shouldDeleteForExpiry".

- [ ] **Step 3: Add the package-private decision function and rewire the loop**

In `LoganThread.java`, locate the existing `deleteExpiredFile` method (lines 172-195):

```java
    private void deleteExpiredFile(long deleteTime) {
        File dir = new File(mPath);
        if (dir.isDirectory()) {
            String[] files = dir.list();
            if (files != null) {
                for (String item : files) {
                    try {
                        if (TextUtils.isEmpty(item)) {
                            continue;
                        }
                        String[] longStrArray = item.split("\\.");
                        if (longStrArray.length > 0) {  //小于时间就删除
                            long longItem = Long.valueOf(longStrArray[0]);
                            if (longItem <= deleteTime && longStrArray.length == 1) {
                                new File(mPath, item).delete(); //删除文件
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
```

Replace with:

```java
    private void deleteExpiredFile(long deleteTime) {
        File dir = new File(mPath);
        if (!dir.isDirectory()) {
            return;
        }
        String[] files = dir.list();
        if (files == null) {
            return;
        }
        for (String item : files) {
            if (shouldDeleteForExpiry(item, deleteTime)) {
                new File(mPath, item).delete();
            }
        }
    }

    /**
     * Pure decision function: does {@code name} belong to a non-{@code .copy}
     * Logan file whose date is on or before {@code deleteTime}?
     *
     * <p>Visible for testing.
     */
    static boolean shouldDeleteForExpiry(String name, long deleteTime) {
        FileNames.Parsed p = FileNames.parse(name);
        if (p == null || p.isCopy) {
            return false;
        }
        return p.dateMillis <= deleteTime;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd Example/Logan-Android && ./gradlew :logan:test --tests com.dianping.logan.DeleteExpiredFileTest
```

Expected: BUILD SUCCESSFUL, 8 tests run.

- [ ] **Step 5: Commit**

```bash
git add Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganThread.java Example/Logan-Android/logan/src/test/java/com/dianping/logan/DeleteExpiredFileTest.java
git commit -m "$(cat <<'EOF'
fix(android): expire {date}_{type} files; skip .copy snapshots

Routes deleteExpiredFile through FileNames.parse so the underscore
form is recognized. Active in-upload .copy files are now explicitly
skipped instead of being silently rejected by the long-parse failure.
Adds JUnit coverage on the new shouldDeleteForExpiry decision.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Android — `LoganThread.prepareLogFile` per-(date,type) source path

**Files:**
- Modify: `Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganThread.java`

`prepareLogFile(SendAction action)` must compose the source filename from `(date, type)` instead of `date` alone, while preserving the today→`.copy` snapshot path and past→direct upload path.

- [ ] **Step 1: Replace `prepareLogFile`**

In `LoganThread.java`, locate the existing method (lines 280-301):

```java
    private boolean prepareLogFile(SendAction action) {
        if (Logan.sDebug) {
            Log.d(TAG, "prepare log file");
        }
        if (isFile(action.date)) { //是否有日期文件
            String src = mPath + File.separator + action.date;
            if (action.date.equals(String.valueOf(Util.getCurrentTime()))) {
                doFlushLog2File();
                String des = mPath + File.separator + action.date + ".copy";
                if (copyFile(src, des)) {
                    action.uploadPath = des;
                    return true;
                }
            } else {
                action.uploadPath = src;
                return true;
            }
        } else {
            action.uploadPath = "";
        }
        return false;
    }
```

Replace with:

```java
    private boolean prepareLogFile(SendAction action) {
        if (Logan.sDebug) {
            Log.d(TAG, "prepare log file");
        }
        long dateMillis;
        try {
            dateMillis = Long.parseLong(action.date);
        } catch (NumberFormatException e) {
            action.uploadPath = "";
            return false;
        }
        String srcName = FileNames.compose(dateMillis, action.type);
        if (!isFile(srcName)) {
            action.uploadPath = "";
            return false;
        }
        String src = mPath + File.separator + srcName;
        if (dateMillis == Util.getCurrentTime()) {
            doFlushLog2File();
            String des = src + FileNames.COPY_SUFFIX;
            if (copyFile(src, des)) {
                action.uploadPath = des;
                return true;
            }
            action.uploadPath = "";
            return false;
        }
        action.uploadPath = src;
        return true;
    }
```

- [ ] **Step 2: Verify the module still compiles**

```bash
cd Example/Logan-Android && ./gradlew :logan:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all tests**

```bash
cd Example/Logan-Android && ./gradlew :logan:test
```

Expected: BUILD SUCCESSFUL. (Existing tests + Tasks 1, 4 should all pass.)

- [ ] **Step 4: Commit**

```bash
git add Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganThread.java
git commit -m "$(cat <<'EOF'
feat(android): prepareLogFile composes {date}_{type} source path

Picks the source pathname from (action.date, action.type) via
FileNames.compose. action.type == null still resolves to the legacy
bare {date} file. Today's file is still .copy-snapshotted before
upload; past-day files are uploaded directly.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Android — `LoganControlCenter.send` typed-and-enumerated dispatch

**Files:**
- Modify: `Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganControlCenter.java`

Two changes here:

1. The legacy `send(String[] dates, runnable)` enumerates every file present for each date and enqueues one SEND model per match. Behavior change: callers who hit a date with N typed files now receive N `sendLog(File)` calls (was 1). This is documented in spec §4.6.
2. A new `send(String date, Integer[] types, runnable)` enqueues one SEND model per requested type (or, when `types` is null/empty, falls through to enumeration like the legacy path).

- [ ] **Step 1: Add a private enqueue helper**

In `LoganControlCenter.java`, find the existing `send(String dates[], SendLogRunnable runnable)` method (lines 114-136). Insert this private helper directly below it (still inside the class):

```java
    private void enqueueSend(long dateMillis, Integer type, SendLogRunnable runnable) {
        LoganModel model = new LoganModel();
        SendAction action = new SendAction();
        model.action = LoganModel.Action.SEND;
        action.date = String.valueOf(dateMillis);
        action.type = type;
        action.sendLogRunnable = runnable;
        model.sendAction = action;
        mCacheLogQueue.add(model);
        if (mLoganThread != null) {
            mLoganThread.notifyRun();
        }
    }

    private void enqueueAllForDate(long dateMillis, SendLogRunnable runnable) {
        File dir = new File(mPath);
        String[] entries = dir.isDirectory() ? dir.list() : null;
        if (entries == null) {
            return;
        }
        for (String name : entries) {
            FileNames.Parsed p = FileNames.parse(name);
            if (p == null || p.isCopy) {
                continue;
            }
            if (p.dateMillis == dateMillis) {
                enqueueSend(dateMillis, p.type, runnable);
            }
        }
    }
```

- [ ] **Step 2: Rewrite the legacy `send(String[] dates, runnable)` to enumerate**

Still in `LoganControlCenter.java`, replace the body of the existing `send(String dates[], SendLogRunnable runnable)` (lines 114-136):

```java
    void send(String dates[], SendLogRunnable runnable) {
        if (TextUtils.isEmpty(mPath) || dates == null || dates.length == 0) {
            return;
        }
        for (String date : dates) {
            if (TextUtils.isEmpty(date)) {
                continue;
            }
            long time = getDateTime(date);
            if (time > 0) {
                LoganModel model = new LoganModel();
                SendAction action = new SendAction();
                model.action = LoganModel.Action.SEND;
                action.date = String.valueOf(time);
                action.sendLogRunnable = runnable;
                model.sendAction = action;
                mCacheLogQueue.add(model);
                if (mLoganThread != null) {
                    mLoganThread.notifyRun();
                }
            }
        }
    }
```

with:

```java
    void send(String dates[], SendLogRunnable runnable) {
        if (TextUtils.isEmpty(mPath) || dates == null || dates.length == 0) {
            return;
        }
        for (String date : dates) {
            if (TextUtils.isEmpty(date)) {
                continue;
            }
            long time = getDateTime(date);
            if (time > 0) {
                enqueueAllForDate(time, runnable);
            }
        }
    }
```

- [ ] **Step 3: Add the new typed-send overload**

Still in `LoganControlCenter.java`, directly below the `send(String dates[], ...)` method, insert:

```java
    void send(String date, Integer[] types, SendLogRunnable runnable) {
        if (TextUtils.isEmpty(mPath) || TextUtils.isEmpty(date) || runnable == null) {
            return;
        }
        long time = getDateTime(date);
        if (time <= 0) {
            return;
        }
        if (types == null || types.length == 0) {
            enqueueAllForDate(time, runnable);
            return;
        }
        for (Integer type : types) {
            enqueueSend(time, type, runnable);
        }
    }
```

- [ ] **Step 4: Add the `File` import at the top of `LoganControlCenter.java`**

Verify the file already has `import java.io.File;` (it does — line 28). No edit needed; if that import is missing, add it.

- [ ] **Step 5: Verify compile**

```bash
cd Example/Logan-Android && ./gradlew :logan:compileDebugJavaWithJavac
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add Example/Logan-Android/logan/src/main/java/com/dianping/logan/LoganControlCenter.java
git commit -m "$(cat <<'EOF'
feat(android): typed send + enumerate-all dispatch in LoganControlCenter

Adds send(date, types, runnable) which enqueues one SEND per type
(or enumerates every matching file when types is null/empty).
Reroutes the legacy send(dates, runnable) through the same
enumerator so a date with N typed files yields N sendLog calls.
Spec §4.5/§4.6.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Android — `Logan.s(date, types, runnable)` public API + `getAllFilesInfo` aggregation

**Files:**
- Modify: `Example/Logan-Android/logan/src/main/java/com/dianping/logan/Logan.java`

Surface the new `LoganControlCenter.send(date, types, runnable)` as a public method. Update `getAllFilesInfo()` so the new `{date}_{type}` filenames don't fall through the existing `Long.parseLong(file.getName())` and produce a wrong/missing key — sizes for the same date should be aggregated across types.

- [ ] **Step 1: Add the new public overload**

In `Logan.java`, find the existing `s(String url, String date, Map<String, String> headers, SendLogCallback sendLogCallback)` method (lines 105-114). Insert this new method directly below it (still inside `class Logan`):

```java
    /**
     * @param date     "yyyy-MM-dd" 格式日期。
     * @param types    要上报的日志类型数组。元素为 null 表示旧版未拆分的 {date}
     *                 文件；非空整数表示对应的 {date}_{type} 文件。
     *                 传入 null 或空数组等价于"上传该日期下的全部文件"，
     *                 与 {@link #s(String[], SendLogRunnable)} 行为一致。
     * @param runnable 用户实现的上报逻辑。每匹配一个文件会回调一次 sendLog(File)，
     *                 调用方必须在每次回调结束后调用 {@link SendLogRunnable#finish()}。
     */
    public static void s(String date, Integer[] types, SendLogRunnable runnable) {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        sLoganControlCenter.send(date, types, runnable);
    }
```

- [ ] **Step 2: Rewrite `getAllFilesInfo` to aggregate per-date**

Still in `Logan.java`, locate the existing `getAllFilesInfo()` (lines 119-140):

```java
    public static Map<String, Long> getAllFilesInfo() {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        File dir = sLoganControlCenter.getDir();
        if (!dir.exists()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        Map<String, Long> allFilesInfo = new HashMap<>();
        for (File file : files) {
            try {
                allFilesInfo.put(Util.getDateStr(Long.parseLong(file.getName())), file.length());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return allFilesInfo;
    }
```

Replace with:

```java
    public static Map<String, Long> getAllFilesInfo() {
        if (sLoganControlCenter == null) {
            throw new RuntimeException("Please initialize Logan first");
        }
        File dir = sLoganControlCenter.getDir();
        if (!dir.exists()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        Map<String, Long> allFilesInfo = new HashMap<>();
        for (File file : files) {
            FileNames.Parsed p = FileNames.parse(file.getName());
            if (p == null || p.isCopy) {
                continue;
            }
            String dateStr = Util.getDateStr(p.dateMillis);
            Long previous = allFilesInfo.get(dateStr);
            allFilesInfo.put(dateStr, (previous == null ? 0L : previous) + file.length());
        }
        return allFilesInfo;
    }
```

- [ ] **Step 3: Verify compile and tests**

```bash
cd Example/Logan-Android && ./gradlew :logan:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add Example/Logan-Android/logan/src/main/java/com/dianping/logan/Logan.java
git commit -m "$(cat <<'EOF'
feat(android): public Logan.s(date, types, runnable); aggregate getAllFilesInfo

New typed-upload overload routes through LoganControlCenter.send.
getAllFilesInfo now sums file sizes per date across types so the
public {date -> totalBytes} contract still holds with the new
{date}_{type} naming. Spec §4.1, §4.8.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: iOS — write rotation on `(date, type)` change

**Files:**
- Modify: `Logan/iOS/Logan.m`

Add `lastLogType` to the `Logan` singleton and extend the rotation gate. The synthetic clogan-header record per file is acceptable per spec §3. `clogan_open` flushes the previous file internally; the existing `clogan_flush()` line is preserved for symmetry with the day-rollover branch.

- [ ] **Step 1: Add the property**

In `Logan/iOS/Logan.m`, find the `@interface Logan : NSObject { ... }` block (around lines 41-50). Below the existing `@property (nonatomic, copy) NSString *lastLogDate;` (line 44), add:

```objc
@property (nonatomic, strong) NSNumber *lastLogType;
```

- [ ] **Step 2: Rewrite the rotation condition in `writeLog:logType:`**

Still in `Logan.m`, locate the existing dispatch block in `writeLog:logType:` (lines 174-183):

```objc
    dispatch_async(self.loganQueue, ^{
        NSString *today = [Logan currentDate];
        if (self.lastLogDate && ![self.lastLogDate isEqualToString:today]) {
                // 日期变化，立即写入日志文件
            clogan_flush();
            clogan_open((char *)today.UTF8String);
        }
        self.lastLogDate = today;
        clogan_write((int)type, (char *)log.UTF8String, (long long)localTime, threadNameC, (long long)threadNum, (int)threadIsMain);
    });
```

Replace with:

```objc
    dispatch_async(self.loganQueue, ^{
        NSString *today = [Logan currentDate];
        NSNumber *currentType = @(type);
        BOOL dateChanged = self.lastLogDate && ![self.lastLogDate isEqualToString:today];
        BOOL typeChanged = !self.lastLogType || ![self.lastLogType isEqual:currentType];
        if (dateChanged || typeChanged) {
            clogan_flush();
            NSString *filename = [NSString stringWithFormat:@"%@_%lu", today, (unsigned long)type];
            clogan_open((char *)filename.UTF8String);
        }
        self.lastLogDate = today;
        self.lastLogType = currentType;
        clogan_write((int)type, (char *)log.UTF8String, (long long)localTime, threadNameC, (long long)threadNum, (int)threadIsMain);
    });
```

- [ ] **Step 3: Update bootstrap `clogan_open` in `initAndOpenCLib`**

Still in `Logan.m`, locate `initAndOpenCLib` (lines 139-151). The bootstrap currently opens a bare `{today}` file at init time, which would be the only legacy bare file the new SDK ever produces. Remove that pre-open so the first real `writeLog:logType:` chooses the typed pathname.

Replace lines 147-148:

```objc
    NSString *today = [Logan currentDate];
    clogan_open((char *)today.UTF8String);
```

with:

```objc
    // The first writeLog:logType: call performs clogan_open with the
    // typed filename {yyyy-MM-dd}_{type}. We deliberately don't open a
    // bare {yyyy-MM-dd} file here so the new SDK never produces legacy
    // bare files on disk.
```

(Spec §1 invariant 3: legacy bare files remain readable but are never produced by the new SDK.)

- [ ] **Step 4: Build the iOS example**

```bash
cd Example/Logan-iOS && pod install
xcodebuild -workspace Logan-iOS.xcworkspace -scheme Logan-iOS -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

Expected: `** BUILD SUCCEEDED **`. If `xcodebuild` is unavailable in your environment, open `Logan-iOS.xcworkspace` in Xcode and run a build there; report whichever method you used.

- [ ] **Step 5: Commit**

```bash
git add Logan/iOS/Logan.m
git commit -m "$(cat <<'EOF'
feat(ios): rotate Logan file on (date, type) change

Adds lastLogType to the Logan singleton; writeLog:logType: now
opens {yyyy-MM-dd}_{type} files and rotates whenever either part
changes. Removes the bootstrap clogan_open of a bare {date} file so
the new SDK never produces legacy filenames on disk.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: iOS — `+deleteOutdatedFiles` and `+allFilesInfo` parsers

**Files:**
- Modify: `Logan/iOS/Logan.m`

`+deleteOutdatedFiles` currently nukes anything whose name length isn't 10 (`yyyy-MM-dd`), which would delete every new `{yyyy-MM-dd}_{type}` file (length 12+) on first launch. `+allFilesInfo` slices the first 10 chars; with the new naming, `2025-04-24_1` → key `2025-04-24` already, but the body skips entries whose `pathExtension` is set. Both need to accept the underscore form and aggregate sizes per date.

- [ ] **Step 1: Rewrite `+deleteOutdatedFiles`**

In `Logan/iOS/Logan.m`, locate `+deleteOutdatedFiles` (lines 439-466):

```objc
+ (void)deleteOutdatedFiles {
    NSArray *allFiles = [Logan localFilesArray];
    __block NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    NSString *dateFormatString = @"yyyy-MM-dd";
    [formatter setDateFormat:dateFormatString];
    [allFiles enumerateObjectsUsingBlock:^(NSString *_Nonnull dateStr, NSUInteger idx, BOOL *_Nonnull stop) {
            // 检查后缀名
        if ([dateStr pathExtension].length > 0) {
            [self deleteLoganFile:dateStr];
            return;
        }
        
            // 检查文件名长度
        if (dateStr.length != (dateFormatString.length)) {
            [self deleteLoganFile:dateStr];
            return;
        }
            // 文件名转化为日期
        dateStr = [dateStr substringToIndex:dateFormatString.length];
        NSDate *date = [formatter dateFromString:dateStr];
        NSString *todayStr = [Logan currentDate];
        NSDate *todayDate = [formatter dateFromString:todayStr];
        if (!date || [self getDaysFrom:date To:todayDate] >= __max_reversed_date) {
                // 删除过期文件
            [self deleteLoganFile:dateStr];
        }
    }];
}
```

Replace with:

```objc
+ (void)deleteOutdatedFiles {
    NSArray *allFiles = [Logan localFilesArray];
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    NSString *dateFormatString = @"yyyy-MM-dd";
    [formatter setDateFormat:dateFormatString];
    [formatter setLocale:[NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"]];
    NSString *todayStr = [Logan currentDate];
    NSDate *todayDate = [formatter dateFromString:todayStr];

    [allFiles enumerateObjectsUsingBlock:^(NSString *_Nonnull name, NSUInteger idx, BOOL *_Nonnull stop) {
        // Stray .temp / .copy snapshots (orphans from a previous run).
        if ([name pathExtension].length > 0) {
            [self deleteLoganFile:name];
            return;
        }
        // Slice off the optional "_{type}" tail to recover the date prefix.
        NSRange us = [name rangeOfString:@"_"];
        NSString *base = (us.location == NSNotFound) ? name : [name substringToIndex:us.location];
        if (base.length != dateFormatString.length) {
            [self deleteLoganFile:name];
            return;
        }
        NSDate *date = [formatter dateFromString:base];
        if (!date || [self getDaysFrom:date To:todayDate] >= __max_reversed_date) {
            [self deleteLoganFile:name];
        }
    }];
}
```

- [ ] **Step 2: Rewrite `+allFilesInfo` to aggregate per-date**

Still in `Logan.m`, locate `+allFilesInfo` (lines 376-390):

```objc
+ (NSDictionary *)allFilesInfo {
    NSArray *allFiles = [Logan localFilesArray];
    NSString *dateFormatString = @"yyyy-MM-dd";
    NSMutableDictionary *infoDic = [NSMutableDictionary new];
    for (NSString *file in allFiles) {
        if ([file pathExtension].length > 0) {
            continue;
        }
        NSString *dateString = [file substringToIndex:dateFormatString.length];
        unsigned long long gzFileSize = [Logan fileSizeAtPath:[self logFilePath:dateString]];
        NSString *size = [NSString stringWithFormat:@"%llu", gzFileSize];
        [infoDic setObject:size forKey:dateString];
    }
    return infoDic;
}
```

Replace with:

```objc
+ (NSDictionary *)allFilesInfo {
    NSArray *allFiles = [Logan localFilesArray];
    NSString *dateFormatString = @"yyyy-MM-dd";
    NSMutableDictionary<NSString *, NSNumber *> *totals = [NSMutableDictionary new];
    for (NSString *file in allFiles) {
        if ([file pathExtension].length > 0) {
            continue;
        }
        NSRange us = [file rangeOfString:@"_"];
        NSString *base = (us.location == NSNotFound) ? file : [file substringToIndex:us.location];
        if (base.length != dateFormatString.length) {
            continue;
        }
        unsigned long long thisSize = [Logan fileSizeAtPath:[self logFilePath:file]];
        NSNumber *prev = totals[base];
        unsigned long long sum = (prev ? [prev unsignedLongLongValue] : 0ULL) + thisSize;
        totals[base] = @(sum);
    }
    NSMutableDictionary<NSString *, NSString *> *infoDic = [NSMutableDictionary new];
    [totals enumerateKeysAndObjectsUsingBlock:^(NSString *date, NSNumber *sum, BOOL *stop) {
        infoDic[date] = [NSString stringWithFormat:@"%llu", [sum unsignedLongLongValue]];
    }];
    return infoDic;
}
```

- [ ] **Step 3: Build to verify**

```bash
cd Example/Logan-iOS && xcodebuild -workspace Logan-iOS.xcworkspace -scheme Logan-iOS -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 4: Commit**

```bash
git add Logan/iOS/Logan.m
git commit -m "$(cat <<'EOF'
fix(ios): expire/aggregate {yyyy-MM-dd}_{type} log files

+deleteOutdatedFiles previously deleted any name whose length
wasn't 10, which would have wiped every new typed file on first
launch. Both expiry and +allFilesInfo now slice up to the first '_'
to recover the date prefix and aggregate size per date. Spec §4.8.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: iOS — `loganUploadFilePathWithTypes` + `filePathForDate:` enumeration

**Files:**
- Modify: `Logan/iOS/Logan.h`
- Modify: `Logan/iOS/Logan.m`

Refactor today/past-day file resolution into a per-file helper, then invoke it once per `(date, type)` match. The legacy `loganUploadFilePath` continues to exist; its block is now invoked once per matching file, and (preserving the existing zero-match contract) once with `nil` if no file exists for the date.

- [ ] **Step 1: Declare the new C-callable extern in `Logan.h`**

In `Logan/iOS/Logan.h`, locate the existing declaration of `loganUploadFilePath`:

```objc
extern void loganUploadFilePath(NSString *_Nonnull date, LoganFilePathBlock _Nonnull filePathBlock);
```

Insert directly below it:

```objc
/**
 根据日期+类型获取上传日志的文件路径，异步方式。每匹配一个磁盘上存在的
 文件，filePathBlock 会被回调一次（在主线程）。

 @param date          日志日期 格式："2018-11-21"。
 @param types         NSArray<NSNumber *>。元素为 NSNull 表示旧版未拆分的
                      {date} 文件；元素为非空 NSNumber 表示 {date}_{type}
                      文件。传 nil 或空数组表示"枚举该日期下所有存在的
                      文件"。
 @param filePathBlock 每个存在的匹配文件回调一次。**不存在的类型不回调** —
                      与 loganUploadFilePath 的"无文件即回调一次 nil"
                      语义不同，避免对未生成的类型产生噪声回调。
 */
extern void loganUploadFilePathWithTypes(NSString *_Nonnull date,
                                          NSArray *_Nullable types,
                                          LoganFilePathBlock _Nonnull filePathBlock);
```

- [ ] **Step 2: Extend the `Logan` class extension with new method declarations**

In `Logan/iOS/Logan.m`, find the `@interface Logan : NSObject { ... }` class-extension block near the top of the file (the one that already declares `writeLog:logType:`, `clearLogs`, `+allFilesInfo`, `filePathForDate:block:`, etc.). Append three new method declarations directly above the closing `@end` of that interface block:

```objc
+ (NSArray<NSString *> *)logFileNamesForDate:(NSString *)date;
- (void)filePathForName:(NSString *)name isToday:(BOOL)isToday block:(LoganFilePathBlock)filePathBlock;
- (void)filePathForDate:(NSString *)date types:(NSArray *)types block:(LoganFilePathBlock)filePathBlock;
```

- [ ] **Step 3: Add `+logFileNamesForDate:` and the per-file helper in `@implementation Logan`**

Still in `Logan/iOS/Logan.m`, find the existing `+ (NSDictionary *)allFilesInfo` implementation. Insert the following two methods directly below it (still inside `@implementation Logan`):

```objc
+ (NSArray<NSString *> *)logFileNamesForDate:(NSString *)date {
    if (!date.length) {
        return @[];
    }
    NSString *exactPrefix = [date stringByAppendingString:@"_"];
    NSMutableArray<NSString *> *out = [NSMutableArray array];
    for (NSString *name in [Logan localFilesArray]) {
        if ([name pathExtension].length > 0) {
            continue;
        }
        if ([name isEqualToString:date] || [name hasPrefix:exactPrefix]) {
            [out addObject:name];
        }
    }
    return out;
}

- (void)filePathForName:(NSString *)name isToday:(BOOL)isToday block:(LoganFilePathBlock)filePathBlock {
    NSString *filePath = [Logan logFilePath:name];
    if (![[NSFileManager defaultManager] fileExistsAtPath:filePath]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            filePathBlock(nil);
        });
        return;
    }
    if (isToday) {
        dispatch_async(self.loganQueue, ^{
            [self flushInQueue];
            NSString *uploadFilePath = [Logan uploadFilePath:name];
            NSError *error;
            [[NSFileManager defaultManager] removeItemAtPath:uploadFilePath error:&error];
            if (![[NSFileManager defaultManager] copyItemAtPath:filePath toPath:uploadFilePath error:&error]) {
                uploadFilePath = nil;
            }
            dispatch_async(dispatch_get_main_queue(), ^{
                filePathBlock(uploadFilePath);
            });
        });
        return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        filePathBlock(filePath);
    });
}
```

- [ ] **Step 4: Replace `filePathForDate:block:` and remove `todayFilePatch:`**

Still in `Logan.m`, locate the existing `filePathForDate:block:` (originally at lines 320-344) and `todayFilePatch:` (originally at lines 346-359). Replace **both** methods with this single new implementation:

```objc
- (void)filePathForDate:(NSString *)date block:(LoganFilePathBlock)filePathBlock {
    if (!date.length) {
        dispatch_async(dispatch_get_main_queue(), ^{
            filePathBlock(nil);
        });
        return;
    }
    NSArray<NSString *> *matches = [Logan logFileNamesForDate:date];
    if (matches.count == 0) {
        dispatch_async(dispatch_get_main_queue(), ^{
            filePathBlock(nil);
        });
        return;
    }
    BOOL isToday = [date isEqualToString:[Logan currentDate]];
    for (NSString *name in matches) {
        [self filePathForName:name isToday:isToday block:filePathBlock];
    }
}
```

(`todayFilePatch:` is now folded into `filePathForName:isToday:block:` from step 3 — there is no replacement for it.) Also remove its declaration from the `@interface Logan` class extension if one is present (the existing file does not declare it there, but if a future maintainer added one, drop it).

- [ ] **Step 5: Add the typed `filePathForDate:types:block:` implementation**

Still in `@implementation Logan`, directly below the new `filePathForDate:block:` from step 4, add:

```objc
- (void)filePathForDate:(NSString *)date types:(NSArray *)types block:(LoganFilePathBlock)filePathBlock {
    if (!date.length) {
        return;
    }
    if (types == nil || types.count == 0) {
        // Enumerate every file present for the date — matches the legacy
        // filePathForDate:block: behavior.
        [self filePathForDate:date block:filePathBlock];
        return;
    }
    BOOL isToday = [date isEqualToString:[Logan currentDate]];
    for (id rawType in types) {
        NSString *name;
        if ([rawType isKindOfClass:[NSNull class]]) {
            name = date;
        } else if ([rawType isKindOfClass:[NSNumber class]]) {
            name = [NSString stringWithFormat:@"%@_%lu", date, (unsigned long)[(NSNumber *)rawType unsignedIntegerValue]];
        } else {
            continue;
        }
        NSString *filePath = [Logan logFilePath:name];
        if (![[NSFileManager defaultManager] fileExistsAtPath:filePath]) {
            // Skip-if-missing — see header doc for divergence from the
            // single-shot loganUploadFilePath contract.
            continue;
        }
        [self filePathForName:name isToday:isToday block:filePathBlock];
    }
}
```

- [ ] **Step 6: Add the new C wrapper next to `loganUploadFilePath`**

Near the top of `Logan.m`, find the existing C function `loganUploadFilePath`:

```objc
void loganUploadFilePath(NSString *_Nonnull date, LoganFilePathBlock _Nonnull filePathBlock) {
    [[Logan logan] filePathForDate:date block:filePathBlock];
}
```

Insert directly below it:

```objc
void loganUploadFilePathWithTypes(NSString *_Nonnull date,
                                   NSArray *_Nullable types,
                                   LoganFilePathBlock _Nonnull filePathBlock) {
    [[Logan logan] filePathForDate:date types:types block:filePathBlock];
}
```

- [ ] **Step 7: Build the iOS example**

```bash
cd Example/Logan-iOS && xcodebuild -workspace Logan-iOS.xcworkspace -scheme Logan-iOS -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 8: Commit**

```bash
git add Logan/iOS/Logan.h Logan/iOS/Logan.m
git commit -m "$(cat <<'EOF'
feat(ios): loganUploadFilePathWithTypes + per-file enumeration

filePathForDate:block: now resolves every {date} and {date}_{type}
file present and invokes the block once per match (zero-match still
yields a single nil call to preserve the existing contract).
loganUploadFilePathWithTypes lets callers target specific types and
silently skips missing ones. Uses a shared per-file helper so the
today-snapshot/.temp logic isn't duplicated. Spec §4.2/§4.4.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Demo-app validation and release notes

**Files:**
- Read-only manual verification using `Example/Logan-Android` and `Example/Logan-iOS` demo apps. No additional source edits.

The C core, server, and wire format are unchanged, so the validation surface is the on-disk filenames, expiry, and upload callback counts. This task is the gate for shipping.

- [ ] **Step 1: Android demo — emit three types and inspect on-disk filenames**

Open `Example/Logan-Android` in Android Studio. In `Example/Logan-Android/app/src/main/java/com/dianping/logan/MainActivity.java` find the existing demo write button and replace its call with three writes (or add a new button) that runs:

```java
Logan.w("hello type 1", 1);
Logan.w("hello type 2", 2);
Logan.w("hello type 3", 3);
Logan.f();
```

Run the demo app on an emulator, tap the button, then:

```bash
adb shell run-as com.meituan.logan ls -la /data/data/com.meituan.logan/files/logan_v1
```

Expected: three files named `{day_millis}_1`, `{day_millis}_2`, `{day_millis}_3` (no bare `{day_millis}` file).

- [ ] **Step 2: Android demo — typed upload callback count**

In the demo, call:

```java
Logan.s(Logan.todaysDate(/* or your existing helper */), new Integer[] {1}, new SendLogRunnable() {
    @Override public void sendLog(File file) {
        android.util.Log.i("LoganDemo", "uploaded " + file.getName());
        finish();
    }
});
```

Expected: exactly one logcat line, with filename ending `..._1.copy`.

Then call the same with `new Integer[] {1, 2, null}` after manually creating an empty `{day_millis}` file via `adb shell touch`.

Expected: three logcat lines, in any order — `..._1.copy`, `..._2.copy`, and `{day_millis}.copy`.

- [ ] **Step 3: Android demo — expiry**

Manually plant an old-date file:

```bash
adb shell run-as com.meituan.logan sh -c 'cd /data/data/com.meituan.logan/files/logan_v1 && touch -t 202001010000 1577836800000_1 1577836800000'
```

Restart the demo and write any log. Expected: both planted files are gone after the next write (expiry runs in `doWriteLog2File` on day-boundary check).

- [ ] **Step 4: iOS demo — emit three types and inspect filenames**

In `Example/Logan-iOS/Logan-iOS/ViewController.m` (or whichever file the demo uses), call:

```objc
logan(1, @"hello type 1");
logan(2, @"hello type 2");
logan(3, @"hello type 3");
loganFlush();
```

Run on a simulator. Then in Terminal:

```bash
xcrun simctl get_app_container booted com.meituan.LoganDemo data
# Then list the app's Documents/LoganLoggerv3
```

Expected: three files `{yyyy-MM-dd}_1`, `{yyyy-MM-dd}_2`, `{yyyy-MM-dd}_3`. No bare `{yyyy-MM-dd}` file.

- [ ] **Step 5: iOS demo — typed upload callback count**

In the demo, call:

```objc
loganUploadFilePathWithTypes(loganTodaysDate(), @[@(1)], ^(NSString *path) {
    NSLog(@"uploaded %@", path);
});
```

Expected: one log line with `..._1.temp`.

Then call:

```objc
loganUploadFilePathWithTypes(loganTodaysDate(), @[@(1), @(2), [NSNull null]], ^(NSString *path) {
    NSLog(@"uploaded %@", path);
});
```

after manually creating a bare `{yyyy-MM-dd}` file. Expected: three log lines.

Without the bare file, expected: exactly two log lines (the `NSNull` element is silently skipped — spec §4.2).

- [ ] **Step 6: iOS demo — legacy `loganUploadFilePath` zero-match path**

Call `loganUploadFilePath(@"1900-01-01", ^(NSString *p) { NSLog(@"got %@", p); })`. Expected: exactly one log line with `got (null)` — the zero-match nil contract is preserved.

- [ ] **Step 7: Write release notes**

Create `docs/superpowers/specs/2026-04-24-logan-split-by-type-release-notes.md` with this content:

```markdown
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
```

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/specs/2026-04-24-logan-split-by-type-release-notes.md
git commit -m "$(cat <<'EOF'
docs: release notes for per-type log file split

Calls out the two compatibility-affecting behavior changes for
existing single-date upload APIs (now N callbacks per date) and
documents the new typed-upload entry points.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

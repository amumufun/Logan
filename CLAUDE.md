# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository overview

Logan is Meituan-Dianping's multi-platform case logging system. This is a mono-repo containing five SDKs plus a server/frontend stack that all speak the same on-disk log protocol:

- **`Logan/Clogan/`** — C core: AES-encrypted, gzip-compressed, mmap-backed log writer (`clogan_core.c`). Depends on bundled **`Logan/mbedtls/`** (vendored) and `cJSON.c`. Shared by iOS and Android.
- **`Logan/iOS/`** — Thin Objective-C wrapper (`Logan.h/m`) over Clogan. Packaged as the `Logan` CocoaPod (`Logan.podspec`) and as a SwiftPM target (`Package.swift`, with `mbedtls` → `CLogan` → `Logan` target graph).
- **`Example/Logan-Android/`** — Gradle project with two modules: `app` (demo) and `logan` (the library). The `logan` module's `CMakeLists.txt` reaches *upward* into `../../../Logan/Clogan` to compile the shared C core alongside its own `src/main/jni/clogan_protocol.c`, then exposes it to Java via `CLoganProtocol.java`. The Java API is `com.dianping.logan.Logan`.
- **`Logan/WebSDK/`** (published as `logan-web` on npm) — TypeScript, IndexedDB-backed log storage via `idb-managed`, CryptoJS for encryption. Uses dynamic `import()` for `save-log.ts` / `report-log.ts` so they can be code-split.
- **`Flutter/`** — `flutter_logan` plugin. Dart entry is `Flutter/lib/flutter_logan.dart`; platform channels into `Flutter/android` (Java) and `Flutter/ios` (ObjC) which re-use the native SDKs.
- **`Logan/Server/`** — Java 8 / Spring 5 / MyBatis / MySQL webapp (`war` packaged). Packages live under `com.meituan.logan.web.{controller,service,mapper,parser,handler,...}`. DB config: `src/main/resources/db.properties`. Table DDL is documented in `Logan/Server/README` and applied from `Logan/scripts/migration/mysql/`.
- **`Logan/LoganSite/`** — React 16 + Redux + antd frontend (ejected CRA; scripts live in `Logan/LoganSite/scripts/`). Reads `API_BASE_URL` from `.env.development`.
- **`Example/Logan-NodeServer/`** — Reference TypeScript upload receiver for `logan-web` (Express + ts-node).

Log format is shared across platforms — see https://github.com/Meituan-Dianping/Logan/wiki/Log-protocol. Any change to encryption/framing in `Logan/Clogan/` must be mirrored in `Logan/WebSDK/src/` and the server-side parser `Logan/Server/src/main/java/com/meituan/logan/web/parser/`.

## Common commands

### iOS (CocoaPod + SwiftPM)
```bash
# Run the example app
cd Example/Logan-iOS && pod install
open Logan-iOS.xcworkspace
# SwiftPM resolves via Package.swift at the repo root
```

### Android (Gradle + NDK)
The SDK build expects **NDK 23.2.8568313** (`Example/Logan-Android/logan/build.gradle`). The older README still mentions r16b, but current code targets `compileSdk 34` / `minSdk 21` / `ndkVersion 23.2.8568313`.
```bash
cd Example/Logan-Android
./gradlew :logan:assembleRelease          # build AAR
./gradlew :app:installDebug               # install demo app
./gradlew :logan:test                     # JVM unit tests
./gradlew :logan:connectedAndroidTest     # instrumentation tests
```
Native code is built via CMake; `logan/CMakeLists.txt` sets `-Wl,-z,max-page-size=16384` for 16 KB page-size compatibility (see `Logan/Clogan/` commit history).

### Web SDK (TypeScript)
```bash
cd Logan/WebSDK
npm test                     # jest --coverage
npm run build                # tsc to build/ + copies lib/*.js
npm run start:dev            # build + webpack-dev-server demo
npm run publish:prod         # runs tests, tags, publishes
```

### LoganSite (React frontend)
```bash
cd Logan/LoganSite
npm install
npm start                    # dev server (wraps scripts/start.js)
npm run build                # production build (scripts/build.js)
npm test                     # jest runner (scripts/test.js); single file: npm test -- src/path/to/file.test.js
```
`src/common/api.js` line 4 hard-codes `BASE_URL` for production builds; dev uses `.env.development`'s `API_BASE_URL`.

### Server (Maven / Spring)
```bash
cd Logan/Server
mvn package -Denv=beta       # -> target/logan-web.war
```
Update `src/main/resources/db.properties` before running. To format log contents for new `log_type` values, implement `com.meituan.logan.web.handler.ContentHandler` (see `Logan/Server/README`).

### Full stack via Docker
```bash
cd Logan
./deploy.sh                  # patches db.properties + LoganSite env, then docker-compose up
# Frontend: http://localhost:3000
# Backend:  http://localhost:8888
# MySQL:    localhost:23306 (logan/logan)
# phpMyAdmin: http://localhost:10050
```
`docker-compose.yaml` runs `migrate/migrate` against `Logan/scripts/migration/mysql/` on startup.

### Node example server
```bash
cd Example/Logan-NodeServer && npm start    # ts-node ./app.ts
```

## Notes for contributors

- Target branch for PRs is **`master`** (`CONTRIBUTING.md`). Squash commits before submitting.
- Every new C / ObjC / Java / JS source file must start with the MIT copyright notice block from `CONTRIBUTING.md` (the "美团点评" header).
- Encryption keys in examples (`0123456789012345`) are **placeholders** — Logan expects exactly 16-byte AES key + 16-byte IV; WebSDK additionally supports RSA-wrapped keys via `publicKey` config.

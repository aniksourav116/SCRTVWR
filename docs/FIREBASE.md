# Firebase

The app uses Crashlytics, Cloud Messaging (FCM) and Analytics, and ships test builds
through App Distribution.

Project: `srvgallery-41cc2` · Android app id: `1:715397747565:android:860aca1cb7adf184014064`

## What is wired up

| Piece | Where |
| --- | --- |
| Config | [`app/google-services.json`](../app/google-services.json) — must live in the module dir, not the repo root |
| Versions | [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) (`firebaseBom` pins the SDK versions) |
| Plugins | `google-services`, `firebase-crashlytics`, `firebase-appdistribution` in [`app/build.gradle.kts`](../app/build.gradle.kts) |
| Runtime policy | [`ScrtvwrFirebase`](../app/src/main/java/srv/scrtvwr/firebase/ScrtvwrFirebase.kt) |
| Push handling | [`ScrtvwrMessagingService`](../app/src/main/java/srv/scrtvwr/firebase/ScrtvwrMessagingService.kt) |
| Crash breadcrumbs | [`CrashlyticsTree`](../app/src/main/java/srv/scrtvwr/util/logs/CrashlyticsTree.kt) |

Crashlytics and Analytics collection are **off in debug builds** and on in release, so local
runs never show up in the console. Timber routes to `CustomLogger` in debug and to
`CrashlyticsTree` in release, where warnings become breadcrumbs and errors carrying a
throwable are recorded as non-fatals.

## Cloud Messaging

`POST_NOTIFICATIONS` is requested once on first launch (Android 13+). Firebase draws
notification-only messages itself when the app is backgrounded; `ScrtvwrMessagingService`
covers foreground delivery and data-only messages, reading `title` and `body` data keys.

To target a debug device from the console, run a debug build and copy the token from
logcat (`FCM registration token: …`).

## App Distribution

Uploads need credentials, resolved in this order:

1. `FIREBASE_SERVICE_ACCOUNT` — absolute path to a service account JSON with the
   *Firebase App Distribution Admin* role. Preferred for CI.
2. Otherwise the local `firebase login` / `gcloud auth application-default login` session.

Distribute a build:

```bash
./gradlew assembleRelease appDistributionUploadRelease
```

Add testers with `-PfirebaseTesterGroups=<group aliases>`; without it the release uploads
without being assigned to anyone. Release notes come from
[`distribution/release-notes.txt`](../distribution/release-notes.txt).

Debug builds distribute the same way with `assembleDebug appDistributionUploadDebug`.

This project has `org.gradle.configuration-cache=true`. If an upload task reports a
configuration-cache problem, run that invocation with `--no-configuration-cache`; it only
affects the upload, not the build.

### Signing

App Distribution needs a signed artifact. `release` picks up a signing config only when a
keystore is configured; otherwise the release APK is unsigned and the upload will fail —
distribute the debug variant instead, or supply a keystore.

Create `keystore.properties` in the repo root (git-ignored):

```properties
storeFile=../release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

On CI use the equivalent environment variables instead: `SCRTVWR_STORE_FILE`,
`SCRTVWR_STORE_PASSWORD`, `SCRTVWR_KEY_ALIAS`, `SCRTVWR_KEY_PASSWORD`.

Mapping-file upload is enabled for `release` but only produces anything useful once
`optimization.enable` is turned on in `app/build.gradle.kts` — flip both together.

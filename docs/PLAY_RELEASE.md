# Google Play release

Work branch: `play_deploy`. These changes have not been compiled or device-tested yet.

## Editions

| Behavior | Personal | Play |
| --- | --- | --- |
| Package | `srv.scrtvwr` (existing installation) | `srv.scrtvwr.play` (new public installation) |
| Local storage | Existing all-files permission and file operations | Read-only folders chosen with Android's folder picker |
| Images, videos, ZIP/RAR/CBZ/CBR | Device paths and servers | Selected document trees and HTTPS servers |
| HTTP | Private IPv4/loopback literals only, without credentials | Disabled |
| Credentials | HTTPS only; Android Keystore at rest | HTTPS only; Android Keystore at rest |
| Firebase | Existing Analytics/Crashlytics release collection and FCM | SDKs excluded; no telemetry or push permission |
| Disk downloads | No fixed size cap; storage reserve enforced | 1 GiB per image/archive; storage reserve enforced |
| Backup/transfer | Private app data excluded | Private app data excluded |
| File sharing | Selected file copied into a narrow cache directory | Selected document URI granted read access |

Both editions leave at least 128 MiB of free storage when writing downloads/copies.
Videos stream without a persistent full-video cache or a file-size cap.
Public folders are intentionally read only: the Play edition does not expose copy/move/delete.
History, bookmarks, sorting, folder search, archive viewing and adjacent-folder navigation use
document URIs in the Play edition. Android limits which storage roots/protected folders can be selected.
Users can remove saved folder access from Storage and clear history/cache from the existing controls.
Removing access does not itself erase already-cached thumbnails/archive copies; use Clear cache too.

The separate package allows both editions to be installed together. It does not migrate the
personal app's history, credentials or grants into the public app. Confirm the public package
name before the first Play upload; a published application's package name cannot be changed.

## Changes for personal use

The checked-in debug keystore still belongs only to debug builds. The personal package and
Firebase registration are preserved. Google services and Firebase SDKs are not used by Play.
Existing password-protected HTTP servers must move behind HTTPS with a valid certificate.
HTTP hostnames such as `nas.local` must use HTTPS, or use a literal private IPv4 address without sign-in.
Automatic redirects must stay on the same origin. Add the destination URL directly for a server
that redirects to a different hostname, port or protocol. Credentials are looked up again per hop.

## Privacy policy and store setup

1. Review `docs/privacy-policy.html`, add a direct support email if desired, and host it at a
   stable, public HTTPS URL. No policy has been hosted or submitted from this workspace.
2. Enter that URL in Play Console and provide a working App support email. The in-app policy
   is available from Settings and identifies the public edition. Keep both copies synchronized.
3. Complete Data safety based on the final Play bundle and your actual services. It contains no
   Firebase/ads SDKs. Local-only records do not automatically constitute developer collection;
   account for user-chosen servers/document providers and applicable user-initiated-transfer
   exceptions. Do not reuse the personal edition's Firebase declarations.
4. Complete the content rating, target audience, ads declaration, app access instructions and
   other applicable Console forms. Explain how reviewers can choose a local folder and test
   HTTPS browsing. Provide a working review server if needed; do not depend on your private LAN.
5. Use Play App Signing and a private production upload key. Configure `keystore.properties`
   or the `SCRTVWR_STORE_*` / `SCRTVWR_KEY_*` environment variables described in `FIREBASE.md`.
   Play release packaging fails if no upload key is configured or the checked-in debug key is selected.
6. Inspect the final manifest/dependencies for unwanted permissions, SDKs, identifiers and native
   libraries. Verify 16 KiB page compatibility in the final bundle, including transitive libraries.
7. Complete developer/account verification and any required closed testing. For covered new
   personal accounts, Play requires 12 continuously opted-in testers for 14 days before applying
   for production access. Check the requirements shown in your own Console.

## Verification to run when authorized

Project instructions prohibit automatically running build/test/tool commands. None of the
following has been run for this change:

```powershell
.\gradlew.bat :app:testPersonalDebugUnitTest :app:testPlayDebugUnitTest
.\gradlew.bat :app:lintPlayRelease :app:assemblePersonalDebug :app:assemblePlayDebug
.\gradlew.bat :app:bundlePlayRelease
.\gradlew.bat :app:connectedPlayDebugAndroidTest
python -m unittest discover -s tools/tests
```

The release bundle requires the upload key; connected tests require an Android device/emulator.
Existing plain-HTTP integration fixtures run only for the personal flavor. The Play flavor
has explicit tests proving rejection of HTTP and saved HTTP credentials. HTTPS authenticated
playback and redirect behavior still need device verification against a real TLS server.

On Android 7, 11, 13, 16 and 17 where available:

- Cancel folder selection; choose multiple folders; restart the app; revoke/remove access;
  unplug SD/USB storage. No all-files or photo-library permission should be requested by Play.
- Browse, search and sort selected folders, including names with spaces/non-ASCII characters.
  Open images, videos, ZIP/RAR/CBZ/CBR, history and favorites. Navigate adjacent folders/archives.
- Play and seek local provider videos and HTTPS videos. Confirm no downloaded full-video files.
- Check correct/wrong HTTPS credentials, untrusted certificates, HTTP rejection, same-origin
  redirects, cross-origin rejection and TLS downgrade rejection. No credentials may be sent over HTTP.
- Cancel downloads and kill/restart during a download. Try unknown Content-Length, oversized
  responses and low disk space. Partial files must disappear; originals must remain intact.
- Open selected files with another app. Only that file should be readable; no directory or write
  grant should be sent. Check staged-copy cleanup and Clear cache.
- Verify the policy against actual network behavior, cloud backup and device transfer exclusions.
  Check no Firebase initialization, Analytics/FCM network activity, or notification prompt in Play.
- Inspect the release bundle and run Play's pre-launch report before production rollout.

## Primary references

- [All files access](https://support.google.com/googleplay/android-developer/answer/10467955)
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [User Data and privacy policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [FileProvider configuration](https://developer.android.com/privacy-and-security/risks/file-providers)
- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [16 KiB compatibility](https://developer.android.com/guide/practices/page-sizes)
- [New personal account testing](https://support.google.com/googleplay/android-developer/answer/14151465)

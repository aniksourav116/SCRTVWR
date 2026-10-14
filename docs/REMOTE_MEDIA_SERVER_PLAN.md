# Custom remote media server and Android integration

## Objective

Build our own small server for Windows and Linux. The owner chooses folders to
share, and SCRTVWR browses those folders and views images and videos using its
existing list/grid, image pager, slideshow, and video player.

The first release is a read-only LAN media source. Remote archives and remote
file modifications are separate extensions.

## Server implementation

Use Go with its standard library, packaged as one executable per target platform.
Keep the server under `server/`, independent of the Android Gradle project. Start
with a command-line program and JSON configuration; service installation can
follow after the end-to-end connection works.

The core server needs no database, media transcoder, or recursive background
scanner. Read directory metadata when requested and stream files from disk.
Idle activity should be limited to listening for requests. Establish actual
memory and CPU usage through measurement before promising resource figures.

Example Windows configuration:

```json
{
  "serverId": "home-media",
  "listen": "0.0.0.0:8090",
  "tokenEnv": "SCRTVWR_SERVER_TOKEN",
  "tls": {
    "certificateFile": "server.crt",
    "privateKeyFile": "server.key"
  },
  "shares": [
    { "id": "photos", "name": "Photos", "root": "D:\\Media\\Photos" },
    { "id": "videos", "name": "Videos", "root": "E:\\Videos" }
  ]
}
```

On Linux, use roots such as `/srv/media/photos`. Share IDs and the server ID must
remain stable when a folder root or network address changes. Resolve configured
roots to absolute paths at startup and report inaccessible roots clearly. Do not
accept filesystem roots supplied by clients.

## HTTP API

All endpoints require an `Authorization: Bearer ...` header. Do not put tokens
in URLs. Use a versioned API and structured errors.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/info` | Server identity, protocol version, and capabilities |
| `GET /api/v1/shares` | IDs and display names of configured shares |
| `GET /api/v1/list?share=photos&path=Trips` | Immediate children of a folder |
| `GET /api/v1/content?share=photos&path=Trips/photo.jpg` | Original media bytes |
| `HEAD /api/v1/content?share=videos&path=clip.mp4` | Content metadata |

An empty relative path represents the share root. Paths are UTF-8 with `/`
separators. Encode query parameters with the client's URL builder.

Each directory entry returns its name, share-relative path, kind, media type,
size, and UTC modification timestamp. Include explicit parent information for
navigation. Windows and Linux absolute paths never appear in responses.

For the first version, return a complete immediate-directory listing with a
documented entry limit. If that limit is exceeded, return a clear error rather
than a silently truncated folder. Introduce pagination with stable listing
snapshots if real libraries require it. Preserve the existing sorting and
filtering behavior over complete directory metadata.

Stream regular media files using `net/http.ServeContent` with an opened file.
Support byte ranges, `206`, `416`, HEAD, modification dates, and conditional
requests. Use an appropriate content type and a file-version ETag that changes
when the media changes. Do not read an entire video into RAM.

Define consistent errors for invalid input, unauthorized requests, missing
shares/files, inaccessible folders, and listing limits. Never return OS paths
or tokens in error messages or logs.

## Access boundaries

- Expose only explicitly configured shares, with read-only operations.
- Use Go's traversal-resistant `os.Root` APIs for every listing and file open.
  Use Go 1.24 or newer and confirm required APIs against the chosen toolchain.
- Reject absolute paths and invalid platform-specific names. Treat URL decoding,
  `..`, Windows drive/UNC paths, symlinks, and junctions as access-boundary cases.
  Do not rely only on joining paths or checking string prefixes.
- Serve only regular files of the media types enabled for the first release;
  exclude devices, pipes, and sockets.
- Document that configured trees can contain mounted filesystems and hard links;
  root-relative file access does not establish an OS sandbox.
- Set header/idle timeouts and bounded request/preview concurrency. Streaming
  needs a timeout policy suitable for long videos rather than a short blanket
  response deadline.
- Default to authenticated HTTPS. Configure explicit Android trust for a private
  CA or use a trusted certificate; never disable certificate validation.
- If plain HTTP is supported for LAN development, make that an explicit server
  and app option. Credentials and media are unencrypted on that connection.

## Android architecture

Introduce a shared source interface with `LocalMediaRepository` and
`HttpMediaRepository`. Represent a location with source/server ID, share ID, and
relative path. Keep location identity separate from playback URLs and tokens.
Retain existing local path behavior through the local implementation.

Repository responsibilities include listing children, resolving metadata and
media access, locating parent folders, and navigating to adjacent media folders.
Both viewer activities must use this interface so they share playlist and
folder-navigation behavior.

Changes required:

1. Add saved network sources and an Add server flow: name, address, token,
   connection check, and share selection. Store credentials with Android
   Keystore-backed protection, exclude them from backup, and redact them in logs.
2. Replace `File` directory state and listing calls in `DirectoryViewModel` and
   path assumptions in `Directory.kt` with source-aware references.
3. Adapt `MediaCatalog`, sorting, and directory traversal to source metadata.
   Fetch neighboring folders lazily, cancel obsolete requests, and avoid walking
   an entire remote library on every page selection.
4. Update `MediaViewerViewModel` and activity extras to carry source-aware
   references. Keep image gestures, slideshow controls, and paging behavior.
5. Extend `MediaPreviewLoader` for authenticated HTTP images, decoding with
   bounded memory and caching. Cache keys include server/share/path, file
   version, and requested resolution. Limit remote preloading and cancel it when
   navigation changes.
6. Update `VideoPlayerActivity` to resolve HTTP URIs and use a Media3 HTTP data
   source with authentication. Replace its separate local playlist and
   adjacent-directory scans with the shared repository. Bound remote buffering
   and the number of prepared players.
7. Update preferences, bookmarks, recent folders, and history to store stable
   source references. Migrate existing local entries. An unavailable server must
   not cause remote history to be deleted by local `File.exists()` checks.
8. Keep remote file actions capability-aware. The initial remote source has no
   copy/move/delete commands. Opening remote media in another app requires a
   separate export/download path because the current FileProvider expects files.
9. Add `ACCESS_LOCAL_NETWORK` declaration and runtime handling for Android 17,
   because this project targets API 37. Keep INTERNET permission, configure
   certificate trust, and explicitly handle any supported HTTP development mode.

Show distinct loading, empty-folder, permission-denied, authentication-failed,
and server-unavailable states. Offer retry without losing the current folder.

## Thumbnails and media compatibility

The first working connection can use file icons, cached image previews, and
on-demand viewing. Original image bytes are transferred as needed; videos are
streamed without requiring a complete local download.

For fast grid thumbnails, add an optional `/api/v1/preview` capability after
browsing and playback work. Generate previews on demand with bounded workers,
file-version cache keys, a disk-cache size limit, and no recursive startup scan.
Image preview support can use a small Go image library. Video poster generation
can be an optional FFmpeg worker; it should not be required to run the core
server. Clients must have a fallback when previews are unavailable.

Direct streaming preserves the Android device's existing codec limitations.
Listing an extension does not guarantee playback. Transcoding and remote
ZIP/RAR entry serving are outside the first release.

## Delivery sequence

1. Implement the Go server, folder configuration, authenticated listings, and
   range-capable content endpoints, plus Windows/Linux usage instructions.
2. Introduce source-aware Android models and repositories, and wire saved
   servers into the current folder browser.
3. Connect image viewing and Media3 playback, including remote playlists,
   folder transitions, history, and bookmarks.
4. Add optional thumbnails and startup integration: systemd for Linux and a
   supported Windows service wrapper or a dedicated service mode.

First milestone: start the server with selected folders, add it in SCRTVWR,
browse those folders, swipe through images, seek in a large video, and reopen
remote bookmarks without copying the library to the phone.

## Verification to run when explicitly requested

Project instructions prohibit automatic build/test/tool execution. During
implementation, source inspection is allowed; run checks only after the user
explicitly requests them.

Planned checks are server protocol and traversal tests on Windows and Linux,
range/HEAD behavior, authentication, Unicode and encoded filenames, large
directories, interrupted streams, Android 17 LAN permission, certificate trust,
local-media regressions, remote bookmarks/history, and measured idle/streaming
CPU and memory. Compile and run the server and Android build only when requested.

## References

- Go HTTP file serving: https://pkg.go.dev/net/http#ServeContent
- Go root-relative access: https://pkg.go.dev/os#Root
- Go traversal-resistant access: https://go.dev/blog/osroot
- Media3 data sources: https://developer.android.com/reference/androidx/media3/datasource/DefaultDataSource
- Android local-network permission: https://developer.android.com/privacy-and-security/local-network-permission
- Android network security: https://developer.android.com/privacy-and-security/security-config

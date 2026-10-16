# SCRTVWR

Made this Media Viewer Application to brush up my Kotlin skills.
Nothing out of the ordinary here.

## Serving media from a PC

From the repository folder, run:

```powershell
python tools/media_http_server.py 8000 --bind 0.0.0.0 --directory "D:\Media"
```

Replace `D:\Media` with the folder you want to browse. Stop any other server using port 8000
first. Add `http://<PC-LAN-IP>:8000/` as an HTTP server in SCRTVWR.

This server uses Python's standard library and supports byte ranges (`206 Partial Content`),
so the video player can seek without downloading everything before the requested position.
Expected connection resets when the player seeks or stops do not print a traceback.
The ordinary `python -m http.server` still works for browsing, but lacks byte-range support.

### Video previews

The server can generate still-frame video thumbnails for both its browser directory listings
and the app's list/grid views. Install [FFmpeg](https://ffmpeg.org/download.html) on the PC
and put `ffmpeg` on `PATH`, then restart the server with the command above. It prints
`Video previews enabled` when FFmpeg is found. For an executable outside `PATH`, use:

```powershell
python tools/media_http_server.py 8000 --directory "D:\Media" --ffmpeg "C:\ffmpeg\bin\ffmpeg.exe"
```

Previews are JPEGs bounded to 256 pixels per side, extracted from the first video frame.
The PC generates them on demand with at most two decoders and a 20-second timeout per
preview. A memory cache retains up to 512 thumbnails or 32 MiB, invalidating a video when
its size or modification time changes. Temporary JPEG files are removed after extraction.
The app reuses its existing thumbnail memory/disk caches and downloads only the JPEG,
limited to 1 MiB, after a HEAD capability check. MP4, MKV, WebM, 3GP, MOV, AVI and M4V
are supported when FFmpeg can decode them.

Use `--no-previews` to disable this feature. Missing FFmpeg, damaged videos, and servers
without preview support leave the app's play icon visible. Video playback still works
without FFmpeg. The endpoint is `<video-url>?scrtvwr-preview=1`; supporting servers
advertise `X-SCRTVWR-Video-Previews: 1`.

Remote videos have no app file-size limit. They stream directly with a 32 MiB memory buffer
target and no video file or persistent video cache on the phone. Consumed playback data is
discarded; finishing, stopping, or closing the player releases its buffers. A slow read may wait up to
60 seconds before failing; total playback duration is unrestricted.

Remote images and archives also have no download-size cap, subject to available disk space.
Images download to a temporary file, decode at the requested resolution, and delete the
original temporary file after decoding, failure, or cancellation. Small decoded thumbnails
may remain cached. Archives need a complete local copy for ZIP/RAR random access and use a
1 GiB cache budget; the archive being opened is retained even if it exceeds that budget.
The existing 128 MiB limit on each decompressed archive image remains in place.

## Verification

From the repository folder, run these when you want to verify changes:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
python -m unittest discover -s tools/tests
```

On a device, open an image over 64 MiB, an archive over 512 MiB, and a multi-gigabyte video.
Seek near the end of the video, then switch videos, finish playback, and leave the viewer.
Replay a finished video and verify repeat-one/repeat-all still work. Check that video
playback creates no downloaded video files. Cancel an archive or image
download and check that its partial file is removed. The server should log `206` for video
range requests; unsupported codecs or damaged files can still fail independently of size.

For previews, restart the PC server with FFmpeg available and browse a video folder in
both a browser and the app's list/grid views. Confirm thumbnails appear and playback
still seeks. Restart with `--no-previews` (and try ordinary `python -m http.server`) to
check the play-icon fallback. Try a damaged video and a filename with spaces, `&`, or
non-ASCII characters. The preview tests mock FFmpeg; an actual video/device check is
still needed to verify decoding and display.

# Polymer Filebin

A vibe-coded reimplementation of polymer-autohost that uses [Filebin](https://filebin.net) to distribute the pack, so
it works on e4mc and other tunnels that only forward the game protocol.

Polymer, including the autohost code, is included as a submodule for reference only. It isn't part of the build.

## What it does

1. When the server starts, Polymer's resource pack is generated (like autohost).
2. The pack is uploaded to a Filebin bin as `pack-<sha1>.zip`. If the same pack is already there, the upload is
   skipped. Older `pack-*.zip` files are removed.
3. Joining players get the pack during the configuration phase. While it's still generating or uploading, they see
   a dialog with progress (same as autohost).
4. Every `recheck_interval_minutes` the server checks that the file is still on Filebin, and re-uploads it if it
   was deleted or the bin expires within a day. Failed uploads are retried every minute.

## Configuration

`config/polymer-filebin.json`, created on first start. Filebin-specific options:

| Option | Default | |
|---|---|---|
| `enabled` | `true` | |
| `filebin_url` | `https://filebin.net` | Filebin instance |
| `bin` | random | Bin name. **Anyone who knows it can delete files from it.** |
| `delete_old_files` | `true` | Delete previously uploaded packs |
| `recheck_interval_minutes` | `60` | |
| `download_link_lifetime` | `600` | Seconds a resolved download link is reused (filebin.net links last 900s) |
| `download_retries` | `2` | Retries with a fresh link when a client reports a failed download |

The remaining options (`required`, `mod_override`, `message`, `disconnect_message`, `informative_disconnect`,
`external_resource_packs`, `setup_early`, the `dialog_*` options, `clear_all_client_resource_packs`) work like
their polymer-autohost equivalents.

Don't enable polymer-autohost at the same time (`config/polymer/auto-host.json`), or players will get the pack twice.
Autohost is off by default outside a dev environment, and a warning is logged if both are enabled.

## Commands

`/polymer-filebin` (permission level 3, or `polymer.filebin.command`):

- `status`: upload state, bin and current download link
- `reupload`: force the current pack to be uploaded again
- `regenerate`: regenerate the pack (it gets uploaded when generation finishes)
- `resend`: push the pack to all online players again

## How downloads work

Filebin shows an HTML "verification" page instead of redirecting to the file for most user agents. Only user
agents containing `curl`, `Wget` or `VLC` skip it (see `cookieVerify` in filebin2's `internal/web/http.go`).
The vanilla client downloads packs with the fixed user agent `Minecraft Java/<version>`, so handing it
`https://filebin.net/<bin>/<file>` doesn't work. Tested against filebin.net: the client gets `200 text/html`.

Instead, the server calls the official download endpoint itself with a curl-compatible user agent. It reads the
`302` redirect target, which is a presigned storage URL valid for 15 minutes on filebin.net, and sends that URL
to clients. The URL is cached and re-resolved before it expires. If a client reports a failed download, a fresh
URL is resolved and the pack is offered again.


## Testing

`./gradlew runServer` starts a dev server with moretools (`moretools-1.10.1+26.2.jar`) on the classpath. In the dev
environment polymer-autohost defaults to enabled, so set `{"enabled": false}` in `run/config/polymer/auto-host.json`.

`testing/join-bot.py` is a tiny headless client that goes through login and configuration like the vanilla client.
It downloads the pushed pack with the vanilla User-Agent and checks its SHA-1. Pass a number to make it report that
many failed downloads first, to exercise the retry logic.

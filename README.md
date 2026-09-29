A vibe-coded reimplementation of polymer-autohost that uses Filebin as a distribution mechanism so it can run on e4mc

Polymer, including the autohost code, is included as a submodule for reference. This is not included directly in the build.

## How downloads work

Filebin shows an HTML "verification" page instead of redirecting to the file for most user agents. Only user
agents containing `curl`, `Wget` or `VLC` skip it (see `cookieVerify` in filebin2's `internal/web/http.go`).
The vanilla client downloads packs with the fixed user agent `Minecraft Java/<version>`, so handing it
`https://filebin.net/<bin>/<file>` doesn't work. Tested against filebin.net: the client gets `200 text/html`.

Instead, the server calls the official download endpoint itself with a curl-compatible user agent. It reads the
`302` redirect target, which is a presigned storage URL valid for 15 minutes on filebin.net, and sends that URL
to clients. The URL is cached and re-resolved before it expires. If a client reports a failed download, a fresh
URL is resolved and the pack is offered again.

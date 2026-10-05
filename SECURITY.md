# Security Policy

CAMusic for Linux collects nothing and talks only to the servers you configure on your
own network: MPD, Jellyfin and (optionally) HueGhost. Nothing goes to the cloud.

Connection details — server addresses, the Jellyfin API key and the HueGhost token — are
stored in plain JSON at `~/.config/camusic/settings.json`, readable by your user account.
Keep that file's permissions tight on a shared machine.

## Supported versions

Only the latest `master` is supported.

## Reporting a vulnerability

Please report security issues through GitHub issues or a pull request. They are handled
first, ahead of other work.

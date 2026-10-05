#!/bin/sh
# Install CAMusic for Linux from an extracted release tarball.
#
#   sudo ./install.sh            install to /opt/camusic-linux (+ menu entry, `camusic` command)
#   sudo ./install.sh --remove   take it out again
#
# The app is self-contained (it carries its own Java runtime), so there is nothing
# else to install. Settings live in ~/.config/camusic/settings.json and are kept.
set -eu

PREFIX=/opt/camusic-linux
HERE=$(cd "$(dirname "$0")" && pwd)

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root: sudo $0 $*" >&2
    exit 1
fi

if [ "${1:-}" = "--remove" ]; then
    rm -rf "$PREFIX"
    rm -f /usr/local/bin/camusic /usr/share/applications/camusic.desktop
    echo "Removed CAMusic. Settings in ~/.config/camusic were left as they were."
    exit 0
fi

if [ ! -x "$HERE/CAMusic/bin/CAMusic" ]; then
    echo "CAMusic/bin/CAMusic not found next to this script — run it from the extracted release." >&2
    exit 1
fi

rm -rf "$PREFIX"
mkdir -p "$PREFIX"
cp -a "$HERE/CAMusic/." "$PREFIX/"
install -m 0644 "$HERE/camusic.png" "$PREFIX/camusic.png"
ln -sf "$PREFIX/bin/CAMusic" /usr/local/bin/camusic
sed "s|@PREFIX@|$PREFIX|g" "$HERE/camusic.desktop" > /usr/share/applications/camusic.desktop
chmod 0644 /usr/share/applications/camusic.desktop

echo "Installed CAMusic to $PREFIX — start it from the Audio menu or with: camusic"

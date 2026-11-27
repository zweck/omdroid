# Default terminal execution helper

Unmodified `xdg-terminal-exec` 0.14.4 and its terminal fallback list from
Arch Linux ARM's `xdg-terminal-exec-0.14.4-1-any.pkg.tar.xz`.

Upstream: https://github.com/Vladimir-csp/xdg-terminal-exec

License: GPL-3.0-or-later; see the script's copyright notices and `COPYING`.

Package SHA-256:
`98d00248c758a03be0be449096e63438078e79edb3d3f34c0907d6f03d4f36e1`.

Script SHA-256:
`16c908e4de29c1528a606d2bd154ca059d35fb1cceb044e4b422d12b201651f1`.

The pinned guest image omits this dependency of `omarchy-launch-tui`. Session
startup links this copy only if no terminal execution helper is already on PATH.
It respects terminal desktop-entry arguments and user terminal preferences.

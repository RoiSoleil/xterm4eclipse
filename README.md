# Xterm4Eclipse : the terminal of VS Code in Eclipse

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/RoiSoleil/xterm4eclipse/build.yml)](https://github.com/RoiSoleil/xterm4eclipse/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/RoiSoleil/xterm4eclipse/branch/main/graph/badge.svg)](https://codecov.io/gh/RoiSoleil/xterm4eclipse)
[![GitHub](https://img.shields.io/github/license/RoiSoleil/xterm4eclipse)](LICENSE)

A terminal view for Eclipse rendered by [xterm.js](https://xtermjs.org/), the terminal emulator of
VS Code, connected to a local shell through the PTY of CDT (`org.eclipse.cdt.core.native`, already
installed with the standard Eclipse terminal). It behaves well with full screen terminal
applications such as Claude Code.

*Window > Show View > Other > Xterm > Xterm*

# Update Site

You can find the latest build of Xterm4Eclipse here:

https://github.com/RoiSoleil/xterm4eclipse/raw/update-site/latest/

# Features

- xterm.js rendering in the colors and font of the Eclipse theme.
- Shell chooser on the **+** button (shells of `/etc/shells`; PowerShell, cmd, Git Bash, WSL on
  Windows), with a default shell.
- Tab icon showing a running command (orange dot) and a finished command or a program asking for
  attention through the bell or an OSC 9 / OSC 777 notification (green dot).
- After a restart of Eclipse each terminal reopens with the same shell, directory and screen content.
- `exit` closes the view.
- Shift+Enter inserts a newline in Claude Code. Copy/paste: Ctrl+Shift+C / Ctrl+Shift+V,
  Shift+Insert, right click; Cmd+C / Cmd+V on macOS; Ctrl+C (with a selection) / Ctrl+V on Windows.
- Preferences in *Window > Preferences > Xterm Terminal*.

# Build

Requires JDK 21 and Maven 3.9 (the Apache distribution: the Maven packaged by some Linux
distributions does not work with Tycho).

```bash
mvn clean install   # update site in update-site/org.eclipse.xterm4eclipse/target/repository
```

Every push to `main` is built by GitHub Actions, which publishes the update site to the
`update-site` branch and a zipped copy to the `latest` release.

Two scripts help during development; they compile against an Eclipse installation (2024-06 or
later) and need bash:

```bash
export ECLIPSE_HOME=/path/to/eclipse   # macOS: /Applications/Eclipse.app/Contents/Eclipse
./build.sh --install   # quick build, copied to $ECLIPSE_HOME/dropins
./test.sh              # tests with coverage (fails under 85 % of lines)
```

The tests drive the real view, browser and shell, so they open windows on the current display and
only run on Linux and macOS. GitHub Actions runs them on a virtual display and sends the coverage
to Codecov.

# Platform notes

- Linux is the platform the plug-in is developed and tested on. The Windows and macOS code paths
  are covered by unit tests where they are plain logic (shell detection, environment, shortcuts
  configuration) but have not been run on those systems.
- The directory of the shell is read from the system on Linux and macOS. On Windows it is known
  only if the shell announces it (OSC 9;9 or OSC 7): this is set up automatically for `cmd.exe`.

# License

[Eclipse Public License, v2.0](http://www.eclipse.org/legal/epl-v20.html). The bundled xterm.js is
distributed under the [MIT license](bundles/org.eclipse.xterm4eclipse/web/LICENSE-xterm.txt).

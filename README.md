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
- *Show in Xterm* in the context menu of resources and of text editors: opens a shell in the directory
  of the selection or of the edited file.
- New terminals start in the project of the selected resource, or else of the file of the active
  editor.
- Toolbar button and Ctrl+Alt+Shift+X to open a terminal.
- The tab shows the icon of the shell or program it runs (bash, zsh, fish, PowerShell, cmd, Git Bash,
  WSL, Claude Code), with a badge for a running command (orange dot) and for a finished command or a
  program asking for attention through the bell or an OSC 9 / OSC 777 notification (green dot).
- After a restart of Eclipse each terminal reopens with the same shell, directory and screen content.
- `exit` closes the view. A program run on its own (Claude Code...) that fails, or a shell that cannot
  start, keeps its view open with its last output: Enter starts it again. *Restart* in the view menu
  starts a fresh shell in the directory of the current one.
- Shift+Enter inserts a newline in Claude Code (and runs the command at a shell prompt). Copy/paste:
  Ctrl+Shift+C / Ctrl+Shift+V, Shift+Insert, right click; Cmd+C / Cmd+V on macOS; Ctrl+C (with a
  selection) / Ctrl+V on Windows.
- The keys go to the shell, except a few Eclipse shortcuts that keep working in the terminal (Quick
  Access Ctrl+3, Open Resource / Type Ctrl+Shift+R / T, next view / editor / perspective
  Ctrl+F6 / F7 / F8, Ctrl+PageUp / PageDown, new terminal Ctrl+Alt+Shift+X): the list can be changed
  in the preferences.
- *Run Selected Text in Terminal* (Ctrl+Alt+Enter, Cmd+Alt+Enter on macOS, or the context menu of
  text editors): runs the selection, or the line of the cursor, in the last active terminal (a new
  one if there is none).
- Ctrl+click (Cmd+click on macOS) on a file path printed in the terminal opens it in an Eclipse
  editor, at the line and column given with it (`src/Foo.java:12:5`, `Foo.cs(12,5)`,
  `File "foo.py", line 12`). Relative paths are resolved from the directory of the shell. Ctrl+click on
  a URL opens it in the browser.
- *Rename…* in the view menu gives the terminal a fixed name, kept after a restart (the titles set by
  programs then only go to the tool tip; an empty name brings them back).
- *Move to the Editor Area* / *Move Back to the Views* (tool bar): the terminal view moves to the
  editor area, next to the active editor, and back to where it came from, as when its tab is dragged
  there. It is the same view: the shell, the program and the screen stay as they are. Eclipse keeps
  it there after a restart.
- Find in the terminal (Ctrl+Shift+F, Cmd+F on macOS, or *Find…* in the view menu): every match is
  highlighted, Enter / Shift+Enter go to the next / previous one, with case, whole word and regular
  expression options.
- On Linux the selection is the PRIMARY selection and the middle button pastes it (unless the program
  tracks the mouse). *Copy on select* in the preferences; *Select All* in the view menu.
- A paste of several lines is confirmed first (paste, paste as one line, cancel) when the shell
  would run each line at once, without bracketed paste (cmd.exe, sh...), as in VS Code.
- Pasted, dropped and sent texts are typed as text: their control characters (escape sequences) are
  removed, so that a copied text cannot run a command by itself.
- Security: the browser of the terminal can only show its own page; only http and https links are
  opened, and a file path is always opened in an Eclipse editor (the text editor when its default
  editor is a program of the system), never run. Shells and Claude Code are only looked up in the
  absolute directories of the PATH.
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

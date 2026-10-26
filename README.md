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

It holds two features: **Xterm4Eclipse**, the terminal, and **Xterm4Eclipse Coding Agents
(optional)**, what the terminal knows about [the coding agents](#coding-agents-optional) (Claude Code,
Codex, OpenCode, Reasonix...) and the [Claude Code Chat](#claude-code-chat) view. The terminal works
without the second one: leave it unchecked if you do not want it.

# Features

- xterm.js rendering in the colors and font of the Eclipse theme.
- Shell chooser on the **+** button (shells of `/etc/shells`; PowerShell, cmd, Git Bash, WSL on
  Windows), with a default shell.
- *Show in Xterm* in the context menu of resources and of text editors: opens a shell in the directory
  of the selection or of the edited file.
- New terminals start in the project of the selected resource, or else of the file of the active
  editor.
- Toolbar button and Ctrl+Alt+Shift+X to open a terminal.
- Ctrl+Alt+T opens a terminal with the default shell, and Ctrl+Alt+Shift+T asks which of the shells found on
  the system to open: they replace the ones of the Terminal view of Eclipse (Local Terminal), whose *Show In >
  Terminal* and *Show in Local Terminal* menus are hidden too. *Window > Preferences > General > Capabilities* shows them again (*Terminal*
  > *Show in Local Terminal*).
- The tab shows the icon of the shell or program it runs (bash, zsh, fish, PowerShell, cmd, Git Bash,
  WSL), with a badge for a running command (orange dot) and for a finished command or a program
  asking for attention through the bell or an OSC 9 / OSC 777 notification (green dot).
- The tab shows as busy while a program reports its progress (OSC 9;4, as on the tabs of Windows
  Terminal).
- After a restart of Eclipse each terminal reopens with the same shell, directory and screen content.
- `exit` closes the view. A program run on its own (Claude Code...) that fails, or a shell that cannot
  start, keeps its view open with its last output: Enter starts it again. *Restart* in the view menu
  starts a fresh shell in the directory of the current one.
- Shift+Enter inserts a newline in Claude Code (and runs the command at a shell prompt). Copy/paste:
  Ctrl+Shift+C / Ctrl+Shift+V, Shift+Insert, right click; Cmd+C / Cmd+V on macOS; Ctrl+C (with a
  selection) / Ctrl+V on Windows.
- The keys go to the shell, except a few Eclipse shortcuts that keep working in the terminal (Quick
  Access Ctrl+3, Open Resource / Type Ctrl+Shift+R / T, next view / editor / perspective
  Ctrl+F6 / F7 / F8, Ctrl+PageUp / PageDown, new terminal Ctrl+Alt+Shift+X, Ctrl+Alt+T, Ctrl+Alt+Shift+T): the list can be changed
  in the preferences.
- *Run Selected Text in Terminal* (Ctrl+Alt+Enter, Cmd+Alt+Enter on macOS, or the context menu of
  text editors): runs the selection, or the line of the cursor, in the last active terminal (a new
  one if there is none).
- Ctrl+click (Cmd+click on macOS) on a file path printed in the terminal opens it in an Eclipse
  editor, at the line and column given with it (`src/Foo.java:12:5`, `Foo.cs(12,5)`,
  `File "foo.py", line 12`). Relative paths are resolved from the directory of the shell. Ctrl+click on
  a folder opens it in the file manager of the system (Explorer, Finder, the file manager of the
  desktop; a package of macOS such as an `.app` is shown in its folder, never run). Ctrl+click on a URL
  opens it in the browser.
- *Rename…* in the view menu gives the terminal a fixed name, kept after a restart (the titles set by
  programs then only go to the tool tip; an empty name brings them back).
- *Move to the Editor Area* / *Move Back to the Views* (tool bar): the terminal view moves to the
  editor area, next to the active editor, and back to where it came from, as when its tab is dragged
  there. It is the same view: the shell, the program and the screen stay as they are. Eclipse keeps
  it there after a restart. The chat with Claude Code (see below) has the same button.
- Find in the terminal (Ctrl+Shift+F, Cmd+F on macOS, or *Find…* in the view menu): every match is
  highlighted, Enter / Shift+Enter go to the next / previous one, with case, whole word and regular
  expression options.
- On Linux the selection is the PRIMARY selection and the middle button pastes it (unless the program
  tracks the mouse). *Copy on select* in the preferences; *Select All* in the view menu.
- A paste of several lines is confirmed first (paste, paste as one line, cancel) when the shell
  would run each line at once, without bracketed paste (cmd.exe, sh...), as in VS Code.
- Files dropped on the terminal are typed as paths quoted for its shell (bash, PowerShell, cmd);
  in WSL, `C:\Users\me` becomes `/mnt/c/Users/me` and `\\wsl$\Ubuntu\home\me` becomes `/home/me`.
- Pasted, dropped and sent texts are typed as text: their control characters (escape sequences) are
  removed, so that a copied text cannot run a command by itself.
- Security: the browser of the terminal can only show its own page; only http and https links are
  opened, and a file path is always opened in an Eclipse editor (the text editor when its default
  editor is a program of the system), never run. Shells and coding agents are only looked up in the
  absolute directories of the PATH.
- Font and size of the terminal (by default those of the text font of Eclipse), lines kept above the
  screen, cursor style and blinking, Option as Meta on macOS. Ctrl+mouse wheel (Cmd on macOS) zooms
  a terminal.
- Environment variables for the shells in the preferences, one per line: `NAME=value` (with
  `${OTHER}` for the value of another variable, for example `PATH=/opt/tools/bin:${PATH}`) or `-NAME`
  to remove one.
- Preferences in *Window > Preferences > Xterm Terminal*.

# Coding agents (optional)

The feature *Xterm4Eclipse Coding Agents* (plug-in `org.eclipse.xterm4eclipse.harness`) holds all
that the terminal knows about the coding agents ("harnesses"). Without it the terminal runs them as
any other program.

- An agent that is on the PATH has its entry in the menus of the shells (the **+** button, *Show in
  Xterm*), started through the login shell of the user on Unix so that it finds the same environment
  (node, nvm...) as in a terminal; and its icon, in the menus and in the tab of its terminal.
- When Eclipse reopens a terminal, its agent comes back to its conversation. An agent that ends with
  an error comes back to it too on Enter; *Restart* starts a new conversation. An agent that cannot
  resume and fails at once starts a new conversation on the next Enter.
- When the [MCP server of Eclipse](https://github.com/vogellacompany/eclipse-mcp-server) runs, a new
  terminal of an agent that does not know it yet offers to add it as
  `eclipse-mcp-server-via-xterm4eclipse`, for the user and with the token of the server, before the
  agent starts so that it uses it at once: *Add*, *Always Add* (the next times without asking), *Not
  Now* (until Eclipse restarts) or *Never Ask* (*Always Add* and *Never Ask* are also in *Window >
  Preferences > Xterm Terminal > Coding Agents*). The agent adds the server itself, with its own command.
- A command line that chooses its conversation itself (`claude --continue`, `codex resume`...), or
  that runs another command of the agent (`codex exec`...), is run as it is.

| Agent | Program | Conversation of a reopened terminal | MCP server of Eclipse |
|---|---|---|---|
| Claude Code | `claude` | its own: started with `--session-id`, resumed with `--resume` | `claude mcp add --scope user` |
| Codex | `codex` | the last one of the folder: `codex resume --last` | `codex mcp add`; the token is in the environment of the terminals (`XTERM4ECLIPSE_MCP_TOKEN`) |
| OpenCode | `opencode` | its own, found in `opencode session list` after the first message and resumed with `--session`; else the last one (`--continue`) | `opencode mcp add --global` |
| Reasonix (DeepSeek) | `reasonix` | the last one of the folder: `--continue` | `reasonix mcp add` |
| Gemini CLI | `gemini` | its own: `--session-id`, resumed with `--resume` | `gemini mcp add -s user` |
| GitHub Copilot CLI | `copilot` | its own: `--session-id` | `copilot mcp add` |
| Cursor Agent | `cursor-agent` | the last one: `--continue` | - |
| Qwen Code | `qwen` | its own: `--session-id`, resumed with `--resume` | `qwen mcp add -s user` |
| Kilo Code | `kilo`, `kilocode` | the last one of the folder: `--continue` | `kilo mcp add` |
| Crush | `crush` | the last one of the folder: `--continue` | - |
| Amp | `amp` | - (its threads are kept on its server) | `amp mcp add` |
| goose | `goose` | its own: `goose session --name`, resumed with `--resume` | - |
| Aider | `aider` | the history of the folder: `--restore-chat-history` | - |

"Its own" is the conversation of that very terminal, also with several terminals of the agent in the
same folder. The agents that choose their ids themselves can only be asked for the last conversation
of the folder, which is done once the user has talked to the agent in the terminal. The command
lines are those of the versions of October 2026 (Claude Code 2.1, Codex 0.162, OpenCode 2.0,
Reasonix 2.37, Gemini CLI 0.63, Copilot CLI 1.0, Qwen Code 0.25, Kilo Code 7.8, Crush 0.98,
goose 1.54).

# Claude Code Chat

A view that talks with [Claude Code](https://claude.com/claude-code) as the Code tab of Claude Desktop
does, in the same feature as the coding agents (plug-in `org.eclipse.xterm4eclipse.claude`): *Window >
Show View > Xterm > Claude Code Chat*, the menu *Claude*, the tool bar or Ctrl+Alt+Shift+C. It is not
Claude Desktop: it runs the `claude` program of the computer, which must be installed and logged in.

- One conversation per view, in the project of the selection (or of the active editor). Its messages in
  Markdown, its tools in cards that open (the change of an Edit, the command of a Bash, the tasks of a
  TodoWrite...), the text as it comes.
- Claude Code asks the view before it uses a tool, unless its permission mode allows it: *Allow*, *Allow
  All Edits* or *Always Allow* (what Claude Code suggests, for the conversation) or *Deny*. The mode
  (*Ask before edits*, *Accept edits*, *Plan mode*) and the model are chosen below the message, also
  while Claude Code works.
- Enter sends, Shift+Enter starts a new line, Escape or *Stop* interrupts Claude Code. `@file` mentions
  a file, as in the CLI.
- *Compare* on the card of an Edit or a Write shows the change in the compare editor of Eclipse; a file
  path in a message (`src/Foo.java:12`) opens in an editor; the workspace is refreshed after the changes.
- *Ask Claude Code* (context menu of the editors and of the resources, Ctrl+Alt+Shift+K in an editor)
  adds the selected lines (`@src/Foo.java#L10-20`) or files to the message of the last chat.
- The conversations are those of the CLI and of Claude Desktop (`~/.claude/projects`): *Resume a
  Conversation...* lists those of the project, the latest first (a filter field narrows the list by
  title or date), and a chat that Eclipse reopens shows its messages and goes on with it
  (`claude --resume`). A conversation of the chat can be resumed in a terminal the same way, and the
  tool bar of the terminal view, like the menu *Claude*, has *Resume a Conversation in a Terminal...*:
  the same list, and `claude --resume` runs in a new terminal in the project.
- *Window > Preferences > Xterm Terminal > Claude Code Chat*: the program of Claude Code (else `claude`
  on the PATH or where its installer puts it), the model and the permission mode of new chats, more
  arguments, and whether a new chat continues the last conversation of its project.

Claude Code runs beside the view (`claude -p --input-format stream-json --output-format stream-json
--permission-prompt-tool stdio`), through the login shell of the user on Unix as in the terminal; the
view answers its requests for permissions on its input. The page of the view shows only its own
content: the messages are text, their links open in the browser of the system (http and https), their
files in Eclipse.

Another plug-in can teach the terminal other programs through the extension point
`org.eclipse.xterm4eclipse.terminalExtensions` (package `org.eclipse.xterm4eclipse.spi`): entries in
the menus, icons, and a say in how its program starts in a terminal and comes back.

# Build

Requires JDK 21 and Maven 3.9 (the Apache distribution: the Maven packaged by some Linux
distributions does not work with Tycho).

```bash
mvn clean install   # update site in update-site/org.eclipse.xterm4eclipse/target/repository
```

The terminal is the plug-in `bundles/org.eclipse.xterm4eclipse`; the coding agents are in
`bundles/org.eclipse.xterm4eclipse.harness`, which only depends on the first one, and the chat in
`bundles/org.eclipse.xterm4eclipse.claude`, which depends on neither, both in a feature of their own.

Every push to `main` is built by GitHub Actions, which publishes the update site to the
`update-site` branch and a zipped copy to the `latest` release.

Two scripts help during development; they compile against an Eclipse installation (2024-06 or
later) and need bash:

```bash
export ECLIPSE_HOME=/path/to/eclipse   # macOS: /Applications/Eclipse.app/Contents/Eclipse
./build.sh --install   # quick build, copied to $ECLIPSE_HOME/dropins (--no-harness: without the coding agents)
./test.sh              # tests with coverage (fails under 85 % of lines)
```

`test.sh` tests the terminal without the plug-in of the coding agents first, then the agents, each
with a stand-in for its program, then the chat, with a stand-in for Claude Code that replays its
messages (`TESTS=terminal`, `TESTS=harness` or `TESTS=claude` for one of them only). The icons
of the agents are drawn by `java tools/MakeHarnessIcons.java bundles/org.eclipse.xterm4eclipse.harness/icons`.

xterm.js and its addons are copied to `bundles/org.eclipse.xterm4eclipse/web` from the npm packages
of the versions in `xterm/package-lock.json`, checked against their integrity hashes (the build fails
if the files differ):

```bash
node xterm/update.mjs            # copies the locked versions
node xterm/update.mjs --latest   # moves to the latest stable versions (out for at least 7 days)
```

Every Monday, GitHub Actions (`xterm-update.yml`) opens a pull request when new versions are out and
runs the build and the tests on it; it is merged by hand once they pass.

The tests drive the real view, browser and shell, so they open windows on the current display. They
run on Linux, macOS and Windows (in Git Bash), with the bash of the PATH, which must be 4.4 or
later: on macOS, the one of Homebrew (`brew install bash`). GitHub Actions runs them on the three
systems and sends the coverage of Linux to Codecov.

# Platform notes

- The tests run on Linux, macOS and Windows at each change, with the real browser of each system
  (WebKitGTK, WebKit, Edge) and its pseudo terminals (ConPTY on Windows).
- The directory of the shell is read from the system on Linux and macOS. On Windows it is known
  only if the shell announces it (OSC 9;9 or OSC 7): this is set up automatically for `cmd.exe`, for
  PowerShell (5.1 and 7, whose prompt, that of the profile included, is wrapped when it runs as a
  shell) and for the bash of Git for Windows, MSYS2 and Cygwin (through `PROMPT_COMMAND`, which a
  `.bashrc` that replaces it turns off).
- On Windows the pseudo console clears the screen when a shell starts: the restored history and the
  end of a restarted shell are in the scrollback, above it.

# License

[Eclipse Public License, v2.0](http://www.eclipse.org/legal/epl-v20.html). The bundled xterm.js is
distributed under the [MIT license](bundles/org.eclipse.xterm4eclipse/web/LICENSE-xterm.txt).

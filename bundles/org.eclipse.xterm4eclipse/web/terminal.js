/* Glue between xterm.js and the Java side (BrowserFunctions prefixed with "java"). */
(function () {
	var THEMES = {
		dark: {
			background: '#1e1e1e', foreground: '#cccccc', cursor: '#cccccc', selectionBackground: '#264f78',
			black: '#000000', red: '#cd3131', green: '#0dbc79', yellow: '#e5e510',
			blue: '#2472c8', magenta: '#bc3fbc', cyan: '#11a8cd', white: '#e5e5e5',
			brightBlack: '#666666', brightRed: '#f14c4c', brightGreen: '#23d18b', brightYellow: '#f5f543',
			brightBlue: '#3b8eea', brightMagenta: '#d670d6', brightCyan: '#29b8db', brightWhite: '#e5e5e5'
		},
		light: {
			background: '#ffffff', foreground: '#333333', cursor: '#333333', selectionBackground: '#add6ff',
			black: '#000000', red: '#cd3131', green: '#107c10', yellow: '#949800',
			blue: '#0451a5', magenta: '#bc05bc', cyan: '#0598bc', white: '#555555',
			brightBlack: '#666666', brightRed: '#cd3131', brightGreen: '#14ce14', brightYellow: '#b5ba00',
			brightBlue: '#0451a5', brightMagenta: '#bc05bc', brightCyan: '#0598bc', brightWhite: '#a5a5a5'
		}
	};

	/**
	 * The words of a line of output that may be file paths, with the line and column that follow them.
	 * Whether the file exists is for the Java side to tell.
	 */
	function fileLinkCandidates(text) {
		var result = [];
		var word = /[^\s"'`()<>\[\]{}|,;]+/g;
		var match;
		while ((match = word.exec(text)) !== null) {
			var token = match[0].replace(/[:.]$/, ''); // end of the sentence or of a compiler message
			var start = match.index;
			if (/^[a-z][\w+.-]*:\/\//i.test(token)) {
				continue; // a URL, for the web links
			}
			var position = /^(.*?)(?::(\d+)(?:[:.](\d+))?)?$/.exec(token);
			var path = position[1];
			var line = position[2] ? parseInt(position[2], 10) : 0;
			var column = position[3] ? parseInt(position[3], 10) : 0;
			var end = start + token.length;
			var after = text.substring(end);
			var suffix = /^\((\d+)(?:,\s*(\d+))?\)/.exec(after) || /^", line (\d+)/.exec(after);
			if (!line && suffix) {
				line = parseInt(suffix[1], 10);
				column = suffix[2] ? parseInt(suffix[2], 10) : 0;
				if (suffix[0].charAt(0) === '(') {
					end += suffix[0].length;
				}
			}
			// A path has a separator or an extension; a word that is only dots or digits is not one.
			if (!/[\\\/]|\.[A-Za-z]\w*$/.test(path) || /^[.\d]*$/.test(path)) {
				continue;
			}
			result.push({ start: start, end: end, text: text.substring(start, end), path: path, line: line, column: column });
		}
		return result;
	}
	window.xtermFileLinkCandidates = fileLinkCandidates;

	// Called by the Java side once the page is loaded and the java* functions are available.
	window.xtermInit = function (cfg) {
		if (window.xtermWrite) {
			return;
		}
		// The ANSI palette follows the brightness of the Eclipse theme, the background and text
		// colors are the ones of Eclipse itself.
		function themeOf(config) {
			var result = Object.assign({}, THEMES[config.dark ? 'dark' : 'light']);
			if (config.background) {
				result.background = config.background;
			}
			if (config.foreground) {
				result.foreground = config.foreground;
				result.cursor = config.foreground;
			}
			document.body.style.background = result.background;
			return result;
		}
		var theme = themeOf(cfg);

		var term = new Terminal({
			allowProposedApi: true,
			cursorBlink: true,
			scrollback: 10000,
			fontFamily: cfg.fontFamily,
			fontSize: cfg.fontSize,
			theme: theme
		});
		var fit = new FitAddon.FitAddon();
		term.loadAddon(fit);
		term.loadAddon(new Unicode11Addon.Unicode11Addon());
		term.unicode.activeVersion = '11';
		var serializer = new SerializeAddon.SerializeAddon();
		term.loadAddon(serializer);
		term.loadAddon(new WebLinksAddon.WebLinksAddon(function (e, uri) {
			if (e.ctrlKey || e.metaKey) {
				javaOpenLink(uri);
			}
		}));

		// File paths printed by compilers, test runners, grep, git...: Ctrl+click opens them in Eclipse,
		// at the line and column given with them (file:12:5, file(12,5), File "file", line 12).
		term.registerLinkProvider({
			provideLinks: function (y, callback) {
				var line = term.buffer.active.getLine(y - 1);
				var candidates = line ? fileLinkCandidates(line.translateToString(true)) : [];
				if (!candidates.length) {
					callback(undefined);
					return;
				}
				var files = javaResolveFiles(candidates.map(function (c) { return c.path; })) || [];
				var links = [];
				candidates.forEach(function (candidate, i) {
					var file = files[i];
					if (!file) {
						return;
					}
					links.push({
						range: { start: { x: candidate.start + 1, y: y }, end: { x: candidate.end, y: y } },
						text: candidate.text,
						decorations: { pointerCursor: true, underline: true },
						activate: function (e) {
							if (e.ctrlKey || e.metaKey) {
								javaOpenFile(file, candidate.line, candidate.column);
							}
						}
					});
				});
				callback(links.length ? links : undefined);
			}
		});

		var container = document.getElementById('terminal');
		term.open(container);

		function copySelection() {
			var selection = term.getSelection();
			if (selection) {
				javaCopy(selection);
			}
			return !!selection;
		}

		function paste() {
			var text = javaPaste();
			if (text) {
				term.paste(text);
			}
		}

		// The Eclipse shortcuts the user keeps in the terminal, named as in the Keys preference page.
		var shortcuts = cfg.shortcuts || [];
		var KEY_NAMES = {
			PageUp: 'PAGE_UP', PageDown: 'PAGE_DOWN', ArrowUp: 'ARROW_UP', ArrowDown: 'ARROW_DOWN',
			ArrowLeft: 'ARROW_LEFT', ArrowRight: 'ARROW_RIGHT', Home: 'HOME', End: 'END', Insert: 'INSERT',
			Delete: 'DEL', Backspace: 'BS', Tab: 'TAB', Escape: 'ESC', Enter: 'CR', Space: 'SPACE'
		};
		function strokeName(e) {
			var code = e.code || '';
			var key;
			if (/^Key[A-Z]$/.test(code)) {
				key = code.substring(3);
			} else if (/^Digit[0-9]$/.test(code)) {
				key = code.substring(5);
			} else if (/^F[0-9]+$/.test(code)) {
				key = code;
			} else {
				key = KEY_NAMES[code] || KEY_NAMES[e.key];
			}
			return key && (e.altKey ? 'ALT+' : '') + (e.metaKey ? 'COMMAND+' : '') + (e.ctrlKey ? 'CTRL+' : '')
				+ (e.shiftKey ? 'SHIFT+' : '') + key;
		}

		term.attachCustomKeyEventHandler(function (e) {
			var down = e.type === 'keydown';
			if (down && shortcuts.length) {
				var stroke = strokeName(e);
				if (stroke && shortcuts.indexOf(stroke) >= 0 && javaShortcut(stroke)) {
					e.preventDefault();
					return false;
				}
			}
			// Shift+Enter inserts a newline in Claude Code and similar TUIs (same as Alt+Enter), and runs
			// the command at the prompt of a shell: the Java side knows which program has the terminal.
			if (e.key === 'Enter' && e.shiftKey && !e.ctrlKey && !e.altKey) {
				if (down) {
					javaShiftEnter();
				}
				e.preventDefault();
				return false;
			}
			// Platform conventions: Cmd+C / Cmd+V on macOS; on Windows Ctrl+V pastes and Ctrl+C copies
			// when text is selected (it still interrupts the running command otherwise).
			var plain = !e.shiftKey && !e.altKey;
			var nativeCopy = plain && e.code === 'KeyC'
				&& ((cfg.os === 'mac' && e.metaKey) || (cfg.os === 'windows' && e.ctrlKey && term.hasSelection()));
			var nativePaste = plain && e.code === 'KeyV'
				&& ((cfg.os === 'mac' && e.metaKey) || (cfg.os === 'windows' && e.ctrlKey));
			if (nativeCopy) {
				if (down && copySelection()) {
					term.clearSelection();
				}
				e.preventDefault();
				return false;
			}
			if (nativePaste) {
				if (down) {
					paste();
				}
				e.preventDefault();
				return false;
			}
			if (e.ctrlKey && e.shiftKey && e.code === 'KeyC') {
				if (down) {
					copySelection();
				}
				e.preventDefault();
				return false;
			}
			if ((e.ctrlKey && e.shiftKey && e.code === 'KeyV') || (e.shiftKey && !e.ctrlKey && e.key === 'Insert')) {
				if (down) {
					paste();
				}
				e.preventDefault();
				return false;
			}
			return true;
		});

		// Right click: copy the selection if there is one, paste otherwise. A program that tracks the
		// mouse (Claude Code, vim with mouse=a...) receives the click itself and may paste on its own:
		// doing it here too would paste twice. Shift+right click bypasses the program, as in xterm.
		container.addEventListener('contextmenu', function (e) {
			e.preventDefault();
			if (term.modes.mouseTrackingMode !== 'none' && !e.shiftKey) {
				return;
			}
			if (copySelection()) {
				term.clearSelection();
			} else {
				paste();
			}
		});

		// Drag and drop: without this the browser opens the dropped file in place of the terminal.
		// The paths are inserted as typed text, quoted for the shell by the Java side.
		function allowDrop(e) {
			e.preventDefault();
			if (e.dataTransfer) {
				e.dataTransfer.dropEffect = 'copy';
			}
		}
		window.addEventListener('dragenter', allowDrop, true);
		window.addEventListener('dragover', allowDrop, true);
		window.addEventListener('drop', function (e) {
			e.preventDefault();
			e.stopPropagation();
			var data = e.dataTransfer;
			if (!data) {
				return;
			}
			var uris = '';
			var text = '';
			try {
				uris = data.getData('text/uri-list') || '';
				text = data.getData('text/plain') || '';
			} catch (ignored) {
				// Some engines refuse to give the data of a foreign drag.
			}
			var dropped = javaDrop(uris, text);
			if (dropped) {
				term.paste(dropped);
			}
			term.focus();
		}, true);

		term.onData(function (data) { javaInput(data); });
		term.onBinary(function (data) { javaBinary(data); });
		term.onResize(function (size) { javaResize(size.cols, size.rows); });
		// Programs asking for attention: the bell, or a desktop notification (OSC 9 / OSC 777) as
		// sent by Claude Code when it has finished or needs input. "9;4" is a progress report.
		term.onBell(function () { javaAttention(); });
		term.parser.registerOscHandler(9, function (data) {
			if (data.indexOf('9;') === 0) {
				// OSC 9;9 is the current directory (cmd.exe, PowerShell, Windows Terminal convention).
				javaDirectory(data.substring(2).replace(/^"|"$/g, ''));
			} else if (data.indexOf('4;') !== 0) {
				javaAttention();
			}
			return true;
		});
		// The shell telling where it is: used to reopen the terminal in the same directory.
		term.parser.registerOscHandler(7, function (data) {
			javaDirectory(data);
			return true;
		});
		term.parser.registerOscHandler(1337, function (data) {
			if (data.indexOf('CurrentDir=') === 0) {
				javaDirectory(data.substring(11));
				return true;
			}
			return false;
		});
		term.parser.registerOscHandler(777, function () {
			javaAttention();
			return true;
		});
		term.onTitleChange(function (title) { javaTitle(title); });

		var started = false;
		function doFit() {
			if (!container.clientWidth || !container.clientHeight) {
				return; // view not visible yet
			}
			fit.fit();
			if (!started) {
				started = true;
				javaStart(term.cols, term.rows);
			}
		}
		var pendingFit = false;
		new ResizeObserver(function () {
			if (!pendingFit) {
				pendingFit = true;
				requestAnimationFrame(function () {
					pendingFit = false;
					doFit();
				});
			}
		}).observe(container);

		window.xtermWrite = function (base64) {
			var binary = atob(base64);
			var bytes = new Uint8Array(binary.length);
			for (var i = 0; i < binary.length; i++) {
				bytes[i] = binary.charCodeAt(i);
			}
			term.write(bytes);
		};
		// The screen and scrollback as escape sequences, replayed after an Eclipse restart.
		window.xtermSerialize = function () {
			return serializer.serialize({ scrollback: 2000, excludeAltBuffer: true, excludeModes: true });
		};
		window.xtermSetTheme = function (config) {
			term.options.theme = themeOf(config);
			term.options.fontFamily = config.fontFamily;
			term.options.fontSize = config.fontSize;
			shortcuts = config.shortcuts || [];
			doFit();
		};
		window.xtermTheme = function () { return term.options.theme; };
		window.addEventListener('focus', function () { term.focus(); });
		window.xtermClear = function () { term.clear(); };
		// "Run Selected Text in Terminal": pasted, so that a multi-line text is one block for the shell.
		window.xtermRun = function (base64) {
			var binary = atob(base64);
			var bytes = new Uint8Array(binary.length);
			for (var i = 0; i < binary.length; i++) {
				bytes[i] = binary.charCodeAt(i);
			}
			term.paste(new TextDecoder('utf-8').decode(bytes));
			javaInput('\r');
		};

		// The view keeps the browser hidden until now, to avoid a white flash while loading. A hidden
		// browser may have no size yet: fit again once it is shown.
		javaReady();
		doFit();
		setTimeout(doFit, 100);
		term.focus();
	};
})();

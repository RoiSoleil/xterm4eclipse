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
	function base64Bytes(base64) {
		var binary = atob(base64);
		var bytes = new Uint8Array(binary.length);
		for (var i = 0; i < binary.length; i++) {
			bytes[i] = binary.charCodeAt(i);
		}
		return bytes;
	}

	// replay: the screen of a terminal that moves here with its program, and the size it had.
	window.xtermInit = function (cfg, replay) {
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
			var bar = document.getElementById('find');
			bar.style.background = result.background;
			bar.style.color = result.foreground;
			var input = document.getElementById('find-input');
			input.style.background = result.background;
			input.style.color = result.foreground;
			input.style.borderColor = config.dark ? '#555555' : '#c8c8c8';
			return result;
		}
		var theme = themeOf(cfg);
		var currentDark = cfg.dark;

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
		var search = new SearchAddon.SearchAddon();
		term.loadAddon(search);
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

		// Pasted, dropped or sent text goes to the shell as typed text, never as terminal sequences: a
		// text copied from a web page could otherwise end the bracketed paste (ESC [201~) and run
		// what follows it, or send other escape sequences to the program. Only tab and line breaks
		// are kept of the control characters.
		function sanitize(text) {
			return text.replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]/g, '');
		}
		function safePaste(text) {
			term.paste(sanitize(text));
		}
		window.xtermSanitize = sanitize;

		function paste() {
			var text = javaPaste();
			if (text) {
				safePaste(text);
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

		// Find bar, as in VS Code: all the matches are highlighted, Enter / Shift+Enter go through them.
		var find = document.getElementById('find');
		var findInput = document.getElementById('find-input');
		var findCount = document.getElementById('find-count');
		var findOptions = { caseSensitive: false, wholeWord: false, regex: false };
		function searchOptions() {
			return {
				caseSensitive: findOptions.caseSensitive,
				wholeWord: findOptions.wholeWord,
				regex: findOptions.regex,
				decorations: currentDark
					? { matchBackground: '#623315', activeMatchBackground: '#a0522d',
						matchOverviewRuler: '#d186167e', activeMatchColorOverviewRuler: '#a0522d' }
					: { matchBackground: '#f8d7a8', activeMatchBackground: '#f6a54a',
						matchOverviewRuler: '#d186167e', activeMatchColorOverviewRuler: '#f6a54a' }
			};
		}
		function findNext(backwards, incremental) {
			var text = findInput.value;
			if (!text) {
				search.clearDecorations();
				findCount.textContent = '';
				find.classList.remove('missing');
				return;
			}
			var options = searchOptions();
			options.incremental = !!incremental;
			var found;
			try {
				found = backwards ? search.findPrevious(text, options) : search.findNext(text, options);
			} catch (invalidRegex) {
				found = false;
			}
			find.classList.toggle('missing', !found);
			if (!found) {
				findCount.textContent = 'No results';
			}
		}
		search.onDidChangeResults(function (e) {
			if (!findInput.value) {
				findCount.textContent = '';
			} else if (e.resultCount === 0) {
				findCount.textContent = 'No results';
			} else if (e.resultIndex < 0) {
				findCount.textContent = e.resultCount + '+ results';
			} else {
				findCount.textContent = (e.resultIndex + 1) + ' of ' + e.resultCount;
			}
		});
		function openFind() {
			var selection = term.getSelection();
			if (selection && selection.indexOf('\n') < 0) {
				findInput.value = selection;
			}
			find.classList.add('open');
			findInput.focus();
			findInput.select();
			findNext(false, true);
		}
		function closeFind() {
			find.classList.remove('open');
			search.clearDecorations();
			term.focus();
		}
		findInput.addEventListener('input', function () { findNext(false, true); });
		findInput.addEventListener('keydown', function (e) {
			if (e.key === 'Enter') {
				findNext(e.shiftKey, false);
				e.preventDefault();
			} else if (e.key === 'Escape') {
				closeFind();
				e.preventDefault();
			} else if (isFindKey(e)) {
				findInput.select();
				e.preventDefault();
			}
		});
		[['find-case', 'caseSensitive'], ['find-word', 'wholeWord'], ['find-regex', 'regex']].forEach(function (toggle) {
			var button = document.getElementById(toggle[0]);
			button.addEventListener('click', function () {
				findOptions[toggle[1]] = !findOptions[toggle[1]];
				button.classList.toggle('on', findOptions[toggle[1]]);
				// The add-on keeps the matches of an unchanged text: start the search over.
				search.clearDecorations();
				findInput.focus();
				findNext(false, true);
			});
		});
		document.getElementById('find-previous').addEventListener('click', function () { findNext(true, false); });
		document.getElementById('find-next').addEventListener('click', function () { findNext(false, false); });
		document.getElementById('find-close').addEventListener('click', closeFind);
		// Ctrl+Shift+F (the Ctrl+F of the shell moves the cursor), Cmd+F on macOS.
		function isFindKey(e) {
			return e.code === 'KeyF' && !e.altKey
				&& ((e.ctrlKey && e.shiftKey && !e.metaKey) || (cfg.os === 'mac' && e.metaKey && !e.ctrlKey && !e.shiftKey));
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
			if (isFindKey(e)) {
				if (down) {
					openFind();
				}
				e.preventDefault();
				return false;
			}
			if (e.key === 'Escape' && find.classList.contains('open')) {
				if (down) {
					closeFind();
				}
				e.preventDefault();
				return false;
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
				safePaste(dropped);
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

		// The screen of a moving terminal is drawn at its old size, then fitted like any terminal: xterm.js
		// then moves the lines and the cursor as the program expects.
		var replaying = false;
		if (replay && replay.data) {
			replaying = true;
			term.resize(replay.cols, replay.rows);
			term.write(base64Bytes(replay.data), function () {
				replaying = false;
				doFit();
			});
		}

		var started = false;
		function doFit() {
			if (replaying || !container.clientWidth || !container.clientHeight) {
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
			term.write(base64Bytes(base64));
		};
		// The screen and scrollback as escape sequences, replayed after an Eclipse restart.
		window.xtermSerialize = function () {
			return serializer.serialize({ scrollback: 2000, excludeAltBuffer: true, excludeModes: true });
		};
		// The whole state of the screen, alternate screen and modes included, to show it again in
		// another part when the terminal moves there with its running program.
		window.xtermSnapshot = function () {
			return serializer.serialize({ excludeAltBuffer: false, excludeModes: false });
		};
		window.xtermSetTheme = function (config) {
			currentDark = config.dark;
			term.options.theme = themeOf(config);
			term.options.fontFamily = config.fontFamily;
			term.options.fontSize = config.fontSize;
			shortcuts = config.shortcuts || [];
			doFit();
		};
		window.xtermTheme = function () { return term.options.theme; };
		window.addEventListener('focus', function () {
			if (find.classList.contains('open') && document.activeElement === findInput) {
				return;
			}
			term.focus();
		});
		window.xtermFind = openFind;
		window.xtermClear = function () { term.clear(); };
		// "Run Selected Text in Terminal": pasted, so that a multi-line text is one block for the shell.
		window.xtermRun = function (base64) {
			var binary = atob(base64);
			var bytes = new Uint8Array(binary.length);
			for (var i = 0; i < binary.length; i++) {
				bytes[i] = binary.charCodeAt(i);
			}
			safePaste(new TextDecoder('utf-8').decode(bytes));
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

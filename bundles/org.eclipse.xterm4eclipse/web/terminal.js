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

		term.attachCustomKeyEventHandler(function (e) {
			var down = e.type === 'keydown';
			// Shift+Enter inserts a newline in Claude Code and similar TUIs (same as Alt+Enter).
			if (e.key === 'Enter' && e.shiftKey && !e.ctrlKey && !e.altKey) {
				if (down) {
					javaInput('\x1b\r');
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

		// Right click: copy the selection if there is one, paste otherwise.
		container.addEventListener('contextmenu', function (e) {
			e.preventDefault();
			if (copySelection()) {
				term.clearSelection();
			} else {
				paste();
			}
		});

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
			doFit();
		};
		window.xtermTheme = function () { return term.options.theme; };
		window.addEventListener('focus', function () { term.focus(); });
		window.xtermClear = function () { term.clear(); };

		// The view keeps the browser hidden until now, to avoid a white flash while loading. A hidden
		// browser may have no size yet: fit again once it is shown.
		javaReady();
		doFit();
		setTimeout(doFit, 100);
		term.focus();
	};
})();

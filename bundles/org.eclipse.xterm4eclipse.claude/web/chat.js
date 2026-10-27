/*
 * The chat: the messages of Claude Code as Java gives them (chatEvent), and what the user does,
 * which goes to Java through the java* functions.
 */
(function () {
	"use strict";

	var md = window.chatMarkdown;
	var log = document.getElementById("log");
	var input = document.getElementById("input");
	var modelSelect = document.getElementById("model");
	var modeSelect = document.getElementById("mode");
	var usage = document.getElementById("usage");
	var working = document.getElementById("working");
	var sendButton = document.getElementById("send");
	var stopButton = document.getElementById("stop");
	var attachments = document.getElementById("attachments");

	var config = {};
	/** The cards of the tools, by the id of their use. */
	var tools = {};
	/** The cards of the requests of permission, by the id of the request. */
	var requests = {};
	/** The text of the assistant as it comes, until its message. */
	var streaming = null;
	/** The messages of the user that Claude Code has not answered yet. */
	var turns = 0;
	/** The log follows the last message, unless the user has scrolled up. */
	var following = true;
	/** The cost of the Claude Code that runs, which it counts from its start, and of those before it. */
	var cost = 0;
	var costBefore = 0;

	function element(tag, className, text) {
		var created = document.createElement(tag);
		if (className) {
			created.className = className;
		}
		if (text !== undefined && text !== null) {
			created.textContent = text;
		}
		return created;
	}

	function scroll() {
		if (following) {
			log.scrollTop = log.scrollHeight;
		}
	}

	log.addEventListener("scroll", function () {
		following = log.scrollTop + log.clientHeight >= log.scrollHeight - 40;
	});

	function add(node, parent) {
		(parent || log).appendChild(node);
		scroll();
		return node;
	}

	/** A path as the user reads it: relative to the folder of the chat when it is in it. */
	function relative(path) {
		var base = (config.directory || "").replace(/\\/g, "/").replace(/\/$/, "");
		var shown = String(path || "").replace(/\\/g, "/");
		return base && shown.indexOf(base + "/") === 0 ? shown.slice(base.length + 1) : shown;
	}

	function setTurns(value) {
		turns = Math.max(0, value);
		document.body.classList.toggle("busy", turns > 0);
	}

	// Theme.

	function applyTheme(theme) {
		var style = document.documentElement.style;
		style.setProperty("--background", theme.background);
		if (theme.foreground) {
			style.setProperty("--foreground", theme.foreground);
		} else {
			style.removeProperty("--foreground");
		}
		style.setProperty("--font", theme.font);
		style.setProperty("--font-size", theme.fontSize + "px");
		style.setProperty("--code-font", theme.codeFont);
		style.setProperty("--code-font-size", theme.codeFontSize + "px");
		document.body.classList.toggle("dark", !!theme.dark);
		document.body.classList.toggle("light", !theme.dark);
	}

	function option(select, value, label) {
		var created = element("option", null, label);
		created.value = value;
		select.appendChild(created);
		return created;
	}

	function showModel(value) {
		var known = false;
		Array.prototype.forEach.call(modelSelect.options, function (existing) {
			known = known || existing.value === value;
		});
		if (!known && value) {
			option(modelSelect, value, value);
		}
		modelSelect.value = value || "";
	}

	window.chatInit = function (initial) {
		config = initial;
		applyTheme(initial);
		document.body.classList.add(initial.os);
		modelSelect.innerHTML = "";
		option(modelSelect, "", "Default model");
		initial.models.forEach(function (model) {
			option(modelSelect, model, model.charAt(0).toUpperCase() + model.slice(1));
		});
		showModel(initial.model);
		modeSelect.innerHTML = "";
		Object.keys(initial.modes).forEach(function (mode) {
			option(modeSelect, mode, initial.modes[mode]);
		});
		modeSelect.value = initial.mode;
		input.focus();
	};

	window.chatSetTheme = function (theme) {
		config.dark = theme.dark;
		applyTheme(theme);
	};

	// What the user does.

	/** The images pasted or dropped, until the message is sent; each with its media type and base64 data. */
	var images = [];
	/** The API takes these formats, up to 5 MB each (counted on the base64 data, to be safe). */
	var IMAGE_TYPES = /^image\/(png|jpeg|gif|webp)$/;
	var MAX_IMAGE = 5 * 1024 * 1024;
	/** What Java keeps of a message: its count and, under the 32 MB of a request of the API, its total. */
	var MAX_IMAGES = 20;
	var MAX_TOTAL = 25 * 1024 * 1024;
	/** The images that are still being read, and whether the user sent the message meanwhile. */
	var reading = 0;
	var sendWhenRead = false;

	function showAttachments() {
		attachments.textContent = "";
		images.forEach(function (image, index) {
			var item = element("div", "attachment");
			var thumbnail = element("img");
			thumbnail.src = dataUrl(image);
			item.appendChild(thumbnail);
			var remove = element("button", null, "\u00d7");
			remove.title = "Remove the image";
			remove.addEventListener("click", function () {
				images.splice(index, 1);
				showAttachments();
				input.focus();
			});
			item.appendChild(remove);
			attachments.appendChild(item);
		});
		attachments.classList.toggle("some", images.length > 0);
	}

	function dataUrl(image) {
		return "data:" + image.media_type + ";base64," + image.data;
	}

	function addImages(files) {
		var refused = false;
		Array.prototype.forEach.call(files, function (file) {
			if (!IMAGE_TYPES.test(file.type)) {
				refused = true;
				return;
			}
			reading++;
			var reader = new FileReader();
			reader.onload = function () {
				var match = /^data:([^;]+);base64,(.*)$/.exec(reader.result);
				if (!match || !match[2]) {
					notice("error", "The image is empty.");
					return;
				}
				var total = images.reduce(function (sum, image) { return sum + image.data.length; }, match[2].length);
				if (match[2].length > MAX_IMAGE) {
					notice("error", "The image is too large: Claude Code takes images up to 5 MB.");
				} else if (images.length >= MAX_IMAGES || total > MAX_TOTAL) {
					notice("error", "Too many images for one message.");
				} else {
					images.push({ media_type: match[1], data: match[2] });
					showAttachments();
				}
			};
			reader.onerror = function () {
				sendWhenRead = false;
				notice("error", "The image could not be read.");
			};
			reader.onloadend = function () {
				reading--;
				if (reading === 0 && sendWhenRead) {
					sendWhenRead = false;
					send();
				}
			};
			reader.readAsDataURL(file);
		});
		if (refused) {
			notice("error", "Claude Code takes PNG, JPEG, GIF and WebP images only.");
		}
	}

	function send() {
		var text = input.value;
		if (reading > 0) {
			// An image is still being read: it goes with this message.
			sendWhenRead = true;
			return;
		}
		if (!text.trim() && images.length === 0) {
			return;
		}
		var sent = images;
		images = [];
		showAttachments();
		input.value = "";
		resize();
		following = true;
		showUser(text, sent);
		setTurns(turns + 1);
		javaSend(text, sent.length ? JSON.stringify(sent) : "");
	}

	// An image of the clipboard is attached; a text, even with an image (a copy from a page), is pasted as usual.
	input.addEventListener("paste", function (e) {
		var data = e.clipboardData;
		if (!data || !data.files || data.files.length === 0) {
			return;
		}
		if (data.getData("text/plain")) {
			return;
		}
		e.preventDefault();
		addImages(data.files);
	});
	// Only files are taken: the drag and drop of text into the input stays the browser's.
	function hasFiles(e) {
		return e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types || [], "Files") >= 0;
	}
	document.addEventListener("dragover", function (e) {
		if (hasFiles(e)) {
			e.preventDefault();
		}
	});
	document.addEventListener("drop", function (e) {
		if (hasFiles(e)) {
			e.preventDefault();
			addImages(e.dataTransfer.files);
		}
	});

	function resize() {
		input.style.height = "auto";
		input.style.height = Math.min(input.scrollHeight + 2, Math.round(window.innerHeight * 0.4)) + "px";
	}

	input.addEventListener("input", resize);
	input.addEventListener("keydown", function (e) {
		if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
			e.preventDefault();
			send();
		}
	});
	document.addEventListener("keydown", function (e) {
		if (e.key === "Escape" && turns > 0) {
			e.preventDefault();
			javaInterrupt();
		}
	});
	// The view gives the focus to the browser: the message gets it.
	window.addEventListener("focus", function () {
		if (!window.getSelection() || window.getSelection().isCollapsed) {
			input.focus();
		}
	});
	sendButton.addEventListener("click", send);
	stopButton.addEventListener("click", function () {
		javaInterrupt();
	});
	modelSelect.addEventListener("change", function () {
		javaSetModel(modelSelect.value);
	});
	modeSelect.addEventListener("change", function () {
		javaSetMode(modeSelect.value);
	});
	log.addEventListener("click", function (e) {
		var target = e.target.closest ? e.target.closest("a") : null;
		if (!target) {
			return;
		}
		e.preventDefault();
		if (target.dataset.path) {
			javaOpenFile(target.dataset.path, parseInt(target.dataset.line || "0", 10));
		} else if (target.dataset.href) {
			javaOpenLink(target.dataset.href);
		}
	});

	// The messages.

	/** @param pictures the images of the message, each with its media type and base64 data */
	function showUser(text, pictures) {
		var bubble = add(element("div", "message user"));
		var body = bubble.appendChild(element("div", "user-text"));
		if (pictures && pictures.length) {
			var shown = body.appendChild(element("div", "user-images"));
			pictures.forEach(function (picture) {
				var image = element("img");
				image.src = dataUrl(picture);
				shown.appendChild(image);
			});
		}
		if (text) {
			body.appendChild(element("div", null, text));
		}
		return bubble;
	}

	function notice(level, text, details) {
		var shown = add(element("div", "notice " + level, text));
		if (details) {
			shown.appendChild(element("pre", null, details));
		}
		return shown;
	}

	/** The text that came is replaced by the message of the assistant. */
	function endStreaming() {
		if (streaming) {
			streaming.remove();
			streaming = null;
		}
	}

	/** The turn ended before the message of the assistant (interrupted): the text that came stays. */
	function keepStreaming() {
		if (streaming) {
			streaming.className = "message assistant markdown";
			streaming.innerHTML = md.render(streaming.textContent);
			streaming = null;
		}
	}

	/** Where the messages of a tool of a subagent go: in the card of the tool that runs it. */
	function container(parent) {
		var card = parent ? tools[parent] : null;
		if (!card) {
			return log;
		}
		var inner = card.querySelector(":scope > .tool-body > .subagent");
		if (!inner) {
			inner = card.querySelector(":scope > .tool-body").appendChild(element("div", "subagent"));
		}
		return inner;
	}

	function showAssistant(message, parent) {
		var blocks = message && message.content;
		if (!Array.isArray(blocks)) {
			return;
		}
		var target = container(parent);
		blocks.forEach(function (block) {
			if (block.type === "text" && block.text) {
				var text = add(element("div", "message assistant markdown"), target);
				text.innerHTML = md.render(block.text);
			} else if (block.type === "thinking" && block.thinking) {
				var thinking = add(element("details", "thinking"), target);
				thinking.appendChild(element("summary", null, "Thinking"));
				thinking.appendChild(element("div", "thinking-text", block.thinking));
			} else if (block.type === "tool_use") {
				add(toolCard(block), target);
			}
		});
		if (!parent && message.usage) {
			var context = (message.usage.input_tokens || 0) + (message.usage.cache_creation_input_tokens || 0)
				+ (message.usage.cache_read_input_tokens || 0);
			if (context > 0) {
				usage.dataset.context = context;
				showUsage();
			}
		}
	}

	function showUsage() {
		var parts = [];
		if (usage.dataset.context) {
			var context = parseInt(usage.dataset.context, 10);
			parts.push((context >= 1000 ? (context / 1000).toFixed(context >= 100000 ? 0 : 1) + "k" : context) + " tokens");
		}
		var total = costBefore + cost;
		if (total > 0) {
			parts.push("$" + total.toFixed(total < 1 ? 4 : 2));
		}
		usage.textContent = parts.join(" · ");
		usage.title = "Context of the conversation, and what this chat has cost";
	}

	/** What a tool works on, in a few words. */
	function target(name, input) {
		input = input || {};
		if (input.file_path || input.notebook_path) {
			return relative(input.file_path || input.notebook_path);
		}
		if (name === "Bash" || name === "PowerShell") {
			return String(input.command || "").split("\n")[0];
		}
		if (name === "Grep") {
			return input.pattern + (input.path ? " in " + relative(input.path) : "");
		}
		if (input.pattern) {
			return input.pattern;
		}
		if (input.url) {
			return input.url;
		}
		if (input.query) {
			return input.query;
		}
		if (input.description) {
			return input.description;
		}
		for (var key in input) {
			if (typeof input[key] === "string") {
				return input[key].split("\n")[0];
			}
		}
		return "";
	}

	function diff(before, after) {
		var shown = element("div", "diff");
		if (before) {
			String(before).split("\n").forEach(function (line) {
				shown.appendChild(element("div", "deleted", "- " + line));
			});
		}
		if (after) {
			String(after).split("\n").forEach(function (line) {
				shown.appendChild(element("div", "inserted", "+ " + line));
			});
		}
		return shown;
	}

	/** The input of a tool, as the user reads it best. */
	function inputView(name, input) {
		input = input || {};
		var view = element("div", "tool-input");
		if (name === "Edit") {
			view.appendChild(diff(input.old_string, input.new_string));
		} else if (name === "MultiEdit" && Array.isArray(input.edits)) {
			input.edits.forEach(function (edit) {
				view.appendChild(diff(edit.old_string, edit.new_string));
			});
		} else if (name === "Write") {
			var lines = String(input.content || "").split("\n");
			view.appendChild(element("pre", "write", lines.slice(0, 200).join("\n") + (lines.length > 200 ? "\n…" : "")));
		} else if (name === "Bash" || name === "PowerShell") {
			if (input.description) {
				view.appendChild(element("div", "tool-description", input.description));
			}
			view.appendChild(element("pre", "command", input.command));
		} else if (name === "TodoWrite" && Array.isArray(input.todos)) {
			var list = view.appendChild(element("ul", "todos"));
			input.todos.forEach(function (todo) {
				var mark = todo.status === "completed" ? "☑ " : todo.status === "in_progress" ? "◐ " : "☐ ";
				list.appendChild(element("li", "todo " + todo.status, mark + (todo.content || todo.activeForm || "")));
			});
		} else if (name === "ExitPlanMode" && input.plan) {
			var plan = view.appendChild(element("div", "markdown"));
			plan.innerHTML = md.render(input.plan);
		} else {
			view.appendChild(element("pre", null, JSON.stringify(input, null, 2)));
		}
		return view;
	}

	function toolTitle(name, input, summary) {
		summary.appendChild(element("span", "tool-name", name));
		var what = target(name, input);
		if (input && (input.file_path || input.notebook_path)) {
			var link = element("a", "file", what);
			link.dataset.path = input.file_path || input.notebook_path;
			link.dataset.line = "0";
			link.title = "Open in Eclipse";
			summary.appendChild(link);
		} else if (what) {
			summary.appendChild(element("span", "tool-target", what));
		}
	}

	function toolCard(block) {
		var card = element("details", "tool");
		card.dataset.id = block.id;
		var summary = card.appendChild(element("summary"));
		toolTitle(block.name, block.input, summary);
		summary.appendChild(element("span", "tool-state"));
		var body = card.appendChild(element("div", "tool-body"));
		body.appendChild(inputView(block.name, block.input));
		// Its plan and its list of tasks are what the user wants to read.
		if (block.name === "TodoWrite" || block.name === "ExitPlanMode") {
			card.open = true;
		}
		tools[block.id] = card;
		return card;
	}

	function showResults(blocks, parent) {
		var answers = blocks.some(function (block) { return block.type === "tool_result"; });
		if (!parent && !answers) {
			// A message of the user with images, as it is in a conversation that is resumed.
			var pictures = blocks.filter(function (block) {
				return block.type === "image" && block.source && block.source.data;
			}).map(function (block) {
				return { media_type: block.source.media_type, data: block.source.data };
			});
			if (pictures.length) {
				var texts = blocks.filter(function (block) {
					return block.type === "text" && block.text;
				});
				showUser(texts.map(function (block) { return block.text; }).join("\n"), pictures);
				blocks = blocks.filter(function (block) {
					return block.type !== "text" && block.type !== "image";
				});
			}
		}
		blocks.forEach(function (block) {
			if (block.type === "tool_result") {
				var card = tools[block.tool_use_id];
				if (!card) {
					return;
				}
				card.classList.add(block.is_error ? "failed" : "done");
				var body = card.querySelector(":scope > .tool-body");
				if (block.content) {
					var result = body.appendChild(element("details", "tool-result"));
					result.appendChild(element("summary", null, block.is_error ? "Error" : "Result"));
					result.appendChild(element("pre", null, block.content));
					if (block.is_error) {
						result.open = true;
					}
				}
				if (block.change) {
					var compare = element("button", "compare", "Compare");
					compare.title = "Show the change in the compare editor of Eclipse";
					compare.addEventListener("click", function (e) {
						e.preventDefault();
						javaOpenChange(block.tool_use_id);
					});
					card.querySelector(":scope > summary").appendChild(compare);
				}
				scroll();
			} else if (block.type === "text" && block.text) {
				if (/^\[Request interrupted/.test(block.text)) {
					notice("info", "Interrupted");
				} else if (!parent) {
					showUser(block.text);
				}
			}
		});
	}

	function showRequest(event) {
		endStreaming();
		var card = add(element("div", "permission"));
		var title = card.appendChild(element("div", "permission-title"));
		title.appendChild(document.createTextNode("Claude Code wants to use "));
		var summary = title.appendChild(element("span", "permission-tool"));
		toolTitle(event.tool_name, event.input, summary);
		if (event.description && event.description !== target(event.tool_name, event.input)) {
			card.appendChild(element("div", "tool-description", event.description));
		}
		card.appendChild(inputView(event.tool_name, event.input));
		var buttons = card.appendChild(element("div", "buttons"));
		function answer(decision, label) {
			javaAnswer(event.request_id, decision);
			buttons.remove();
			card.classList.add("answered", decision === "deny" ? "denied" : "allowed");
			card.appendChild(element("div", "answer", label));
			delete requests[event.request_id];
			input.focus();
		}
		var allow = buttons.appendChild(element("button", "primary", "Allow"));
		allow.addEventListener("click", function () {
			answer("allow", "Allowed");
		});
		if (event.always) {
			var always = buttons.appendChild(element("button", null,
				event.tool_name === "Edit" || event.tool_name === "Write" || event.tool_name === "MultiEdit"
					? "Allow All Edits" : "Always Allow"));
			always.title = "Allow, and do not ask again in this conversation";
			always.addEventListener("click", function () {
				answer("always", "Allowed, without asking again");
			});
		}
		var deny = buttons.appendChild(element("button", null, "Deny"));
		deny.addEventListener("click", function () {
			answer("deny", "Denied");
		});
		requests[event.request_id] = card;
		allow.focus();
	}

	window.chatEvent = function (event) {
		switch (event.type) {
		case "system":
			showModel(event.model);
			if (event.permissionMode && config.modes[event.permissionMode]) {
				modeSelect.value = event.permissionMode;
			}
			break;
		case "stream_event":
			if (event.parent) {
				break;
			}
			if (event.start) {
				endStreaming();
			} else if (event.text) {
				if (!streaming) {
					streaming = add(element("div", "message assistant streaming"));
				}
				streaming.textContent += event.text;
				scroll();
			}
			break;
		case "assistant":
			if (!event.parent) {
				endStreaming();
			}
			showAssistant(event.message, event.parent);
			break;
		case "user":
			if (typeof event.text === "string") {
				showUser(event.text);
			} else if (Array.isArray(event.content)) {
				showResults(event.content, event.parent);
			}
			break;
		case "control_request":
			showRequest(event);
			break;
		case "control_cancel_request":
			var cancelled = requests[event.request_id];
			if (cancelled) {
				var buttons = cancelled.querySelector(".buttons");
				if (buttons) {
					buttons.remove();
				}
				cancelled.classList.add("answered");
				cancelled.appendChild(element("div", "answer", "Cancelled"));
				delete requests[event.request_id];
			}
			break;
		case "result":
			keepStreaming();
			setTurns(turns - 1);
			if (typeof event.total_cost_usd === "number") {
				cost = event.total_cost_usd;
				showUsage();
			}
			if (event.is_error && event.subtype !== "error_during_execution") {
				notice("error", event.result || "Claude Code could not answer (" + event.subtype + ").",
					Array.isArray(event.errors) ? event.errors.join("\n") : null);
			}
			break;
		case "x4e_exit":
			keepStreaming();
			setTurns(0);
			costBefore += cost;
			cost = 0;
			Object.keys(requests).forEach(function (id) {
				window.chatEvent({ type: "control_cancel_request", request_id: id });
			});
			if (event.code !== 0) {
				notice("error", "Claude Code has ended (exit code " + event.code + "). The next message starts it again.",
					event.errors || null);
			}
			break;
		case "x4e_notice":
			endStreaming();
			if (event.level === "error") {
				setTurns(0);
			}
			notice(event.level, event.text);
			break;
		case "x4e_history_end":
			add(element("div", "separator", "Resumed"));
			following = true;
			scroll();
			break;
		case "x4e_insert":
			var start = input.selectionStart;
			var before = input.value.slice(0, start);
			var separator = before && !/\s$/.test(before) ? " " : "";
			input.value = before + separator + event.text + input.value.slice(input.selectionEnd);
			input.selectionStart = input.selectionEnd = start + separator.length + event.text.length;
			resize();
			input.focus();
			break;
		default:
			break;
		}
	};

	window.chatState = function () {
		return JSON.stringify({ turns: turns, tools: Object.keys(tools).length, requests: Object.keys(requests).length,
			text: log.innerText, input: input.value });
	};
})();

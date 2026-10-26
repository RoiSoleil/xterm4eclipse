/*
 * The Markdown of the messages of Claude Code, as HTML. All the text is escaped before any tag is
 * added, so that a message never adds markup of its own; links keep their address in a data
 * attribute, which the page opens through Java (http and https only).
 */
(function () {
	"use strict";

	function escape(text) {
		return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
			.replace(/"/g, "&quot;").replace(/'/g, "&#39;");
	}

	/** A path of a file, maybe with its line: src/Foo.java, ./a.txt:12, /home/me/b.md#L3. */
	var FILE = /^(?:[A-Za-z]:[\\/]|[.~]?[\\/])?(?:[\w.@+-]+[\\/])*[\w@+-][\w.@+-]*\.[A-Za-z0-9]{1,10}(?:(?::|#L)(\d+)(?:[-:]\d+)?)?$/;

	/** @return the file and line of a text that is a path, or null */
	function fileReference(text) {
		var match = FILE.exec(text);
		if (!match || /^\d+(\.\d+)+$/.test(text)) {
			return null;
		}
		var path = text.replace(/(?::|#L)\d+(?:[-:]\d+)?$/, "");
		return { path: path, line: match[1] ? parseInt(match[1], 10) : 0 };
	}

	function fileLink(text, shown) {
		var reference = fileReference(text);
		if (!reference) {
			return null;
		}
		return "<a class=\"file\" data-path=\"" + escape(reference.path) + "\" data-line=\"" + reference.line
			+ "\" title=\"Open in Eclipse\">" + shown + "</a>";
	}

	/** The inline Markdown of a line: code, emphasis, links. */
	function inline(text) {
		var out = "";
		var code = /(`+)([\s\S]*?[^`])\1(?!`)/g;
		var position = 0;
		var match;
		while ((match = code.exec(text)) !== null) {
			out += emphasis(text.slice(position, match.index));
			var content = match[2].replace(/^ (.*) $/, "$1");
			var shown = "<code>" + escape(content) + "</code>";
			out += fileLink(content.trim(), shown) || shown;
			position = code.lastIndex;
		}
		return out + emphasis(text.slice(position));
	}

	function emphasis(text) {
		var links = [];
		// Links first, kept aside: their address must not be read as emphasis.
		var html = text.replace(/\[([^\]\n]+)\]\(\s*(https?:\/\/[^\s)]+)\s*\)/g, function (all, label, url) {
			links.push("<a class=\"link\" data-href=\"" + escape(url) + "\" title=\"" + escape(url) + "\">" + escape(label) + "</a>");
			return "\u0000" + (links.length - 1) + "\u0000";
		}).replace(/https?:\/\/[^\s<>()\[\]]*[^\s<>()\[\].,;:!?'"]/g, function (url) {
			links.push("<a class=\"link\" data-href=\"" + escape(url) + "\" title=\"" + escape(url) + "\">" + escape(url) + "</a>");
			return "\u0000" + (links.length - 1) + "\u0000";
		});
		html = escape(html)
			.replace(/\*\*(?=\S)([\s\S]*?\S)\*\*/g, "<strong>$1</strong>")
			.replace(/(^|[^\w])__(?=\S)([\s\S]*?\S)__(?![\w])/g, "$1<strong>$2</strong>")
			.replace(/(^|[^\w*])\*(?=[^\s*])([^*\n]*?[^\s*])\*(?![\w*])/g, "$1<em>$2</em>")
			.replace(/(^|[^\w])_(?=[^\s_])([^_\n]*?[^\s_])_(?![\w])/g, "$1<em>$2</em>")
			.replace(/~~(?=\S)([\s\S]*?\S)~~/g, "<del>$1</del>");
		return html.replace(/\u0000(\d+)\u0000/g, function (all, index) {
			return links[parseInt(index, 10)];
		});
	}

	var FENCE = /^ {0,3}(`{3,}|~{3,})\s*([^`\s]*)[^`]*$/;
	var BULLET = /^( *)([-*+]|\d{1,9}[.)])( +|$)(.*)$/;
	var RULE = /^ {0,3}([-*_])( *\1){2,} *$/;
	var HEADING = /^ {0,3}(#{1,6})(?: +(.*?))?(?: +#+)? *$/;
	var TABLE_RULE = /^ *\|? *:?-+:? *(\| *:?-+:? *)*\|? *$/;

	function isBlockStart(line) {
		return FENCE.test(line) || HEADING.test(line) || RULE.test(line) || /^ {0,3}>/.test(line) || BULLET.test(line);
	}

	function cells(line) {
		return line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map(function (cell) {
			return cell.trim();
		});
	}

	/** @return the HTML of a Markdown text */
	function render(source) {
		var lines = String(source).replace(/\r\n?/g, "\n").split("\n");
		var html = "";
		var i = 0;
		while (i < lines.length) {
			var line = lines[i];
			var match;
			if ((match = FENCE.exec(line)) !== null) {
				var fence = match[1];
				var body = [];
				i++;
				while (i < lines.length && !(lines[i].trim().indexOf(fence) === 0 && lines[i].trim().replace(new RegExp("^\\" + fence[0] + "+"), "") === "")) {
					body.push(lines[i]);
					i++;
				}
				i++;
				html += "<pre class=\"code\"" + (match[2] ? " data-language=\"" + escape(match[2]) + "\"" : "") + "><code>"
					+ escape(body.join("\n")) + "</code></pre>";
				continue;
			}
			if (/^\s*$/.test(line)) {
				i++;
				continue;
			}
			if ((match = HEADING.exec(line)) !== null) {
				var level = match[1].length;
				html += "<h" + level + ">" + inline(match[2] || "") + "</h" + level + ">";
				i++;
				continue;
			}
			if (RULE.test(line)) {
				html += "<hr>";
				i++;
				continue;
			}
			if (/^ {0,3}>/.test(line)) {
				var quoted = [];
				while (i < lines.length && /^ {0,3}>/.test(lines[i])) {
					quoted.push(lines[i].replace(/^ {0,3}> ?/, ""));
					i++;
				}
				html += "<blockquote>" + render(quoted.join("\n")) + "</blockquote>";
				continue;
			}
			if ((match = BULLET.exec(line)) !== null) {
				var indent = match[1].length;
				var ordered = /\d/.test(match[2]);
				var items = [];
				var item = null;
				while (i < lines.length) {
					var current = lines[i];
					var bullet = BULLET.exec(current);
					if (bullet && bullet[1].length <= indent + 1 && /\d/.test(bullet[2]) === ordered) {
						item = [bullet[4]];
						items.push({ lines: item, start: parseInt(bullet[2], 10) });
					} else if (/^\s*$/.test(current)) {
						// A blank line ends the list unless an item goes on after it.
						var following = lines[i + 1];
						if (following === undefined || !(/^ +\S/.test(following) && following.search(/\S/) > indent)
								&& !(BULLET.test(following) && BULLET.exec(following)[1].length <= indent + 1)) {
							break;
						}
						item.push("");
					} else if (current.search(/\S/) > indent || !isBlockStart(current) && item !== null && current.search(/\S/) >= 0
							&& items.length > 0 && !/^\s*$/.test(lines[i - 1])) {
						item.push(current.replace(new RegExp("^ {0," + (indent + 4) + "}"), ""));
					} else {
						break;
					}
					i++;
				}
				var start = ordered && items[0].start !== 1 && !isNaN(items[0].start) ? " start=\"" + items[0].start + "\"" : "";
				html += ordered ? "<ol" + start + ">" : "<ul>";
				items.forEach(function (entry) {
					// Its first paragraph is the text of the item, before a list in it.
					html += "<li>" + render(entry.lines.join("\n")).replace(/^<p>((?:(?!<\/?p>)[\s\S])*)<\/p>/, "$1") + "</li>";
				});
				html += ordered ? "</ol>" : "</ul>";
				continue;
			}
			if (line.indexOf("|") >= 0 && i + 1 < lines.length && TABLE_RULE.test(lines[i + 1]) && lines[i + 1].indexOf("-") >= 0) {
				var header = cells(line);
				html += "<table><thead><tr>" + header.map(function (cell) {
					return "<th>" + inline(cell) + "</th>";
				}).join("") + "</tr></thead><tbody>";
				i += 2;
				while (i < lines.length && lines[i].indexOf("|") >= 0 && !/^\s*$/.test(lines[i])) {
					html += "<tr>" + cells(lines[i]).map(function (cell) {
						return "<td>" + inline(cell) + "</td>";
					}).join("") + "</tr>";
					i++;
				}
				html += "</tbody></table>";
				continue;
			}
			var paragraph = [];
			while (i < lines.length && !/^\s*$/.test(lines[i]) && (paragraph.length === 0 || !isBlockStart(lines[i]))) {
				paragraph.push(lines[i]);
				i++;
			}
			// The line breaks of Claude Code are kept, as in its terminal.
			html += "<p>" + paragraph.map(function (part) {
				return inline(part.trim());
			}).join("<br>") + "</p>";
		}
		return html;
	}

	window.chatMarkdown = { render: render, inline: inline, escape: escape, fileReference: fileReference };
})();

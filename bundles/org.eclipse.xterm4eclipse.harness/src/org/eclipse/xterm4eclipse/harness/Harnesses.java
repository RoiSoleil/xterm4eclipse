package org.eclipse.xterm4eclipse.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.xterm4eclipse.harness.Harness.Mcp;
import org.eclipse.xterm4eclipse.harness.Harness.Sessions;
import org.eclipse.xterm4eclipse.harness.Harness.Store;
import org.eclipse.xterm4eclipse.spi.CommandLine;

/**
 * The coding agents that the terminals know. Their command lines are those of the versions named
 * with each of them, read from the program itself ({@code --help}, and its commands run for real).
 */
final class Harnesses {

	/** The header that gives the token of the MCP server of Eclipse, as most agents write it. */
	private static final String BEARER = "Authorization: Bearer {token}"; //$NON-NLS-1$
	/** The same header for the agents that take it as {@code name=value}. */
	private static final String BEARER_PAIR = "Authorization=Bearer {token}"; //$NON-NLS-1$

	/** Claude Code 2.1: takes the id of a new conversation, and keeps one file by conversation. */
	static final Harness CLAUDE = new Harness("claude", "Claude", "Claude Code", List.of("claude"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("agents", "attach", "auth", "auto-mode", "doctor", "gateway", "import", "install", "logs", "mcp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"plugin", "purge", "respawn", "rm", "setup-token", "stop", "ultrareview", "update"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			Set.of("-r", "--resume", "-c", "--continue", "--session-id", "-p", "--print", "--from-pr", "--teleport", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					"--fork-session"), //$NON-NLS-1$
			Set.of(),
			Sessions.own(List.of("--session-id", "{id}"), List.of("--resume", "{id}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					// projects/<folder of the conversation>/<id>.jsonl
					files(2, id -> Pattern.quote(id + ".jsonl"), List.of(() -> claudeHome().resolve("projects")))), //$NON-NLS-1$ //$NON-NLS-2$
			new Mcp(List.of("mcp", "add", "--transport", "http", "--scope", "user", "{name}", "{url}", "--header", BEARER), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					List.of("mcp", "remove", "--scope", "user", "{name}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					// In CLAUDE_CONFIG_DIR if it is set, else ~/.claude.json next to the folder ~/.claude.
					List.of(() -> Places.env("CLAUDE_CONFIG_DIR") != null ? claudeHome().resolve(".claude.json") //$NON-NLS-1$ //$NON-NLS-2$
							: claudeHome().resolveSibling(".claude.json")), //$NON-NLS-1$
					List.of(".mcp.json"), false)); //$NON-NLS-1$

	/**
	 * Codex 0.162 of OpenAI: chooses its ids itself and keeps its conversations in a database, so
	 * that only the last one of the folder is resumed. It reads the token of an MCP server in a
	 * variable of the environment.
	 */
	static final Harness CODEX = new Harness("codex", "Codex", "Codex", List.of("codex"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("agents", "exec", "e", "review", "login", "logout", "mcp", "plugin", "app-server", "remote-control", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"completion", "update", "doctor", "sandbox", "debug", "apply", "a", "resume", "queue", "archive", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"delete", "migrate-rollouts", "unarchive", "fork", "cloud", "exec-server", "features", "help"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			Set.of("--remote"), //$NON-NLS-1$
			Set.of("-c", "--config", "--enable", "--disable", "--remote-auth-token-env", "-i", "--image", "-m", "--model", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					"--local-provider", "-p", "--profile", "-s", "--sandbox", "-C", "--cd", "--add-dir", "-a", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					"--ask-for-approval"), //$NON-NLS-1$
			Sessions.last("resume", "--last"), //$NON-NLS-1$ //$NON-NLS-2$
			new Mcp(List.of("mcp", "add", "{name}", "--url", "{url}", "--bearer-token-env-var", "{variable}"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
					List.of(() -> Places.home("CODEX_HOME", ".codex").resolve("config.toml")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of(".codex/config.toml"), true)); //$NON-NLS-1$

	/**
	 * OpenCode 2.0: chooses its ids itself and lists the conversations of a folder, among which the
	 * one of a terminal is found once the user has talked in it.
	 */
	static final Harness OPENCODE = new Harness("opencode", "OpenCode", "OpenCode", List.of("opencode"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("upgrade", "update", "uninstall", "acp", "api", "debug", "auth", "mcp", "plugin", "models", "stats", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
					"mini", "run", "session", "service", "reload", "pair", "serve", "attach", "web", "export", "import", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
					"github", "pr", "agent"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			Set.of("-c", "--continue", "-s", "--session", "--fork", "--server"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			Set.of(),
			new Sessions(null, List.of("--session", "{id}"), List.of("--continue"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of("session", "list", "--format", "json", "-n", "20"), "ses_[A-Za-z0-9]{8,80}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			new Mcp(List.of("mcp", "add", "{name}", "--url", "{url}", "--header", BEARER_PAIR, "--global"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
					List.of(() -> Places.config("opencode").resolve("opencode.json"), //$NON-NLS-1$ //$NON-NLS-2$
							() -> Places.config("opencode").resolve("opencode.jsonc")), //$NON-NLS-1$ //$NON-NLS-2$
					List.of("opencode.json", "opencode.jsonc", ".opencode/opencode.json", ".opencode/opencode.jsonc"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					false));

	/**
	 * Reasonix 2.37, the agent built for the models of DeepSeek: its {@code --continue} resumes the
	 * last conversation of the folder, or starts one when there is none.
	 */
	static final Harness REASONIX = new Harness("reasonix", "Reasonix", "Reasonix", List.of("reasonix"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("tui", "code", "chat", "run", "review", "web", "serve", "acp", "setup", "config", "report", "mcp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$ //$NON-NLS-12$
					"login", "whoami", "logout", "subagent", "init", "doctor", "session", "hook", "trust", "task", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"upgrade", "update", "completion", "version", "help"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			Set.of("-c", "--continue", "-r", "--resume", "-p", "--print", "--acp", "--copy"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			Set.of(),
			Sessions.last("--continue"), //$NON-NLS-1$
			new Mcp(List.of("mcp", "add", "{name}", "--http", "{url}", "--header", BEARER_PAIR), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
					List.of("mcp", "remove", "{name}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of(() -> Places.home("REASONIX_HOME", ".reasonix").resolve("config.toml")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of("reasonix.toml", ".mcp.json"), false)); //$NON-NLS-1$ //$NON-NLS-2$

	/** Gemini CLI 0.63 of Google: takes the id of a new conversation, kept in a file named after it. */
	static final Harness GEMINI = new Harness("gemini", "Gemini", "Gemini CLI", List.of("gemini"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("mcp", "extensions", "skills", "hooks", "gemma"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			Set.of("-r", "--resume", "--session-id", "--session-file", "-p", "--prompt", "--list-sessions", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
					"--delete-session"), //$NON-NLS-1$
			Set.of(),
			Sessions.own(List.of("--session-id", "{id}"), List.of("--resume", "{id}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					// tmp/<project>/chats/session-<time>-<first characters of the id>.jsonl
					files(3, id -> "session-.*-" + Pattern.quote(id.substring(0, 8)) + "\\.jsonl?", //$NON-NLS-1$ //$NON-NLS-2$
							geminiHomes("tmp"))), //$NON-NLS-1$
			new Mcp(List.of("mcp", "add", "-s", "user", "-t", "http", "{name}", "{url}", "-H", BEARER), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					geminiHomes("settings.json"), List.of(".gemini/settings.json"), false)); //$NON-NLS-1$ //$NON-NLS-2$

	/**
	 * GitHub Copilot CLI 1.0: one option starts the conversation of an id, or resumes it if there is
	 * one.
	 */
	static final Harness COPILOT = new Harness("copilot", "Copilot", "GitHub Copilot CLI", List.of("copilot"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("app", "login", "help", "init", "config", "update", "version", "workflow", "sessions", "memories", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"plugin", "mcp", "skill", "instruction", "lsp", "sandbox", "completion"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			Set.of("-r", "--resume", "--continue", "--session-id", "--connect", "-p", "--prompt"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			Set.of(),
			Sessions.own(List.of("--session-id", "{id}"), List.of("--session-id", "{id}"), null), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			new Mcp(List.of("mcp", "add", "--transport", "http", "{name}", "{url}", "--header", BEARER), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
					List.of("mcp", "remove", "{name}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of(() -> Places.home("COPILOT_HOME", ".copilot").resolve("mcp-config.json")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of(".mcp.json", ".github/mcp.json"), false)); //$NON-NLS-1$ //$NON-NLS-2$

	/**
	 * The CLI of Cursor (2026.10), under its name {@code cursor-agent}: {@code agent}, its other
	 * name, could be any program. It has no command to add an MCP server.
	 */
	static final Harness CURSOR = new Harness("cursor", "Cursor Agent", "Cursor Agent", List.of("cursor-agent"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("persist", "install-shell-integration", "uninstall-shell-integration", "login", "logout", "mcp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
					"plugin", "worker", "status", "models", "team", "bedrock", "about", "update", "create-chat", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					"generate-rule", "agent", "ls", "resume", "help"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			Set.of("--resume", "--continue", "-p", "--print"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of(),
			Sessions.last("--continue"), null); //$NON-NLS-1$

	/** Qwen Code 0.25 of Alibaba: takes the id of a new conversation, and keeps one file by conversation. */
	static final Harness QWEN = new Harness("qwen", "Qwen Code", "Qwen Code", List.of("qwen"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("agents", "auth", "batch", "board", "channel", "extensions", "hooks", "mcp", "review", "sandbox", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"serve", "sessions", "update"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			Set.of("-c", "--continue", "-r", "--resume", "--session-id", "--fork-session", "-p", "--prompt"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			Set.of(),
			Sessions.own(List.of("--session-id", "{id}"), List.of("--resume", "{id}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					// projects/<project>/chats/<id>.jsonl
					files(3, id -> Pattern.quote(id + ".jsonl"), //$NON-NLS-1$
							List.of(() -> Places.home("QWEN_HOME", ".qwen").resolve("projects")))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			new Mcp(List.of("mcp", "add", "-s", "user", "-t", "http", "{name}", "{url}", "-H", BEARER), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					List.of(() -> Places.home("QWEN_HOME", ".qwen").resolve("settings.json")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of(".qwen/settings.json"), false)); //$NON-NLS-1$

	/** Kilo Code CLI 7.8: resumes the last conversation of the folder. */
	static final Harness KILO = new Harness("kilo", "Kilo Code", "Kilo Code", List.of("kilo", "kilocode"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			Set.of("completion", "acp", "mcp", "attach", "run", "debug", "auth", "agent", "upgrade", "uninstall", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"serve", "models", "stats", "export", "import", "github", "pr", "session", "plugin", "db", "console", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$ //$NON-NLS-11$
					"cloud", "roll-call", "profile", "remote", "daemon", "config", "worktree", "help"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			Set.of("-c", "--continue", "-s", "--session", "--fork", "--cloud-fork"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			Set.of(),
			Sessions.last("--continue"), //$NON-NLS-1$
			new Mcp(List.of("mcp", "add", "{name}", "--url", "{url}", "--header", BEARER_PAIR), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
					List.of(() -> Places.config("kilo").resolve("kilo.jsonc"), () -> Places.config("kilo").resolve("kilo.json")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
					List.of("kilo.jsonc", "kilo.json", ".kilo/kilo.jsonc", ".kilo/kilo.json"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

	/** Crush 0.98 of Charm: resumes the last conversation of the folder; its MCP servers are only in its files. */
	static final Harness CRUSH = new Harness("crush", "Crush", "Crush", List.of("crush"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("completion", "dirs", "help", "login", "logout", "logs", "models", "projects", "run", "server", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"session", "stats", "update-providers"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			Set.of("-C", "--continue", "-s", "--session", "-H", "--host"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			Set.of(),
			Sessions.last("--continue"), null); //$NON-NLS-1$

	/** Amp 2026.10: its conversations are threads kept on its server, which the terminal does not choose. */
	static final Harness AMP = new Harness("amp", "Amp", "Amp", List.of("amp"), null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("version", "logout", "account", "login", "orb", "portal", "net", "runner", "clone", "git-setup", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"files", "top", "last", "report-bug", "threads", "tools", "plugins", "skill", "permissions", "mcp", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
					"config", "domains", "projects", "apps", "oauth-clients", "secrets", "svc", "usage", "update", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
					"completions"), //$NON-NLS-1$
			Set.of(), Set.of(), Sessions.none(),
			new Mcp(List.of("mcp", "add", "{name}", "--header", BEARER_PAIR, "{url}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					List.of("mcp", "remove", "{name}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					List.of(() -> Places.config("amp").resolve("settings.json")), //$NON-NLS-1$ //$NON-NLS-2$
					List.of(".amp/settings.json"), false)); //$NON-NLS-1$

	/**
	 * goose 1.54 of Block: a conversation has a name, which the terminal chooses. Its conversations
	 * are those of its command {@code session}, which a bare {@code goose} runs too.
	 */
	static final Harness GOOSE = new Harness("goose", "goose", "goose", List.of("goose"), "session", Set.of(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			Set.of("-n", "--name", "--session-id", "--path", "-r", "--resume", "--fork"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
			Set.of("--system", "--max-tool-repetitions", "--max-turns"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			Sessions.own(List.of("--name", "{id}"), List.of("--resume", "--name", "{id}"), null), null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

	/** Aider: one history by folder, which it reads again when asked to. It knows no MCP server. */
	static final Harness AIDER = new Harness("aider", "Aider", "Aider", List.of("aider"), null, Set.of(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			Set.of("--restore-chat-history", "--no-restore-chat-history", "-m", "--message", "--msg", "-f", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
					"--message-file"), //$NON-NLS-1$
			Set.of(),
			Sessions.last("--restore-chat-history"), null); //$NON-NLS-1$

	/** In the order of the menus. */
	static final List<Harness> ALL = List.of(CLAUDE, CODEX, OPENCODE, REASONIX, GEMINI, COPILOT, CURSOR, QWEN, KILO,
			CRUSH, AMP, GOOSE, AIDER);

	private Harnesses() {
	}

	/** A coding agent in a terminal: the agent, and how the command line of the terminal runs it. */
	record Found(Harness harness, Invocation invocation) {
	}

	/** @return the agent whose program has this name, or {@code null} */
	static Harness named(String program) {
		for (Harness harness : ALL) {
			if (harness.programs().contains(program)) {
				return harness;
			}
		}
		return null;
	}

	/**
	 * @param commandLine
	 *            the command line of a terminal, parsed
	 * @return the agent that it runs, or {@code null} if it runs none, or not plainly
	 */
	static Found find(String[] commandLine) {
		Invocation invocation = Invocation.of(commandLine, program -> named(program) != null);
		return invocation == null ? null : new Found(named(invocation.name()), invocation);
	}

	static Found find(String commandLine) {
		return find(CommandLine.parse(commandLine));
	}

	private static Path claudeHome() {
		return Places.home("CLAUDE_CONFIG_DIR", ".claude"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * A file of Gemini CLI: in ~/.gemini, or where GEMINI_CLI_HOME says, which is this folder for a
	 * part of the program and the home that holds it for the other.
	 */
	private static List<Supplier<Path>> geminiHomes(String file) {
		return List.of(() -> Places.userHome.resolve(".gemini").resolve(file), //$NON-NLS-1$
				() -> Places.home("GEMINI_CLI_HOME", ".gemini").resolve(file), //$NON-NLS-1$ //$NON-NLS-2$
				() -> Places.home("GEMINI_CLI_HOME", "").resolve(".gemini").resolve(file)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/**
	 * @param depth
	 *            how many folders down the files of the conversations are
	 * @param name
	 *            gives the regular expression of the name of the file of a conversation
	 * @param folders
	 *            where the agent keeps its conversations
	 * @return the conversations kept as files
	 */
	static Store files(int depth, Function<String, String> name, List<Supplier<Path>> folders) {
		return id -> {
			Pattern file = Pattern.compile(name.apply(id));
			for (Supplier<Path> folder : folders) {
				try (Stream<Path> files = Files.walk(folder.get(), depth)) {
					if (files.anyMatch(path -> file.matcher(path.getFileName().toString()).matches())) {
						return true;
					}
				} catch (IOException | RuntimeException e) {
					// No such folder: no conversation.
				}
			}
			return false;
		};
	}
}

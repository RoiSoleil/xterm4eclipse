package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellProfilesTest {

	@TempDir
	Path temp;

	@AfterEach
	void resetDefault() {
		XtermPreferencePageTest.resetPreferences();
	}

	private static ShellProfiles.Host host(String os, Map<String, String> env, Path shells) {
		return new ShellProfiles.Host(os, env::get, shells);
	}

	@Test
	void parseSplitsOnSpacesAndHonoursQuotes() {
		assertArrayEquals(new String[] {"/bin/bash", "-l"}, ShellProfiles.parse("  /bin/bash   -l "));
		assertArrayEquals(new String[] {"C:\\Program Files\\Git\\bin\\bash.exe", "--login"},
				ShellProfiles.parse("\"C:\\Program Files\\Git\\bin\\bash.exe\" --login"));
		assertArrayEquals(new String[] {"sh", "-c", ""}, ShellProfiles.parse("sh -c \"\""));
		assertEquals(0, ShellProfiles.parse("   ").length);
	}

	@Test
	void shellsAreToldApartFromPrograms() {
		assertTrue(ShellProfiles.isShell("/bin/bash --norc"));
		assertTrue(ShellProfiles.isShell("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" -NoLogo"));
		assertTrue(ShellProfiles.isShell("C:\\Windows\\System32\\cmd.exe"));
		assertFalse(ShellProfiles.isShell("/bin/zsh -l -i -c claude"));
		assertFalse(ShellProfiles.isShell("\"C:\\Users\\me\\.local\\bin\\claude.exe\""));
		assertFalse(ShellProfiles.isShell("htop"));
	}

	@Test
	void displayNameIsTheExecutableName() {
		assertEquals("zsh", ShellProfiles.displayName("/usr/bin/zsh -l"));
		assertEquals("pwsh", ShellProfiles.displayName("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\""));
		assertEquals("Xterm", ShellProfiles.displayName(""));
		assertEquals("claude", ShellProfiles.displayName("/bin/zsh -l -i -c claude"));
		assertEquals("claude", ShellProfiles.displayName("cmd.exe /C \"C:\\npm\\claude.cmd\""));
		assertEquals("claude", ShellProfiles.displayName("bash -c \"'/my dir/claude' --resume\""));
		assertEquals("vim", ShellProfiles.displayName("bash -c \"vim notes.txt\""));
		assertEquals("bash", ShellProfiles.displayName("bash -c \"'unterminated\""));
	}

	@Test
	void unixShellsComeFromTheUserShellAndEtcShells() throws Exception {
		Path zsh = executable(temp.resolve("usr/bin/zsh"));
		Path duplicate = executable(temp.resolve("bin/zsh"));
		Path fish = executable(temp.resolve("with space/fish"));
		Path shells = temp.resolve("shells");
		Files.write(shells, List.of("# comment", zsh.toString(), duplicate.toString(), temp.resolve("missing").toString()));

		List<ShellProfiles.Profile> profiles = ShellProfiles
				.detect(host("Linux", Map.of("SHELL", fish.toString()), shells));

		assertEquals("fish", profiles.get(0).name());
		assertEquals('"' + fish.toString() + '"', profiles.get(0).commandLine());
		assertEquals(zsh.toString(), profiles.get(1).commandLine());
		assertEquals(1, profiles.stream().filter(profile -> profile.name().equals("zsh")).count());
	}

	@Test
	void missingEtcShellsStillOffersBash() {
		List<ShellProfiles.Profile> profiles = ShellProfiles
				.detect(host("Linux", Map.of(), temp.resolve("does-not-exist")));
		assertEquals(new File("/bin/bash").canExecute() ? 1 : 0, profiles.size());
	}

	@Test
	void macShellsAreLoginShells() throws Exception {
		Path zsh = executable(temp.resolve("zsh"));
		ShellProfiles.Host mac = host("Mac OS X", Map.of("SHELL", zsh.toString()), temp.resolve("none"));
		assertEquals(zsh + " -l", ShellProfiles.detect(mac).get(0).commandLine());
		assertEquals(zsh + " -l", ShellProfiles.defaultCommandLine(mac));
	}

	@Test
	void windowsShellsComeFromThePathAndGitInstallations() throws Exception {
		Path system = temp.resolve("System32");
		Path powershell = temp.resolve("Power Shell");
		Files.createFile(Files.createDirectories(system).resolve("cmd.exe"));
		Files.createFile(system.resolve("wsl.exe"));
		Files.createFile(Files.createDirectories(powershell).resolve("pwsh.exe"));
		Path programFiles = temp.resolve("Program Files");
		Files.createFile(Files.createDirectories(programFiles.resolve("Git/bin")).resolve("bash.exe"));
		Map<String, String> env = new HashMap<>();
		env.put("PATH", system + ";" + powershell + ";" + temp.resolve("absent"));
		env.put("ProgramFiles", programFiles.toString());
		env.put("LocalAppData", temp.resolve("AppData").toString());

		List<ShellProfiles.Profile> profiles = ShellProfiles.detect(host("Windows 11", env, null));

		assertEquals(List.of("PowerShell", "Command Prompt", "Git Bash", "WSL"),
				profiles.stream().map(ShellProfiles.Profile::name).toList());
		assertTrue(profiles.get(0).commandLine().startsWith("\""), "paths with spaces are quoted");
		assertTrue(profiles.get(2).commandLine().endsWith("--login -i"));
		String[] gitBash = ShellProfiles.parse(profiles.get(2).commandLine());
		assertTrue(new File(gitBash[0]).isFile());
	}

	@Test
	void gitBashIsFoundNextToTheGitOfThePathWhereverItIsInstalled() throws Exception {
		for (String onPath : new String[] {"cmd", "bin", "mingw64/bin"}) {
			Path root = temp.resolve("custom " + onPath.replace('/', '-') + "/Git");
			Files.createFile(Files.createDirectories(root.resolve("bin")).resolve("bash.exe"));
			Path pathEntry = Files.createDirectories(root.resolve(onPath));
			Files.createFile(pathEntry.resolve("git.exe"));

			List<ShellProfiles.Profile> profiles = ShellProfiles
					.detect(host("Windows 11", Map.of("PATH", temp.resolve("other") + ";" + pathEntry), null));

			assertEquals(List.of("Git Bash"), profiles.stream().map(ShellProfiles.Profile::name).toList(), onPath);
			assertEquals(root.resolve("bin").resolve("bash.exe").toFile(),
					new File(ShellProfiles.parse(profiles.get(0).commandLine())[0]));
		}
	}

	@Test
	void gitWithoutBashIsNotOffered() throws Exception {
		Path cmd = Files.createDirectories(temp.resolve("Git/cmd"));
		Files.createFile(cmd.resolve("git.exe"));
		assertTrue(ShellProfiles.detect(host("Windows 11", Map.of("PATH", cmd.toString()), null)).isEmpty());
	}

	@Test
	void everyShellHasAnIconThatExists() throws Exception {
		Map<String, String> expected = new HashMap<>();
		expected.put("/usr/bin/bash", "bash");
		expected.put("/bin/zsh -l", "zsh");
		expected.put("/usr/bin/fish", "fish");
		expected.put("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\"", "powershell");
		expected.put("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "powershell");
		expected.put("C:\\Windows\\System32\\cmd.exe", "cmd");
		expected.put("C:\\Windows\\System32\\wsl.exe", "linux");
		expected.put("/bin/dash", "shell");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			String icon = new ShellProfiles.Profile("any", entry.getKey()).icon();
			assertEquals("icons/shells/" + entry.getValue() + ".png", icon);
			assertTrue(XtermPlugin.class.getResource("/" + icon) != null, icon);
		}
		String gitBash = new ShellProfiles.Profile("Git Bash", "C:\\Git\\bin\\bash.exe --login -i").icon();
		assertEquals("icons/shells/gitbash.png", gitBash);
		assertTrue(XtermPlugin.class.getResource("/" + gitBash) != null);
	}

	@Test
	void claudeIsOfferedOnlyWhenItIsOnThePath() throws Exception {
		Path bin = executable(temp.resolve("bin/claude")).getParent();
		Path shells = temp.resolve("none");
		List<ShellProfiles.Profile> unix = ShellProfiles
				.detect(host("Linux", Map.of("PATH", temp.resolve("absent") + ":" + bin, "SHELL", "/bin/zsh"), shells));
		ShellProfiles.Profile claude = unix.get(unix.size() - 1);
		assertEquals("Claude", claude.name());
		assertEquals("/bin/zsh -l -i -c claude", claude.commandLine(), "through the login shell of the user");
		assertEquals("icons/shells/claude.png", claude.icon());
		assertTrue(XtermPlugin.class.getResource("/" + claude.icon()) != null);
		assertEquals("/bin/bash -l -i -c claude",
				ShellProfiles.detect(host("Linux", Map.of("PATH", bin.toString()), shells)).stream()
						.filter(profile -> profile.name().equals("Claude")).findFirst().orElseThrow().commandLine());
		assertTrue(ShellProfiles.detect(host("Linux", Map.of("HOME", temp.toString()), shells)).stream()
				.noneMatch(profile -> profile.name().equals("Claude")), "not on the PATH: not offered");

		Path native_ = Files.createDirectories(temp.resolve("local bin"));
		Files.createFile(native_.resolve("claude.exe"));
		List<ShellProfiles.Profile> windows = ShellProfiles.detect(host("Windows 11", Map.of("PATH", native_.toString()), null));
		assertEquals(List.of("Claude"), windows.stream().map(ShellProfiles.Profile::name).toList());
		assertEquals('"' + native_.resolve("claude.exe").toString() + '"', windows.get(0).commandLine());

		Path npm = Files.createDirectories(temp.resolve("npm"));
		Files.createFile(npm.resolve("claude.cmd"));
		ShellProfiles.Profile cmd = ShellProfiles.detect(host("Windows 11", Map.of("PATH", npm.toString()), null)).get(0);
		assertEquals("cmd.exe /c " + npm.resolve("claude.cmd"), cmd.commandLine());
		assertEquals("Claude", cmd.name());
		assertEquals("claude", ShellProfiles.displayName(cmd.commandLine()));
	}

	@Test
	void windowsWithoutPathFindsNothing() {
		assertTrue(ShellProfiles.detect(host("Windows 11", Map.of(), null)).isEmpty());
	}

	@Test
	void defaultIsThePlatformShellUntilTheUserChoosesOne() {
		assertEquals("/bin/zsh", ShellProfiles.defaultCommandLine(host("Linux", Map.of("SHELL", "/bin/zsh"), null)));
		assertEquals("/bin/bash", ShellProfiles.defaultCommandLine(host("Linux", Map.of(), null)));
		assertEquals("cmd.exe", ShellProfiles.defaultCommandLine(host("Windows 11", Map.of(), null)));
		assertEquals("\"C:\\Win dows\\cmd.exe\"",
				ShellProfiles.defaultCommandLine(host("Windows 11", Map.of("COMSPEC", "C:\\Win dows\\cmd.exe"), null)));

		ShellProfiles.setDefaultCommandLine("  /usr/bin/fish -l ");
		assertEquals("/usr/bin/fish -l", ShellProfiles.defaultCommandLine(host("Windows 11", Map.of(), null)));
		assertEquals("/usr/bin/fish -l", ShellProfiles.defaultCommandLine());
	}

	@Test
	void currentHostIsDetected() {
		ShellProfiles.Host current = ShellProfiles.Host.current();
		assertEquals(System.getProperty("os.name").toLowerCase().contains("win"), current.isWindows());
		assertFalse(ShellProfiles.detect().isEmpty());
	}

	private static Path executable(Path file) throws Exception {
		Files.createDirectories(file.getParent());
		Files.createFile(file);
		assertTrue(file.toFile().setExecutable(true));
		return file;
	}
}

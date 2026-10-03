#!/usr/bin/env node
// Copies xterm.js and its addons, at the versions of package-lock.json, to the web folder of the
// bundle.
//
//   node xterm/update.mjs            copies the locked versions
//   node xterm/update.mjs --check    fails if the web folder differs from the locked versions
//   node xterm/update.mjs --latest   moves to the latest stable versions first, published at
//                                    least COOLDOWN_DAYS ago, and prints what changed
//
// npm checks every package against the integrity hash of the lock; the scripts of the packages
// never run.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.join(HERE, '..', 'bundles', 'org.eclipse.xterm4eclipse', 'web');
/** A release is taken only once it has been out that long: a compromised one is usually pulled by then. */
const COOLDOWN_DAYS = 7;
/** The files of each package that the bundle uses, and their names in the web folder. */
const FILES = {
	'@xterm/xterm': { 'lib/xterm.js': 'xterm.js', 'css/xterm.css': 'xterm.css', LICENSE: 'LICENSE-xterm.txt' },
	'@xterm/addon-fit': { 'lib/addon-fit.js': 'addon-fit.js' },
	'@xterm/addon-search': { 'lib/addon-search.js': 'addon-search.js' },
	'@xterm/addon-serialize': { 'lib/addon-serialize.js': 'addon-serialize.js' },
	'@xterm/addon-unicode11': { 'lib/addon-unicode11.js': 'addon-unicode11.js' },
	'@xterm/addon-web-links': { 'lib/addon-web-links.js': 'addon-web-links.js' },
};
const NPM_FLAGS = ['--ignore-scripts', '--no-audit', '--no-fund'];

function npm(...args) {
	return execFileSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', args, {
		cwd: HERE,
		encoding: 'utf8',
		stdio: ['ignore', 'pipe', 'inherit'],
		shell: process.platform === 'win32',
	});
}

function lockedVersions() {
	return JSON.parse(fs.readFileSync(path.join(HERE, 'package.json'), 'utf8')).dependencies;
}

const STABLE = /^(\d+)\.(\d+)\.(\d+)$/;

function compare(a, b) {
	const x = STABLE.exec(a).slice(1).map(Number);
	const y = STABLE.exec(b).slice(1).map(Number);
	return x[0] - y[0] || x[1] - y[1] || x[2] - y[2];
}

/** The newest stable version of the package out for COOLDOWN_DAYS, never older than the current one. */
function latest(name, current) {
	const info = JSON.parse(npm('view', name, 'time', 'dist-tags', '--json'));
	const tagged = info['dist-tags'].latest;
	const before = Date.now() - COOLDOWN_DAYS * 24 * 3600 * 1000;
	let best = current;
	for (const [version, date] of Object.entries(info.time)) {
		if (STABLE.test(version) && Date.parse(date) <= before && compare(version, tagged) <= 0
				&& compare(version, best) > 0) {
			best = version;
		}
	}
	return best;
}

function update() {
	const current = lockedVersions();
	const changes = [];
	for (const name of Object.keys(FILES)) {
		const version = latest(name, current[name]);
		if (version !== current[name]) {
			changes.push({ name, from: current[name], to: version });
		}
	}
	if (changes.length > 0) {
		npm('install', '--save-exact', ...NPM_FLAGS, ...changes.map(change => `${change.name}@${change.to}`));
	}
	for (const { name, from, to } of changes) {
		const major = STABLE.exec(from)[1] !== STABLE.exec(to)[1] ? ' (major version: check the breaking changes)' : '';
		console.log(`- ${name}: ${from} → ${to}${major}`);
	}
}

/** Calls back with the source in node_modules and the target in the web folder of every file. */
function eachFile(callback) {
	for (const [name, files] of Object.entries(FILES)) {
		for (const [source, target] of Object.entries(files)) {
			callback(path.join(HERE, 'node_modules', ...name.split('/'), source), path.join(WEB, target));
		}
	}
}

const mode = process.argv[2];
if (mode !== undefined && mode !== '--latest' && mode !== '--check') {
	console.error('Usage: node xterm/update.mjs [--latest | --check]');
	process.exit(2);
}
if (mode === '--latest') {
	update();
}
npm('ci', ...NPM_FLAGS);
if (mode === '--check') {
	const different = [];
	eachFile((source, target) => {
		if (!fs.existsSync(target) || !fs.readFileSync(source).equals(fs.readFileSync(target))) {
			different.push(path.basename(target));
		}
	});
	if (different.length > 0) {
		console.error(`Not the versions of xterm/package-lock.json: ${different.join(', ')}. Run node xterm/update.mjs`);
		process.exit(1);
	}
} else {
	eachFile((source, target) => fs.copyFileSync(source, target));
}

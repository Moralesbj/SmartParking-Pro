// Runs Vitest from a working directory with an uppercase drive letter. On Windows, some terminals start in "c:\…",
// and Vitest 4 then loads two copies of its runner ("failed to find the current suite"). Elsewhere it changes nothing.
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

const upperDrive = path => (process.platform === 'win32' ? path.replace(/^[a-z]:/, d => d.toUpperCase()) : path);
const cwd = upperDrive(process.cwd());
// The binary path must use the same casing as the working directory, or the two copies appear again.
const bin = upperDrive(join(dirname(createRequire(import.meta.url).resolve('vitest/package.json')), 'vitest.mjs'));
const result = spawnSync(process.execPath, [bin, ...process.argv.slice(2)], { cwd, stdio: 'inherit' });
process.exit(result.status ?? 1);

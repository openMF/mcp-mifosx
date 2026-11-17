// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// Lays the installed adaptors out the way the worker's runtime looks for them
// (WORKER_REPO_DIR): node_modules/<package>_<version>, here a link to the
// package npm installed, and a package.json listing those names. A job's
// adaptor found there is used as it is; the worker installs nothing.
//   node link-adaptors.js <repo>
const fs = require('node:fs');
const path = require('node:path');

const repo = process.argv[2];
const pkg = JSON.parse(fs.readFileSync(path.join(repo, 'package.json'), 'utf8'));
const aliases = {};
for (const [name, version] of Object.entries(pkg.dependencies)) {
  if (!/^\d+\.\d+\.\d+$/.test(version)) {
    throw new Error(`${name}: ${version} is not an exact version`);
  }
  const installed = JSON.parse(fs.readFileSync(path.join(repo, 'node_modules', name, 'package.json'), 'utf8')).version;
  if (installed !== version) {
    throw new Error(`${name}: ${installed} is installed, package.json asks for ${version}`);
  }
  const alias = `${name}_${version}`;
  fs.symlinkSync(path.basename(name), path.join(repo, 'node_modules', alias));
  require(path.join(repo, 'node_modules', alias));
  aliases[alias] = `npm:${name}@${version}`;
  console.log(`${alias} -> ${name}@${installed}`);
}
fs.writeFileSync(path.join(repo, 'package.json'), `${JSON.stringify({ ...pkg, dependencies: aliases }, null, 2)}\n`);

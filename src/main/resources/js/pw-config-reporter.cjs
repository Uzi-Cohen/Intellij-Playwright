/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
// Playwright Toolkit: prints the resolved projects of a config as one JSON line.
// Used with `playwright test --list --reporter=<this file>`. FullProject.use is the merged
// config.use + project.use + CLI overrides (packages/playwright/src/common/config.ts).
'use strict';

const MARKER = 'PW_TOOLKIT_PROJECTS ';

class PwToolkitConfigReporter {
  printsToStdio() {
    return true;
  }

  onBegin(config) {
    const projects = (config.projects || []).map(project => {
      const use = project.use || {};
      return {
        name: project.name || '',
        testDir: project.testDir,
        outputDir: project.outputDir,
        testIdAttribute: typeof use.testIdAttribute === 'string' ? use.testIdAttribute : null,
      };
    });
    process.stdout.write(MARKER + JSON.stringify({ projects }) + '\n');
  }

  onError(error) {
    process.stderr.write(MARKER + 'error ' + (error && error.message ? error.message : String(error)) + '\n');
  }
}

module.exports = PwToolkitConfigReporter;

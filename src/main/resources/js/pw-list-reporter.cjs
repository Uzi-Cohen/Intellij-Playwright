/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
// Playwright Toolkit: prints every listed test of a config as one JSON line, for the Tests tab.
// Used with `playwright test --list --reporter=<this file> -c <config>`.
// The suite tree is root -> project -> file -> describe* -> test (packages/playwright/types/testReporter.d.ts).
// Anonymous describes are flattened, as TestCase.titlePath() does (packages/playwright/src/common/test.ts).
'use strict';

const MARKER = 'PW_TOOLKIT_TESTS ';
const ERROR_MARKER = 'PW_TOOLKIT_TESTS_ERROR ';

function locationOf(entry) {
  const location = entry && entry.location;
  return {
    file: location && location.file ? location.file : '',
    line: location && location.line ? location.line : 0,
    column: location && location.column ? location.column : 0,
  };
}

function entriesOf(suite) {
  if (typeof suite.entries === 'function')
    return suite.entries();
  return [...(suite.suites || []), ...(suite.tests || [])];
}

function visit(suite, file, projectName, titles, describes, out) {
  for (const entry of entriesOf(suite)) {
    if (entry.type === 'test') {
      const location = locationOf(entry);
      out.push({
        id: typeof entry.id === 'string' ? entry.id : null,
        projectName,
        file: location.file || file,
        line: location.line,
        column: location.column,
        titlePath: [...titles, entry.title],
        describes,
      });
      continue;
    }
    // A describe suite.
    if (entry.title) {
      const location = locationOf(entry);
      visit(entry, file, projectName, [...titles, entry.title],
          [...describes, { title: entry.title, line: location.line, column: location.column }], out);
    } else {
      visit(entry, file, projectName, titles, describes, out);
    }
  }
}

class PwToolkitListReporter {
  printsToStdio() {
    return true;
  }

  onBegin(config, rootSuite) {
    const projects = (config.projects || []).map(project => ({
      name: project.name || '',
      testDir: project.testDir || '',
      outputDir: project.outputDir || '',
    }));
    const tests = [];
    for (const projectSuite of rootSuite.suites || []) {
      const project = typeof projectSuite.project === 'function' ? projectSuite.project() : undefined;
      const projectName = project ? (project.name || '') : (projectSuite.title || '');
      for (const fileSuite of projectSuite.suites || []) {
        const file = locationOf(fileSuite).file;
        visit(fileSuite, file, projectName, [], [], tests);
      }
    }
    process.stdout.write(MARKER + JSON.stringify({ projects, tests }) + '\n');
  }

  onError(error) {
    const location = error && error.location ? error.location : null;
    const message = error && error.message ? error.message : String(error);
    process.stdout.write(ERROR_MARKER + JSON.stringify({ message, location }) + '\n');
  }
}

module.exports = PwToolkitListReporter;

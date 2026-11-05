/* Copyright 2026 Uzi Cohen. Licensed under the Apache License, Version 2.0. */
package dev.uzi.pw.reports

/**
 * Reporters the setup wizard can add to `playwright.config.*`.
 *
 * Option names are from the sources:
 * - html / junit: microsoft/playwright packages/playwright/types/test.d.ts (`HtmlReporterOptions.outputFolder/open`,
 *   `JUnitReporterOptions.outputFile`). The HTML reporter defaults to `playwright-report` next to the config and
 *   `open: 'on-failure'`, which would pop a browser after failing IDE runs, so the wizard sets `open: 'never'`.
 * - allure-playwright 3.14.0: option `resultsDir`, default `./allure-results` (allure-js-commons
 *   sdk/reporter/utils.js), relative to the test process's working directory.
 */
enum class ReporterKind(
    /** The reporter name as written in the config's `reporter` array. */
    val configName: String,
    /** The array entry the wizard writes. */
    val entry: String,
    /** Dev dependencies to install; empty for built-in reporters. */
    val packages: List<String>,
    /** Lines for `.gitignore`. */
    val gitignore: List<String>,
) {
    HTML(
        configName = "html",
        entry = "['html', { open: 'never' }]",
        packages = emptyList(),
        gitignore = listOf("/playwright-report/"),
    ),
    ALLURE(
        configName = "allure-playwright",
        entry = "['allure-playwright', { resultsDir: 'allure-results' }]",
        packages = listOf("allure-playwright", "allure"),
        gitignore = listOf("/allure-results/", "/allure-report/"),
    ),
    JUNIT(
        configName = "junit",
        entry = "['junit', { outputFile: 'junit-results.xml' }]",
        packages = emptyList(),
        gitignore = listOf("/junit-results.xml"),
    );

    companion object {
        fun byConfigName(name: String): ReporterKind? = entries.firstOrNull { it.configName == name }
    }
}

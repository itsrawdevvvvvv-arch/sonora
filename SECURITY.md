# Security Policy

## Supported versions

The latest release receives security fixes.

| Version | Supported |
| ------- | --------- |
| 1.0.x   | ✅        |

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Instead, report privately via GitHub's [Security Advisories](https://github.com/itsrawdevvvvvv-arch/sonora/security/advisories/new)
("Report a vulnerability"). Include a description, steps to reproduce, and any
relevant details.

We aim to acknowledge reports within a few days and will credit reporters in the
release notes unless you prefer to stay anonymous.

## Scope

Sonora is a fully offline app with no network permission. The most relevant
surface is the `WebView` host in `app/src/main/java/com/sonora/app/MainActivity.java`
and the bundled web assets.

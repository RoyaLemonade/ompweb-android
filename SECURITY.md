# Security policy

## Reporting a vulnerability

Do **not** open a public issue for a security problem.

Use GitHub's private vulnerability reporting on this repository:
**Security → Advisories → Report a vulnerability**
(<https://github.com/RoyaLemonade/ompweb-android/security/advisories/new>).
The report stays private between you and the maintainers until an advisory is
published.

Please include:

- what is affected (app version from **Settings → About**, Android version),
- the steps to reproduce, and what an attacker gains,
- logs or a proof of concept, with secrets and personal data removed.

## What to expect

- An acknowledgement as soon as possible, usually within a few days.
- A fix released as a new signed APK (`android-v<version>`) and, where the
  issue is in the server or the protocol, a pointer to the matching upstream
  change in [ompweb](https://github.com/kahme247/ompweb).
- Credit in the release notes if you want it.

## Scope

In scope: this app (Kotlin/Compose code in `android/`), its connection handling
(password, session cookie, origin policy, plain-HTTP rules), the self-update
path (download, checksum, package and certificate verification) and the release
pipeline in `.github/workflows/`.

Out of scope: vulnerabilities in the ompweb server or omp itself (report them
to [ompweb](https://github.com/kahme247/ompweb)), and issues that require a
compromised device or a modified APK.

## Verifying a release

Every release publishes the APK's SHA-256 and the signing certificate SHA-256
`d73354b52dc7c3f0c74623c439ddb7b52e252811c6e6b5f851c9d04b7015fc87`. See the
[README](README.md#verify-the-apk) for the verification commands. An APK signed
with a different key was not built by this project.

# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The options are checked before anything is built, and a failure says what the option was and what it should be. The main
  namespace is looked for in the sources, and a build fails naming it when it is not there.
- A check after the compilation that the main namespace has a main class, which it lacks without `(:gen-class)`: the uberjar would
  not start.
- `build-plan`, the part that checks, which returns what is going to be built.
- The signatures of signed dependencies are left out of the uberjar, which the JVM refuses with `Invalid signature file digest`.
  `:exclude` adds more patterns.
- `:aliases`, to build with the aliases of `deps.edn`, `:compile-opts`, such as `{:direct-linking true}`, `:target-dir`, and
  `:version`, which takes the place of `APP_VERSION`.
- `uber` returns where the jar is, its version and its size, and says so.
- A test suite that builds an uberjar of a small project, runs it, and reads its manifest and entries.

### Changed

- A resource directory that does not exist is left out of the copy.
- Moves to tools.build 0.10.14, from Maven Central, where it was a git dependency at 0.10.5.
- The published library is named `io.github.af2b/borba-build-component`.

## [0.1.2] - 2026-03-14

The generic `uber` and `clean`, driven by the `:exec-args` of the project.

[Unreleased]: https://github.com/AF2B/borba-build-component/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/AF2B/borba-build-component/releases/tag/v0.1.2

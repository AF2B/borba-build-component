# borba-build-component

[![CI](https://github.com/AF2B/borba-build-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-build-component/actions/workflows/ci.yml)

A generic uberjar build for a Borba service, driven by the `:exec-args` of the `deps.edn` of the service, so that no service carries a
`build.clj` of its own. The options are checked before anything is built, and a build that would make an uberjar that does not start
fails saying why.

## Install

The service adds a `:build` alias to its `deps.edn`:

```clojure
:aliases
{:build
 {:deps       {io.github.af2b/borba-build-component
               {:git/url "https://github.com/AF2B/borba-build-component"
                :git/tag "v1.0.0"
                :git/sha "<the commit of the tag, printed in the release notes>"}}
  :ns-default borba.build
  :exec-args  {:lib     com.example/payments
               :main-ns com.example.payments.main}}}
```

It depends on Clojure and [tools.build](https://github.com/clojure/tools.build) 0.10.14.

## Use

```bash
APP_VERSION=1.4.2 clojure -T:build uber
# built target/app.jar, version 1.4.2, 4.8 MB

java -jar target/app.jar
```

`uber` copies the sources and the resources of the service, compiles it, and makes `target/app.jar` with everything it depends on. The
version goes in the manifest, as `Implementation-Version`, with the name of the library as `Implementation-Title`; it is `APP_VERSION`
when that is set, and `dev` otherwise. `clojure -T:build clean` deletes `target`.

`uber` returns where the jar is, its version and its size:

```clojure
(borba.build/uber {:lib 'com.example/demo :main-ns 'demo.main :version "1.4.2"})
;; => {:uber-file "target/app.jar", :version "1.4.2", :size-bytes 5019451}
```

The main namespace needs `(:gen-class)` and a `-main`:

```clojure
(ns com.example.payments.main
  (:gen-class)
  (:require [borba.core.main :as core]))

(defn -main [& args] (apply core/-main args))
```

| Option | What it is | Default |
|---|---|---|
| `:lib` | The qualified symbol of the service, such as `com.example/payments` | required |
| `:main-ns` | The namespace that has `-main` and `(:gen-class)` | required |
| `:uber-file` | The uberjar | `"target/app.jar"` |
| `:class-dir` | Where the classes are compiled to | `"target/classes"` |
| `:target-dir` | What `clean` deletes | `"target"` |
| `:src-dirs` | The sources | `["src"]` |
| `:resource-dirs` | The resources; the ones that do not exist are left out | `["resources"]` |
| `:aliases` | The aliases of `deps.edn` to build with | none |
| `:compile-opts` | Options of the Clojure compiler, such as `{:direct-linking true}` | none |
| `:exclude` | More patterns of what is not copied from the dependencies | none |
| `:version` | The version of the build | `APP_VERSION`, or `"dev"` |

## What is checked

The options are checked before anything is built, and the message says what it got and what it expects. The build also checks what an
uberjar that does not start would have taught later:

```clojure
(borba.build/build-plan {:lib 'demo :main-ns 'demo.main})
;; throws ExceptionInfo ":lib is demo, and must be a qualified symbol, such as com.example/app"
;;   {:error :borba.build/invalid-option, :option :lib, :value demo}

(borba.build/build-plan {:lib 'com.example/demo :main-ns 'demo.nowhere})
;; throws ExceptionInfo "the namespace demo.nowhere is not in [\"src\"]"
;;   {:error :borba.build/main-ns-not-found, :main-ns demo.nowhere, :src-dirs ["src"]}
```

| `:error` | When |
|---|---|
| `::invalid-option` | An option is not the kind of value it is (`:option` and `:value` in the data) |
| `::main-ns-not-found` | The main namespace is not in the sources (`:src-dirs` in the data) |
| `::no-main-class` | After the compilation: the main namespace has no main class, because it lacks `(:gen-class)` |

`build-plan` is the part that checks, and can be called alone to see what a build is going to do.

## Signed dependencies

A signed jar, such as one of BouncyCastle, has signatures of its own files, which make the JVM refuse an uberjar that has them
copied in, with `Invalid signature file digest`. They are left out by default, with `META-INF/*.SF`, `.DSA`, `.RSA` and `.EC`; `:exclude` adds
to them. Files that two dependencies both have, such as `data_readers.clj` and the `META-INF/services` of Java, are merged, which is
what tools.build does by default.

## API

| Name | What it does |
|---|---|
| `borba.build/uber` | Compiles the service and makes its uberjar |
| `borba.build/clean` | Deletes what was built |
| `borba.build/build-plan` | Checks the options and returns what is going to be built |

## Tests

The suite builds an uberjar of a small project in a temporary directory, runs it with `java -jar`, and reads its manifest and its
entries. It also checks every option that is refused, and the main namespace that is not in the sources or has no main class.

## Design notes

- **Fail before, not after.** An option that is wrong is known before the first file is copied. What can only be known after the
  compilation, a main class that is not there, is checked right after it, and not by whoever runs the jar.
- **One build for every service.** What differs between the services is data, in their `deps.edn`, and the code that builds is
  tested once here.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)

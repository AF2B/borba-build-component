(ns borba.build
  "Generic uberjar build for Borba microservices.
  
    Driven entirely by :exec-args declared in the consuming project's deps.edn.
    No project-local build.clj required.
  
    Contract
    --------
    :lib           - qualified symbol identifying the library (e.g. com.borba/sum-service)
    :main-ns       - symbol of the entry-point namespace (e.g. borba.runtime.main)
    :uber-file     - output path (default: \"target/app.jar\")
    :class-dir     - compiled classes dir (default: \"target/classes\")
    :src-dirs      - source paths (default: [\"src\"])
    :resource-dirs - resource paths (default: [\"resources\"])
  
    Environment
    -----------
    APP_VERSION - semantic version injected at build time (default: \"dev\")
    "
  (:require [clojure.tools.build.api :as b]))

(defn clean
  "Remove all build artifacts under target/."
  [{:keys [_lib] :as _opts}]
  (b/delete {:path "target"}))

(defn uber
  "Compile Clojure sources and produce a self-contained uberjar."
  [{:keys [lib main-ns uber-file class-dir src-dirs resource-dirs]
    :or   {uber-file     "target/app.jar"
           class-dir     "target/classes"
           src-dirs      ["src"]
           resource-dirs ["resources"]}
    :as   opts}]
  (let [version (or (System/getenv "APP_VERSION") "dev")
        basis   (b/create-basis {:project "deps.edn"})]
    (clean opts)
    (b/copy-dir {:src-dirs   (into src-dirs resource-dirs)
                 :target-dir class-dir})
    (b/compile-clj {:basis     basis
                    :src-dirs  src-dirs
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns
             :manifest  {"Implementation-Title"   (name lib)
                         "Implementation-Version" version}})))

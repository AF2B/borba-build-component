(ns borba.build
  "A generic uberjar build for a service, driven by the :exec-args of its
   deps.edn, so that no service carries a build.clj of its own:

     :aliases
     {:build
      {:deps       {io.github.af2b/borba-build-component
                    {:git/url \"https://github.com/AF2B/borba-build-component\"
                     :git/tag \"v1.0.0\"
                     :git/sha \"...\"}}
       :ns-default borba.build
       :exec-args  {:lib     com.example/payments
                    :main-ns com.example.payments.main}}}

     APP_VERSION=1.4.2 clojure -T:build uber

   The uberjar is target/app.jar, with the sources and the resources of the
   service, compiled, and everything it depends on. It runs with `java -jar`.

   The options are checked before anything is built, and a build that would
   make an uberjar that does not start fails saying why: a main namespace that
   is not in the sources, or that has no -main compiled because it lacks
   (:gen-class)."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.build.api :as b])
  (:import
   (java.io File)))

(set! *warn-on-reflection* true)

(def default-target-dir
  "Where everything that is built goes, unless told otherwise."
  "target")

(def default-uber-file
  "The uberjar, unless told otherwise."
  "target/app.jar")

(def default-class-dir
  "Where the compiled classes go, unless told otherwise."
  "target/classes")

(def default-src-dirs
  "The sources of the service, unless told otherwise."
  ["src"])

(def default-resource-dirs
  "The resources of the service, unless told otherwise."
  ["resources"])

(def default-version
  "The version of an uberjar built without APP_VERSION."
  "dev")

(def version-env-var
  "The environment variable that gives the version of the build."
  "APP_VERSION")

(def default-exclude
  "What is not copied from the dependencies into the uberjar: the signatures of
   signed jars, which are of the jar they were in and make the JVM refuse the
   uberjar they are copied into."
  ["^META-INF/[^/]+\\.(?:SF|DSA|RSA|EC)$"])

(def ^:private bytes-per-megabyte (* 1024 1024))

(defn- invalid-option
  [option
   value
   expected]
  (ex-info (str ":" (name option) " is " (pr-str value) ", and must be "
                expected)
           {:error  ::invalid-option
            :option option
            :value  value}))

(defn- strings?
  [value]
  (and (sequential? value) (every? string? value)))

(defn- ns-path
  "Returns the path of the files of a namespace, without the extension."
  [namespace-symbol]
  (-> (str namespace-symbol) (str/replace "-" "_") (str/replace "." "/")))

(defn- project-file
  "Returns a file of the project, which is where the build is run."
  ^File [& parts]
  (apply io/file b/*project-root* parts))

(defn- main-source?
  "Returns true when the sources have the file of the namespace."
  [main-ns
   src-dirs]
  (let [path (ns-path main-ns)]
    (boolean
     (some (fn [dir]
             (or (.exists (project-file dir (str path ".clj")))
                 (.exists (project-file dir (str path ".cljc")))))
           src-dirs))))

(defn build-plan
  "Checks the options of a build and returns what to build, with their
   defaults. Fails naming the first option that is not valid.
   - lib: the qualified symbol of the service, such as com.example/payments
   - main-ns: the symbol of the namespace that has -main and (:gen-class)
   - uber-file: the uberjar to make (default \"target/app.jar\")
   - class-dir: where the classes are compiled to (default
     \"target/classes\")
   - target-dir: what a clean deletes (default \"target\")
   - src-dirs: the sources (default [\"src\"])
   - resource-dirs: the resources, the ones that exist (default [\"resources\"])
   - aliases: the aliases of deps.edn to build with (default none)
   - compile-opts: options of the Clojure compiler, such as
     {:direct-linking true} (default none)
   - exclude: more patterns of what is not copied from the dependencies
     (default none)
   - version: the version of the build (default: APP_VERSION, or \"dev\")"
  [{:keys [lib main-ns uber-file class-dir target-dir src-dirs resource-dirs
           aliases compile-opts exclude version]
    :or   {uber-file     default-uber-file
           class-dir     default-class-dir
           target-dir    default-target-dir
           src-dirs      default-src-dirs
           resource-dirs default-resource-dirs
           aliases       []
           compile-opts  {}
           exclude       []}}]
  (when-not (qualified-symbol? lib)
    (throw (invalid-option :lib lib
                           "a qualified symbol, such as com.example/app")))
  (when-not (simple-symbol? main-ns)
    (throw (invalid-option :main-ns main-ns
                           "a namespace, such as com.example.app.main")))
  (doseq [[option value] [[:uber-file uber-file]
                          [:class-dir class-dir]
                          [:target-dir target-dir]]]
    (when-not (and (string? value) (not (str/blank? value)))
      (throw (invalid-option option value "a non-empty string"))))
  (doseq [[option value] [[:src-dirs src-dirs]
                          [:resource-dirs resource-dirs]
                          [:exclude exclude]]]
    (when-not (strings? value)
      (throw (invalid-option option value "a vector of strings"))))
  (when-not (and (sequential? aliases) (every? keyword? aliases))
    (throw (invalid-option :aliases aliases "a vector of keywords")))
  (when-not (map? compile-opts)
    (throw (invalid-option :compile-opts compile-opts "a map")))
  (when-not (or (nil? version)
                (and (string? version) (not (str/blank? version))))
    (throw (invalid-option :version version
                           "a non-empty string, or not given")))
  (when-not (main-source? main-ns src-dirs)
    (throw (ex-info (str "the namespace " main-ns " is not in "
                         (pr-str src-dirs))
                    {:error    ::main-ns-not-found
                     :main-ns  main-ns
                     :src-dirs (vec src-dirs)})))
  {:lib           lib
   :main-ns       main-ns
   :uber-file     uber-file
   :class-dir     class-dir
   :target-dir    target-dir
   :src-dirs      (vec src-dirs)
   :resource-dirs (vec resource-dirs)
   :aliases       (vec aliases)
   :compile-opts  compile-opts
   :exclude       (into default-exclude exclude)
   :version       (or version (System/getenv version-env-var) default-version)})

(defn clean
  "Deletes what a build made.
   - target-dir: the directory to delete (default \"target\")"
  [{:keys [target-dir] :or {target-dir default-target-dir}}]
  (b/delete {:path target-dir}))

(defn- main-class-file
  "Returns the file of the compiled main class."
  ^File [{:keys [class-dir main-ns]}]
  (project-file class-dir (str (ns-path main-ns) ".class")))

(defn uber
  "Compiles the service and makes its uberjar, which runs with `java -jar`, and
   returns where it is, its version and its size. Fails, before it builds,
   when an option is not valid or the main namespace is not in the sources, and
   fails, after it compiles, when the main namespace has no -main compiled.
   The options are the ones of `build-plan`.
   - opts: a map of :lib, :main-ns and, optionally, the others of `build-plan`"
  [opts]
  (let [{:keys [lib main-ns uber-file class-dir src-dirs resource-dirs aliases
                compile-opts exclude version]
         :as   plan} (build-plan opts)
        basis        (b/create-basis {:project "deps.edn" :aliases aliases})
        resources    (filterv #(.exists (project-file %)) resource-dirs)]
    (clean plan)
    (b/copy-dir {:src-dirs   (into src-dirs resources)
                 :target-dir class-dir})
    (b/compile-clj {:basis        basis
                    :src-dirs     src-dirs
                    :class-dir    class-dir
                    :compile-opts compile-opts})
    (when-not (.exists (main-class-file plan))
      (throw (ex-info (str "the namespace " main-ns " has no main class: add "
                           "(:gen-class) to it, and a -main")
                      {:error   ::no-main-class
                       :main-ns main-ns})))
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main-ns
             :exclude   exclude
             :manifest  {"Implementation-Title"   (name lib)
                         "Implementation-Version" version}})
    (let [size (.length (project-file uber-file))]
      (println (format "built %s, version %s, %.1f MB"
                       uber-file version (/ (double size) bytes-per-megabyte)))
      {:uber-file  uber-file
       :version    version
       :size-bytes size})))

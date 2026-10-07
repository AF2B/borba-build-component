(ns borba.build-test
  (:require
   [borba.build :as build]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.build.api :as b])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.jar JarFile)))

(set! *warn-on-reflection* true)

(def ^:private deps-edn
  "{:paths [\"src\" \"resources\"]
    :deps  {org.clojure/clojure {:mvn/version \"1.12.6\"}}}")

(def ^:private main-source
  "(ns demo.main
     (:gen-class))

   (defn -main
     [& _args]
     (println \"hello from the uberjar\"))")

(def ^:private no-main-source
  "(ns demo.nomain)

   (defn -main [& _args] (println \"never\"))")

(defn- project
  "Makes a project in a temporary directory, with a main namespace and a
   resource, and returns its directory."
  ^File []
  (let [directory (.toFile (Files/createTempDirectory
                            "borba-build"
                            (make-array FileAttribute 0)))]
    (.deleteOnExit directory)
    (spit (io/file directory "deps.edn") deps-edn)
    (io/make-parents (io/file directory "src/demo/main.clj"))
    (spit (io/file directory "src/demo/main.clj") main-source)
    (spit (io/file directory "src/demo/nomain.clj") no-main-source)
    (io/make-parents (io/file directory "resources/config.edn"))
    (spit (io/file directory "resources/config.edn") "{:service \"demo\"}")
    directory))

(defn- thrown-data
  "Returns the data of the exception a function throws, or nil."
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- plan-error
  "Returns the option that a build is refused for, or nil."
  [directory options]
  (binding [b/*project-root* (.getPath ^File directory)]
    (let [data (thrown-data
                #(build/build-plan (merge {:lib     'com.example/demo
                                           :main-ns 'demo.main}
                                          options)))]
      (when (= :borba.build/invalid-option (:error data))
        (:option data)))))

(deftest build-plan-test
  (let [directory (project)]
    (binding [b/*project-root* (.getPath directory)]
      (testing "has the places and the options of a service by default"
        (is (= {:lib           'com.example/demo
                :main-ns       'demo.main
                :uber-file     "target/app.jar"
                :class-dir     "target/classes"
                :target-dir    "target"
                :src-dirs      ["src"]
                :resource-dirs ["resources"]
                :aliases       []
                :compile-opts  {}
                :exclude       build/default-exclude
                :version       "1.0.0"}
               (build/build-plan {:lib     'com.example/demo
                                  :main-ns 'demo.main
                                  :version "1.0.0"}))))

      (testing "takes what it is told"
        (let [plan (build/build-plan {:lib           'com.example/demo
                                      :main-ns       'demo.main
                                      :uber-file     "out/demo.jar"
                                      :class-dir     "out/classes"
                                      :target-dir    "out"
                                      :aliases       [:prod]
                                      :compile-opts  {:direct-linking true}
                                      :exclude       ["^META-INF/extra$"]
                                      :version       "2.3.4"})]
          (is (= "out/demo.jar" (:uber-file plan)))
          (is (= [:prod] (:aliases plan)))
          (is (= {:direct-linking true} (:compile-opts plan)))
          (is (= (conj build/default-exclude "^META-INF/extra$")
                 (:exclude plan)))))

      (testing "has a version when none is given"
        (is (string? (:version (build/build-plan {:lib     'com.example/demo
                                                  :main-ns 'demo.main}))))))))

(deftest invalid-options-test
  (let [directory (project)]
    (testing "the library is a qualified symbol"
      (doseq [bad [nil 'demo "com.example/demo" :com.example/demo]]
        (is (= :lib (plan-error directory {:lib bad})) (pr-str bad))))

    (testing "the main namespace is a symbol"
      (doseq [bad [nil "demo.main" :demo.main 'demo/main]]
        (is (= :main-ns (plan-error directory {:main-ns bad})) (pr-str bad))))

    (testing "the files are non-empty strings"
      (doseq [option [:uber-file :class-dir :target-dir]
              bad    [nil "" "  " 5]]
        (is (= option (plan-error directory {option bad}))
            (str option " " (pr-str bad)))))

    (testing "the directories and the patterns are vectors of strings"
      (doseq [option [:src-dirs :resource-dirs :exclude]
              bad    ["src" [1] :src]]
        (is (= option (plan-error directory {option bad}))
            (str option " " (pr-str bad)))))

    (testing "the aliases are keywords, and the compile options a map"
      (is (= :aliases (plan-error directory {:aliases ["prod"]})))
      (is (= :compile-opts (plan-error directory {:compile-opts [:a]}))))

    (testing "the version is a non-empty string, or not given"
      (is (= :version (plan-error directory {:version ""})))
      (is (= :version (plan-error directory {:version 1}))))

    (testing "the main namespace is in the sources, and says where it looked"
      (binding [b/*project-root* (.getPath ^File directory)]
        (is (= {:error    :borba.build/main-ns-not-found
                :main-ns  'demo.missing
                :src-dirs ["src"]}
               (thrown-data
                #(build/build-plan {:lib     'com.example/demo
                                    :main-ns 'demo.missing}))))))))

(deftest default-exclude-test
  (testing "keeps the signatures of a jar out of the uberjar"
    (let [excluded? (fn [path]
                      (boolean (some #(re-find (re-pattern %) path)
                                     build/default-exclude)))]
      (doseq [path ["META-INF/BCKEY.SF" "META-INF/BCKEY.DSA"
                    "META-INF/BCKEY.RSA" "META-INF/SIGNER.EC"]]
        (is (excluded? path) path))
      (doseq [path ["META-INF/MANIFEST.MF" "META-INF/services/x.Provider"
                    "META-INF/maven/a/b/pom.xml" "data_readers.clj"
                    "META-INF/versions/9/Foo.class"]]
        (is (not (excluded? path)) path)))))

(deftest clean-test
  (testing "deletes what was built, and nothing else"
    (let [directory (project)]
      (binding [b/*project-root* (.getPath directory)]
        (io/make-parents (io/file directory "target/classes/x"))
        (spit (io/file directory "target/classes/x") "x")
        (build/clean {})
        (is (not (.exists (io/file directory "target"))))
        (is (.exists (io/file directory "src/demo/main.clj")))))))

(defn- run-jar
  "Runs an uberjar, and returns its exit status and what it printed."
  [^File jar]
  (let [java    (str (System/getProperty "java.home") "/bin/java")
        command [java "-jar" (.getPath jar)]
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    .start)
        output  (slurp (.getInputStream process))]
    {:exit (.waitFor process) :output (str/trim output)}))

(deftest uber-test
  (testing "makes an uberjar that runs, with the resources and the manifest"
    (let [directory (project)]
      (binding [b/*project-root* (.getPath directory)]
        (let [result (build/uber {:lib     'com.example/demo
                                  :main-ns 'demo.main
                                  :version "1.2.3"})
              jar    (io/file directory "target/app.jar")]
          (is (= {:uber-file "target/app.jar" :version "1.2.3"}
                 (select-keys result [:uber-file :version])))
          (is (pos? (:size-bytes result)))
          (is (.exists jar))
          (is (= {:exit 0 :output "hello from the uberjar"} (run-jar jar)))
          (with-open [jar-file (JarFile. jar)]
            (is (some? (.getEntry jar-file "config.edn")))
            (is (some? (.getEntry jar-file "demo/main.class")))
            (let [attributes (.getMainAttributes (.getManifest jar-file))]
              (is (= "demo" (.getValue attributes "Implementation-Title")))
              (is (= "1.2.3" (.getValue attributes "Implementation-Version")))
              (is (= "demo.main" (.getValue attributes "Main-Class"))))))))))

(deftest no-main-class-test
  (testing "fails after it compiles, when the main namespace has no main class"
    (let [directory (project)]
      (binding [b/*project-root* (.getPath directory)]
        (is (= {:error   :borba.build/no-main-class
                :main-ns 'demo.nomain}
               (thrown-data #(build/uber {:lib     'com.example/demo
                                          :main-ns 'demo.nomain}))))))))

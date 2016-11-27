(defproject onyx-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen testing Onyx"
  :url "github.com/onyx-platform/onyx-jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.onyxplatform/onyx "0.9.7-beta1" :exclusions [org.slf4j/slf4j-nop]]
                 ;[org.slf4j/slf4j-nop "1.7.21"]
                 [fipp "0.6.4"]
                 [org.onyxplatform/onyx-metrics "0.9.7.0-beta1"]
                 [org.onyxplatform/onyx-bookkeeper "0.9.7.0-beta1"]
                 [org.apache.bookkeeper/bookkeeper-server "4.4.0" :exclusions [[org.slf4j/slf4j-log4j12]]]
                 [jepsen "0.1.1"]]
  :test-selectors {:jepsen :jepsen
                   :test-jepsen-tests :test-jepsen-tests
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server" "-Xmx3g" "-XX:+UseG1GC"]
  :profiles {:uberjar {:aot [onyx-peers.launcher.aeron-media-driver
                             onyx-peers.launcher.launch-prod-peers]}
             :dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
                   :source-paths ["env/dev" "src"]}})

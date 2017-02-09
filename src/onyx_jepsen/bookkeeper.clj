(ns onyx-jepsen.bookkeeper
  "Tests for BookKeeper"
  (:require [clojure.tools.logging :refer :all]
            [knossos.core :as knossos]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]

            ;; Onyx!
            [onyx.static.default-vals :refer [arg-or-default defaults]]
            [onyx.state.log.bookkeeper :as obk]
            [onyx.compression.nippy :as nippy]                                                                                                             
            [knossos.op :as op]
            [jepsen.control.util]

            ;; debian hackery
            [jepsen.os :as os]
            [jepsen.control.net :as net]

            [jepsen [client :as client]
             [core :as jepsen]
             [model :as model]
             [db :as db]
             [tests :as tests]
             [control :as c :refer [|]]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :refer [timeout meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian])

  (:import [org.apache.bookkeeper.client LedgerHandle LedgerEntry BookKeeper BookKeeper$DigestType AsyncCallback$AddCallback BKException BKException$Code AsyncCallback$AddCallback]
           [org.apache.bookkeeper.conf ClientConfiguration]
           [org.apache.curator.framework CuratorFramework CuratorFrameworkFactory]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BookKeeper only test code
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn zk-node-ids
  "We number nodes in reverse order so the leader is the first node. Returns a
  map of node names to node ids."
  [test]
  (->> test
       :nodes
       (map-indexed (fn [i node] [node (- (count (:nodes test)) i)]))
       (into {})))

(defn zk-node-id
  [test node]
  (get (zk-node-ids test) node))

(defn zoo-cfg-servers
  "Constructs a zoo.cfg fragment for servers."
  [test]
  (->> (zk-node-ids test)
       (map (fn [[node id]]
              (str "server." id "=" (name node) ":2888:3888")))
       (str/join "\n")))

(def || (c/lit "||"))

(def env-config 
  (-> "resources/prod-env-config.edn" slurp read-string))

(defn bk-cfg-servers
  [test]
  (str "zkServers=" (clojure.string/join ","
                                         (map (fn [n]
                                                (str (name n) ":2181")) 
                                              (:nodes test)))))

(def bk-ledger-path
  (str "zkLedgersRootPath=/ledgers" #_(:onyx/tenancy-id env-config)))

(defn setup 
  "Sets up and tears down Onyx"
  [version]
  (reify db/DB
    (setup! [_ test node]

      (c/su
       ;; bookkeeper threw some exceptions because hostname wasn't set correctly?
       (c/exec "hostname" (name node))

       (c/exec :apt-get :update)

       ;; Disable non-embedded BookKeeper
       (c/upload "bookkeeper-server-4.4.0-bin.tar.gz" "/bookkeeper.tar.gz")
       (c/exec :tar "zxvf" "/bookkeeper.tar.gz")
       (c/exec :mv "bookkeeper-server-4.4.0" "/bookkeeper-server")

       (c/upload "script/upgrade-java.sh" "/upgrade-java.sh")
       (c/exec :chmod "+x" "/upgrade-java.sh")
       (info node "Upgrading java")
       (c/exec "/upgrade-java.sh")
       (info node "Done upgrading java")

       (info node "Setting up ZK")
       ; Install zookeeper
       (debian/install [:zookeeper :zookeeper-bin :zookeeperd])

       ; Set up zookeeper
       (c/exec :echo (zk-node-id test node) :> "/etc/zookeeper/conf/myid")
       (c/exec :echo (str (slurp (io/resource "zoo.cfg"))
                          "\n"
                          (zoo-cfg-servers test))
               :> "/etc/zookeeper/conf/zoo.cfg")

       (info node "ZK restarting")
       (c/exec :service :zookeeper :restart)

       ;; Disable non-embedded BookKeeper
       (c/exec :echo (str (slurp (io/resource "bk_server.conf"))
                          "\n"
                          (bk-cfg-servers test)
                          "\n"
                          bk-ledger-path)
               :> "/bookkeeper-server/conf/bk_server.conf")                                                                                                               
       (info node "starting bookkeeper")
       (c/exec :echo "N" | "/bookkeeper-server/bin/bookkeeper" "shell" "metaformat" || "true")
       (c/exec "/bookkeeper-server/bin/bookkeeper-daemon.sh" "start" "bookie")

       (info node "ZK ready"))

      (info node "set up"))

    (teardown! [_ test node]
      (c/su
        (comment (c/exec :service :zookeeper :stop)
                 (c/exec :rm :-rf
                         (c/lit "/var/lib/zookeeper/version-*")
                         (c/lit "/var/log/zookeeper/*"))
                 (grepkill "java")))
      (info node "tore down"))))

(def ledger-ids (atom []))

;; Move into own ns
(def digest-type
  (BookKeeper$DigestType/MAC))

(def DEFAULT_PASSWORD "password")
(def ZK_LEDGERS_ROOT_PATH "/ledgers")

(defn password [peer-opts]
  (.getBytes ^String DEFAULT_PASSWORD))

(defn open-ledger ^org.apache.bookkeeper.client.LedgerHandle [^BookKeeper client id digest-type password]
  (.openLedger client id digest-type password))

(defn open-ledger-no-recovery ^org.apache.bookkeeper.client.LedgerHandle [^BookKeeper client id digest-type password]
  (.openLedgerNoRecovery client id digest-type password))

(defn create-ledger ^org.apache.bookkeeper.client.LedgerHandle [^BookKeeper client ensemble-size quorum-size digest-type password]
  (.createLedger client ensemble-size quorum-size digest-type password))

(defn close-handle [^LedgerHandle ledger-handle]
  (.close ledger-handle))

(defn new-ledger ^org.apache.bookkeeper.client.LedgerHandle [client peer-opts]
  (let [ensemble-size (arg-or-default :onyx.bookkeeper/ledger-ensemble-size peer-opts)
        quorum-size (arg-or-default :onyx.bookkeeper/ledger-quorum-size peer-opts)]
    (create-ledger client ensemble-size quorum-size digest-type (password peer-opts))))

(defn ^org.apache.bookkeeper.client.BookKeeper bookkeeper
  ([opts]
   (bookkeeper (:zookeeper/address opts)
               ZK_LEDGERS_ROOT_PATH
               (arg-or-default :onyx.bookkeeper/client-timeout opts)
               (arg-or-default :onyx.bookkeeper/client-throttle opts)))
  ([zk-addr zk-root-path timeout throttle]
   (try
    (let [conf (doto (ClientConfiguration.)
                 (.setZkServers zk-addr)
                 (.setZkTimeout timeout)
                 (.setThrottleValue throttle)
                 (.setZkLedgersRootPath zk-root-path))]
      (BookKeeper. conf))
    (catch org.apache.zookeeper.KeeperException$NoNodeException nne
      (throw (ex-info "Error locating BookKeeper cluster via ledger path. Check that BookKeeper has been started via start-env by setting `:onyx.bookkeeper/server? true` in env-config, or is setup at the correct path."
                      {:zookeeper-addr zk-addr
                       :zookeeper-path zk-root-path}
                      nne))))))

(defn read-ledger-entries [ledger-id]
  (let [client (bookkeeper env-config)
        pwd (password env-config)]
    (let [ledger-handle (open-ledger client ledger-id digest-type pwd)
          results (try 
                    (let [last-confirmed (.getLastAddConfirmed ledger-handle)]
                      (if-not (neg? last-confirmed)
                        (loop [results [] 
                               entries (.readEntries ledger-handle 0 last-confirmed)
                               element ^LedgerEntry (.nextElement entries)] 
                          (let [new-results (conj results (nippy/window-log-decompress (.getEntry element)))] 
                            (if (.hasMoreElements entries)
                              (recur new-results entries (.nextElement entries))
                              new-results)))
                        []))
                    (catch Throwable t
                      (throw 
                        (ex-info (str t (.getCause t))
                                 {:ledger-id ledger-id :exception t})))
                    (finally 
                      (.close client)
                      (.close ledger-handle)))]
      {:ledger-id ledger-id
       :results results})))

(defrecord WriteLogClient [client ledger-handle]
  client/Client
  (setup! [_ test node]
    (let [client (bookkeeper env-config)
          lh (new-ledger client env-config)]
      (swap! ledger-ids conj (.getId lh))
      (WriteLogClient. client lh)))

  (invoke! [this test op]
    (case (:f op)
      :read-ledger (timeout 50000
                            (assoc op :type :info :value :timed-out)
                            (try
                              (assoc op 
                                     :type :ok 
                                     :value (mapv read-ledger-entries @ledger-ids))
                              (catch Throwable t
                                (assoc op :type :info :value t))))
      :add (timeout 5000 
                    (assoc op :type :info :value :timed-out)
                    (try
                     (do 
                      (println "Adding entry")
                      (info "Adding entry" (:value op))
                      (.addEntry ledger-handle (nippy/window-log-compress (:value op)))
                      (info "Done adding entry" (:value op))
                      (assoc op :type :ok :ledger-id (.getId ledger-handle)))
                      (catch Throwable t
                        (assoc op :type :info :value (.getMessage t)))))))

  (teardown! [_ test]
    (.close client)))

(defn write-log-client []
  (->WriteLogClient nil nil))

(defn adds
  "Generator that emits :add operations for sequential integers."
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :add, :value x}))
       gen/seq))

(defrecord Checker []
  checker/Checker
  (check [checker test model history opts]
    (info "Checked " test model history)
    (let [ledger-reads (first (filter (fn [action]
                                        (and (= (:f action) :read-ledger)
                                             (= (:type action) :ok)))
                                      history))
          results (map :results (:value ledger-reads))
          all-in-order? (= results (map sort results))
          successfully-added (filter (fn [action]
                                       (and (= (:f action) :add)
                                            (= (:type action) :ok)))
                                     history)
          added-values (set (map :value successfully-added))
          read-values (set (reduce into [] results))
          all-written-read? (clojure.set/difference added-values read-values)
          unacked-writes-read (clojure.set/difference read-values added-values)] 
      {:valid? (and all-in-order? all-written-read?)
       :in-order? all-in-order?
       :added added-values
       :read-values read-values
       :unacknowledged-writes-read unacked-writes-read
       :all-written-read? (empty? all-written-read?)})))

(defn read-ledger
  []
  (gen/clients (gen/once {:type :invoke :f :read-ledger})))


(def os
  (reify os/OS
    (setup! [_ test node]
      (info node "setting up debian")

      ;(c/upload "script/switch_apt_sources.sh" "/switch_apt_sources.sh")
      ;(c/exec :chmod "+x" "/switch_apt_sources.sh")
      ;(c/exec "/switch_apt_sources.sh")

      (debian/setup-hostfile!)

      (debian/maybe-update!)

      (c/su
       ; Packages!
       (debian/install [:wget
                        :sysvinit-core
                        :sysvinit
                        :sysvinit-utils
                        :curl
                        :vim
                        :man-db
                        :faketime
                        :unzip
                        :iptables
                        :psmisc
                        :iputils-ping
                        :rsyslog
                        :logrotate])

       ; Fucking systemd breaks a bunch of packages
       (if (debian/installed? :systemd)
         (c/exec :apt-get :remove :-y :--purge :--auto-remove :systemd)))

      (meh (net/heal)))
    (teardown! [_ test node])))

(defn basic-test
  "A simple test of BookKeeper's safety."
  [{:keys [version awake-ms stopped-ms time-limit]}]
  (merge tests/noop-test
         {:os os ;debian/os
          :db (setup version)
          :client (write-log-client)
          :model model/noop
          :checker (->Checker)
          :generator (gen/phases
                       (->> (adds) 
                            (gen/stagger 1/10)
                            (gen/delay 1)
                            (gen/nemesis
                              (gen/seq (cycle
                                         [(gen/sleep awake-ms)
                                          {:type :info :f :start}
                                          (gen/sleep stopped-ms)
                                          {:type :info :f :stop}])))
                            (gen/time-limit time-limit)) 
                       (read-ledger))
          ;:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
          :nemesis (nemesis/partition-random-halves)}))

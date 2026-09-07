(ns sahai.cli
  "Operational entry point for the fleet placement layer."
  (:require [kotoba.lang.fmt :as fmt]
            [sahai.core :as fleet]
            [sahai.exec :as exec]
            [sahai.fence :as fence]
            [sahai.store :as store])
  (:gen-class))

(defn- root []
  (or (System/getenv "FLEET_ROOT")
      ;; Compatibility with pre-split deployments.
      (System/getenv "KOTOTAMA_FLEET_ROOT")
      "tmp/fleet"))

(defn- fact-wasm []
  "fleet/fixtures/kotoba-compiled-fact.wasm")

(defn- flag? [args flag]
  (boolean (some #{flag} args)))

(defn- parse-long-opt [args flag default]
  (let [xs (vec args)
        i (.indexOf xs flag)]
    (if (and (>= i 0) (< (inc i) (count xs)))
      (try
        (Long/parseLong (str (nth xs (inc i))))
        (catch Exception _ default))
      default)))

(defn cmd-fleet-demo []
  (let [lease (fleet/make-lease "tenant-a" "guest/fact"
                                :budget {:fuel 100000 :ticks 3
                                         :llm-infers 0 :http-posts 0}
                                :grants [])
        reg0 (fleet/register-lease (fleet/empty-registry) lease)
        run (fn [_] {:ok? true :result 120 :fuel-used 59 :limits {}})
        step1 (fleet/run-loop-step reg0 (:kototama.fleet/lease-id lease) run)
        step2 (fleet/run-loop-step (:registry step1)
                                   (:kototama.fleet/lease-id lease) run)
        cp (fleet/checkpoint (:registry step2) {:demo true})
        restored (fleet/restore cp)]
    (fmt/pprint
     {:ok? true
      :lease-id (:kototama.fleet/lease-id lease)
      :step1-ok (:ok? step1)
      :step2-ok (:ok? step2)
      :budget-after (:kototama.fleet/budget (:lease step2))
      :checkpoint-schema (:kototama.fleet/checkpoint-schema cp)
      :restored-tenant-leases
      (count (fleet/tenant-leases restored "tenant-a"))})
    {:ok? true}))

(defn cmd-fleet-run [wasm-path & args]
  (let [use-aiueos? (flag? args "--use-aiueos")
        out (exec/bootstrap-and-run!
             "cli-tenant" "cli-guest" wasm-path
             :store (store/disk-store (root))
             :max-ticks 2
             :budget {:fuel 5000000 :ticks 5}
             :use-aiueos? use-aiueos?)]
    (fmt/pprint
     {:ok? true
      :lease-id (:lease-id out)
      :stopped (:stopped out)
      :result (get-in out [:last :result])
      :checkpoint-path (:checkpoint-path out)
      :checkpoint-key (:checkpoint-key out)
      :steps (count (:steps out))
      :grant-source (:grant-source out)
      :use-aiueos? use-aiueos?})
    {:ok? true}))

(defn cmd-tamaki-run [envelope-path wasm-path]
  (let [envelope (exec/read-capability-envelope envelope-path)
        out (exec/bootstrap-tamaki-run!
             envelope wasm-path
             :store (store/disk-store (root))
             :max-ticks 2
             :budget {:fuel 5000000 :ticks 5})]
    (fmt/pprint
     {:ok? true
      :lease-id (:lease-id out)
      :stopped (:stopped out)
      :result (get-in out [:last :result])
      :checkpoint-key (:checkpoint-key out)
      :steps (count (:steps out))
      :capability-contract (:capability-contract out)})
    {:ok? true}))

(defn cmd-fleet-list []
  (let [keys (store/list-checkpoint-keys (store/disk-store (root)))]
    (fmt/pprint {:ok? true :root (root) :keys keys :count (count keys)})
    {:ok? true}))

(defn cmd-fleet-status []
  (fmt/pprint (assoc (store/summarize-store (store/disk-store (root)))
                    :ok? true))
  {:ok? true})

(defn cmd-fleet-audit []
  (let [entries (store/list-audit-entries (store/disk-store (root)))]
    (fmt/pprint
     {:ok? true
      :root (root)
      :count (count entries)
      :entries
      (mapv (fn [{:keys [key data]}]
              {:key key
               :lease-id (:kototama.fleet/lease-id data)
               :tick (:kototama.fleet/tick data)
               :ok? (:ok? data)
               :result (:result data)
               :fuel-used (:fuel-used data)
               :error (:error data)})
            entries)})
    {:ok? true}))

(defn cmd-fleet-resume [checkpoint-key wasm-path]
  (let [out (exec/resume-from-checkpoint!
             checkpoint-key
             :store (store/disk-store (root))
             :wasm wasm-path
             :max-ticks 2)]
    (fmt/pprint
     {:ok? (boolean (:ok? out))
      :checkpoint-key (:checkpoint-key out)
      :active-before (:active-before out)
      :resumes
      (mapv (fn [r]
              {:lease-id (:lease-id r)
               :stopped (:stopped r)
               :result (get-in r [:last :result])
               :checkpoint-key (:checkpoint-key r)
               :resumed? (:resumed? r)})
            (:resumes out))})
    {:ok? (boolean (:ok? out))}))

(defn cmd-fleet-recover [wasm-path]
  (let [out (exec/recovery-pass!
             :store (store/disk-store (root))
             :wasm wasm-path
             :max-keys 10
             :max-ticks 1)]
    (fmt/pprint
     {:ok? true
      :keys (:keys out)
      :ok-count (:ok-count out)
      :fail-count (:fail-count out)
      :results (mapv #(select-keys % [:key :ok? :active-before :error])
                     (:results out))})
    {:ok? true}))

(defn cmd-fleet-gate []
  (let [out (exec/run-r3-gate! :wasm (fact-wasm))]
    (fmt/pprint
     (select-keys out [:ok? :status :pass-count :fail-count :checks
                       :not-claimed :gate :store-root]))
    {:ok? (boolean (:ok? out))}))

(defn cmd-fleet-daemon [wasm-path args]
  (let [interval (parse-long-opt args "--interval-ms" 500)
        max-passes (parse-long-opt args "--max-passes" 3)
        max-ticks (parse-long-opt args "--max-ticks" 1)
        out (exec/run-daemon!
             :store (store/disk-store (root))
             :wasm wasm-path
             :interval-ms interval
             :max-passes max-passes
             :max-ticks max-ticks
             :max-keys 10)]
    (fmt/pprint
     {:ok? true
      :stopped (:stopped out)
      :pass-count (:pass-count out)
      :ok-count (:ok-count out)
      :fail-count (:fail-count out)
      :interval-ms interval
      :max-passes max-passes
      :node-id (fence/node-id)})
    {:ok? true}))

(defn cmd-fleet-fence-demo []
  (let [node-a "node-a"
        node-b "node-b"
        reg0 (fleet/empty-registry)
        lease-a (fleet/make-lease "t1" "g1" :owner node-a
                                  :budget {:fuel 1000 :ticks 5})
        claim-a (fence/claim-lease reg0 lease-a node-a)
        lease-b (fleet/make-lease "t1" "g1" :owner node-b
                                  :budget {:fuel 1000 :ticks 5})
        refuse (fence/claim-lease (:registry claim-a) lease-b node-b)
        steal (fence/claim-lease
               (:registry claim-a)
               (assoc lease-b :kototama.fleet/epoch 2)
               node-b)
        merged (fence/merge-registries (:registry claim-a)
                                       (:registry steal))]
    (fmt/pprint
     {:ok? true
      :claim-a (:reason claim-a)
      :refuse-b (:ok? refuse)
      :refuse-reason (:reason refuse)
      :steal-b (:reason steal)
      :merged-owners
      (mapv :kototama.fleet/owner (fleet/all-leases merged))
      :node-id (fence/node-id)})
    {:ok? true}))

(defn- usage-error [message]
  (binding [*out* *err*] (println message))
  {:ok? false})

(defn -main [& args]
  (let [[cmd & more] args
        result
        (case cmd
          "fleet-demo" (cmd-fleet-demo)
          "fleet-run" (if-let [wasm (first more)]
                        (apply cmd-fleet-run wasm (rest more))
                        (usage-error "usage: fleet-run <guest.wasm> [--use-aiueos]"))
          "tamaki-run" (if (and (first more) (second more))
                         (cmd-tamaki-run (first more) (second more))
                         (usage-error "usage: tamaki-run <capability-envelope.edn> <guest.wasm>"))
          "fleet-list" (cmd-fleet-list)
          "fleet-status" (cmd-fleet-status)
          "fleet-audit" (cmd-fleet-audit)
          "fleet-resume" (if (and (first more) (second more))
                           (cmd-fleet-resume (first more) (second more))
                           (usage-error "usage: fleet-resume <checkpoint-key> <guest.wasm>"))
          "fleet-recover" (if-let [wasm (first more)]
                            (cmd-fleet-recover wasm)
                            (usage-error "usage: fleet-recover <guest.wasm>"))
          "fleet-daemon" (if-let [wasm (first more)]
                           (cmd-fleet-daemon wasm (rest more))
                           (usage-error "usage: fleet-daemon <guest.wasm> [--interval-ms N] [--max-passes N]"))
          "fleet-fence-demo" (cmd-fleet-fence-demo)
          "fleet-gate" (cmd-fleet-gate)
          (do
            (println "fleet — durable T6 placement for Kotoba tenders")
            (println "  fleet-gate | fleet-demo | fleet-run | tamaki-run | fleet-list")
            (println "  fleet-status | fleet-audit | fleet-resume")
            (println "  fleet-recover | fleet-daemon | fleet-fence-demo")
            {:ok? true}))]
    (System/exit (if (:ok? result) 0 1))))

(ns cauchy.jobs.health
  (:require [cauchy.jobs.utils :as utils]
            [clojure.string :as str]
            [sigmund.core :as sig]))

(def total-mem (:total (sig/os-memory)))

(defn load-average
  ([{:keys [warn crit] :as conf :or {warn 1 crit 2}}]
   (let [services ["load_1" "load_5" "load_15"]
         metrics (vec (sig/os-load-avg))
         tconf {:comp > :crit crit :warn warn}]
     (map (fn [s m]
            {:service s
             :metric m
             :state (utils/threshold tconf m)})
          services metrics)))
  ([] (load-average {})))

(defn memory
  ([{:keys [warn crit] :as conf :or {warn 80 crit 90}}]
   (let [{:keys [total free] :as data} (sig/os-memory)
         used (- total free)
         used-pct (double (* 100 (/ used total)))
         tconf {:comp > :crit crit :warn warn}]

     [{:service "memory_total"
       :metric total}

      {:service "memory_free"
       :metric free}

      {:service "memory_used"
       :metric used}

      {:service "memory_used_pct"
       :metric used-pct
       :state (utils/threshold tconf used-pct)}]))
  ([] (memory {})))

(defn swap
  ([{:keys [warn crit] :as conf :or {warn 80 crit 90}}]
   (let [{:keys [total used] :as data} (sig/os-swap)]
     (when-not (zero? total)
       (let [free (- total used)
             used-pct (double (* 100 (/ used total)))
             tconf {:comp > :crit crit :warn warn}]
         [{:service "swap_total" :metric total}
          {:service "swap_free" :metric free}
          {:service "swap_used" :metric used}
          {:service "swap_used_pct" :metric used-pct
           :state (utils/threshold tconf used-pct)}]))))
  ([] (swap {})))

(defn disk-entry
  [{:keys [warn crit] :as conf :or {warn 80 crit 90}}
   {:keys [dir-name dev-name] :as device}]
  (let [{:keys [total free]} (sig/fs-usage dir-name)]
    (when (and (pos? total) (pos? free))
      (let [total (bit-shift-left total 10) ;; kB
            free (bit-shift-left free 10) ;; kB
            used  (- total free)
            used-pct (double (* 100 (/ used total)))
            tconf {:comp > :crit crit :warn warn}
            sname (str "disk" (str/replace dir-name #"\/" "_"))]
        [{:service (str sname "_total")
          :metric total}

         {:service (str sname "_free")
          :metric free}

         {:service (str sname "_used")
          :metric used}

         {:service (str sname "_used_pct")
          :metric used-pct
          :state (utils/threshold tconf used-pct)}]))))

(defn disk
  ([tconf]
   (let [virtual-fses ["/dev" "/sys" "/proc" "/run"]]
     (->> (sig/fs-devices)
          (remove (fn [{:keys [^String dir-name] :as entry}]
                    (some #(.startsWith dir-name %)
                          virtual-fses)))
          (map #(disk-entry tconf %))
          (flatten))))
  ([] (disk {})))

(defn process
  [{:keys [pattern name warn-num crit-num
           warn-cpu crit-cpu warn-mem crit-mem]
    :or {warn-num "1:1" crit-num "1:1"
         warn-cpu 10 crit-cpu 20
         warn-mem 10 crit-mem 20}}]

  (if (and name pattern)
    (let [all-pids (sig/os-pids)
          all-info (map (fn [pid]
                          (try
                            (merge {:cmd (str/join " " (sig/ps-args pid))}
                                   (sig/ps-cpu pid)
                                   (sig/ps-exe pid)
                                   (sig/ps-memory pid)
                                   (sig/ps-info pid))
                            (catch Exception e
                              nil)))
                        all-pids)
          all-info (remove nil? all-info)
          total-proc-count (count all-info)
          patt (re-pattern pattern)
          matched-process (filter #(re-find patt (:cmd %)) all-info)
          process-count (count matched-process)

          [nwl nwh] (map #(Integer/parseInt %) (str/split warn-num #"\:"))
          [ncl nch] (map #(Integer/parseInt %) (str/split crit-num #"\:"))
          final-state (utils/worst-state
                       (utils/threshold {:warn nwh :crit nch :comp >} process-count)
                       (utils/threshold {:warn nwl :crit ncl :comp <} process-count))

          number-msg {:service (str "process_num_" name)
                      :metric process-count
                      :state final-state}

          sum-cpu (* 100 (reduce + (map :percent matched-process)))
          cpu-msg {:service (str "process_cpu_" name)
                   :metric sum-cpu
                   :state (utils/threshold
                           {:warn warn-cpu :crit crit-cpu :comp >}
                           sum-cpu)}

          sum-rss (reduce + (map :rss matched-process))
          rss-msg {:service (str "process_rss_" name)
                   :metric sum-rss}

          mem-used (double (/ (* 100 sum-rss) total-mem))
          mem-msg {:service (str "process_mem_" name)
                   :metric mem-used
                   :state (utils/threshold
                           {:warn warn-mem :crit crit-mem :comp >}
                           mem-used)}]
      (remove nil? [number-msg cpu-msg rss-msg mem-msg]))
    ;; badly configured, need name and pattern
    (throw (Exception. (str  "process check is badly configured: "
                             "need name and pattern keys")))))

(def disk-io-data (atom nil))

(defn disk-io
  ([{:keys [r-warn r-crit w-warn w-crit] :as conf
     :or {r-warn 100000 r-crit 100000
          w-warn 100000 w-crit 100000}}]
   (let [usage  (map #(sig/fs-usage (:dir-name %)) (sig/fs-devices) )
         reads  (->> dat (map :disk-read-bytes) (reduce +))
         writes (->> dat (map :disk-write-bytes) (reduce +))
         now (java.util.Date.)]
     (if-let [[^java.util.Date old-t old-r old-w] @disk-io-data]
       (let [t-diff (- (.getTime now) (.getTime old-t))
             read-io (long ( / (- reads old-r)
                               t-diff 0.001))
             write-io (long ( / (- writes old-w)
                                t-diff 0.001))]
         (reset! disk-io-data [now reads writes])
         [{:service "disk_read_bytes"
           :metric read-io
           :state (utils/threshold
                   {:warn r-warn :crit r-crit :comp >}
                   read-io)}
          {:service "disk_write_bytes"
           :metric write-io
           :state (utils/threshold
                   {:warn w-warn :crit w-crit :comp >}
                   write-io)}])
       ;; initialize old value and try again
       (do
         (reset! disk-io-data [now reads writes])
         (Thread/sleep 1000)
         (disk-io)))))
  ([] (disk-io {})))

(defn bandwidth
  ([{:keys [rx-warn rx-crit tx-warn tx-crit] :as conf
     :or {rx-warn 1000000 rx-crit 1000000
          tx-warn 1000000 tx-crit 1000000}}]
   (when-let [{:keys [speed]} (sig/net-bandwidth)]
     (let [{:keys [rx-bytes tx-bytes]} speed]

       [{:service "rx_bytes_rate"
         :metric rx-bytes
         :state (utils/threshold
                 {:warn rx-warn :crit rx-crit :comp >}
                 rx-bytes)}

        {:service "tx_bytes_rate"
         :metric tx-bytes
         :state (utils/threshold
                 {:warn tx-warn :crit tx-crit :comp >}
                 tx-bytes)}])))
  ([] (bandwidth {})))

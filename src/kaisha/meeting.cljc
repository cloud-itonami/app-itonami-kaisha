(ns kaisha.meeting
  "株主総会と取締役会: 招集通知の期限、決議の要件、議事録の保存期間。

  決議の判定は、入力された議決権の数をもとにした計算であり、決議の有効性の認定ではない。
  309条3項・4項の特殊決議、種類株主総会、累積投票は扱わず :undetermined を返す。"
  (:require [kaisha.company :as co]
            [kaisha.date :as d]))

;; 議題ごとの決議の種類。:source はその要件を定める条文。
(def matters
  {:approve-financials {:resolution :ordinary :source :kaisha/a438}
   :director-compensation {:resolution :ordinary}
   :dividend {:resolution :ordinary}
   :elect-officer {:resolution :officer :source :kaisha/a341}
   :dismiss-director {:resolution :officer :source :kaisha/a341}
   :dismiss-auditor {:resolution :special :source :kaisha/a309}
   :amend-articles {:resolution :special :source :kaisha/a309}
   :share-transfer-restriction {:resolution :special-special :source :kaisha/a309}})

(def board-matters
  "取締役会の議題の種類。稟議・計算書類に結びつけるときに種類が一致するかを確かめる。"
  {:board-general {}
   :approve-financials {:source :kaisha/a436}
   :important-asset {:source :kaisha/a362}
   :large-borrowing {:source :kaisha/a362}
   :important-employee {:source :kaisha/a362}
   :branch {:source :kaisha/a362}})

(defn matter-valid? [body matter]
  (contains? (if (= body :board) board-matters matters) matter))

;; ---------------------------------------------------------------- 招集通知

(defn shareholder-notice-days
  "株主総会の招集通知を発する期限の日数（299条1項）。
  公開会社、または書面・電子投票を定めたときは 2 週間。
  公開会社でなければ 1 週間。取締役会設置会社でない会社は、定款で短くできる。"
  [c {:keys [written-voting?]}]
  (let [short (get-in c [:company/articles :shareholder-notice-days])]
    (cond
      (or (:company/public? c) written-voting?) 14
      (and (not (:company/board? c)) short) short
      :else 7)))

(defn board-notice-days
  "取締役会の招集通知の日数（368条1項）。定款で短くできる。"
  [c]
  (or (get-in c [:company/articles :board-notice-days]) 7))

(defn latest-send-date
  "「会日の N 日前までに」発する場合の最終日。会日と発信日の間に N 日をあける（会日 − N − 1）。"
  [meeting-date days]
  (d/add-days meeting-date (- (inc days))))

(defn notice-check
  "招集通知が期限内に発せられたか（または、まだ発していないなら最終日）を返す。"
  [c {:meeting/keys [body date notice-sent-on written-voting? all-consent-waiver?]}]
  (cond
    (not (d/valid? date)) {:status :undetermined :reason :missing-meeting-date}

    (and (= body :board) all-consent-waiver?)
    {:status :ok :waived true :source :kaisha/a368}

    :else
    (let [days (if (= body :board)
                 (board-notice-days c)
                 (shareholder-notice-days c {:written-voting? written-voting?}))
          latest (latest-send-date date days)
          src (if (= body :board) :kaisha/a368 :kaisha/a299)]
      (cond
        (nil? notice-sent-on)
        {:status :pending :required-days days :latest-send-date latest :source src}
        (not (d/valid? notice-sent-on))
        {:status :blocked :reason :invalid-notice-date :source src}
        (d/on-or-before? notice-sent-on latest)
        {:status :ok :required-days days :latest-send-date latest :source src}
        :else
        {:status :blocked :reason :notice-too-late :required-days days
         :latest-send-date latest :source src}))))

;; ---------------------------------------------------------------- 決議

(defn- ratio-ok?
  "n/d ≥ num/den（:at-least）または n/d > num/den（:more-than）を整数で比べる。"
  [mode n d [num den]]
  (case mode
    :at-least (>= (* n den) (* num d))
    :more-than (> (* n den) (* num d))))

(defn- quorum-rule
  "出席の要件。定款の定め {:quorum [num den]} または :none（普通決議のみ）。"
  [kind arts]
  (let [q (get arts kind)]
    (case kind
      :ordinary (cond (= q :none) :none
                      q [:at-least q]
                      :else [:more-than [1 2]])
      (:special :officer) (cond (nil? q) [:more-than [1 2]]
                                (= q :none) :invalid
                                (ratio-ok? :at-least (first q) (second q) [1 3]) [:at-least q]
                                :else :invalid))))

(defn resolve-shareholders
  "株主総会の決議要件の計算（309条1・2項、341条）。

  vote: {:matter kw :rights-total n :rights-present n :rights-for n}
  定款の定めは company の :company/articles :quorum {:ordinary [1 3] ...}。"
  [c {:keys [matter rights-total rights-present rights-for]}]
  (let [{:keys [resolution source]} (get matters matter)
        arts (get-in c [:company/articles :quorum])
        nums [rights-total rights-present rights-for]]
    (cond
      (not (co/kk? c)) {:status :undetermined :reason :not-a-stock-company}
      (nil? resolution) {:status :undetermined :reason :unknown-matter}
      (= resolution :special-special) {:status :undetermined :reason :special-special-resolution
                                       :source source}
      (not (every? #(and (integer? %) (not (neg? %))) nums))
      {:status :blocked :reason :invalid-vote-counts}
      (or (zero? rights-total) (> rights-present rights-total) (> rights-for rights-present))
      {:status :blocked :reason :inconsistent-vote-counts}
      :else
      (let [q (quorum-rule resolution arts)]
        (if (= q :invalid)
          {:status :blocked :reason :quorum-below-one-third :source (or source :kaisha/a309)}
          (let [quorum? (or (= q :none)
                            (ratio-ok? (first q) rights-present rights-total (second q)))
                pass-rule (if (= resolution :special) [:at-least [2 3]] [:more-than [1 2]])
                passed? (and quorum? (pos? rights-present)
                             (ratio-ok? (first pass-rule) rights-for rights-present (second pass-rule)))]
            {:status (if passed? :passed :failed)
             :resolution resolution
             :quorum-met quorum?
             :reason (cond (not quorum?) :quorum-not-met (not passed?) :insufficient-votes)
             :source (or source :kaisha/a309)}))))))

(defn resolve-board
  "取締役会の決議要件（369条1・2項）。特別の利害関係を有する取締役は数に入れない。

  vote: {:present #{ids} :for #{ids} :interested #{ids}}"
  [c {:keys [present for interested]}]
  (let [directors (set (co/directors c))
        interested (set interested)
        eligible (remove interested directors)
        n (count eligible)
        present-e (filter (set present) eligible)
        for-e (filter (set for) present-e)
        ignored (filter interested (concat present for))]
    (cond
      (not (:company/board? c)) {:status :undetermined :reason :no-board}
      (not (every? directors (concat present for interested)))
      {:status :blocked :reason :unknown-director}
      (zero? n) {:status :blocked :reason :no-eligible-directors :source :kaisha/a369}
      :else
      (let [quorum? (> (* 2 (count present-e)) n)
            passed? (and quorum? (> (* 2 (count for-e)) (count present-e)))]
        {:status (if passed? :passed :failed)
         :quorum-met quorum?
         :reason (cond (not quorum?) :quorum-not-met (not passed?) :insufficient-votes)
         :excluded-interested (vec (distinct ignored))
         :source :kaisha/a369}))))

(defn minutes-retention
  "議事録を本店に備え置く期間の終わり。株主総会は 318条2項、取締役会は 371条1項（いずれも 10 年）。"
  [{:meeting/keys [body date]}]
  (when (d/valid? date)
    {:until (d/add-years date 10)
     :source (if (= body :board) :kaisha/a371 :kaisha/a318)}))

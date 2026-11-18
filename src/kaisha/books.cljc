(ns kaisha.books
  "会計との接点: 残高試算表の取り込みと、計算書類の作成・監査・承認・公告の段階（435〜440条）。

  帳簿そのものは kaikei（network-awai/app-itonami-kaikei）が持つ。Kaisha は作り直さず、
  試算表の残高を受け取って、会社法上の手続きの段階を管理する。"
  (:require [clojure.string :as str]))

(def categories {"資産" :asset "負債" :liability "純資産" :equity "収益" :revenue "費用" :expense
                 "asset" :asset "liability" :liability "equity" :equity "revenue" :revenue "expense" :expense})

(defn- split-csv-line
  "1 行を列に分ける。二重引用符の中のカンマと \"\" を扱う。"
  [line]
  (loop [cs (seq line) cur [] cols [] quoted? false]
    (if-let [ch (first cs)]
      (cond
        (and quoted? (= ch \") (= (second cs) \")) (recur (nnext cs) (conj cur \") cols true)
        (= ch \") (recur (next cs) cur cols (not quoted?))
        (and (= ch \,) (not quoted?)) (recur (next cs) [] (conj cols (apply str cur)) false)
        :else (recur (next cs) (conj cur ch) cols quoted?))
      (conj cols (apply str cur)))))

(defn- parse-amount [s]
  (let [t (str/replace (str/trim s) #"[,\s円]" "")]
    (when (re-matches #"-?\d{1,15}" t)
      #?(:clj (Long/parseLong t) :cljs (js/parseInt t 10)))))

(defn parse-trial-balance
  "「科目,区分,金額」の CSV（1 行目は見出し）を読む。金額は円の整数。
  返り値 {:rows [{:account :category :amount}] :errors [{:line n :code kw}]}"
  [text]
  (let [lines (->> (str/split-lines (or text "")) (map str/trim) (map-indexed vector)
                   (remove (comp str/blank? second)))
        [[_ header] & body] lines
        head (when header (mapv str/trim (split-csv-line header)))]
    (if (not= ["科目" "区分" "金額"] (take 3 head))
      {:rows [] :errors [{:line 1 :code :unexpected-header}]}
      (reduce (fn [acc [i line]]
                (let [[account cat amount] (map str/trim (split-csv-line line))
                      category (get categories cat)
                      n (when amount (parse-amount amount))]
                  (cond
                    (str/blank? account) (update acc :errors conj {:line (inc i) :code :missing-account})
                    (nil? category) (update acc :errors conj {:line (inc i) :code :unknown-category})
                    (nil? n) (update acc :errors conj {:line (inc i) :code :invalid-amount})
                    :else (update acc :rows conj {:account account :category category :amount n}))))
              {:rows [] :errors []}
              body))))

(defn summarize
  "区分ごとの合計。決算整理前の試算表では 資産 = 負債 + 純資産 + (収益 − 費用)。"
  [rows]
  (let [sum (fn [k] (reduce + 0 (map :amount (filter #(= k (:category %)) rows))))
        a (sum :asset) l (sum :liability) e (sum :equity) r (sum :revenue) x (sum :expense)]
    {:assets a :liabilities l :equity e :revenue r :expenses x
     :net-income (- r x)
     :balanced? (= a (+ l e (- r x)))}))

;; ---------------------------------------------------------------- 計算書類の段階

(defn steps
  "会社の機関設計に応じた計算書類の段階。監査役がいなければ監査、取締役会がなければ取締役会の承認はない。"
  [c]
  (cond-> [{:step :prepared :source :kaisha/a435}]
    (:company/auditor? c) (conj {:step :audited :source :kaisha/a436})
    (:company/board? c) (conj {:step :board-approved :source :kaisha/a436})
    true (conj {:step :agm-approved :source :kaisha/a438}
               {:step :published :source :kaisha/a440})))

(defn next-step [c fs]
  (let [done (set (map :step (:fs/history fs)))]
    (first (remove (comp done :step) (steps c)))))

(defn advance
  "次の段階へ進める。段階は飛ばせない。
  - 作成: 試算表が貸借一致していること
  - 取締役会の承認: 可決した取締役会決議の id
  - 総会の承認: :approve-financials を可決した株主総会決議の id
  返り値 {:ok fs} または {:error kw}"
  [c fs {:keys [step on actor resolution]}]
  (let [nxt (next-step c fs)
        summary (summarize (:fs/rows fs))]
    (cond
      (nil? nxt) {:error :already-complete}
      (not= step (:step nxt)) {:error :out-of-order :expected (:step nxt)}
      (and (= step :prepared) (empty? (:fs/rows fs))) {:error :no-trial-balance}
      (and (= step :prepared) (not (:balanced? summary))) {:error :unbalanced-trial-balance}
      (and (= step :board-approved)
           (not (and (= :passed (get-in resolution [:result :status]))
                     (= :approve-financials (:matter resolution)))))
      {:error :board-resolution-required}
      (and (= step :agm-approved)
           (not (and (= :passed (get-in resolution [:result :status]))
                     (= :approve-financials (:matter resolution)))))
      {:error :agm-resolution-required}
      :else {:ok (update fs :fs/history (fnil conj [])
                         (cond-> {:step step :on on :actor actor}
                           resolution (assoc :resolution-id (:id resolution))))})))

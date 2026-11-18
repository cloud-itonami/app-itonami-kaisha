(ns itonami.egov.core
  "Shakai / Kaisha / Matsuri が共有する判定ロジック（AWAI-3147）。

  ここが返すのは「候補」と「人への引継ぎ」だけで、資格判定・受理・行政決定は返さない。
  制度は制度パック（seido/*.edn）として外から渡し、国ごとにコードを複製しない。
  入出力はすべてデータで、ファイル・ネットワーク・時計には触れない（today も引数で渡す）。")

;; ---------------------------------------------------------------------------
;; 日付（YYYY-MM-DD）。cljc で bb / nbb / JVM のどれでも同じ結果にするため、
;; 標準ライブラリの日付型を使わずに暦日を整数に変換する。

(defn- parse-int [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn- days-from-civil [y m d]
  (let [y (if (<= m 2) (dec y) y)
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))
        mp (mod (+ m 9) 12)
        doy (+ (quot (+ (* 153 mp) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- civil-from-days [z]
  (let [z (+ z 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        doy (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (if (< mp 10) (+ mp 3) (- mp 9))
        y (+ yoe (* era 400))]
    [(if (<= m 2) (inc y) y) m d]))

(defn- pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (- w (count s)) "0")) s)))

(defn date->day
  "\"2026-11-18\" -> 暦日の通し番号。形式が違えば nil。"
  [s]
  (when (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}" s))
    (let [[y m d] (map parse-int (re-seq #"\d+" s))]
      (when (and (<= 1 m 12) (<= 1 d 31))
        (let [n (days-from-civil y m d)]
          ;; 2026-02-30 のような存在しない日付を弾く
          (when (= [y m d] (civil-from-days n)) n))))))

(defn day->date [n]
  (let [[y m d] (civil-from-days n)]
    (str (pad y 4) "-" (pad m 2) "-" (pad d 2))))

(defn add-days [date n]
  (some-> (date->day date) (+ n) day->date))

(defn- after?
  "a が b より後か。どちらかの日付が読めなければ nil（呼び出し側で先に検査する）。"
  [a b]
  (let [x (date->day a) y (date->day b)]
    (and x y (> x y))))

;; ---------------------------------------------------------------------------
;; 結果の形

(defn blocked
  "止める。理由を残し、候補も提出も出さない。"
  [reason & {:as detail}]
  (merge {:status :blocked :reason reason} detail))

(defn undetermined
  "結論を出さない（未確定）。制度が収録されていない・事実が足りないとき。"
  [reason & {:as detail}]
  (merge {:status :undetermined :reason reason} detail))

(defn stopped? [r] (contains? #{:blocked :undetermined} (:status r)))

;; ---------------------------------------------------------------------------
;; 制度パック

(defn pack-freshness
  "制度パックの鮮度。review-by を過ぎたら :stale、checked-on より前の日付で
  使おうとしたら :not-yet-checked。"
  [pack today]
  (cond
    (nil? (date->day today)) :unknown-today
    (or (nil? (date->day (:pack/checked-on pack)))
        (nil? (date->day (:pack/review-by pack)))) :invalid-pack-dates
    (after? (:pack/checked-on pack) today) :not-yet-checked
    (after? today (:pack/review-by pack)) :stale
    :else :current))

(defn find-pack [packs country]
  (first (filter #(= [country] (:pack/jurisdiction %)) packs)))

(defn resolve-jurisdiction
  "場所 {:country :subdivision} に使える制度パックを選ぶ。使えなければ停止結果を返す。"
  [packs today {:keys [country subdivision] :as place}]
  (let [pack (find-pack packs country)]
    (cond
      (nil? country) (undetermined :jurisdiction-missing)
      (nil? pack) (undetermined :unsupported-jurisdiction :jurisdiction country)
      (and (:pack/requires-subdivision pack) (nil? subdivision))
      (undetermined :subdivision-required :jurisdiction country)
      (and subdivision (not (contains? (:pack/subdivisions pack) subdivision)))
      (undetermined :unknown-subdivision :jurisdiction country :subdivision subdivision)
      :else
      (let [fresh (pack-freshness pack today)]
        (if (= :current fresh)
          {:status :resolved :pack pack :place place}
          (blocked :pack-not-current :freshness fresh :pack-id (:pack/id pack)
                   :review-by (:pack/review-by pack)))))))

(defn- source-refs [pack ids]
  (mapv (fn [id]
          (let [s (get-in pack [:pack/sources id])]
            (assoc (select-keys s [:title :issuer :url :law-revision-id :in-force-from
                                   :retrieved-on :sha256])
                   :id id)))
        ids))

(defn- deadline [{:keys [kind days anchor]} facts]
  (let [base (get facts anchor)]
    (case kind
      :within-days {:kind :within-days :days days :from base :due (add-days base days)}
      :before {:kind :before :due-before base}
      {:kind :unknown})))

(defn- submit-to [where facts]
  (if (map? where)
    where ; 国の機関など、場所によらない提出先（例: {:agency :se/skatteverket :name "Skatteverket"}）
    (case where
      :from-municipality (get-in facts [:from :subdivision])
      :to-municipality (get-in facts [:to :subdivision])
      :registry-office {:registry-office-for (get-in facts [:to :subdivision])}
      nil)))

(defn- candidate [pack facts proc]
  {:procedure/id (:procedure/id proc)
   :procedure/title (:procedure/title proc)
   :standing :candidate ; 候補であって、当局の判断ではない
   :deadline (deadline (:deadline proc) facts)
   :submit-to (submit-to (:submit-to proc) facts)
   :sources (source-refs pack (:sources proc))
   :unknowns (vec (:unknowns proc))})

(defn- review-gate [pack]
  (if (= :reviewed (:review/status pack))
    :allowed-after-user-check
    :withheld-unreviewed-pack))

;; ---------------------------------------------------------------------------
;; 権限

(defn grant-allows?
  "grant が principal について action を許すか。本人（:self）か、期限内の委任だけを認める。"
  [grant principal action today]
  (and (map? grant)
       (= principal (:principal grant))
       (contains? (set (:scope grant)) action)
       (or (= :self (:actor grant))
           (and (= :delegate (:actor grant))
                (some? (date->day (:expires grant)))
                (some? (date->day today))
                (not (after? today (:expires grant)))))))

;; ---------------------------------------------------------------------------
;; 手続きの適用条件

(defn applies?
  "手続きの :applies-when（1 つのキーワードか、その集合）が場面 situation に当たるか。"
  [applies-when situation]
  (if (set? applies-when)
    (contains? applies-when situation)
    (= applies-when situation)))

;; ---------------------------------------------------------------------------
;; Shakai（国民側：個人・家族）

(defn shakai-plan
  "住所変更の候補手続きを出す。facts の例:
  {:person \"fixture-person-1\" :event :move
   :from {:country :jp :subdivision :jp.fixture/city-a}
   :to   {:country :jp :subdivision :jp.fixture/city-b}
   :moved-on \"2026-11-20\" :address-confirmed? true}"
  [{:keys [packs today grant facts]}]
  (let [{:keys [person from to moved-on address-confirmed?]} facts]
    (cond
      (not (grant-allows? grant person :plan today)) (blocked :no-authority)
      (not= (:country from) (:country to)) (undetermined :cross-border-not-supported)
      (not address-confirmed?) (undetermined :ambiguous-address)
      (nil? (date->day moved-on)) (undetermined :moved-on-missing)
      :else
      (let [ctx-from (resolve-jurisdiction packs today from)
            ctx-to (resolve-jurisdiction packs today to)]
        (cond
          (stopped? ctx-from) ctx-from
          (stopped? ctx-to) ctx-to
          :else
          (let [pack (:pack ctx-to)
                when* (if (= (:subdivision from) (:subdivision to))
                        :move-within-municipality
                        :move-between-municipalities)
                procs (filter #(and (= :person (:subject %)) (applies? (:applies-when %) when*))
                              (:pack/procedures pack))
                refs (get-in pack [:pack/subdivisions (:subdivision to) :references])]
            (if (empty? procs)
              (undetermined :no-procedure-in-pack :applies-when when*)
              {:status :candidates
               :principal person
               :applies-when when*
               :candidates (mapv #(candidate pack facts %) procs)
               :pack {:id (:pack/id pack) :version (:pack/version pack)
                      :review (:review/status pack) :review-by (:pack/review-by pack)}
               :submission-proposal (review-gate pack)
               ;; 転入先の市町村の条例・方針。手続きではなく、所在を示す参考情報。
               :local-references (vec refs)
               :note "候補の一覧であり、届出の要否や受理を決めるのは当局。"})))))))

;; ---------------------------------------------------------------------------
;; Kaisha（国民側：法人・団体）

(defn kaisha-plan
  "本店移転の候補手続きを出す。代表権の確認が取れていなければ止める。"
  [{:keys [packs today grant facts]}]
  (let [{:keys [organization form from to moved-on address-confirmed?
                representative-confirmed?]} facts]
    (cond
      (not (grant-allows? grant organization :plan today)) (blocked :no-authority)
      (not representative-confirmed?) (blocked :representative-unconfirmed)
      (not= (:country from) (:country to)) (undetermined :cross-border-not-supported)
      (not address-confirmed?) (undetermined :ambiguous-address)
      (nil? (date->day moved-on)) (undetermined :moved-on-missing)
      :else
      (let [ctx (resolve-jurisdiction packs today to)]
        (if (stopped? ctx)
          ctx
          (let [pack (:pack ctx)
                procs (filter #(and (= form (:subject %)) (applies? (:applies-when %) :head-office-move))
                              (:pack/procedures pack))]
            (if (empty? procs)
              (undetermined :unsupported-entity-form :form form)
              {:status :candidates
               :principal organization
               :candidates (mapv #(candidate pack facts %) procs)
               :pack {:id (:pack/id pack) :version (:pack/version pack)
                      :review (:review/status pack) :review-by (:pack/review-by pack)}
               :submission-proposal (review-gate pack)
               :note "候補の一覧であり、登記の受否は登記官が判断する。"})))))))

;; ---------------------------------------------------------------------------
;; 共有（Shakai -> Matsuri）。本人の同意に束縛された最小限の受け渡し票。

(def shareable-items #{:principal-ref :from :to :moved-on :procedures})

(defn share-packet
  "本人の同意どおりの項目だけを受け渡し票にする。送信そのものは別の操作で、
  ブラウザ側の操作ごとの同意を通す（ここでは送らない）。"
  [{:keys [plan facts consent today]}]
  (cond
    (not= :candidates (:status plan)) (blocked :plan-not-ready)
    (not= :approved (:decision consent)) (blocked :consent-denied)
    (nil? (:recipient consent)) (blocked :consent-missing-recipient)
    (nil? (date->day (:expires consent))) (blocked :consent-missing-expiry)
    (after? today (:expires consent)) (blocked :consent-expired)
    :else
    (let [items (filter shareable-items (:items consent))
          values {:principal-ref (:principal plan)
                  :from (:from facts)
                  :to (:to facts)
                  :moved-on (:moved-on facts)
                  :procedures (mapv :procedure/id (:candidates plan))}]
      {:status :packet
       :packet/recipient (:recipient consent)
       :packet/purpose (:purpose consent)
       :packet/expires (:expires consent)
       :packet/pack (:pack plan)
       :packet/items (select-keys values items)})))

;; ---------------------------------------------------------------------------
;; Matsuri（政府側：担当者の補助）

(def ^:private checklists
  {:jp/tennyu-todoke ["届出事項（住民基本台帳法 第22条第1項各号）が揃っているかを担当者が確認する"
                      "住所の異動に関する文書（同条第2項）の添付を担当者が確認する"
                      "転入日から14日以内かを担当者が確認し、過ぎていれば扱いを担当者が判断する"]
   :jp/tenkyo-todoke ["届出事項（住民基本台帳法 第23条各号）が揃っているかを担当者が確認する"
                      "転居日から14日以内かを担当者が確認する"]
   :jp/tenshutsu-todoke ["転出先と転出予定日（住民基本台帳法 第24条）を担当者が確認する"]})

(defn matsuri-intake
  "受け渡し票を受付準備まで整え、人の審査者へ引き継ぐ。受理・審査・決定はしない。"
  [{:keys [packet agency staff packs today]}]
  (let [here (:jurisdiction agency)]
    (cond
      (not (:confirmed? agency)) (blocked :agency-unconfirmed)
      (not= (:id agency) (:agency staff)) (blocked :no-authority :why :staff-not-in-agency)
      (not (contains? (set (:roles staff)) :intake)) (blocked :no-authority :why :missing-intake-role)
      (not= :packet (:status packet)) (blocked :no-packet)
      (not= (:id agency) (:packet/recipient packet)) (blocked :wrong-recipient)
      (nil? (date->day (:packet/expires packet))) (blocked :consent-missing-expiry)
      (after? today (:packet/expires packet)) (blocked :consent-expired)
      :else
      (let [ctx (resolve-jurisdiction packs today here)
            items (:packet/items packet)
            mine (filter (fn [pid]
                           (let [proc (some #(when (= pid (:procedure/id %)) %)
                                            (:pack/procedures (:pack ctx)))]
                             (= (:subdivision here)
                                (submit-to (:submit-to proc) items))))
                         (:procedures items))]
        (cond
          (stopped? ctx) ctx
          (nil? (:procedures items)) (undetermined :procedures-not-shared)
          (empty? mine) (undetermined :not-this-agency)
          :else
          {:status :ready-for-human-review
           :decision nil ; 決定は人が行う
           :agency (:id agency)
           :prepared-by (:id staff)
           :procedures (vec mine)
           :checklist (vec (mapcat #(get checklists % ["この手続きの確認項目は未収録。担当者が確認する"]) mine))
           :handoff {:to :human-reviewer :role :case-reviewer}
           :pack (:packet/pack packet)
           :note "受付準備まで。受理・不受理・審査・通知の決定は担当者が行う。"})))))

;; ---------------------------------------------------------------------------
;; 提出後の状態

(defn submission-status
  "提出したかと受付の証跡から状態を返す。受付の証跡がなければ完了扱いにしない。"
  [{:keys [submitted? receipt]}]
  (cond
    (not submitted?) :not-submitted
    (nil? receipt) :submitted-unconfirmed
    :else :receipt-recorded))

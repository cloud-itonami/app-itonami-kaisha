(ns kaisha.ui
  "画面。構成は Figma の提案（umqCqP5KSjLWh9fhCI1HaN、ページ 319:2、AWAI-3445）に合わせる。
  状態は kaisha.app の純粋な関数で更新し、端末の localStorage にだけ保存する。
  外部への通信はしない（CSP connect-src は ipc のみ）。"
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [kaisha.app :as app]
            [kaisha.books :as books]
            [kaisha.chat :as chat]
            [kaisha.company :as co]
            [kaisha.date :as d]
            [kaisha.deadlines :as dl]
            [kaisha.i18n :as i18n]
            [kaisha.law :as law]
            [kaisha.meeting :as mt]
            [kaisha.ringi :as ringi]))

(def storage-key "itonami.kaisha.state.v1")

(defonce ui (atom {:tab :home :lang :ja :error nil :storage? true}))
(defonce state (atom nil))

;; ---------------------------------------------------------------- 保存

(defn- load-state []
  (try
    (let [raw (.getItem js/localStorage storage-key)
          s (when raw (reader/read-string raw))]
      (if (app/valid-state? s) s (app/sample)))
    (catch :default _
      (swap! ui assoc :storage? false)
      (app/sample))))

(defn- save! [s]
  (try (.setItem js/localStorage storage-key (pr-str s))
       (catch :default _ (swap! ui assoc :storage? false))))

;; ---------------------------------------------------------------- 小道具

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn- today []
  (let [d (js/Date.)]
    (str (.getFullYear d) "-" (pad2 (inc (.getMonth d))) "-" (pad2 (.getDate d)))))

(defn- now []
  (let [d (js/Date.)]
    (str (today) "T" (pad2 (.getHours d)) ":" (pad2 (.getMinutes d)) ":" (pad2 (.getSeconds d)))))

(defn- t ([k] (i18n/t (:lang @ui) k)) ([k p] (i18n/t (:lang @ui) k p)))
(defn- has-msg? [k] (some? (get-in i18n/messages [(:lang @ui) k])))

(defn- num-str [n] (.format (js/Intl.NumberFormat. (if (= :ja (:lang @ui)) "ja-JP" "en-US")) n))
(defn- yen [n] (t :common/yen {:amount (num-str n)}))

(defn- h
  "最小の DOM 組み立て。[tag attrs & children]。attrs の :on-* はイベント。"
  [x]
  (cond
    (nil? x) nil
    (string? x) (.createTextNode js/document x)
    (number? x) (.createTextNode js/document (str x))
    (seq? x) (let [f (.createDocumentFragment js/document)]
               (doseq [c x] (when-let [n (h c)] (.appendChild f n))) f)
    (vector? x)
    (let [[tag & more] x
          [attrs children] (if (map? (first more)) [(first more) (rest more)] [{} more])
          el (.createElement js/document (name tag))]
      (doseq [[k v] attrs :when (some? v)]
        (let [k (name k)]
          (cond
            (str/starts-with? k "on-") (.addEventListener el (subs k 3) v)
            (= k "class") (set! (.-className el) v)
            (= k "value") (set! (.-value el) v)
            (= k "checked") (set! (.-checked el) (boolean v))
            (true? v) (.setAttribute el k "")
            (false? v) nil
            :else (.setAttribute el k (str v)))))
      (doseq [c children] (when-let [n (h c)] (.appendChild el n)))
      el)
    :else (.createTextNode js/document (str x))))

(declare render!)

(defn- act!
  "操作を適用する。拒まれたら理由を画面に出し、状態は変えない。成功なら true。"
  [ev]
  (let [ev (merge {:on (today) :at (now)} ev)
        res (app/dispatch @state ev)]
    (if (:error res)
      (swap! ui assoc :error (:error res))
      (do (reset! state (:state res)) (save! (:state res)) (swap! ui assoc :error nil)))
    (render!)
    (not (:error res))))

(defn- go! [& kvs] (apply swap! ui assoc :error nil kvs) (render!) (.scrollTo js/window 0 0))

(defn- form-values [^js e]
  (.preventDefault e)
  (let [fd (js/FormData. (.-target e))]
    (into {} (map (fn [[k v]] [(keyword k) v])) (js->clj (js/Array.from (.entries fd))))))

;; ---------------------------------------------------------------- 部品（Figma の DS / Kaisha 部品に対応）

(defn- icon [nm] [:span {:class (str "icon icon--" nm) :aria-hidden "true"}])

(defn- chip
  "DS / Chip。Tone = :warning :info :up :down :neutral"
  [tone label] [:span {:class (str "chip chip--" (name tone))} label])

(defn- banner
  "DS / Banner。Tone = :warning :error :info"
  [tone title body]
  [:div {:class (str "banner banner--" (name tone)) :role (when (= tone :error) "alert")}
   [:strong {:class "banner__title"} title]
   (when body [:p {:class "banner__body"} body])])

(defn- stat [label value] [:div {:class "stat"} [:span {:class "stat__label"} label] [:span {:class "stat__value num"} value]])

(defn- btn
  ([style label on-click] (btn style label on-click {}))
  ([style label on-click {:keys [destructive? disabled? type]}]
   [:button {:class (str "awai-button awai-button--" (name style))
             :data-destructive (when destructive? "true")
             :disabled disabled?
             :type (or type "button")
             :on-click on-click} label]))

(defn- field
  "DS / Field: ラベルと、単位つきの入力欄。"
  [label input & [unit]]
  [:label {:class "field"}
   [:span {:class "field__label"} label]
   [:span {:class "field__box"} input (when unit [:span {:class "field__unit"} unit])]])

(defn- select [nm options & [selected]]
  [:select {:name nm}
   (for [[v label] options] [:option {:value (name v) :selected (= v selected)} label])])

(defn- card [& children] (into [:section {:class "card"}] children))
(defn- h2 [s] [:h2 s])
(defn- caption [s & [cls]] [:p {:class (str "caption " (or cls "muted"))} s])
(defn- source-line [id] (when id (caption (t :common/source-inline {:law (law/short-title id)}))))

(defn- member-name [id]
  (or (:member/name (app/find-by :member/id id (get-in @state [:company :company/members]))) id))

(defn- matter-label [body matter]
  (t (if (and (= body :board) (= matter :approve-financials))
       :matter/approve-financials-board
       (keyword "matter" (name matter)))))

;; ---------------------------------------------------------------- 予定（K1・S1・S2）

(defn- deadline-row [{:keys [kind date params estimated overdue source]}]
  [:li {:class "deadline"}
   [:div {:class "deadline__date"}
    [:strong {:class "num"} date]
    (cond overdue (chip :warning (t :home/overdue))
          estimated (chip :neutral (t :home/estimated)))]
   [:div {:class "deadline__body"}
    [:span {:class "deadline__title"}
     (t (keyword "deadline" (name kind))
        (cond-> params (:step params) (update :step #(t (keyword "step" (name %))))))]
    (source-line source)]])

(defn- officer-card [o]
  (let [c (:company @state)
        term (co/officer-term c o)
        role (co/term-role o)
        rep (when (some #{:representative-director} (:officer/roles o)) (co/representative? c (:officer/id o)))]
    (card
     [:strong {:class "card__title"} (:officer/name o)]
     (caption (t :officer/term {:role (str/join "・" (map #(t (keyword "role" (name %))) (:officer/roles o)))
                                :years (co/term-years c role) :elected (:officer/elected-on o)}))
     (when (= :ok (:status term)) (caption (t :officer/expiry {:date (:latest-expiry term)})))
     (when rep
       (if (= :ok (:status rep))
         (caption (t :officer/representative-ok {:date (:officer/authority-verified-on o)}))
         (list [:div {:class "chips"} (chip :warning (t :officer/representative-unverified))]
               (btn :tonal (t :officer/verify) #(act! {:type :officer/verify :id (:officer/id o)}))))))))

(defn- home-view []
  (let [s @state
        items (dl/upcoming s (today) 400)]
    (list
     [:div {:class "chips"}
      (when (:fixture s) (chip :warning (t :app/fixture)))
      (when (not= :reviewed (:review/status law/pack)) (chip :info (t :app/review)))]
     (h2 (t :home/heading))
     (if (seq items)
       [:ul {:class "deadlines"} (map deadline-row items)]
       (card [:strong (t :home/empty)] (caption (t :home/hint-empty))))
     [:details {:class "disclosure"}
      [:summary {:class "awai-button awai-button--text"} (str "＋ " (t :home/registration-add))]
      [:form {:class "form" :on-submit #(let [v (form-values %)]
                                          (act! {:type :registration/add :title (:title v) :change-on (:on v)}))}
       (field (t :home/registration-title) [:input {:name "title" :required true}])
       (field (t :home/registration-on) [:input {:name "on" :type "date" :required true}])
       (btn :primary (t :common/add) nil {:type "submit"})]]
     (h2 (t :home/officers))
     (map officer-card (get-in s [:company :company/officers])))))

;; ---------------------------------------------------------------- 会議（K2・S3）

(defn- result-chip [m a]
  (let [r (:agenda/result a) v (:agenda/vote a)
        passed? (= :passed (:status r))
        params {:mark (if passed? "✓" "✕") :result (t (keyword "result" (name (:status r))))
                :for (if (= :board (:meeting/body m)) (count (:for v)) (num-str (:rights-for v)))}]
    (chip (if passed? :up :down)
          (if (= :board (:meeting/body m))
            (t :meetings/result-board (assoc params :present (count (remove (set (:interested v)) (:present v)))
                                             :eligible (count (remove (set (:interested v)) (co/directors (:company @state))))))
            (t :meetings/result-shareholders (assoc params :present (num-str (:rights-present v)) :total (num-str (:rights-total v))))))))

(defn- agenda-item [m a notice]
  [:div {:class "agenda"}
   [:strong (:agenda/title a)]
   (caption (matter-label (:meeting/body m) (:agenda/matter a)))
   (cond
     (:agenda/result a) [:div {:class "chips"} (result-chip m a)]
     (= :ok (:status notice)) (btn :tonal (t :meetings/record) #(go! :vote {:meeting (:meeting/id m) :agenda (:agenda/id a)}))
     (= :pending (:status notice)) (caption (t :meetings/vote-after-notice)))])

(defn- meeting-card [m]
  (let [c (:company @state)
        n (mt/notice-check c m)
        ret (mt/minutes-retention m)
        law-id (:source n)]
    (card
     (when (= :blocked (:status n))
       (banner :error (t :meetings/late-title)
               (t :meetings/late-body {:date (:latest-send-date n) :law (law/short-title law-id)})))
     [:strong {:class "card__title"} (:meeting/title m)]
     (caption (str (t (keyword "body" (name (:meeting/body m)))) " · " (:meeting/date m)))
     (case (:status n)
       :pending (list [:div {:class "chips"} (chip :warning (t :meetings/notice-pending {:date (:latest-send-date n) :days (:required-days n)}))]
                      (btn :tonal (t :meetings/notice-sent) #(act! {:type :meeting/notice-sent :id (:meeting/id m)})))
       :ok (caption (if (:waived n)
                      (t :meetings/notice-waived)
                      (t :meetings/notice-sent-on {:date (:meeting/notice-sent-on m) :law (law/short-title law-id)}))
                    "text")
       :blocked (list [:div {:class "chips"} (chip :warning (t :meetings/notice-chip-late {:sent (:meeting/notice-sent-on m) :date (:latest-send-date n)}))]
                      (caption (t :meetings/late-note)))
       nil)
     (map #(agenda-item m % n) (:meeting/agenda m))
     (when ret (caption (str (t :meetings/retention {:date (:until ret)}) "（" (law/short-title (:source ret)) "）"))))))

(defn- meetings-view []
  (let [s @state c (:company s)]
    (list
     (h2 (t :meetings/heading))
     (map meeting-card (sort-by :meeting/date (:meetings s)))
     [:details {:class "disclosure"}
      [:summary {:class "awai-button awai-button--text"} (str "＋ " (t :meetings/add))]
      [:form {:class "form"
              :on-submit #(let [v (form-values %)]
                            (act! {:type :meeting/add
                                   :meeting {:meeting/id (str "m-" (now))
                                             :meeting/body (keyword (:body v))
                                             :meeting/title (:title v)
                                             :meeting/date (:date v)
                                             :meeting/agenda [{:agenda/id "a1" :agenda/title (:agenda v)
                                                               :agenda/matter (keyword (:matter v))}]}}))}
       (field (t :meetings/body) (select "body" (cond-> [[:shareholders (t :body/shareholders)]]
                                                  (:company/board? c) (conj [:board (t :body/board)]))))
       (field (t :meetings/title) [:input {:name "title" :required true}])
       (field (t :meetings/date) [:input {:name "date" :type "date" :required true}])
       (field (t :meetings/agenda-title) [:input {:name "agenda" :required true}])
       (field (t :meetings/matter)
              (select "matter" (concat (for [k (keys mt/matters)] [k (matter-label :shareholders k)])
                                       (when (:company/board? c)
                                         (for [k (keys mt/board-matters) :when (not= k :approve-financials)]
                                           [k (matter-label :board k)])))))
       (btn :primary (t :common/add) nil {:type "submit"})]])))

;; ---------------------------------------------------------------- 決議の記録（K3・S4・S8）

(defn- rule-label [resolution which]
  (case which
    :quorum (t :vote/rule-majority)
    :pass (if (= resolution :special) (t :vote/rule-two-thirds) (t :vote/rule-majority))))

(defn- parse-count [s] (let [n (js/parseInt s 10)] (when-not (js/isNaN n) n)))

(defn- preview-shareholders
  "入力中の票数から、必要な数と判定の見込みを返す（画面の一部を差し替える）。"
  [c matter total present for]
  (let [th (mt/shareholder-thresholds c matter total present)
        r (when (and total present for)
            (mt/resolve-shareholders c {:matter matter :rights-total total :rights-present present :rights-for for}))]
    {:stats [(stat (t :vote/quorum {:rule (rule-label (:resolution th) :quorum)})
                   (if (:min-present th) (t :vote/at-least {:n (num-str (:min-present th))}) "—"))
             (stat (t :vote/pass {:rule (rule-label (:resolution th) :pass)})
                   (if (:min-for th) (t :vote/at-least {:n (num-str (:min-for th))}) "—"))]
     :chip (cond
             (nil? r) (chip :neutral (t :vote/preview-empty))
             (= :blocked (:status r)) (chip :warning (t :vote/preview-invalid))
             (= :passed (:status r)) (chip :up (t :vote/preview-pass))
             (= :quorum-not-met (:reason r)) (chip :down (t :vote/preview-quorum {:present (num-str present) :need (num-str (:min-present th))}))
             :else (chip :down (t :vote/preview-votes {:for (num-str for) :need (num-str (:min-for th))})))
     :failed? (= :failed (:status r))}))

(defn- preview-board [c present for interested]
  (let [eligible-present (count (remove interested present))
        th (mt/board-thresholds c interested eligible-present)
        r (when (seq present) (mt/resolve-board c {:present present :for for :interested interested}))]
    {:stats [(stat (t :vote/eligible) (t :common/people {:n (:eligible th)}))
             (stat (t :vote/quorum {:rule (t :vote/rule-majority)}) (t :vote/at-least-people {:n (:min-present th)}))
             (stat (t :vote/pass {:rule (t :vote/rule-majority)}) (if (:min-for th) (t :vote/at-least-people {:n (:min-for th)}) "—"))]
     :chip (cond
             (nil? r) (chip :neutral (t :vote/preview-empty))
             (= :passed (:status r)) (chip :up (t :vote/preview-pass))
             (= :quorum-not-met (:reason r)) (chip :down (t :vote/preview-quorum {:present eligible-present :need (:min-present th)}))
             :else (chip :down (t :vote/preview-votes {:for (count (remove interested for)) :need (:min-for th)})))
     :failed? (= :failed (:status r))}))

(defn- refresh-preview! [^js form board? matter]
  (let [c (:company @state)
        fd (js/FormData. form)
        p (if board?
            (let [pick (fn [k] (set (js->clj (.getAll fd k))))]
              (preview-board c (pick "present") (pick "for") (pick "interested")))
            (preview-shareholders c matter (parse-count (.get fd "total")) (parse-count (.get fd "present")) (parse-count (.get fd "for"))))]
    (.replaceChildren (.querySelector form "[data-stats]") (h (seq (:stats p))))
    (.replaceChildren (.querySelector form "[data-preview]") (h (:chip p)))
    (set! (.-textContent (.querySelector form "[data-submit]"))
          (if (:failed? p) (t :meetings/record-failed) (t :meetings/record)))))

(defn- vote-view []
  (let [{:keys [meeting agenda]} (:vote @ui)
        c (:company @state)
        m (app/find-by :meeting/id meeting (:meetings @state))
        a (app/find-by :agenda/id agenda (:meeting/agenda m))
        board? (= :board (:meeting/body m))
        n (mt/notice-check c m)
        th (when-not board? (mt/shareholder-thresholds c (:agenda/matter a) 1 nil))
        p (if board? (preview-board c #{} #{} #{}) (preview-shareholders c (:agenda/matter a) nil nil nil))
        law-id (if board? :kaisha/a369 (or (:source th) :kaisha/a309))]
    (cond
      (nil? a) nil
      (= :undetermined (:status th))
      (list (banner :info (t :vote/undetermined-title) (t :vote/undetermined-body))
            (caption (t :vote/disclaimer {:law (law/short-title :kaisha/a309)})))
      :else
      (list
       [:div {:class "chips"}
        (chip :up (if (:waived n) (t :meetings/notice-waived) (t :meetings/notice-chip-ok {:date (:meeting/notice-sent-on m)})))]
       [:form {:class "form"
               :on-input #(refresh-preview! (.-currentTarget %) board? (:agenda/matter a))
               :on-change #(refresh-preview! (.-currentTarget %) board? (:agenda/matter a))
               :on-submit
               (fn [e]
                 (.preventDefault e)
                 (let [fd (js/FormData. (.-target e))
                       pick (fn [k] (set (js->clj (.getAll fd k))))
                       num (fn [k] (parse-count (.get fd k)))]
                   (when (act! {:type :meeting/vote :id meeting :agenda agenda
                                :vote (if board?
                                        {:present (pick "present") :for (pick "for") :interested (pick "interested")}
                                        {:rights-total (num "total") :rights-present (num "present") :rights-for (num "for")})})
                     (go! :vote nil :tab :meetings))))}
        (if board?
          (for [[k label] [["present" :meetings/present] ["for" :meetings/for] ["interested" :meetings/interested]]]
            [:fieldset {:class "checks"} [:legend (t label)]
             (for [d (co/directors c)] [:label [:input {:type "checkbox" :name k :value d}] (member-name d)])])
          (for [[k label] [["total" :meetings/rights-total] ["present" :meetings/rights-present] ["for" :meetings/rights-for]]]
            (field (t label) [:input {:name k :type "number" :min 0 :step 1 :required true :inputmode "numeric"}]
                   (t :common/unit-votes))))
        [:div {:class "stats-row" :data-stats true} (seq (:stats p))]
        [:div {:class "chips" :data-preview true} (:chip p)]
        (caption (t :vote/disclaimer {:law (law/short-title law-id)}))
        [:button {:class "awai-button awai-button--primary" :type "submit" :data-submit true} (t :meetings/record)]]))))

;; ---------------------------------------------------------------- 稟議（K4・S5・S6）

(defn- passed-board-items [category]
  (for [m (:meetings @state) :when (= :board (:meeting/body m))
        a (:meeting/agenda m) :when (and (= :passed (get-in a [:agenda/result :status]))
                                         (= category (:agenda/matter a)))]
    [(str (:meeting/id m) "|" (:agenda/id a)) (str (:meeting/title m) " — " (:agenda/title a))]))

(defn- status-chip [st]
  (case st
    :approved (chip :up (str "✓ " (t :status/approved)))
    :rejected (chip :down (str "✕ " (t :status/rejected)))
    :awaiting-board (chip :info (t :status/awaiting-board))
    (chip :neutral (t (keyword "status" (name st))))))

(defn- ringi-card [r]
  (let [s @state c (:company s)
        st (ringi/status c r)
        {:keys [approvers board-resolution? source]} (ringi/route c r)
        approved-by (map :by (filter #(= :approve (:decision %)) (:ringi/decisions r)))
        me (:me s)
        rep (some #(when (some #{:representative-director :representative-member} (:officer/roles %)) %) (:company/officers c))]
    (card
     [:div {:class "card__head"} [:strong {:class "card__title"} (:ringi/title r)] (status-chip st)]
     [:p {:class "amount num"} (yen (:ringi/amount r))]
     (caption (str (t (keyword "category" (name (:ringi/category r)))) " · "
                   (t :ringi/drafter {:name (str (member-name (:ringi/drafter r)) (when (= me (:ringi/drafter r)) (t :ringi/you)))})))
     (caption (t :ringi/approvers {:have (count approved-by) :need approvers
                                   :names (if (seq approved-by) (str "（" (str/join "・" (map member-name approved-by)) "）") "")})
              "text")
     (when board-resolution?
       (if-let [res (:ringi/board-resolution r)]
         (caption (t :ringi/board-linked {:title (let [[mid aid] (str/split (:id res) #"/")
                                                       m (app/find-by :meeting/id mid (:meetings s))]
                                                   (str (:meeting/title m) " — " (:agenda/title (app/find-by :agenda/id aid (:meeting/agenda m)))))})
                  "text")
         [:div {:class "chips"} (chip :warning (str (t :ringi/needs-board)))]))
     (case st
       :draft (when (= me (:ringi/drafter r))
                (btn :primary (t :ringi/submit) #(act! {:type :ringi/submit :id (:ringi/id r)})))
       :pending [:div {:class "actions"}
                 (btn :primary (t :ringi/approve) #(act! {:type :ringi/decide :id (:ringi/id r) :decision :approve}))
                 (btn :primary (t :ringi/reject) #(act! {:type :ringi/decide :id (:ringi/id r) :decision :reject}) {:destructive? true})]
       :awaiting-board
       (let [opts (passed-board-items (:ringi/category r))]
         (if (seq opts)
           [:form {:class "form" :on-submit #(let [[mid aid] (str/split (:ref (form-values %)) #"\|")]
                                               (act! {:type :ringi/link-board :id (:ringi/id r) :meeting mid :agenda aid}))}
            (field (t :ringi/link-field {:category (t (keyword "category" (name (:ringi/category r))))}) (select "ref" opts) "▾")
            (btn :tonal (t :ringi/link) nil {:type "submit"})]
           (caption (t :ringi/no-board-items))))
       :approved (when rep
                   (let [sc (ringi/signer-check c r (:officer/id rep))]
                     (if (= :ok (:status sc))
                       (caption (t :ringi/signer-ok {:name (:officer/name rep) :date (:officer/authority-verified-on rep)}) "text")
                       (list (banner :warning (t :ringi/signer-unverified-title) (t :ringi/signer-unverified-body {:name (:officer/name rep)}))
                             (btn :tonal (t :officer/verify) #(act! {:type :officer/verify :id (:officer/id rep)}))))))
       nil)
     (source-line source))))

(defn- ringi-view []
  (list
   (h2 (t :ringi/heading))
   (map ringi-card (reverse (:ringi @state)))
   [:details {:class "disclosure"}
    [:summary {:class "awai-button awai-button--primary"} (t :ringi/new)]
    [:form {:class "form"
            :on-submit #(let [v (form-values %)]
                          (act! {:type :ringi/create
                                 :ringi {:ringi/id (str "r-" (now)) :ringi/title (:title v)
                                         :ringi/amount (js/parseInt (:amount v) 10)
                                         :ringi/category (keyword (:category v))}}))}
     (field (t :ringi/title) [:input {:name "title" :required true}])
     (field (t :ringi/amount) [:input {:name "amount" :type "number" :min 0 :step 1 :required true :inputmode "numeric"}] "円")
     (field (t :ringi/category) (select "category" (for [k [:ordinary :important-asset :large-borrowing :important-employee :branch]]
                                                     [k (t (keyword "category" (name k)))])) "▾")
     (btn :primary (t :common/add) nil {:type "submit"})]]))

;; ---------------------------------------------------------------- 会計（K5・S7）

(defn- resolutions-for [step]
  (for [m (:meetings @state)
        :when (= (:meeting/body m) (if (= step :board-approved) :board :shareholders))
        a (:meeting/agenda m) :when (and (= :passed (get-in a [:agenda/result :status]))
                                         (= :approve-financials (:agenda/matter a)))]
    [(str (:meeting/id m) "|" (:agenda/id a)) (str (:meeting/title m) " — " (:agenda/title a))]))

(defn- step-detail [h]
  (let [res (when-let [rid (:resolution-id h)]
              (let [[mid] (str/split rid #"/")] (:meeting/title (app/find-by :meeting/id mid (:meetings @state)))))]
    (str/join " · " (remove nil? [(:on h) (member-name (:actor h)) res]))))

(defn- import-form []
  [:form {:class "form" :on-submit #(act! {:type :books/import :csv (:csv (form-values %))})}
   (caption (t :books/import-hint))
   [:textarea {:name "csv" :rows 8 :required true :spellcheck "false"}]
   (btn :primary (t :books/import) nil {:type "submit"})])

(defn- last-fy-end []
  (let [c (:company @state) td (today)]
    (peek (d/fiscal-year-ends (:company/fy-end-month c) (d/add-years td -1) td))))

(defn- books-view []
  (let [s @state c (:company s) fs (:financials s)
        rows (:fs/rows fs)
        sm (books/summarize rows)
        nxt (books/next-step c fs)
        history (into {} (map (juxt :step identity)) (:fs/history fs))]
    (list
     (h2 (t :books/heading-fy {:fy (or (last-fy-end) "—")}))
     (when (and (seq rows) (not (:balanced? sm)))
       (banner :error (t :error/unbalanced-trial-balance)
               (t :books/unbalanced-body {:assets (yen (:assets sm)) :rest (yen (+ (:liabilities sm) (:equity sm) (:net-income sm)))})))
     (if (seq rows)
       (card
        [:div {:class "stats-grid"}
         (for [[k v] [[:books/assets (:assets sm)] [:books/liabilities (:liabilities sm)] [:books/equity (:equity sm)]
                      [:books/revenue (:revenue sm)] [:books/expenses (:expenses sm)] [:books/net-income (:net-income sm)]]]
           (stat (t k) (yen v)))]
        [:div {:class "chips"}
         (if (:balanced? sm)
           (chip :up (str "✓ " (t :books/balanced)))
           (chip :down (t :books/difference {:amount (yen (- (:assets sm) (+ (:liabilities sm) (:equity sm) (:net-income sm))))})))]
        (when (:fs/imported-on fs) (caption (t :books/imported {:date (:fs/imported-on fs)}))))
       (card [:strong (t :books/import)] (import-form)))
     (when (seq rows)
       (card
        [:ol {:class "steps"}
         (for [{:keys [step]} (books/steps c)
               :let [done (history step)]]
           [:li {:class (str "step" (when done " step--done"))}
            [:span {:class "step__mark" :aria-hidden "true"} (if done "✓" "○")]
            [:span {:class "step__text"}
             [:span {:class "step__label"} (t (keyword "step" (name step)))]
             [:span {:class "step__detail"} (if done (step-detail done) (t (keyword "books" (str "hint-" (name step)))))]]])]))
     (cond
       (and (seq rows) nxt)
       (let [needs-res? (#{:board-approved :agm-approved} (:step nxt))]
         [:form {:class "form"
                 :on-submit #(let [v (form-values %)
                                   [mid aid] (when (:ref v) (str/split (:ref v) #"\|"))]
                               (act! (cond-> {:type :books/advance :step (:step nxt)}
                                       mid (assoc :meeting mid :agenda aid))))}
          (when needs-res? (field (t :books/resolution) (select "ref" (resolutions-for (:step nxt))) "▾"))
          (btn :primary (t :books/advance {:step (t (keyword "step" (name (:step nxt))))}) nil
               {:type "submit" :disabled? (not (:balanced? sm))})])
       (seq rows) (caption (t :books/done) "text"))
     (when (and (seq rows) (empty? (:fs/history fs)))
       [:details {:class "disclosure" :open (not (:balanced? sm))}
        [:summary {:class "awai-button awai-button--tonal"} (t :books/reimport)]
        (import-form)]))))

;; ---------------------------------------------------------------- 会話（K6）

(defn- channel-label [ch]
  (if (= :dm (:channel/kind ch))
    (t :chat/dm {:name (member-name (first (disj (:channel/members ch) (:me @state))))})
    (str "# " (:channel/name ch))))

(defn- hhmm [at] (if (and at (>= (count at) 16)) (subs at 11 16) at))

(defn- message-view [space ch-id m & [reply?]]
  [:li {:class (str "msg" (when reply? " msg--reply"))}
   [:div {:class "msg__meta"} [:strong (member-name (:message/author m))] [:span {:class "caption muted"} (hhmm (:message/at m))]]
   [:p {:class "msg__body"} (:message/body m)]
   (when-not reply?
     (let [rs (chat/replies space ch-id (:message/id m))]
       (list
        (when (seq rs) [:ul {:class "thread"} (map #(message-view space ch-id % true) rs)])
        [:details {:class "disclosure"}
         [:summary {:class "caption link"} (if (seq rs) (t :chat/replies {:n (count rs)}) (t :chat/reply))]
         [:form {:class "compose" :on-submit #(act! {:type :chat/post :channel ch-id :parent (:message/id m)
                                                     :body (:body (form-values %))})}
          [:span {:class "compose__input"} [:input {:name "body" :required true :placeholder (t :chat/placeholder-short)}]]
          (btn :tonal (t :chat/send) nil {:type "submit"})]])))])

(defn- chat-view []
  (let [s @state me (:me s) space (:space s)
        chs (chat/visible-channels space me)
        current (or (some #(when (= (:channel (deref ui)) (:channel/id %)) %) chs) (first chs))
        ch-id (:channel/id current)]
    (when ch-id (js/setTimeout #(when (pos? (chat/unread-count (:space @state) me ch-id))
                                  (act! {:type :chat/read :channel ch-id})) 0))
    (list
     [:nav {:class "segments" :aria-label (t :chat/heading)}
      (for [ch chs :let [n (chat/unread-count space me (:channel/id ch))]]
        [:button {:class "segment" :type "button" :aria-pressed (str (= ch-id (:channel/id ch)))
                  :on-click #(go! :channel (:channel/id ch))}
         (str (channel-label ch) (when (pos? n) (str " · " (t :chat/unread {:n n}))))])]
     [:details {:class "disclosure"}
      [:summary {:class "caption link"} (t :chat/open-dm)]
      [:div {:class "actions"}
       (for [m (get-in s [:company :company/members]) :when (not= me (:member/id m))]
         (btn :text (:member/name m) #(do (act! {:type :chat/open-dm :with (:member/id m)})
                                          (go! :channel (chat/dm-id me (:member/id m))))))]]
     (when ch-id
       (list
        [:ul {:class "messages"} (map #(message-view space ch-id %) (chat/top-level space ch-id))]
        [:div {:class "spacer"}]
        [:form {:class "compose compose--main" :on-submit #(act! {:type :chat/post :channel ch-id :body (:body (form-values %))})}
         [:span {:class "compose__input"} [:input {:name "body" :required true :placeholder (t :chat/placeholder-short)}]]
         (btn :primary (t :chat/send) nil {:type "submit"})])))))

;; ---------------------------------------------------------------- 全体

(def tabs [[:home "calendar"] [:meetings "meeting"] [:ringi "approval"] [:books "ledger"] [:chat "chat"]])

(defn- app-bar [{:keys [title subtitle on-back]}]
  [:header {:class "app-bar"}
   (when on-back [:button {:class "app-bar__back" :type "button" :aria-label (t :common/back) :on-click on-back} (icon "back")])
   [:div {:class "app-bar__text"} [:h1 title] [:p subtitle]]])

(defn- settings []
  (let [s @state c (:company s)]
    [:details {:class "disclosure settings"}
     [:summary {:class "awai-button awai-button--text"} (t :app/settings)]
     (field (t :app/acting-as)
            [:select {:on-change #(act! {:type :session/as :member (.. % -target -value)})}
             (for [m (:company/members c)]
               [:option {:value (:member/id m) :selected (= (:me s) (:member/id m))} (:member/name m)])] "▾")
     [:div {:class "actions"}
      (btn :text (t :app/language) #(do (swap! ui update :lang {:ja :en :en :ja}) (render!)))
      (btn :text (t :app/reset) #(let [s (app/sample)] (reset! state s) (save! s) (go! :vote nil)) {:destructive? true})]
     (caption (t :app/saved-locally))]))

(defn- error-banner []
  (when-let [e (:error @ui)]
    (let [k (keyword "error" (name e)) b (keyword "error-body" (name e))]
      (banner :error (if (has-msg? k) (t k) (t :error/generic {:code (name e)})) (when (has-msg? b) (t b))))))

(defn- view []
  (let [s @state
        vote (:vote @ui)
        m (when vote (app/find-by :meeting/id (:meeting vote) (:meetings s)))
        a (when m (app/find-by :agenda/id (:agenda vote) (:meeting/agenda m)))]
    [:div {:class "shell"}
     (if a
       (app-bar {:title (:meeting/title m) :subtitle (matter-label (:meeting/body m) (:agenda/matter a))
                 :on-back #(go! :vote nil)})
       (app-bar {:title (t :app/title) :subtitle (get-in s [:company :company/name])}))
     [:main {:class "content"}
      (when-not (:storage? @ui) (banner :warning (t :storage/title) (t :storage/body)))
      (error-banner)
      (if a
        (vote-view)
        (list
         (case (:tab @ui)
           :home (home-view) :meetings (meetings-view) :ringi (ringi-view) :books (books-view) :chat (chat-view))
         (settings)))]
     [:nav {:class "tabs" :aria-label "Kaisha"}
      (for [[k ic] tabs]
        [:a {:href (str "#" (name k)) :class "tab" :aria-current (when (and (= k (:tab @ui)) (not a)) "page")
             :on-click #(do (.preventDefault %) (go! :tab k :vote nil))}
         [:span {:class "tab__indicator"} (icon ic)]
         [:span {:class "tab__label"} (t (keyword "tab" (name k)))]])]]))

(defn render! []
  (let [root (.getElementById js/document "app")
        y (.-scrollY js/window)]
    (set! (.-lang (.-documentElement js/document)) (name (:lang @ui)))
    (.replaceChildren root (h (view)))
    (.scrollTo js/window 0 y)))

(defn ^:export init []
  (when (str/starts-with? (or (.-language js/navigator) "ja") "en") (swap! ui assoc :lang :en))
  (reset! state (load-state))
  (render!))

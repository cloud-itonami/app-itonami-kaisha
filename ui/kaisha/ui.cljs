(ns kaisha.ui
  "画面。構成は Figma の提案（umqCqP5KSjLWh9fhCI1HaN、ページ 319:2、AWAI-3445）に合わせる。
  状態は kaisha.app の純粋な関数で更新し、端末の localStorage にだけ保存する。
  外部への通信はしない（CSP connect-src は ipc のみ）。"
  (:require [clojure.string :as str]
            [kaisha.app :as app]
            [kaisha.approval :as approval]
            [kaisha.books :as books]
            [kaisha.chat :as chat]
            [kaisha.company :as co]
            [kaisha.date :as d]
            [kaisha.deadlines :as dl]
            [kaisha.i18n :as i18n]
            [kaisha.identity :as idn]
            [kaisha.law :as law]
            [kaisha.meeting :as mt]
            [kaisha.procedures :as proc]
            [kaisha.ringi :as ringi]))

(def storage-key "itonami.kaisha.state.v2")
(def legacy-storage-key "itonami.kaisha.state.v1")

(defonce ui (atom {:tab :home :lang :ja :error nil :storage? true}))
(defonce state (atom nil))

;; ---------------------------------------------------------------- 保存

(defn- save! [s]
  (try (.setItem js/localStorage storage-key (pr-str s)) true
       (catch :default _ (swap! ui assoc :storage? false) false)))

(defn- load-state []
  (try
    (let [v2 (app/parse-state (.getItem js/localStorage storage-key))
          migrated (when-not v2 (app/parse-state (.getItem js/localStorage legacy-storage-key)))]
      (cond
        v2 v2
        migrated (do (when (save! migrated)
                       (try (.removeItem js/localStorage legacy-storage-key)
                            (catch :default _)))
                     migrated)
        :else (app/sample)))
    (catch :default _
      (swap! ui assoc :storage? false)
      (app/sample))))

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

(declare render! procedures-section settings-row section)

(defn- act!
  "操作を適用する。拒まれたら理由を画面に出し、状態は変えない。成功なら true。"
  [ev]
  (let [ev (merge {:on (today) :at (now)} ev)
        res (app/dispatch @state ev)]
    (if (:error res)
      (swap! ui assoc :error (:error res) :problems (:problems res))
      (do (reset! state (:state res)) (save! (:state res)) (swap! ui assoc :error nil :problems nil)))
    (render!)
    (not (:error res))))

(defn- go! [& kvs]
  (apply swap! ui assoc :error nil :problems nil kvs)
  (render!)
  (.scrollTo js/window 0 0)
  (when (or (:confirm-reset @ui) (:approval-dialog @ui))
    (some-> (.querySelector js/document ".dialog button") .focus)))

(defn- open-approval!
  ([op ev] (open-approval! op ev {}))
  ([op ev opts]
   (swap! ui assoc :approval-dialog {:op op :ev ev :evidence "" :on-success (:on-success opts)})
   (render!)
   (some-> (.querySelector js/document ".dialog button") .focus)))

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

(defn- actor-id [s] (:actor/id (:actor s)))

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
               (btn :tonal (t :officer/verify) #(open-approval! :officer.verify {:type :officer/verify :id (:officer/id o)}))))))))

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
     (procedures-section)
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
  (let [p (approval/preview-shareholders c matter total present for)
        th (:thresholds p)
        r (:result p)]
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
     :failed? (:failed? p)}))

(defn- preview-board [c present for interested]
  (let [p (approval/preview-board c present for interested)
        th (:thresholds p)
        r (:result p)
        eligible-present (count (remove (set interested) (set present)))]
    {:stats [(stat (t :vote/eligible) (t :common/people {:n (:eligible th)}))
             (stat (t :vote/quorum {:rule (t :vote/rule-majority)}) (t :vote/at-least-people {:n (:min-present th)}))
             (stat (t :vote/pass {:rule (t :vote/rule-majority)}) (if (:min-for th) (t :vote/at-least-people {:n (:min-for th)}) "—"))]
     :chip (cond
             (nil? r) (chip :neutral (t :vote/preview-empty))
             (= :passed (:status r)) (chip :up (t :vote/preview-pass))
             (= :quorum-not-met (:reason r)) (chip :down (t :vote/preview-quorum {:present eligible-present :need (:min-present th)}))
             :else (chip :down (t :vote/preview-votes {:for (count (remove (set interested) (set for))) :need (:min-for th)})))
     :failed? (:failed? p)}))

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
                   (open-approval! :meeting.vote
                                   {:type :meeting/vote :id meeting :agenda agenda
                                    :vote (if board?
                                            {:present (pick "present") :for (pick "for") :interested (pick "interested")}
                                            {:rights-total (num "total") :rights-present (num "present") :rights-for (num "for")})}
                                   {:on-success #(go! :vote nil :tab :meetings)})))}
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
        me (actor-id s)
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
                 (btn :primary (t :ringi/approve) #(open-approval! :ringi.decide {:type :ringi/decide :id (:ringi/id r) :decision :approve}))
                 (btn :primary (t :ringi/reject) #(open-approval! :ringi.decide {:type :ringi/decide :id (:ringi/id r) :decision :reject}) {:destructive? true})]
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
                             (btn :tonal (t :officer/verify) #(open-approval! :officer.verify {:type :officer/verify :id (:officer/id rep)}))))))
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
                               (open-approval! :books.advance
                                               (cond-> {:type :books/advance :step (:step nxt)}
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
    (t :chat/dm {:name (member-name (first (disj (:channel/members ch) (actor-id @state))))})
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
  (let [s @state me (actor-id s) space (:space s)
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

;; ---------------------------------------------------------------- 行政手続き（W1〜W4、AWAI-3482）

(defn- place-name [{:keys [country subdivision]}]
  (or (proc/subdivision-name country subdivision) (t :proc/not-set)))

(defn- source-short [title] (let [i (.indexOf title "（")] (if (pos? i) (subs title 0 i) title)))

(defn- procedures-section []
  (let [moves (:procedures @state)]
    (list
     (h2 (t :proc/heading))
     (when (seq moves)
       [:div {:class "settings-list"}
        (for [mv (reverse moves)]
          (settings-row (t :proc/move-row {:to (place-name (:to mv))})
                        (t :proc/move-row-detail {:date (:moved-on mv)})
                        #(go! :screen :procedure :procedure (:procedure/id mv))))])
     [:button {:class "awai-button awai-button--text align-start" :type "button" :on-click #(go! :screen :move)}
      (t :proc/record-move)])))

(defn- move-view []
  (let [c (:company @state)
        from (:company/address c)
        country (or (:country from) :jp)
        options (remove #(= (first %) (:subdivision from)) (proc/subdivisions country))]
    [:form {:class "form"
            :on-submit (fn [^js e]
                         (.preventDefault e)
                         (let [fd (js/FormData. (.-target e))
                               id (str "p-" (now))]
                           (when (act! {:type :procedure/head-office-move :id id
                                        :to {:country country :subdivision (keyword (.get fd "to"))}
                                        :moved-on (.get fd "moved-on")
                                        :address-confirmed? (= "yes" (.get fd "confirmed"))})
                             (go! :screen :procedure :procedure id))))}
     (stat (t :proc/from) (place-name from))
     [:div {:class "field-group"}
      (field (t :proc/to) [:select {:name "to" :required true}
                           (for [[id nm] options] [:option {:value (str (namespace id) "/" (name id))} nm])] "▾")
      (caption (t :proc/to-note))]
     (field (t :proc/moved-on) [:input {:name "moved-on" :type "date" :required true}])
     [:label {:class "check-row"} [:input {:type "checkbox" :name "confirmed" :value "yes"}] (t :proc/confirm-address)]
     (btn :primary (t :proc/plan) nil {:type "submit"})
     (caption (t :proc/move-note))]))

(defn- reason-key [reason]
  (cond
    (#{:unknown-subdivision :subdivision-required :unsupported-jurisdiction :jurisdiction-missing} reason) "not-in-pack"
    (has-msg? (keyword "proc" (str "reason-title-" (name reason)))) (name reason)
    :else "other"))

(defn- candidate-card [cand]
  (let [{:keys [deadline submit-to sources unknowns]} cand
        reg (:registry-office-for submit-to)]
    (card
     [:strong {:class "card__title"} (:procedure/title cand)]
     [:div {:class "stats-row"}
      (stat (t :proc/due) (case (:kind deadline)
                            :within-days (t :proc/due-value {:date (:due deadline)})
                            :before (t :proc/before {:date (:due-before deadline)})
                            "—"))
      (when (= :within-days (:kind deadline)) (stat (t :proc/count) (t :proc/within-days {:days (:days deadline)})))]
     (cond
       reg (caption (t :proc/submit-to-registry {:name (place-name {:country :jp :subdivision reg})}) "text")
       (map? submit-to) (caption (t :proc/submit-to {:name (or (:name submit-to) "—")}) "text"))
     (for [src sources] (caption (t :common/source-inline {:law (source-short (:title src))})))
     (when (seq unknowns)
       [:div {:class "chips"}
        (for [u unknowns :let [k (keyword "proc" (str "unknown-" (name u)))]]
          (chip :warning (if (has-msg? k) (t k) (name u))))]))))

(defn- procedure-view []
  (let [s @state c (:company s)
        mv (app/find-by :procedure/id (:procedure @ui) (:procedures s))
        plan (when mv (proc/head-office-move-plan c mv (today)))
        added? (some #(and (= (:on %) (:moved-on mv)) (= (:kind %) :head-office-move)) (:registrations s))]
    (case (:status plan)
      :candidates
      (list
       [:div {:class "chips"} (chip :neutral (t :proc/candidate)) (when (not= :reviewed (get-in plan [:pack :review])) (chip :info (t :app/review)))]
       (map candidate-card (:candidates plan))
       (when (= :withheld-unreviewed-pack (:submission-proposal plan))
         (banner :info (t :proc/withheld-title) (t :proc/withheld-body)))
       (card [:div {:class "card__head"} [:strong (t :proc/efiling)] (chip :neutral (t :proc/preparing))]
             (caption (t :proc/efiling-body))
             [:button {:class "awai-button awai-button--text align-start" :type "button" :on-click #(go! :screen :identity)} (t :identity/open)])
       (if added?
         (caption (t :proc/deadline-added) "text")
         (btn :tonal (t :proc/add-deadline)
              #(act! {:type :registration/add :title (t :proc/move-row {:to (place-name (:to mv))})
                      :change-on (:moved-on mv) :kind :head-office-move})))
       (caption (t :proc/note)))
      (let [k (reason-key (:reason plan))
            tone (if (= :blocked (:status plan)) :error :info)]
        (list
         (banner tone (t (keyword "proc" (str "reason-title-" k)))
                 (t (keyword "proc" (str "reason-body-" k)) {:review (:review-by plan) :code (some-> (:reason plan) name)}))
         (when (= :representative-unconfirmed (:reason plan))
           (btn :tonal (t :proc/open-officers) #(go! :screen :officers)))
         (when (= tone :info) (caption (t :proc/stopped-note))))))))

(defn- address-view []
  (let [c (:company @state)
        addr (:company/address c)]
    [:form {:class "form"
            :on-submit (fn [^js e]
                         (.preventDefault e)
                         (let [v (.get (js/FormData. (.-target e)) "subdivision")]
                           (when (act! {:type :company/address :address {:country :jp :subdivision (keyword v)}})
                             (go! :screen :settings))))}
     (stat (t :address/country) (t :address/japan))
     [:div {:class "field-group"}
      (field (t :address/municipality)
             [:select {:name "subdivision"}
              (for [[id nm] (proc/subdivisions :jp)]
                [:option {:value (str (namespace id) "/" (name id)) :selected (= id (:subdivision addr))} nm])] "▾")
      (caption (t :address/note))]
     (btn :primary (t :common/save) nil {:type "submit"})]))

;; ---------------------------------------------------------------- 本人確認と電子申請（X1、AWAI-3483）

(defn- service-row [{:keys [id status requirements]}]
  (let [reqs (remove #(= "" %) (map #(t (keyword "req" (name %))) requirements))]
    (settings-row (t (keyword "service" (str (namespace id) "-" (name id))))
                  (str/join " · " (cons (t (keyword "identity" (str "status-" (name status)))) reqs))
                  nil)))

(defn- identity-view []
  (list
   (banner :info (t :identity/no-mynumber-title) (t :identity/no-mynumber-body))
   (section (t :identity/japan) [:div {:class "settings-list"} (map service-row (idn/by-country :jp))])
   (section (t :identity/other) [:div {:class "settings-list"} (map service-row (remove #(= :jp (:country %)) idn/services))])
   (caption (t :identity/consent-note))))

;; ---------------------------------------------------------------- 設定（U1〜U5・V1〜V3、AWAI-3449）

(defn- app-bar [{:keys [title subtitle on-back on-settings]}]
  [:header {:class "app-bar"}
   (when on-back [:button {:class "icon-button app-bar__back" :type "button" :aria-label (t :common/back) :on-click on-back} (icon "back")])
   [:div {:class "app-bar__text"} [:h1 title] [:p subtitle]]
   (when on-settings [:button {:class "icon-button" :type "button" :aria-label (t :settings/open) :on-click on-settings} (icon "settings")])])

(defn- section [title & children] (into [:section {:class "section"} [:h2 title]] children))

(defn- settings-row
  "Kaisha / Settings row。on-click がなければ表示だけ（矢印を出さない）。"
  [title detail on-click]
  (if on-click
    [:button {:class "settings-row" :type "button" :on-click on-click}
     [:span {:class "settings-row__text"} [:span {:class "settings-row__title"} title] (when detail [:span {:class "settings-row__detail"} detail])]
     (icon "chevron")]
    [:div {:class "settings-row"}
     [:span {:class "settings-row__text"} [:span {:class "settings-row__title"} title] (when detail [:span {:class "settings-row__detail"} detail])]]))

(defn- choice
  "DS / Segment を radio で。"
  [nm label options selected & [note]]
  [:fieldset {:class "choice"}
   [:legend {:class "field__label"} label]
   [:div {:class "segments"}
    (for [[v l] options]
      [:label {:class "segment-radio"}
       [:input {:type "radio" :name nm :value (name v) :checked (= v selected)}]
       [:span {:class "segment"} l]])]
   (when note (caption note))])

(defn- problem-text [{:keys [code limit]}]
  (let [k (keyword "problem" (name code))]
    (if (has-msg? k) (t k {:limit limit}) (t :error/generic {:code (name code)}))))

(def ^:private problem-field
  {:director-term-out-of-range "director" :auditor-term-out-of-range "auditor"
   :notice-shortening-not-allowed "shareholder" :board-notice-out-of-range "board"
   :quorum-below-one-third "officer-quorum" :missing-name "name"
   :invalid-corporate-number "corporate-number"})

(defn- show-problems!
  "入力中の誤りを Banner と項目の下の文に出し、誤りがあれば保存ボタンを無効にする。"
  [^js form problems]
  (let [errors (filter #(= :error (:severity %)) problems)]
    (.replaceChildren (.querySelector form "[data-problems]")
                      (h (when (seq errors) (banner :error (t :problem/title) (str/join " " (map problem-text errors))))))
    (doseq [^js el (array-seq (.querySelectorAll form "[data-problem-for]"))]
      (let [p (some #(when (= (.getAttribute el "data-problem-for") (problem-field (:code %))) %) errors)]
        (set! (.-textContent el) (if p (str "✕ " (problem-text p)) ""))))
    (set! (.-disabled (.querySelector form "[data-save]")) (boolean (seq errors)))))

(defn- int-or-nil [v] (when (and v (not= "" v)) (let [n (js/parseInt v 10)] (when-not (js/isNaN n) n))))

(defn- company-patch [^js fd]
  {:company/name (str/trim (or (.get fd "name") ""))
   :company/corporate-number (let [v (str/replace (or (.get fd "corporate-number") "") #"[\s-]" "")] (when-not (str/blank? v) v))
   :company/form (keyword (.get fd "form"))
   :company/public? (= "yes" (.get fd "public"))
   :company/board? (= "yes" (.get fd "board"))
   :company/auditor? (= "yes" (.get fd "auditor"))
   :company/fy-end-month (int-or-nil (.get fd "fy"))
   :company/agm-within-months (int-or-nil (.get fd "agm"))})

(defn- articles-patch [c ^js fd]
  (let [arts (:company/articles c)
        q (cond-> {}
            (= "excluded" (.get fd "ordinary")) (assoc :ordinary :none)
            (= "one-third" (.get fd "officer")) (assoc :officer [1 3])
            (get-in arts [:quorum :special]) (assoc :special (get-in arts [:quorum :special])))
        put (fn [m k v] (if (some? v) (assoc m k v) (dissoc m k)))]
    {:company/articles
     (-> arts
         (put :director-term-years (int-or-nil (.get fd "director")))
         (cond-> (.get fd "auditor") (put :auditor-term-years (int-or-nil (.get fd "auditor"))))
         (cond-> (some? (.get fd "board")) (put :board-notice-days (int-or-nil (.get fd "board"))))
         (cond-> (some? (.get fd "shareholder")) (put :shareholder-notice-days (int-or-nil (.get fd "shareholder"))))
         (put :quorum (not-empty q)))}))

(defn- live-form [patch-fn content]
  (let [c (:company @state)
        check (fn [^js e] (let [f (.-currentTarget e)] (show-problems! f (co/validate (merge c (patch-fn c (js/FormData. f)))))))]
    (into [:form {:class "form"
                  :on-input check :on-change check
                  :on-submit (fn [^js e]
                               (.preventDefault e)
                               (when (act! {:type :company/set :patch (patch-fn c (js/FormData. (.-target e)))})
                                 (go! :screen :settings)))}
           [:div {:data-problems true}]]
          (concat content [[:button {:class "awai-button awai-button--primary" :type "submit" :data-save true} (t :common/save)]]))))

(defn- problem-slot [k] [:p {:class "caption problem" :data-problem-for k :aria-live "polite"}])

(defn- company-summary [c]
  (t :company/summary {:form (t (keyword "form" (name (:company/form c))))
                       :public (t (if (:company/public? c) :company/public-yes :company/public-no))
                       :organs (t (cond (and (:company/board? c) (:company/auditor? c)) :company/organs-both
                                        (:company/board? c) :company/organs-board
                                        (:company/auditor? c) :company/organs-auditor
                                        :else :company/organs-none))
                       :m (:company/fy-end-month c)}))

(defn- settings-list []
  (let [s @state c (:company s)
        reps (filter #(some #{:representative-director :representative-member} (:officer/roles %)) (:company/officers c))
        arts (:company/articles c)
        policy (or (seq (:company/approval-policy c)) ringi/default-policy)]
    (list
     (section (t :settings/acting-heading)
              (field (t :app/acting-as)
                     [:select {:on-change #(act! {:type :session/as :member (.. % -target -value)})}
                      (for [m (:company/members c)]
                        [:option {:value (:member/id m) :selected (= (actor-id s) (:member/id m))} (:member/name m)])] "▾")
              (caption (t :settings/acting-note)))
     (section (t :settings/display)
              [:fieldset {:class "choice"}
               [:legend {:class "field__label"} (t :settings/language)]
               [:div {:class "segments"}
                (for [[l label] [[:ja "日本語"] [:en "English"]]]
                  [:button {:class "segment" :type "button" :aria-pressed (str (= l (:lang @ui)))
                            :on-click #(do (swap! ui assoc :lang l) (render!))} label])]
               (caption (t :settings/theme-note))])
     (section (t :settings/company)
              [:div {:class "settings-list"}
               (settings-row (t :settings/row-company) (company-summary c) #(go! :screen :company))
               (settings-row (t :settings/row-articles)
                             (if (co/kk? c)
                               (t :settings/summary-articles {:director (co/term-years c :director) :auditor (co/term-years c :auditor)
                                                              :board (mt/board-notice-days c)})
                               "—")
                             (when (co/kk? c) #(go! :screen :articles)))
               (settings-row (t :settings/row-officers)
                             (t :settings/summary-officers {:n (count (:company/officers c)) :reps (count reps)
                                                            :ok (count (filter #(d/valid? (:officer/authority-verified-on %)) reps))})
                             #(go! :screen :officers))
               (settings-row (t :settings/row-members)
                             (t :settings/summary-members {:n (count (:company/members c))
                                                           :approvers (count (filter #(some #{:approve} (:member/permissions %)) (:company/members c)))})
                             #(go! :screen :members))
               (settings-row (t :settings/row-policy)
                             (str/join " · " (for [{:keys [up-to approvers]} policy]
                                               (if up-to (t :settings/policy-up-to {:amount (yen up-to) :n approvers})
                                                   (t :settings/policy-above {:n approvers}))))
                             nil)])
     (section (t :settings/procedures)
              [:div {:class "settings-list"}
               (settings-row (t :identity/title) (t :identity/row-detail) #(go! :screen :identity))
               (settings-row (t :settings/row-address) (place-name (:company/address c)) #(go! :screen :address))])
     (section (t :settings/law)
              [:div {:class "chips"} (chip :info (t :app/review))]
              (caption (t :settings/law-detail {:n (count law/sources) :date (:pack/checked-on law/pack) :review (:pack/review-by law/pack)})))
     (section (t :settings/data)
              (caption (t :app/saved-locally) "text")
              (btn :primary (t :reset/confirm) #(go! :confirm-reset true) {:destructive? true})))))

(defn- company-view []
  (let [c (:company @state)
        yn (fn [nm label v & [note]] (choice nm label [[:yes (t :present)] [:no (t :absent)]] (if v :yes :no) note))]
    (live-form (fn [_ fd] (company-patch fd))
               [(field (t :company/name) [:input {:name "name" :value (:company/name c) :required true}])
                (problem-slot "name")
                [:div {:class "field-group"}
                 (field (t :company/corporate-number) [:input {:name "corporate-number" :inputmode "numeric" :maxlength 13 :autocomplete "off" :value (:company/corporate-number c)}])
                 (problem-slot "corporate-number")
                 (caption (t :company/corporate-number-note))]
                (choice "form" (t :company/form) [[:kk (t :form/kk)] [:godo (t :form/godo)]] (:company/form c) (t :company/form-note))
                (choice "public" (t :company/public) [[:no (t :no)] [:yes (t :yes)]] (if (:company/public? c) :yes :no) (t :company/public-note))
                (yn "board" (t :company/board) (:company/board? c))
                (yn "auditor" (t :company/auditor) (:company/auditor? c))
                (field (t :company/fy-month) (select "fy" (for [m (range 1 13)] [(keyword (str m)) (t :company/month {:m m})]) (keyword (str (:company/fy-end-month c)))) "▾")
                [:div {:class "field-group"}
                 (field (t :company/agm) (select "agm" (for [m [1 2 3]] [(keyword (str m)) (t :company/agm-option {:m m})]) (keyword (str (or (:company/agm-within-months c) 3)))) "▾")
                 (caption (t :company/agm-note))]])))

(defn- articles-view []
  (let [c (:company @state) arts (:company/articles c)
        fixed? (or (:company/public? c) (:company/board? c))]
    (live-form articles-patch
               [[:div {:class "field-group"}
                 (field (t :articles/director) [:input {:name "director" :type "number" :min 1 :max 10 :step 1 :inputmode "numeric" :value (co/term-years c :director)}] (t :unit/years))
                 (problem-slot "director") (caption (t :articles/director-note))]
                (when (:company/auditor? c)
                  [:div {:class "field-group"}
                   (field (t :articles/auditor) [:input {:name "auditor" :type "number" :min 4 :max 10 :step 1 :inputmode "numeric" :value (co/term-years c :auditor)}] (t :unit/years))
                   (problem-slot "auditor") (caption (t :articles/auditor-note))])
                [:div {:class "field-group"}
                 (if fixed?
                   (list (stat (t :articles/shareholder-notice) (t :articles/shareholder-fixed {:days (mt/shareholder-notice-days c {})}))
                         (caption (t :articles/shareholder-fixed-note)))
                   (list (field (t :articles/shareholder-notice) [:input {:name "shareholder" :type "number" :min 0 :max 7 :step 1 :inputmode "numeric" :value (:shareholder-notice-days arts)}] (t :unit/days))
                         (problem-slot "shareholder") (caption (t :articles/shareholder-short-note))))]
                (when (:company/board? c)
                  [:div {:class "field-group"}
                   (field (t :articles/board-notice) [:input {:name "board" :type "number" :min 1 :max 7 :step 1 :inputmode "numeric" :value (mt/board-notice-days c)}] (t :unit/days))
                   (problem-slot "board") (caption (t :articles/board-notice-note))])
                (choice "ordinary" (t :articles/ordinary-quorum) [[:majority (t :articles/majority)] [:excluded (t :articles/excluded)]]
                        (if (= :none (get-in arts [:quorum :ordinary])) :excluded :majority) (t :articles/ordinary-note))
                [:div {:class "field-group"}
                 (field (t :articles/officer-quorum) (select "officer" [[:majority (t :articles/majority)] [:one-third (t :articles/one-third)]]
                                                             (if (get-in arts [:quorum :officer]) :one-third :majority)) "▾")
                 (problem-slot "officer-quorum") (caption (t :articles/officer-note))]])))

(defn- officers-view []
  (let [c (:company @state)
        roles (get co/officer-roles-by-form (:company/form c))
        rep (some #(when (some #{:representative-director :representative-member} (:officer/roles %)) %) (:company/officers c))
        rep-status (when rep (co/representative? c (:officer/id rep)))]
    (list
     [:div {:class "settings-list"}
      (for [o (:company/officers c) :let [term (co/officer-term c o)]]
        (settings-row (:officer/name o)
                      (t :officers/row {:role (str/join "・" (map #(t (keyword "role" (name %))) (:officer/roles o)))
                                        :elected (:officer/elected-on o)
                                        :expiry (if (= :ok (:status term)) (t :officers/expiry {:date (:latest-expiry term)}) "")})
                      nil))]
     (when (and rep (= :unverified (:status rep-status)))
       (list [:div {:class "chips"} (chip :warning (t :officers/rep-unverified {:name (:officer/name rep)}))]
             (btn :tonal (t :officer/verify) #(open-approval! :officer.verify {:type :officer/verify :id (:officer/id rep)}))))
     (caption (t :officers/note))
     [:details {:class "disclosure"}
      [:summary {:class "awai-button awai-button--primary"} (t :officers/add)]
      [:form {:class "form"
              :on-submit #(let [v (form-values %)]
                            (act! {:type :officer/add
                                   :officer {:officer/id (str "o-" (now)) :officer/name (str/trim (:name v))
                                             :officer/roles [(keyword (:role v))] :officer/elected-on (:elected v)}}))}
       (field (t :officers/name) [:input {:name "name" :required true}])
       (field (t :officers/role) (select "role" (for [r (sort roles)] [r (t (keyword "role" (name r)))])) "▾")
       (field (t :officers/elected) [:input {:name "elected" :type "date" :required true}])
       (btn :primary (t :common/add) nil {:type "submit"})]])))

(defn- perm-label [perms]
  (if (seq perms) (str/join " · " (map #(t (keyword "perm" (name %))) (sort perms))) (t :members/none)))

(defn- perm-checks [selected]
  [:fieldset {:class "checks"}
   (for [p [:approve :post]]
     [:label [:input {:type "checkbox" :name "perm" :value (name p) :checked (contains? (set selected) p)}] (t (keyword "perm" (name p)))])])

(defn- members-view []
  (let [c (:company @state)]
    (list
     [:div {:class "settings-list"}
      (for [m (:company/members c)]
        [:details {:class "settings-row-detail"}
         [:summary {:class "settings-row"}
          [:span {:class "settings-row__text"} [:span {:class "settings-row__title"} (:member/name m)]
           [:span {:class "settings-row__detail"} (perm-label (:member/permissions m))]]
          (icon "chevron")]
         [:form {:class "form" :on-submit #(let [fd (js/FormData. (.-target %))]
                                             (.preventDefault %)
                                             (open-approval! :member.permissions.set
                                                             {:type :member/set-permissions :id (:member/id m)
                                                              :permissions (map keyword (js->clj (.getAll fd "perm")))}))}
          (perm-checks (:member/permissions m))
          (btn :tonal (t :common/save) nil {:type "submit"})]])]
     (caption (t :members/note))
     [:details {:class "disclosure"}
      [:summary {:class "awai-button awai-button--primary"} (t :members/add)]
      [:form {:class "form" :on-submit #(let [fd (js/FormData. (.-target %))]
                                          (.preventDefault %)
                                          (act! {:type :member/add
                                                 :member {:member/id (str "m-" (now)) :member/name (str/trim (or (.get fd "name") ""))
                                                          :member/permissions (map keyword (js->clj (.getAll fd "perm")))}}))}
       (field (t :members/name) [:input {:name "name" :required true}])
       (perm-checks [:post])
       (btn :primary (t :common/add) nil {:type "submit"})]])))

(def ^:private screens
  {:settings {:title :settings/title :parent nil}
   :company {:title :settings/row-company :parent :settings}
   :articles {:title :settings/row-articles :parent :settings}
   :officers {:title :settings/row-officers :parent :settings}
   :members {:title :settings/row-members :parent :settings}
   :address {:title :settings/row-address :parent :settings}
   :identity {:title :identity/title :parent :settings}
   :move {:title :proc/move-title :subtitle :proc/move-subtitle :parent nil}
   :procedure {:title :proc/candidates-title :parent nil}})

(defn- reset-dialog []
  [:div {:class "scrim" :on-keydown #(when (= "Escape" (.-key %)) (go! :confirm-reset false))}
   [:div {:class "dialog" :role "alertdialog" :aria-modal "true" :aria-labelledby "reset-title" :aria-describedby "reset-body"}
    [:h2 {:id "reset-title"} (t :reset/title)]
    [:p {:id "reset-body"} (t :reset/body)]
    [:div {:class "dialog__actions"}
     (btn :text (t :common/cancel) #(go! :confirm-reset false))
     (btn :primary (t :reset/confirm)
          #(let [s (app/sample)] (reset! state s) (save! s) (go! :confirm-reset false :screen nil :vote nil :tab :home))
          {:destructive? true})]]])

(defn- approval-dialog []
  (when-let [{:keys [op ev evidence on-success]} (:approval-dialog @ui)]
    (let [lang (:lang @ui)
          curr-prev (approval/preview @state op (cond-> ev
                                                  (= (approval/normalize-op op) :officer.verify)
                                                  (assoc :evidence (or evidence "")))
                                      {:lang lang})
          allowed? (:allowed? curr-prev)
          effects (:effects curr-prev)]
      [:div {:class "scrim" :on-keydown #(when (= "Escape" (.-key %)) (go! :approval-dialog nil))}
       [:div {:class "dialog" :role "alertdialog" :aria-modal "true"
              :aria-labelledby "approval-title" :aria-describedby "approval-desc"}
        [:h2 {:id "approval-title"} (t (keyword "approval" (str "title-" (name (approval/normalize-op op)))))]
        [:div {:id "approval-desc" :class "approval-dialog__content"}
         [:div {:class "chips"}
          (chip :warning (t :approval/irreversible))
          (chip :neutral (t :approval/human-only))]
         (when (seq effects)
           [:ul {:class "approval-effects"}
            (for [eff effects]
              [:li eff])])
         (when (= (approval/normalize-op op) :officer.verify)
           [:div {:class "field-group"}
            (field (t :approval/evidence-label)
                   [:textarea {:name "evidence" :rows 3 :required true
                               :placeholder (t :approval/evidence-placeholder)
                               :value (or evidence "")
                               :on-input #(let [v (.. % -target -value)]
                                            (swap! ui assoc-in [:approval-dialog :evidence] v)
                                            (render!))}])
            (caption (t :approval/evidence-note))])
         (when (and (not allowed?) (:reason curr-prev))
           (caption (t (keyword "approval" (str "reason-" (name (:reason curr-prev)))))
                    "problem"))]
        [:div {:class "dialog__actions"}
         (btn :text (t :common/cancel) #(go! :approval-dialog nil))
         (btn :primary (t :approval/confirm)
              (fn []
                (let [final-ev (cond-> ev
                                 (= (approval/normalize-op op) :officer.verify)
                                 (assoc :evidence (or evidence "")))]
                  (when (act! final-ev)
                    (let [cb on-success]
                      (swap! ui dissoc :approval-dialog)
                      (when cb (cb))
                      (render!)))))
              {:disabled? (not allowed?) :destructive? (= (:decision ev) :reject)})]]])))

(defn- error-banner []
  (when-let [e (:error @ui)]
    (let [k (keyword "error" (name e)) b (keyword "error-body" (name e))
          problems (:problems @ui)]
      (banner :error (if (has-msg? k) (t k) (t :error/generic {:code (name e)}))
              (cond (seq problems) (str/join " " (map problem-text problems))
                    (has-msg? b) (t b))))))

;; ---------------------------------------------------------------- 全体

(def tabs [[:home "calendar"] [:meetings "meeting"] [:ringi "approval"] [:books "ledger"] [:chat "chat"]])

(defn- view []
  (let [s @state
        vote (:vote @ui)
        screen (:screen @ui)
        m (when vote (app/find-by :meeting/id (:meeting vote) (:meetings s)))
        a (when m (app/find-by :agenda/id (:agenda vote) (:meeting/agenda m)))
        confirm? (:confirm-reset @ui)
        approval? (:approval-dialog @ui)]
    (list
    [:div {:class "shell" :inert (boolean (or confirm? approval?))}
     (cond
       screen (app-bar {:title (t (get-in screens [screen :title]))
                        :subtitle (cond (= screen :settings) (get-in s [:company :company/name])
                                        (= screen :procedure) (t :proc/candidates-subtitle
                                                                 {:date (:moved-on (app/find-by :procedure/id (:procedure @ui) (:procedures s)))})
                                        (get-in screens [screen :subtitle]) (t (get-in screens [screen :subtitle]))
                                        :else (t :settings/title))
                        :on-back #(go! :screen (get-in screens [screen :parent]))})
       a (app-bar {:title (:meeting/title m) :subtitle (matter-label (:meeting/body m) (:agenda/matter a))
                   :on-back #(go! :vote nil)})
       :else (app-bar {:title (t :app/title) :subtitle (get-in s [:company :company/name])
                       :on-settings #(go! :screen :settings)}))
     [:main {:class "content"}
      (when-not (:storage? @ui)
        (if screen
          (banner :warning (t :storage/title) (t :storage/body-settings))
          (banner :warning (t :storage/title) (t :storage/body))))
      (error-banner)
      (case screen
        :settings (settings-list)
        :company (company-view)
        :articles (articles-view)
        :officers (officers-view)
        :members (members-view)
        :address (address-view)
        :identity (identity-view)
        :move (move-view)
        :procedure (procedure-view)
        (if a
          (vote-view)
          (case (:tab @ui)
            :home (home-view) :meetings (meetings-view) :ringi (ringi-view) :books (books-view) :chat (chat-view))))]
     (when-not screen
       [:nav {:class "tabs" :aria-label "Kaisha"}
        (for [[k ic] tabs]
          [:a {:href (str "#" (name k)) :class "tab" :aria-current (when (and (= k (:tab @ui)) (not a)) "page")
               :on-click #(do (.preventDefault %) (go! :tab k :vote nil))}
           [:span {:class "tab__indicator"} (icon ic)]
           [:span {:class "tab__label"} (t (keyword "tab" (name k)))]])])]
    (when confirm? (reset-dialog))
    (when approval? (approval-dialog)))))

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

(ns kaisha.ui
  "画面。状態は kaisha.app の純粋な関数で更新し、端末の localStorage にだけ保存する。
  外部への通信はしない（CSP connect-src は ipc のみ）。"
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [kaisha.app :as app]
            [kaisha.books :as books]
            [kaisha.chat :as chat]
            [kaisha.company :as co]
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

(defn- yen [n]
  (t :common/yen {:amount (.format (js/Intl.NumberFormat. (if (= :ja (:lang @ui)) "ja-JP" "en-US")) n)}))

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
  "操作を適用する。拒まれたら理由を画面に出し、状態は変えない。"
  [ev]
  (let [ev (merge {:on (today) :at (now)} ev)
        res (app/dispatch @state ev)]
    (if (:error res)
      (swap! ui assoc :error (:error res))
      (do (reset! state (:state res)) (save! (:state res)) (swap! ui assoc :error nil)))
    (render!)))

(defn- form-values [^js e]
  (.preventDefault e)
  (let [fd (js/FormData. (.-target e))]
    (into {} (map (fn [[k v]] [(keyword k) v])) (js->clj (js/Array.from (.entries fd))))))

(defn- source-note [id]
  (when id
    (let [{:keys [title url]} (law/cite id)]
      [:details {:class "awai-disclosure"}
       [:summary (t :common/source)]
       [:p {:class "awai-caption"} title]
       [:p {:class "awai-caption awai-muted"} url]])))

(defn- chip [tone label] [:span {:class (str "chip chip--" (name tone))} label])

(defn- member-name [id]
  (or (:member/name (app/find-by :member/id id (get-in @state [:company :company/members]))) id))

(defn- field [label input] [:label {:class "field"} [:span {:class "field__label"} label] input])

(defn- select [nm options & [selected]]
  [:select {:name nm}
   (for [[v label] options] [:option {:value (name v) :selected (= v selected)} label])])

;; ---------------------------------------------------------------- 予定

(defn- deadline-row [{:keys [kind date params estimated overdue source]}]
  [:li {:class "row"}
   [:div {:class "row__date"} [:span date]
    (when overdue (chip :error (t :home/overdue)))
    (when estimated (chip :neutral (t :home/estimated)))]
   [:div {:class "row__body"}
    [:span (t (keyword "deadline" (name kind))
              (cond-> params (:step params) (update :step #(t (keyword "step" (name %))))))]
    (source-note source)]])

(defn- officer-card [o]
  (let [c (:company @state)
        term (co/officer-term c o)
        role (co/term-role o)
        rep (when (some #{:representative-director} (:officer/roles o)) (co/representative? c (:officer/id o)))]
    [:li {:class "awai-card"}
     [:strong (:officer/name o)]
     [:span {:class "awai-caption"}
      (t :officer/term {:role (str/join "・" (map #(t (keyword "role" (name %))) (:officer/roles o)))
                        :years (co/term-years c role) :elected (:officer/elected-on o)})]
     (when (= :ok (:status term))
       [:span {:class "awai-caption"} (t :officer/expiry {:date (:latest-expiry term)})])
     (when rep
       (if (= :ok (:status rep))
         [:span {:class "awai-caption"} (t :officer/representative-ok {:date (:officer/authority-verified-on o)})]
         (list [:span {:class "awai-caption warn"} (t :officer/representative-unverified)]
               [:button {:class "awai-button awai-button--tonal"
                         :on-click #(act! {:type :officer/verify :id (:officer/id o)})}
                (t :officer/verify)])))
     (source-note (:source term))]))

(defn- home-view []
  (let [s @state
        items (dl/upcoming s (today) 400)]
    (list
     [:h2 (t :home/heading)]
     (if (seq items)
       [:ul {:class "list"} (map deadline-row items)]
       [:p {:class "awai-muted"} (t :home/empty)])
     [:details {:class "awai-disclosure"}
      [:summary (t :home/registration-add)]
      [:form {:class "form" :on-submit #(let [v (form-values %)]
                                          (act! {:type :registration/add :title (:title v) :change-on (:on v)}))}
       (field (t :home/registration-title) [:input {:name "title" :required true}])
       (field (t :home/registration-on) [:input {:name "on" :type "date" :required true}])
       [:button {:class "awai-button awai-button--primary" :type "submit"} (t :common/add)]]]
     [:h2 (t :home/officers)]
     [:ul {:class "cards"} (map officer-card (get-in s [:company :company/officers]))])))

;; ---------------------------------------------------------------- 会議

(defn- vote-form [m a]
  (let [c (:company @state)
        board? (= :board (:meeting/body m))
        ds (co/directors c)]
    [:form {:class "form"
            :on-submit
            (fn [e]
              (.preventDefault e)
              (let [fd (js/FormData. (.-target e))
                    pick (fn [k] (set (js->clj (.getAll fd k))))
                    num (fn [k] (js/parseInt (.get fd k) 10))]
                (act! {:type :meeting/vote :id (:meeting/id m) :agenda (:agenda/id a)
                       :vote (if board?
                               {:present (pick "present") :for (pick "for") :interested (pick "interested")}
                               {:rights-total (num "total") :rights-present (num "present") :rights-for (num "for")})})))}
     (if board?
       (for [[k label] [["present" :meetings/present] ["for" :meetings/for] ["interested" :meetings/interested]]]
         [:fieldset {:class "checks"} [:legend (t label)]
          (for [d ds] [:label [:input {:type "checkbox" :name k :value d}] (member-name d)])])
       (for [[k label] [["total" :meetings/rights-total] ["present" :meetings/rights-present] ["for" :meetings/rights-for]]]
         (field (t label) [:input {:name k :type "number" :min 0 :step 1 :required true :inputmode "numeric"}])))
     [:button {:class "awai-button awai-button--tonal" :type "submit"} (t :meetings/vote)]]))

(defn- matter-label [body matter]
  (t (if (and (= body :board) (= matter :approve-financials))
       :matter/approve-financials-board
       (keyword "matter" (name matter)))))

(defn- agenda-item [m a]
  (let [r (:agenda/result a)
        notice-ok? (= :ok (:status (mt/notice-check (:company @state) m)))]
    [:li {:class "row"}
     [:div {:class "row__body"}
      [:strong (:agenda/title a)]
      [:span {:class "awai-caption"} (matter-label (:meeting/body m) (:agenda/matter a))]
      (if r
        (list (chip (if (= :passed (:status r)) :up :down)
                    (str (if (= :passed (:status r)) "✓ " "✕ ") (t (keyword "result" (name (:status r))))))
              (source-note (:source r)))
        (if notice-ok?
          (vote-form m a)
          [:span {:class "awai-caption awai-muted"} (t :meetings/vote-after-notice)]))]]))

(defn- meeting-card [m]
  (let [c (:company @state)
        n (mt/notice-check c m)
        ret (mt/minutes-retention m)]
    [:li {:class "awai-card"}
     [:strong (:meeting/title m)]
     [:span {:class "awai-caption"} (str (t (keyword "body" (name (:meeting/body m)))) " · " (:meeting/date m))]
     [:span {:class (str "awai-caption" (when (= :blocked (:status n)) " warn"))}
      (case (:status n)
        :pending (t :meetings/notice-pending {:date (:latest-send-date n) :days (:required-days n)})
        :ok (if (:waived n) (t :meetings/notice-waived) (t :meetings/notice-ok {:date (:meeting/notice-sent-on m)}))
        :blocked (t :meetings/notice-late {:date (:latest-send-date n)})
        "")]
     (when (= :pending (:status n))
       [:button {:class "awai-button awai-button--tonal"
                 :on-click #(act! {:type :meeting/notice-sent :id (:meeting/id m)})}
        (t :meetings/notice-sent)])
     (source-note (:source n))
     [:ul {:class "list"} (map #(agenda-item m %) (:meeting/agenda m))]
     (when ret [:span {:class "awai-caption awai-muted"} (t :meetings/retention {:date (:until ret)})])]))

(defn- meetings-view []
  (let [s @state c (:company s)]
    (list
     [:h2 (t :meetings/heading)]
     [:ul {:class "cards"} (map meeting-card (sort-by :meeting/date (:meetings s)))]
     [:details {:class "awai-disclosure"}
      [:summary (t :meetings/add)]
      [:form {:class "form"
              :on-submit #(let [v (form-values %)
                                body (keyword (:body v))]
                            (act! {:type :meeting/add
                                   :meeting {:meeting/id (str "m-" (now))
                                             :meeting/body body
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
       [:button {:class "awai-button awai-button--primary" :type "submit"} (t :common/add)]]])))

;; ---------------------------------------------------------------- 稟議

(defn- passed-board-items []
  (for [m (:meetings @state) :when (= :board (:meeting/body m))
        a (:meeting/agenda m) :when (= :passed (get-in a [:agenda/result :status]))]
    [(str (:meeting/id m) "|" (:agenda/id a)) (str (:meeting/title m) " — " (:agenda/title a))]))

(defn- ringi-card [r]
  (let [s @state c (:company s)
        st (ringi/status c r)
        {:keys [approvers board-resolution? source]} (ringi/route c r)
        have (count (filter #(= :approve (:decision %)) (:ringi/decisions r)))
        me (:me s)
        rep (some #(when (some #{:representative-director :representative-member} (:officer/roles %)) %) (:company/officers c))]
    [:li {:class "awai-card"}
     [:div {:class "row__date"} [:strong (:ringi/title r)] (chip (case st :approved :up :rejected :down :neutral) (t (keyword "status" (name st))))]
     [:span {:class "num"} (yen (:ringi/amount r))]
     [:span {:class "awai-caption"} (str (t (keyword "category" (name (:ringi/category r)))) " · "
                                         (t :ringi/drafter {:name (member-name (:ringi/drafter r))}))]
     [:span {:class "awai-caption"} (t :ringi/needs {:have have :need approvers})]
     (when board-resolution? (list [:span {:class "awai-caption warn"} (t :ringi/needs-board)] (source-note source)))
     (case st
       :draft (when (= me (:ringi/drafter r))
                [:button {:class "awai-button awai-button--primary" :on-click #(act! {:type :ringi/submit :id (:ringi/id r)})}
                 (t :ringi/submit)])
       :pending [:div {:class "actions"}
                 [:button {:class "awai-button awai-button--primary" :on-click #(act! {:type :ringi/decide :id (:ringi/id r) :decision :approve})} (t :ringi/approve)]
                 [:button {:class "awai-button awai-button--text" :data-destructive "true"
                           :on-click #(act! {:type :ringi/decide :id (:ringi/id r) :decision :reject})} (t :ringi/reject)]]
       :awaiting-board
       (let [opts (passed-board-items)]
         (when (seq opts)
           [:form {:class "form" :on-submit #(let [[mid aid] (str/split (:ref (form-values %)) #"\|")]
                                               (act! {:type :ringi/link-board :id (:ringi/id r) :meeting mid :agenda aid}))}
            (field (t :ringi/link-board) (select "ref" opts))
            [:button {:class "awai-button awai-button--tonal" :type "submit"} (t :common/add)]]))
       :approved (when rep
                   (let [sc (ringi/signer-check c r (:officer/id rep))]
                     [:span {:class "awai-caption"}
                      (t :ringi/signer {:status (str (:officer/name rep) " — "
                                                     (if (= :ok (:status sc))
                                                       (t :officer/representative-ok {:date (:officer/authority-verified-on rep)})
                                                       (t :officer/representative-unverified)))})]))
       nil)]))

(defn- ringi-view []
  (list
   [:h2 (t :ringi/heading)]
   [:ul {:class "cards"} (map ringi-card (reverse (:ringi @state)))]
   [:details {:class "awai-disclosure"}
    [:summary (t :ringi/new)]
    [:form {:class "form"
            :on-submit #(let [v (form-values %)]
                          (act! {:type :ringi/create
                                 :ringi {:ringi/id (str "r-" (now)) :ringi/title (:title v)
                                         :ringi/amount (js/parseInt (:amount v) 10)
                                         :ringi/category (keyword (:category v))}}))}
     (field (t :ringi/title) [:input {:name "title" :required true}])
     (field (t :ringi/amount) [:input {:name "amount" :type "number" :min 0 :step 1 :required true :inputmode "numeric"}])
     (field (t :ringi/category) (select "category" (for [k [:ordinary :important-asset :large-borrowing :important-employee :branch]]
                                                     [k (t (keyword "category" (name k)))])))
     [:button {:class "awai-button awai-button--primary" :type "submit"} (t :common/add)]]]))

;; ---------------------------------------------------------------- 会計

(defn- resolutions-for [step]
  (for [m (:meetings @state)
        :when (= (:meeting/body m) (if (= step :board-approved) :board :shareholders))
        a (:meeting/agenda m) :when (= :passed (get-in a [:agenda/result :status]))]
    [(str (:meeting/id m) "|" (:agenda/id a)) (str (:meeting/title m) " — " (:agenda/title a))]))

(defn- books-view []
  (let [s @state c (:company s) fs (:financials s)
        rows (:fs/rows fs)
        sm (books/summarize rows)
        nxt (books/next-step c fs)
        done (set (map :step (:fs/history fs)))]
    (list
     [:h2 (t :books/heading)]
     (when (seq rows)
       [:div {:class "awai-card"}
        [:dl {:class "stats"}
         (for [[k v] [[:books/assets (:assets sm)] [:books/liabilities (:liabilities sm)] [:books/equity (:equity sm)]
                      [:books/revenue (:revenue sm)] [:books/expenses (:expenses sm)] [:books/net-income (:net-income sm)]]]
           (list [:dt (t k)] [:dd {:class "num"} (yen v)]))]
        (if (:balanced? sm)
          (chip :up (str "✓ " (t :books/balanced)))
          (chip :down (str "✕ " (t :books/unbalanced))))])
     (when (seq rows)
       [:ol {:class "steps"}
        (for [{:keys [step source]} (books/steps c)]
          [:li {:class (if (done step) "done" "")}
           [:span (str (if (done step) "✓ " "○ ") (t (keyword "step" (name step))))]
           (source-note source)])])
     (cond
       (and (seq rows) nxt)
       (let [needs-res? (#{:board-approved :agm-approved} (:step nxt))]
         [:form {:class "form"
                 :on-submit #(let [v (form-values %)
                                   [mid aid] (when (:ref v) (str/split (:ref v) #"\|"))]
                               (act! (cond-> {:type :books/advance :step (:step nxt)}
                                       mid (assoc :meeting mid :agenda aid))))}
          (when needs-res? (field (t :books/resolution) (select "ref" (resolutions-for (:step nxt)))))
          [:button {:class "awai-button awai-button--primary" :type "submit"}
           (t :books/advance {:step (t (keyword "step" (name (:step nxt))))})]])
       (seq rows) [:p (t :books/done)])
     (when (empty? (:fs/history fs))
       [:details {:class "awai-disclosure" :open (empty? rows)}
        [:summary (t :books/import)]
        [:p {:class "awai-caption awai-muted"} (t :books/import-hint)]
        [:form {:class "form" :on-submit #(act! {:type :books/import :csv (:csv (form-values %))})}
         [:textarea {:name "csv" :rows 8 :required true :spellcheck "false"}]
         [:button {:class "awai-button awai-button--primary" :type "submit"} (t :books/import)]]]))))

;; ---------------------------------------------------------------- 会話

(defn- channel-label [ch]
  (if (= :dm (:channel/kind ch))
    (t :chat/dm {:name (member-name (first (disj (:channel/members ch) (:me @state))))})
    (str "# " (:channel/name ch))))

(defn- message-view [space ch-id m & [reply?]]
  [:li {:class (str "msg" (when reply? " msg--reply"))}
   [:div {:class "msg__meta"} [:strong (member-name (:message/author m))] [:span {:class "awai-caption awai-muted"} (:message/at m)]]
   [:p (:message/body m)]
   (when-not reply?
     (let [rs (chat/replies space ch-id (:message/id m))]
       (list
        (when (seq rs) [:ul {:class "thread"} (map #(message-view space ch-id % true) rs)])
        [:details {:class "awai-disclosure"}
         [:summary (if (seq rs) (t :chat/replies {:n (count rs)}) (t :chat/reply))]
         [:form {:class "compose" :on-submit #(act! {:type :chat/post :channel ch-id :parent (:message/id m)
                                                     :body (:body (form-values %))})}
          [:input {:name "body" :required true :placeholder (t :chat/placeholder)}]
          [:button {:class "awai-button awai-button--tonal" :type "submit"} (t :chat/send)]]])))])

(defn- chat-view []
  (let [s @state me (:me s) space (:space s)
        chs (chat/visible-channels space me)
        current (or (some #(when (= (:channel (deref ui)) (:channel/id %)) %) chs) (first chs))
        ch-id (:channel/id current)]
    (when ch-id (js/setTimeout #(when (pos? (chat/unread-count (:space @state) me ch-id))
                                  (act! {:type :chat/read :channel ch-id})) 0))
    (list
     [:h2 (t :chat/heading)]
     [:nav {:class "channels" :aria-label (t :chat/heading)}
      (for [ch chs :let [n (chat/unread-count space me (:channel/id ch))]]
        [:button {:class "awai-button awai-button--text" :aria-pressed (str (= ch-id (:channel/id ch)))
                  :on-click #(do (swap! ui assoc :channel (:channel/id ch)) (render!))}
         (channel-label ch) (when (pos? n) (str " · " (t :chat/unread {:n n})))])]
     [:details {:class "awai-disclosure"}
      [:summary (t :chat/open-dm)]
      [:div {:class "actions"}
       (for [m (get-in s [:company :company/members]) :when (not= me (:member/id m))]
         [:button {:class "awai-button awai-button--text"
                   :on-click #(do (act! {:type :chat/open-dm :with (:member/id m)})
                                  (swap! ui assoc :channel (chat/dm-id me (:member/id m))) (render!))}
          (:member/name m)])]]
     (when ch-id
       (list
        [:ul {:class "messages"} (map #(message-view space ch-id %) (chat/top-level space ch-id))]
        [:form {:class "compose" :on-submit #(act! {:type :chat/post :channel ch-id :body (:body (form-values %))})}
         [:input {:name "body" :required true :placeholder (t :chat/placeholder)}]
         [:button {:class "awai-button awai-button--primary" :type "submit"} (t :chat/send)]])))))

;; ---------------------------------------------------------------- 全体

(def tabs [[:home "◷"] [:meetings "◫"] [:ringi "✓"] [:books "¥"] [:chat "✉"]])

(defn- app-bar []
  (let [s @state c (:company s)]
    [:header {:class "app-bar"}
     [:div
      [:h1 (t :app/title)]
      [:p {:class "awai-caption"} (:company/name c)]]
     [:div {:class "app-bar__chips"}
      (when (:fixture s) (chip :warning (t :app/fixture)))
      (when (not= :reviewed (:review/status law/pack)) (chip :info (t :app/review)))]
     [:div {:class "app-bar__actions"}
      (field (t :app/acting-as)
             [:select {:on-change #(act! {:type :session/as :member (.. % -target -value)})}
              (for [m (:company/members c)]
                [:option {:value (:member/id m) :selected (= (:me s) (:member/id m))} (:member/name m)])])
      [:button {:class "awai-button awai-button--text"
                :on-click #(do (swap! ui update :lang {:ja :en :en :ja}) (render!))} (t :app/language)]]]))

(defn- view []
  [:div {:class "shell"}
   (app-bar)
   [:main {:class "content"}
    (when-let [e (:error @ui)]
      [:div {:class "banner banner--error" :role "alert"}
       (let [k (keyword "error" (name e))]
         (if (get-in i18n/messages [(:lang @ui) k]) (t k) (t :error/generic {:code (name e)})))])
    (case (:tab @ui)
      :home (home-view) :meetings (meetings-view) :ringi (ringi-view) :books (books-view) :chat (chat-view))
    [:footer {:class "foot awai-caption awai-muted"}
     [:p (if (:storage? @ui) (t :app/saved-locally) (t :app/storage-unavailable))]
     [:button {:class "awai-button awai-button--text" :data-destructive "true"
               :on-click #(let [s (app/sample)] (reset! state s) (save! s) (swap! ui assoc :error nil) (render!))}
      (t :app/reset)]]]
   [:nav {:class "awai-navigation"}
    [:div {:class "tabs"}
     (for [[k mark] tabs]
       [:a {:href (str "#" (name k)) :aria-current (when (= k (:tab @ui)) "page")
            :on-click #(do (.preventDefault %) (swap! ui assoc :tab k :error nil) (render!))}
        [:span {:class "awai-nav-indicator" :aria-hidden "true"} mark]
        (t (keyword "tab" (name k)))])]]])

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

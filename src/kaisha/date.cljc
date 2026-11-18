(ns kaisha.date
  "ISO 日付（YYYY-MM-DD）の計算。暦は先発グレゴリオ暦。時刻とタイムゾーンは扱わない。"
  (:require [clojure.string :as str]))

(defn- parse-int [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn- floor-div [a b] (long (Math/floor (/ a b))))

(defn- days-from-civil
  "1970-01-01 からの日数（H. Hinnant の civil_from_days の逆）。"
  [y m d]
  (let [y (if (<= m 2) (dec y) y)
        era (floor-div y 400)
        yoe (- y (* era 400))
        mp (mod (+ m 9) 12)
        doy (+ (quot (+ (* 153 mp) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- civil-from-days [z]
  (let [z (+ z 719468)
        era (floor-div z 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y (+ yoe (* era 400))
        doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (if (< mp 10) (+ mp 3) (- mp 9))]
    [(if (<= m 2) (inc y) y) m d]))

(defn- pad [n w]
  (let [s (str n)] (str (apply str (repeat (- w (count s)) "0")) s)))

(defn- leap? [y] (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400)))))

(defn days-in-month [y m]
  (case m 2 (if (leap? y) 29 28) (4 6 9 11) 30 31))

(defn parse
  "\"2026-03-31\" → [2026 3 31]。形式または暦として正しくなければ nil。"
  [s]
  (when (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}" s))
    (let [[y m d] (map parse-int (str/split s #"-"))]
      (when (and (<= 1 m 12) (<= 1 d (days-in-month y m))) [y m d]))))

(defn valid? [s] (some? (parse s)))

(defn ->day [s]
  (when-let [[y m d] (parse s)] (days-from-civil y m d)))

(defn day-> [n]
  (let [[y m d] (civil-from-days n)] (str (pad y 4) "-" (pad m 2) "-" (pad d 2))))

(defn add-days [s n] (day-> (+ (->day s) n)))

(defn days-between
  "b − a の日数。"
  [a b] (- (->day b) (->day a)))

(defn before? [a b] (< (->day a) (->day b)))
(defn on-or-before? [a b] (<= (->day a) (->day b)))

(defn add-months
  "月を足す。月末を越える日は月末に寄せる（2026-01-31 + 1 か月 = 2026-02-28）。"
  [s n]
  (let [[y m d] (parse s)
        idx (+ (* y 12) (dec m) n)
        y2 (floor-div idx 12)
        m2 (inc (- idx (* y2 12)))]
    (str (pad y2 4) "-" (pad m2 2) "-" (pad (min d (days-in-month y2 m2)) 2))))

(defn add-years [s n] (add-months s (* 12 n)))

(defn month-end
  "その月の末日。"
  [y m] (str (pad y 4) "-" (pad m 2) "-" (pad (days-in-month y m) 2)))

(defn fiscal-year-ends
  "決算月 fy-month の事業年度末日のうち、from 以後 to 以前のもの（昇順）。"
  [fy-month from to]
  (let [[fy] (parse from) [ty] (parse to)]
    (->> (range (dec fy) (inc ty))
         (map #(month-end % fy-month))
         (filter #(and (on-or-before? from %) (on-or-before? % to)))
         vec)))

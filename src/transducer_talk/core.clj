(ns transducer-talk.core
  (:require [clojure.core.async :refer [chan go >! <!! alts! timeout]]
            [clojure.core.reducers :as r]
            [cheshire.core :refer :all]
            [clj-http.client :as http])
  (:refer clojure.repl))


;;; EXPLORING TRANSDUCERS

;;; MOTIVATION

;;; Why? New in 1.7

;;; For whom? basic knowledge of Clojure needed/ may be not so
;;; interesting if you have already seen Rich Hickeys talks about tranducers

;;; There are at least two perspectives
;;; application developer vs. library developer

;;; From a user/application developer perspective this is about
;;; reuse/avoiding duplication

;;; How many implementations of map do we have in Clojure?

(doc map)
(doc clojure.core.async/map)
(doc r/map)

;;; Sidenote
;;; How many implementations of map do we have in Scala?
;;; 43
;;; Why: Object/Functional hybrid + static typing (?)


;;; Every time we want to use one of these implemenations we have to
;;; rejig our function/composition of functions for that specific implemention 

;;; QUESTION

;;; Could we write our transformation logic so that it is reusable in
;;; all contexts where we want to "map" something


;;; From the library/API dev perspective:
;;; Could we reuse/create composable code without having to reimplment
;;; map every time

;;; This is one aspect of what transducers allow us to do.

;;; BASIC

;;; defining transformations without knowing about collections/sources
;;; or the reducing function -> decoupling and reuse

(map inc (range 10))

;;; as of 1.7 we can do this
(map inc)

(def xf-inc (map inc))

(def xf-filter-odd (filter odd?))

;;; composition
(def odd-plus-one (comp xf-filter-odd xf-inc))

;;; using tranducers in context ...

(sequence odd-plus-one (range 10))

;;; no laziness -> push
(into [] odd-plus-one (range 10))

(transduce odd-plus-one + (range 10))

;;think about 'into' again...
(transduce odd-plus-one conj (range 10))


;;; no matter what input collection


;;; it can even be a core async channel
(def c (chan 1 odd-plus-one))

(go (>! c 3))

(<!! c)

(go (>! c 2))

(go (let [[v c] (alts! [c (timeout 1000)])]
      (println "Read " v " from " c)))

;;; We have now seen how we create transducers and how we use them in
;;; transducible contexts.

;;; Let's take a step back and think about what we just saw...



;;; TERMINOLOGY


;;; == Reducing function

;;; --> nothing new. A function that takes an
;;; accumulated result and a new input and returns a new accumulated
;;; result.
;;; Pseudo type signature:
;;; whatever, input -> whatever

;;; Examples +, conj
(+ 1 1)
(+ 2) ;;identity
(+) ;; neutral element

(* 1 2)
(* 2)
(*)

(conj [] 1)
(conj [1])
(conj)


;;; == Transducer
;;; --> three arity function defining a step based
;;; transformation ("leading across") of reducing functions
;;;
;;; (whatever, inputA -> whatever) -> (whatever, inputB -> whatever)
;;;
;;; We will look into the anatomy of a transducer in more detail in a minute


;;; == Transducible Context
;;; Things that can work with a transducer:
;;; * transduce
;;; * into
;;; * sequence
;;; * chan
;;;
;;;We have seen example use cases above 


;;; ANATOMY OF A TRANSDUCER

;;;map has a transducer generating function in arity-1

;;; as have
;;; cat mapcat filter remove take take-while take-nth drop drop-while replace partition-by partition-all keep keep-indexed dedupe random-sample

;;;
;;; this is not a transducer but will produce a transducer
;;; when given the function to use for mapping

;;; I'm using the example from above:
(map inc)

;;; mapping inc 
 ([inc]
    (fn [rf]
      (fn
        ([] (rf));;init -> neutral element
        ([result] (rf result));; complete -> identity
        ([result input];; step -> work
           (rf result (inc input))))))


;;; filter
([pred]
    (fn [rf]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result input]
           (if (pred input)
             (rf result input)
             result)))))



;;; What's next:
;;; * 2 more examples of tranducers (with state/with completion)
;;; * One example of a transducible context


;;; stateful transducer

(defn dedupe []
  (fn [rf]
    (let [prev (volatile! ::none)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result input]
          (let [prior @prev]
            (vreset! prev input)
              (if (= prior input)
                result
                (rf result input))))))))

;;; a more involved example with initialization and cleanup


(partition-by identity "ABBA")


(def by-odds  (partition-by odd?))

(into [] by-odds '(1 1 1 2 2 2 3 3 5 3 3 ))

;;; this is the tranducer generating part from clojure.core
(fn [rf]
    (let [a (java.util.ArrayList.)
          pv (volatile! ::none)]
      (fn
        ([] (rf));;; init
        ([result] ;;; complete
           (let [result (if (.isEmpty a)
                          result
                          (let [v (vec (.toArray a))]
                            ;;clear first!
                            (.clear a)
                            (unreduced (rf result v))))]
             (rf result)))
        ([result input]
           (let [pval @pv
                 val (odd? input)]
             (vreset! pv val)
             (if (or (identical? pval ::none)
                     (= val pval))
               (do
                (.add a input)
                 result)
               (let [v (vec (.toArray a))]
                 (.clear a)
                 (let [ret (rf result v)]
                   (when-not (reduced? ret);;;check for early termination
                     (.add a input)) ;;; we are good 
                   ret))))))))



;;; Examples for TRANSDUCIBLE CONTEXTS
;; Simplyfied version of transduce: 
(defn transduce
  ([xform f coll] (transduce xform f (f) coll))
  ([xform f init coll]
   (let [xf (xfrom f);;; bind transducer to reducing f -> transform
         ret (reduce coll xf init)])))


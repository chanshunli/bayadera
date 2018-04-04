;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Dragan Djuric"}
    uncomplicate.bayadera.core
  (:require [uncomplicate.commons
             [core :refer [release with-release let-release double-fn]]
             [utils :refer [dragan-says-ex cond-into]]]
            [uncomplicate.neanderthal
             [core :refer [transfer vctr native! matrix-type compatible? info]]
             [block :refer [column?]]]
            [uncomplicate.bayadera
             [distributions :refer [uniform-params gaussian-params student-t-params beta-params
                                    gamma-params exponential-params erlang-params]]
             [util :refer [srand-int]]]
            [uncomplicate.bayadera.internal
             [protocols :as p]
             [extensions :as extensions]
             [impl :refer :all]]))

(def ^:dynamic *bayadera-factory*)

(defmacro with-bayadera [factory-fn params & body]
  `(binding [*bayadera-factory* (~factory-fn ~@params)]
     (try ~@body
          (finally (release *bayadera-factory*)))))

;; =================== Dataset =================================================

(defn dataset
  ([data-matrix]
   (dataset *bayadera-factory* data-matrix))
  ([factory data-matrix]
   (if (and (compatible? factory data-matrix)
            (= :ge (matrix-type data-matrix)) (column? data-matrix))
     (->DatasetImpl (p/dataset-engine factory) data-matrix)
     (dragan-says-ex {:matrix-type (matrix-type data-matrix) :data (info data-matrix)
                      :factory (info factory) :errors
                      (cond-into []
                                 (not (= :ge (matrix-type data-matrix))) "matrix type is not :ge"
                                 (not (compatible? factory data-matrix))
                                 "data is not compatible with factory"
                                 (not (column? data-matrix)) "only column-major layout is supported")}))))

;; =================== Distributions ===========================================

(defn uniform
  ([^double a ^double b]
   (uniform *bayadera-factory* a b))
  ([factory ^double a ^double b]
   (->UniformDistribution factory (p/distribution-engine factory :uniform)
                          (vctr factory (uniform-params a b)) a b)))

(defn gaussian
  ([^double mu ^double sigma]
   (gaussian *bayadera-factory* mu sigma))
  ([factory ^double mu ^double sigma]
   (->GaussianDistribution factory (p/distribution-engine factory :gaussian)
                           (vctr factory (gaussian-params mu sigma)) mu sigma)))

(defn student-t
  ([^double nu ^double mu ^double sigma]
   (student-t *bayadera-factory* nu mu sigma))
  ([^double nu]
   (student-t nu 0.0 1.0))
  ([factory ^double nu ^double mu ^double sigma]
   (->StudentTDistribution factory (p/distribution-engine factory :student-t)
                           (vctr factory (student-t-params nu mu sigma)) nu mu sigma))
  ([factory ^double nu]
   (student-t factory nu 0.0 1.0)))

(defn beta
  ([^double a ^double b]
   (beta *bayadera-factory* a b))
  ([factory ^double a ^double b]
   (->BetaDistribution factory (p/distribution-engine factory :beta)
                       (vctr factory (beta-params a b)) a b)))

(defn gamma
  ([^double theta ^double k]
   (beta *bayadera-factory* theta k))
  ([factory ^double theta ^double k]
   (->BetaDistribution factory (p/distribution-engine factory :gamma)
                       (vctr factory (gamma-params theta k)) theta k)))

 (defn exponential
  ([^double lambda]
   (exponential *bayadera-factory* lambda))
  ([factory ^double lambda]
   (->ExponentialDistribution factory (p/distribution-engine factory :exponential)
                              (vctr factory (exponential-params lambda)) lambda)))

(defn erlang
  ([^double lambda ^long k]
   (erlang *bayadera-factory* lambda k))
  ([factory ^double lambda ^long k]
   (->ErlangDistribution factory (p/distribution-engine factory :erlang)
                         (vctr factory (erlang-params lambda k)) lambda k)))

;; ====================== Distribution =========================================

(defn distribution
  ([model]
   (distribution *bayadera-factory* model))
  ([factory model]
   (if (compatible? factory model)
     (->DistributionCreator factory (p/distribution-engine factory model)
                            (p/mcmc-factory factory model) model)
     (throw (IllegalArgumentException. (format "Incompatible model type: %s." (type model)))))))

(defn posterior-model
  ([name likelihood prior]
   (if (compatible? likelihood (p/model prior))
     (p/posterior-model (p/model prior) name likelihood)
     (throw (IllegalArgumentException.
             (format "Incompatible model types: %s and %s." (type likelihood) (type (p/model prior)))))))
  ([likelihood prior]
   (posterior-model (str (gensym "posterior")) likelihood prior)))

(defn posterior
  ([model]
   (posterior *bayadera-factory* model))
  ([factory model]
   (if (compatible? factory model)
     (->DistributionCreator factory (p/posterior-engine factory model)
                            (p/mcmc-factory factory model) model)
     (throw (IllegalArgumentException. (format "Incompatible model type: %s." (type model))))))
  ([^String name likelihood prior]
   (posterior *bayadera-factory* name likelihood prior))
  ([factory ^String name likelihood prior]
   (let-release [dist-creator (posterior factory (posterior-model name likelihood prior))]
     (if (satisfies? p/Distribution prior)
       (if (compatible? factory (p/parameters prior))
         (->PosteriorCreator dist-creator (transfer (p/parameters prior)))
         (throw (IllegalArgumentException.
                 (format "Incompatible parameters type: %s."
                         (type (p/parameters prior))))))
       dist-creator))))

;; ====================== Measures =============================================

(defn mean [x]
  (native! (p/mean x)))

(defn mode [x]
  (native! (p/mode x)))

(defn median [x]
  (native! (p/median x)))

(defn variance [x]
  (native! (p/variance x)))

(defn sd [x]
  (native! (p/sd x)))

;;TODO rename to pd or density
(defn pdf [dist xs]
  (if (compatible? dist (p/data xs))
    (p/pdf (p/engine dist) (p/parameters dist) (p/data xs))
    (format "Incompatible xs: %s." (type xs))))

;;TODO rename to log-pd or log-density
(defn log-pdf [dist xs]
  (if (compatible? dist (p/data xs))
    (p/log-pdf (p/engine dist) (p/parameters dist) (p/data xs))
    (format "Incompatible xs: %s." (type xs))))

(defn likelihood [lik xs]
  (throw (UnsupportedOperationException. "TODO")))

(defn evidence ^double [dist xs]
  (if (compatible? dist (p/data xs))
    (p/evidence (p/engine dist) (p/parameters dist) (p/data xs))
    (format "Incompatible xs: %s." (type xs))))

;; ================= Estimation ===============================================

(defn sampler
  ([dist]
   (p/sampler dist))
  ([dist options]
   (p/sampler dist options)))

(defn sample!
  ([sampler]
   (p/sample! sampler))
  ([sampler n]
   (p/sample! sampler n)))

(defn sample
  ([sampler]
   (p/sample sampler))
  ([sampler n]
   (p/sample sampler n)))

(defn init!
  ([samp seed]
   (p/init! samp seed))
  ([samp]
   (p/init! samp (srand-int))))

(defn histogram!
  ([estimator n]
   (p/histogram! estimator n)))

(defn histogram
  ([estimator]
   (p/histogram estimator)))

;; ============================================================================

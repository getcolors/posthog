(ns io.github.getcolors.posthog.ssh-config-test
  "Conformance with the workspace SSH Config Standard."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.posthog.ssh-config :as ssh-config]
            [io.github.getcolors.posthog.tools :as tools]
            [io.github.getcolors.posthog.validate-test :refer [fixture optout]]
            [io.github.getcolors.posthog.workflow :as workflow]))

;; §2 the alias and the identity file

(deftest alias-is-the-profile
  (is (= "posthog-fixture" (ssh-config/host-alias (fixture)))))

(deftest identity-file-keeps-the-tilde
  ;; An expanded home directory would make the rendered block differ per
  ;; workstation; OpenSSH expands the tilde itself.
  (is (= "~/.ssh/posthog-fixture" (ssh-config/identity-file (fixture))))
  (is (not (str/includes? (ssh-config/identity-file (fixture))
                          (System/getProperty "user.home")))))

(deftest the-marker-is-the-alias-alone
  ;; The profile is <package>-<suffix>, so a marker carrying the package name
  ;; too would repeat it: "# BEGIN posthog posthog-digitalocean".
  (is (= "# BEGIN posthog-digitalocean ANSIBLE MANAGED BLOCK"
         (ssh-config/begin-marker "posthog-digitalocean")))
  (is (= "# END posthog-digitalocean ANSIBLE MANAGED BLOCK"
         (ssh-config/end-marker "posthog-digitalocean"))))

;; §5 never adopt

(deftest a-foreign-stanza-is-found
  (let [lines ["Host other" "    HostName 192.0.2.1" "" "Host posthog-fixture"]]
    (is (= 4 (ssh-config/foreign-stanza-line lines "posthog-fixture")))))

(deftest our-own-block-is-not-foreign
  (let [alias "posthog-fixture"
        lines [(ssh-config/begin-marker alias)
               (str "Host " alias)
               "    HostName 192.0.2.1"
               (ssh-config/end-marker alias)]]
    (is (nil? (ssh-config/foreign-stanza-line lines alias)))))

(deftest a-stanza-after-our-block-is-still-foreign
  (let [alias "posthog-fixture"
        lines [(ssh-config/begin-marker alias)
               (str "Host " alias)
               (ssh-config/end-marker alias)
               (str "Host " alias)]]
    (is (= 4 (ssh-config/foreign-stanza-line lines alias)))))

(deftest a-block-under-a-retired-marker-is-foreign
  ;; The superseded `# BEGIN posthog <alias>` marker is gone, so a block
  ;; still carrying it belongs to nobody this package knows and must stop the
  ;; run rather than being silently overwritten. Reinstating a marker means
  ;; putting it back in owned-markers at the same time.
  (let [alias "posthog-digitalocean"
        lines [(str "# BEGIN posthog " alias " ANSIBLE MANAGED BLOCK")
               (str "Host " alias)
               (str "# END posthog " alias " ANSIBLE MANAGED BLOCK")]]
    (is (= 2 (ssh-config/foreign-stanza-line lines alias)))))

(deftest a-multi-pattern-host-line-counts
  (is (= 1 (ssh-config/foreign-stanza-line ["Host web posthog-fixture db"]
                                           "posthog-fixture"))))

(deftest an-unrelated-file-is-left-alone
  (is (nil? (ssh-config/foreign-stanza-line ["Host build" "Host posthog-other"]
                                            "posthog-fixture"))))

(deftest preflight-refuses-rather-than-overwrites
  (with-redefs [ssh-config/adopt-error (fn [_] "already declares `Host x`")
                ssh-config/placement-error (fn [_] nil)]
    (let [r (ssh-config/preflight! (fixture))]
      (is (= 1 (:green/exit r)))
      (is (str/includes? (:green/err r) "already declares")))))

(deftest preflight-passes-a-clean-file
  (with-redefs [ssh-config/adopt-error (fn [_] nil)
                ssh-config/placement-error (fn [_] nil)]
    (is (nil? (:green/exit (ssh-config/preflight! (fixture)))))))

;; §5 placement. The block is written with insertbefore: BOF, because
;; blockinfile anchors insertbefore on the *last* match and has no firstmatch.

(deftest an-option-above-the-first-host-is-refused
  ;; It is global today; a BOF insert would capture it into one stanza.
  (is (= 1 (ssh-config/leading-option-line ["ServerAliveInterval 60" "Host a"])))
  (is (= 3 (ssh-config/leading-option-line ["# comment" "" "IdentitiesOnly yes" "Host a"]))))

(deftest a-file-that-opens-with-a-host-is-fine
  (is (nil? (ssh-config/leading-option-line ["Host a" "    User root"])))
  (is (nil? (ssh-config/leading-option-line ["# lead comment" "" "Host a" "    User root"])))
  (is (nil? (ssh-config/leading-option-line ["Match host b" "    User root"]))))

(deftest a-file-of-only-comments-is-fine
  (is (nil? (ssh-config/leading-option-line ["# nothing here" ""]))))

(deftest placement-error-mentions-the-recovery
  (with-redefs [ssh-config/leading-option-line (fn [_] 4)]
    (when (.isFile (ssh-config/config-path))
      (let [err (ssh-config/placement-error (fixture))]
        (is (str/includes? err "line 4"))
        (is (str/includes? err "Host *"))))))

;; §6 build determinism

(deftest build-and-dry-run-never-read-the-config
  ;; The only reader is adopt-error, and it must not run on a rendered-only
  ;; event. Redefining it to throw proves nothing in the build path calls it.
  (with-redefs [ssh-config/adopt-error (fn [_] (throw (ex-info "read ~/.ssh/config" {})))
                ssh-config/placement-error (fn [_] (throw (ex-info "read ~/.ssh/config" {})))]
    (doseq [opts [(assoc (fixture) :green/event :build)
                  (assoc (fixture) :green/event :create :green/dry-run true)]]
      (is (= 0 (:green/exit (workflow/start-step opts {})))))))

(deftest the-local-play-renders-no-address
  ;; Address, user and alias are run-time facts and travel as extra-vars, so
  ;; the rendered playbook carries none of them.
  (let [data (tools/ansible-local-data (assoc (fixture) :ip "203.0.113.7"))]
    (is (not (contains? data :ip-rendered)))
    (is (= "~/.ssh/posthog-fixture" (:ssh-config-identity-file data)))))

(deftest the-local-stage-renders-three-files
  (let [targets (map #(str (:target %)) (tools/ansible-local-specs (fixture)))]
    (is (some #(str/ends-with? % "/ansible.cfg") targets))
    (is (some #(str/ends-with? % "/inventory.ini") targets))
    (is (some #(str/ends-with? % "/main.yml") targets))
    (is (every? #(str/includes? % "posthog-ansible-local") targets))))

;; §3 the identity file follows keygen mode

(deftest keygen-mode-decides-the-identity-lines
  (is (true? (:ssh-keygen (tools/ansible-local-data (fixture)))))
  (is (false? (:ssh-keygen (tools/ansible-local-data (optout))))))

;; §4 lifecycle

(deftest create-writes-the-block-after-compute-and-before-convergence
  (is (= [:posthog/ssh-config]
         (vec (rest (workflow/wire-fn :posthog/infrastructure {:green/event :create})))))
  (is (= [:posthog/dns]
         (vec (rest (workflow/wire-fn :posthog/ssh-config {:green/event :create}))))))

(deftest delete-removes-the-block-before-the-destroy
  ;; The opposite of the keypair, which goes last. A stale block is harmless; a
  ;; key removed early locks the operator out of a machine that still exists.
  (is (= [:posthog/ssh-config]
         (vec (rest (workflow/wire-fn :posthog/dns {:green/event :delete})))))
  (is (= [:posthog/infrastructure]
         (vec (rest (workflow/wire-fn :posthog/ssh-config {:green/event :delete})))))
  (is (= [:posthog/ssh-cleanup]
         (vec (rest (workflow/wire-fn :posthog/infrastructure {:green/event :delete}))))))

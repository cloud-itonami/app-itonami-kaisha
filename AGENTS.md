# Kaisha

Follow the workspace-level AGENTS.md (Kotoba end-user app contract, Linear tracking) and network-awai/awai-os AGENTS.md (Figma design first, Awai design system, app ownership).

- Domain rules live in `src/kaisha/*.cljc` as pure functions. The UI and the Tauri host only call them; do not duplicate a rule in JS or Rust.
- Every legal rule cites a source in `kaisha.law/sources` (e-Gov law API response, revision ID and sha256, stored under `docs/evidence/`). Do not add a rule without a fetched, hashed source. The pack is unreviewed; never present a result as a legal decision, and return `:undetermined` instead of guessing for unsupported cases.
- The app sends nothing: `descriptor.json` declares no operations and the CSP allows no network. Any future external operation (AwaiFS, e-Gov, kaikei) must go through the canonical kotoba-client-runtime broker with per-operation consent; never add an app-local approval UI or proxy.
- Internal roles never prove representative authority. Keep `:unverified` until the register has been checked.
- Run `bb test` (and `clojure -M:test`) before every commit. Check Japanese and English at phone width.

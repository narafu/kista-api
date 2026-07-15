# Admin Runtime Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add admin-managed runtime settings for signup approval, broker account registration availability, strategy creation availability, and strategy creation field defaults/options without changing existing account or strategy behavior.

**Architecture:** Keep `/api/meta` as static capability metadata with its existing cache policy. Add a separate no-store runtime settings API for user-facing UI and backend enforcement. Admin settings are persisted server-side and enforced at write boundaries: signup, account registration/connection test, and new strategy creation. Existing accounts and strategies continue to operate with their stored state.

**Tech Stack:** Java 21, Spring Boot, Flyway, JPA, JUnit 5, Mockito, Next.js/React, React Query.

## Global Constraints

- Do not change `/api/meta` into a dynamic settings endpoint.
- Keep `/api/meta` cacheable for stable metadata.
- New runtime settings responses used by UI must use `Cache-Control: no-store`.
- Runtime settings must be enforced server-side; UI hiding is not sufficient.
- Broker disablement blocks only new broker registration and connection tests.
- Existing broker accounts remain usable for trading, statistics, balance checks, and order execution.
- Strategy type disablement blocks only new strategy creation.
- Existing strategies remain usable for edit, pause/resume, scheduler next cycle, order execution, and history.
- Strategy detail settings are templates and validation rules for new strategy creation only.
- Changing admin detail defaults/options must not mutate existing strategies.
- Existing strategy edit must keep strategy type, ticker, division count, and VR detail values as stored/read-only unless a separate edit feature is explicitly designed later.
- If a field is `customizable=false`, UI should hide or lock the field and submit the default implicitly.
- If a field is `customizable=false`, backend must apply the default when the request omits the value and reject explicit non-default values.
- When approval is turned off, existing `PENDING` users should become `ACTIVE`.
- `REJECTED` users must not be auto-activated by turning approval off.
- If a rejected user reapplies while approval is off, the new approval flow may activate them immediately according to the existing reapply policy. This requires an explicit code change to `UserService.reapply()` (it currently always transitions to `PENDING` regardless of any setting) plus a dedicated test — see Task 3 Step 3.
- PRIVACY/VR ticker enforcement in Task 5 is an intentional API contract change, not a restatement of existing behavior: `Strategy.Type.resolveTicker()` today silently coerces any input ticker to `SOXL`/`TQQQ`. Rejecting explicit non-default values with a 400 replaces that silent coercion — call this out in the PR description.
- `/api/runtime-config` must be `permitAll` in `SecurityConfig`, matching the existing `GET /api/meta` matcher — it must be readable before login so signup/pending screens can react to `approvalRequired`.
- New runtime settings services/controllers must go through a `domain/port/in/*UseCase` interface like every other application service — `RuntimeConfigController`/`AdminSettingsController` may not depend on `RuntimeSettingsService` directly (ArchUnit forbids `adapter.in → application`).

---

## Current Findings

- `MetaController` currently exposes brokers and strategy type metadata through `/api/meta`.
- Frontend account registration currently renders broker choices from `meta.brokers`.
- Frontend strategy creation currently renders strategy types from `meta.strategyTypes`.
- Frontend strategy form currently hardcodes some creation options:
  - INFINITE division count UI options are hardcoded as `20`, `30`, `40`.
  - VR band width UI options are hardcoded as `10`, `15`, `20`.
  - VR interval week UI options are hardcoded as `1`, `2`, `4`.
  - VR defaults are currently interval `2`, band width `15`, and hold mode.
- Backend strategy registration currently resolves ticker and default division count in `StrategyService.register()`.
- Backend strategy edit does not currently update strategy type or detailed creation fields, which matches the target policy.
- Pre-existing inconsistency discovered during planning review: `InfiniteCycleOrderStrategy.availableDivisionCounts()` (surfaced via `/api/meta`'s `StrategyTypeMeta.divisionCounts`) returns `[20]` only, while the frontend already offers `20/30/40` and the domain trading math (`InfinitePosition`, `CycleOrderComputer`) genuinely supports any positive divisionCount. Seeding runtime-config defaults as `[20]` (matching the stale `/api/meta` value) would silently break current 30/40-division registrations — the seed must instead match actual current behavior (`[20, 30, 40]`, default `20`). After this ships, `/api/meta`'s `divisionCounts` field should be treated as "does this strategy type use the division-count concept" (non-empty check) only — the frontend must source the actual allowed-value list from `/api/runtime-config`, not from `/api/meta`, to avoid two conflicting SSOTs.

---

## Target Runtime Settings Contract

Create a public runtime settings endpoint:

- `GET /api/runtime-config`
- Authentication: `permitAll` — same as `GET /api/meta` (`SecurityConfig.java:50`). Must be readable by unauthenticated users so login/signup/pending screens can react to `approvalRequired` before the user has a token.
- Cache policy: `Cache-Control: no-store`.
- Purpose: dynamic settings that can change through admin UI and must be reflected quickly in user UI.

Example response:

```json
{
  "auth": {
    "approvalRequired": true
  },
  "brokers": {
    "KIS": {
      "enabled": true
    },
    "TOSS": {
      "enabled": true
    }
  },
  "strategies": {
    "INFINITE": {
      "enabled": true,
      "fields": {
        "ticker": {
          "customizable": true,
          "allowedValues": ["MAGX", "USD", "TQQQ", "SOXL"],
          "defaultValue": "SOXL"
        },
        "divisionCount": {
          "customizable": true,
          "allowedValues": [20, 30, 40],
          "defaultValue": 20
        }
      }
    },
    "PRIVACY": {
      "enabled": true,
      "fields": {
        "ticker": {
          "customizable": false,
          "allowedValues": ["SOXL"],
          "defaultValue": "SOXL"
        }
      }
    },
    "VR": {
      "enabled": true,
      "fields": {
        "ticker": {
          "customizable": false,
          "allowedValues": ["TQQQ"],
          "defaultValue": "TQQQ"
        },
        "recurringMode": {
          "customizable": true,
          "allowedValues": ["DEPOSIT", "HOLD", "WITHDRAW"],
          "defaultValue": "HOLD"
        },
        "bandWidth": {
          "customizable": true,
          "allowedValues": [10, 15, 20],
          "defaultValue": 15
        },
        "intervalWeeks": {
          "customizable": true,
          "allowedValues": [1, 2, 4],
          "defaultValue": 2
        }
      }
    }
  }
}
```

`recurringMode` semantics: the backend has no `recurringMode` enum today — only a signed `recurringAmount` integer (positive=DEPOSIT, `0`=HOLD, negative=WITHDRAW; see `docs/agents/constraints.md` "VR 공식"). `recurringMode` in this contract is a UI-facing direction selector only, not a magnitude:
- `customizable=false` means only `HOLD` is offered and the backend forces `recurringAmount=0`, rejecting any nonzero explicit value.
- `customizable=true` means all three directions are selectable; the actual numeric amount stays a free user input, validated by the existing `validateVrCommand` magnitude rules (`StrategyService.java`), not by `allowedValues`.
- `defaultValue` only pre-selects the UI direction (`HOLD` ⇒ 0); it does not imply a default magnitude for DEPOSIT/WITHDRAW.

Admin endpoint:

- `GET /api/admin/settings`
- `PUT /api/admin/settings`
- Authentication: admin only.
- Response shape can match `/api/runtime-config`, with optional audit metadata if needed.

---

## File Structure

Backend candidates:

- Create `src/main/resources/db/migration/V23__create_admin_runtime_settings.sql` (re-verify against `ls src/main/resources/db/migration` at implementation time)
- Create `src/main/java/com/kista/domain/model/settings/RuntimeSettings.java`
- Create `src/main/java/com/kista/domain/model/settings/StrategyCreationSettings.java`
- Create `src/main/java/com/kista/domain/port/out/RuntimeSettingsPort.java`
- Create `src/main/java/com/kista/domain/port/in/RuntimeSettingsUseCase.java` (read-only, for `RuntimeConfigController`)
- Create `src/main/java/com/kista/domain/port/in/AdminSettingsUseCase.java` (read/update, for `AdminSettingsController`) — required by ArchUnit (`adapter.in` may not depend on `application` directly)
- Create `src/main/java/com/kista/application/service/settings/RuntimeSettingsService.java` (package-private, implements both UseCases)
- Create `src/main/java/com/kista/adapter/in/web/RuntimeConfigController.java`
- Create `src/main/java/com/kista/adapter/in/web/AdminSettingsController.java`
- Create persistence adapter classes under `src/main/java/com/kista/adapter/out/persistence/settings/` (owns JSONB↔domain-record serialization/validation — no other layer should parse the raw JSONB)
- Modify `src/main/java/com/kista/adapter/in/web/security/SecurityConfig.java` — add `GET /api/runtime-config` to the `permitAll` matchers alongside `GET /api/meta`
- Modify `src/main/java/com/kista/application/service/user/UserService.java` (signup path + `reapply()`)
- Modify `src/main/java/com/kista/application/service/account/AccountService.java`
- Modify `src/main/java/com/kista/application/service/strategy/StrategyService.java`
- Modify `src/main/java/com/kista/adapter/in/web/MetaController.java` only if shared DTO extraction is needed; do not make it dynamic.

Frontend candidates in `../kista-ui`:

- Create runtime config API/types/hooks under the existing API/entity convention.
- Modify account registration broker step to filter disabled brokers.
- Modify strategy creation form to filter disabled strategy types and consume field defaults/options from runtime config.
- Modify login/signup/pending copy to react to `approvalRequired`.
- Add admin settings page and save flow under the existing admin route pattern.

---

### Task 1: Add Backend Persistence and Defaults

**Files:**

- Create migration for runtime settings persistence.
- Create domain model and persistence adapter for loading/saving settings.
- Add default values that preserve current production behavior.

- [ ] **Step 1: Create migration**

Latest applied migration is `V22__reorder_privacy_trade_base_release_date.sql` — this must be `V23__create_admin_runtime_settings.sql` (re-check `ls src/main/resources/db/migration` at implementation time in case other work has landed first; invoke the `flyway-migration` skill).

Use a small key-value table to avoid repeated schema churn as settings expand. Include `created_at` even though the table effectively holds one logical row, to match the repo's column-order convention (`docs/agents/constraints.md`: `pk, fk, business columns…, created_at, updated_at, deleted_at`) and to allow `BaseAuditEntity` if the entity ends up needing both timestamps:

```sql
CREATE TABLE admin_runtime_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Seed one row with the default runtime settings:

- `approvalRequired=true`
- `KIS.enabled=true`
- `TOSS.enabled=true`
- `INFINITE.enabled=true`
- `PRIVACY.enabled=true`
- `VR.enabled=true`
- INFINITE ticker customizable with current available tickers.
- INFINITE division count customizable, allowed values `[20, 30, 40]`, default `20` (must match current frontend/domain behavior — see "Current Findings" note on the `/api/meta` divisionCounts inconsistency).
- PRIVACY ticker fixed `SOXL`.
- VR ticker fixed `TQQQ`.
- VR recurring mode default `HOLD`.
- VR band width default `15`.
- VR interval weeks default `2`.

- [ ] **Step 2: Create domain settings model**

Model settings as typed records/classes instead of passing raw maps through application services.

Required behavior:

- Runtime settings can be loaded with safe defaults if the DB row is missing.
- Runtime settings validate broker keys and strategy keys against known enums.
- Strategy field settings validate `defaultValue` is included in `allowedValues`.
- Non-customizable fields require exactly one allowed default or must reject non-default input explicitly.

- [ ] **Step 3: Create persistence adapter**

Implement load/save through `RuntimeSettingsPort`.

Expected methods:

- `RuntimeSettings load()`
- `RuntimeSettings save(RuntimeSettings settings)`

---

### Task 2: Add Runtime and Admin APIs

**Files:**

- Create `RuntimeConfigController`.
- Create `AdminSettingsController`.
- Add DTOs in the existing web DTO package style.

- [ ] **Step 1: Add public runtime config API**

Implement:

- `GET /api/runtime-config`
- Return current runtime settings.
- Add `Cache-Control: no-store`.
- Do not include admin-only metadata unless explicitly needed by UI.

- [ ] **Step 2: Add admin settings API**

Implement:

- `GET /api/admin/settings`
- `PUT /api/admin/settings`

Behavior:

- Admin only.
- Validate the full payload before saving.
- Persist as a single atomic settings update.
- Record audit log if the existing admin audit pattern is available.
- On `approvalRequired: true -> false`, activate existing `PENDING` users in the same transaction or through a clearly documented application service call.
- Do not activate `REJECTED` users.

---

### Task 3: Enforce Approval Setting

**Files:**

- Modify `UserService` and related signup/approval request services.
- Add focused tests around signup and approval state transitions.

- [ ] **Step 1: Apply setting on signup**

Behavior:

- If `approvalRequired=true`, preserve current behavior.
- If `approvalRequired=false`, create or transition new eligible signup users as `ACTIVE`.
- Preserve deleted-user and rejected-user rejoin policies already present in the service.

- [ ] **Step 2: Apply setting update side effect**

When admin changes `approvalRequired` from true to false:

- Find existing `PENDING` users via `UserPort.findAllByStatus(PENDING)`.
- Activate them by reusing `UserService.approve()` per user (or equivalent), which publishes `UserApprovedEvent` per user (AFTER_COMMIT — safe to keep inside the settings-update transaction).
- Decide and document explicitly whether this should fan out one Telegram/SSE notification per newly-activated user, or use a distinct code path that updates status without the per-user notification side effect if that Telegram volume is undesirable. Default recommendation: keep per-user notifications (matches "you were approved" semantics), but flag this to the admin/product owner before shipping.
- Leave `REJECTED` users unchanged.
- Avoid duplicate approval request side effects.

- [ ] **Step 3: Apply setting to reapply flow**

`UserService.reapply()` currently always transitions `PENDING`→`PENDING` (with cooldown) or `REJECTED`→`PENDING` regardless of any setting. Modify it so that:

- If `approvalRequired=true`, preserve current behavior (transition to `PENDING`).
- If `approvalRequired=false`, transition a `REJECTED` user who reapplies directly to `ACTIVE` instead of `PENDING`, following the same immediate-activation policy as new signups (constraint list item on reapply-while-approval-off).
- `PENDING` users calling `reapply()` while `approvalRequired=false` should not occur in practice (they'd already have been auto-activated by Step 2), but the cooldown/validation logic must not throw unexpectedly if it does.
- Add a dedicated test: `REJECTED` user reapplies while `approvalRequired=false` → status becomes `ACTIVE`.

---

### Task 4: Enforce Broker Registration Settings

**Files:**

- Modify `AccountService`.
- Modify account controller validation if controller currently owns broker checks.
- Add service/controller tests.

- [ ] **Step 1: Block disabled broker registration**

Behavior:

- If `brokers.KIS.enabled=false`, reject new KIS account registration.
- If `brokers.TOSS.enabled=false`, reject new Toss account registration.
- Return a user-safe validation error, not a generic server error.

- [ ] **Step 2: Block disabled broker connection tests**

Connection test APIs must use the same broker enablement policy as registration.

- [ ] **Step 3: Preserve existing accounts**

Do not add broker enablement checks to:

- Trading execution.
- Scheduler flows.
- Account list/detail.
- Balance/statistics reads.
- Order history.

---

### Task 5: Enforce Strategy Creation Settings

**Files:**

- Modify `StrategyService.register()`.
- Add helper/service for applying strategy creation settings.
- Add tests for every strategy type and field.

- [ ] **Step 1: Block disabled strategy creation**

Behavior:

- If `strategies.INFINITE.enabled=false`, reject new INFINITE strategy creation.
- If `strategies.PRIVACY.enabled=false`, reject new PRIVACY strategy creation.
- If `strategies.VR.enabled=false`, reject new VR strategy creation.
- Existing strategy edit and scheduler flows must not check this flag.

- [ ] **Step 2: Apply field defaults**

When strategy creation omits a field:

- Use runtime config default value.
- Preserve current fixed ticker policy for PRIVACY and VR.
- Preserve current VR default semantics for hold mode.

- [ ] **Step 3: Validate allowed values**

Reject request values outside configured `allowedValues`.

Specific rules:

- INFINITE ticker must be one of configured ticker values.
- INFINITE division count must be one of configured division counts.
- PRIVACY ticker must be `SOXL`.
- VR ticker must be `TQQQ`.
- VR recurring mode must map to the existing `recurringAmount` sign model.
- VR band width must be one of configured band widths.
- VR interval weeks must be one of configured interval weeks.

- [ ] **Step 4: Enforce non-customizable fields**

If a field is non-customizable:

- Missing value means use default.
- Explicit default value is allowed.
- Explicit non-default value is rejected.

Notes:

- For PRIVACY/VR ticker specifically, this replaces `Strategy.Type.resolveTicker()`'s current silent coercion (any input ticker is quietly forced to `SOXL`/`TQQQ`) with an explicit 400 rejection when the request ticker disagrees with the fixed value. This is a deliberate, user-visible API behavior change — call it out in the PR description, since a client sending an arbitrary ticker today succeeds silently and will start failing.
- `RegisterStrategyCommand.divisionCount` is a primitive `int`; the existing convention (`StrategyService.java`) already treats `0` as "not provided" (`cmd.divisionCount() > 0 ? cmd.divisionCount() : DEFAULT`). Reuse this sentinel — do not add a second "was this field present" concept — but document it explicitly in the command's Javadoc-equivalent inline comment so future editors don't assume `0` is a valid explicit division count.

---

### Task 6: Update Frontend Runtime Config Consumption

**Repository:** `../kista-ui`

- [ ] **Step 1: Add runtime config client**

Create a React Query hook for `/api/runtime-config`.

Expected behavior:

- Do not rely on `/api/meta` for dynamic toggles.
- Use short or zero stale time, or explicitly refetch on page focus where appropriate.
- Treat runtime config as no-store dynamic data.

- [ ] **Step 2: Update account registration UI**

Behavior:

- Hide disabled brokers in the broker selection step.
- If all brokers are disabled, show a clear unavailable-state message.
- Prevent continuing without an enabled broker.

- [ ] **Step 3: Update strategy creation UI**

Behavior:

- Hide disabled strategy types.
- If all strategy types are disabled, show a clear unavailable-state message.
- Use runtime settings for ticker/division/VR options and defaults.
- Remove hardcoded INFINITE division count options from the form.
- Remove hardcoded VR band width and interval week options from the form.
- Hide or lock non-customizable fields.

- [ ] **Step 4: Update approval-related UI copy**

Behavior:

- If `approvalRequired=false`, do not show signup/login/pending messages that imply admin approval is required.
- Preserve pending/rejected UI for users that are actually in those states.

---

### Task 7: Add Admin Settings UI

**Repository:** `../kista-ui`

- [ ] **Step 1: Add admin settings page**

Create a page under the existing admin route structure.

Sections:

- Approval required toggle.
- Broker enablement toggles for KIS and Toss.
- Strategy enablement toggles for INFINITE, PRIVACY, and VR.
- INFINITE field settings.
- PRIVACY fixed ticker display.
- VR field settings.

- [ ] **Step 2: Add validation before save**

UI should prevent clearly invalid payloads:

- Empty allowed values.
- Default not included in allowed values.
- Non-customizable field with conflicting explicit defaults.

Backend remains the final source of truth.

- [ ] **Step 3: Add save/reset behavior**

Behavior:

- Save through `PUT /api/admin/settings`.
- Refetch runtime config after save.
- Show clear success/error state.
- Avoid optimistic UI for settings that have side effects, especially approval activation.

---

### Task 8: Tests and Verification

Backend verification:

- [ ] Runtime settings migration applies.
- [ ] Runtime settings defaults load when DB row exists.
- [ ] `/api/runtime-config` returns no-store response.
- [ ] Admin settings API rejects invalid payloads.
- [ ] Approval-off signup creates/keeps eligible users active.
- [ ] Approval true-to-false update activates pending users only.
- [ ] `REJECTED` user calling `reapply()` while `approvalRequired=false` becomes `ACTIVE` (not `PENDING`).
- [ ] `REJECTED` user calling `reapply()` while `approvalRequired=true` still becomes `PENDING` (unchanged behavior).
- [ ] INFINITE strategy creation with `divisionCount=30` or `40` still succeeds with default runtime-config seed (regression guard for the "preserve current production behavior" constraint).
- [ ] Disabled broker registration is rejected.
- [ ] Disabled broker connection test is rejected.
- [ ] Existing account operations do not check broker enabled flags.
- [ ] Disabled strategy creation is rejected.
- [ ] Existing strategy edit does not check strategy enabled flags.
- [ ] Strategy creation applies defaults and rejects disallowed values.
- [ ] Non-customizable fields reject explicit non-default values.

Frontend verification:

- [ ] Account registration hides disabled brokers.
- [ ] Account registration handles all-brokers-disabled state.
- [ ] Strategy creation hides disabled strategy types.
- [ ] Strategy creation handles all-strategies-disabled state.
- [ ] Strategy creation uses runtime division count options.
- [ ] Strategy creation uses runtime VR band width and interval options.
- [ ] Non-customizable fields are hidden or locked.
- [ ] Approval-related messages disappear when approval is off.
- [ ] Admin settings page saves and refetches settings.

Recommended commands:

```bash
./gradlew compileJava
./gradlew test --tests '*RuntimeSettings*' --tests '*UserService*' --tests '*AccountService*' --tests '*StrategyService*'
```

For frontend:

```bash
npm run lint
npm run test -- --run
npm run build
```

---

## Rollout Plan

- [ ] Deploy backend with migration and default settings that preserve current behavior.
- [ ] Verify `/api/meta` still responds as before and remains cacheable.
- [ ] Verify `/api/runtime-config` returns default settings with `Cache-Control: no-store`.
- [ ] Deploy frontend runtime config consumption while all toggles remain enabled.
- [ ] Deploy admin settings page.
- [ ] Test disabling one broker in production-like environment:
  - New registration blocked.
  - Existing account list/detail still works.
  - Existing account trading path still works.
- [ ] Test disabling one strategy in production-like environment:
  - New creation blocked.
  - Existing strategy detail/edit/pause/resume still works.
  - Scheduler still processes existing strategies.
- [ ] Test approval off in production-like environment:
  - New eligible signup becomes active.
  - Pending users are activated.
  - Rejected users are not auto-activated.

---

## Suggested Implementation Order

1. Backend runtime settings model, migration, persistence, and default load.
2. Backend public/admin APIs with validation and no-store response.
3. Backend enforcement in signup, broker registration, broker connection test, and strategy creation.
4. Frontend runtime config client and create-flow UI updates.
5. Admin settings UI.
6. Focused backend/frontend tests.
7. Production rollout verification.

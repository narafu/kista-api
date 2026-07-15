# Admin Privacy Guard Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change the PRIVACY invalid-base guard from auto-pause to alert-only and add admin strategy pause/resume controls.

**Architecture:** Reuse the existing privacy validation service in both open/close planning paths but convert the response to skip+alert without mutating strategy state. Extend the admin API layer with strategy status actions that delegate to the existing strategy service so behavior stays consistent with user-facing pause/resume semantics and audit logging can be added centrally.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, WebMvcTest

## Global Constraints

- Keep KST trade-date semantics unchanged.
- Reuse existing `PrivacyTradeValidationService`; do not duplicate validation rules.
- Guard behavior must skip invalid PRIVACY order generation without changing `strategy.status`.
- Admin status changes must be available under `/api/admin/**` and follow existing security patterns.
- Add tests before implementation code for each behavior change.

---

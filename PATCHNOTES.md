# **PATCHNOTES — Strata (Engineering & Implementation Guide)**

> **Developer playbook** for Strata — a cross-platform Kotlin Multiplatform personal assistant that unifies calendar, mail, tasks, health, and important alerts from **Google** and **Apple** services into a single dashboard.
> Optimized for Kotlin Multiplatform Contest judging — no paid Apple Developer account required.

---

## **0) Core Principles**

* **One app, all accounts** → Users can link Google, Apple, or both.
* **Single-language stack** → Kotlin everywhere (frontend + backend).
* **Union dashboard** → Merge data from all connected sources without duplicates.
* **Optional integrations** → Works even if user only connects one account type.
* **Contest-ready** → No Apple Developer Program needed; all features work in simulator.

---

## **1) Tech Stack**

**Frontend (KMP)**

* Kotlin Multiplatform + Compose Multiplatform UI
* SQLDelight → offline cache
* Ktor Client → API calls
* WebSockets → real-time updates

**Backend (Ktor)**

* Ktor server (monolith)
* PostgreSQL → persistent storage (encrypted for sensitive fields)
* Redis → caching & session store
* Google OAuth 2.0 (Google APIs)
* Apple API access via EventKit (Calendar, Reminders), MailKit (Mail), CalDAV/IMAP (server-side)

---

## **2) Integrations**

| Service Category | Google API                   | Apple API                                    |
| ---------------- | ---------------------------- | -------------------------------------------- |
| Calendar         | Google Calendar API          | EventKit (local) / CalDAV (server)           |
| Mail             | Gmail API                    | MailKit (local) / IMAP (server)              |
| Tasks            | Google Tasks API             | EventKit Reminders (local) / CalDAV (server) |
| Health           | **Google Fit REST API only** | *(No Apple HealthKit for contest)*           |
| Bills/Alerts     | Gmail API parsing            | MailKit/IMAP parsing                         |

---

## **3) Data Model**

```kotlin
data class CalendarEvent(val id: String, val title: String, val start: Instant, val end: Instant, val source: String)
data class Email(val id: String, val subject: String, val sender: String, val timestamp: Instant, val source: String)
data class Task(val id: String, val title: String, val due: Instant?, val completed: Boolean, val source: String)
data class HealthMetric(val type: String, val value: Double, val unit: String, val timestamp: Instant, val source: String)
data class BillAlert(val id: String, val description: String, val due: Instant, val source: String)
```

---

## **4) Feature → Implementation Mapping**

**Personalized Daily Briefing**

* `GET /briefing` → queries all connected accounts → AI summary (Gemini API)
* Cache in Redis

**Unified Dashboard**

* `GET /dashboard` → merges normalized data into union sets
* Deduplication based on matching title + timestamp

**Quick Actions**

* `POST /action` → handle reply, create, complete, mark done

**Notifications**

* `WS /ws` → push new events/emails/tasks/alerts
* In-monolith scheduler for periodic checks

**Offline Mode**

* SQLDelight local cache + queued sync jobs

---

## **5) Account Linking Flow**

1. **Connect Google** → OAuth 2.0 → refresh token stored encrypted in Postgres
2. **Connect Apple** →

   * On iOS/macOS → native permission prompts (EventKit, MailKit)
   * On other platforms → CalDAV + IMAP (requires iCloud app-specific password)
3. Both tokens stored separately; backend fetches from whichever is available

---

## **6) API Endpoints (Planned, Ktor)**

```
POST /auth/google
POST /auth/apple
GET /dashboard
GET /briefing
GET /calendar
GET /mail
GET /tasks
GET /health   // Google Fit only
GET /alerts
POST /action
WS  /ws
```

---

## **7) Security**

* Sensitive tokens encrypted in Postgres
* Minimal OAuth scopes
* No tracking unless explicitly enabled

---

## **8) Contest Mode Notes**

* **Health** → Google Fit only, no Apple HealthKit dependency.
* **Apple services** → Calendar, Reminders, Mail work in simulator without paid account.
* **Push notifications** → via WebSockets (no APNs).
* Hosted backend for live demo; README includes local run instructions.
* All API keys included in repo (contest requirement), restricted to contest/demo usage.

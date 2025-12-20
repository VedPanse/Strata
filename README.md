# **Strata** — Your Unified Personal Assistant

**Kotlin Multiplatform Contest 2025 Submission**

> **One dashboard. Every account. Zero app switching.**
> Strata unifies your calendars, emails, tasks, health data, and important alerts from **Google** and **Apple** services into one AI-powered personal feed — working seamlessly across Android, iOS, and Desktop.

---

## **✨ Why Strata?**

We live in dozens of apps — juggling meetings, to-do lists, emails, and health stats. Strata brings it all together:

* **Unified Dashboard** — one feed for everything that matters today.
* **Multi-Account Support** — connect Google, Apple, or both.
* **AI-Powered Summaries** — know exactly what to focus on in seconds.
* **Cross-Device Sync** — Android, iOS, and Desktop stay in sync automatically.
* **Offline Mode** — works without internet; syncs when you’re back online.
* **Privacy-First** — data stays encrypted; no tracking without consent.

---

## **📱 Supported Integrations**

**Google**

* Calendar (Google Calendar API)
* Mail (Gmail API)
* Tasks (Google Tasks API)
* Health (Google Fit API)
* Bills & Alerts (via Gmail parsing)

**Apple** *(contest mode)*

* Calendar (EventKit / CalDAV)
* Reminders (EventKit / CalDAV)
* Mail (MailKit / IMAP)

---

## **🖥 Screens & Features**

### **1. Onboarding & Account Linking**

* Simple welcome screen with **“Connect Google”** and **“Connect Apple”** buttons.
* You can link either account type or both.
* Once connected, Strata automatically syncs your data.

---

### **2. Daily Briefing**

* Your **morning snapshot**:

  * Today’s meetings (merged from Google & Apple calendars)
  * Top priority emails
  * Due tasks & reminders
  * Health stats (Google Fit)
  * Bills & alerts due soon
* **AI Summary** at the top condenses the day:

  > “You have 2 meetings, 3 important emails, and a task due today. Your electricity bill is due tomorrow.”

---

### **3. Unified Dashboard**

* Scrollable feed with widgets for:

  * Calendar
  * Tasks
  * Mail
  * Health (Google only)
  * Alerts
* Pull-to-refresh or auto-sync via WebSockets.
* Tap any item for details or quick actions.

---

### **4. Quick Actions**

* Reply to emails directly from Strata.
* Mark tasks complete or add new ones.
* Log health metrics manually (Google Fit).
* Mark alerts as resolved.

---

### **5. Notifications**

* Smart, context-aware alerts:

  * “Meeting starts in 10 min”
  * “Bill due tomorrow”
  * “You haven’t met today’s step goal” (Google Fit)
* Delivered via in-app banners and system notifications.

---

### **6. Offline Mode**

* SQLDelight local storage keeps your last sync available offline.
* Changes made offline are queued and synced automatically.

---

## **⚙️ How It Works**

* **Frontend:** Kotlin Multiplatform (Compose Multiplatform, SQLDelight, Ktor Client, WebSockets)
* **Backend:** Ktor monolith with PostgreSQL + Redis
* **Data Merge:** All sources normalized to internal models → deduplicated → union set sent to frontend
* **Security:** OAuth 2.0 for Google; EventKit/MailKit for Apple; all tokens encrypted in DB

---

## **🚀 Getting Started**

1. Clone the repo:

   ```bash
   git clone https://github.com/yourusername/strata.git
   cd strata
   ```
2. Configure secrets securely (no hardcoded keys in code):

   Desktop/JVM target reads environment variables or a local .env file:
   - GOOGLE_DESKTOP_CLIENT_ID (required)
   - GOOGLE_DESKTOP_CLIENT_SECRET (optional; only if you use a Web client that requires it)
   - GOOGLE_API_KEY (optional; used to add key= to Gmail/Tasks/Calendar requests)
   - GOOGLE_GEMINI_API_KEY (optional; used for Gemini model access if/when enabled)

   You can place a .env file at one of these paths (first found wins):
   - composeApp/src/.env
   - src/.env
   - .env (project root)

   Example .env:
   ```env
   GOOGLE_DESKTOP_CLIENT_ID=your_client_id.apps.googleusercontent.com
   GOOGLE_DESKTOP_CLIENT_SECRET=your_secret_if_applicable
   GOOGLE_API_KEY=optional_google_api_key
   GOOGLE_GEMINI_API_KEY=AIzaSyBIj-V_5wxLoETpmPWf-skWE_s3pWBWVGY
   ```

3. Run backend (if you use the optional backend):

   ```bash
   ./gradlew runBackend
   ```
4. Run frontend (choose target):

   ```bash
   ./gradlew run           # Desktop
   ./gradlew installDebug  # Android
   ./gradlew iosDeploy     # iOS simulator
   ```

### 🔥 Hot Reload on Save (Desktop)

This project is configured with Compose Hot Reload. To run the Desktop app with automatic reload on file save:

```bash
./scripts/dev.sh
```

What it does:
- Runs `:composeApp:hotRunJvm` with `--auto`, which rebuilds changed sources and hot reloads the running app automatically.
- You can also run it directly without the script:

```bash
./gradlew :composeApp:hotRunJvm --auto
```

Manual reload options:
- Trigger an explicit reload at any time:

```bash
./gradlew reload
```

- Or reload only the main JVM compilation:

```bash
./gradlew :composeApp:hotReloadJvmMain
```

---

## **🛡 Contest Mode Notes**

* Works fully in iOS simulator without paid Apple Developer account.
* Health integration is **Google-only** for contest build.
* Hosted backend available for seamless judging; local backend instructions included.

---

## **📄 License**

This project is licensed under the **MIT License**.

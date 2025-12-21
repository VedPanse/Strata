# Features

## Goal-Based Requests
- Accepts high-level goals and decomposes them into multi-step plans.
- Uses explicit clarification steps when intent is ambiguous (no actions until clarified).
- Resumes pending questions across sessions via SQLDelight.

## Supported Actions
- Mail: send, reply, forward, explain, delete (by message id).
- Calendar: add, update, delete events.
- Tasks/reminders: add, update, delete tasks.
- External actions: proposes third-party intents (e.g., food ordering) with mandatory confirmation; provider execution not wired yet.

## OpenAI API Health
- Displays a quota usage bar using `OPENAI_DAILY_QUOTA` when configured.
- Detects quota exhaustion or invalid key errors and shows an error state.

## API Usage Guardrails
- Local handling for simple pending-plan confirmations to avoid extra API calls.
- Short-lived response cache (5 minutes) for identical prompts.
- Cooldown after repeated failures and when rate limits are detected.

## Long-Term Memory
- Stores user-approved facts and preferences for reuse after app restarts.
- Memory is injected into prompts as context.
- Settings includes a "Delete LLM memory" action.

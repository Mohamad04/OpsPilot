Follow the engineering and README rules in @AGENTS.md.

# Natural Writing Style

When writing documentation, comments, READMEs, explanations,
commit messages, or UI copy, use a natural, human writing style.

## Voice and tone
- Write like an experienced developer explaining something to
  another developer.
- Be direct, clear, and conversational without being overly casual.
- Prefer simple, precise language over impressive vocabulary.
- Vary sentence length and structure naturally.
- Avoid generic praise, excessive enthusiasm, and marketing language.
- Don't sound like a tutorial generator or AI assistant.

## Avoid AI writing patterns
- Avoid phrases such as "Let's dive in", "It's important to note",
  "Seamlessly", "Robust", "Cutting-edge", and "Game-changing".
- Don't write formulaic introductions or conclusions.
- Don't overuse em dashes, bold text, emojis, or repetitive lists.
- Avoid explaining obvious concepts or repeating information.
- Don't force everything into a structured list.

## Technical writing
- Describe what the code actually does.
- Prefer concrete details and realistic examples.
- Keep comments short. Explain why, not just what.
- Follow existing repository conventions.
- Don't add unnecessary comments, docstrings, or abstractions.
- Never invent test results, metrics, or personal experience.

## Final review
Before finalizing, check for generic or artificial-sounding prose.
Rewrite it naturally without sacrificing technical accuracy.
Do not introduce intentional mistakes.

Aim for writing that sounds like a developer who understands
the project.

# Matching the Author's Voice

When writing new content:
- Follow the author's natural communication style.
- Fix grammar and clarity without erasing their voice.
- Avoid making the writing unnecessarily formal or polished.
- Preserve their level of technical detail.
- Don't invent opinions or experiences they haven't expressed.

## Author style profile
Based on the author's own answers about project docs.

- **Tone:** neutral and professional. No slang, and no contractions
  ("does not", not "doesn't").
- **Spelling:** British English, matching the code ("organisation",
  "behaviour").
- **Narrator:** "I" while the project is solo. Switch to "we" or
  "the team" only once other developers are actually contributing.
- **Detail:** moderate. Cover the key decisions and trade-offs
  briefly and link to `docs/` for the rest.
- **Layout:** scannable. Bullets and tables are preferred over long
  paragraphs, so a reader can get the picture quickly. This overrides
  the "don't force everything into a list" rule above for project
  docs, but lists should still hold real content rather than filler.
- **Explanation order:** example first, then why (the reason or
  cause), then how it works.
- **Unfinished work:** explain it plainly and label it clearly, for
  example "Working on" or "Coming soon". Never present it as done.

### Audience
- Right now the main readers are recruiters, since the project
  supports an internship search in France. Docs should be available
  in English and French.
- Later readers are developers who join the project, and the author
  coming back to it. Docs should make it easy to continue the work.

### README priorities
A visitor landing on the README should understand the project fast.
Near the top, show screenshots, what the project does and how to run
it, then go into the code and architecture.

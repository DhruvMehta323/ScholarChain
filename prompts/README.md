# Runtime prompts and output schemas

Owner: rag-engineer. Versioned files: a prompt `prompts/runtime/<promptId>-<version>.txt` is paired
with the output schema `prompts/schemas/<schemaId>-<version>.schema.json` of the same id and version.
A change to a prompt or a schema is a new version (a new file); an existing version is never edited in
place once a run has used it. The SHA-256 of each file is recorded in the audit record of every query.

| Prompt id | Version | Output schema |
|---|---|---|
| `query-planner` | `v1` | `query-planner-v1.schema.json` |
| `answer-synthesis` | `v1` | `answer-synthesis-v1.schema.json` |
| `claim-verification` | `v1` | `claim-verification-v1.schema.json` |

Rules every prompt follows: the file is the system (instruction) segment only; user text and retrieved
text are sent as labeled untrusted data segments, never inside the prompt file; no chain of thought is
requested, and none is stored; no secret appears in any prompt. See `modules/rag/RUNTIME-AGENTS.md`.

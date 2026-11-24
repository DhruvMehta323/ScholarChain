# Course spec excerpt for Phase 10 (supplied by the human architect)

Source: CS441 ScholarChain course project specification, sections "REST API contract", "Error model", "Observability requirements", "Security requirements". Copied verbatim by the human for the orchestrator, which cannot read the spec. Where the work order and this excerpt differ, the human's Phase 10 decisions (agent-report.md) win.

## REST API contract

The following API is the minimum required surface. Endpoint names may change when the submitted OpenAPI contract is internally consistent.

### POST /v1/query

Request:

```json
{
  "question": "Which researchers have the most distinct coauthors in distributed systems since 2015, which of them participated in the largest author teams, and which are the strongest bridges between collaboration communities?",
  "constraints": {
    "yearFrom": 2015,
    "yearTo": 2026,
    "topics": ["distributed systems"],
    "institutions": [],
    "venues": []
  },
  "analysis": {
    "rankingMetric": "DISTINCT_COAUTHOR_COUNT",
    "includeTeamSize": true,
    "includeBridgeMetrics": true,
    "includeCommunities": true,
    "includePaths": true
  },
  "retrieval": {
    "graphLimit": 100,
    "vectorLimit": 30,
    "maxPathDepth": 6
  },
  "output": {
    "includeEvidence": true,
    "includeScores": true,
    "includeUncertainty": true
  }
}
```

Synchronous response:

```json
{
  "queryId": "qry-01J...",
  "mode": "synchronous",
  "answer": {
    "summary": "...",
    "researchers": [
      {
        "researcherId": "openalex:A123",
        "displayName": "Example Researcher",
        "publicationCount": 87,
        "distinctCoauthorCount": 142,
        "weightedDegree": 211,
        "averageCoauthorsPerPaper": 4.6,
        "maximumCoauthorsOnOnePaper": 28,
        "maximumTeamWorkIds": ["dblp:conf/example/Example2024"],
        "componentId": "cc-1",
        "communityId": "community-17",
        "bridgeMetric": {"name": "approxBetweenness", "value": 0.812},
        "claimIds": ["claim-1", "claim-2"]
      }
    ]
  },
  "claims": [
    {
      "claimId": "claim-1",
      "text": "The researcher has 142 distinct coauthors in the selected 2015 to 2026 window.",
      "evidenceIds": ["ev-metric-17"],
      "verificationStatus": "VERIFIED",
      "uncertainty": "The value depends on the submitted researcher-identity resolution policy and dataset snapshots."
    }
  ],
  "links": {
    "evidence": "/v1/queries/qry-01J.../evidence",
    "explain": "/v1/explain/qry-01J..."
  }
}
```

Asynchronous response:

```json
{
  "queryId": "qry-01J...",
  "mode": "asynchronous",
  "jobId": "job-01J...",
  "status": "QUEUED",
  "statusUrl": "/v1/jobs/job-01J..."
}
```

(The numbers in these examples are illustrative placeholders for the API format; real responses must come from the deployed corpus.)

### GET /v1/jobs/{jobId}

```json
{
  "jobId": "job-01J...",
  "queryId": "qry-01J...",
  "state": "RUNNING",
  "stage": "CLAIM_VERIFICATION",
  "createdAt": "2026-09-14T18:00:00Z",
  "updatedAt": "2026-09-14T18:00:12Z",
  "resultUrl": null,
  "error": null
}
```

### GET /v1/evidence/{evidenceId}

```json
{
  "evidenceId": "ev-work-33",
  "evidenceType": "TEXT_CHUNK",
  "workId": "doi:10.1234/example",
  "sourceDataset": "s2orc",
  "sourceRecordId": "s2:abc",
  "snapshot": "2026-08-01",
  "text": "A bounded supporting excerpt or generated path summary.",
  "textS3Uri": "s3://scholarchain-rag/chunks/...",
  "score": 0.842,
  "provenance": {
    "runId": "run-...",
    "textHash": "sha256:...",
    "schemaVersion": "evidence-v1"
  }
}
```

### GET /v1/graph/nodes/{nodeId}/neighbors

Parameters:

```text
direction=in|out|both
depth=1..3
limit=1..200
edgeTypes=COAUTHORED_WITH,AUTHORED,HAS_TOPIC,AFFILIATED_WITH,PUBLISHED_IN
```

The server must reject unbounded graph exploration.

### GET /v1/explain/{queryId}

Response content:
- Normalized QueryPlan.
- Retrieval modes selected.
- Parameterized query-template IDs.
- Indexes queried.
- Metadata filters.
- Score components and weights.
- Prompt versions.
- Model versions.
- Evidence IDs.
- Claim-verification outcomes.
- Latency and retry counters.
- Cache hits.

The endpoint must not reveal secret prompts, credentials, or private chain-of-thought.

## Error model

| HTTP status | Meaning |
|---|---|
| 400 | Malformed request or invalid field |
| 401 | Missing or invalid authentication |
| 403 | Authenticated caller lacks permission |
| 404 | Unknown query, job, node, or evidence ID |
| 409 | Idempotency conflict or incompatible state |
| 422 | Valid JSON with unsupported constraints or retrieval plan |
| 429 | Rate or budget limit exceeded |
| 502 | Downstream service failure |
| 503 | Service not ready or temporarily unavailable |
| 504 | Workflow or downstream timeout |
| 500 | Unexpected internal failure with trace ID |

Example:

```json
{
  "error": "UNSUPPORTED_RETRIEVAL_MODE",
  "message": "Hybrid retrieval is not enabled in this deployment.",
  "traceId": "trace-01J...",
  "retryable": false
}
```

## Observability requirements

Every runtime request must have a trace ID propagated across services and workflow steps. Logs must be structured and must not contain secrets or full sensitive payloads.

Required metrics include (service-relevant subset):
- Neptune query duration and error count.
- DynamoDB consumed capacity, throttles, conditional failures, and latency.
- S3 Vectors write throughput, query duration, rejected vectors, and retries from the application client.
- SQS queue depth, age of oldest message, and dead-letter count.
- ECS desired, pending, and running task counts.
- API request count, p50, p95, p99, status codes, and timeouts.
- LLM request count, model ID, latency, retry count, and token usage where available.
- Claim verification status counts.
- Evidence-bundle size.
- Answer abstention rate.

## Security requirements (service-relevant)

The threat model must cover at least: prompt injection in titles, abstracts, full text, institution descriptions, topic summaries and generated profile text; data poisoning; fabricated identifiers, edges and path links; over-permissioned IAM roles; secret exposure in logs, prompts, Terraform state, deployment-script output or Git history; unbounded graph queries; LLM cost exhaustion; queue flooding; malformed or decompression-bomb inputs; cross-tenant or unauthorized evidence access when authentication is enabled; destructive agent commands; Terraform state compromise; provenance or audit-record tampering.

Required controls include:
- Treat retrieved documents as data, never as system instructions.
- Allowlisted query plans and parameterized graph queries.
- JSON schema validation for every model output.
- Bounded inputs, outputs, retries, graph depth, and concurrency.
- Least-privilege IAM roles.
- Separate deployment and runtime roles.
- Encryption in transit and at rest.
- Secrets Manager and role-based or environment-scoped credential injection.
- Immutable image tags.
- CloudTrail or equivalent account audit where available.
- Versioned prompts and model identifiers.
- Content hashes for evidence text.
- Conditional DynamoDB writes for audit records.
- Human approval for destructive and high-cost actions.

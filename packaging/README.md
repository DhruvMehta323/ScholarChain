# Service images

Three independently deployable images are built from ONE sbt module, `modules/services`:

| Image | Main class | Port | Health check |
|---|---|---|---|
| `scholarchain/query-api:<sha>` | `scholarchain.services.QueryApiMain` | 8080 | `curl --fail http://127.0.0.1:8080/healthz` |
| `scholarchain/retrieval:<sha>` | `scholarchain.services.RetrievalMain` | 8080 | `curl --fail http://127.0.0.1:8080/healthz` |
| `scholarchain/embedding-worker:<sha>` | `scholarchain.services.EmbeddingWorkerMain` | none, no inbound | `grep -qa EmbeddingWorkerMain /proc/1/cmdline` |

The service list lives in `build.sbt` (`serviceImages`). Each image holds the same `lib/` (the
services runtime classpath) and selects its main through the `SERVICE_MAIN` environment variable
set in the image; `entrypoint.sh` runs `exec java ... -cp "/opt/scholarchain/lib/*" $SERVICE_MAIN`.

## Commands (repository root)

| Command | What it does |
|---|---|
| `sbt servicesGuard` | Fails if a `spark-*` or `hadoop-*` jar is on `services/Runtime` classpath. |
| `sbt dockerStage` | Renders the build context (`lib/`, `prompts/`, `entrypoint.sh`, `.dockerignore`, three Dockerfiles) under `target/out/jvm/scala-3.3.9/scholarchain-services/docker-services/`. Runs the guard first. Needs no tag. |
| `SCHOLARCHAIN_GIT_SHA=$(git rev-parse --short HEAD) sbt dockerLocal` (append `-dirty` when the tree is dirty) | Stages, then `docker build --platform linux/amd64` for all three images, tagged `scholarchain/<service>:<sha>`. Never pushes, never logs in. |

`sbt check` does not run any of the image tasks and needs no environment variable.

Environment inputs (also listed in the header of `build.sbt`):

- `SCHOLARCHAIN_GIT_SHA`: required by `dockerLocal` only. 7 to 40 hex characters, optionally followed
  by the single suffix `-dirty` (for example `932fd2f-dirty`); anything else
  (including unset and `latest`) fails the build with a clear message. There is no `latest` tag.
- `SCHOLARCHAIN_ALLOW_MISSING_MAINS=1`: bootstrap escape hatch only; do not use. Without it
  `dockerLocal` fails while a main class is not compiled. Images are built with the real mains
  (`QueryApiMain`, `RetrievalMain`, `EmbeddingWorkerMain`); an image built with it cannot start.

A running sbt server keeps the environment it was started with. Run `sbt shutdown` before a
`dockerLocal` whose environment differs from the server's.

## Image contents

- Base: `eclipse-temurin:<Versions.temurinJreImage>` (read from `project/Versions.scala` at render
  time). The base image provides `curl`, `bash`, `grep` (checked on the pinned tag); the health
  checks use only these.
- Non-root user and group `scholarchain`, UID/GID 10001, `USER 10001:10001`, no shell login.
- JVM flags (in `entrypoint.sh`) for a 512 MiB task: `-XX:MaxRAMPercentage=70.0`,
  `-XX:InitialRAMPercentage=25.0`, `-XX:+UseSerialGC`, `-XX:MaxMetaspaceSize=96m`,
  `-XX:+ExitOnOutOfMemoryError`. The optional `JAVA_OPTS` variable is appended and can override them.
- Labels: `org.opencontainers.image.revision` (the SHA) and `.title`. Environment baked in:
  `SERVICE_NAME` and `SERVICE_MAIN` only. No secret, endpoint, AWS setting or build argument.
- Prompts: `prompts/runtime/` and `prompts/schemas/` (not `prompts/README.md`) are copied to
  `/opt/scholarchain/prompts`, owned by root and read-only (`dr-xr-xr-x` / `-r--r--r--`).
  `entrypoint.sh` passes `-Dscholarchain.prompts.dir=/opt/scholarchain/prompts` to the JVM, which
  `PromptRepository.locate` reads; `/readyz` reports `prompts: up` when `query-planner`,
  `answer-synthesis` and `claim-verification` at `v1` are found.
- Spark and Hadoop are not in the image: the guard checks the classpath, and `storage` declares
  Spark as `Provided` while `services` has it in `Test` only.
- The context is the rendered directory only; its `.dockerignore` denies everything except `lib/`,
  `prompts/`, `entrypoint.sh` and `Dockerfile.*`, so `.env*`, keys, datasets, `.git` and `target` junk cannot enter.

## Runtime contract for the service mains

- Configuration: environment variables only (names in `infra/terraform/INFRA-CONTRACTS.md` and
  `modules/core`). Secrets arrive as environment variables injected by the task definition.
- `exec` makes the JVM PID 1: SIGTERM reaches the JVM. Mains should register a shutdown hook, stop
  accepting work, drain briefly and exit 0 within the container stop timeout (30 s by default).
- query-api and retrieval answer `GET /healthz` with 200 without authentication on port 8080.

## Local-mode smoke run (verified 932fd2f-dirty)

`SCHOLARCHAIN_ENVIRONMENT=local` is a development-checkout mode: the mains look for `build.sbt`
above the working directory and read fixtures from `modules/storage/target/loads/L4` and
`modules/services/target/embedding-runs/L4`, and write audit files under
`modules/services/target/local-audit`. None of that is in the image, so a local run needs those
paths mounted (read-write for `modules/services/target`, since the container user is 10001):

```
docker run -d --name <name> -p 127.0.0.1:18080:8080 -v <fixture-root>:/work -w /work \
  -e SCHOLARCHAIN_ENVIRONMENT=local scholarchain/query-api:<sha>
curl http://127.0.0.1:18080/healthz   # 200 {"status":"ok","service":"query-api"}
curl http://127.0.0.1:18080/readyz    # 200, checks key_value, graph, vectors, prompts all "up"
```

retrieval is the same with its image (`/readyz` checks `graph`, `vectors`). embedding-worker has no
local mode: it exits with code 2 and `startup.refused` ("the embedding worker has no local mode");
in AWS mode it needs `SCHOLARCHAIN_AWS_REGION` and the other configuration from the task definition.
Never pass real secrets for a local run.

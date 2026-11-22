# Phase 08: bedrockruntime and Test-scope spark-sql (no version value change)

Date checked: 2026-11-21
Owner: dependency-updater
Work order: Phase 08 A0, dependency-updater part

## Change

- `project/Versions.scala`: comment only. `bedrockruntime` added to the list of SDK modules sharing `awsSdkV2`. No version value changed (`awsSdkV2` stays "2.59.7", `spark` stays "4.1.1").
- Classification: no upgrade. Both items below reuse existing constants. No major-version change, so no major-upgrade approval applies.

## Additions made by build-engineer (reference only)

| Artifact | Version | Constant | Module and scope |
|---|---|---|---|
| software.amazon.awssdk:bedrockruntime | 2.59.7 | `Versions.awsSdkV2` | rag, Compile |
| software.amazon.awssdk:url-connection-client | 2.59.7 | `Versions.awsSdkV2` | rag (already covered in Phase 07 for other modules) |
| org.apache.spark:spark-sql_2.13 | 4.1.1 | `Versions.spark` | services, Test only (existing version) |

Same pin as Phase 07: see `reports/dependency-updates/phase-07-aws-sdk-v2.md`.

## Verified (observed 2026-11-21)

1. `https://repo1.maven.org/maven2/software/amazon/awssdk/bedrockruntime/maven-metadata.xml`: `<latest>` and `<release>` both 2.59.7, lastUpdated 20261121025739.
2. `.../bedrockruntime/2.59.7/bedrockruntime-2.59.7.pom`: HTTP 200.
3. OSV advisory query (`POST https://api.osv.dev/v1/querybatch`, read-only, unauthenticated; only package names and version 2.59.7 sent) for `software.amazon.awssdk:bedrockruntime` and `software.amazon.awssdk:url-connection-client`: HTTP 200, response `{"results":[{},{}]}`, i.e. no vulnerabilities listed for either.

## Not verified

- spark-sql 4.1.1: no new advisory lookup was done in this run (version unchanged from the existing ledger). Test scope only, so it is not shipped in the service runtime, assuming build-engineer scoped it as stated; I did not inspect `project/Dependencies.scala` or the sbt dependency tree.
- Third-party transitives of bedrockruntime (Netty, Jackson, Apache HttpClient, etc.) were not queried.
- GitHub advisory database, NVD and AWS security bulletins were not queried.
- The AWS SDK changelog or release notes for 2.59.7 and the Bedrock Runtime API changes were not read.
- No AWS calls were made and no credentials were used or read.
- `sbt check` was not run by this agent, per the work order. The orchestrator runs it.
- OSV can lag behind newly disclosed issues, so an empty result is not proof of absence.

# Phase 07: AWS SDK for Java v2 version value

Date checked: 2026-11-21
Owner: dependency-updater

## Change

- File: `project/Versions.scala`
- Added constant: `val awsSdkV2 = "2.59.7"` (value only, no ModuleIDs, no logic).
- Classification: new dependency version value, no upgrade (no prior AWS SDK constant existed). Not a major-version change of an existing dependency, so no major-upgrade approval applies. build-engineer must add the references in `project/Dependencies.scala`.

## Source

Latest and release per each artifact's `maven-metadata.xml` (`<latest>` and `<release>` both `2.59.7`, lastUpdated 2026-11-21):

- https://repo1.maven.org/maven2/software/amazon/awssdk/bom/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/s3/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/dynamodb/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/s3vectors/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/neptunedata/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/sqs/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/sts/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/url-connection-client/maven-metadata.xml
- https://repo1.maven.org/maven2/software/amazon/awssdk/apache-client/maven-metadata.xml

## Artifacts verified at 2.59.7

Each `<artifact>/2.59.7/<artifact>-2.59.7.pom` returned HTTP 200 under `https://repo1.maven.org/maven2/software/amazon/awssdk/`: bom, s3, dynamodb, s3vectors, neptunedata, sqs, sts, url-connection-client, apache-client. s3vectors exists at the latest version, so no fallback version was needed.

## Vulnerability and changelog review

### Advisory check, 2026-11-21 (phase 07 repair 01, finding M7)

Only package names and the version string 2.59.7 were sent. No other data.

Commands (read-only, unauthenticated):

1. `curl -s -m 60 -X POST https://api.osv.dev/v1/querybatch -d @<query-file> -o <out-file> -w "http=%{http_code}\n"`
   where the query file holds one query per artifact: `{"package":{"ecosystem":"Maven","name":"software.amazon.awssdk:<artifact>"},"version":"2.59.7"}`.
   Observed: `http=200`, 35 results for 35 queries.
2. `curl -s -m 60 "https://api.github.com/advisories?ecosystem=maven&affects=software.amazon.awssdk:s3@2.59.7"`
   Observed: `http=200`, body was an empty JSON array `[]`.

OSV result: 0 vulnerabilities for each of these 35 artifacts at 2.59.7 (no IDs):
s3, dynamodb, s3vectors, neptunedata, sqs, sts, url-connection-client, apache-client, bom, sdk-core, aws-core, http-client-spi, regions, auth, third-party-jackson-core, aws-query-protocol, aws-json-protocol, aws-xml-protocol, protocol-core, utils, annotations, profiles, identity-spi, json-utils, metrics-spi, endpoints-spi, checksums, checksums-spi, arns, retries, retries-spi, http-auth, http-auth-spi, http-auth-aws, netty-nio-client.

GitHub advisory database (software.amazon.awssdk:s3@2.59.7 only): empty result. Only s3 was queried there.

Conclusion: no advisory affecting software.amazon.awssdk 2.59.7 was found in OSV or in the GitHub query. Versions.scala was not changed.

### Not checked
- Third-party transitives of the SDK (Apache HttpClient, Netty, Jackson, reactive-streams, slf4j, etc.) were not queried. Their resolved versions depend on the sbt dependency tree, which was not inspected (sbt not run).
- GitHub advisories were queried for the s3 artifact only. The empty response may also reflect how the unauthenticated `affects` filter behaves, so it is weaker evidence than OSV.
- NVD and the AWS security bulletins were not queried.
- The AWS SDK changelog or release notes for 2.59.7 were not read.
- No dependency-scan tooling was run. OSV can lag behind newly disclosed issues.
- `sbt check` was not run by this agent in this repair, per the work order.

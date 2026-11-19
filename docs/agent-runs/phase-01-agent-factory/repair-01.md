Use the factory-engineer agent to replace the placeholder phase names in the lifecycle routing table in docs/agents.md, and in any other owned file that repeats them, with the official course phases and owners below. Change nothing else. Re-run validate-agent-files.sh and report in the required format.

0 Plant the seed agent: human orchestrator
1 Factory builds the agent system: factory-engineer
2 Build and repository skeleton: build-engineer; code-reviewer
3 Contracts, configuration, dataset manifests: dataset-engineer, domain-engineer; code-reviewer
4 Raw ingestion and Hadoop MapReduce: hadoop-engineer; test-engineer, code-reviewer
5 Entity resolution: entity-resolution-engineer; code-reviewer
6 Temporal coauthorship graph and Spark analytics: spark-engineer; code-reviewer
7 Storage adapters and loading: storage-engineer; code-reviewer
8 Embeddings and S3 Vectors: storage-engineer, rag-engineer
9 Runtime collaboration-analysis agents: rag-engineer; code-reviewer
10 REST microservices: service-engineer
11 Adversarial testing and full review: test-engineer, code-reviewer
12 AWS infrastructure in Terraform: infra-engineer; code-reviewer
13 Build, deployment, experiment automation: infra-engineer
14 Apply and deploy through human approval: deploy-engineer
15 Data pipeline and elasticity experiments: experiment-engineer
16 Evaluation: experiment-engineer, documentation-engineer
17 Documentation, final review, release: documentation-engineer, code-reviewer, test-engineer
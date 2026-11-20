# Instructor-approved deviations (received 2026-11-20)

Approved for Dhruv Mehta's ScholarChain project, under the same conditions set for other students:

1. DBLP experiments: two distinct scales of about 10K and about 50K real DBLP works. The 50K run replaces the 100K scale. Compare execution time, resource utilization, scalability and cost across both. Further reductions need justification.
2. S3 Vectors: up to 10K real vectors (target 10K when the data supports it); report the actual count and demonstrate ingestion and retrieval. Independent of the DBLP work count.
3. Entity-resolution gold set: at least 170 manually reviewed pairs in total, stratified 60/40 into 102 calibration and 68 held-out pairs, both containing positives and hard negatives. Tune only on calibration; report precision, recall, F1 and the confusion matrix on the held-out set; explain the methodology and limitations.
4. Bedrock fallback: if Bedrock quotas prevent timely execution, the Anthropic API (LLM) and Voyage AI (embeddings) may be used, with Bedrock kept configurable. Document models and configuration; include all external API costs in the budget.

Budget: unchanged at $25, including AWS consumption before credits and all external API costs.
The final report must state exact dataset sizes, vector counts, gold-set composition and split, execution times, resource utilization, costs and limitations, and distinguish local tests, fixture demonstrations and AWS-validated runs on real data.

Not covered by this approval: the bounded, DOI-driven enrichment subsets of OpenAlex, Crossref and ORCID. These follow the specification's own bounded-retrieval requirement and are documented as a design choice, not as an instructor-approved deviation.

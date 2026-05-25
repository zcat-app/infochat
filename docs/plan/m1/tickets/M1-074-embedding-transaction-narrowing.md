---
id: M1-074
title: EmbeddingWorker transaction narrowing + TransactionHelper extraction
status: pending
created: 2026-05-25
last_updated: 2026-05-25
blocked_by: []
files_budget: 6
files_scope:
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorker.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/TransactionHelper.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage1/Stage1Pipeline.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/Stage2VerdictHandler.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/embedding/EmbeddingWorkerIT.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/eval/stage1/Stage1PipelineIT.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
source: deep-code-review full-2026-05-25-1901 (06-module-infochat-collector.md#F2, 06-module-infochat-collector.md#F3)
out_of_scope:
  - any EmbeddingWorker retry-logic or concurrency-semaphore change — only the @Transactional boundary moves
  - any Stage1Pipeline or Stage2VerdictHandler behavioral change — only the inTransaction helper is extracted to the shared class
  - any new pipeline stage
acceptance:
  - "EmbeddingWorker.processBatch no longer carries @Transactional on the method. The JTA transaction wraps only the DB writes (insertEmbeddingRows + advanceEmbeddingDone), not the semaphore acquisition or embedding HTTP call. Verify: EmbeddingWorkerIT.transactionDoesNotSpanHttpCall passes"
  - "TransactionHelper.java exists as a package-private utility in app.zcat.infochat.collector.eval with a static inTransaction(DataSource, String, TxBody) method. Verify: code inspection"
  - "Stage1Pipeline uses TransactionHelper.inTransaction instead of its private copy. Verify: Stage1PipelineIT tests remain green"
  - "Stage2VerdictHandler uses TransactionHelper.inTransaction instead of its private copy. Verify: mvn -pl infochat-collector verify green"
  - "The private TxBody interface and inTransaction methods are removed from Stage1Pipeline and Stage2VerdictHandler. Verify: grep returns empty"
  - "mvn -pl infochat-collector verify is green"
test_plan:
  adds:
    - EmbeddingWorkerIT.transactionDoesNotSpanHttpCall (new — verifies no idle-in-transaction during embed call)
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/architecture.md §Evaluation pipeline
decision_refs: []
---

## Context

`EmbeddingWorker.processBatch` wraps the entire method (including blocking semaphore acquisition and outbound embedding HTTP calls) in `@Transactional`, holding a JTA transaction open during potentially multi-second HTTP round-trips. Under max concurrency (8 on remote-llm), this wastes 8 connection pool slots on idle-in-transaction time.

Additionally, the `inTransaction(TxBody)` helper + `TxBody` interface is duplicated verbatim in Stage1Pipeline and Stage2VerdictHandler. Extracting to a shared utility eliminates the duplication and provides a home for EmbeddingWorker's narrow transaction.

## Fix approach

1. Remove `@Transactional` from `processBatch`.
2. Extract `TransactionHelper` with `inTransaction(DataSource, String context, TxBody)` to `app.zcat.infochat.collector.eval`.
3. Narrow the transaction to wrap only `insertEmbeddingRows` + `advanceEmbeddingDone`.
4. Migrate Stage1Pipeline and Stage2VerdictHandler to use the shared helper, removing their private copies.

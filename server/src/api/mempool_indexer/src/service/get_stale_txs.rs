use std::collections::HashSet;

use async_stream::try_stream;
use bdk_utils::bdk::bitcoin::Txid;
use database::ddb::WRITE_BATCH_MAX;
use futures::Stream;
use time::{Duration, OffsetDateTime};
use tracing::{event, Level};

use super::Service;
use crate::{entities::TransactionRecord, MempoolIndexerError};

impl Service {
    pub fn get_stale_txs(
        &self,
    ) -> impl Stream<Item = Result<Vec<TransactionRecord>, MempoolIndexerError>> + '_ {
        try_stream! {
            let network = self.settings.network;

            let expiring_txs = {
                let now = OffsetDateTime::now_utc();
                let mut write_guard = self.stale_txs_expiring_after.write().await;
                let expiring_after = write_guard.unwrap_or(now);
                let txs = self.repo.fetch_expiring_transactions(network, expiring_after).await?;
                if let Some(max_expiring_at) = txs.iter().map(|tx| tx.expiring_at).max() {
                    let new_expiring_after = max_expiring_at + Duration::seconds(1);
                    *write_guard = Some(new_expiring_after);
                }
                txs
            };
            let expiring_tx_ids = expiring_txs.iter().map(|tx| tx.txid).collect::<HashSet<Txid>>();
            let num_expiring_tx_ids = expiring_tx_ids.len();
            event!(
                Level::INFO,
                "Retrieved {num_expiring_tx_ids} expiring transactions from network {network}."
            );
            if num_expiring_tx_ids == 0 {
                return;
            }

            let update_tx_ids = {
                let current_mempool_txids = self.current_mempool_txids.read().await;
                current_mempool_txids
                    .intersection(&expiring_tx_ids)
                    .cloned()
                    .collect::<Vec<Txid>>()
            };

            let tx_records_to_update = expiring_txs.into_iter().filter(|tx| update_tx_ids.contains(&tx.txid)).collect::<Vec<TransactionRecord>>();

            // We do these in batches to avoid hitting the DDB write limit
            let chunks = tx_records_to_update
                .chunks(WRITE_BATCH_MAX)
                .map(|chunk| chunk.to_vec())
                .collect::<Vec<Vec<TransactionRecord>>>();

            for tx_records_batch in chunks {
                let tx_ids = tx_records_batch.iter().map(|r| r.txid.to_string()).collect::<Vec<String>>().join(", ");
                if let Ok(updated_tx_records) = self.repo.update_expiry(tx_records_batch).await {
                    yield updated_tx_records;
                } else {
                    event!(
                        Level::ERROR,
                        "Failed to persist tx record for batch with tx ids: {tx_ids}",
                    );
                }
        }
        }
    }
}

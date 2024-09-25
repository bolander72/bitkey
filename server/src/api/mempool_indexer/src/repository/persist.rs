use database::{
    aws_sdk_dynamodb::types::{PutRequest, WriteRequest},
    ddb::{try_to_item, DatabaseError, PersistBatchTrait, Repository},
};
use tracing::instrument;

use crate::entities::TransactionRecord;

use super::MempoolIndexerRepository;

impl MempoolIndexerRepository {
    #[instrument(skip(self))]
    pub async fn persist_batch(
        &self,
        records: &Vec<TransactionRecord>,
    ) -> Result<(), DatabaseError> {
        let table_name = self.get_table_name().await?;
        let database_object = self.get_database_object();

        // Generate write requests for each watched address
        let ops: Vec<WriteRequest> = records
            .iter()
            .map(|v| {
                Ok(WriteRequest::builder()
                    .set_put_request(Some(
                        PutRequest::builder()
                            .set_item(Some(try_to_item(v, database_object)?))
                            .build()?,
                    ))
                    .build())
            })
            .collect::<Result<Vec<WriteRequest>, DatabaseError>>()?;

        // Split the requests into chunks < DDB limitation
        ops.persist(&self.connection.client, &table_name, database_object)
            .await?;
        Ok(())
    }
}

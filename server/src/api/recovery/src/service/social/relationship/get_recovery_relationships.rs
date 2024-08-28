use super::{error::ServiceError, Service};
use repository::recovery::social::fetch::RecoveryRelationshipsForAccount;
use tracing::instrument;
use types::account::identifiers::AccountId;
use types::recovery::social::relationship::RecoveryRelationship;
use types::recovery::trusted_contacts::TrustedContactRole;

/// The input for the `get_recovery_relationships` function
///
/// # Fields
///
/// * `account_id` - The account to fetch the recovery relationships for
pub struct GetRecoveryRelationshipsInput<'a> {
    pub account_id: &'a AccountId,
    pub trusted_contact_role: TrustedContactRole,
}

/// The output for the `get_recovery_relationships` function
///
/// # Fields
///
/// * `invitations` - The list of invitations for the account
/// * `endorsed_trusted_contacts` - The list of endorsed trusted contacts for the account
/// * `unendorsed_trusted_contacts` - The list of unendorsed trusted contacts for the account
/// * `customers` - The list of customers for the account
pub struct GetRecoveryRelationshipsOutput {
    pub invitations: Vec<RecoveryRelationship>,
    pub endorsed_trusted_contacts: Vec<RecoveryRelationship>,
    pub unendorsed_trusted_contacts: Vec<RecoveryRelationship>,
    pub customers: Vec<RecoveryRelationship>,
}

impl From<RecoveryRelationshipsForAccount> for GetRecoveryRelationshipsOutput {
    fn from(relationships: RecoveryRelationshipsForAccount) -> Self {
        Self {
            invitations: relationships.invitations,
            endorsed_trusted_contacts: relationships.endorsed_trusted_contacts,
            unendorsed_trusted_contacts: relationships.unendorsed_trusted_contacts,
            customers: relationships.customers,
        }
    }
}

impl Service {
    /// This function fetches the recovery relationships for a given account. It returns a list of
    /// invitations, trusted contacts, and customers. Both the trusted contacts and customers are
    /// able to call this endpoint.
    ///
    /// # Arguments
    ///
    /// * `input` - Contains the account id to fetch the recovery relationships for
    ///
    /// # Returns
    ///
    /// * A list of invitations, trusted contacts, and customers
    #[instrument(skip(self, input))]
    pub async fn get_recovery_relationships(
        &self,
        input: GetRecoveryRelationshipsInput<'_>,
    ) -> Result<GetRecoveryRelationshipsOutput, ServiceError> {
        let relationships = self
            .repository
            .fetch_recovery_relationships_for_account(input.account_id)
            .await?;

        let tc_role = input.trusted_contact_role;
        let filtered_relationships = RecoveryRelationshipsForAccount {
            invitations: filter_by_role(relationships.invitations, &tc_role),
            endorsed_trusted_contacts: filter_by_role(
                relationships.endorsed_trusted_contacts,
                &tc_role,
            ),
            unendorsed_trusted_contacts: filter_by_role(
                relationships.unendorsed_trusted_contacts,
                &tc_role,
            ),
            customers: filter_by_role(relationships.customers, &tc_role),
        };

        Ok(filtered_relationships.into())
    }
}

fn filter_by_role(
    relationships: Vec<RecoveryRelationship>,
    role: &TrustedContactRole,
) -> Vec<RecoveryRelationship> {
    relationships
        .into_iter()
        .filter(|r| r.has_role(role))
        .collect()
}

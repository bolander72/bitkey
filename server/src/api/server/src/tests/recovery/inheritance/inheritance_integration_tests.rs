use crate::tests::{
    gen_services_with_overrides,
    lib::create_phone_touchpoint,
    recovery::shared::{
        assert_notifications, create_beneficiary_account,
        try_accept_recovery_relationship_invitation, try_create_relationship,
        try_endorse_recovery_relationship, CodeOverride,
    },
};
use axum::body::Body;
use bdk_utils::bdk::bitcoin::key::Secp256k1;
use bdk_utils::bdk::{database::AnyDatabase, FeeRate, SignOptions, Wallet};
use bdk_utils::signature::sign_message;
use http::{Method, StatusCode};
use mockall::mock;
use notification::NotificationPayloadType;
use rand::thread_rng;
use std::collections::HashSet;
use std::str::FromStr;
use std::sync::Arc;

use bdk_utils::bdk::bitcoin::psbt::{PartiallySignedTransaction, Psbt};
use bdk_utils::bdk::wallet::{AddressIndex, AddressInfo};
use bdk_utils::error::BdkUtilError;
use bdk_utils::{ElectrumRpcUris, TransactionBroadcasterTrait};
use recovery::routes::inheritance::{
    CancelInheritanceClaimRequest, CancelInheritanceClaimResponse,
    CompleteInheritanceClaimResponse, CreateInheritanceClaimRequest,
    CreateInheritanceClaimResponse, InheritancePackage, LockInheritanceClaimResponse,
    UpdateInheritanceProcessWithDestinationRequest,
    UpdateInheritanceProcessWithDestinationResponse, UploadInheritancePackagesRequest,
    UploadInheritancePackagesResponse,
};
use recovery::routes::relationship::CreateRelationshipRequest;
use rstest::rstest;
use serde_json::{json, Value};
use time::{Duration, OffsetDateTime};
use tokio::join;
use types::account::entities::Account;
use types::recovery::inheritance::claim::{InheritanceClaim, InheritanceClaimPending};
use types::recovery::inheritance::router::BeneficiaryInheritanceClaimViewPending;
use types::{
    account::{bitcoin::Network, identifiers::AccountId, keys::FullAccountAuthKeys, AccountType},
    recovery::{
        inheritance::{
            claim::{InheritanceClaimAuthKeys, InheritanceClaimId, InheritanceDestination},
            router::{BenefactorInheritanceClaimView, BeneficiaryInheritanceClaimView},
        },
        social::relationship::RecoveryRelationshipId,
        trusted_contacts::TrustedContactRole,
    },
};

use crate::tests::lib::create_default_account_with_predefined_wallet;
use crate::{
    tests::{
        gen_services,
        lib::{create_full_account, create_new_authkeys},
        requests::{axum::TestClient, CognitoAuthentication},
        TestContext,
    },
    Bootstrap, GenServiceOverrides,
};

enum InheritanceClaimActor {
    Benefactor,
    Beneficiary,
    External,
}

pub(super) async fn try_start_inheritance_claim(
    context: &TestContext,
    client: &TestClient,
    beneficiary_account_id: &AccountId,
    recovery_relationship_id: &RecoveryRelationshipId,
    auth_keys: &FullAccountAuthKeys,
    expected_status_code: StatusCode,
) -> Option<CreateInheritanceClaimResponse> {
    let keys = context
        .get_authentication_keys_for_account_id(beneficiary_account_id)
        .expect("Invalid keys for account");
    let create_response = client
        .start_inheritance_claim(
            &beneficiary_account_id.to_string(),
            &CreateInheritanceClaimRequest {
                recovery_relationship_id: recovery_relationship_id.to_owned(),
                auth: InheritanceClaimAuthKeys::FullAccount(auth_keys.to_owned()),
            },
            &keys,
        )
        .await;

    assert_eq!(
        create_response.status_code, expected_status_code,
        "{:?}",
        create_response.body_string
    );

    if expected_status_code == StatusCode::OK {
        let create_body = create_response.body.unwrap();
        return Some(create_body);
    }

    None
}

pub(super) async fn try_cancel_inheritance_claim(
    context: &TestContext,
    client: &TestClient,
    account_id: &AccountId,
    auth_account_id: &AccountId,
    inheritance_claim_id: &InheritanceClaimId,
    expected_status_code: StatusCode,
) -> Option<CancelInheritanceClaimResponse> {
    let keys = context
        .get_authentication_keys_for_account_id(auth_account_id)
        .expect("Invalid keys for account");
    let cancel_response = client
        .cancel_inheritance_claim(
            &account_id.to_string(),
            &inheritance_claim_id.to_string(),
            &CancelInheritanceClaimRequest {},
            &auth_account_id.to_string(),
            &keys,
        )
        .await;

    assert_eq!(
        cancel_response.status_code, expected_status_code,
        "{:?}",
        cancel_response.body_string
    );

    if expected_status_code == StatusCode::OK {
        let cancel_body = cancel_response.body.unwrap();
        return Some(cancel_body);
    }

    None
}

struct BenefactorBeneficiarySetup {
    pub benefactor: Account,
    pub beneficiary: Account,
    pub recovery_relationship_id: RecoveryRelationshipId,
    pub benefactor_wallet: Wallet<AnyDatabase>,
    pub beneficiary_wallet: Option<Wallet<AnyDatabase>>,
}

async fn setup_benefactor_and_beneficiary_account(
    context: &mut TestContext,
    bootstrap: &Bootstrap,
    client: &TestClient,
    beneficiary_account_type: AccountType,
) -> BenefactorBeneficiarySetup {
    let (benefactor_account, benefactor_wallet) =
        create_default_account_with_predefined_wallet(context, client, &bootstrap.services).await;
    let benefactor = Account::Full(benefactor_account);
    create_phone_touchpoint(&bootstrap.services, benefactor.get_id(), true).await;

    let (beneficiary, beneficiary_wallet) =
        create_beneficiary_account(beneficiary_account_type, context, bootstrap, client).await;
    create_phone_touchpoint(&bootstrap.services, beneficiary.get_id(), true).await;

    let create_body = try_create_relationship(
        context,
        client,
        benefactor.get_id(),
        &TrustedContactRole::Beneficiary,
        &CognitoAuthentication::Wallet {
            is_app_signed: true,
            is_hardware_signed: true,
        },
        StatusCode::OK,
        1,
        0,
    )
    .await
    .unwrap();

    let recovery_relationship_id = create_body
        .invitation
        .recovery_relationship_info
        .recovery_relationship_id
        .clone();
    try_accept_recovery_relationship_invitation(
        context,
        client,
        benefactor.get_id(),
        beneficiary.get_id(),
        &TrustedContactRole::Beneficiary,
        &CognitoAuthentication::Recovery,
        &create_body.invitation,
        CodeOverride::None,
        StatusCode::OK,
        1,
    )
    .await;

    try_endorse_recovery_relationship(
        context,
        client,
        benefactor.get_id(),
        &TrustedContactRole::Beneficiary,
        &recovery_relationship_id,
        "RANDOM_CERT",
        StatusCode::OK,
    )
    .await;

    BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        benefactor_wallet,
        beneficiary_wallet,
    }
}

#[rstest]
#[case::create_with_full_account(AccountType::Full, false, StatusCode::OK)]
#[case::create_with_lite_account(AccountType::Lite, false, StatusCode::FORBIDDEN)]
#[case::create_with_existing_claim(AccountType::Full, true, StatusCode::BAD_REQUEST)]
#[tokio::test]
async fn start_inheritance_claim_test(
    #[case] beneficiary_account_type: AccountType,
    #[case] create_existing_inheritance_claim: bool,
    #[case] expected_status_code: StatusCode,
) {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.clone().router).await;

    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        ..
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        beneficiary_account_type,
    )
    .await;

    let rotate_to_inheritance_keys = match &beneficiary {
        Account::Full(f) => f.active_auth_keys().unwrap().to_owned(),
        Account::Lite(_) => {
            let k = create_new_authkeys(&mut context);
            FullAccountAuthKeys {
                app_pubkey: k.app.public_key,
                hardware_pubkey: k.hw.public_key,
                recovery_pubkey: Some(k.recovery.public_key),
            }
        }
        Account::Software(_) => unimplemented!(),
    };
    if create_existing_inheritance_claim {
        try_start_inheritance_claim(
            &context,
            &client,
            beneficiary.get_id(),
            &recovery_relationship_id,
            &rotate_to_inheritance_keys,
            StatusCode::OK,
        )
        .await;
    }

    // act
    try_start_inheritance_claim(
        &context,
        &client,
        beneficiary.get_id(),
        &recovery_relationship_id,
        &rotate_to_inheritance_keys,
        expected_status_code,
    )
    .await;

    // assert
    if expected_status_code.is_success() {
        let benefactor_claims_resp = client
            .get_inheritance_claims(
                &benefactor.get_id().to_string(),
                &context
                    .get_authentication_keys_for_account_id(benefactor.get_id())
                    .expect("Invalid keys for account"),
            )
            .await;

        assert_eq!(benefactor_claims_resp.status_code, StatusCode::OK);
        let benefactor_claims = benefactor_claims_resp.body.unwrap().claims_as_benefactor;
        assert_eq!(benefactor_claims.len(), 1);

        let beneficiary_claims_resp = client
            .get_inheritance_claims(
                &beneficiary.get_id().to_string(),
                &context
                    .get_authentication_keys_for_account_id(beneficiary.get_id())
                    .expect("Invalid keys for account"),
            )
            .await;

        assert_eq!(beneficiary_claims_resp.status_code, StatusCode::OK);
        let beneficiary_claims = beneficiary_claims_resp.body.unwrap().claims_as_beneficiary;
        assert_eq!(beneficiary_claims.len(), 1);

        // Check whether the notifications were created
        let (
            benefactor_expected_scheduled_notifications_types,
            beneficiary_expected_scheduled_notifications_types,
        ) = (
            vec![
                // The initiated schedule gets split into 1 that gets sent immediately
                // and a schedule that starts in 7 days
                NotificationPayloadType::RecoveryRelationshipInvitationPending,
                NotificationPayloadType::InheritanceClaimPeriodInitiated,
                NotificationPayloadType::InheritanceClaimPeriodCompleted,
            ],
            vec![NotificationPayloadType::InheritanceClaimPeriodCompleted],
        );

        let (
            benefactor_expected_customer_notifications_types,
            beneficiary_expected_customer_notifications_types,
        ) = (
            vec![
                NotificationPayloadType::RecoveryRelationshipInvitationAccepted,
                NotificationPayloadType::InheritanceClaimPeriodInitiated,
            ],
            vec![NotificationPayloadType::InheritanceClaimPeriodInitiated],
        );

        join!(
            assert_notifications(
                &bootstrap,
                benefactor.get_id(),
                benefactor_expected_customer_notifications_types,
                benefactor_expected_scheduled_notifications_types
            ),
            assert_notifications(
                &bootstrap,
                beneficiary.get_id(),
                beneficiary_expected_customer_notifications_types,
                beneficiary_expected_scheduled_notifications_types
            ),
        );
    }
}

pub(super) async fn try_package_upload(
    context: &TestContext,
    client: &TestClient,
    beneficiary_account_id: &AccountId,
    recovery_relationships: &Vec<RecoveryRelationshipId>,
    expected_status_code: StatusCode,
) -> Option<UploadInheritancePackagesResponse> {
    let keys = context
        .get_authentication_keys_for_account_id(beneficiary_account_id)
        .expect("Invalid keys for account");

    let create_response = client
        .package_upload(
            &beneficiary_account_id.to_string(),
            &UploadInheritancePackagesRequest {
                packages: recovery_relationships
                    .iter()
                    .map(|r| InheritancePackage {
                        recovery_relationship_id: r.clone(),
                        sealed_dek: "RANDOM_SEALED_DEK".to_string(),
                        sealed_mobile_key: "RANDOM_SEALED_MOBILE_KEY".to_string(),
                    })
                    .collect(),
            },
            &keys,
        )
        .await;

    assert_eq!(
        create_response.status_code, expected_status_code,
        "{:?}",
        create_response.body_string
    );

    if expected_status_code == StatusCode::OK {
        let create_body = create_response.body.unwrap();
        return Some(create_body);
    }

    None
}

#[rstest]
#[case::upload_package(false, StatusCode::OK)]
#[case::relationship_doesnt_exist(true, StatusCode::BAD_REQUEST)]
#[tokio::test]
async fn package_upload_test(
    #[case] invalid_relationships: bool,
    #[case] expected_status_code: StatusCode,
) {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.router).await;

    let benefactor_account = create_full_account(
        &mut context,
        &bootstrap.services,
        Network::BitcoinSignet,
        None,
    )
    .await;

    let mut relationship_ids = Vec::new();
    if invalid_relationships {
        relationship_ids.extend(vec![RecoveryRelationshipId::from_str(
            "urn:wallet-recovery-relationship:01J7P50S2SPMZJPQTERANGC0FE",
        )
        .unwrap()]);
    } else {
        let beneficiary_account = Account::Full(
            create_full_account(
                &mut context,
                &bootstrap.services,
                Network::BitcoinSignet,
                None,
            )
            .await,
        );

        let create_body = try_create_relationship(
            &context,
            &client,
            &benefactor_account.id,
            &TrustedContactRole::Beneficiary,
            &CognitoAuthentication::Wallet {
                is_app_signed: true,
                is_hardware_signed: true,
            },
            StatusCode::OK,
            1,
            0,
        )
        .await
        .unwrap();

        let recovery_relationship_id = create_body
            .invitation
            .recovery_relationship_info
            .recovery_relationship_id
            .clone();

        try_accept_recovery_relationship_invitation(
            &context,
            &client,
            &benefactor_account.id,
            beneficiary_account.get_id(),
            &TrustedContactRole::Beneficiary,
            &CognitoAuthentication::Recovery,
            &create_body.invitation,
            CodeOverride::None,
            StatusCode::OK,
            1,
        )
        .await;

        try_endorse_recovery_relationship(
            &context,
            &client,
            &benefactor_account.id,
            &TrustedContactRole::Beneficiary,
            &recovery_relationship_id,
            "RANDOM_CERT",
            StatusCode::OK,
        )
        .await;

        relationship_ids.push(recovery_relationship_id.clone());
    }

    // act
    try_package_upload(
        &context,
        &client,
        &benefactor_account.id,
        &relationship_ids,
        expected_status_code,
    )
    .await;

    // assert
    if expected_status_code.is_success() {
        let packages = bootstrap
            .services
            .inheritance_service
            .get_packages_by_relationship_id(&relationship_ids)
            .await
            .unwrap();
        assert_eq!(packages.len(), relationship_ids.len());
    }
}

#[rstest]
#[case::cancel_with_benefactor(InheritanceClaimActor::Benefactor, false, StatusCode::OK)]
#[case::cancel_with_beneficiary(InheritanceClaimActor::Beneficiary, false, StatusCode::OK)]
#[case::cancel_with_random_account(
    InheritanceClaimActor::External,
    false,
    StatusCode::UNAUTHORIZED
)]
#[case::cancel_with_benefactor_and_canceled_claim(
    InheritanceClaimActor::Benefactor,
    true,
    StatusCode::OK
)]
#[case::cancel_with_beneficiary_and_canceled_claim(
    InheritanceClaimActor::Beneficiary,
    true,
    StatusCode::OK
)]
#[case::cancel_with_external_and_canceled_claim(
    InheritanceClaimActor::External,
    true,
    StatusCode::UNAUTHORIZED
)]
#[tokio::test]
async fn cancel_inheritance_claim(
    #[case] cancel_role: InheritanceClaimActor,
    #[case] precancel_claim: bool,
    #[case] expected_status_code: StatusCode,
) {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.clone().router).await;

    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        ..
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Full,
    )
    .await;
    let external_account = Account::Full(
        create_full_account(
            &mut context,
            &bootstrap.services,
            Network::BitcoinSignet,
            None,
        )
        .await,
    );
    let rotate_to_inheritance_keys = if let Account::Full(f) = &beneficiary {
        f.active_auth_keys().unwrap().to_owned()
    } else {
        unimplemented!()
    };
    let BeneficiaryInheritanceClaimView::Pending(pending_claim) = try_start_inheritance_claim(
        &context,
        &client,
        beneficiary.get_id(),
        &recovery_relationship_id,
        &rotate_to_inheritance_keys,
        StatusCode::OK,
    )
    .await
    .expect("Created inheritance claim")
    .claim
    else {
        panic!("Expected a pending claim");
    };
    let pending_claim_id = pending_claim.common_fields.id;
    let account_id = match cancel_role {
        InheritanceClaimActor::Benefactor => benefactor.get_id(),
        InheritanceClaimActor::Beneficiary => beneficiary.get_id(),
        InheritanceClaimActor::External => external_account.get_id(),
    };
    if precancel_claim {
        try_cancel_inheritance_claim(
            &context,
            &client,
            beneficiary.get_id(),
            beneficiary.get_id(),
            &pending_claim_id,
            StatusCode::OK,
        )
        .await;
    }

    // act
    try_cancel_inheritance_claim(
        &context,
        &client,
        if matches!(cancel_role, InheritanceClaimActor::External) {
            beneficiary.get_id()
        } else {
            account_id
        },
        account_id,
        &pending_claim_id,
        expected_status_code,
    )
    .await;

    // assert
    if expected_status_code.is_success() {
        let benefactor_claims_resp = client
            .get_inheritance_claims(
                &benefactor.get_id().to_string(),
                &context
                    .get_authentication_keys_for_account_id(benefactor.get_id())
                    .expect("Invalid keys for account"),
            )
            .await;

        assert_eq!(benefactor_claims_resp.status_code, StatusCode::OK);
        let benefactor_claims = benefactor_claims_resp.body.unwrap().claims_as_benefactor;
        assert_eq!(benefactor_claims.len(), 1);
        assert!(matches!(
            benefactor_claims[0],
            BenefactorInheritanceClaimView::Canceled(_)
        ));

        let beneficiary_claims_resp = client
            .get_inheritance_claims(
                &beneficiary.get_id().to_string(),
                &context
                    .get_authentication_keys_for_account_id(beneficiary.get_id())
                    .expect("Invalid keys for account"),
            )
            .await;

        assert_eq!(beneficiary_claims_resp.status_code, StatusCode::OK);
        let beneficiary_claims = beneficiary_claims_resp.body.unwrap().claims_as_beneficiary;
        assert_eq!(beneficiary_claims.len(), 1);
        assert!(matches!(
            beneficiary_claims[0],
            BeneficiaryInheritanceClaimView::Canceled(_)
        ));

        let claims = bootstrap
            .services
            .inheritance_service
            .repository
            .fetch_claims_for_recovery_relationship_id(&recovery_relationship_id)
            .await
            .expect("no claims found");
        assert_eq!(claims.len(), 1);

        // Check whether the notifications were created
        let (
            benefactor_expected_scheduled_notifications_types,
            beneficiary_expected_scheduled_notifications_types,
        ) = (
            vec![
                // The initiated schedule gets split into 1 that gets sent immediately
                // and a schedule that starts in 7 days
                NotificationPayloadType::RecoveryRelationshipInvitationPending,
                NotificationPayloadType::InheritanceClaimPeriodInitiated,
                NotificationPayloadType::InheritanceClaimPeriodCompleted,
            ],
            vec![NotificationPayloadType::InheritanceClaimPeriodCompleted],
        );

        let (
            benefactor_expected_customer_notifications_types,
            beneficiary_expected_customer_notifications_types,
        ) = (
            vec![
                NotificationPayloadType::RecoveryRelationshipInvitationAccepted,
                NotificationPayloadType::InheritanceClaimPeriodInitiated,
                NotificationPayloadType::InheritanceClaimCanceled,
            ],
            vec![
                NotificationPayloadType::InheritanceClaimPeriodInitiated,
                NotificationPayloadType::InheritanceClaimCanceled,
            ],
        );

        join!(
            assert_notifications(
                &bootstrap,
                benefactor.get_id(),
                benefactor_expected_customer_notifications_types,
                benefactor_expected_scheduled_notifications_types
            ),
            assert_notifications(
                &bootstrap,
                beneficiary.get_id(),
                beneficiary_expected_customer_notifications_types,
                beneficiary_expected_scheduled_notifications_types
            ),
        );
    }
}

pub(super) async fn try_update_inheritance_claim<'a>(
    context: &TestContext,
    client: &TestClient,
    beneficiary_account: &'a Account,
    inheritance_claim_id: InheritanceClaimId,
    destination: &InheritanceDestination,
    expected_status_code: StatusCode,
) -> Option<UpdateInheritanceProcessWithDestinationResponse> {
    let beneficiary_account_id = &beneficiary_account.get_id();
    let keys = context
        .get_authentication_keys_for_account_id(beneficiary_account_id)
        .expect("Invalid keys for account");

    let update_response = client
        .update_inheritance_claim(
            &beneficiary_account.get_id().to_string(),
            &inheritance_claim_id.to_string(),
            &UpdateInheritanceProcessWithDestinationRequest {
                destination: destination.to_owned(),
            },
            &keys,
        )
        .await;

    assert_eq!(
        update_response.status_code, expected_status_code,
        "{:?}",
        update_response.body_string
    );

    if expected_status_code == StatusCode::OK {
        let update_body = update_response.body.unwrap();
        return Some(update_body);
    }

    None
}

#[rstest]
#[case::update_claim(
    AccountType::Full,
    InheritanceClaimActor::Beneficiary,
    InheritanceDestination::Internal {
        destination_address: String::from("tb1qx70e787fwv2wzy39denc86vkdzgaf8804g4lsylh5cv5dz5xlj9skzcl7c")
    },
    StatusCode::OK
)]
#[case::benefactor_update(
    AccountType::Full,
    InheritanceClaimActor::Benefactor,
    InheritanceDestination::Internal {
        destination_address: String::from("tb1qx70e787fwv2wzy39denc86vkdzgaf8804g4lsylh5cv5dz5xlj9skzcl7c")
    },
    StatusCode::BAD_REQUEST
)]
#[case::external_actor(
    AccountType::Full,
    InheritanceClaimActor::External,
    InheritanceDestination::Internal {
        destination_address: String::from("tb1qx70e787fwv2wzy39denc86vkdzgaf8804g4lsylh5cv5dz5xlj9skzcl7c")
    },
    StatusCode::BAD_REQUEST
)]
#[case::invalid_internal_destination(
    AccountType::Full,
    InheritanceClaimActor::Beneficiary,
    InheritanceDestination::Internal {
        destination_address: String::from("tb1q3fd3hf4ccfa0qsne3hcj3k7h6272sg00g4458q")
    },
    StatusCode::BAD_REQUEST
)]
#[case::invalid_external_destination(
    AccountType::Full,
    InheritanceClaimActor::Beneficiary,
    InheritanceDestination::External {
        destination_address: String::from("tb1q3fd3hf4ccfa0qsne3hcj3k7h6272sg00g4458q")
    },
    StatusCode::BAD_REQUEST
)]
#[tokio::test]
#[ignore = "Updating inheritance claims with destination is not required for V1"]
async fn update_inheritance_claim_test(
    #[case] beneficiary_account_type: AccountType,
    #[case] updater: InheritanceClaimActor,
    #[case] destination: InheritanceDestination,
    #[case] expected_status_code: StatusCode,
) {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.clone().router).await;

    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        ..
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        beneficiary_account_type,
    )
    .await;
    let external_account = Account::Full(
        create_full_account(
            &mut context,
            &bootstrap.services,
            Network::BitcoinSignet,
            None,
        )
        .await,
    );

    let rotate_to_inheritance_keys = match &beneficiary {
        Account::Full(f) => f.active_auth_keys().unwrap().to_owned(),
        Account::Software(_) | Account::Lite(_) => unimplemented!(),
    };

    let response = try_start_inheritance_claim(
        &context,
        &client,
        beneficiary.get_id(),
        &recovery_relationship_id,
        &rotate_to_inheritance_keys,
        StatusCode::OK,
    )
    .await;

    match response.expect("expected start claim response").claim {
        BeneficiaryInheritanceClaimView::Canceled(_)
        | BeneficiaryInheritanceClaimView::Locked(_)
        | BeneficiaryInheritanceClaimView::Completed(_) => {
            panic!("Expected a pending claim");
        }
        BeneficiaryInheritanceClaimView::Pending(pending_claim) => {
            let actor = match updater {
                InheritanceClaimActor::Benefactor => &benefactor,
                InheritanceClaimActor::Beneficiary => &beneficiary,
                InheritanceClaimActor::External => &external_account,
            };
            // act
            try_update_inheritance_claim(
                &context,
                &client,
                actor,
                pending_claim.common_fields.id,
                &destination,
                expected_status_code,
            )
            .await;
        }
    }

    // assert
    if expected_status_code.is_success() {
        let claims = bootstrap
            .services
            .inheritance_service
            .repository
            .fetch_claims_for_recovery_relationship_id(&recovery_relationship_id)
            .await
            .expect("no relationships found for recovery relationship id");
        assert_eq!(
            claims
                .into_iter()
                .map(|c| c.common_fields().to_owned().destination.unwrap())
                .collect::<Vec<_>>(),
            vec![destination]
        );
    }
}

#[tokio::test]
async fn test_lock_inheritance_claim_success() {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.router.clone()).await;

    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        ..
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Full,
    )
    .await;

    let secp = Secp256k1::new();
    let (app_auth_seckey, app_auth_pubkey) = secp.generate_keypair(&mut thread_rng());
    let (hardware_auth_seckey, hardware_auth_pubkey) = secp.generate_keypair(&mut thread_rng());
    let (_recovery_auth_seckey, recovery_auth_pubkey) = secp.generate_keypair(&mut thread_rng());
    let auth_keys = FullAccountAuthKeys::new(
        app_auth_pubkey,
        hardware_auth_pubkey,
        Some(recovery_auth_pubkey),
    );

    let pending_claim = create_lockable_claim(
        &mut context,
        bootstrap,
        &client,
        &benefactor,
        &beneficiary,
        &recovery_relationship_id,
        &auth_keys,
    )
    .await;

    let challenge = create_challenge(auth_keys);
    let app_signature = sign_message(&secp, &challenge, &app_auth_seckey);
    let hardware_signature = sign_message(&secp, &challenge, &hardware_auth_seckey);

    // act
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{}/lock",
        beneficiary.get_id(),
        pending_claim.common_fields.id
    );
    let body = json!({
        "recovery_relationship_id": recovery_relationship_id,
        "challenge": challenge,
        "app_signature": app_signature,
        "hardware_signature": hardware_signature,
    })
    .to_string();

    let response = client
        .make_request::<CreateRelationshipRequest>(&uri, &Method::PUT, Body::from(body))
        .await;

    // assert
    assert_eq!(response.status_code, StatusCode::OK);

    let Account::Full(benefactor_account) = benefactor else {
        panic!("Expected a full account")
    };

    let expected_descriptor_keyset = benefactor_account
        .active_descriptor_keyset()
        .unwrap()
        .into_multisig_descriptor()
        .unwrap();
    let expected_claim_id = pending_claim.common_fields.id;

    let expected_response_json = json!({
        "claim": {
            "status": "LOCKED",
            "id": expected_claim_id,
            "recovery_relationship_id": recovery_relationship_id,
            "sealed_dek": "RANDOM_SEALED_DEK",
            "sealed_mobile_key": "RANDOM_SEALED_MOBILE_KEY",
            "benefactor_descriptor_keyset": expected_descriptor_keyset,
        }
    });
    let actual_response_json: Value =
        serde_json::from_str(&response.body_string).expect("should be valid json");

    assert_eq!(actual_response_json, expected_response_json);
}

fn create_challenge(auth_keys: FullAccountAuthKeys) -> String {
    let challenge = format!(
        "LockInheritanceClaim{}{}{}",
        auth_keys.hardware_pubkey,
        auth_keys.app_pubkey,
        auth_keys
            .recovery_pubkey
            .map(|k| k.to_string())
            .unwrap_or("".to_string())
    );
    challenge
}

async fn complete_claim_delay_period(
    bootstrap: Bootstrap,
    pending_claim_view: &BeneficiaryInheritanceClaimViewPending,
) {
    let repository = bootstrap.services.inheritance_service.repository;
    let pending_claim = repository
        .fetch_inheritance_claim(&pending_claim_view.common_fields.id)
        .await
        .expect("fetch claim");
    let pending_claim_past_delay_end_time = InheritanceClaimPending {
        common_fields: pending_claim.common_fields().clone(),
        delay_end_time: OffsetDateTime::now_utc() - Duration::days(1),
    };

    repository
        .persist_inheritance_claim(&InheritanceClaim::Pending(
            pending_claim_past_delay_end_time,
        ))
        .await
        .expect("persist claim");
}

#[tokio::test]
async fn test_lock_inheritance_claim_lite_account_forbidden() {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.router.clone()).await;

    let BenefactorBeneficiarySetup {
        beneficiary,
        recovery_relationship_id,
        ..
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Lite,
    )
    .await;

    // act
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{}/lock",
        beneficiary.get_id(),
        InheritanceClaimId::gen().expect("fake claim id")
    );
    let body = json!({
        "recovery_relationship_id": recovery_relationship_id,
        "challenge": "fake_challenge",
        "app_signature": "fake_app_signature",
        "hardware_signature": "fake_hardware_signature",
    })
    .to_string();

    let response = client
        .make_request::<CreateRelationshipRequest>(&uri, &Method::PUT, Body::from(body))
        .await;

    // assert
    assert_eq!(response.status_code, StatusCode::FORBIDDEN);
}

mock! {
    TransactionBroadcaster { }
    impl TransactionBroadcasterTrait for TransactionBroadcaster {
        fn broadcast(
            &self,
            wallet: Wallet<AnyDatabase>,
            transaction: &mut Psbt,
            rpc_uris: &ElectrumRpcUris
        ) -> Result<(), BdkUtilError>;
    }
}
#[tokio::test]
async fn test_complete_inheritance_claim_lite_account_forbidden() {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.router.clone()).await;

    let BenefactorBeneficiarySetup { beneficiary, .. } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Lite,
    )
    .await;
    let keys = create_new_authkeys(&mut context);

    // act
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{}/complete",
        beneficiary.get_id(),
        InheritanceClaimId::gen().expect("fake claim id")
    );
    let body = json!({ "psbt": "cHNidP8BAHUCAAAAASaBcTce3/KF6Tet7qSze3gADAVmy7OtZGQXE8pCFxv2AAAAAAD+////AtPf9QUAAAAAGXapFNDFmQPFusKGh2DpD9UhpGZap2UgiKwA4fUFAAAAABepFDVF5uM7gyxHBQ8k0+65PJwDlIvHh7MuEwAAAQD9pQEBAAAAAAECiaPHHqtNIOA3G7ukzGmPopXJRjr6Ljl/hTPMti+VZ+UBAAAAFxYAFL4Y0VKpsBIDna89p95PUzSe7LmF/////4b4qkOnHf8USIk6UwpyN+9rRgi7st0tAXHmOuxqSJC0AQAAABcWABT+Pp7xp0XpdNkCxDVZQ6vLNL1TU/////8CAMLrCwAAAAAZdqkUhc/xCX/Z4Ai7NK9wnGIZeziXikiIrHL++E4sAAAAF6kUM5cluiHv1irHU6m80GfWx6ajnQWHAkcwRAIgJxK+IuAnDzlPVoMR3HyppolwuAJf3TskAinwf4pfOiQCIAGLONfc0xTnNMkna9b7QPZzMlvEuqFEyADS8vAtsnZcASED0uFWdJQbrUqZY3LLh+GFbTZSYG2YVi/jnF6efkE/IQUCSDBFAiEA0SuFLYXc2WHS9fSrZgZU327tzHlMDDPOXMMJ/7X85Y0CIGczio4OFyXBl/saiK9Z9R5E5CVbIBZ8hoQDHAXR8lkqASECI7cr7vCWXRC+B3jv7NYfysb3mk6haTkzgHNEZPhPKrMAAAAAAAAA" }).to_string();

    let response = client
        .make_request_with_auth::<CompleteInheritanceClaimResponse>(
            &uri,
            beneficiary.get_id().to_string().as_str(),
            &Method::PUT,
            Body::from(body),
            &CognitoAuthentication::Wallet {
                is_app_signed: true,
                is_hardware_signed: true,
            },
            &keys,
        )
        .await;

    // assert
    assert_eq!(response.status_code, StatusCode::FORBIDDEN);
}
#[tokio::test]
async fn test_complete_inheritance_claim_success() {
    // arrange
    let mut broadcaster_mock = MockTransactionBroadcaster::new();
    broadcaster_mock
        .expect_broadcast()
        .times(1)
        .returning(|_, _, _| Ok(()));
    let overrides = GenServiceOverrides::new().broadcaster(Arc::new(broadcaster_mock));
    let (mut context, bootstrap) = gen_services_with_overrides(overrides).await;
    let client = TestClient::new(bootstrap.router.clone()).await;
    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        benefactor_wallet,
        beneficiary_wallet,
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Full,
    )
    .await;
    let claim_id = create_locked_claim(
        &mut context,
        bootstrap,
        &client,
        &benefactor,
        &beneficiary,
        &recovery_relationship_id,
    )
    .await;
    let (psbt, _) = build_sweep_psbt(&benefactor_wallet, beneficiary_wallet.as_ref(), 5.0, true);

    // act
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{claim_id}/complete",
        beneficiary.get_id()
    );
    let body = json!({ "psbt": psbt.to_string() }).to_string();
    let keys = context
        .get_authentication_keys_for_account_id(beneficiary.get_id())
        .expect("Invalid keys for account");
    let response = client
        .make_request_with_auth::<CompleteInheritanceClaimResponse>(
            &uri,
            &beneficiary.get_id().to_string(),
            &Method::PUT,
            Body::from(body),
            &CognitoAuthentication::Wallet {
                is_app_signed: true,
                is_hardware_signed: true,
            },
            &keys,
        )
        .await;

    // assert
    assert_eq!(response.status_code, StatusCode::OK);

    let expected_response_json_without_psbt = json!({
        "claim": {
            "status": "COMPLETED",
            "id": claim_id,
            "recovery_relationship_id": recovery_relationship_id,
        }
    });
    let actual_response_json: Value =
        serde_json::from_str(&response.body_string).expect("should be valid json");
    let mut actual_response_json_without_psbt = actual_response_json.clone();
    actual_response_json_without_psbt["claim"]
        .as_object_mut()
        .unwrap()
        .remove("psbt");

    let actual_psbt = actual_response_json["claim"]["psbt"]
        .as_str()
        .expect("psbt field missing");
    let actual_psbt = Psbt::from_str(actual_psbt).expect("psbt should be valid");

    assert_eq!(
        actual_response_json_without_psbt,
        expected_response_json_without_psbt
    );

    assert_eq!(actual_psbt.unsigned_tx, psbt.unsigned_tx);
    actual_psbt.inputs.iter().for_each(|input| {
        assert!(input.final_script_witness.is_some());
    });
}

#[tokio::test]
async fn test_complete_inheritance_claim_rbf_success() {
    // arrange
    let mut broadcaster_mock = MockTransactionBroadcaster::new();
    broadcaster_mock
        .expect_broadcast()
        .times(2)
        .returning(|_, _, _| Ok(()));
    let overrides = GenServiceOverrides::new().broadcaster(Arc::new(broadcaster_mock));
    let (mut context, bootstrap) = gen_services_with_overrides(overrides).await;
    let client = TestClient::new(bootstrap.router.clone()).await;
    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        benefactor_wallet,
        beneficiary_wallet,
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Full,
    )
    .await;
    let claim_id = create_locked_claim(
        &mut context,
        bootstrap,
        &client,
        &benefactor,
        &beneficiary,
        &recovery_relationship_id,
    )
    .await;
    let (psbt, _) = build_sweep_psbt(&benefactor_wallet, beneficiary_wallet.as_ref(), 5.0, true);
    let auth = &CognitoAuthentication::Wallet {
        is_app_signed: true,
        is_hardware_signed: true,
    };

    // complete claim
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{claim_id}/complete",
        beneficiary.get_id()
    );
    let keys = context
        .get_authentication_keys_for_account_id(beneficiary.get_id())
        .expect("Invalid keys for account");
    let _ = client
        .make_request_with_auth::<CompleteInheritanceClaimResponse>(
            &uri,
            &beneficiary.get_id().to_string(),
            &Method::PUT,
            Body::from(json!({ "psbt": psbt.to_string() }).to_string()),
            auth,
            &keys,
        )
        .await;

    let (rbf_psbt, _) =
        build_sweep_psbt(&benefactor_wallet, beneficiary_wallet.as_ref(), 7.0, true);

    // act
    let rbf_response = client
        .make_request_with_auth::<CompleteInheritanceClaimResponse>(
            &uri,
            &beneficiary.get_id().to_string(),
            &Method::PUT,
            Body::from(json!({ "psbt": rbf_psbt.to_string() }).to_string()),
            auth,
            &keys,
        )
        .await;

    // assert
    assert_eq!(rbf_response.status_code, StatusCode::OK);

    let actual_response_json: Value =
        serde_json::from_str(&rbf_response.body_string).expect("should be valid json");

    let actual_psbt = actual_response_json["claim"]["psbt"]
        .as_str()
        .expect("psbt field missing");
    let actual_psbt = Psbt::from_str(actual_psbt).expect("psbt should be valid");

    assert_eq!(actual_psbt.unsigned_tx, rbf_psbt.unsigned_tx);
}

#[tokio::test]
async fn test_complete_inheritance_claim_sanctions_failure() {
    // arrange
    let (mut context, bootstrap) = gen_services().await;
    let client = TestClient::new(bootstrap.router.clone()).await;
    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        benefactor_wallet,
        beneficiary_wallet,
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Full,
    )
    .await;
    let claim_id = create_locked_claim(
        &mut context,
        bootstrap,
        &client,
        &benefactor,
        &beneficiary,
        &recovery_relationship_id,
    )
    .await;
    let (psbt, beneficiary_address) =
        build_sweep_psbt(&benefactor_wallet, beneficiary_wallet.as_ref(), 5.0, true);

    // add beneficiary to blocked list
    let blocked_hash_set = HashSet::from([beneficiary_address.to_string()]);
    let mut broadcaster_mock = MockTransactionBroadcaster::new();
    broadcaster_mock.expect_broadcast().times(0);
    let overrides = GenServiceOverrides::new()
        .broadcaster(Arc::new(broadcaster_mock))
        .blocked_addresses(blocked_hash_set);
    let (_, bootstrap) = gen_services_with_overrides(overrides).await;
    let client = TestClient::new(bootstrap.router).await;

    // act
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{claim_id}/complete",
        beneficiary.get_id()
    );
    let body = json!({ "psbt": psbt.to_string() }).to_string();
    let keys = context
        .get_authentication_keys_for_account_id(beneficiary.get_id())
        .expect("Invalid keys for account");
    let response = client
        .make_request_with_auth::<CompleteInheritanceClaimResponse>(
            &uri,
            &beneficiary.get_id().to_string(),
            &Method::PUT,
            Body::from(body),
            &CognitoAuthentication::Wallet {
                is_app_signed: true,
                is_hardware_signed: true,
            },
            &keys,
        )
        .await;

    // assert
    assert_eq!(
        response.status_code,
        StatusCode::UNAVAILABLE_FOR_LEGAL_REASONS
    );
}

#[tokio::test]
async fn test_complete_inheritance_claim_unsigned_psbt_fails() {
    // arrange
    let mut broadcaster_mock = MockTransactionBroadcaster::new();
    broadcaster_mock.expect_broadcast().times(0);
    let overrides = GenServiceOverrides::new().broadcaster(Arc::new(broadcaster_mock));
    let (mut context, bootstrap) = gen_services_with_overrides(overrides).await;
    let client = TestClient::new(bootstrap.router.clone()).await;
    let BenefactorBeneficiarySetup {
        benefactor,
        beneficiary,
        recovery_relationship_id,
        benefactor_wallet,
        beneficiary_wallet,
    } = setup_benefactor_and_beneficiary_account(
        &mut context,
        &bootstrap,
        &client,
        AccountType::Full,
    )
    .await;
    let claim_id = create_locked_claim(
        &mut context,
        bootstrap,
        &client,
        &benefactor,
        &beneficiary,
        &recovery_relationship_id,
    )
    .await;
    let (psbt, _) = build_sweep_psbt(&benefactor_wallet, beneficiary_wallet.as_ref(), 5.0, false);

    // act
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{claim_id}/complete",
        beneficiary.get_id()
    );
    let body = json!({ "psbt": psbt.to_string() }).to_string();
    let keys = context
        .get_authentication_keys_for_account_id(beneficiary.get_id())
        .expect("Invalid keys for account");
    let response = client
        .make_request_with_auth::<CompleteInheritanceClaimResponse>(
            &uri,
            &beneficiary.get_id().to_string(),
            &Method::PUT,
            Body::from(body),
            &CognitoAuthentication::Wallet {
                is_app_signed: true,
                is_hardware_signed: true,
            },
            &keys,
        )
        .await;

    // assert
    assert_eq!(response.status_code, StatusCode::INTERNAL_SERVER_ERROR);
    assert!(response
        .body_string
        .contains("Input does not only have one signature"));
}

async fn create_locked_claim(
    context: &mut TestContext,
    bootstrap: Bootstrap,
    client: &TestClient,
    benefactor: &Account,
    beneficiary: &Account,
    recovery_relationship_id: &RecoveryRelationshipId,
) -> InheritanceClaimId {
    let secp = Secp256k1::new();
    let (app_auth_seckey, app_auth_pubkey) = secp.generate_keypair(&mut thread_rng());
    let (hardware_auth_seckey, hardware_auth_pubkey) = secp.generate_keypair(&mut thread_rng());
    let (_recovery_auth_seckey, recovery_auth_pubkey) = secp.generate_keypair(&mut thread_rng());
    let auth_keys = FullAccountAuthKeys::new(
        app_auth_pubkey,
        hardware_auth_pubkey,
        Some(recovery_auth_pubkey),
    );

    let pending_claim = create_lockable_claim(
        context,
        bootstrap,
        client,
        benefactor,
        beneficiary,
        recovery_relationship_id,
        &auth_keys,
    )
    .await;

    let challenge = create_challenge(auth_keys);
    let app_signature = sign_message(&secp, &challenge, &app_auth_seckey);
    let hardware_signature = sign_message(&secp, &challenge, &hardware_auth_seckey);

    // lock claim
    let uri = format!(
        "/api/accounts/{}/recovery/inheritance/claims/{}/lock",
        beneficiary.get_id(),
        pending_claim.common_fields.id
    );
    let body = json!({
        "recovery_relationship_id": recovery_relationship_id,
        "challenge": challenge,
        "app_signature": app_signature,
        "hardware_signature": hardware_signature,
    })
    .to_string();
    let _ = client
        .make_request::<LockInheritanceClaimResponse>(&uri, &Method::PUT, Body::from(body))
        .await;
    pending_claim.common_fields.id
}

fn build_sweep_psbt(
    benefactor_wallet: &Wallet<AnyDatabase>,
    beneficiary_wallet: Option<&Wallet<AnyDatabase>>,
    fee: f32,
    sign: bool,
) -> (PartiallySignedTransaction, AddressInfo) {
    let beneficiary_address = beneficiary_wallet
        .expect("Beneficiary wallet not created")
        .get_address(AddressIndex::New)
        .expect("Could not get address");

    let mut builder = benefactor_wallet.build_tx();
    builder
        .drain_wallet()
        .drain_to(beneficiary_address.script_pubkey())
        .fee_rate(FeeRate::from_sat_per_vb(fee));

    let (mut psbt, _) = builder.finish().expect("Could not build psbt");

    if sign {
        let _ = benefactor_wallet.sign(
            &mut psbt,
            SignOptions {
                remove_partial_sigs: false,
                ..SignOptions::default()
            },
        );
    }
    (psbt, beneficiary_address)
}

async fn create_lockable_claim(
    context: &mut TestContext,
    bootstrap: Bootstrap,
    client: &TestClient,
    benefactor: &Account,
    beneficiary: &Account,
    recovery_relationship_id: &RecoveryRelationshipId,
    auth_keys: &FullAccountAuthKeys,
) -> BeneficiaryInheritanceClaimViewPending {
    let BeneficiaryInheritanceClaimView::Pending(pending_claim) = try_start_inheritance_claim(
        context,
        client,
        beneficiary.get_id(),
        recovery_relationship_id,
        auth_keys,
        StatusCode::OK,
    )
    .await
    .expect("Created inheritance claim")
    .claim
    else {
        panic!("Expected a pending claim");
    };

    complete_claim_delay_period(bootstrap, &pending_claim).await;

    try_package_upload(
        context,
        client,
        benefactor.get_id(),
        &vec![recovery_relationship_id.clone()],
        StatusCode::OK,
    )
    .await;
    pending_claim
}

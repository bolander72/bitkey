use bitcoin::Network;
use crypto::keys::PublicKey;
use serde::{Deserialize, Serialize};

use crate::derivation::WSMSupportedDomain;

#[derive(Deserialize, Serialize, Debug)]
pub struct InitiateDistributedKeygenRequest {
    pub root_key_id: String,
    pub network: Network,
    pub sealed_request: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct InitiateDistributedKeygenResponse {
    pub root_key_id: String,
    pub sealed_response: String,
    pub aggregate_public_key: PublicKey,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct ContinueDistributedKeygenRequest {
    pub root_key_id: String,
    pub network: Network,
    pub sealed_request: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct ContinueDistributedKeygenResponse {
    pub root_key_id: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct CreateRootKeyRequest {
    pub root_key_id: String,
    pub network: Network,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct DeriveKeyRequest {
    pub root_key_id: String,
    pub domain: WSMSupportedDomain,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct CreatedSigningKey {
    pub root_key_id: String,
    pub xpub: String,
    #[serde(default)]
    pub xpub_sig: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct CreatedSigningKeyShare {
    pub root_key_id: String,
    pub aggregate_public_key: PublicKey,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct SignPsbtRequest {
    pub root_key_id: String,
    pub descriptor: String,
    pub change_descriptor: String,
    pub psbt: String,
}

#[derive(Serialize, Deserialize, Debug)]
pub struct SignedPsbt {
    pub psbt: String,
    pub root_key_id: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct SignBlobRequest {
    pub root_key_id: String,
    pub blob: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct GenerateIntegrityKeyResponse {
    pub wrapped_privkey: String,
    pub pubkey: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct GetIntegritySigRequest {
    pub root_key_id: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct GetIntegritySigResponse {
    pub signature: String,
}

#[derive(Deserialize, Serialize, Debug)]
pub struct AttestationDocRequest {}

#[derive(Deserialize, Serialize, Debug)]
pub struct AttestationDocResponse {
    pub document: Vec<u8>,
}

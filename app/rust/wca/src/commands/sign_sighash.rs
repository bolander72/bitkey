use bitcoin::secp256k1::ThirtyTwoByteHash;
use bitcoin::{bip32::DerivationPath, secp256k1::ecdsa::Signature};
use miniscript::DescriptorPublicKey;
use next_gen::generator;

use crate::signing::Sighash;
use crate::wca;
use crate::{
    errors::CommandError,
    fwpb::{self, derive_and_sign_rsp::DeriveAndSignRspStatus, DeriveKeyDescriptorAndSignCmd},
};

pub struct SignedSighash {
    pub signature: Signature,
    pub descriptor: DescriptorPublicKey,
}

#[generator(yield(Vec<u8>), resume(Vec<u8>))]
pub(crate) fn derive_and_sign(
    sighash: Sighash,
    derivation_path: &DerivationPath,
) -> Result<Signature, CommandError> {
    let sighash_slice = match sighash {
        Sighash::Legacy(sighash) => sighash.into_32(),
        Sighash::SegwitV0(sighash) => sighash.into_32(),
    };

    let apdu: apdu::Command = DeriveKeyDescriptorAndSignCmd {
        derivation_path: Some(derivation_path.into()),
        hash: sighash_slice.to_vec(),
    }
    .try_into()?;
    let data = yield_!(apdu.into());

    let response = apdu::Response::from(data);
    let message = wca::decode_and_check(response)?
        .msg
        .ok_or(CommandError::MissingMessage)?;

    match message {
        fwpb::wallet_rsp::Msg::DeriveAndSignRsp(fwpb::DeriveAndSignRsp { status, signature }) => {
            match DeriveAndSignRspStatus::from_i32(status) {
                Some(DeriveAndSignRspStatus::Success) => Ok(Signature::from_compact(&signature)?),
                Some(DeriveAndSignRspStatus::DerivationFailed) => {
                    Err(CommandError::KeyGenerationFailed)
                }
                Some(DeriveAndSignRspStatus::Error) => Err(CommandError::GeneralCommandError),
                Some(DeriveAndSignRspStatus::Unauthenticated) => Err(CommandError::Unauthenticated),
                Some(DeriveAndSignRspStatus::Unspecified) => {
                    Err(CommandError::UnspecifiedCommandError)
                }
                None => Err(CommandError::InvalidResponse),
            }
        }
        _ => Err(CommandError::MissingMessage),
    }
}

package build.wallet.di

import build.wallet.account.AccountRepository
import build.wallet.account.analytics.AppInstallationDao
import build.wallet.analytics.events.*
import build.wallet.auth.AccountAuthenticator
import build.wallet.auth.AppAuthKeyMessageSigner
import build.wallet.auth.AuthTokenDao
import build.wallet.auth.AuthTokensRepository
import build.wallet.availability.F8eAuthSignatureStatusProvider
import build.wallet.availability.NetworkReachabilityEventDao
import build.wallet.availability.NetworkReachabilityProvider
import build.wallet.bdk.bindings.BdkAddressBuilder
import build.wallet.bdk.bindings.BdkDescriptorSecretKeyGenerator
import build.wallet.bdk.bindings.BdkMnemonicGenerator
import build.wallet.bdk.bindings.BdkPartiallySignedTransactionBuilder
import build.wallet.bitcoin.AppPrivateKeyDao
import build.wallet.bitcoin.address.BitcoinAddressService
import build.wallet.bitcoin.bdk.BdkBlockchainProvider
import build.wallet.bitcoin.descriptor.BitcoinMultiSigDescriptorBuilder
import build.wallet.bitcoin.fees.BitcoinFeeRateEstimator
import build.wallet.bitcoin.keys.ExtendedKeyGenerator
import build.wallet.bitcoin.sync.ElectrumReachability
import build.wallet.bitcoin.sync.ElectrumServerConfigRepository
import build.wallet.bitcoin.sync.ElectrumServerSettingProvider
import build.wallet.bitcoin.transactions.OutgoingTransactionDetailDao
import build.wallet.bitcoin.wallet.SpendingWalletProvider
import build.wallet.bugsnag.BugsnagContext
import build.wallet.configuration.MobilePayFiatConfigService
import build.wallet.database.BitkeyDatabaseProvider
import build.wallet.datadog.DatadogRumMonitor
import build.wallet.datadog.DatadogTracer
import build.wallet.debug.DebugOptionsService
import build.wallet.encrypt.MessageSigner
import build.wallet.encrypt.Secp256k1KeyGenerator
import build.wallet.encrypt.SignatureVerifier
import build.wallet.f8e.auth.AuthF8eClient
import build.wallet.f8e.client.F8eHttpClient
import build.wallet.f8e.debug.NetworkingDebugConfigRepository
import build.wallet.f8e.featureflags.FeatureFlagsF8eClient
import build.wallet.f8e.notifications.NotificationTouchpointF8eClient
import build.wallet.feature.FeatureFlag
import build.wallet.feature.FeatureFlagService
import build.wallet.feature.flags.*
import build.wallet.firmware.FirmwareDeviceInfoDao
import build.wallet.firmware.FirmwareMetadataDao
import build.wallet.firmware.FirmwareTelemetryUploader
import build.wallet.firmware.HardwareAttestation
import build.wallet.fwup.FirmwareDataService
import build.wallet.fwup.FwupDataDao
import build.wallet.fwup.FwupDataFetcher
import build.wallet.fwup.FwupProgressCalculator
import build.wallet.inappsecurity.BiometricPreference
import build.wallet.keybox.KeyboxDao
import build.wallet.keybox.keys.AppKeysGenerator
import build.wallet.keybox.keys.OnboardingAppKeyKeystore
import build.wallet.keybox.wallet.AppSpendingWalletProvider
import build.wallet.keybox.wallet.KeysetWalletProvider
import build.wallet.ktor.result.client.KtorLogLevelPolicy
import build.wallet.logging.LogWriterContextStore
import build.wallet.logging.dev.LogStore
import build.wallet.memfault.MemfaultClient
import build.wallet.money.currency.FiatCurrencyDao
import build.wallet.money.display.BitcoinDisplayPreferenceRepository
import build.wallet.money.display.FiatCurrencyPreferenceRepository
import build.wallet.money.exchange.ExchangeRateF8eClient
import build.wallet.nfc.haptics.NfcHaptics
import build.wallet.notifications.DeviceTokenManager
import build.wallet.notifications.NotificationTouchpointDao
import build.wallet.notifications.NotificationTouchpointService
import build.wallet.notifications.RegisterWatchAddressContext
import build.wallet.phonenumber.PhoneNumberValidator
import build.wallet.phonenumber.lib.PhoneNumberLibBindings
import build.wallet.platform.PlatformContext
import build.wallet.platform.config.AppId
import build.wallet.platform.config.AppVariant
import build.wallet.platform.config.DeviceOs
import build.wallet.platform.data.FileDirectoryProvider
import build.wallet.platform.data.FileManager
import build.wallet.platform.device.DeviceInfoProvider
import build.wallet.platform.haptics.Haptics
import build.wallet.platform.permissions.PermissionChecker
import build.wallet.platform.permissions.PushNotificationPermissionStatusProvider
import build.wallet.platform.random.UuidGenerator
import build.wallet.platform.settings.CountryCodeGuesser
import build.wallet.platform.settings.LocaleCountryCodeProvider
import build.wallet.platform.settings.LocaleCurrencyCodeProvider
import build.wallet.platform.settings.LocaleLanguageCodeProvider
import build.wallet.platform.versions.OsVersionInfoProvider
import build.wallet.pricechart.BitcoinPriceCardPreference
import build.wallet.queueprocessor.PeriodicProcessor
import build.wallet.queueprocessor.Processor
import build.wallet.recovery.RecoveryDao
import build.wallet.store.EncryptedKeyValueStoreFactory
import build.wallet.store.KeyValueStoreFactory
import build.wallet.time.Delayer
import build.wallet.worker.AppWorkerExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import kotlin.time.Duration

interface AppComponent {
  val accountAuthenticator: AccountAuthenticator
  val accountRepository: AccountRepository
  val allFeatureFlags: List<FeatureFlag<*>>
  val allRemoteFeatureFlags: List<FeatureFlag<*>>
  val appAuthKeyMessageSigner: AppAuthKeyMessageSigner
  val registerWatchAddressProcessor: Processor<RegisterWatchAddressContext>
  val appWorkerExecutor: AppWorkerExecutor
  val authF8eClient: AuthF8eClient
  val authTokensRepository: AuthTokensRepository
  val appCoroutineScope: CoroutineScope
  val appId: AppId
  val deviceOs: DeviceOs
  val appInstallationDao: AppInstallationDao
  val appKeysGenerator: AppKeysGenerator
  val appPrivateKeyDao: AppPrivateKeyDao
  val appSpendingWalletProvider: AppSpendingWalletProvider
  val keysetWalletProvider: KeysetWalletProvider
  val appVariant: AppVariant
  val appVersion: String
  val authTokenDao: AuthTokenDao
  val bdkAddressBuilder: BdkAddressBuilder
  val bdkBlockchainProvider: BdkBlockchainProvider
  val bdkDescriptorSecretKeyGenerator: BdkDescriptorSecretKeyGenerator
  val bdkMnemonicGenerator: BdkMnemonicGenerator
  val bdkPartiallySignedTransactionBuilder: BdkPartiallySignedTransactionBuilder
  val bitcoinDisplayPreferenceRepository: BitcoinDisplayPreferenceRepository
  val bitcoinMultiSigDescriptorBuilder: BitcoinMultiSigDescriptorBuilder
  val bitkeyDatabaseProvider: BitkeyDatabaseProvider
  val bugsnagContext: BugsnagContext
  val clock: Clock
  val countryCodeGuesser: CountryCodeGuesser
  val datadogRumMonitor: DatadogRumMonitor
  val datadogTracer: DatadogTracer
  val delayer: Delayer
  val deviceInfoProvider: DeviceInfoProvider
  val deviceTokenManager: DeviceTokenManager
  val electrumReachability: ElectrumReachability
  val electrumServerConfigRepository: ElectrumServerConfigRepository
  val electrumServerSettingProvider: ElectrumServerSettingProvider
  val eventStore: EventStore
  val eventTracker: EventTracker
  val extendedKeyGenerator: ExtendedKeyGenerator
  val f8eHttpClient: F8eHttpClient
  val featureFlagService: FeatureFlagService
  val feeBumpIsAvailableFeatureFlag: FeeBumpIsAvailableFeatureFlag
  val fiatCurrencyDao: FiatCurrencyDao
  val fiatCurrencyPreferenceRepository: FiatCurrencyPreferenceRepository
  val fileManager: FileManager
  val fileDirectoryProvider: FileDirectoryProvider
  val firmwareDataService: FirmwareDataService
  val firmwareDeviceInfoDao: FirmwareDeviceInfoDao
  val firmwareMetadataDao: FirmwareMetadataDao
  val firmwareTelemetryUploader: FirmwareTelemetryUploader
  val fwupDataFetcher: FwupDataFetcher
  val fwupDataDao: FwupDataDao
  val fwupProgressCalculator: FwupProgressCalculator
  val featureFlagsF8eClient: FeatureFlagsF8eClient
  val keyboxDao: KeyboxDao
  val keyValueStoreFactory: KeyValueStoreFactory
  val ktorLogLevelPolicy: KtorLogLevelPolicy
  val localeCountryCodeProvider: LocaleCountryCodeProvider
  val localeCurrencyCodeProvider: LocaleCurrencyCodeProvider
  val localeLanguageCodeProvider: LocaleLanguageCodeProvider
  val logStore: LogStore
  val logWriterContextStore: LogWriterContextStore
  val memfaultClient: MemfaultClient
  val messageSigner: MessageSigner
  val mobileTestFeatureFlag: MobileTestFeatureFlag
  val mobilePayFiatConfigService: MobilePayFiatConfigService
  val signatureVerifier: SignatureVerifier
  val networkingDebugConfigRepository: NetworkingDebugConfigRepository
  val networkReachabilityEventDao: NetworkReachabilityEventDao
  val networkReachabilityProvider: NetworkReachabilityProvider
  val notificationTouchpointDao: NotificationTouchpointDao
  val notificationTouchpointF8eClient: NotificationTouchpointF8eClient
  val notificationTouchpointService: NotificationTouchpointService
  val bitcoinAddressService: BitcoinAddressService
  val haptics: Haptics
  val nfcHaptics: NfcHaptics
  val osVersionInfoProvider: OsVersionInfoProvider
  val periodicEventProcessor: PeriodicProcessor
  val periodicFirmwareCoredumpProcessor: PeriodicProcessor
  val periodicFirmwareTelemetryEventProcessor: PeriodicProcessor
  val permissionChecker: PermissionChecker
  val phoneNumberLibBindings: PhoneNumberLibBindings
  val platformContext: PlatformContext
  val platformInfoProvider: PlatformInfoProvider
  val secp256k1KeyGenerator: Secp256k1KeyGenerator
  val pushNotificationPermissionStatusProvider: PushNotificationPermissionStatusProvider
  val recoveryDao: RecoveryDao
  val secureStoreFactory: EncryptedKeyValueStoreFactory
  val appSessionManager: AppSessionManager
  val bitcoinFeeRateEstimator: BitcoinFeeRateEstimator
  val spendingWalletProvider: SpendingWalletProvider
  val debugOptionsService: DebugOptionsService
  val outgoingTransactionDetailDao: OutgoingTransactionDetailDao
  val uuidGenerator: UuidGenerator
  val onboardingAppKeyKeystore: OnboardingAppKeyKeystore
  val phoneNumberValidator: PhoneNumberValidator
  val recoverySyncFrequency: Duration
  val hardwareAttestation: HardwareAttestation
  val f8eAuthSignatureStatusProvider: F8eAuthSignatureStatusProvider
  val analyticsTrackingPreference: AnalyticsTrackingPreference
  val exchangeRateF8eClient: ExchangeRateF8eClient
  val softwareWalletIsEnabledFeatureFlag: SoftwareWalletIsEnabledFeatureFlag
  val promptSweepFeatureFlag: PromptSweepFeatureFlag
  val coachmarksGlobalFeatureFlag: CoachmarksGlobalFeatureFlag
  val bitcoinPriceChartFeatureFlag: BitcoinPriceChartFeatureFlag
  val biometricPreference: BiometricPreference
  val bitcoinPriceCardPreference: BitcoinPriceCardPreference
}

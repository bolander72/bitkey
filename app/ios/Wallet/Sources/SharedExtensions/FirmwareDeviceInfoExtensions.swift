import firmware
import Foundation
import Shared

extension Shared.FirmwareDeviceInfo {

    convenience init(coreDeviceInfo deviceInfo: firmware.DeviceInfo) {
        self.init(
            version: deviceInfo.version,
            serial: deviceInfo.serial,
            swType: deviceInfo.swType,
            hwRevision: deviceInfo.hwRevision,
            activeSlot: {
                switch deviceInfo.activeSlot {
                case .a: return .a
                case .b: return .b
                }
            }(),
            batteryCharge: Double(deviceInfo.batteryCharge),
            vCell: Int64(deviceInfo.vcell),
            avgCurrentMa: Int64(deviceInfo.avgCurrentMa),
            batteryCycles: Int64(deviceInfo.batteryCycles),
            secureBootConfig: {
                if deviceInfo.secureBootConfig == nil {
                    return SecureBootConfig.notSet
                } else {
                    switch deviceInfo.secureBootConfig.unsafelyUnwrapped {
                    case .dev: return .dev
                    case .prod: return .prod
                    }
                }
            }(),
            timeRetrieved: Int64(NSDate().timeIntervalSince1970),
            bioMatchStats: deviceInfo.bioMatchStats.map { bioMatchStats in
                Shared.BioMatchStats(
                    passCounts: bioMatchStats.passCounts.map { matchStat in
                        Shared.TemplateMatchStats_(
                            passCount: Int64(matchStat.passCount),
                            firmwareVersion: matchStat.firmwareVersion
                        )
                    },
                    failCount: Int64(bioMatchStats.failCount)
                )
            }
        )
    }

}

import Foundation
import CommonCrypto
import CryptoKit

enum DplsCrypto {
    static let pbkdf2Iterations: UInt32 = 10_000

    static func deriveVerifier(password: String, salt: Data) -> Data {
        let passwordData = Data(password.utf8)
        var derived = Data(count: 32)
        let status = derived.withUnsafeMutableBytes { derivedPtr in
            passwordData.withUnsafeBytes { passwordPtr in
                salt.withUnsafeBytes { saltPtr in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordPtr.bindMemory(to: Int8.self).baseAddress,
                        passwordData.count,
                        saltPtr.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        pbkdf2Iterations,
                        derivedPtr.bindMemory(to: UInt8.self).baseAddress,
                        32
                    )
                }
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 failed: \(status)")
        return derived
    }

    static func hmacSHA256(key: Data, message: Data) -> Data {
        let symmetric = SymmetricKey(data: key)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: symmetric)
        return Data(mac)
    }

    static func randomBytes(_ count: Int) -> Data {
        var data = Data(count: count)
        let result = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        precondition(result == errSecSuccess)
        return data
    }
}

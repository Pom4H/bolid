import XCTest
import DplsCore

final class DplsCoreIntegrationTests: XCTestCase {
    func testSharedComposeEntryPointBuilds() {
        XCTAssertNotNil(IosAppKt.MainViewController())
    }
}

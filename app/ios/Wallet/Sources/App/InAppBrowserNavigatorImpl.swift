import SafariServices
import Shared
import UIKit

class InAppBrowserNavigatorImpl: NSObject, InAppBrowserNavigator, SFSafariViewControllerDelegate {

    // MARK: - Private Properties

    // We store a reference to the `appViewController` so we can display the in-app browser on top
    // when needed
    private let appViewController: UINavigationController

    // We store a reference to the `appViewController` so we can display the in-app browser on top
    // when needed
    private var onCloseBrowser: (() -> Void)? = nil

    // We store a reference to the `safariViewController` so we can dismiss it later when needed
    private var safariViewController: SFSafariViewController? = nil

    // MARK: - Life Cycle

    init(appViewController: UINavigationController) {
        self.appViewController = appViewController
    }

    /**
     * Will open a safari browser that is embedded within the app
     */
    func open(url: String, onClose: @escaping () -> Void) {
        guard let topViewController = appViewController.topViewController,
              topViewController.presentedViewController as? SFSafariViewController == nil
        else {
            return
        }
        self.onCloseBrowser = onClose
        let vc = SFSafariViewController(url: URL(string: url)!)
        self.safariViewController = vc
        vc.delegate = self

        if topViewController.presentedViewController != nil,
           topViewController.presentedViewController?.isBeingDismissed == false
        {
            topViewController.presentedViewController?.present(vc, animated: true)
        } else {
            topViewController.present(vc, animated: true)
        }
    }

    func close() {
        self.safariViewController?.dismiss(animated: false)
        self.onClose()
    }

    func onClose() {
        self.safariViewController = nil
        onCloseBrowser?()
        onCloseBrowser = nil
    }

    // MARK: - SFSafariViewControllerDelegate

    func safariViewControllerDidFinish(_: SFSafariViewController) {
        onClose()
    }
}

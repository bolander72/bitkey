import UIKit

// TODO: [W-627] We will be working on a more declarative solution to the problem that this class solves.

/// A TEMPORARY solution for pushing/popping views in a UINavigationController,
/// when the pushes/pops are driven by model outputs from our shared state machines.
class StateChangeHandler: NSObject, UIAdaptivePresentationControllerDelegate {

    // MARK: - Private Types

    /// Will be handled in `handleNavigationAnimationCompletion`.
    enum QueuedAction: Equatable {
        /// A queued view controller to navigate to after any in progress navigation completes.
        case pushOrPop(vc: UIViewController, stateKey: String, animation: AnimationStyle?)

        /// Clears the stack
        case clearStack
    }

    // MARK: - Public Types

    public enum PresentationStyle {
        /// Presented modally. If `swipeToDismissCallback` is provided, we will allow swiping to
        /// dismiss, otherwise it will be prevented.
        case modal(swipeToDismissCallback: (() -> Void)?)

        /// Presented full screen,
        case fullScreen
    }

    enum AnimationStyle {
        /// Standard push / pop navigation animations
        case pushPop

        /// Cross-dissolve animation
        case fade
    }

    // MARK: - Public Properties

    // TODO: [W-970] See if there is a better way to track the current Model/ State coming from the State Machine
    /// The key for the current view controller at the top of the stack (or becoming the top of the
    /// stack).
    /// Corresponds to `currentViewController`.
    /// Set immediately so that `AppUiStateMachineManager` can apply model updates to it even when
    /// it is still being animated in.
    public var currentScreenModelKey: String? = nil

    /// The current view controller either in the process of being pushed onto the stack, or already
    /// at the top of the stack.
    /// Corresponds to `currentScreenModelKey`.
    /// Set immediately so that `AppUiStateMachineManager` can apply model updates to it even when
    /// it is still being animated in.
    public var currentViewController: UIViewController?

    /// The navigation view controller outside consumers can use to present and dismiss
    /// Separate from `navController` to keep `UINavigationController` capabilities private.
    public var navViewController: UIViewController {
        return navController
    }

    // MARK: - Private Properties

    /// The state of the animation of this navigation controller.
    /// Used to guard against animating view controllers in too quick of succession.
    private enum NavigationAnimationState {
        /// We are not currently in the process of performing any navigation animation
        case none

        /// We are currently animating a view controller via push or pop.
        /// If a new view controller asks to be shown via `pushOrPop` while we are in this state, we
        /// will assign it to
        /// `queuedViewControllerToNavigateTo` and handle once the current animation completes.
        case animating
    }

    /// Tracks the animation of the current view controller being pushed or popped to.
    /// Set in `pushOrPop` and cleared in `handleNavigationAnimationCompletion`
    private var currentAnimationState: NavigationAnimationState = .none

    /// Will be handled in `handleNavigationAnimationCompletion`.
    private var queuedActions = [QueuedAction]()

    private let navController: UINavigationController

    /// Maintains a mapping of model keys to view controllers to determine if we should be popping
    /// back to them.
    /// A state machine's model key should remain stable for views within a flow. So if a state
    /// machine emits a model
    /// such as:
    ///
    /// • "looking-up-cloud-account"
    /// • "found-no-cloud-account"
    /// • "looking-up-cloud-account"
    ///
    /// Then this represents 2 pushes to the stack followed by a pop back to the first vc pushed.
    private var viewControllersForState = [String: UIViewController]()

    /// The swipe to dismiss callback for the navigation stack managed by this `StateChangeHandler`
    /// to call when it detects a swipe to dismiss
    /// This is needed to keep the internal state in the KMP state machines accurate when the
    /// dismissal happens
    private let swipeToDismissCallback: (() -> Void)?

    // MARK: - Life Cycle

    public init(
        rootViewController: (vc: UIViewController, key: String),
        presentationStyle: PresentationStyle
    ) {
        self
            .navController = HiddenBarNavigationController(
                rootViewController: rootViewController
                    .vc
            )
        viewControllersForState[rootViewController.key] = rootViewController.vc
        currentScreenModelKey = rootViewController.key
        currentViewController = rootViewController.vc

        switch presentationStyle {
        case let .modal(swipeToDismissCallback):
            self.swipeToDismissCallback = swipeToDismissCallback
            if swipeToDismissCallback == nil {
                // This prevents swipe down to dismiss
                navController.isModalInPresentation = true
            }

        case .fullScreen:
            self.swipeToDismissCallback = nil
            if #available(iOS 16.4, *) {
                // For some reason, in iOS 16.4, using `fullScreen` presentation style shifts the
                // underlying view up
                navController.modalPresentationStyle = .overFullScreen
            } else {
                navController.modalPresentationStyle = .fullScreen
                navController.transitioningDelegate = ModalFlowTransitioningDelegate.default
            }
        }

        super.init()

        if swipeToDismissCallback != nil {
            navController.presentationController?.delegate = self
        }
    }

    public init(
        navController: UINavigationController
    ) {
        self.navController = navController
        self.swipeToDismissCallback = nil

        super.init()
    }

    // MARK: - UIAdaptivePresentationControllerDelegate

    func presentationControllerDidDismiss(_: UIPresentationController) {
        swipeToDismissCallback?()
    }

    // MARK: - Public Methods

    /// Attempts to pop to the view controller at the given key, otherwise pushes if that key hasn't
    /// been seen yet.
    /// If the key is the current key, does nothing
    /// - Parameter stateKey: The state the view controller represents.
    public func pushOrPopTo(
        vc newViewController: UIViewController,
        forStateKey stateKey: String,
        animation: AnimationStyle?
    ) {
        guard currentScreenModelKey != stateKey else {
            log(.error) { "Attempted to push or pop to the current key \(stateKey)" }
            return
        }

        // Queue the view controller if there is currently one be animated to.
        guard currentAnimationState == .none else {
            queuedActions.append(.pushOrPop(
                vc: newViewController,
                stateKey: stateKey,
                animation: animation
            ))
            return
        }

        // We're able to handle it, make sure to clear the queue
        queuedActions.removeAll(where: { $0 == .pushOrPop(
            vc: newViewController,
            stateKey: stateKey,
            animation: animation
        ) })

        currentScreenModelKey = stateKey
        currentViewController = newViewController

        // An animation is in progress if the transition is animated
        currentAnimationState = animation == .none ? .none : .animating

        if !popToState(
            viewController: newViewController,
            stateKey: stateKey,
            animated: animation == .pushPop
        ) {
            let fadeAnimationKey = "fadeAnimationKey"
            if animation == .fade {
                let transition = CATransition()
                transition.duration = 0.3
                transition.type = CATransitionType.fade
                navController.view.layer.add(transition, forKey: fadeAnimationKey)
            } else {
                navController.view.layer.removeAnimation(forKey: fadeAnimationKey)
            }
            navController.pushViewController(
                newViewController,
                animated: animation == .pushPop,
                completion: handleNavigationAnimationCompletion
            )
        }

        viewControllersForState[stateKey] = newViewController
    }

    /// Clears the back stack, setting the current view controller as the root
    public func clearBackStack() {
        guard currentAnimationState == .none else {
            if !queuedActions.contains(where: { $0 == .clearStack }) {
                queuedActions.append(.clearStack)
            }
            return
        }

        guard let currentScreenModelKey,
              let currentViewController
        else {
            return
        }
        viewControllersForState = [currentScreenModelKey: currentViewController]
        navController.setViewControllers([currentViewController], animated: false)
        queuedActions.removeAll(where: { $0 == .clearStack })
    }

    // MARK: - Private Methods

    private func handleNavigationAnimationCompletion() {
        currentAnimationState = .none
        // Always clear the queue prior to attempting to run the queued actions.
        // Any action that cannot be completed due to an animation will be requeued.
        // This prevent a pushOrPop getting stuck in the queue and shown later,
        // which would get the view model and navigation state out of sync.
        // See [W-6300] for an example of this.
        let queueToDrain = dedupeQueue(queueToDrain: queuedActions)
        queuedActions = []

        for queuedAction in queueToDrain {
            switch queuedAction {
            case let .pushOrPop(vc, stateKey, animation):
                pushOrPopTo(vc: vc, forStateKey: stateKey, animation: animation)
            case .clearStack:
                clearBackStack()
            }
        }
    }

    /// Dedupes enqueued .pushOrPop actions by their stateKey, choosing the last enqueued action as
    /// it is the most up to date.
    /// Without this, screen models that are rapidly updated during an animation may enqueue
    /// multiple actions, but only
    /// the earliest screen would be rendered, thus not showing the actual desired end state.
    /// W-9908.
    func dedupeQueue(queueToDrain: [QueuedAction]) -> [QueuedAction] {
        // Dictionary to track the most recent `stateKey` for `.pushOrPop` cases
        var seenStateKeys: [String: QueuedAction] = [:]

        // Reverse iteration to dedupe (keeping the most recent occurrence)
        return queueToDrain.reversed().filter { action in
            switch action {
            case let .pushOrPop(_, stateKey, _):
                if seenStateKeys[stateKey] == nil {
                    // If the stateKey hasn't been seen, store this action and include it in the
                    // result
                    seenStateKeys[stateKey] = action
                    return true
                }
                // If we've already seen this stateKey, skip this action (duplicate)
                return false
            default:
                // For none `.pushOrPop` actions, just include them in the result
                return true
            }
        }.reversed() // Reverse back to original order
    }

    /// If a view controller for the given state already exists in the stack, it pops to that view
    /// controller.
    /// And replaces if with the given `viewController`.
    /// Returns whether or not it popped.
    /// - Parameter viewController: The current viewController that was provided for that stateKey
    /// - Parameter stateKey: The state you wish to look up a view controller for.
    /// - Returns: Whether or not we found a view controller for the given state and popped to it.
    private func popToState(
        viewController: UIViewController,
        stateKey: String,
        animated: Bool
    ) -> Bool {
        guard let oldViewController = viewControllersForState[stateKey] else {
            // no view controller found for `stateKey`
            return false
        }

        guard replaceViewControllerOnStack(
            oldViewController: oldViewController,
            newViewController: viewController,
            stateKey: stateKey
        ) else {
            // viewControllers were not equal,
            // but no view controller matching `vc` found in navigation stack
            return false
        }

        let poppedViewControllers = navController.popToViewController(
            viewController,
            animated: animated,
            completion: handleNavigationAnimationCompletion
        )
        guard let poppedControllers = poppedViewControllers else {
            // Nothing was popped. This is unexpected given that the view controller existed in
            // `viewControllersForState`.
            // Undo the view controller replacement.
            _ = replaceViewControllerOnStack(
                oldViewController: viewController,
                newViewController: oldViewController,
                stateKey: stateKey
            )
            return false
        }

        // remove the view controllers that were popped:
        for (key, value) in viewControllersForState where poppedControllers.contains(value) {
            viewControllersForState.removeValue(forKey: key)
        }

        return true
    }

    /// Replaces the `oldViewController` with the `newViewController` with the same stateKey,
    /// this is because the model may have changed so although we still want the popping animation
    /// we want a
    /// different underlying ViewController
    /// - Parameter oldViewController: The state you wish to look up a view controller for.
    /// - Returns: Whether or not we found a view controller for the given state and popped to it.
    private func replaceViewControllerOnStack(
        oldViewController: UIViewController,
        newViewController: UIViewController,
        stateKey _: String
    ) -> Bool {
        guard let index = navController.viewControllers.lastIndex(of: oldViewController) else {
            return false
        }
        navController.viewControllers[index] = newViewController
        return true
    }

}

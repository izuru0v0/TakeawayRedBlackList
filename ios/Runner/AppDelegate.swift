import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let channel = FlutterMethodChannel(
      name: "takeaway_board",
      binaryMessenger: engineBridge.applicationRegistrar.messenger()
    )
    channel.setMethodCallHandler { call, result in
      switch call.method {
      case "load":
        result(UserDefaults.standard.string(forKey: "reviews") ?? "[]")
      case "save":
        guard let reviews = call.arguments as? String else {
          result(FlutterError(code: "invalid", message: "Missing reviews", details: nil))
          return
        }
        UserDefaults.standard.set(reviews, forKey: "reviews")
        result(nil)
      case "share":
        guard let text = call.arguments as? String,
              let window = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows }).first(where: { $0.isKeyWindow }),
              var presenter = window.rootViewController else {
          result(FlutterError(code: "share", message: "No active window", details: nil))
          return
        }
        while let presented = presenter.presentedViewController { presenter = presented }
        let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = presenter.view
        sheet.popoverPresentationController?.sourceRect = CGRect(
          x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 1, height: 1
        )
        presenter.present(sheet, animated: true)
        result(nil)
      default:
        result(FlutterMethodNotImplemented)
      }
    }
  }
}

import SwiftUI
import SmokeShared

@main
struct SmokeApp: App {
    @State private var log = "starting...\n"

    var body: some Scene {
        WindowGroup {
            ScrollView {
                Text(log)
                    .font(.system(size: 13, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .onAppear {
                SmokeDriver().run { line in
                    print("SMOKE: \(line)")
                    log += line + "\n"
                }
            }
        }
    }
}

# audio-stream-player

Low-latency raw PCM streaming audio player for Kotlin Multiplatform
(Android, iOS, macOS).

Designed for feeding audio as it arrives from a TTS or realtime voice API
(OpenAI, ElevenLabs, Cartesia, Google, ...): feed arbitrary-length PCM chunks,
playback starts immediately and plays gaplessly.

This is the Kotlin Multiplatform sibling of the
[`audio_stream_player`](https://pub.dev/packages/audio_stream_player) Flutter
package and tracks its core behavior. The native engines are the same:
`AudioTrack` in `MODE_STREAM` on Android, `AVAudioEngine` +
`AVAudioPlayerNode` on Apple platforms.

## Installation

Available on Maven Central. Add `mavenCentral()` to your repositories, then:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.adrianczuczka:audio-stream-player:0.1.0")
        }
    }
}
```

With a version catalog:

```toml
# gradle/libs.versions.toml
[libraries]
audio-stream-player = { module = "com.adrianczuczka:audio-stream-player", version = "0.1.0" }
```

- In a plain Android project the same coordinate resolves to the Android
  variant through Gradle module metadata; no `-android` suffix needed.
- `kotlinx-coroutines-core` is an `api` dependency, so `Flow` and `StateFlow`
  are available without declaring it yourself.

## Usage

```kotlin
val player = AudioStreamPlayer(sampleRate = 24000)
player.play()
ttsResponse.collect { chunk -> player.feed(chunk) }
player.endOfStream() // suspends until the last sample has played
player.dispose()
```

- Chunks may be any length – frame alignment across chunk boundaries is
  handled internally.
- `sampleRate`/`channels`/`format` describe the data you feed, not the device;
  resampling is handled natively.
- Buffering is unbounded; use `bufferedDuration()` for backpressure if the
  source can outrun playback indefinitely.

### Observing playback

```kotlin
player.state    // StateFlow<PlayerState>: IDLE, PLAYING, PAUSED
player.events   // SharedFlow<AudioStreamPlayerEvent>: Underrun, Error
```

An underrun (buffer ran dry mid-stream) keeps the player in `PLAYING`;
playback resumes automatically when more data is fed.

### iOS audio session

By default the player configures the audio session for playback (category
`playback`, mode `spokenAudio`) and activates it on `play()`. Pass
`configureAudioSession = false` to manage the session yourself.

## Supported targets

`android` (minSdk 23), `iosArm64`, `iosSimulatorArm64`, `iosX64`,
`macosArm64`, `macosX64`.

## License

MIT

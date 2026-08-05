# Yohan VPN

A secure SSH-based VPN tunnel application for Android with OpenSSH key support.

## Features

- **SSH Connection**: Real SSH connection using JSch library
- **OpenSSH Key Support**: Full support for OpenSSH private keys (BEGIN OPENSSH PRIVATE KEY)
- **HTTP Payload**: Send custom HTTP payload before establishing tunnel
- **SSH Tunnel**: Dynamic port forwarding (SOCKS proxy) through SSH
- **VPN Service**: Android system-level VPN tunnel
- **Auto Reconnect**: Automatic reconnection with exponential backoff
- **Material Design 3**: Modern UI following Material You design
- **Real-time Logs**: Live connection logging

## Requirements

- Android 7.0+ (API 24+)
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17

## Tech Stack

- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Gradle**: 8.2
- **AGP**: 8.2.0
- **Kotlin**: 1.9.20
- **UI**: Material Design 3
- **SSH**: JSch (Java Secure Channel)
- **Crypto**: BouncyCastle

## Project Structure

```
YohanVPN/
├── app/
│   ├── src/main/java/com/yohan/vpn/
│   │   ├── MainActivity.kt              # Main UI
│   │   ├── YohanVpnApplication.kt       # Application class
│   │   ├── ssh/
│   │   │   ├── SshManager.kt            # SSH connection manager
│   │   │   └── SshTunnel.kt             # SOCKS proxy tunnel
│   │   ├── service/
│   │   │   ├── YohanVpnService.kt       # Android VPN service
│   │   │   └── SshConnectionService.kt  # SSH foreground service
│   │   └── utils/
│   │       ├── Constants.kt             # App constants
│   │       ├── Logger.kt                # Logging system
│   │       └── ConnectionState.kt       # Connection states
│   └── src/main/res/                    # UI resources
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle
├── settings.gradle
└── gradlew / gradlew.bat
```

## Building

### Local Build

```bash
# Clone the repository
git clone <repository-url>
cd YohanVPN

# Generate Gradle Wrapper (if not present)
gradle wrapper --gradle-version 8.2

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

### Using Android Studio

1. Open the project in Android Studio
2. Sync project with Gradle files
3. Build → Make Project (Ctrl+F9)
4. Run → Run 'app' (Shift+F10)

### GitHub Actions

The project includes a GitHub Actions workflow that automatically builds the APK on every push. The workflow:

1. Sets up JDK 17
2. Sets up Android SDK
3. Generates Gradle Wrapper
4. Builds both Debug and Release APKs
5. Uploads APKs as artifacts

## Configuration

The app requires the following connection parameters:

- **Host**: SSH server hostname or IP address
- **Port**: SSH server port (default: 22)
- **Username**: SSH username
- **Private Key**: OpenSSH format private key (BEGIN OPENSSH PRIVATE KEY...END OPENSSH PRIVATE KEY)
- **Payload**: Optional HTTP payload to send before tunnel establishment

## Permissions

The app requires the following permissions:

- `INTERNET` - For SSH and VPN connections
- `ACCESS_NETWORK_STATE` - Network state monitoring
- `ACCESS_WIFI_STATE` - WiFi state information
- `FOREGROUND_SERVICE` - For persistent VPN service
- `POST_NOTIFICATIONS` - For service notifications (Android 13+)
- `WAKE_LOCK` - To keep connection alive
- `BIND_VPN_SERVICE` - For Android VPN service

## License

Copyright 2024 Yohan VPN. All rights reserved.

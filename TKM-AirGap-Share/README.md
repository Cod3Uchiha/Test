# TKM AirGap Share 2

An Android-to-Android optical file transfer app. It moves files through rapidly changing QR frames using only the sender's screen and the receiver's camera.

## Version 2 improvements

- 2,048-byte payload chunks instead of 650-byte chunks
- Turbo mode with a 65 ms frame interval
- Reliable mode for difficult cameras or lighting
- Disk-backed sender and receiver pipelines instead of keeping the entire transfer in RAM
- Random-access chunk storage, so missed QR frames are recovered during the next loop
- CRC32 validation for every chunk and SHA-256 verification for the finished file
- Streaming GZIP for compressible text-based files
- Maximum original file size increased from 25 MB to 512 MB

## Privacy model

The Android manifest contains no Internet, Wi-Fi, Bluetooth, nearby-device, location, contacts, or account permissions. Camera permission is used only by the receiving screen.

## Practical performance

Turbo mode has an ideal optical payload rate of roughly 30 KB/s before camera misses and QR generation overhead. Real speed depends heavily on camera focus, screen brightness, distance, refresh rate, and lighting. Large files are supported without memory crashes, but transferring hundreds of megabytes optically can still take hours.

For best results, maximize sender brightness, keep both phones steady, fill most of the receiver preview with the QR square, and switch to Reliable mode if the capture rate is unstable.

## Build

```bash
gradle assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

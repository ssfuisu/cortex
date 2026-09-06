# Cortex Terminal Proguard Rules

-keepclassmembers class * {
    native <methods>;
}

-keep class org.cortex.terminal.pty.** { *; }
-keep class org.cortex.terminal.emulator.** { *; }

# Cortex ProGuard Rules

# Keep native methods and class structure for PtyNative JNI bindings
-keep class org.cortex.terminal.pty.PtyNative {
    native <methods>;
    *;
}

# Keep DrawerLayout internal fields accessed via reflection in MainActivity
-keepclassmembers class androidx.drawerlayout.widget.DrawerLayout {
    private androidx.customview.widget.ViewDragHelper mLeftDragger;
}

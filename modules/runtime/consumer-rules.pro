# Native methods are bound by their JNI names; Entry point is referenced by instrumented 'activity-compose'
-keep class org.jetbrains.compose.reload.android.JvmtiAgent { native <methods>; }
-keep class org.jetbrains.compose.reload.android.AndroidDevelopmentEntryPoint { *; }

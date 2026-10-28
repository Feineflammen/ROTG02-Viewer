# JNI entry points and callback names are looked up by native code.
# They must not be renamed or removed by R8.
-keep class de.feineflammen.rotg02viewer.NativeUvcStream { *; }
-keep class de.feineflammen.rotg02viewer.FrameSink { *; }
-keepclassmembers class de.feineflammen.rotg02viewer.FrameSink {
    public void onFrame(byte[], long);
}

# RAOfflineProxy

-keepattributes *Annotation*
-keep class com.raofflineproxy.data.** { *; }

# RomDataSource methods are looked up by name from JNI (rchash_jni.cpp).
# Without these rules R8 renames them, causing GetMethodID to fail.
-keep interface com.raofflineproxy.proxy.hash.RomDataSource {
    public abstract long getLength();
    public abstract int read(long, byte[], int);
}
-keepclassmembers class * implements com.raofflineproxy.proxy.hash.RomDataSource {
    public long getLength();
    public int read(long, byte[], int);
}

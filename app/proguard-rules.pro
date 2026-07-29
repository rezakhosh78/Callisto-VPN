-keep class com.rkh.callisto.core.NativeCore { *; }

# Psiphon's gomobile bindings use JNI names generated from these exact Java
# packages. They must not be renamed or removed in a minified Release build.
-keep class ca.psiphon.** { *; }
-keep interface ca.psiphon.** { *; }
-keep class psi.** { *; }
-keep interface psi.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }

# Snowflake/IPtProxy and the embedded Tor service are also discovered across
# process boundaries and must retain their runtime names.
-keep class IPtProxy.** { *; }
-keep interface IPtProxy.** { *; }
-keep class hN.** { *; }
-keep interface hN.** { *; }
-keep class org.torproject.jni.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

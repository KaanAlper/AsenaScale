# gomobile bindings are called reflectively from Go.
-keep class go.** { *; }
-keep class tsbridge.** { *; }
# JSch loads its crypto implementations by class name.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.**

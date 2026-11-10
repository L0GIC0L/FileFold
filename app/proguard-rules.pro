# Apache Commons Compress looks up optional codecs reflectively; the ones we do
# not ship (Brotli, Zstandard, ASM for Pack200) are simply absent at runtime.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn org.slf4j.**
-keep class org.tukaani.xz.** { *; }

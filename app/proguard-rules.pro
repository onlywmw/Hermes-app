# P2-16: ProGuard / R8 keep rules

# JS 桥方法 — 被 WebView 反射调用, 混淆后壳白屏
-keep class com.hermes.android.HermesActivity$HermesBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Capability 接口实现 — 通过 ServiceLoader / 反射加载
-keep class com.hermes.android.capability.** { *; }

# WorkManager Worker — 反射实例化
-keep class com.hermes.android.cron.** { *; }

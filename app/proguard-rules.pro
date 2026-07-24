# P2-16: ProGuard / R8 keep rules

# JS 桥方法 — 被 WebView 反射调用, 混淆后壳白屏
# (@JavascriptInterface 注解集中在 BridgeFactory, 委托 bridge 包各子模块)
-keep class com.hermes.android.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}

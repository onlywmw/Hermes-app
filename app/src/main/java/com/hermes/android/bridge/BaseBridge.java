package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;

/**
 * Bridge 基类 — 持有 Activity 引用，子类通过它访问共享资源。
 */
public abstract class BaseBridge {
    protected final HermesActivity activity;

    public BaseBridge(HermesActivity activity) {
        this.activity = activity;
    }

    /** 在主线程安全执行 JS 回调 */
    protected void evalJs(String script) {
        activity.evalJsPublic(script);
    }
}

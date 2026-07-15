package com.zaid.geode144shizuku;

interface IGeode144Service {
    void destroy() = 16777114;
    void exit() = 1;
    String startMonitor() = 2;
    String stopAndRestore() = 3;
    String diagnose() = 4;
    boolean isMonitoring() = 5;
    String getLastStatus() = 6;
}

package io.panggung.obs.privilege;

interface IPanggungShizukuService {
    void destroy() = 16777114;
    int getUid() = 1;
    byte[] captureScreenRaw() = 2;
    boolean launchAppOnDisplay(String componentName, int displayId) = 3;
    boolean injectTap(float x, float y) = 4;
    boolean injectKeyEvent(int keyCode) = 5;
    boolean hasRemoteSubmixPermission() = 6;
    String execShell(String cmd) = 7;
}

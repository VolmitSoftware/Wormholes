package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.NativeMenuInput;

interface SeamlessClient extends TestClient {
    void waitForChunksDownload();

    void waitForChunksRender();

    void holdForward();

    void releaseForward();

    void holdForwardFor(int ticks);

    void holdJump();

    void releaseJump();

    void lookAt(float yaw, float pitch);

    void moveCursor(double deltaX, double deltaY);

    void resizeWindow(int width, int height);

    void restoreDefaultGameOptions();

    TickStepper lockstep();

    NativeMenuInput menuInput();
}

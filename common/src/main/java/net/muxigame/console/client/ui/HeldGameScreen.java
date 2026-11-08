package net.muxigame.console.client.ui;

/** Compatibility name for the model fullscreen presentation; there is no separate focus mode. */
@Deprecated
public final class HeldGameScreen extends GameScreen {
    public HeldGameScreen(ClientActions actions,int port){super(actions,port);}
}

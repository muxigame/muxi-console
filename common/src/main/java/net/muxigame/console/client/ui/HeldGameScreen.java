package net.muxigame.console.client.ui;

/** Explicit keyboard capture keeps the actual item and world visible; no second running session. */
public final class HeldGameScreen extends GameScreen {
    public HeldGameScreen(ClientActions actions,int port){super(actions,port,true);}
    @Override protected String worldLabel(){return "手持视角";}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==1&&minecraft!=null){minecraft.setScreen(new GameScreen(actions,port()));return true;}
        return true;
    }
}

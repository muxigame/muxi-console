package net.muxigame.console.domain;

/** Native-file links use alphabetic MC operation namespaces, independently of native worker opcodes. */
public final class LinkMessages {
    public static String read(boolean threeDs){return threeDs?"link_threeds_read":"link_ds_read";}
    public static String reply(boolean threeDs){return threeDs?"link_threeds_reply":"link_ds_reply";}
    private LinkMessages(){}
}

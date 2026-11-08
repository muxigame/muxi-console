package net.muxigame.console.client.transfer;

import java.util.Objects;
import java.util.UUID;

/** Thread-safe observations of real transfer work; stages without measurable work have no percentage. */
public final class LoadProgress {
    public enum Stage { PREPARING,CACHE_CHECK,DOWNLOADING,VERIFYING,STARTING,RESTORING,WAITING,SHARED,COMPLETE,FAILED,CANCELLED }
    public record Snapshot(UUID card,String stage,long bytes,long total,int percent,String text,boolean loading,String error){}
    private final UUID card;
    private Stage stage=Stage.PREPARING;
    private long bytes,total;
    private String error="";
    public LoadProgress(UUID card){this.card=Objects.requireNonNull(card);}
    private boolean terminal(){return stage==Stage.COMPLETE||stage==Stage.FAILED||stage==Stage.CANCELLED;}
    public synchronized void begin(Stage next,long total){
        if(total<0||next==Stage.FAILED)throw new IllegalArgumentException("Invalid loading stage");
        if(terminal())return;stage=Objects.requireNonNull(next);bytes=0;this.total=total;
    }
    public synchronized void advance(long count){
        if(terminal())return;if(count<0||total<=0||count>total-bytes)throw new IllegalArgumentException("Loading byte count exceeds declared work");bytes+=count;
    }
    public synchronized void fail(String message){if(terminal())return;stage=Stage.FAILED;error=Objects.requireNonNullElse(message,"无法加载此卡带");}
    public synchronized void cancel(){if(!terminal())stage=Stage.CANCELLED;}
    public synchronized Snapshot snapshot(){
        int percent=total>0&&!terminal()?(bytes==total?100:(int)Math.min(99,(double)bytes/total*100)):-1;
        String label=switch(stage){
            case PREPARING->"正在准备卡带";case CACHE_CHECK->"正在检查缓存";case DOWNLOADING->"正在加载卡带";
            case VERIFYING->"正在校验卡带";case STARTING->"正在启动游戏";case RESTORING->"正在恢复存档";
            case WAITING->"正在等待控制器";case SHARED->"正在连接主机画面";
            case COMPLETE->"";case FAILED->"加载失败："+error;case CANCELLED->"已取消加载";
        };
        String text=terminal()?label:label+(percent<0?"…":" "+percent+"%");
        return new Snapshot(card,stage.name().toLowerCase(java.util.Locale.ROOT),bytes,total,percent,text,!terminal(),error);
    }
}

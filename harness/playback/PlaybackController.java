package com.xuncorp.voxzen.service;
import spw.harness.playback.PlaybackBridgeProbe.Item;

/** Isolated controller fixture. Never loaded into the actual player. */
public final class PlaybackController {
    public static final PlaybackController INSTANCE=new PlaybackController();
    public int queue,select,prepare,play,toggle; public boolean failQueue,failSelect;
    public void setPlaybackQueue(Item item){queue++;if(failQueue)throw new IllegalStateException("queue refused");}
    public void playMusicAt(int index,boolean play){select++;if(index!=0||!play)throw new AssertionError("unexpected selection");if(failSelect)throw new IllegalStateException("select refused");}
    public void play(){play++;}
    public void prepare(){prepare++;if(failSelect)throw new IllegalStateException("prepare refused");}
    public void playOrPause(){toggle++;}
    public void reset(){queue=select=prepare=play=toggle=0;failQueue=failSelect=false;}
}

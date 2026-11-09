package net.muxigame.console.tests.link;

import net.muxigame.console.domain.*;

/** The actual DS server window leaves margin below the client's eight-batch guard, including cold nonzero frames. */
public final class LinkDsFrameFlowTest {
    private static int checks;
    private static void check(boolean good,String label){checks++;if(!good)throw new AssertionError(label);}
    private static void denied(Runnable work,String label){try{work.run();throw new AssertionError(label);}catch(DomainException expected){checks++;}}
    public static void main(String[] args){
        var flow=new FrameFlowWindow(6,180000);flow.reset(3,1510,1000);
        check(flow.completedFrame()==1510&&flow.issuedFrame()==1510,"restored DNL1 starts from its true committed frame rather than zero");
        for(int i=0;i<6;i++)flow.issued(3,i,1510+(i+1)*3,1001+i);
        check(flow.outstanding()==6&&!flow.available(3),"at most six pending batches occupy the DS server window");check(flow.outstanding()<8,"client eight-batch overflow guard retains two slots of margin");
        check(LinkMedia.frameWithinWindow(1513,flow.issuedFrame(),1513),"eighteen issued frames do not reject the first real completed CPU picture/audio");
        check(!LinkMedia.frameWithinWindow(1513,flow.issuedFrame(),flow.issuedFrame()),"legacy sent-frame policy would discard that legal DS media, reproducing the regression");
        check(!LinkMedia.frameWithinWindow(1529,flow.issuedFrame(),1513),"unsent future media is still rejected");
        check(!LinkMedia.frameWithinWindow(1499,flow.issuedFrame(),1513),"media older than the real completion watermark is still bounded");
        check(LinkMedia.frameWithinWindow(1516,1528,1528)&&!LinkMedia.frameWithinWindow(1515,1528,1528),"GB/GBA retain their original twelve-frame media tolerance");
        denied(()->flow.issued(3,6,1531,1008),"blocked server cannot issue another hidden native frame batch");check(flow.issuedFrame()==1528&&flow.completedFrame()==1510,"a full window cannot advance the authoritative issued/completed counters");
        denied(()->flow.acknowledge(2,0,1513,1010),"an old epoch cannot release the live DS window");denied(()->flow.acknowledge(3,0,1514,1010),"a future or in-batch frame cannot masquerade as actual CPU completion");denied(()->flow.acknowledge(3,7,1534,1010),"an unsent batch has no acknowledgement authority");
        check(flow.acknowledge(3,0,1513,1011)&&flow.available(3),"one real completed CPU boundary permits subsequent issuance");check(!flow.acknowledge(3,0,1513,1012),"a duplicate receipt does not create extra capacity");
        flow.issued(3,6,1531,1013);check(flow.outstanding()==6,"the newly admitted work still stays within six pending batches");
        check(!flow.expired(3,181011),"a real acknowledged batch refreshes the no-progress deadline");check(flow.expired(3,181012),"a stuck host eventually reaches the bounded no-progress exit");
        flow.reset(4,9000,200000);check(flow.completedFrame()==9000&&flow.outstanding()==0,"cold reopening installs the new group barrier and discards old pending batches");denied(()->flow.acknowledge(3,6,1531,200001),"a late old-room receipt cannot change a fresh cold group");
        System.out.println("LinkDsFrameFlowTest "+checks+" checks passed");
    }
}

package net.muxigame.consoleqa;

import com.sun.jna.*;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** QA-owned Windows high-resolution deadline timer; never calls timeBeginPeriod. */
final class LatencyTimer implements AutoCloseable {
    private interface Kernel extends StdCallLibrary {
        Pointer CreateWaitableTimerExW(Pointer security,Pointer name,int flags,int access);
        Pointer CreateEventW(Pointer security,boolean manual,boolean initial,Pointer name);
        boolean SetEvent(Pointer event);
        boolean SetWaitableTimer(Pointer timer,LongByReference due,int period,Pointer callback,Pointer argument,boolean resume);
        int WaitForMultipleObjects(int count,Pointer[] handles,boolean all,int milliseconds);
        boolean CloseHandle(Pointer handle);
    }
    private record Job(long at,long order,Runnable action){}
    private final Kernel api=Native.load("kernel32",Kernel.class);private final Pointer timer,wake;private final Thread worker;
    private final PriorityQueue<Job> jobs=new PriorityQueue<>(Comparator.comparingLong(Job::at).thenComparingLong(Job::order));
    private long sequence;private volatile boolean closed,handlesClosed;private volatile String failure="";
    LatencyTimer()throws IOException{
        timer=api.CreateWaitableTimerExW(Pointer.NULL,Pointer.NULL,2,0x1F0003);if(timer==null)throw new IOException("QA high-resolution timer unavailable");
        wake=api.CreateEventW(Pointer.NULL,false,false,Pointer.NULL);if(wake==null){api.CloseHandle(timer);throw new IOException("QA timer wake event unavailable");}
        worker=new Thread(this::run,"console-qa-high-resolution-input");worker.setDaemon(true);worker.start();
    }
    synchronized void afterMillis(int milliseconds,Runnable action){
        if(closed||!failure.isEmpty()||milliseconds<0||milliseconds>2000||jobs.size()>=64)throw new IllegalStateException("QA timer schedule unavailable");
        jobs.add(new Job(System.nanoTime()+milliseconds*1_000_000L,sequence++,action));if(!api.SetEvent(wake))throw new IllegalStateException("QA timer wake failed");
    }
    private void run(){try{
        while(!closed){Job job; synchronized(this){job=jobs.peek();if(job!=null&&job.at<=System.nanoTime())jobs.remove();else job=null;}
            if(job!=null){job.action.run();continue;}
            long wait; synchronized(this){wait=jobs.isEmpty()?1_000_000_000L:Math.max(100,jobs.peek().at-System.nanoTime());}
            if(!api.SetWaitableTimer(timer,new LongByReference(-Math.max(1,wait/100)),0,Pointer.NULL,Pointer.NULL,false))throw new IOException("QA timer setup failed");
            int value=api.WaitForMultipleObjects(2,new Pointer[]{timer,wake},false,2000);if(value!=0&&value!=1)throw new IOException("QA timer wait failed");
        }
    }catch(Throwable error){failure=error.getClass().getSimpleName();}
    finally{boolean a=api.CloseHandle(timer),b=api.CloseHandle(wake);handlesClosed=a&&b;}}
    String failure(){return failure;}
    public void close()throws IOException{closed=true;api.SetEvent(wake);try{worker.join(3000);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("QA timer close interrupted",error);}if(worker.isAlive()||!handlesClosed)throw new IOException("QA timer handles did not close");synchronized(this){jobs.clear();}}
    public static void main(String[] args)throws Exception{
        long began=System.nanoTime();CountDownLatch done=new CountDownLatch(2);java.util.List<Integer> order=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try(var timer=new LatencyTimer()){timer.afterMillis(50,()->{order.add(2);done.countDown();});timer.afterMillis(5,()->{order.add(1);done.countDown();});if(!done.await(2,TimeUnit.SECONDS)||!order.equals(java.util.List.of(1,2))||!timer.failure().isEmpty())throw new AssertionError("High-resolution scheduling must preserve newly earlier deadlines");}
        System.out.println("{\"checks\":3,\"ownedHandlesClosed\":true,\"systemTimerPeriodChanged\":false,\"elapsedMillis\":"+(System.nanoTime()-began)/1e6+"}");
    }
}

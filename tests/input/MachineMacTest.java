import net.muxigame.console.client.RuntimeFiles;
import java.util.*;
public final class MachineMacTest{
 public static void main(String[]args){Set<String> observed=new HashSet<>();int checks=0;
  for(int i=0;i<16;i++){UUID machine=new UUID(1234567,i);String mac=RuntimeFiles.machineMac(machine);if(!mac.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}"))throw new AssertionError("Invalid core MAC literal");checks++;if(!mac.equals(RuntimeFiles.machineMac(UUID.fromString(machine.toString()))))throw new AssertionError("Machine MAC changed after identity roundtrip");checks++;int first=Integer.parseInt(mac.substring(0,2),16);if((first&3)!=2)throw new AssertionError("MAC must be locally-administered unicast");checks++;if(!observed.add(mac))throw new AssertionError("Distinct machine collision in deterministic test set");checks++;}
  System.out.println("MachineMacTest: "+checks+" checks passed");
 }
}

package net.muxigame.console.domain;

import java.io.IOException;
import java.util.Objects;

/** A 3DS CPU checkpoint may only accompany its own exact, still-current native filesystem generation. */
public final class Link3dsCheckpointBinding {
    public static boolean current(MachineStateVault states,MachineProfileVault profiles,MachineSaveKey key,
                                  String fingerprint,MachineStateVault.Snapshot state,MachineProfileVault.Snapshot profile)throws IOException {
        Objects.requireNonNull(states);Objects.requireNonNull(profiles);Objects.requireNonNull(key);
        fingerprint=Hashing.normalizeSha256(fingerprint);
        var profileKey=new MachineProfileVault.Key(key.machineUUID(),Platform.N3DS);
        if(state==null||profile==null||!state.key().equals(key)||!profile.key().equals(profileKey)
                ||!state.fingerprint().equals(fingerprint)||!profile.generation().equals(state.profileGeneration()))return false;
        var actualState=states.latest(key);var actualProfile=profiles.latest(profileKey);
        return actualState.isPresent()&&actualProfile.isPresent()&&actualState.get().equals(state)&&actualProfile.get().equals(profile);
    }
    private Link3dsCheckpointBinding(){}
}

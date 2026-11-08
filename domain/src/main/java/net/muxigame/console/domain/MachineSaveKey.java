package net.muxigame.console.domain;

import java.util.UUID;

/** A machine retains one independent progress history for each exact game content. */
public record MachineSaveKey(UUID machineUUID,String contentSHA) {
    public MachineSaveKey {
        if(machineUUID==null)throw new DomainException("save_identity","Machine identity is required");
        if(contentSHA==null||!contentSHA.matches("[a-f0-9]{64}"))throw new DomainException("save_hash","Expected a lowercase content SHA-256");
    }
}

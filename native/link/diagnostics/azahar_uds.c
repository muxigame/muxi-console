/* SPDX-License-Identifier: MIT
 * Original two-role homebrew UDS challenge diagnostic. No game or BIOS assets.
 * Room membership alone cannot turn the bottom screen green: a real opposite
 * role's challenge must arrive through the guest NWM UDS PullPacket API.
 */
#include <3ds.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef MUXI_UDS_ROLE
#define MUXI_UDS_ROLE 0
#endif

enum { COMM_ID = 0x4d584331, CHANNEL = 1 };
static const char passphrase[] = "original-uds-challenge";
static const u32 challenges[2] = {0x319ac76d, 0xb6502fe4};
typedef struct { u32 magic, role, sequence, challenge; } Message;
typedef struct {
    u32 magic, role, stage, frame, sent, received, peer_role, peer_sequence;
    u32 peer_challenge, result, local_node, total_nodes;
} Evidence;

static void fill_bottom(bool received) {
    u16 width, height;
    u8* pixels = gfxGetFramebuffer(GFX_BOTTOM, GFX_LEFT, &width, &height);
    for (unsigned i = 0; i < (unsigned)width * height; ++i) {
        pixels[i * 3] = MUXI_UDS_ROLE ? 192 : 32;
        pixels[i * 3 + 1] = received ? 224 : 24;
        pixels[i * 3 + 2] = received ? 24 : 208;
    }
}

static void evidence(const Evidence* value) {
    FILE* file = fopen("sdmc:/muxi-original-uds-evidence.bin", "wb");
    if (file) { fwrite(value, 1, sizeof(*value), file); fclose(file); }
}

int main(void) {
    gfxInitDefault();
    consoleInit(GFX_TOP, NULL);
    archiveMount(ARCHIVE_SDMC, fsMakePath(PATH_EMPTY, ""), "sdmc");
    Evidence value = {.magic = 0x55445331, .role = MUXI_UDS_ROLE, .peer_role = 0xffffffff};
    value.result = udsInit(0x3000, MUXI_UDS_ROLE ? "ConsoleB" : "ConsoleA");
    bool initialized = R_SUCCEEDED(value.result), connected = false;
    udsBindContext binding = {0};
    udsNetworkStruct network;
    void* scan = memalign(0x1000, 0x4000);
    u32 writes = 0, round_nonce = 0;
    value.stage = initialized ? 1 : 0;
    evidence(&value);
    while (aptMainLoop()) {
        hidScanInput();
        if (hidKeysDown() & KEY_START) break;
        // A real guest input changes the challenge only after a cold restore.
        // Previously queued packets cannot satisfy the new-round receive proof.
        if (hidKeysDown() & KEY_B) round_nonce ^= 0x6d39a7c2;
        if (hidKeysDown() & KEY_A) {
            const u32 record[3] = {0x52454c31, ++writes, MUXI_UDS_ROLE};
            FILE* file = fopen("sdmc:/muxi-original-relocation.bin", "wb");
            if (file) { fwrite(record, 1, sizeof(record), file); fclose(file); }
        }
        if (initialized && !connected && value.frame % 30 == 0) {
            if (MUXI_UDS_ROLE == 0) {
                udsGenerateDefaultNetworkStruct(&network, COMM_ID, 1, 2);
                network.channel = 11;
                value.result = udsCreateNetwork(&network, passphrase, sizeof(passphrase),
                    &binding, CHANNEL, UDS_DEFAULT_RECVBUFSIZE);
                connected = R_SUCCEEDED(value.result);
            } else if (scan) {
                udsNetworkScanInfo* networks = NULL;
                size_t count = 0;
                value.result = udsScanBeacons(scan, 0x4000, &networks, &count, COMM_ID, 1, NULL, false);
                if (R_SUCCEEDED(value.result) && count) {
                    value.result = udsConnectNetwork(&networks[0].network, passphrase,
                        sizeof(passphrase), &binding, UDS_HOST_NETWORKNODEID,
                        UDSCONTYPE_Client, CHANNEL, UDS_DEFAULT_RECVBUFSIZE);
                    connected = R_SUCCEEDED(value.result);
                }
                free(networks);
            }
            if (connected) value.stage = 2;
        }
        if (connected) {
            udsConnectionStatus status = {0};
            if (R_SUCCEEDED(udsGetConnectionStatus(&status))) {
                value.local_node = status.cur_NetworkNodeID;
                value.total_nodes = status.total_nodes;
            }
            if (value.frame % 12 == 0) {
                const Message message = {0x4d585544, MUXI_UDS_ROLE,
                    value.sent + 1, challenges[MUXI_UDS_ROLE] ^ round_nonce};
                Result result = udsSendTo(UDS_BROADCAST_NETWORKNODEID, CHANNEL,
                    UDS_SENDFLAG_Default, &message, sizeof(message));
                value.result = result;
                if (R_SUCCEEDED(result)) ++value.sent;
            }
            for (unsigned i = 0; i < 8; ++i) {
                Message message = {0};
                size_t size = 0;
                u16 sender = 0;
                Result result = udsPullPacket(&binding, &message, sizeof(message), &size, &sender);
                if (R_FAILED(result) || !size) break;
                if (size == sizeof(message) && message.magic == 0x4d585544 &&
                    message.role == 1 - MUXI_UDS_ROLE &&
                    message.challenge == (challenges[1 - MUXI_UDS_ROLE] ^ round_nonce) && sender != value.local_node) {
                    ++value.received;
                    value.peer_role = message.role;
                    value.peer_sequence = message.sequence;
                    value.peer_challenge = message.challenge;
                    value.stage = 3;
                }
            }
        }
        fill_bottom(value.received != 0);
        if (value.frame % 30 == 0) {
            printf("\x1b[2J\x1b[HOriginal UDS role %lu\nStage %lu Frame %lu\nSent %lu Received %lu\nPeer %lu seq %lu\nNodes %lu id %lu\nResult %08lx\n",
                value.role, value.stage, value.frame, value.sent, value.received,
                value.peer_role, value.peer_sequence, value.total_nodes, value.local_node, value.result);
            evidence(&value);
        }
        gfxFlushBuffers(); gfxSwapBuffers(); gspWaitForVBlank(); ++value.frame;
    }
    evidence(&value);
    if (connected) {
        udsUnbind(&binding);
        if (MUXI_UDS_ROLE == 0) udsDestroyNetwork(); else udsDisconnectNetwork();
    }
    if (initialized) udsExit();
    free(scan); gfxExit(); return 0;
}

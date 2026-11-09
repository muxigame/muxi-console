/* Original cooperative GBA cable frontend, MIT. Linked upstream mGBA retains MPL-2.0. */
#include <mgba/core/core.h>
#include <mgba/core/lockstep.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/sio/lockstep.h>
#include <mgba/internal/gba/savedata.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/vfs.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif
struct Endpoint {
    struct mLockstepUser user;
    struct mCore* core;
    struct GBASIOLockstepDriver driver;
    unsigned index;
    int asleep;
    uint32_t video[240*160];
};
static struct Endpoint endpoints[2];
static struct GBASIOLockstepCoordinator coordinator;
static int opened;

static void sleep_endpoint(struct mLockstepUser* user) { ((struct Endpoint*)user)->asleep=1; }
static void wake_endpoint(struct mLockstepUser* user) { ((struct Endpoint*)user)->asleep=0; }
static int requested_id(struct mLockstepUser* user) { return ((struct Endpoint*)user)->index; }
EXPORT unsigned muxi_gba_link_abi(void) { return 1; }
EXPORT int muxi_gba_link_alive(void) { return opened&&endpoints[0].core&&endpoints[1].core; }
EXPORT void muxi_gba_link_close(void) {
    for(unsigned i=0;i<2;i++)if(endpoints[i].core){
        mCoreConfigDeinit(&endpoints[i].core->config);
        endpoints[i].core->deinit(endpoints[i].core);endpoints[i].core=NULL;
    }
    if(opened)GBASIOLockstepCoordinatorDeinit(&coordinator);
    opened=0;memset(endpoints,0,sizeof(endpoints));
}
EXPORT int muxi_gba_link_open(const char* left,const char* right) {
    if(opened)return 0;
    memset(endpoints,0,sizeof(endpoints));GBASIOLockstepCoordinatorInit(&coordinator);opened=1;
    const char* names[2]={left,right};
    for(unsigned i=0;i<2;i++){
        struct Endpoint* e=&endpoints[i];e->index=i;
        e->user.sleep=sleep_endpoint;e->user.wake=wake_endpoint;e->user.requestedId=requested_id;
        e->core=GBACoreCreate();if(!e->core||!e->core->init(e->core))goto failed;
        mCoreInitConfig(e->core,NULL);
        e->core->opts.skipBios=true;e->core->opts.useBios=false;e->core->opts.sampleRate=48000;e->core->opts.volume=0x100;
        e->core->opts.audioBuffers=4096;e->core->opts.videoSync=false;e->core->opts.audioSync=false;
        e->core->loadConfig(e->core,&e->core->config);
        e->core->setVideoBuffer(e->core,e->video,240);e->core->setAudioBufferSize(e->core,4096);
        struct VFile* rom=VFileOpen(names[i],O_RDONLY);if(!rom)goto failed;
        if(!e->core->loadROM(e->core,rom)){rom->close(rom);goto failed;}
        e->core->reset(e->core);
        GBASIOLockstepDriverCreate(&e->driver,&e->user);
        GBASIOLockstepCoordinatorAttach(&coordinator,&e->driver);
        struct GBA* gba=e->core->board;GBASIOSetDriver(&gba->sio,&e->driver.d);
    }
    return GBASIOLockstepCoordinatorAttached(&coordinator)==2;
failed:
    muxi_gba_link_close();return 0;
}
/* Keys use mGBA's hardware bit order, not libretro controller ids. */
EXPORT int muxi_gba_link_step(uint32_t left,uint32_t right) {
    if(!opened||!endpoints[0].core||!endpoints[1].core)return 0;
    uint32_t target[2];uint32_t keys[2]={left,right};
    for(unsigned i=0;i<2;i++){endpoints[i].core->setKeys(endpoints[i].core,keys[i]&0x3ff);target[i]=endpoints[i].core->frameCounter(endpoints[i].core)+1;}
    for(unsigned iterations=0;iterations<2000000;iterations++){
        int complete[2];for(unsigned i=0;i<2;i++)complete[i]=endpoints[i].core->frameCounter(endpoints[i].core)>=target[i];
        if(complete[0]&&complete[1])return 1;
        int progress=0;
        for(unsigned i=0;i<2;i++)if(!endpoints[i].asleep&&(!complete[i]||endpoints[1-i].asleep)){
            endpoints[i].core->runLoop(endpoints[i].core);progress=1;
        }
        if(!progress)return 0;
    }
    return 0;
}
EXPORT uint32_t muxi_gba_link_read(unsigned endpoint,uint32_t address) {
    return opened&&endpoint<2&&endpoints[endpoint].core?endpoints[endpoint].core->busRead32(endpoints[endpoint].core,address):0;
}
EXPORT unsigned muxi_gba_link_frame(unsigned endpoint) {
    return opened&&endpoint<2&&endpoints[endpoint].core?endpoints[endpoint].core->frameCounter(endpoints[endpoint].core):0;
}
EXPORT const uint32_t* muxi_gba_link_video(unsigned endpoint) { return opened&&endpoint<2?endpoints[endpoint].video:NULL; }
EXPORT unsigned muxi_gba_link_audio_rate(unsigned endpoint) { return opened&&endpoint<2?endpoints[endpoint].core->audioSampleRate(endpoints[endpoint].core):0; }
EXPORT unsigned muxi_gba_link_audio(unsigned endpoint,int16_t* output,unsigned capacity) {
    if(!opened||endpoint>1||!output||capacity<2)return 0;
    struct mAudioBuffer* buffer=endpoints[endpoint].core->getAudioBuffer(endpoints[endpoint].core);
    size_t count=mAudioBufferAvailable(buffer);if(count>capacity/2)count=capacity/2;
    return mAudioBufferRead(buffer,output,count)*2;
}
/* State exports use explicit big-endian lengths; they never contain host paths or pointer values. */
static void be32(uint8_t* p,uint32_t value){p[0]=value>>24;p[1]=value>>16;p[2]=value>>8;p[3]=value;}
static uint32_t read32(const uint8_t* p){return (uint32_t)p[0]<<24|(uint32_t)p[1]<<16|(uint32_t)p[2]<<8|p[3];}
static uint8_t* blob(unsigned endpoint,int linked,size_t* size){
    *size=0;if(!opened||endpoint>1||!endpoints[endpoint].core)return NULL;
    struct mCore* core=endpoints[endpoint].core;struct GBA* gba=core->board;
    struct GBASIODriver* saved=gba->sio.driver;
    // A single-machine export excludes link-only coordinator extensions without detaching or advancing either CPU.
    if(!linked)gba->sio.driver=NULL;
    // MXS1 already carries authoritative SRAM separately. The upstream libretro
    // loader masks any duplicate EXTDATA_SAVEDATA into a private copy while its
    // public SRAM pointer remains the old backing, so single exports omit that
    // duplicate. Full link groups retain it because they have no external SRAM.
    int flags=SAVESTATE_RTC|(linked?SAVESTATE_SAVEDATA:0);
    struct VFile* file=VFileMemChunk(NULL,0);int ok=file&&mCoreSaveStateNamed(core,file,flags);
    gba->sio.driver=saved;
    if(!ok){if(file)file->close(file);return NULL;}
    ssize_t count=file->size(file);if(count<1||count>4*1024*1024){file->close(file);return NULL;}
    uint8_t* bytes=malloc(count);if(!bytes){file->close(file);return NULL;}
    file->seek(file,0,SEEK_SET);if(file->read(file,bytes,count)!=count){free(bytes);bytes=NULL;}else *size=count;
    file->close(file);return bytes;
}
static int load_blob(unsigned endpoint,const uint8_t* bytes,size_t size,int linked){
    struct mCore* core=endpoints[endpoint].core;struct GBA* gba=core->board;struct GBASIODriver* saved=gba->sio.driver;
    if(!linked)gba->sio.driver=NULL;
    struct VFile* file=VFileFromConstMemory(bytes,size);
    int ok=file&&mCoreLoadStateNamed(core,file,SAVESTATE_SAVEDATA|SAVESTATE_RTC);
    if(file)file->close(file);gba->sio.driver=saved;
    if(ok&&!linked){saved->reset(saved);saved->setMode(saved,gba->sio.mode);}
    return ok;
}
EXPORT size_t muxi_gba_link_export(unsigned endpoint,uint8_t* output,size_t capacity){
    size_t core_size=0;uint8_t* core=blob(endpoint,0,&core_size);if(!core)return 0;
    void* sram=NULL;size_t sram_size=endpoints[endpoint].core->savedataClone(endpoints[endpoint].core,&sram);
    if(sram_size>128*1024){free(core);free(sram);return 0;}
    size_t total=16+core_size+sram_size;
    if(output&&capacity>=total){be32(output,0x4d585331);be32(output+4,core_size);memcpy(output+8,core,core_size);be32(output+8+core_size,sram_size);if(sram_size)memcpy(output+12+core_size,sram,sram_size);be32(output+12+core_size+sram_size,0);}
    free(core);free(sram);return total;
}
EXPORT size_t muxi_gba_link_group_export(uint8_t* output,size_t capacity){
    size_t lengths[2]={0,0};uint8_t* data[2]={blob(0,1,&lengths[0]),blob(1,1,&lengths[1])};
    if(!data[0]||!data[1]){free(data[0]);free(data[1]);return 0;}
    size_t total=12+lengths[0]+lengths[1];
    if(output&&capacity>=total){be32(output,0x4d474c31);be32(output+4,lengths[0]);memcpy(output+8,data[0],lengths[0]);be32(output+8+lengths[0],lengths[1]);memcpy(output+12+lengths[0],data[1],lengths[1]);}
    free(data[0]);free(data[1]);return total;
}
static int apply_group(const uint8_t* data,size_t size){
    if(!opened||size<12||read32(data)!=0x4d474c31)return 0;size_t left=read32(data+4);
    if(left<1||left>4*1024*1024||left>size-12)return 0;size_t right=read32(data+8+left);
    if(right<1||right>4*1024*1024||right!=size-12-left)return 0;
    return load_blob(0,data+8,left,1)&&load_blob(1,data+12+left,right,1);
}
EXPORT int muxi_gba_link_group_restore(const uint8_t* data,size_t size){
    if(!opened||!data||size>8*1024*1024)return 0;size_t n=muxi_gba_link_group_export(NULL,0);if(!n)return 0;uint8_t* before=malloc(n);if(!before)return 0;
    muxi_gba_link_group_export(before,n);int ok=apply_group(data,size);if(!ok&&!apply_group(before,n))muxi_gba_link_close();free(before);return ok;
}
EXPORT int muxi_gba_link_restore(unsigned endpoint,const uint8_t* data,size_t size){
    if(!opened||endpoint>1||!data||size<16||size>4*1024*1024||read32(data)!=0x4d585331)return 0;
    size_t core=read32(data+4);if(core<1||core>size-16)return 0;size_t sram=read32(data+8+core);if(sram>128*1024||sram!=size-16-core||read32(data+12+core+sram)!=0)return 0;
    size_t n=muxi_gba_link_group_export(NULL,0);if(!n)return 0;uint8_t* before=malloc(n);if(!before)return 0;muxi_gba_link_group_export(before,n);
    int ok=load_blob(endpoint,data+8,core,0);
    if(ok){
        void* actual=NULL;size_t expected=endpoints[endpoint].core->savedataClone(endpoints[endpoint].core,&actual);free(actual);
        const struct GBA* gba=endpoints[endpoint].core->board;
        // libretro exposes a full 128 KiB scratch region before a title detects its actual save hardware.
        int autodetect=gba->memory.savedata.type==GBA_SAVEDATA_AUTODETECT;
        if(sram!=expected&&!(autodetect&&(sram==0||sram==128*1024)))ok=0;
    }
    if(ok&&sram)ok=endpoints[endpoint].core->savedataRestore(endpoints[endpoint].core,data+12+core,sram,false);
    if(!ok&&!apply_group(before,n))muxi_gba_link_close();free(before);return ok;
}

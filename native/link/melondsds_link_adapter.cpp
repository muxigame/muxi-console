/* Original bounded MP frontend snapshot ABI, MIT. The linked melonDS DS frontend remains GPL-3.0-or-later.
 * No callback address or host path is serialized. The two tiny access declarations are supplied as explicit source patches. */
#include "core/core.hpp"
#include <cstdint>
#include <cstring>
#include <queue>
#include <vector>

namespace MelonDsDs {
extern CoreState& Core;
struct MuxiMpSnapshot {
    static constexpr size_t MaxBytes=16*1024*1024,MaxPackets=256;
    struct Writer {
        std::vector<uint8_t> bytes;
        void number(uint64_t value,unsigned width){for(unsigned i=width;i-->0;)bytes.push_back(uint8_t(value>>(i*8)));}
        void payload(const void* data,size_t size){auto ptr=static_cast<const uint8_t*>(data);bytes.insert(bytes.end(),ptr,ptr+size);}
    };
    struct Reader {
        const uint8_t* bytes;size_t size,offset=0;
        bool number(uint64_t& value,unsigned width){if(width>size-offset)return false;value=0;for(unsigned i=0;i<width;i++)value=(value<<8)|bytes[offset++];return true;}
    };
    static std::vector<uint8_t> save(const MpState& state){
        if(!state.IsReady()||state.receivedPackets.size()>MaxPackets)return {};
        Writer out;out.number(0x444d5031,4);out.number(1,2);out.number(state._warnedHighLatency?1:0,1);out.number(state._timeoutCount,4);out.number(state._hostId.has_value()?1:0,1);out.number(state._hostId.value_or(0),2);out.number(state.receivedPackets.size(),4);
        auto packets=state.receivedPackets;
        while(!packets.empty()){
            const auto& packet=packets.front();size_t length=packet.Length();if(length>65526||out.bytes.size()+length+14>MaxBytes)return {};
            out.number(packet.Timestamp(),8);out.number(packet.Aid(),1);out.number(static_cast<unsigned>(packet.PacketType()),1);out.number(length,4);out.payload(packet.Data(),length);packets.pop();
        }
        return std::move(out.bytes);
    }
    static bool restore(MpState& state,const uint8_t* bytes,size_t size){
        if(!state.IsReady()||!bytes||size<18||size>MaxBytes)return false;Reader in{bytes,size};uint64_t magic,version,warned,timeouts,hosted,host,count;
        if(!in.number(magic,4)||magic!=0x444d5031||!in.number(version,2)||version!=1||!in.number(warned,1)||warned>1||!in.number(timeouts,4)||timeouts>1000000||!in.number(hosted,1)||hosted>1||!in.number(host,2)||host>1||!in.number(count,4)||count>MaxPackets)return false;
        std::queue<Packet> restored;
        for(uint64_t i=0;i<count;i++){
            uint64_t timestamp,aid,type,length;if(!in.number(timestamp,8)||!in.number(aid,1)||!in.number(type,1)||type>2||!in.number(length,4)||length>65526||length>in.size-in.offset)return false;
            restored.emplace(in.bytes+in.offset,length,timestamp,static_cast<uint8_t>(aid),static_cast<Packet::Type>(type));in.offset+=length;
        }
        if(in.offset!=in.size)return false;
        // Commit only after all bounds and packet data have parsed successfully.
        state.receivedPackets.swap(restored);state._warnedHighLatency=warned!=0;state._timeoutCount=static_cast<int>(timeouts);state._hostId=hosted?std::optional<uint16_t>(static_cast<uint16_t>(host)):std::nullopt;return true;
    }
};
}

#if defined(_WIN32)
#define EXPORT extern "C" __declspec(dllexport)
#else
#define EXPORT extern "C" __attribute__((visibility("default")))
#endif
EXPORT unsigned muxi_ds_link_abi(){return 1;}
EXPORT size_t muxi_ds_mp_save(void* output,size_t capacity){try{auto bytes=MelonDsDs::MuxiMpSnapshot::save(MelonDsDs::Core.MuxiMpState());if(output&&capacity>=bytes.size())std::memcpy(output,bytes.data(),bytes.size());return bytes.size();}catch(...){return 0;}}
EXPORT int muxi_ds_mp_restore(const void* bytes,size_t size){try{return MelonDsDs::MuxiMpSnapshot::restore(MelonDsDs::Core.MuxiMpState(),static_cast<const uint8_t*>(bytes),size)?1:0;}catch(...){return 0;}}

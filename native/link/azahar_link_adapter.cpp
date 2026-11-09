// SPDX-License-Identifier: MIT
// Original bounded C ABI over the separately licensed Azahar network frontend.
// The linked Azahar core and source modifications retain GPL-2.0-or-later.
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include "network/network.h"

#if defined(_WIN32)
#define MUXI_EXPORT extern "C" __declspec(dllexport)
#else
#define MUXI_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {
std::mutex lifecycle;
std::atomic<std::uint32_t> initialized{}, room_open{}, member_state{}, last_error{};
std::atomic<std::uint64_t> tx_packets{}, tx_bytes{}, rx_packets{}, rx_bytes{}, tx_data{}, rx_data{};
Network::RoomMember::CallbackHandle<Network::RoomMember::State> state_callback;
Network::RoomMember::CallbackHandle<Network::RoomMember::Error> error_callback;
Network::RoomMember::CallbackHandle<Network::WifiPacket> wifi_callback;

bool bounded(const char* text, std::size_t minimum, std::size_t maximum) {
    if (!text) return false;
    std::size_t length = 0;
    while (length <= maximum && text[length]) ++length;
    return length >= minimum && length <= maximum;
}
bool hex_id(const char* text) {
    if (!bounded(text, 64, 64)) return false;
    for (std::size_t i = 0; i < 64; ++i)
        if (!((text[i] >= '0' && text[i] <= '9') || (text[i] >= 'a' && text[i] <= 'f')))
            return false;
    return true;
}
// Shutdown is called after Core::System::Shutdown, so NWM has unbound its own callback.
void shutdown_locked() {
    if (!initialized) return;
    if (auto member = Network::GetRoomMember().lock()) {
        member->Unbind(state_callback);
        member->Unbind(error_callback);
        member->Unbind(wifi_callback);
    }
    state_callback.reset(); error_callback.reset(); wifi_callback.reset();
    Network::Shutdown();
    initialized = 0; room_open = 0; member_state = 0;
}
}

// All fields are native-endian unsigned integers. Caller must pass sizeof(StatusV1).
struct StatusV1 {
    std::uint32_t size, abi, initialized, room_open, member_state, last_error;
    std::uint64_t tx_packets, tx_bytes, rx_packets, rx_bytes, tx_data_packets, rx_data_packets;
};
static_assert(sizeof(StatusV1) == 72);

// Only this separately fingerprinted patched build serializes native paths as relocatable placeholders.
// This capability does not make old unbound official-core snapshots safe to restore.
MUXI_EXPORT std::uint32_t muxi_azahar_profile_state_abi() noexcept { return 1; }

MUXI_EXPORT int muxi_azahar_link_init() noexcept {
    std::lock_guard lock(lifecycle);
    if (initialized) return 1;
    try {
        if (!Network::Init()) return 0;
        initialized = 1;
        auto member = Network::GetRoomMember().lock();
        if (!member) { shutdown_locked(); return 0; }
        state_callback = member->BindOnStateChanged([](const auto& state) {
            member_state = static_cast<std::uint32_t>(state);
        });
        error_callback = member->BindOnError([](const auto& error) {
            last_error = 1 + static_cast<std::uint32_t>(error);
        });
        wifi_callback = member->BindOnWifiPacketReceived([](const Network::WifiPacket& packet) {
            ++rx_packets; rx_bytes += packet.data.size();
            if (packet.type == Network::WifiPacket::PacketType::Data) ++rx_data;
        });
        member_state = static_cast<std::uint32_t>(member->GetState());
        return 1;
    } catch (...) { shutdown_locked(); return 0; }
}

MUXI_EXPORT int muxi_azahar_link_host(std::uint16_t port, const char* password) noexcept {
    std::lock_guard lock(lifecycle);
    if (!initialized || port < 1024 || !bounded(password, 16, 64) || room_open) return 0;
    try {
        auto room = Network::GetRoom().lock();
        if (!room || !room->Create("Private Console room", "", "127.0.0.1", port,
                                   password, 2, "", "", 0,
                                   std::make_unique<Network::VerifyUser::NullBackend>())) return 0;
        room_open = 1; return 1;
    } catch (...) { return 0; }
}

MUXI_EXPORT int muxi_azahar_link_join(std::uint16_t port, const char* nickname,
                                    const char* console_id, const std::uint8_t* mac,
                                    const char* password) noexcept {
    std::lock_guard lock(lifecycle);
    if (!initialized || port < 1024 || !bounded(nickname, 4, 20) || !hex_id(console_id) ||
        !mac || (mac[0] & 1) || !bounded(password, 16, 64)) return 0;
    for (std::size_t i = 0; nickname[i]; ++i) {
        const auto c = static_cast<unsigned char>(nickname[i]);
        if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') &&
            !(c >= '0' && c <= '9') && c != '_' && c != '-') return 0;
    }
    try {
        auto member = Network::GetRoomMember().lock();
        if (!member || member->GetState() == Network::RoomMember::State::Joining ||
            member->IsConnected()) return 0;
        Network::MacAddress address{};
        std::memcpy(address.data(), mac, address.size());
        last_error = 0;
        // Upstream has a fixed 5000 ms ENet connection deadline. Completion is asynchronous.
        member->Join(nickname, console_id, "127.0.0.1", port, 0, address, password);
        return member->GetState() == Network::RoomMember::State::Joining ||
               member->IsConnected() ? 1 : 0;
    } catch (...) { return 0; }
}

MUXI_EXPORT int muxi_azahar_link_status(void* output, std::size_t capacity) noexcept {
    if (!output || capacity != sizeof(StatusV1)) return 0;
    const StatusV1 status{sizeof(StatusV1), 1, initialized.load(), room_open.load(),
        member_state.load(), last_error.load(), tx_packets.load(), tx_bytes.load(),
        rx_packets.load(), rx_bytes.load(), tx_data.load(), rx_data.load()};
    std::memcpy(output, &status, sizeof(status)); return 1;
}

// Called exclusively by the real RoomMember::SendWifiPacket path in the patched frontend.
MUXI_EXPORT void muxi_azahar_link_wifi_sent(std::uint32_t type, std::size_t bytes) noexcept {
    ++tx_packets; tx_bytes += bytes;
    if (type == static_cast<std::uint32_t>(Network::WifiPacket::PacketType::Data)) ++tx_data;
}

MUXI_EXPORT void muxi_azahar_link_leave() noexcept {
    std::lock_guard lock(lifecycle);
    try {
        if (auto member = Network::GetRoomMember().lock(); member && member->IsConnected())
            member->Leave();
        if (auto room = Network::GetRoom().lock(); room && room_open) room->Destroy();
        room_open = 0;
    } catch (...) { last_error = 0xffffffffu; }
}

MUXI_EXPORT void muxi_azahar_link_shutdown() noexcept {
    std::lock_guard lock(lifecycle);
    try { shutdown_locked(); } catch (...) { last_error = 0xffffffffu; }
}

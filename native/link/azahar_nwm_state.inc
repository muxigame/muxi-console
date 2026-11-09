// SPDX-License-Identifier: GPL-2.0-or-later
// Complete bounded NWM state for the separately fingerprinted Azahar radio ABI 2.
// Included inside NWM_UDS::serialize. The frontend holds RoomMember's snapshot
// callback lock before serializing any Core/Kernel object. No ENet queue is saved.
constexpr u32 MaxNodes = 16;
constexpr u32 MaxPackets = 1024;
constexpr u32 MaxPacketBytes = 65536;
constexpr u64 MaxQueuedBytes = 16 * 1024 * 1024;
auto count = [&ar](u32& value, u32 maximum) {
    ar & value;
    if (value > maximum)
        throw std::runtime_error("NWM snapshot collection exceeds its bound");
};
auto pod = [&ar](auto& value) {
    using T = std::remove_reference_t<decltype(value)>;
    static_assert(std::is_trivially_copyable_v<T>);
    ar & boost::serialization::make_array(reinterpret_cast<u8*>(&value), sizeof(T));
};
u64 queued_bytes = 0;
auto bytes = [&ar, &count, &queued_bytes](std::vector<u8>& value) {
    u32 length = Archive::is_saving::value ? static_cast<u32>(value.size()) : 0;
    if (Archive::is_saving::value && value.size() > MaxPacketBytes)
        throw std::runtime_error("NWM snapshot packet exceeds its bound");
    count(length, MaxPacketBytes);
    queued_bytes += length;
    if (queued_bytes > MaxQueuedBytes)
        throw std::runtime_error("NWM snapshot packet budget exceeded");
    if constexpr (Archive::is_loading::value)
        value.resize(length);
    if (length)
        ar & boost::serialization::make_array(value.data(), length);
};

DEBUG_SERIALIZATION_POINT;
ar & boost::serialization::base_object<Kernel::SessionRequestHandler>(*this);
ar & connection_status_event;
ar & recv_buffer_memory;
pod(connection_status);
bool ready = initialized.load();
ar & ready;
if constexpr (Archive::is_loading::value)
    initialized.store(ready);
pod(current_node);
ar & conn_type;
ar & network_channel;
pod(network_info);

u32 nodes = Archive::is_saving::value ? static_cast<u32>(node_info.size()) : 0;
count(nodes, MaxNodes);
if constexpr (Archive::is_loading::value)
    node_info.resize(nodes);
for (auto& node : node_info)
    pod(node);

u32 mapped = Archive::is_saving::value ? static_cast<u32>(node_map.size()) : 0;
count(mapped, MaxNodes);
if constexpr (Archive::is_loading::value) {
    node_map.clear();
    for (u32 i = 0; i < mapped; ++i) {
        MacAddress mac{};
        Node node{};
        pod(mac);
        ar & node.connected;
        ar & node.spec;
        ar & node.node_id;
        if (node.node_id > MaxNodes || !node_map.emplace(mac, node).second)
            throw std::runtime_error("Invalid NWM snapshot node map");
    }
} else {
    for (auto& [mac, node] : node_map) {
        auto address = mac;
        pod(address);
        ar & node.connected;
        ar & node.spec;
        ar & node.node_id;
    }
}

u32 binds = Archive::is_saving::value ? static_cast<u32>(channel_data.size()) : 0;
count(binds, MaxNodes);
auto bind = [&](u32& channel, BindNodeData& value) {
    ar & channel;
    ar & value.bind_node_id;
    ar & value.channel;
    ar & value.network_node_id;
    ar & value.event;
    u32 packets = Archive::is_saving::value ? static_cast<u32>(value.received_packets.size()) : 0;
    count(packets, MaxPackets);
    if constexpr (Archive::is_loading::value)
        value.received_packets.resize(packets);
    for (auto& packet : value.received_packets)
        bytes(packet);
};
if constexpr (Archive::is_loading::value) {
    channel_data.clear();
    for (u32 i = 0; i < binds; ++i) {
        u32 channel{};
        BindNodeData value{};
        bind(channel, value);
        if (channel != value.channel || !channel_data.emplace(channel, std::move(value)).second)
            throw std::runtime_error("Invalid NWM snapshot bind map");
    }
} else {
    for (auto& [key, value] : channel_data) {
        u32 channel = key;
        bind(channel, value);
    }
}

ar & connection_event;
u32 beacons = Archive::is_saving::value ? static_cast<u32>(received_beacons.size()) : 0;
count(beacons, 15);
if constexpr (Archive::is_loading::value)
    received_beacons.resize(beacons);
for (auto& beacon : received_beacons) {
    ar & beacon.type;
    bytes(beacon.data);
    pod(beacon.transmitter_address);
    pod(beacon.destination_address);
    ar & beacon.channel;
}

// CoreTiming is serialized before NWM. Preserve one pending event reference for
// every already-scheduled dispatch; do not signal it early or schedule it twice.
// Callback delivery is paused, and no emulation frame runs inside this scope.
u32 pending = Archive::is_saving::value ? static_cast<u32>(pending_async_event_signals.Size()) : 0;
count(pending, MaxPackets);
if constexpr (Archive::is_loading::value) {
    pending_async_event_signals.Clear();
    for (u32 i = 0; i < pending; ++i) {
        std::shared_ptr<Kernel::Event> event;
        ar & event;
        if (!event)
            throw std::runtime_error("Null NWM snapshot pending event");
        pending_async_event_signals.Push(event);
    }
} else {
    std::vector<std::shared_ptr<Kernel::Event>> events;
    events.reserve(pending);
    for (u32 i = 0; i < pending; ++i) {
        std::shared_ptr<Kernel::Event> event;
        if (!pending_async_event_signals.Pop(event) || !event)
            throw std::runtime_error("NWM snapshot pending event changed");
        events.push_back(event);
    }
    // Restore the original order before touching the archive; an archive
    // exception must not reorder or consume the live guest's pending signals.
    for (const auto& event : events)
        pending_async_event_signals.Push(event);
    for (auto& event : events)
        ar & event;
}

if (ready && (connection_status.total_nodes > MaxNodes ||
              connection_status.max_nodes > MaxNodes ||
              connection_status.network_node_id > MaxNodes ||
              network_info.total_nodes > MaxNodes || network_info.max_nodes > MaxNodes))
    throw std::runtime_error("Invalid NWM snapshot network size");
// Callback handles, mutexes and timing-function pointers are constructor-owned.
